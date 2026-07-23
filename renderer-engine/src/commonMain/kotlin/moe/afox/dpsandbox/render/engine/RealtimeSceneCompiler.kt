package moe.afox.dpsandbox.render.engine

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/** A contiguous material draw range in a realtime scene section. */
data class RealtimeMaterialBatch(
    val pass: String,
    val indexOffset: Int,
    val indexCount: Int,
    val seeThrough: Boolean,
)

/** Geometry that may be replaced independently from the other scene sections. */
data class RealtimeSceneSection(
    val vertices: FloatArray,
    val indices: IntArray,
    val batches: List<RealtimeMaterialBatch>,
)

/** Texture atlas shared by static blocks and entity geometry. */
data class RealtimeTextureAtlas(
    val width: Int,
    val height: Int,
    val rgba: ByteArray,
)

/** Raster-backend-neutral scene compiled for a realtime GPU consumer. */
data class RealtimeRenderScene(
    val blocks: RealtimeSceneSection,
    val entities: RealtimeSceneSection,
    val atlas: RealtimeTextureAtlas,
    val cameraPosition: List<Double>,
    val cameraYaw: Double,
    val cameraPitch: Double,
    val boundsMinimum: List<Double>,
    val boundsMaximum: List<Double>,
    val visibleBlocks: Int,
    val visibleEntities: Int,
    val vertexStride: Int = 19,
)

/**
 * Converts the exact triangles and textures used by the software renderer into
 * indexed WebGL buffers. Camera movement therefore changes uniforms only.
 */
internal class RealtimeSceneCompiler(
    private val textures: EngineTextureStore,
) {
    private var cachedBlockKey: Int? = null
    private var cachedBlockTriangles: List<EngineSceneTriangle> = emptyList()
    private var cachedBlockSectionKey: Int? = null
    private var cachedBlockSection: RealtimeSceneSection? = null
    private var cachedAtlasKey: Int? = null
    private var cachedAtlas: AtlasLayout? = null

    fun invalidate() {
        cachedBlockKey = null
        cachedBlockTriangles = emptyList()
        cachedBlockSectionKey = null
        cachedBlockSection = null
        cachedAtlasKey = null
        cachedAtlas = null
    }

    fun compile(
        world: EngineRenderWorld,
        width: Int,
        height: Int,
    ): RealtimeRenderScene {
        require(width > 0 && height > 0) { "Realtime viewport dimensions must be positive" }
        val builder = EngineSceneBuilder(textures)
        val camera = builder.build(world, width, height).camera
        val blockWorld = world.copy(entities = emptyList(), entityCount = 0)
        val entityWorld = world.copy(blocks = emptyList())
        val blockKey = 31 * world.blocks.hashCode() + world.seed.hashCode()
        val blockTriangles =
            if (cachedBlockKey == blockKey) {
                cachedBlockTriangles
            } else {
                builder.build(blockWorld, width, height, cameraOverride = camera, cull = false).triangles.also { triangles ->
                    cachedBlockKey = blockKey
                    cachedBlockTriangles = triangles
                    cachedBlockSectionKey = null
                    cachedBlockSection = null
                }
            }
        val entityScene = builder.build(entityWorld, width, height, cameraOverride = camera, cull = false)
        val atlasLayout = buildAtlas(blockTriangles + entityScene.triangles)
        val blockSectionKey = 31 * blockKey + atlasLayout.geometryKey
        val blockSection =
            if (cachedBlockSectionKey == blockSectionKey) {
                cachedBlockSection ?: compileSection(blockTriangles, atlasLayout)
            } else {
                compileSection(blockTriangles, atlasLayout).also { section ->
                    cachedBlockSectionKey = blockSectionKey
                    cachedBlockSection = section
                }
            }
        val bounds = bounds(world)
        return RealtimeRenderScene(
            blocks = blockSection,
            entities = compileSection(entityScene.triangles, atlasLayout),
            atlas = atlasLayout.atlas,
            cameraPosition = listOf(camera.position.x, camera.position.y, camera.position.z),
            cameraYaw = camera.yaw,
            cameraPitch = camera.pitch,
            boundsMinimum = bounds.first,
            boundsMaximum = bounds.second,
            visibleBlocks = world.blocks.size.coerceAtMost(SoftwareWorldRenderer.MAX_VISIBLE_BLOCKS),
            visibleEntities = entityScene.visibleEntities,
        )
    }

    private fun compileSection(
        source: List<EngineSceneTriangle>,
        atlas: AtlasLayout,
    ): RealtimeSceneSection {
        val ordered = source.sortedWith(compareBy<EngineSceneTriangle>({ passOrder(it) }, { it.seeThrough }, { it.texture.id }))
        val vertices = FloatArray(ordered.size * 3 * FLOATS_PER_VERTEX)
        val indices = IntArray(ordered.size * 3)
        val batches = mutableListOf<RealtimeMaterialBatch>()
        var floatOffset = 0
        var vertexIndex = 0
        var batchPass: String? = null
        var batchSeeThrough = false
        var batchStart = 0
        ordered.forEachIndexed { triangleIndex, triangle ->
            val pass = triangle.texture.materialPass.name.lowercase()
            if (pass != batchPass || triangle.seeThrough != batchSeeThrough) {
                batchPass?.let { batches += RealtimeMaterialBatch(it, batchStart, triangleIndex * 3 - batchStart, batchSeeThrough) }
                batchPass = pass
                batchSeeThrough = triangle.seeThrough
                batchStart = triangleIndex * 3
            }
            val placement = atlas.placements.getValue(triangle.texture.id)
            val tint = triangle.tint.takeUnless { it == -1 } ?: -1
            listOf(triangle.a, triangle.b, triangle.c).forEach { vertex ->
                val wrappedU = atlasCoordinate(vertex.uv.x)
                val wrappedV = atlasCoordinate(vertex.uv.y)
                val u = (placement.x + 0.5 + wrappedU * (placement.width - 1).coerceAtLeast(0)) / atlas.atlas.width
                val v = (placement.y + 0.5 + wrappedV * (placement.height - 1).coerceAtLeast(0)) / atlas.atlas.height
                vertices[floatOffset++] = vertex.position.x.toFloat()
                vertices[floatOffset++] = vertex.position.y.toFloat()
                vertices[floatOffset++] = vertex.position.z.toFloat()
                vertices[floatOffset++] = u.toFloat()
                vertices[floatOffset++] = v.toFloat()
                vertices[floatOffset++] = triangle.normal.x.toFloat()
                vertices[floatOffset++] = triangle.normal.y.toFloat()
                vertices[floatOffset++] = triangle.normal.z.toFloat()
                vertices[floatOffset++] = ((tint ushr 16 and 0xff) / 255f)
                vertices[floatOffset++] = ((tint ushr 8 and 0xff) / 255f)
                vertices[floatOffset++] = ((tint and 0xff) / 255f)
                vertices[floatOffset++] = if (triangle.emissive) 1f else 0f
                val local = vertex.billboardLocal ?: EngineVec3.ZERO
                val pivot = vertex.billboardPivot ?: EngineVec3.ZERO
                vertices[floatOffset++] = local.x.toFloat()
                vertices[floatOffset++] = local.y.toFloat()
                vertices[floatOffset++] = local.z.toFloat()
                vertices[floatOffset++] = pivot.x.toFloat()
                vertices[floatOffset++] = pivot.y.toFloat()
                vertices[floatOffset++] = pivot.z.toFloat()
                vertices[floatOffset++] = vertex.billboardMode.toFloat()
                indices[vertexIndex] = vertexIndex
                vertexIndex += 1
            }
        }
        batchPass?.let { batches += RealtimeMaterialBatch(it, batchStart, indices.size - batchStart, batchSeeThrough) }
        return RealtimeSceneSection(vertices, indices, batches)
    }

    private fun buildAtlas(triangles: List<EngineSceneTriangle>): AtlasLayout {
        val unique = linkedMapOf<String, EngineTexture>()
        triangles.forEach { triangle ->
            if (triangle.texture.id !in unique) unique[triangle.texture.id] = triangle.texture
        }
        val atlasKey = unique.values.fold(1) { hash, texture ->
            31 * hash + texture.id.hashCode() + 31 * texture.width + 17 * texture.height + texture.pixels.contentHashCode()
        }
        if (cachedAtlasKey == atlasKey) cachedAtlas?.let { return it }
        if (unique.isEmpty()) {
            return AtlasLayout(RealtimeTextureAtlas(1, 1, byteArrayOf(-1, -1, -1, -1)), emptyMap(), 1).also {
                cachedAtlasKey = atlasKey
                cachedAtlas = it
            }
        }
        val area = unique.values.sumOf { (it.width + PADDING) * (it.height + PADDING) }
        val widest = unique.values.maxOf { it.width + PADDING }
        val targetWidth = nextPowerOfTwo(max(widest, ceil(sqrt(area.toDouble())).toInt()).coerceAtMost(MAX_ATLAS_WIDTH))
        val placements = linkedMapOf<String, Placement>()
        var x = PADDING
        var y = PADDING
        var rowHeight = 0
        unique.values.forEach { texture ->
            if (x + texture.width + PADDING > targetWidth) {
                x = PADDING
                y += rowHeight + PADDING
                rowHeight = 0
            }
            placements[texture.id] = Placement(x, y, texture.width, texture.height)
            x += texture.width + PADDING
            rowHeight = max(rowHeight, texture.height)
        }
        val atlasHeight = nextPowerOfTwo(y + rowHeight + PADDING)
        val rgba = ByteArray(targetWidth * atlasHeight * 4)
        unique.values.forEach { texture ->
            val placement = placements.getValue(texture.id)
            texture.pixels.forEachIndexed { index, argb ->
                val sourceX = index % texture.width
                val sourceY = index / texture.width
                val target = ((placement.y + sourceY) * targetWidth + placement.x + sourceX) * 4
                rgba[target] = (argb ushr 16 and 0xff).toByte()
                rgba[target + 1] = (argb ushr 8 and 0xff).toByte()
                rgba[target + 2] = (argb and 0xff).toByte()
                rgba[target + 3] = (argb ushr 24 and 0xff).toByte()
            }
        }
        val geometryKey = 31 * targetWidth + atlasHeight + 31 * placements.hashCode()
        return AtlasLayout(RealtimeTextureAtlas(targetWidth, atlasHeight, rgba), placements, geometryKey).also {
            cachedAtlasKey = atlasKey
            cachedAtlas = it
        }
    }

    private fun bounds(world: EngineRenderWorld): Pair<List<Double>, List<Double>> {
        val points = buildList {
            world.blocks.forEach { block ->
                add(listOf(block.x.toDouble(), block.y.toDouble(), block.z.toDouble()))
                add(listOf(block.x + 1.0, block.y + 1.0, block.z + 1.0))
            }
            world.entities.forEach { entity -> add(listOf(entity.x, entity.y, entity.z)) }
        }
        if (points.isEmpty()) return listOf(-0.5, -0.5, -0.5) to listOf(0.5, 0.5, 0.5)
        return listOf(points.minOf { it[0] }, points.minOf { it[1] }, points.minOf { it[2] }) to
            listOf(points.maxOf { it[0] }, points.maxOf { it[1] }, points.maxOf { it[2] })
    }

    private fun passOrder(triangle: EngineSceneTriangle): Int =
        when (triangle.texture.materialPass) {
            EngineMaterialPass.OPAQUE -> 0
            EngineMaterialPass.CUTOUT -> 1
            EngineMaterialPass.TRANSLUCENT -> 2
        }

    private fun nextPowerOfTwo(value: Int): Int {
        var result = 1
        while (result < value) result = result shl 1
        return result
    }

    private fun atlasCoordinate(value: Double): Double {
        val wrapped = value - floor(value)
        return if (wrapped == 0.0 && value > 0.0) 1.0 else wrapped
    }

    private data class Placement(val x: Int, val y: Int, val width: Int, val height: Int)

    private data class AtlasLayout(
        val atlas: RealtimeTextureAtlas,
        val placements: Map<String, Placement>,
        val geometryKey: Int,
    )

    companion object {
        const val FLOATS_PER_VERTEX = 19
        private const val PADDING = 1
        private const val MAX_ATLAS_WIDTH = 2_048
    }
}
