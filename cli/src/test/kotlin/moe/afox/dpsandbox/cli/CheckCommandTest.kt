package moe.afox.dpsandbox.cli

import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CheckCommandTest {
    @Test
    fun `check can validate manifests against bundled schema before running`() {
        val dir = Files.createTempDirectory("dps-check-schema")
        val pack =
            Path
                .of("../core/src/test/resources/packs/counter")
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
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

        val output =
            captureStdout {
                main(arrayOf("check", manifest.toString(), "--validate-schema"))
            }

        assertTrue("PASS $manifest" in output, output)
    }

    @Test
    fun `manifest schema validator reports invalid manifest shapes`() {
        val errors =
            ManifestSchemaValidator.validate(
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
    fun `check strict mode validates schema and fails strict runtime issues`() {
        val schemaDir = Files.createTempDirectory("dps-check-strict-schema")
        val invalidManifest = schemaDir.resolve("invalid.dps.json")
        Files.writeString(
            invalidManifest,
            """
            {
              "version": "26.2",
              "packs": "pack",
              "steps": [
                { "ticks": "one" }
              ]
            }
            """.trimIndent(),
        )

        val schemaResult = runCliProcess("check", invalidManifest.toString(), "--strict")
        assertEquals(ExitCodes.INPUT_FORMAT, schemaResult.exitCode, schemaResult.output)
        assertTrue("Manifest schema validation failed" in schemaResult.output, schemaResult.output)

        val runtimeDir = Files.createTempDirectory("dps-check-strict-runtime")
        val pack = writeVerbosePack(runtimeDir, "strict-pack", "strict", includeMissingLoad = false)
        val unsupportedManifest = runtimeDir.resolve("unsupported.dps.json")
        Files.writeString(
            unsupportedManifest,
            """
            {
              "version": "26.2",
              "packs": ["${manifestPath(pack)}"],
              "steps": [
                { "command": "schedule noop demo:later 1t" }
              ]
            }
            """.trimIndent(),
        )

        val unsupportedResult = runCliProcess("check", unsupportedManifest.toString(), "--strict")
        assertEquals(ExitCodes.UNSUPPORTED_OR_VERSION, unsupportedResult.exitCode, unsupportedResult.output)
        assertTrue("Only 'schedule function' and 'schedule clear' are implemented" in unsupportedResult.output, unsupportedResult.output)

        val missingPack = writeVerbosePack(runtimeDir, "strict-missing-pack", "missing", includeMissingLoad = true)
        val missingManifest = runtimeDir.resolve("missing.dps.json")
        Files.writeString(
            missingManifest,
            """
            {
              "version": "26.2",
              "packs": ["${manifestPath(missingPack)}"]
            }
            """.trimIndent(),
        )

        val missingResult = runCliProcess("check", missingManifest.toString(), "--strict")
        assertEquals(ExitCodes.ASSERTION_FAILED, missingResult.exitCode, missingResult.output)
        assertTrue(
            "missing-reference #minecraft:load -> function demo:missing_load" in missingResult.output,
            missingResult.output,
        )
    }

    @Test
    fun `manifest schema validator follows included manifests`() {
        val dir = Files.createTempDirectory("dps-schema-include")
        val common = dir.resolve("common")
        Files.createDirectories(common)
        val pack =
            Path
                .of("../core/src/test/resources/packs/counter")
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
        val included = common.resolve("bad.dps.json")
        Files.writeString(
            included,
            """
            {
              "version": "26.2",
              "packs": ["$pack"],
              "steps": [
                { "ticks": "one" }
              ]
            }
            """.trimIndent(),
        )
        val manifest = dir.resolve("root.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "include": "common/bad.dps.json"
            }
            """.trimIndent(),
        )

        val errors = ManifestSchemaValidator.validateFileTree(manifest)

        assertTrue(
            errors.any { included.toAbsolutePath().normalize().toString() in it && "\$/steps/0/ticks" in it },
            errors.joinToString(),
        )
    }

    @Test
    fun `manifest schema validator reports missing included manifests`() {
        val dir = Files.createTempDirectory("dps-schema-missing-include")
        val missing =
            dir
                .resolve("common")
                .resolve("missing.dps.json")
                .toAbsolutePath()
                .normalize()
        val manifest = dir.resolve("root.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "include": "common/missing.dps.json"
            }
            """.trimIndent(),
        )

        val errors = ManifestSchemaValidator.validateFileTree(manifest)

        assertTrue(errors.any { missing.toString() in it && "included manifest is missing" in it }, errors.joinToString())
    }

    @Test
    fun `check can print and write manifest command traces`() {
        val dir = Files.createTempDirectory("dps-check-trace")
        val traceFile = dir.resolve("trace.jsonl")
        val pack =
            Path
                .of("../core/src/test/resources/packs/counter")
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
        val manifest = dir.resolve("trace.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.1.2",
              "packs": ["$pack"],
              "steps": [
                { "command": "say traced from check" },
                { "command": "scoreboard objectives add filtered_check dummy" },
                { "command": "scoreboard players set #check filtered_check 4" }
              ],
              "assertions": [
                { "trace": { "command": "say traced from check", "success": true, "outputs": 1, "hasDiff": true, "diffPath": "/outputs/0", "diffKind": "added", "count": 1 } },
                { "trace": { "command": "scoreboard players set #check filtered_check 4", "outputs": 0, "hasDiff": true, "diffPath": "/scores/filtered_check", "diffContains": "#check", "count": 1 } }
              ]
            }
            """.trimIndent(),
        )

        val output =
            captureStdout {
                main(arrayOf("check", manifest.toString(), "--trace", "--trace-filter", "root=say", "--trace-file", traceFile.toString()))
            }

        assertTrue("PASS $manifest" in output, output)
        assertTrue("trace OK say traced from check" in output, output)
        assertTrue("scoreboard objectives add filtered_check dummy" !in output, output)
        assertTrue("scoreboard players set #check filtered_check 4" !in output, output)
        assertTrue("trace written: $traceFile" in output, output)
        val traceJson = Files.readString(traceFile)
        assertTrue("\"command\": \"say traced from check\"" in traceJson, traceJson)
        assertTrue("\"root\": \"say\"" in traceJson, traceJson)
        assertTrue("scoreboard objectives add filtered_check dummy" !in traceJson, traceJson)
        assertTrue("scoreboard players set #check filtered_check 4" !in traceJson, traceJson)
    }

    @Test
    fun `check writes manifest output events as jsonl`() {
        val dir = Files.createTempDirectory("dps-check-outputs")
        val outputsFile = dir.resolve("outputs.jsonl")
        val pack =
            Path
                .of("../core/src/test/resources/packs/counter")
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
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

        val output =
            captureStdout {
                main(arrayOf("check", manifest.toString(), "--outputs-file", outputsFile.toString()))
            }

        assertTrue("PASS $manifest" in output, output)
        assertTrue("outputs written: $outputsFile" in output, output)
        val outputJson = Files.readString(outputsFile)
        assertTrue("\"command\": \"say\"" in outputJson, outputJson)
        assertTrue("\"text\": \"<Server> output from check\"" in outputJson, outputJson)
    }

    @Test
    fun `check writes player event traces as jsonl`() {
        val dir = Files.createTempDirectory("dps-check-event-trace")
        val eventTraceFile = dir.resolve("event-trace.jsonl")
        val pack =
            Path
                .of("../core/src/test/resources/packs/counter")
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
        val manifest = dir.resolve("event-trace.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.1.2",
              "packs": ["$pack"],
              "steps": [
                { "event": { "player": "Steve", "type": "key_input", "key": "key.jump", "action": "release" } }
              ],
              "assertions": [
                {
                  "eventTrace": {
                    "player": "Steve",
                    "type": "key_input",
                    "success": true,
                    "device": "keyboard",
                    "key": "key.jump",
                    "action": "release",
                    "count": 1
                  }
                }
              ]
            }
            """.trimIndent(),
        )

        val output =
            captureStdout {
                main(arrayOf("check", manifest.toString(), "--event-trace-file", eventTraceFile.toString()))
            }

        assertTrue("PASS $manifest" in output, output)
        assertTrue("event trace written: $eventTraceFile" in output, output)
        val eventTraceJson = Files.readString(eventTraceFile)
        assertTrue("\"player\": \"Steve\"" in eventTraceJson, eventTraceJson)
        assertTrue("\"type\": \"key_input\"" in eventTraceJson, eventTraceJson)
        assertTrue("\"success\": true" in eventTraceJson, eventTraceJson)
        assertTrue("\"device\": \"keyboard\"" in eventTraceJson, eventTraceJson)
        assertTrue("\"code\": \"key.jump\"" in eventTraceJson, eventTraceJson)
        assertTrue("\"action\": \"release\"" in eventTraceJson, eventTraceJson)
    }

    @Test
    fun `check writes structured manifest reports`() {
        val dir = Files.createTempDirectory("dps-check-report")
        val reportFile = dir.resolve("report.json")
        val pack =
            Path
                .of("../core/src/test/resources/packs/counter")
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
        val manifest = dir.resolve("report.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.1.2",
              "packs": ["$pack"],
              "steps": [
                { "command": "scoreboard objectives add ticks dummy" },
                { "command": "scoreboard players set #clock ticks 5" },
                { "command": "say check report ok" },
                { "event": { "player": "Steve", "type": "key_input", "key": "key.jump", "action": "press" } }
              ],
              "assertions": [
                { "score": { "target": "#clock", "objective": "ticks", "equals": 5 } },
                { "eventTrace": { "player": "Steve", "type": "key_input", "success": true, "count": 1 } }
              ]
            }
            """.trimIndent(),
        )

        val output =
            captureStdout {
                main(arrayOf("check", manifest.toString(), "--report-file", reportFile.toString()))
            }

        assertTrue("PASS $manifest" in output, output)
        assertTrue("report written: $reportFile" in output, output)
        val report = JsonParser.parseString(Files.readString(reportFile)).asJsonArray[0].asJsonObject
        assertTrue(report.get("passed").asBoolean)
        assertTrue(report.get("outputCount").asInt == 1)
        assertTrue(report.get("traceCount").asInt >= 3)
        assertTrue(report.get("eventTraceCount").asInt == 1)
        assertTrue(
            report
                .getAsJsonArray("outputs")[0]
                .asJsonObject
                .get("text")
                .asString == "<Server> check report ok",
        )
        assertTrue(
            report
                .getAsJsonArray("eventTraces")[0]
                .asJsonObject
                .get("type")
                .asString == "key_input",
        )
        assertTrue(
            report
                .getAsJsonArray("attempts")[0]
                .asJsonObject
                .get("version")
                .asString == "26.1.2",
        )
        val attempt = report.getAsJsonArray("attempts")[0].asJsonObject
        assertTrue(attempt.get("eventTraceCount").asInt == 1)
        assertTrue(
            attempt
                .getAsJsonArray("eventTraces")[0]
                .asJsonObject
                .get("player")
                .asString == "Steve",
        )
        assertTrue(
            attempt
                .getAsJsonObject("snapshot")
                .getAsJsonObject("scores")
                .getAsJsonObject("ticks")
                .get("#clock")
                .asInt == 5,
        )
        assertTrue(
            attempt.getAsJsonArray("snapshotDiffs").any { entry ->
                entry.asJsonObject.get("path").asString == "/scores/ticks"
            },
        )
        assertTrue(attempt.getAsJsonObject("resources").get("functions").asInt > 0)
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

        val output =
            captureStdout {
                main(arrayOf("check", manifest.toString(), "--verbose"))
            }

        assertTrue("PASS $manifest" in output, output)
        assertTrue("resources 26.2 functions=1" in output, output)
        assertTrue("recipes=1" in output, output)
        assertTrue("overridden=" in output, output)
        assertTrue("overlay recipe demo:marker modeled active" in output, output)
        assertTrue("overlay recipe demo:marker modeled overridden" in output, output)
        assertTrue("missing-reference #minecraft:load -> function demo:missing_load" in output, output)
    }

    private fun writeVerbosePack(
        root: Path,
        name: String,
        marker: String,
        includeMissingLoad: Boolean,
    ): Path {
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
            val tagRoot =
                pack
                    .resolve("data")
                    .resolve("minecraft")
                    .resolve("tags")
                    .resolve("function")
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
        path
            .toAbsolutePath()
            .normalize()
            .toString()
            .replace("\\", "\\\\")

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

    private fun runCliProcess(vararg args: String): ProcessResult {
        val javaBinary =
            Path.of(
                System.getProperty("java.home"),
                "bin",
                if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java",
            )
        val process =
            ProcessBuilder(
                listOf(
                    javaBinary.toString(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    "moe.afox.dpsandbox.cli.MainKt",
                ) + args,
            ).redirectErrorStream(true)
                .start()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        if (!finished) {
            process.destroyForcibly()
            error("CLI process timed out:${System.lineSeparator()}$output")
        }
        return ProcessResult(process.exitValue(), output)
    }

    private data class ProcessResult(
        val exitCode: Int,
        val output: String,
    )
}
