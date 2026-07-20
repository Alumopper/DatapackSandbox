package moe.afox.dpsandbox.cli

import com.google.gson.JsonParser
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunCommandTest : RunCommandTestSupport() {
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

        val output =
            captureStdout {
                main(arrayOf("run", "--mcfunction", functionFile.toString(), "--version", "26.2", "--snapshot"))
            }

        assertTrue("OK version=26.2" in output, output)
        assertTrue("\"#cli\": 9" in output, output)
    }

    @Test
    fun `run enforces command execution limit`() {
        val result =
            runCliProcess(
                "run",
                "--version",
                "26.2",
                "--max-commands",
                "1",
                "--command",
                "scoreboard objectives add runs dummy",
                "--command",
                "scoreboard players set #limit runs 1",
            )

        assertEquals(ExitCodes.UNSUPPORTED_OR_VERSION, result.exitCode, result.output)
        assertTrue("Command execution count exceeded sandbox limit 1" in result.output, result.output)
    }

    @Test
    fun `check enforces tick execution limit`() {
        val root = Files.createTempDirectory("dps-check-limits")
        val pack = root.resolve("pack")
        Files.createDirectories(pack)
        Files.writeString(pack.resolve("pack.mcmeta"), """{"pack":{"pack_format":107.1,"description":"limit test"}}""")
        val packPath = pack.toString().replace("\\", "\\\\")
        val manifest = root.resolve("limit.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.2",
              "packs": ["$packPath"],
              "steps": [
                { "ticks": 2 }
              ]
            }
            """.trimIndent(),
        )

        val result = runCliProcess("check", manifest.toString(), "--max-ticks-per-run", "1")

        assertEquals(ExitCodes.UNSUPPORTED_OR_VERSION, result.exitCode, result.output)
        assertTrue("Tick count 2 exceeds sandbox limit 1" in result.output, result.output)
    }

    @Test
    fun `run executes inline mcfunction text`() {
        val output =
            captureStdout {
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
        val output =
            captureStdout(
                stdin =
                    """
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
        val output =
            captureStdout(
                stdin =
                    """
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

        val output =
            captureStdout {
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

        val output =
            captureStdout {
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

        val output =
            captureStdout {
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
        val output =
            captureStdout {
                main(
                    arrayOf(
                        "run",
                        "--version",
                        "26.2",
                        "--mcfunction-text",
                        "scoreboard objectives add runs dummy\nscoreboard players set #short runs 5\nrandom reset demo:seq 42\nsay shorthand    ok\ntellraw Steve {\"text\":\"styled\",\"color\":\"yellow\",\"bold\":true}\ntellraw Steve {\"text\":\"multi   space\"}\nplace structure demo:ruin 1 64 2",
                        "--assert",
                        "score:#short:runs=5",
                        "--assert",
                        "score:#short:runs>=4",
                        "--assert",
                        "score:#short:runs<=6",
                        "--assert",
                        "output:shorthand",
                        "--assert",
                        "output-count:styled=1",
                        "--assert",
                        "output-order:3:styled",
                        "--assert",
                        "output-exact:styled",
                        "--assert",
                        "output-matches:shorthand\\s+ok",
                        "--assert",
                        "output-command:say=1",
                        "--assert",
                        "output-command:tellraw=2",
                        "--assert",
                        "output-command:place structure?",
                        "--assert",
                        "output-command:playsound!",
                        "--assert",
                        "output-channel:chat=3",
                        "--assert",
                        "output-channel:worldgen?",
                        "--assert",
                        "output-channel:sound!",
                        "--assert",
                        "output-target:Steve?",
                        "--assert",
                        "output-target:Alex!",
                        "--assert",
                        "output-normalized:shorthand ok",
                        "--assert",
                        "output-normalized-exact:multi space",
                        "--assert",
                        "output-normalized-matches:multi\\s+space",
                        "--assert",
                        "output-segment:styled|color=yellow|bold=true@Steve",
                        "--assert",
                        "output-segment-exact:styled|color=yellow|bold=true@Steve",
                        "--assert",
                        "output-segment-matches:sty.*|color=yellow@Steve",
                        "--assert",
                        "random-sequence:demo:seq=42",
                        "--assert",
                        "output-payload:place structure:placed=false",
                        "--assert",
                        "output-payload:place structure:id=demo:ruin",
                        "--assert",
                        "output-payload:place structure:position.y=64.0",
                        "--assert",
                        "output-payload:place structure:reason",
                    ),
                )
            }

        assertTrue("OK version=26.2" in output, output)
    }

    @Test
    fun `run accepts predicate and loot shorthand inline assertions`() {
        val result =
            runCliProcess(
                "run",
                "--version",
                "26.2",
                "--pack",
                "../examples/full-stack/pack",
                "--command",
                "item replace entity Steve weapon.mainhand with minecraft:carrot_on_a_stick",
                "--assert",
                "predicate:demo:has_carrot:player=Steve",
                "--assert",
                "loot:demo:gift:context=minecraft:advancement_reward:player=Steve:seed=42:count=1:item=minecraft:diamond",
            )

        assertEquals(0, result.exitCode, result.output)
        assertTrue("OK version=26.2" in result.output, result.output)
    }

    @Test
    fun `run accepts advancement criterion shorthand inline assertions`() {
        val result =
            runCliProcess(
                "run",
                "--version",
                "26.2",
                "--pack",
                "../examples/full-stack/pack",
                "--event",
                "player Steve item_used minecraft:carrot_on_a_stick",
                "--assert",
                "advancement:Steve:demo:use_carrot:criterion=use_carrot",
                "--assert",
                "advancement:Steve:demo:use_carrot:done=true",
            )

        assertEquals(0, result.exitCode, result.output)
        assertTrue("OK version=26.2" in result.output, result.output)
    }

    @Test
    fun `run accepts storage shorthand inline assertions`() {
        val output =
            captureStdout {
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
        val output =
            captureStdout {
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

        val output =
            captureStdout {
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

        val output =
            captureStdout {
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
        val output =
            captureStdout {
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
        val output =
            captureStdout {
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
                        "schedule noop demo:later 1t",
                        "--assert",
                        "entity:*=3",
                        "--assert",
                        "entity:minecraft:pig@fixture=1",
                        "--assert",
                        "entity:minecraft:cow>=1",
                        "--assert",
                        "warning=1",
                        "--assert",
                        "warning:Only 'schedule function'",
                        "--assert",
                        "unsupported=1",
                        "--assert",
                        "unsupported:Only 'schedule function'",
                    ),
                )
            }

        assertTrue("OK version=26.2" in output, output)
    }

    @Test
    fun `run accepts diagnostic shorthand for allowed command failures`() {
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
                        "say after allowed failure",
                        "--assert",
                        "diagnostic=1",
                        "--assert",
                        "diagnostic:COMMAND_ERROR=1",
                        "--assert",
                        "diagnostic:COMMAND_ERROR:Unknown scoreboard objective 'missing'=1",
                        "--assert",
                        "diagnostic:Unknown scoreboard objective",
                        "--assert",
                        "output:after allowed failure",
                    ),
                )
            }

        assertTrue("OK version=26.2" in output, output)
    }

    @Test
    fun `run report files include trace diagnostics`() {
        val reportFile = Files.createTempFile("dps-cli-diagnostic-report", ".json")

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
                        "say after diagnostic report",
                        "--assert",
                        "diagnostic:COMMAND_ERROR:Unknown scoreboard objective 'missing'=1",
                        "--report-file",
                        reportFile.toString(),
                    ),
                )
            }

        assertTrue("report written: $reportFile" in output, output)
        val report = JsonParser.parseString(Files.readString(reportFile)).asJsonObject
        val diagnostics = report.getAsJsonArray("diagnostics")
        assertEquals(1, report.get("diagnosticCount").asInt)
        assertEquals(1, diagnostics.size())
        val diagnostic = diagnostics.single().asJsonObject
        assertEquals("26.2", diagnostic.get("version").asString)
        assertEquals("COMMAND_ERROR", diagnostic.get("code").asString)
        assertEquals("scoreboard players set #bad missing 1", diagnostic.get("command").asString)
        assertTrue("Unknown scoreboard objective 'missing'" in diagnostic.get("message").asString)
        assertEquals("<arg:--command>", diagnostic.get("file").asString)
        assertEquals(1, diagnostic.get("line").asInt)
    }

    @Test
    fun `run accepts team and bossbar shorthand inline assertions`() {
        val output =
            captureStdout {
                main(
                    arrayOf(
                        "run",
                        "--version",
                        "26.2",
                        "--command",
                        "team add red",
                        "--command",
                        "team join red Steve",
                        "--command",
                        "bossbar add demo:progress Progress",
                        "--command",
                        "bossbar set demo:progress value 7",
                        "--command",
                        "bossbar set demo:progress max 10",
                        "--command",
                        "bossbar set demo:progress players Steve",
                        "--assert",
                        "team:red?",
                        "--assert",
                        "team:red@Steve",
                        "--assert",
                        "team:red=1",
                        "--assert",
                        "bossbar:demo:progress?",
                        "--assert",
                        "bossbar:demo:progress:value=7",
                        "--assert",
                        "bossbar:demo:progress:max=10",
                        "--assert",
                        "bossbar:demo:progress:player=Steve",
                    ),
                )
            }

        assertTrue("OK version=26.2" in output, output)
    }

    @Test
    fun `run accepts world shorthand inline assertions`() {
        val output =
            captureStdout {
                main(
                    arrayOf(
                        "run",
                        "--version",
                        "26.2",
                        "--command",
                        "time set 6000",
                        "--command",
                        "weather rain 40",
                        "--command",
                        "difficulty hard",
                        "--command",
                        "defaultgamemode creative",
                        "--assert",
                        "world:dayTime=6000",
                        "--assert",
                        "world:time=6000",
                        "--assert",
                        "world:weather=rain",
                        "--assert",
                        "world:weatherDuration=40",
                        "--assert",
                        "world:difficulty=hard",
                        "--assert",
                        "world:defaultGameMode=creative",
                    ),
                )
            }

        assertTrue("OK version=26.2" in output, output)
    }

    @Test
    fun `run accepts gamerule shorthand inline assertions`() {
        val output =
            captureStdout {
                main(
                    arrayOf(
                        "run",
                        "--version",
                        "26.2",
                        "--command",
                        "gamerule doDaylightCycle false",
                        "--command",
                        "gamerule maxEntityCramming 0",
                        "--assert",
                        "gamerule:doDaylightCycle=false",
                        "--assert",
                        "gamerule:doDaylightCycle?",
                        "--assert",
                        "gamerule:maxEntityCramming=0",
                        "--assert",
                        "gamerule:missingRule!",
                    ),
                )
            }

        assertTrue("OK version=26.2" in output, output)
    }

    @Test
    fun `run accepts scheduled function shorthand inline assertions`() {
        val output =
            captureStdout {
                main(
                    arrayOf(
                        "run",
                        "--version",
                        "26.2",
                        "--command",
                        "schedule function demo:scheduled 5t replace",
                        "--assert",
                        "scheduled:demo:scheduled=5",
                        "--assert",
                        "scheduled:demo:scheduled?",
                        "--assert",
                        "scheduled:demo:missing!",
                        "--snapshot",
                    ),
                )
            }

        assertTrue("OK version=26.2" in output, output)
        assertTrue("\"function\": \"demo:scheduled\"" in output, output)
        assertTrue("\"dueTick\": 5" in output, output)
    }

    @Test
    fun `run accepts forced chunk shorthand inline assertions`() {
        val output =
            captureStdout {
                main(
                    arrayOf(
                        "run",
                        "--version",
                        "26.2",
                        "--command",
                        "forceload add 0 0",
                        "--assert",
                        "forced-chunk:0,0?",
                        "--assert",
                        "forceload:1,1!",
                        "--snapshot",
                    ),
                )
            }

        assertTrue("OK version=26.2" in output, output)
        assertTrue("\"forcedChunks\"" in output, output)
        assertTrue("\"x\": 0" in output, output)
        assertTrue("\"z\": 0" in output, output)
    }

    @Test
    fun `run accepts scoreboard display shorthand inline assertions`() {
        val output =
            captureStdout {
                main(
                    arrayOf(
                        "run",
                        "--version",
                        "26.2",
                        "--command",
                        "scoreboard objectives add runs dummy",
                        "--command",
                        "scoreboard objectives setdisplay sidebar.team.red runs",
                        "--command",
                        "scoreboard objectives setdisplay list runs",
                        "--command",
                        "scoreboard objectives setdisplay list",
                        "--assert",
                        "scoreboard-display:sidebar.team.red=runs",
                        "--assert",
                        "scoreboard-display:sidebar.team.red?",
                        "--assert",
                        "scoreboard-display:list!",
                        "--snapshot",
                    ),
                )
            }

        assertTrue("OK version=26.2" in output, output)
        assertTrue("\"scoreboardDisplays\"" in output, output)
        assertTrue("\"slot\": \"sidebar.team.red\"" in output, output)
        assertTrue("\"objective\": \"runs\"" in output, output)
    }

    @Test
    fun `run accepts scoreboard objective shorthand inline assertions`() {
        val output =
            captureStdout {
                main(
                    arrayOf(
                        "run",
                        "--version",
                        "26.2",
                        "--command",
                        "scoreboard objectives add health dummy",
                        "--command",
                        "scoreboard objectives modify health displayname Health Points",
                        "--command",
                        "scoreboard objectives modify health rendertype hearts",
                        "--command",
                        "scoreboard objectives modify health displayautoupdate false",
                        "--assert",
                        "scoreboard-objective:health?",
                        "--assert",
                        "scoreboard-objective:health:displayName=Health Points",
                        "--assert",
                        "scoreboard-objective:health:renderType=hearts",
                        "--assert",
                        "scoreboard-objective:health:displayAutoUpdate=false",
                        "--assert",
                        "scoreboard-objective:missing!",
                        "--snapshot",
                    ),
                )
            }

        assertTrue("OK version=26.2" in output, output)
        assertTrue("\"objectiveDetails\"" in output, output)
        assertTrue("\"displayName\": \"Health Points\"" in output, output)
        assertTrue("\"renderType\": \"hearts\"" in output, output)
    }

    @Test
    fun `run accepts biome shorthand inline assertions`() {
        val output =
            captureStdout {
                main(
                    arrayOf(
                        "run",
                        "--version",
                        "26.2",
                        "--command",
                        "fillbiome 0 64 0 0 64 0 minecraft:forest",
                        "--assert",
                        "biome:0,64,0=minecraft:forest",
                    ),
                )
            }

        assertTrue("OK version=26.2" in output, output)
    }
}
