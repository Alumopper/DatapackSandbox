package moe.afox.dpsandbox.render

import moe.afox.dpsandbox.core.createFunctionSandboxFromString
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SandboxGifRecorderTest {
    @Test
    fun recordsReusableMultiFrameGifWithoutMutatingCapturedWorlds() {
        val sandbox = createFunctionSandboxFromString("26.2", "say gif", defaultPlayerName = null)
        val recorder =
            SandboxGifRecorder(
                request = RenderRequest(width = 160, height = 90),
                frameDelayMillis = 120,
            )
        sandbox.executeCommand("setblock 0 0 2 minecraft:stone")
        recorder.capture(sandbox)
        sandbox.executeCommand("setblock 1 0 2 minecraft:diamond_block")
        recorder.capture(sandbox)

        val animation = recorder.export()
        val bytes = animation.gifBytes()
        assertEquals("GIF89a", bytes.copyOfRange(0, 6).decodeToString())
        assertEquals(2, animation.frameCount)
        assertEquals(240, animation.durationMillis)
        assertEquals(2, gifFrameCount(bytes))
        assertTrue(bytes.contentEquals(recorder.export().gifBytes()))
    }

    private fun gifFrameCount(bytes: ByteArray): Int {
        val input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        reader.input = input
        return try {
            reader.getNumImages(true)
        } finally {
            reader.dispose()
            input.close()
        }
    }
}
