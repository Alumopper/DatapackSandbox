package moe.afox.dpsandbox.render

import moe.afox.dpsandbox.core.DatapackSandbox
import moe.afox.dpsandbox.render.engine.AnimatedGifEncoder
import moe.afox.dpsandbox.render.engine.EngineGifFrame
import java.nio.file.Files
import java.nio.file.Path

/** One already-rendered frame and its display duration in an animated GIF. */
data class GifAnimationFrame(
    val frame: RenderedFrame,
    val delayMillis: Int = 250,
) {
    init {
        require(delayMillis in 10..655_350) { "GIF frame delay must be between 10 and 655350 milliseconds" }
    }
}

/** Encoded GIF animation and stable metadata. */
class RenderedAnimation internal constructor(
    gifBytes: ByteArray,
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val durationMillis: Long,
    val repeat: Int,
) {
    private val bytes = gifBytes.copyOf()

    /** Returns a defensive copy of the GIF89a bytes. */
    fun gifBytes(): ByteArray = bytes.copyOf()

    /** Writes the GIF, creating its parent directory when necessary. */
    fun writeGif(path: Path) {
        val absolute = path.toAbsolutePath().normalize()
        absolute.parent?.let(Files::createDirectories)
        Files.write(absolute, bytes)
    }
}

/** Encodes frames with the same deterministic adaptive-palette/LZW implementation used by the Web Worker. */
@JvmOverloads
fun encodeGif(
    frames: List<GifAnimationFrame>,
    repeat: Int = 0,
): RenderedAnimation {
    require(frames.isNotEmpty()) { "At least one GIF frame is required" }
    val firstMetadata = frames.first().frame.metadata
    val width = firstMetadata.width
    val height = firstMetadata.height
    val engineFrames =
        frames.map { value ->
            require(value.frame.metadata.width == width && value.frame.metadata.height == height) {
                "All GIF frames must use the same dimensions"
            }
            EngineGifFrame(
                width = width,
                height = height,
                rgba = value.frame.rgbaBytes(),
                delayCentiseconds = ((value.delayMillis + 5) / 10).coerceIn(1, 65_535),
            )
        }
    return RenderedAnimation(
        gifBytes = AnimatedGifEncoder.encode(engineFrames, repeat),
        width = width,
        height = height,
        frameCount = frames.size,
        durationMillis = frames.sumOf { it.delayMillis.toLong() },
        repeat = repeat,
    )
}

/** Stateful JVM recorder mirroring the browser capture/export workflow. */
class SandboxGifRecorder
    @JvmOverloads
    constructor(
        val renderer: SandboxRenderer = SandboxRenderer(),
        val request: RenderRequest = RenderRequest(width = 480, height = 270),
        val frameDelayMillis: Int = 250,
        val maximumFrames: Int = 120,
    ) {
        private val frames = mutableListOf<GifAnimationFrame>()

        init {
            require(frameDelayMillis in 10..655_350) { "GIF frame delay must be between 10 and 655350 milliseconds" }
            require(maximumFrames in 1..1000) { "maximumFrames must be between 1 and 1000" }
        }

        val frameCount: Int get() = frames.size

        /** Captures the current sandbox without mutating it and returns the new frame count. */
        fun capture(sandbox: DatapackSandbox): Int {
            require(frames.size < maximumFrames) { "Animation exceeds the $maximumFrames frame limit" }
            frames += GifAnimationFrame(renderer.render(sandbox, request), frameDelayMillis)
            return frames.size
        }

        /** Exports all captured frames. A recorder can be exported repeatedly. */
        @JvmOverloads
        fun export(repeat: Int = 0): RenderedAnimation = encodeGif(frames, repeat)

        fun clear() = frames.clear()
    }
