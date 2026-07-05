package moe.afox.dpsandbox.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class CheckCommandTest {
    @Test
    fun `check can print and write manifest command traces`() {
        val dir = Files.createTempDirectory("dps-check-trace")
        val traceFile = dir.resolve("trace.jsonl")
        val pack = Path.of("../core/src/test/resources/packs/counter").toAbsolutePath().normalize().toString().replace("\\", "\\\\")
        val manifest = dir.resolve("trace.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.1.2",
              "packs": ["$pack"],
              "steps": [
                { "command": "say traced from check" },
                { "command": "scoreboard objectives add filtered_check dummy" }
              ],
              "assertions": [
                { "trace": { "command": "say traced from check", "success": true, "count": 1 } }
              ]
            }
            """.trimIndent(),
        )

        val output = captureStdout {
            main(arrayOf("check", manifest.toString(), "--trace", "--trace-filter", "root=say", "--trace-file", traceFile.toString()))
        }

        assertTrue("PASS $manifest" in output, output)
        assertTrue("trace OK say traced from check" in output, output)
        assertTrue("scoreboard objectives add filtered_check dummy" !in output, output)
        assertTrue("trace written: $traceFile" in output, output)
        val traceJson = Files.readString(traceFile)
        assertTrue("\"command\": \"say traced from check\"" in traceJson, traceJson)
        assertTrue("\"root\": \"say\"" in traceJson, traceJson)
        assertTrue("scoreboard objectives add filtered_check dummy" !in traceJson, traceJson)
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
