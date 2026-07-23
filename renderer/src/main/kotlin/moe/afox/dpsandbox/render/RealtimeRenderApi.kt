package moe.afox.dpsandbox.render

import moe.afox.dpsandbox.core.DatapackSandbox
import moe.afox.dpsandbox.core.Position
import java.awt.image.BufferedImage
import kotlin.math.max
import kotlin.math.min

/** Camera state shared by the JVM realtime viewport and embedders. */
data class RealtimeRenderCamera(
    val position: Position,
    val yaw: Double,
    val pitch: Double,
    val dimension: String = "minecraft:overworld",
)

/** Immutable scene whose geometry can be projected repeatedly from a moving camera. */
class CompiledRealtimeScene internal constructor(
    internal val view: WorldView,
    internal val scene: RenderScene,
    /** Camera chosen by the same automatic framing rules as static PNG rendering. */
    val suggestedCamera: RealtimeRenderCamera,
    /** Number of modeled blocks in this scene. */
    val visibleBlocks: Int,
    /** Number of modeled entities in this scene. */
    val visibleEntities: Int,
    /** Number of triangles cached for realtime projection. */
    val triangles: Int,
    /** Minimum scene bound, useful for custom camera controllers. */
    val boundsMinimum: Position,
    /** Maximum scene bound, useful for custom camera controllers. */
    val boundsMaximum: Position,
)

/** One unencoded JVM realtime frame and its render timing. */
data class RealtimeRenderedFrame(
    val image: BufferedImage,
    val renderNanos: Long,
    val triangles: Int,
)

/**
 * JVM realtime projection backend.
 *
 * World capture, models, and textures are compiled only by [compile]. Calling
 * [render] moves the camera over the immutable scene and does not touch the
 * sandbox or encode PNG bytes.
 */
class SandboxRealtimeRenderer(
    val assets: RenderAssets,
) {
    constructor() : this(RenderAssets.EMPTY)

    private val sharedAssetBytes = mutableMapOf<String, ByteArray?>()
    private val gpuCompiler = JvmGpuSceneCompiler(assets, sharedAssetBytes)

    /** Captures and compiles all visible world geometry without camera-frustum culling. */
    @JvmOverloads
    fun compile(
        sandbox: DatapackSandbox,
        request: RenderRequest = RenderRequest(width = 960, height = 540),
    ): CompiledRealtimeScene {
        val before = sandbox.snapshotString()
        val diagnostics = mutableListOf<RenderDiagnostic>()
        val view = WorldView.capture(sandbox)
        val resolver = AssetResolver(assets, diagnostics, request.strictAssets, view.gameTime, sharedAssetBytes)
        val scene = SceneBuilder(resolver, diagnostics).build(view, request, cull = false)
        check(before == sandbox.snapshotString()) { "Realtime scene compilation changed sandbox state" }
        val bounds = sceneBounds(scene)
        return CompiledRealtimeScene(
            view = view,
            scene = scene,
            suggestedCamera = scene.camera.toRealtimeCamera(),
            visibleBlocks = scene.visibleBlocks,
            visibleEntities = scene.visibleEntities,
            triangles = scene.triangles.size,
            boundsMinimum = bounds.first,
            boundsMaximum = bounds.second,
        )
    }

    /** Compiles transferable-style vertex, index, material, and atlas buffers for a JVM GPU backend. */
    @JvmOverloads
    fun compileGpu(
        sandbox: DatapackSandbox,
        request: RenderRequest = RenderRequest(width = 960, height = 540),
    ): CompiledRealtimeGpuScene = gpuCompiler.compile(sandbox, request)

    /** Projects an already compiled scene from [camera] into an unencoded ARGB image. */
    @JvmOverloads
    fun render(
        scene: CompiledRealtimeScene,
        camera: RealtimeRenderCamera = scene.suggestedCamera,
        width: Int = 960,
        height: Int = 540,
        fieldOfViewDegrees: Double = 70.0,
        renderDistance: Double = 128.0,
        showHud: Boolean = true,
    ): RealtimeRenderedFrame {
        val request =
            RenderRequest(
                width = width,
                height = height,
                camera = RenderCamera.Fixed(camera.position, camera.yaw, camera.pitch, camera.dimension),
                fieldOfViewDegrees = fieldOfViewDegrees,
                renderDistance = renderDistance,
                showHud = showHud,
            )
        val resolved =
            ResolvedCamera(
                position = Vec3(camera.position.x, camera.position.y, camera.position.z),
                yaw = camera.yaw,
                pitch = camera.pitch,
                dimension = camera.dimension,
                description = "realtime JVM camera",
            )
        val started = System.nanoTime()
        val pixels = SoftwareRasterizer().render(scene.view, scene.scene.copy(camera = resolved), request)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, width, height, pixels, 0, width)
        return RealtimeRenderedFrame(image, System.nanoTime() - started, scene.triangles)
    }

    private fun sceneBounds(scene: RenderScene): Pair<Position, Position> {
        if (scene.triangles.isEmpty()) {
            val position = scene.camera.position
            return Position(position.x - 1.0, position.y - 1.0, position.z - 1.0) to
                Position(position.x + 1.0, position.y + 1.0, position.z + 1.0)
        }
        var minimum = Vec3(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)
        var maximum = Vec3(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY)
        scene.triangles.forEach { triangle ->
            listOf(triangle.a, triangle.b, triangle.c).forEach { vertex ->
                minimum = Vec3(min(minimum.x, vertex.position.x), min(minimum.y, vertex.position.y), min(minimum.z, vertex.position.z))
                maximum = Vec3(max(maximum.x, vertex.position.x), max(maximum.y, vertex.position.y), max(maximum.z, vertex.position.z))
            }
        }
        return Position(minimum.x, minimum.y, minimum.z) to Position(maximum.x, maximum.y, maximum.z)
    }

    private fun ResolvedCamera.toRealtimeCamera(): RealtimeRenderCamera =
        RealtimeRenderCamera(Position(position.x, position.y, position.z), yaw, pitch, dimension)
}
