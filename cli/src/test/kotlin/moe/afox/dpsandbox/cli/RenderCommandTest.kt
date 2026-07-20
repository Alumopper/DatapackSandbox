package moe.afox.dpsandbox.cli

import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RenderCommandTest : RunCommandTestSupport() {
    @Test
    fun `run writes screenshot after executing commands`() {
        val output = Files.createTempDirectory("dps-cli-render").resolve("nested/state.png")

        val stdout =
            captureStdout {
                main(
                    arrayOf(
                        "run",
                        "--version",
                        "26.2",
                        "--command",
                        "setblock 0 0 2 minecraft:stone",
                        "--screenshot-file",
                        output.toString(),
                        "--screenshot-width",
                        "320",
                        "--screenshot-height",
                        "180",
                    ),
                )
            }

        assertTrue(Files.isRegularFile(output), stdout)
        val image = ImageIO.read(output.toFile())
        assertEquals(320, image.width)
        assertEquals(180, image.height)
        assertTrue("screenshot written:" in stdout, stdout)
        assertTrue("OK version=26.2" in stdout, stdout)
    }

    @Test
    fun `run rejects invalid screenshot dimensions as input error`() {
        val output = Files.createTempFile("dps-cli-render-invalid", ".png")
        Files.delete(output)

        val result =
            runCliProcess(
                "run",
                "--version",
                "26.2",
                "--screenshot-file",
                output.toString(),
                "--screenshot-width",
                "10",
            )

        assertEquals(ExitCodes.INPUT_FORMAT, result.exitCode, result.output)
        assertTrue("Render width must be between" in result.output, result.output)
        assertFalse(Files.exists(output))
    }

    @Test
    fun `run can require explicit rendering assets`() {
        val output = Files.createTempFile("dps-cli-render-required", ".png")
        Files.delete(output)

        val result =
            runCliProcess(
                "run",
                "--version",
                "26.2",
                "--screenshot-file",
                output.toString(),
                "--require-render-assets",
            )

        assertEquals(ExitCodes.INPUT_FORMAT, result.exitCode, result.output)
        assertTrue("--require-render-assets needs" in result.output, result.output)
        assertFalse(Files.exists(output))
    }

    @Test
    fun `run supports explicit camera and rejects conflicting camera selectors`() {
        val output = Files.createTempDirectory("dps-cli-fixed-camera").resolve("state.png")
        val success =
            runCliProcess(
                "run",
                "--version",
                "26.2",
                "--command",
                "setblock 0 0 2 minecraft:stone",
                "--camera-position",
                "0,1.62,0",
                "--camera-yaw",
                "0",
                "--camera-pitch",
                "15",
                "--screenshot-file",
                output.toString(),
                "--screenshot-width",
                "320",
                "--screenshot-height",
                "180",
            )
        val conflict =
            runCliProcess(
                "run",
                "--version",
                "26.2",
                "--camera-player",
                "Steve",
                "--camera-position",
                "0,0,0",
                "--screenshot-file",
                output.resolveSibling("conflict.png").toString(),
            )

        assertEquals(ExitCodes.OK, success.exitCode, success.output)
        assertTrue(Files.isRegularFile(output), success.output)
        assertEquals(ExitCodes.INPUT_FORMAT, conflict.exitCode, conflict.output)
        assertTrue("Use only one" in conflict.output, conflict.output)
    }
}
