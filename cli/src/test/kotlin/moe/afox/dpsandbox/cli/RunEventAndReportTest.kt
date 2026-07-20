package moe.afox.dpsandbox.cli

import com.google.gson.JsonParser
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunEventAndReportTest : RunCommandTestSupport() {
    @Test
    fun `run injects player events for inline assertions`() {
        val output =
            captureStdout {
                main(
                    arrayOf(
                        "run",
                        "--version",
                        "26.2",
                        "--event",
                        "player Steve key_input key.jump release",
                        "--assert",
                        """{"player":{"name":"Steve","lastInput":{"device":"keyboard","code":"key.jump","action":"release"}}}""",
                        "--assert",
                        """{"eventTrace":{"player":"Steve","type":"key_input","success":true,"inputDevice":"keyboard","inputCode":"key.jump","inputAction":"release","count":1}}""",
                        "--assert",
                        "event-trace:Steve:key_input=1",
                    ),
                )
            }

        assertTrue("OK version=26.2" in output, output)
    }

    @Test
    fun `run injects block event positions from shorthand`() {
        val output =
            captureStdout {
                main(
                    arrayOf(
                        "run",
                        "--version",
                        "26.2",
                        "--event",
                        "player Steve block_placed minecraft:stone 1 64 2",
                        "--assert",
                        """{"block":{"pos":[1,64,2],"id":"minecraft:stone"}}""",
                        "--assert",
                        "block:1,64,2=minecraft:stone",
                        "--assert",
                        "block:1,64,2?",
                        "--assert",
                        "block:9,64,9!",
                        "--assert",
                        """{"eventTrace":{"player":"Steve","type":"block_placed","block":"minecraft:stone","blockX":1,"blockY":64,"blockZ":2,"count":1}}""",
                        "--assert",
                        "event-trace:Steve:block_placed@1,64,2=1",
                    ),
                )
            }

        assertTrue("OK version=26.2" in output, output)
    }

    @Test
    fun `run injects player events from files`() {
        val eventFile = Files.createTempFile("dps-cli-events", ".txt")
        Files.writeString(
            eventFile,
            """
            # event generator output
            player Steve key_input key.jump press

            player Steve mouse_input left click
            """.trimIndent(),
        )

        val output =
            captureStdout {
                main(
                    arrayOf(
                        "run",
                        "--version",
                        "26.2",
                        "--event-file",
                        eventFile.toString(),
                        "--assert",
                        """{"player":{"name":"Steve","lastInput":{"device":"mouse","code":"left","action":"click"}}}""",
                        "--assert",
                        """{"eventTrace":{"player":"Steve","type":"key_input","success":true,"count":1}}""",
                        "--assert",
                        """{"eventTrace":{"player":"Steve","type":"mouse_input","success":true,"count":1}}""",
                    ),
                )
            }

        assertTrue("OK version=26.2" in output, output)
    }

    @Test
    fun `run writes player event traces as jsonl`() {
        val eventTraceFile = Files.createTempFile("dps-cli-event-trace", ".jsonl")

        val output =
            captureStdout {
                main(
                    arrayOf(
                        "run",
                        "--version",
                        "26.2",
                        "--event",
                        "player Steve key_input key.jump release",
                        "--event-trace-file",
                        eventTraceFile.toString(),
                    ),
                )
            }

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

        val output =
            captureStdout {
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
    fun `run uses folder and zip packs as mcfunction dependencies`() {
        val root = Files.createTempDirectory("dps-cli-function-deps")
        val folderPack = writeDependencyPack(root.resolve("folder-pack"), "folder", "scoreboard players add #cli_deps runs 2")
        val zipPack =
            zipPack(
                writeDependencyPack(root.resolve("zip-pack"), "zip", "scoreboard players add #cli_deps runs 4"),
                root.resolve("zip-pack.zip"),
            )

        val output =
            captureStdout {
                main(
                    arrayOf(
                        "run",
                        "--version",
                        "26.2",
                        "--pack",
                        folderPack.toString(),
                        "--pack",
                        zipPack.toString(),
                        "--mcfunction-id",
                        "demo:main",
                        "--mcfunction-text",
                        """
                        demo:main=scoreboard objectives add runs dummy
                        scoreboard players set #cli_deps runs 1
                        function demo:folder
                        function demo:zip
                        """.trimIndent(),
                        "--snapshot",
                    ),
                )
            }

        assertTrue("OK version=26.2" in output, output)
        assertTrue("\"#cli_deps\": 7" in output, output)
    }

    @Test
    fun `run can print and write command trace`() {
        val traceFile = Files.createTempFile("dps-cli-trace", ".jsonl")

        val output =
            captureStdout {
                main(
                    arrayOf(
                        "run",
                        "--version",
                        "26.2",
                        "--mcfunction-text",
                        "say traced from cli",
                        "--trace",
                        "--trace-file",
                        traceFile.toString(),
                    ),
                )
            }

        assertTrue("trace OK say traced from cli" in output, output)
        assertTrue("trace written: $traceFile" in output, output)
        val traceJson = Files.readString(traceFile)
        assertTrue("\"command\": \"say traced from cli\"" in traceJson, traceJson)
        assertTrue("\"root\": \"say\"" in traceJson, traceJson)
        assertTrue("\"success\": true" in traceJson, traceJson)
    }

    @Test
    fun `run writes output events as jsonl`() {
        val outputsFile = Files.createTempFile("dps-cli-outputs", ".jsonl")

        val output =
            captureStdout {
                main(
                    arrayOf(
                        "run",
                        "--version",
                        "26.2",
                        "--mcfunction-text",
                        "say output artifact",
                        "--outputs-file",
                        outputsFile.toString(),
                    ),
                )
            }

        assertTrue("outputs written: $outputsFile" in output, output)
        val outputJson = Files.readString(outputsFile)
        assertTrue("\"command\": \"say\"" in outputJson, outputJson)
        assertTrue("\"channel\": \"chat\"" in outputJson, outputJson)
        assertTrue("\"text\": \"<Server> output artifact\"" in outputJson, outputJson)
    }

    @Test
    fun `run writes structured report files`() {
        val reportFile = Files.createTempFile("dps-cli-report", ".json")

        val output =
            captureStdout {
                main(
                    arrayOf(
                        "run",
                        "--version",
                        "26.2",
                        "--mcfunction-text",
                        "scoreboard objectives add runs dummy\nscoreboard players set #report runs 4\nsay report artifact",
                        "--event",
                        "player Steve key_input key.jump press",
                        "--assert",
                        "output:report artifact",
                        "--report-file",
                        reportFile.toString(),
                    ),
                )
            }

        assertTrue("report written: $reportFile" in output, output)
        val reportJson = Files.readString(reportFile)
        assertTrue("\"version\": \"26.2\"" in reportJson, reportJson)
        assertTrue("\"passed\": true" in reportJson, reportJson)
        assertTrue("\"assertionFailures\": []" in reportJson, reportJson)
        assertTrue("\"outputs\"" in reportJson, reportJson)
        assertTrue("\"text\": \"<Server> report artifact\"" in reportJson, reportJson)
        assertTrue("\"traces\"" in reportJson, reportJson)
        assertTrue("\"eventTraces\"" in reportJson, reportJson)
        assertTrue("\"type\": \"key_input\"" in reportJson, reportJson)
        assertTrue("\"snapshot\"" in reportJson, reportJson)
        assertTrue("\"#report\": 4" in reportJson, reportJson)
        assertTrue("\"snapshotDiffs\"" in reportJson, reportJson)
        assertTrue("\"path\": \"/scores/runs\"" in reportJson, reportJson)
        assertTrue("\"resources\"" in reportJson, reportJson)
        assertTrue("\"functions\": 1" in reportJson, reportJson)
        assertTrue("\"missingReferences\": []" in reportJson, reportJson)
    }

    @Test
    fun `run can fail on missing resource references`() {
        val dir = Files.createTempDirectory("dps-cli-missing-resource")
        val pack = writeMissingReferencePack(dir.resolve("pack"))
        val reportFile = Files.createTempFile("dps-cli-missing-resource-report", ".json")

        val result =
            runCliProcess(
                "run",
                "--version",
                "26.2",
                "--pack",
                pack.toString(),
                "--fail-on-missing-resources",
                "--report-file",
                reportFile.toString(),
            )

        assertEquals(ExitCodes.ASSERTION_FAILED, result.exitCode, result.output)
        assertTrue("report written: $reportFile" in result.output, result.output)
        assertTrue(
            "missing-reference #minecraft:load -> function demo:missing_load" in result.output,
            result.output,
        )
        val reportJson = Files.readString(reportFile)
        assertTrue("\"passed\": false" in reportJson, reportJson)
        assertTrue("\"missingReferences\"" in reportJson, reportJson)
        assertTrue("\"source\": \"#minecraft:load\"" in reportJson, reportJson)
        assertTrue("\"id\": \"demo:missing_load\"" in reportJson, reportJson)
    }

    @Test
    fun `run report files include resource overlay details`() {
        val dir = Files.createTempDirectory("dps-cli-report-overlays")
        val first = writeResourceOverlayPack(dir.resolve("first"), "first", includeMissingLoad = true)
        val second = writeResourceOverlayPack(dir.resolve("second"), "second", includeMissingLoad = false)
        val reportFile = Files.createTempFile("dps-cli-overlay-report", ".json")

        val output =
            captureStdout {
                main(
                    arrayOf(
                        "run",
                        "--version",
                        "26.2",
                        "--pack",
                        first.toString(),
                        "--pack",
                        second.toString(),
                        "--report-file",
                        reportFile.toString(),
                    ),
                )
            }

        assertTrue("report written: $reportFile" in output, output)
        val resources = JsonParser.parseString(Files.readString(reportFile)).asJsonObject.getAsJsonObject("resources")
        val overlays = resources.getAsJsonArray("overlays").map { it.asJsonObject }
        assertTrue(
            overlays.any { overlay ->
                overlay.get("type").asString == "recipe" &&
                    overlay.get("id").asString == "demo:marker" &&
                    overlay.get("active").asBoolean
            },
            overlays.toString(),
        )
        assertTrue(
            overlays.any { overlay ->
                overlay.get("type").asString == "recipe" &&
                    overlay.get("id").asString == "demo:marker" &&
                    !overlay.get("active").asBoolean
            },
            overlays.toString(),
        )
        val missingReferences = resources.getAsJsonArray("missingReferences").map { it.asJsonObject }
        assertTrue(
            missingReferences.any { reference ->
                reference.get("source").asString == "#minecraft:load" &&
                    reference.get("type").asString == "function" &&
                    reference.get("id").asString == "demo:missing_load"
            },
            missingReferences.toString(),
        )
    }

    @Test
    fun `run strict mode fails unsupported commands and missing resource references`() {
        val unsupportedResult =
            runCliProcess(
                "run",
                "--version",
                "26.2",
                "--strict",
                "--command",
                "schedule noop demo:later 1t",
            )
        assertEquals(ExitCodes.UNSUPPORTED_OR_VERSION, unsupportedResult.exitCode, unsupportedResult.output)
        assertTrue("Only 'schedule function' and 'schedule clear' are implemented" in unsupportedResult.output, unsupportedResult.output)

        val dir = Files.createTempDirectory("dps-cli-strict-missing-resource")
        val pack = writeMissingReferencePack(dir.resolve("pack"))
        val missingResult =
            runCliProcess(
                "run",
                "--version",
                "26.2",
                "--pack",
                pack.toString(),
                "--strict",
            )

        assertEquals(ExitCodes.ASSERTION_FAILED, missingResult.exitCode, missingResult.output)
        assertTrue(
            "missing-reference #minecraft:load -> function demo:missing_load" in missingResult.output,
            missingResult.output,
        )
    }

    @Test
    fun `run can print resource summaries`() {
        val dir = Files.createTempDirectory("dps-cli-resource-summary")
        val pack = writeMissingReferencePack(dir.resolve("pack"))

        val output =
            captureStdout {
                main(
                    arrayOf(
                        "run",
                        "--version",
                        "26.2",
                        "--pack",
                        pack.toString(),
                        "--resources",
                    ),
                )
            }

        assertTrue("resources 26.2 functions=0" in output, output)
        assertTrue("missing-reference #minecraft:load -> function demo:missing_load" in output, output)
        assertTrue("OK version=26.2" in output, output)
    }

    @Test
    fun `run filters printed and written command traces`() {
        val traceFile = Files.createTempFile("dps-cli-filtered-trace", ".jsonl")

        val output =
            captureStdout {
                main(
                    arrayOf(
                        "run",
                        "--version",
                        "26.2",
                        "--mcfunction-text",
                        "say hidden from filtered trace\nscoreboard objectives add runs dummy\nscoreboard players set #filtered runs 1",
                        "--trace",
                        "--trace-filter",
                        "root=scoreboard",
                        "--trace-file",
                        traceFile.toString(),
                    ),
                )
            }

        assertTrue("trace OK scoreboard objectives add runs dummy" in output, output)
        assertTrue("trace OK scoreboard players set #filtered runs 1" in output, output)
        assertFalse("trace OK say hidden from filtered trace" in output, output)
        val traceJson = Files.readString(traceFile)
        assertTrue("\"root\": \"scoreboard\"" in traceJson, traceJson)
        assertTrue("\"command\": \"scoreboard players set #filtered runs 1\"" in traceJson, traceJson)
        assertFalse("\"command\": \"say hidden from filtered trace\"" in traceJson, traceJson)
    }

    @Test
    fun `run filters traces by command state changes`() {
        val traceFile = Files.createTempFile("dps-cli-state-filtered-trace", ".jsonl")

        val output =
            captureStdout {
                main(
                    arrayOf(
                        "run",
                        "--version",
                        "26.2",
                        "--mcfunction-text",
                        "say hidden from state filter\nscoreboard objectives add runs dummy\nscoreboard players set #state_filtered runs 2\ndata merge storage demo:env {ready:true}",
                        "--trace",
                        "--trace-filter",
                        "score=#state_filtered",
                        "--trace-file",
                        traceFile.toString(),
                    ),
                )
            }

        assertTrue("trace OK scoreboard players set #state_filtered runs 2" in output, output)
        assertTrue("changes=+1" in output, output)
        assertFalse("trace OK say hidden from state filter" in output, output)
        assertFalse("trace OK data merge storage demo:env" in output, output)
        val traceJson = Files.readString(traceFile)
        assertTrue("\"command\": \"scoreboard players set #state_filtered runs 2\"" in traceJson, traceJson)
        assertTrue("\"snapshotDiffs\"" in traceJson, traceJson)
        assertTrue("\"path\": \"/scores/runs\"" in traceJson, traceJson)
        assertTrue("\"#state_filtered\": 2" in traceJson, traceJson)
        assertFalse("\"command\": \"data merge storage demo:env {ready:true}\"" in traceJson, traceJson)
    }

    @Test
    fun `run filters traces by output text and selector targets`() {
        val outputTraceFile = Files.createTempFile("dps-cli-output-filtered-trace", ".jsonl")
        val selectorTraceFile = Files.createTempFile("dps-cli-selector-filtered-trace", ".jsonl")

        val outputFiltered =
            captureStdout {
                main(
                    arrayOf(
                        "run",
                        "--version",
                        "26.2",
                        "--mcfunction-text",
                        "say visible trace output\nscoreboard objectives add runs dummy",
                        "--trace",
                        "--trace-filter",
                        "output=visible trace output",
                        "--trace-file",
                        outputTraceFile.toString(),
                    ),
                )
            }
        val selectorFiltered =
            captureStdout {
                main(
                    arrayOf(
                        "run",
                        "--version",
                        "26.2",
                        "--mcfunction-text",
                        "say selector target output\nscoreboard objectives add runs dummy",
                        "--trace",
                        "--trace-filter",
                        "selector=Steve",
                        "--trace-file",
                        selectorTraceFile.toString(),
                    ),
                )
            }

        assertTrue("trace OK say visible trace output" in outputFiltered, outputFiltered)
        assertFalse("trace OK scoreboard objectives add runs dummy" in outputFiltered, outputFiltered)
        val outputTraceJson = Files.readString(outputTraceFile)
        assertTrue("\"outputEvents\"" in outputTraceJson, outputTraceJson)
        assertTrue("\"text\": \"<Server> visible trace output\"" in outputTraceJson, outputTraceJson)
        assertFalse("\"root\": \"scoreboard\"" in outputTraceJson, outputTraceJson)

        assertTrue("trace OK say selector target output" in selectorFiltered, selectorFiltered)
        assertFalse("trace OK scoreboard objectives add runs dummy" in selectorFiltered, selectorFiltered)
        val selectorTraceJson = Files.readString(selectorTraceFile)
        assertTrue("\"targets\"" in selectorTraceJson, selectorTraceJson)
        assertTrue("\"Steve\"" in selectorTraceJson, selectorTraceJson)
        assertFalse("\"root\": \"scoreboard\"" in selectorTraceJson, selectorTraceJson)
    }

    @Test
    fun `run filters traces by structured output payloads`() {
        val traceFile = Files.createTempFile("dps-cli-output-payload-filtered-trace", ".jsonl")

        val output =
            captureStdout {
                main(
                    arrayOf(
                        "run",
                        "--version",
                        "26.2",
                        "--mcfunction-text",
                        "say hidden payload trace\nplace structure demo:ruin 1 64 2\nscoreboard objectives add runs dummy",
                        "--trace",
                        "--trace-filter",
                        "output-channel=worldgen",
                        "--trace-filter",
                        "output-payload=id=demo:ruin",
                        "--trace-file",
                        traceFile.toString(),
                    ),
                )
            }

        assertTrue("trace OK place structure demo:ruin 1 64 2" in output, output)
        assertFalse("trace OK say hidden payload trace" in output, output)
        assertFalse("trace OK scoreboard objectives add runs dummy" in output, output)
        val traceJson = Files.readString(traceFile)
        assertTrue("\"channel\": \"worldgen\"" in traceJson, traceJson)
        assertTrue("\"id\": \"demo:ruin\"" in traceJson, traceJson)
        assertFalse("\"root\": \"say\"" in traceJson, traceJson)
        assertFalse("\"root\": \"scoreboard\"" in traceJson, traceJson)
    }

    @Test
    fun `run filters traces by diagnostics`() {
        val traceFile = Files.createTempFile("dps-cli-diagnostic-filtered-trace", ".jsonl")

        val output =
            captureStdout {
                main(
                    arrayOf(
                        "run",
                        "--version",
                        "26.2",
                        "--allow-command-failure",
                        "--command",
                        "scoreboard players set #bad missing 1",
                        "--command",
                        "say hidden after diagnostic",
                        "--trace",
                        "--trace-filter",
                        "error-code=COMMAND_ERROR",
                        "--trace-filter",
                        "diagnostic=Unknown scoreboard objective",
                        "--trace-file",
                        traceFile.toString(),
                    ),
                )
            }

        assertTrue("trace ERR scoreboard players set #bad missing 1" in output, output)
        assertFalse("trace OK say hidden after diagnostic" in output, output)
        val traceJson = Files.readString(traceFile)
        assertTrue("\"errorCode\": \"COMMAND_ERROR\"" in traceJson, traceJson)
        assertTrue("\"errorMessage\": \"Unknown scoreboard objective 'missing'\"" in traceJson, traceJson)
        assertTrue("\"command\": \"scoreboard players set #bad missing 1\"" in traceJson, traceJson)
        assertFalse("\"command\": \"say hidden after diagnostic\"" in traceJson, traceJson)
    }

    @Test
    fun `run inline assertions can check snapshot diffs`() {
        val output =
            captureStdout {
                main(
                    arrayOf(
                        "run",
                        "--version",
                        "26.2",
                        "--mcfunction-text",
                        "scoreboard objectives add runs dummy\nscoreboard players set #inline_diff runs 8",
                        "--assert",
                        """{"snapshotDiff":{"path":"/scores/runs","kind":"added","contains":"\"#inline_diff\": 8","count":1}}""",
                        "--assert",
                        "diff:/scores/runs=added",
                    ),
                )
            }

        assertTrue("OK version=26.2" in output, output)
    }

    @Test
    fun `run inline assertions can check snapshot shorthand`() {
        val output =
            captureStdout {
                main(
                    arrayOf(
                        "run",
                        "--version",
                        "26.2",
                        "--mcfunction-text",
                        "scoreboard objectives add runs dummy\nscoreboard players set #snapshot runs 9",
                        "--assert",
                        "snapshot:scores.runs.#snapshot=9",
                        "--assert",
                        "snapshot:scores.runs?",
                        "--assert",
                        "snapshot:scores.runs.#missing!",
                    ),
                )
            }

        assertTrue("OK version=26.2" in output, output)
    }

    @Test
    fun `run reads assertions from json files`() {
        val assertionsFile = Files.createTempFile("dps-cli-assertions", ".json")
        Files.writeString(
            assertionsFile,
            """
            {
              "assertions": [
                { "score": { "target": "#file_assert", "objective": "runs", "equals": 11 } },
                { "output": { "command": "say", "contains": "assert file ok", "count": 1 } }
              ]
            }
            """.trimIndent(),
        )

        val output =
            captureStdout {
                main(
                    arrayOf(
                        "run",
                        "--version",
                        "26.2",
                        "--mcfunction-text",
                        "scoreboard objectives add runs dummy\nscoreboard players set #file_assert runs 11\nsay assert file ok",
                        "--assert-file",
                        assertionsFile.toString(),
                    ),
                )
            }

        assertTrue("OK version=26.2" in output, output)
    }

    @Test
    fun `run reads shorthand assertions from text files`() {
        val assertionsFile = Files.createTempFile("dps-cli-assertions", ".txt")
        Files.writeString(
            assertionsFile,
            """
            # generated assertions
            score:#line_assert:runs=3
            score:#line_assert:runs>=2

            player:Steve?
            output:line assert ok
            """.trimIndent(),
        )

        val output =
            captureStdout {
                main(
                    arrayOf(
                        "run",
                        "--version",
                        "26.2",
                        "--mcfunction-text",
                        "scoreboard objectives add runs dummy\nscoreboard players set #line_assert runs 3\nsay line assert ok",
                        "--assert-file",
                        assertionsFile.toString(),
                    ),
                )
            }

        assertTrue("OK version=26.2" in output, output)
    }

    @Test
    fun `run can print and write snapshot diff`() {
        val diffFile = Files.createTempFile("dps-cli-snapshot-diff", ".json")

        val output =
            captureStdout {
                main(
                    arrayOf(
                        "run",
                        "--version",
                        "26.2",
                        "--mcfunction-text",
                        "scoreboard objectives add runs dummy\nscoreboard players set #diff runs 5",
                        "--snapshot-diff",
                        "--snapshot-diff-file",
                        diffFile.toString(),
                    ),
                )
            }

        assertTrue("+ /scores/runs =" in output, output)
        assertTrue("\"#diff\": 5" in output, output)
        assertTrue("snapshot diff written: $diffFile" in output, output)
        val diffJson = Files.readString(diffFile)
        assertTrue("\"path\": \"/scores/runs\"" in diffJson, diffJson)
        assertTrue("\"kind\": \"added\"" in diffJson, diffJson)
    }
}
