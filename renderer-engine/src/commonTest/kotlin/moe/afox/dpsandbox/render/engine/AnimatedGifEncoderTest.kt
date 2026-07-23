package moe.afox.dpsandbox.render.engine

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AnimatedGifEncoderTest {
    @Test
    fun encodesDeterministicMultiFrameGif() {
        val first = solidFrame(8, 6, 255, 0, 0, 5)
        val second = solidFrame(8, 6, 0, 255, 0, 12)

        val encoded = AnimatedGifEncoder.encode(listOf(first, second), repeat = 0)

        assertContentEquals(encoded, AnimatedGifEncoder.encode(listOf(first, second), repeat = 0))
        assertEquals("GIF89a", encoded.copyOfRange(0, 6).decodeToString())
        assertEquals(0x3b, encoded.last().toInt() and 0xff)
        assertTrue(encoded.size > 800)
    }

    @Test
    fun rejectsMismatchedFrames() {
        assertFailsWith<IllegalArgumentException> {
            AnimatedGifEncoder.encode(listOf(solidFrame(2, 2, 0, 0, 0, 1), solidFrame(3, 2, 0, 0, 0, 1)))
        }
    }

    private fun solidFrame(
        width: Int,
        height: Int,
        red: Int,
        green: Int,
        blue: Int,
        delay: Int,
    ): EngineGifFrame {
        val rgba = ByteArray(width * height * 4)
        for (index in 0 until width * height) {
            rgba[index * 4] = red.toByte()
            rgba[index * 4 + 1] = green.toByte()
            rgba[index * 4 + 2] = blue.toByte()
            rgba[index * 4 + 3] = 0xff.toByte()
        }
        return EngineGifFrame(width, height, rgba, delay)
    }
}
