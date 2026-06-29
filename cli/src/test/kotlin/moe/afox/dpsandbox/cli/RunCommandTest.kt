package moe.afox.dpsandbox.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class RunCommandTest {
    @Test
    fun `run executes a single mcfunction without a datapack`() {
        val functionFile = Files.createTempFile("dps-cli-single-function", ".mcfunction")
        Files.writeString(
            functionFile,
            """
            scoreboard objectives add runs dummy
            scoreboard players set #cli runs 9
            """.trimIndent(),
        )

        val output = captureStdout {
            main(arrayOf("run", "--mcfunction", functionFile.toString(), "--version", "26.2", "--snapshot"))
        }

        assertTrue("OK version=26.2" in output, output)
        assertTrue("\"#cli\": 9" in output, output)
    }

    @Test
    fun `run executes inline mcfunction text`() {
        val output = captureStdout {
            main(
                arrayOf(
                    "run",
                    "--version",
                    "26.2",
                    "--mcfunction-text",
                    "scoreboard objectives add runs dummy\nscoreboard players set #inline runs 6",
                    "--snapshot",
                ),
            )
        }

        assertTrue("OK version=26.2" in output, output)
        assertTrue("\"#inline\": 6" in output, output)
    }

    @Test
    fun `run loads multiple mcfunction files and strings together`() {
        val mainFile = Files.createTempFile("dps-cli-main-function", ".mcfunction")
        val helperFile = Files.createTempFile("dps-cli-helper-function", ".mcfunction")
        Files.writeString(
            mainFile,
            """
            scoreboard objectives add runs dummy
            scoreboard players set #cli_multi runs 1
            function demo:helper
            """.trimIndent(),
        )
        Files.writeString(
            helperFile,
            """
            scoreboard players add #cli_multi runs 2
            function demo:inline
            """.trimIndent(),
        )

        val output = captureStdout {
            main(
                arrayOf(
                    "run",
                    "--version",
                    "26.2",
                    "--mcfunction-id",
                    "demo:main",
                    "--mcfunction",
                    "demo:main=$mainFile",
                    "--mcfunction",
                    "demo:helper=$helperFile",
                    "--mcfunction-text",
                    "demo:inline=scoreboard players add #cli_multi runs 4",
                    "--snapshot",
                ),
            )
        }

        assertTrue("OK version=26.2" in output, output)
        assertTrue("\"#cli_multi\": 7" in output, output)
    }

    @Test
    fun `version lists supported datapack formats`() {
        val output = captureStdout {
            main(arrayOf("version"))
        }

        assertTrue("1.20.4 java=17 pack_format=26 data=3700" in output, output)
        assertTrue("26.1.2 java=25 pack_format=101.1" in output, output)
        assertTrue("26.2 java=25 pack_format=107.1 data=4903 default" in output, output)
    }

    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val bytes = ByteArrayOutputStream()
        System.setOut(PrintStream(bytes, true, Charsets.UTF_8))
        return try {
            block()
            bytes.toString(Charsets.UTF_8)
        } finally {
            System.setOut(original)
        }
    }
}
