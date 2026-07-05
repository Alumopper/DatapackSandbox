package moe.afox.dpsandbox.cli

import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class CheckCommandTest {
    @Test
    fun `check can validate manifests against bundled schema before running`() {
        val dir = Files.createTempDirectory("dps-check-schema")
        val pack = Path.of("../core/src/test/resources/packs/counter").toAbsolutePath().normalize().toString().replace("\\", "\\\\")
        val manifest = dir.resolve("schema-valid.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.1.2",
              "packs": ["$pack"],
              "steps": [
                { "command": "say schema checked" }
              ],
              "assertions": [
                { "output": { "command": "say", "contains": "schema checked", "count": 1 } }
              ]
            }
            """.trimIndent(),
        )

        val output = captureStdout {
            main(arrayOf("check", manifest.toString(), "--validate-schema"))
        }

        assertTrue("PASS $manifest" in output, output)
    }

    @Test
    fun `manifest schema validator reports invalid manifest shapes`() {
        val errors = ManifestSchemaValidator.validate(
            JsonParser.parseString(
                """
                {
                  "version": "26.2",
                  "packs": "pack",
                  "steps": [
                    { "ticks": "one" }
                  ]
                }
                """.trimIndent(),
            ),
        )

        assertTrue(errors.any { it.contains("$/packs") }, errors.joinToString())
        assertTrue(errors.any { it.contains("$/steps/0/ticks") }, errors.joinToString())
    }

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

    @Test
    fun `check writes manifest output events as jsonl`() {
        val dir = Files.createTempDirectory("dps-check-outputs")
        val outputsFile = dir.resolve("outputs.jsonl")
        val pack = Path.of("../core/src/test/resources/packs/counter").toAbsolutePath().normalize().toString().replace("\\", "\\\\")
        val manifest = dir.resolve("outputs.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.1.2",
              "packs": ["$pack"],
              "steps": [
                { "command": "say output from check" }
              ],
              "assertions": [
                { "output": { "command": "say", "contains": "output from check", "count": 1 } }
              ]
            }
            """.trimIndent(),
        )

        val output = captureStdout {
            main(arrayOf("check", manifest.toString(), "--outputs-file", outputsFile.toString()))
        }

        assertTrue("PASS $manifest" in output, output)
        assertTrue("outputs written: $outputsFile" in output, output)
        val outputJson = Files.readString(outputsFile)
        assertTrue("\"command\": \"say\"" in outputJson, outputJson)
        assertTrue("\"text\": \"<Server> output from check\"" in outputJson, outputJson)
    }

    @Test
    fun `check writes structured manifest reports`() {
        val dir = Files.createTempDirectory("dps-check-report")
        val reportFile = dir.resolve("report.json")
        val pack = Path.of("../core/src/test/resources/packs/counter").toAbsolutePath().normalize().toString().replace("\\", "\\\\")
        val manifest = dir.resolve("report.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.1.2",
              "packs": ["$pack"],
              "steps": [
                { "command": "scoreboard objectives add ticks dummy" },
                { "command": "scoreboard players set #clock ticks 5" }
              ],
              "assertions": [
                { "score": { "target": "#clock", "objective": "ticks", "equals": 5 } }
              ]
            }
            """.trimIndent(),
        )

        val output = captureStdout {
            main(arrayOf("check", manifest.toString(), "--report-file", reportFile.toString()))
        }

        assertTrue("PASS $manifest" in output, output)
        assertTrue("report written: $reportFile" in output, output)
        val report = JsonParser.parseString(Files.readString(reportFile)).asJsonArray[0].asJsonObject
        assertTrue(report.get("passed").asBoolean)
        assertTrue(report.getAsJsonArray("attempts")[0].asJsonObject.get("version").asString == "26.1.2")
        assertTrue(report.getAsJsonArray("attempts")[0].asJsonObject.getAsJsonObject("resources").get("functions").asInt > 0)
    }

    @Test
    fun `check verbose prints resource summary overlays and missing references`() {
        val dir = Files.createTempDirectory("dps-check-verbose")
        val first = writeVerbosePack(dir, "verbose-first", "first", includeMissingLoad = true)
        val second = writeVerbosePack(dir, "verbose-second", "second", includeMissingLoad = false)
        val manifest = dir.resolve("verbose.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.2",
              "packs": ["${manifestPath(first)}", "${manifestPath(second)}"]
            }
            """.trimIndent(),
        )

        val output = captureStdout {
            main(arrayOf("check", manifest.toString(), "--verbose"))
        }

        assertTrue("PASS $manifest" in output, output)
        assertTrue("resources 26.2 functions=1" in output, output)
        assertTrue("recipes=1" in output, output)
        assertTrue("overridden=" in output, output)
        assertTrue("overlay recipe demo:marker active" in output, output)
        assertTrue("overlay recipe demo:marker overridden" in output, output)
        assertTrue("missing-reference #minecraft:load -> function demo:missing_load" in output, output)
    }

    private fun writeVerbosePack(root: Path, name: String, marker: String, includeMissingLoad: Boolean): Path {
        val pack = root.resolve(name)
        Files.createDirectories(pack)
        Files.writeString(
            pack.resolve("pack.mcmeta"),
            """
            {
              "pack": {
                "pack_format": 107.1,
                "description": "$name"
              }
            }
            """.trimIndent(),
        )

        val functionRoot = pack.resolve("data").resolve("demo").resolve("function")
        Files.createDirectories(functionRoot)
        Files.writeString(functionRoot.resolve("noop.mcfunction"), "say $marker")

        val recipeRoot = pack.resolve("data").resolve("demo").resolve("recipe")
        Files.createDirectories(recipeRoot)
        Files.writeString(
            recipeRoot.resolve("marker.json"),
            """
            {
              "type": "minecraft:crafting_shapeless",
              "marker": "$marker",
              "ingredients": [],
              "result": { "id": "minecraft:stone", "count": 1 }
            }
            """.trimIndent(),
        )

        if (includeMissingLoad) {
            val tagRoot = pack.resolve("data").resolve("minecraft").resolve("tags").resolve("function")
            Files.createDirectories(tagRoot)
            Files.writeString(
                tagRoot.resolve("load.json"),
                """
                {
                  "values": ["demo:missing_load"]
                }
                """.trimIndent(),
            )
        }
        return pack
    }

    private fun manifestPath(path: Path): String =
        path.toAbsolutePath().normalize().toString().replace("\\", "\\\\")

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
