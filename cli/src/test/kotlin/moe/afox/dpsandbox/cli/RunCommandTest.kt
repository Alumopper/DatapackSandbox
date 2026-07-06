package moe.afox.dpsandbox.cli

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `run reads stdin as mcfunction text`() {
        val output = captureStdout(
            stdin = """
            scoreboard objectives add runs dummy
            scoreboard players set #stdin runs 8
            """.trimIndent(),
        ) {
            main(
                arrayOf(
                    "run",
                    "--version",
                    "26.2",
                    "--stdin",
                    "--snapshot",
                ),
            )
        }

        assertTrue("OK version=26.2" in output, output)
        assertTrue("\"#stdin\": 8" in output, output)
    }

    @Test
    fun `run reads stdin as raw command lines`() {
        val output = captureStdout(
            stdin = """
            scoreboard objectives add runs dummy
            scoreboard players set #stdin_commands runs 4
            """.trimIndent(),
        ) {
            main(
                arrayOf(
                    "run",
                    "--version",
                    "26.2",
                    "--stdin",
                    "--stdin-mode",
                    "commands",
                    "--snapshot",
                ),
            )
        }

        assertTrue("OK version=26.2" in output, output)
        assertTrue("\"#stdin_commands\": 4" in output, output)
    }

    @Test
    fun `run executes multiple command files in order`() {
        val first = Files.createTempFile("dps-cli-commands-first", ".txt")
        val second = Files.createTempFile("dps-cli-commands-second", ".txt")
        Files.writeString(
            first,
            """
            scoreboard objectives add runs dummy
            scoreboard players set #command_files runs 2
            """.trimIndent(),
        )
        Files.writeString(second, "scoreboard players add #command_files runs 3")

        val output = captureStdout {
            main(
                arrayOf(
                    "run",
                    "--version",
                    "26.2",
                    "--command-file",
                    first.toString(),
                    "--command-file",
                    second.toString(),
                    "--assert",
                    "score:#command_files:runs=5",
                    "--snapshot",
                ),
            )
        }

        assertTrue("OK version=26.2" in output, output)
        assertTrue("\"#command_files\": 5" in output, output)
    }

    @Test
    fun `run applies world fixtures and inline assertions without a pack`() {
        val worldFile = Files.createTempFile("dps-cli-world", ".json")
        Files.writeString(
            worldFile,
            """
            {
              "seed": 42,
              "players": [
                {
                  "name": "Alex",
                  "inventory": [
                    { "id": "minecraft:stick", "count": 2 }
                  ]
                }
              ],
              "scores": [
                { "target": "#fixture", "objective": "ready", "value": 1 }
              ]
            }
            """.trimIndent(),
        )

        val output = captureStdout {
            main(
                arrayOf(
                    "run",
                    "--version",
                    "26.2",
                    "--world",
                    worldFile.toString(),
                    "--assert",
                    """{"world":{"seed":42}}""",
                    "--assert",
                    """{"score":{"target":"#fixture","objective":"ready","equals":1}}""",
                    "--assert",
                    """{"item":{"player":"Alex","id":"minecraft:stick","count":2}}""",
                    "--snapshot",
                ),
            )
        }

        assertTrue("OK version=26.2" in output, output)
        assertTrue("\"seed\": 42" in output, output)
        assertTrue("\"#fixture\": 1" in output, output)
    }

    @Test
    fun `run can override world fixture seed`() {
        val worldFile = Files.createTempFile("dps-cli-world-seed", ".json")
        Files.writeString(worldFile, """{"seed":42}""")

        val output = captureStdout {
            main(
                arrayOf(
                    "run",
                    "--version",
                    "26.2",
                    "--world",
                    worldFile.toString(),
                    "--seed",
                    "99",
                    "--assert",
                    """{"world":{"seed":99}}""",
                    "--snapshot",
                ),
            )
        }

        assertTrue("OK version=26.2" in output, output)
        assertTrue("\"seed\": 99" in output, output)
    }

    @Test
    fun `run accepts shorthand inline assertions`() {
        val output = captureStdout {
            main(
                arrayOf(
                    "run",
                    "--version",
                    "26.2",
                    "--mcfunction-text",
                    "scoreboard objectives add runs dummy\nscoreboard players set #short runs 5\nsay shorthand    ok",
                    "--assert",
                    "score:#short:runs=5",
                    "--assert",
                    "score:#short:runs>=4",
                    "--assert",
                    "score:#short:runs<=6",
                    "--assert",
                    "output:shorthand",
                    "--assert",
                    "output-normalized:shorthand ok",
                ),
            )
        }

        assertTrue("OK version=26.2" in output, output)
    }

    @Test
    fun `run accepts storage shorthand inline assertions`() {
        val output = captureStdout {
            main(
                arrayOf(
                    "run",
                    "--version",
                    "26.2",
                    "--command",
                    "data merge storage demo:env {ready:true}",
                    "--assert",
                    "storage:demo:env?",
                    "--assert",
                    "storage:demo:env:ready=true",
                    "--assert",
                    "storage:demo:env:absent!",
                ),
            )
        }

        assertTrue("OK version=26.2" in output, output)
    }

    @Test
    fun `run accepts item shorthand inline assertions`() {
        val output = captureStdout {
            main(
                arrayOf(
                    "run",
                    "--version",
                    "26.2",
                    "--command",
                    "give Steve minecraft:stick 3",
                    "--assert",
                    "item:Steve:minecraft:stick=3",
                    "--assert",
                    "item:Steve:minecraft:stick>=2",
                    "--assert",
                    "item:Steve:minecraft:stick<=4",
                    "--assert",
                    "item:Steve:minecraft:stick@0=3",
                ),
            )
        }

        assertTrue("OK version=26.2" in output, output)
    }

    @Test
    fun `run accepts player shorthand inline assertions`() {
        val worldFile = Files.createTempFile("dps-cli-player", ".json")
        Files.writeString(
            worldFile,
            """
            {
              "players": [
                {
                  "name": "Alex",
                  "xp": 7,
                  "health": 18.5,
                  "food": 19,
                  "gameMode": "creative",
                  "dimension": "minecraft:the_nether",
                  "selectedSlot": 2,
                  "inventory": [
                    { "id": "minecraft:stick", "count": 1 }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        val output = captureStdout {
            main(
                arrayOf(
                    "run",
                    "--version",
                    "26.2",
                    "--world",
                    worldFile.toString(),
                    "--assert",
                    "player:Alex?",
                    "--assert",
                    "player:Missing!",
                    "--assert",
                    "player:Alex:xp=7",
                    "--assert",
                    "player:Alex:health=18.5",
                    "--assert",
                    "player:Alex:food=19",
                    "--assert",
                    "player:Alex:gamemode=creative",
                    "--assert",
                    "player:Alex:dimension=minecraft:the_nether",
                    "--assert",
                    "player:Alex:slot=2",
                    "--assert",
                    "player:Alex:inventoryCount=1",
                ),
            )
        }

        assertTrue("OK version=26.2" in output, output)
    }

    @Test
    fun `run accepts advancement shorthand inline assertions`() {
        val dir = Files.createTempDirectory("dps-cli-advancement")
        val pack = writeAdvancementPack(dir.resolve("pack"))
        val worldFile = dir.resolve("world.json")
        Files.writeString(worldFile, """{"players":[{"name":"Steve"}]}""")

        val output = captureStdout {
            main(
                arrayOf(
                    "run",
                    "--version",
                    "26.2",
                    "--pack",
                    pack.toString(),
                    "--world",
                    worldFile.toString(),
                    "--command",
                    "advancement grant Steve only demo:shorthand done",
                    "--assert",
                    "advancement:Steve:demo:shorthand",
                    "--assert",
                    "advancement:Steve:demo:shorthand=true",
                ),
            )
        }

        assertTrue("OK version=26.2" in output, output)
    }

    @Test
    fun `run accepts trace shorthand inline assertions`() {
        val output = captureStdout {
            main(
                arrayOf(
                    "run",
                    "--version",
                    "26.2",
                    "--mcfunction-text",
                    "say trace shorthand\nscoreboard objectives add traced dummy\nscoreboard players set #trace traced 1",
                    "--assert",
                    "trace:say=1",
                    "--assert",
                    "trace:scoreboard=2",
                    "--assert",
                    "trace:players set #trace",
                    "--assert",
                    "trace-output:trace shorthand@Steve",
                ),
            )
        }

        assertTrue("OK version=26.2" in output, output)
    }

    @Test
    fun `run accepts entity and warning shorthand inline assertions`() {
        val output = captureStdout {
            main(
                arrayOf(
                    "run",
                    "--version",
                    "26.2",
                    "--command",
                    """summon minecraft:pig 0 0 0 {Tags:["fixture"]}""",
                    "--command",
                    "summon minecraft:cow 0 0 0",
                    "--command",
                    "ban Steve",
                    "--assert",
                    "entity:*=3",
                    "--assert",
                    "entity:minecraft:pig@fixture=1",
                    "--assert",
                    "entity:minecraft:cow>=1",
                    "--assert",
                    "warning=1",
                    "--assert",
                    "warning:Command 'ban'",
                    "--assert",
                    "unsupported=1",
                    "--assert",
                    "unsupported:Command 'ban'",
                ),
            )
        }

        assertTrue("OK version=26.2" in output, output)
    }

    @Test
    fun `run injects player events for inline assertions`() {
        val output = captureStdout {
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

        val output = captureStdout {
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

        val output = captureStdout {
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
    fun `run uses folder and zip packs as mcfunction dependencies`() {
        val root = Files.createTempDirectory("dps-cli-function-deps")
        val folderPack = writeDependencyPack(root.resolve("folder-pack"), "folder", "scoreboard players add #cli_deps runs 2")
        val zipPack = zipPack(writeDependencyPack(root.resolve("zip-pack"), "zip", "scoreboard players add #cli_deps runs 4"), root.resolve("zip-pack.zip"))

        val output = captureStdout {
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

        val output = captureStdout {
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

        val output = captureStdout {
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

        val output = captureStdout {
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

        val result = runCliProcess(
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
    fun `run strict mode fails unsupported commands and missing resource references`() {
        val unsupportedResult = runCliProcess(
            "run",
            "--version",
            "26.2",
            "--strict",
            "--command",
            "ban Steve",
        )
        assertEquals(ExitCodes.UNSUPPORTED_OR_VERSION, unsupportedResult.exitCode, unsupportedResult.output)
        assertTrue("Command 'ban' is not implemented" in unsupportedResult.output, unsupportedResult.output)

        val dir = Files.createTempDirectory("dps-cli-strict-missing-resource")
        val pack = writeMissingReferencePack(dir.resolve("pack"))
        val missingResult = runCliProcess(
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

        val output = captureStdout {
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

        val output = captureStdout {
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

        val output = captureStdout {
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

        val outputFiltered = captureStdout {
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
        val selectorFiltered = captureStdout {
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
    fun `run inline assertions can check snapshot diffs`() {
        val output = captureStdout {
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

        val output = captureStdout {
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

        val output = captureStdout {
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

        val output = captureStdout {
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

    @Test
    fun `version lists supported datapack formats`() {
        val output = captureStdout {
            main(arrayOf("version"))
        }

        assertTrue("1.20.4 java=17 pack_format=26 data=3700" in output, output)
        assertTrue("26.1.2 java=25 pack_format=101.1" in output, output)
        assertTrue("26.2 java=25 pack_format=107.1 data=4903 default" in output, output)
    }

    @Test
    fun `version reports profile diffs`() {
        val output = captureStdout {
            main(arrayOf("version", "1.20.4", "26.2"))
        }

        assertTrue("profile diff 1.20.4 -> 26.2" in output, output)
        assertTrue("java: 17 -> 25" in output, output)
        assertTrue("pack_format: 26 -> 107.1" in output, output)
        assertTrue("nbt_schema: 1.20.4:1.20.4 -> 26.2:26.2" in output, output)
        assertTrue("command_roots: added=transfer" in output, output)
    }

    @Test
    fun `version renders markdown docs table`() {
        val output = captureStdout {
            main(arrayOf("version", "--docs"))
        }

        assertTrue("| Profile | Java | Data version | Data pack format | NBT schema | Resource directories |" in output, output)
        assertTrue("| `1.20.4` | 17 | 3700 | 26 | `1.20.4:1.20.4` | `functions`, `loot_tables`, `predicates`, `advancements` |" in output, output)
        assertTrue("| `26.2` | 25 | 4903 | 107.1 | `26.2:26.2` |" in output, output)
    }

    private fun captureStdout(stdin: String? = null, block: () -> Unit): String {
        val original = System.out
        val originalIn = System.`in`
        val bytes = ByteArrayOutputStream()
        System.setOut(PrintStream(bytes, true, Charsets.UTF_8))
        if (stdin != null) {
            System.setIn(ByteArrayInputStream(stdin.toByteArray(Charsets.UTF_8)))
        }
        return try {
            block()
            bytes.toString(Charsets.UTF_8)
        } finally {
            System.setOut(original)
            System.setIn(originalIn)
        }
    }

    private fun runCliProcess(vararg args: String): ProcessResult {
        val javaBinary = Path.of(
            System.getProperty("java.home"),
            "bin",
            if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java",
        )
        val process = ProcessBuilder(
            listOf(
                javaBinary.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                "moe.afox.dpsandbox.cli.MainKt",
            ) + args,
        )
            .redirectErrorStream(true)
            .start()

        val finished = process.waitFor(30, TimeUnit.SECONDS)
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        if (!finished) {
            process.destroyForcibly()
            error("CLI process timed out:${System.lineSeparator()}$output")
        }
        return ProcessResult(process.exitValue(), output)
    }

    private data class ProcessResult(val exitCode: Int, val output: String)

    private fun writeDependencyPack(root: Path, functionName: String, body: String): Path {
        Files.createDirectories(root)
        Files.writeString(
            root.resolve("pack.mcmeta"),
            """
            {
              "pack": {
                "pack_format": 107.1,
                "description": "temporary dependency pack"
              }
            }
            """.trimIndent(),
        )
        val functionRoot = root.resolve("data").resolve("demo").resolve("function")
        Files.createDirectories(functionRoot)
        Files.writeString(functionRoot.resolve("$functionName.mcfunction"), body)
        return root
    }

    private fun writeMissingReferencePack(root: Path): Path {
        Files.createDirectories(root)
        Files.writeString(
            root.resolve("pack.mcmeta"),
            """
            {
              "pack": {
                "pack_format": 107.1,
                "description": "missing resource reference test"
              }
            }
            """.trimIndent(),
        )
        val tagRoot = root.resolve("data").resolve("minecraft").resolve("tags").resolve("function")
        Files.createDirectories(tagRoot)
        Files.writeString(tagRoot.resolve("load.json"), """{"values":["demo:missing_load"]}""")
        return root
    }

    private fun writeAdvancementPack(root: Path): Path {
        Files.createDirectories(root)
        Files.writeString(
            root.resolve("pack.mcmeta"),
            """
            {
              "pack": {
                "pack_format": 107.1,
                "description": "temporary advancement pack"
              }
            }
            """.trimIndent(),
        )
        val advancementRoot = root.resolve("data").resolve("demo").resolve("advancement")
        Files.createDirectories(advancementRoot)
        Files.writeString(
            advancementRoot.resolve("shorthand.json"),
            """
            {
              "criteria": {
                "done": {
                  "trigger": "minecraft:tick"
                }
              }
            }
            """.trimIndent(),
        )
        return root
    }

    private fun zipPack(root: Path, output: Path): Path {
        ZipOutputStream(Files.newOutputStream(output)).use { zip ->
            Files.walk(root).use { walk ->
                walk.filter { Files.isRegularFile(it) }.forEach { file ->
                    val entryName = root.relativize(file).toString().replace('\\', '/')
                    zip.putNextEntry(ZipEntry(entryName))
                    Files.copy(file, zip)
                    zip.closeEntry()
                }
            }
        }
        return output
    }
}
