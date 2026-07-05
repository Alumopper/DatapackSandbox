package moe.afox.dpsandbox.cli

import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManifestSchemaCommandTest {
    @Test
    fun `schema command prints bundled manifest schema`() {
        val output = captureStdout {
            main(arrayOf("schema"))
        }

        val schema = JsonParser.parseString(output).asJsonObject

        assertEquals("Datapack Sandbox manifest", schema.get("title").asString)
        assertEquals("https://json-schema.org/draft/2020-12/schema", schema.get("\$schema").asString)
        assertTrue(schema.getAsJsonObject("\$defs").has("step"))
    }

    @Test
    fun `schema command writes bundled manifest schema`() {
        val dir = Files.createTempDirectory("dps-schema-command")
        val outputFile = dir.resolve("dps-manifest.schema.json")

        val output = captureStdout {
            main(arrayOf("schema", "--output", outputFile.toString()))
        }

        val schema = JsonParser.parseString(Files.readString(outputFile)).asJsonObject
        assertTrue("schema written: $outputFile" in output, output)
        assertEquals("Datapack Sandbox manifest", schema.get("title").asString)
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
