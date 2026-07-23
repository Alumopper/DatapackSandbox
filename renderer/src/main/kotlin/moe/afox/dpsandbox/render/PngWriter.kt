package moe.afox.dpsandbox.render

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

internal object PngWriter {
    fun encode(
        pixels: IntArray,
        width: Int,
        height: Int,
    ): ByteArray {
        require(pixels.size == width * height) { "Pixel buffer size does not match image dimensions" }
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, width, height, pixels, 0, width)
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "png", output)) { "No PNG ImageIO writer is available" }
            output.toByteArray()
        }
    }

    fun decodeRgba(bytes: ByteArray): ByteArray {
        val image = checkNotNull(ImageIO.read(ByteArrayInputStream(bytes))) { "PNG frame could not be decoded" }
        val argb = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        val rgba = ByteArray(argb.size * 4)
        argb.forEachIndexed { index, color ->
            rgba[index * 4] = (color ushr 16).toByte()
            rgba[index * 4 + 1] = (color ushr 8).toByte()
            rgba[index * 4 + 2] = color.toByte()
            rgba[index * 4 + 3] = (color ushr 24).toByte()
        }
        return rgba
    }
}
