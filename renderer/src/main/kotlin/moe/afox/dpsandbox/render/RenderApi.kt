package moe.afox.dpsandbox.render

import moe.afox.dpsandbox.core.DatapackSandbox
import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.Position
import moe.afox.dpsandbox.core.SandboxException
import java.nio.file.Files
import java.nio.file.Path

/** Asset inputs used to resolve Minecraft block models and textures. */
data class RenderAssets(
    val minecraftAssets: Path? = null,
    val resourcePacks: List<Path> = emptyList(),
    val playerSkins: Map<String, Path> = emptyMap(),
) {
    init {
        (listOfNotNull(minecraftAssets) + resourcePacks + playerSkins.values).forEach { path ->
            if (!Files.exists(path)) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Render asset path does not exist: $path")
            }
        }
    }

    companion object {
        /** Empty asset set. Missing models use deterministic procedural fallbacks. */
        @JvmField
        val EMPTY = RenderAssets()
    }
}

/** Camera selection for a rendered frame. */
sealed interface RenderCamera {
    /** Use a player's eye position and rotation. */
    data class Player(
        val name: String,
    ) : RenderCamera

    /** Use an entity's position and rotation. */
    data class Entity(
        val uuid: String,
    ) : RenderCamera

    /** Use an explicit camera transform. */
    data class Fixed(
        val position: Position,
        val yaw: Double,
        val pitch: Double,
        val dimension: String = "minecraft:overworld",
    ) : RenderCamera

    /** Choose a deterministic overview camera from the visible scene bounds. */
    data object Auto : RenderCamera
}

/** Immutable rendering options. */
data class RenderRequest
    @JvmOverloads
    constructor(
        val width: Int = 1280,
        val height: Int = 720,
        val camera: RenderCamera = RenderCamera.Auto,
        val fieldOfViewDegrees: Double = 70.0,
        val nearPlane: Double = 0.05,
        val renderDistance: Double = 128.0,
        val transparentBackground: Boolean = false,
        val showHud: Boolean = false,
        val showDebugOverlay: Boolean = false,
        val strictAssets: Boolean = false,
    ) {
        init {
            require(width in MIN_IMAGE_SIZE..MAX_IMAGE_SIZE) {
                "Render width must be between $MIN_IMAGE_SIZE and $MAX_IMAGE_SIZE: $width"
            }
            require(height in MIN_IMAGE_SIZE..MAX_IMAGE_SIZE) {
                "Render height must be between $MIN_IMAGE_SIZE and $MAX_IMAGE_SIZE: $height"
            }
            require(fieldOfViewDegrees in 10.0..150.0) { "Render field of view must be between 10 and 150 degrees" }
            require(nearPlane.isFinite() && nearPlane > 0.0) { "Render near plane must be positive and finite" }
            require(renderDistance.isFinite() && renderDistance > nearPlane) {
                "Render distance must be finite and greater than the near plane"
            }
        }

        companion object {
            const val MIN_IMAGE_SIZE = 64
            const val MAX_IMAGE_SIZE = 8192
        }
    }

/** Severity of an asset or scene rendering diagnostic. */
enum class RenderDiagnosticSeverity {
    INFO,
    WARNING,
    ERROR,
}

/** Structured diagnostic produced without mutating the sandbox diagnostic stream. */
data class RenderDiagnostic(
    val severity: RenderDiagnosticSeverity,
    val code: String,
    val message: String,
    val resource: String? = null,
)

/** Metadata describing how a frame was produced. */
data class RenderMetadata(
    val width: Int,
    val height: Int,
    val cameraDescription: String,
    val dimension: String,
    val visibleBlocks: Int,
    val visibleEntities: Int,
    val triangles: Int,
    val assetSources: List<String>,
    val diagnostics: List<RenderDiagnostic>,
    val worldCaptureNanos: Long,
    val assetResolveNanos: Long,
    val sceneBuildNanos: Long,
    val rasterizeNanos: Long,
    val pngEncodeNanos: Long,
    val renderNanos: Long,
    val lightingModel: String = "approximate",
    val visualParity: Boolean = false,
)

/** A PNG frame and its deterministic rendering metadata. */
class RenderedFrame internal constructor(
    pngBytes: ByteArray,
    rgbaBytes: ByteArray,
    val metadata: RenderMetadata,
) {
    internal constructor(
        pngBytes: ByteArray,
        metadata: RenderMetadata,
    ) : this(pngBytes, PngWriter.decodeRgba(pngBytes), metadata)

    private val bytes = pngBytes.copyOf()
    private val rgba = rgbaBytes.copyOf()

    /** Returns a defensive copy of the PNG bytes. */
    fun pngBytes(): ByteArray = bytes.copyOf()

    internal fun rgbaBytes(): ByteArray = rgba.copyOf()

    /** Writes the PNG, creating its parent directory when necessary. */
    fun writePng(path: Path) {
        val absolute = path.toAbsolutePath().normalize()
        absolute.parent?.let(Files::createDirectories)
        Files.write(absolute, bytes)
    }
}

/** Public, reusable entry point for clean-room sandbox rendering. */
class SandboxRenderer
    @JvmOverloads
    constructor(
        val assets: RenderAssets = RenderAssets.EMPTY,
    ) {
        private val sharedAssetBytes = mutableMapOf<String, ByteArray?>()

        /** Captures the current world and returns a PNG without mutating sandbox state. */
        @JvmOverloads
        fun render(
            sandbox: DatapackSandbox,
            request: RenderRequest = RenderRequest(),
        ): RenderedFrame {
            val before = sandbox.snapshotString()
            val started = System.nanoTime()
            val diagnostics = mutableListOf<RenderDiagnostic>()
            val captureStarted = System.nanoTime()
            val view = WorldView.capture(sandbox)
            val worldCaptureNanos = System.nanoTime() - captureStarted
            val resolver = AssetResolver(assets, diagnostics, request.strictAssets, view.gameTime, sharedAssetBytes)
            val sceneStarted = System.nanoTime()
            val scene = SceneBuilder(resolver, diagnostics).build(view, request)
            val sceneElapsedNanos = System.nanoTime() - sceneStarted
            val rasterizeStarted = System.nanoTime()
            val rasterized = SoftwareRasterizer().render(view, scene, request)
            val rasterizeNanos = System.nanoTime() - rasterizeStarted
            val pngStarted = System.nanoTime()
            val pngBytes = PngWriter.encode(rasterized, request.width, request.height)
            val pngEncodeNanos = System.nanoTime() - pngStarted
            val after = sandbox.snapshotString()
            check(before == after) { "Rendering changed sandbox state" }
            val metadata =
                RenderMetadata(
                    width = request.width,
                    height = request.height,
                    cameraDescription = scene.camera.description,
                    dimension = scene.camera.dimension,
                    visibleBlocks = scene.visibleBlocks,
                    visibleEntities = scene.visibleEntities,
                    triangles = scene.triangles.size,
                    assetSources = resolver.sourceDescriptions,
                    diagnostics = diagnostics.toList(),
                    worldCaptureNanos = worldCaptureNanos,
                    assetResolveNanos = resolver.assetResolveNanos,
                    sceneBuildNanos = (sceneElapsedNanos - resolver.assetResolveNanos).coerceAtLeast(0),
                    rasterizeNanos = rasterizeNanos,
                    pngEncodeNanos = pngEncodeNanos,
                    renderNanos = System.nanoTime() - started,
                )
            return RenderedFrame(pngBytes, argbToRgba(rasterized), metadata)
        }

        private fun argbToRgba(pixels: IntArray): ByteArray {
            val rgba = ByteArray(pixels.size * 4)
            pixels.forEachIndexed { index, color ->
                rgba[index * 4] = (color ushr 16).toByte()
                rgba[index * 4 + 1] = (color ushr 8).toByte()
                rgba[index * 4 + 2] = color.toByte()
                rgba[index * 4 + 3] = (color ushr 24).toByte()
            }
            return rgba
        }
    }
