package moe.afox.dpsandbox.render.engine

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AnimatedGifEncoderJvmTest {
    @Test
    fun `large multi-frame gif remains decodable across lzw width changes`() {
        val width = 384
        val height = 256
        val frames =
            listOf(
                patternedFrame(width, height, 0),
                patternedFrame(width, height, 91),
            )

        val encoded = AnimatedGifEncoder.encode(frames)
        val input = ImageIO.createImageInputStream(ByteArrayInputStream(encoded))
        val reader = assertNotNull(ImageIO.getImageReadersByFormatName("gif").asSequence().firstOrNull())
        try {
            reader.input = input
            assertEquals(2, reader.getNumImages(true))
            val first = reader.read(0)
            val second = reader.read(1)
            listOf(0 to 0, 17 to 29, 191 to 127, 383 to 255).forEach { (x, y) ->
                assertEquals(expectedRgb(x, y, 0), first.getRGB(x, y) and 0xffffff)
                assertEquals(expectedRgb(x, y, 91), second.getRGB(x, y) and 0xffffff)
            }
        } finally {
            reader.dispose()
            input.close()
        }
    }

    private fun patternedFrame(
        width: Int,
        height: Int,
        phase: Int,
    ): EngineGifFrame {
        val rgba = ByteArray(width * height * 4)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val offset = (y * width + x) * 4
                val color = expectedRgb(x, y, phase)
                rgba[offset] = (color ushr 16).toByte()
                rgba[offset + 1] = (color ushr 8).toByte()
                rgba[offset + 2] = color.toByte()
                rgba[offset + 3] = 0xff.toByte()
            }
        }
        return EngineGifFrame(width, height, rgba)
    }

    private fun expectedRgb(
        x: Int,
        y: Int,
        phase: Int,
    ): Int {
        val colorIndex = (((x + phase) and 0xf) shl 4) or ((y + phase / 7) and 0xf)
        val red = ((colorIndex ushr 5) and 0x7) * 4 * 255 / 31
        val green = ((colorIndex ushr 2) and 0x7) * 4 * 255 / 31
        val blue = (colorIndex and 0x3) * 8 * 255 / 31
        return (red shl 16) or (green shl 8) or blue
    }
}
