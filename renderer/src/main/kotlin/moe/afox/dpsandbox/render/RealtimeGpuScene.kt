package moe.afox.dpsandbox.render

import moe.afox.dpsandbox.core.DatapackSandbox
import moe.afox.dpsandbox.core.Position
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/** One contiguous draw range in a JVM realtime GPU scene. */
data class RealtimeGpuMaterialBatch(
    val pass: String,
    val indexOffset: Int,
    val indexCount: Int,
    val seeThrough: Boolean,
)

/** Geometry that may be uploaded independently from other scene sections. */
data class RealtimeGpuSceneSection(
    val vertices: FloatArray,
    val indices: IntArray,
    val batches: List<RealtimeGpuMaterialBatch>,
)

/** RGBA texture atlas shared by JVM realtime block and entity geometry. */
data class RealtimeGpuTextureAtlas(
    val width: Int,
    val height: Int,
    val rgba: ByteArray,
)

/** Raster-backend-neutral GPU buffers compiled from the JVM renderer scene. */
data class CompiledRealtimeGpuScene(
    val blocks: RealtimeGpuSceneSection,
    val entities: RealtimeGpuSceneSection,
    val atlas: RealtimeGpuTextureAtlas,
    val suggestedCamera: RealtimeRenderCamera,
    val boundsMinimum: Position,
    val boundsMaximum: Position,
    val visibleBlocks: Int,
    val visibleEntities: Int,
    val gameTime: Long,
    val vertexStride: Int = FLOATS_PER_VERTEX,
) {
    companion object {
        const val FLOATS_PER_VERTEX = 12
    }
}

internal class JvmGpuSceneCompiler(
    private val assets: RenderAssets,
    private val sharedAssetBytes: MutableMap<String, ByteArray?>,
) {
    private var cachedBlockKey: Int? = null
    private var cachedBlockTriangles: List<SceneTriangle> = emptyList()
    private var cachedBlockSectionKey: Int? = null
    private var cachedBlockSection: RealtimeGpuSceneSection? = null

    fun compile(
        sandbox: DatapackSandbox,
        request: RenderRequest,
    ): CompiledRealtimeGpuScene {
        val before = sandbox.snapshotString()
        val diagnostics = mutableListOf<RenderDiagnostic>()
        val view = WorldView.capture(sandbox)
        val resolver = AssetResolver(assets, diagnostics, request.strictAssets, view.gameTime, sharedAssetBytes)
        val builder = SceneBuilder(resolver, diagnostics)
        val camera = builder.build(view, request).camera
        val fixedRequest =
            request.copy(
                camera =
                    RenderCamera.Fixed(
                        Position(camera.position.x, camera.position.y, camera.position.z),
                        camera.yaw,
                        camera.pitch,
                        camera.dimension,
                    ),
            )
        val blockKey = 31 * view.blocks.hashCode() + 17 * view.biomes.hashCode() + view.seed.hashCode()
        val blockTriangles =
            if (cachedBlockKey == blockKey) {
                cachedBlockTriangles
            } else {
                builder
                    .build(view.copy(entities = emptyList()), fixedRequest, cull = false)
                    .triangles
                    .also { triangles ->
                        cachedBlockKey = blockKey
                        cachedBlockTriangles = triangles
                        cachedBlockSectionKey = null
                        cachedBlockSection = null
                    }
            }
        val entityScene = builder.build(view.copy(blocks = emptyList()), fixedRequest, cull = false)
        val atlas = buildAtlas(blockTriangles + entityScene.triangles)
        val blockSectionKey = 31 * blockKey + atlas.geometryKey
        val blockSection =
            if (cachedBlockSectionKey == blockSectionKey) {
                cachedBlockSection ?: compileSection(blockTriangles, atlas)
            } else {
                compileSection(blockTriangles, atlas).also { section ->
                    cachedBlockSectionKey = blockSectionKey
                    cachedBlockSection = section
                }
            }
        check(before == sandbox.snapshotString()) { "GPU scene compilation changed sandbox state" }
        val bounds = worldBounds(view)
        return CompiledRealtimeGpuScene(
            blocks = blockSection,
            entities = compileSection(entityScene.triangles, atlas),
            atlas = atlas.texture,
            suggestedCamera =
                RealtimeRenderCamera(
                    Position(camera.position.x, camera.position.y, camera.position.z),
                    camera.yaw,
                    camera.pitch,
                    camera.dimension,
                ),
            boundsMinimum = bounds.first,
            boundsMaximum = bounds.second,
            visibleBlocks = view.blocks.size,
            visibleEntities = entityScene.visibleEntities,
            gameTime = view.gameTime,
        )
    }

    private fun compileSection(
        source: List<SceneTriangle>,
        atlas: AtlasLayout,
    ): RealtimeGpuSceneSection {
        val ordered = source.sortedWith(compareBy<SceneTriangle>({ passOrder(it) }, { it.seeThrough }, { it.texture.id }))
        val vertices = FloatArray(ordered.size * 3 * CompiledRealtimeGpuScene.FLOATS_PER_VERTEX)
        val indices = IntArray(ordered.size * 3)
        val batches = mutableListOf<RealtimeGpuMaterialBatch>()
        var floatOffset = 0
        var vertexIndex = 0
        var batchPass: String? = null
        var batchSeeThrough = false
        var batchStart = 0
        ordered.forEachIndexed { triangleIndex, triangle ->
            val pass =
                triangle.texture.materialPass.name
                    .lowercase()
            if (pass != batchPass || triangle.seeThrough != batchSeeThrough) {
                batchPass?.let {
                    batches += RealtimeGpuMaterialBatch(it, batchStart, triangleIndex * 3 - batchStart, batchSeeThrough)
                }
                batchPass = pass
                batchSeeThrough = triangle.seeThrough
                batchStart = triangleIndex * 3
            }
            val placement = atlas.placements.getValue(triangle.texture.id)
            val tint = triangle.tint.takeUnless { it == -1 } ?: -1
            listOf(triangle.a, triangle.b, triangle.c).forEach { vertex ->
                val u =
                    (placement.x + 0.5 + atlasCoordinate(vertex.uv.x) * (placement.width - 1).coerceAtLeast(0)) /
                        atlas.texture.width
                val v =
                    (placement.y + 0.5 + atlasCoordinate(vertex.uv.y) * (placement.height - 1).coerceAtLeast(0)) /
                        atlas.texture.height
                vertices[floatOffset++] = vertex.position.x.toFloat()
                vertices[floatOffset++] = vertex.position.y.toFloat()
                vertices[floatOffset++] = vertex.position.z.toFloat()
                vertices[floatOffset++] = u.toFloat()
                vertices[floatOffset++] = v.toFloat()
                vertices[floatOffset++] = triangle.normal.x.toFloat()
                vertices[floatOffset++] = triangle.normal.y.toFloat()
                vertices[floatOffset++] = triangle.normal.z.toFloat()
                vertices[floatOffset++] = (tint ushr 16 and 0xff) / 255f
                vertices[floatOffset++] = (tint ushr 8 and 0xff) / 255f
                vertices[floatOffset++] = (tint and 0xff) / 255f
                vertices[floatOffset++] = if (triangle.emissive) 1f else 0f
                indices[vertexIndex] = vertexIndex
                vertexIndex += 1
            }
        }
        batchPass?.let { batches += RealtimeGpuMaterialBatch(it, batchStart, indices.size - batchStart, batchSeeThrough) }
        return RealtimeGpuSceneSection(vertices, indices, batches)
    }

    private fun buildAtlas(triangles: List<SceneTriangle>): AtlasLayout {
        val textures = linkedMapOf<String, TextureData>()
        triangles.forEach { triangle -> textures.putIfAbsent(triangle.texture.id, triangle.texture) }
        if (textures.isEmpty()) {
            return AtlasLayout(
                RealtimeGpuTextureAtlas(1, 1, byteArrayOf(-1, -1, -1, -1)),
                emptyMap(),
                1,
            )
        }
        val area = textures.values.sumOf { (it.width + PADDING) * (it.height + PADDING) }
        val widest = textures.values.maxOf { it.width + PADDING }
        val atlasWidth = nextPowerOfTwo(max(widest, ceil(sqrt(area.toDouble())).toInt()).coerceAtMost(MAX_ATLAS_WIDTH))
        val placements = linkedMapOf<String, Placement>()
        var x = PADDING
        var y = PADDING
        var rowHeight = 0
        textures.values.forEach { texture ->
            if (x + texture.width + PADDING > atlasWidth) {
                x = PADDING
                y += rowHeight + PADDING
                rowHeight = 0
            }
            placements[texture.id] = Placement(x, y, texture.width, texture.height)
            x += texture.width + PADDING
            rowHeight = max(rowHeight, texture.height)
        }
        val atlasHeight = nextPowerOfTwo(y + rowHeight + PADDING)
        val rgba = ByteArray(atlasWidth * atlasHeight * 4)
        textures.values.forEach { texture ->
            val placement = placements.getValue(texture.id)
            texture.pixels.forEachIndexed { index, argb ->
                val target = ((placement.y + index / texture.width) * atlasWidth + placement.x + index % texture.width) * 4
                rgba[target] = (argb ushr 16 and 0xff).toByte()
                rgba[target + 1] = (argb ushr 8 and 0xff).toByte()
                rgba[target + 2] = (argb and 0xff).toByte()
                rgba[target + 3] = (argb ushr 24 and 0xff).toByte()
            }
        }
        val geometryKey = 31 * atlasWidth + atlasHeight + 31 * placements.hashCode()
        return AtlasLayout(RealtimeGpuTextureAtlas(atlasWidth, atlasHeight, rgba), placements, geometryKey)
    }

    private fun worldBounds(view: WorldView): Pair<Position, Position> {
        val points =
            buildList {
                view.blocks.forEach { block ->
                    add(Position(block.position.x.toDouble(), block.position.y.toDouble(), block.position.z.toDouble()))
                    add(Position(block.position.x + 1.0, block.position.y + 1.0, block.position.z + 1.0))
                }
                view.entities.forEach { add(it.position) }
            }
        if (points.isEmpty()) return Position(-0.5, -0.5, -0.5) to Position(0.5, 0.5, 0.5)
        return Position(points.minOf { it.x }, points.minOf { it.y }, points.minOf { it.z }) to
            Position(points.maxOf { it.x }, points.maxOf { it.y }, points.maxOf { it.z })
    }

    private fun passOrder(triangle: SceneTriangle): Int =
        when (triangle.texture.materialPass) {
            MaterialPass.OPAQUE -> 0
            MaterialPass.CUTOUT -> 1
            MaterialPass.TRANSLUCENT -> 2
        }

    private fun atlasCoordinate(value: Double): Double {
        val wrapped = value - floor(value)
        return if (wrapped == 0.0 && value > 0.0) 1.0 else wrapped
    }

    private fun nextPowerOfTwo(value: Int): Int {
        var result = 1
        while (result < value) result = result shl 1
        return result
    }

    private data class Placement(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )

    private data class AtlasLayout(
        val texture: RealtimeGpuTextureAtlas,
        val placements: Map<String, Placement>,
        val geometryKey: Int,
    )

    companion object {
        private const val PADDING = 1
        private const val MAX_ATLAS_WIDTH = 2_048
    }
}
