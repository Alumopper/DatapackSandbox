package moe.afox.dpsandbox.render

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** Reads image dimensions before decoding so crafted assets cannot request unbounded pixel buffers. */
internal object BoundedImageReader {
    const val MAX_ENCODED_BYTES = 64L * 1024L * 1024L
    const val MAX_PIXELS = 16L * 1024L * 1024L
    const val MAX_DIMENSION = 16_384

    fun read(bytes: ByteArray): BufferedImage? =
        bytes.takeIf { it.size <= MAX_ENCODED_BYTES }?.let { encoded ->
            ByteArrayInputStream(encoded).use(::read)
        }

    fun read(path: Path): BufferedImage? =
        runCatching {
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_ENCODED_BYTES) return null
            Files.newInputStream(path).use(::read)
        }.getOrNull()

    private fun read(input: InputStream): BufferedImage? =
        runCatching {
            ImageIO.createImageInputStream(input)?.use { imageInput ->
                val readers = ImageIO.getImageReaders(imageInput)
                if (!readers.hasNext()) return@use null
                val reader = readers.next()
                try {
                    reader.setInput(imageInput, true, true)
                    val width = reader.getWidth(0)
                    val height = reader.getHeight(0)
                    if (!validDimensions(width, height)) return@use null
                    reader.read(0)?.takeIf { validDimensions(it.width, it.height) }
                } finally {
                    reader.dispose()
                }
            }
        }.getOrNull()

    private fun validDimensions(
        width: Int,
        height: Int,
    ): Boolean =
        width in 1..MAX_DIMENSION &&
            height in 1..MAX_DIMENSION &&
            width.toLong() * height <= MAX_PIXELS
}
