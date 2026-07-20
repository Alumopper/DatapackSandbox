package moe.afox.dpsandbox.render

import java.awt.image.BufferedImage
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
}
