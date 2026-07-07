package moe.afox.dpsandbox.cli

import moe.afox.dpsandbox.core.createSandbox
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class ReplTest {
    @Test
    fun `prints clear feedback for manually entered commands`() {
        val repl = Repl(createSandbox("26.1.2", listOf(Path.of("../core/src/test/resources/packs/counter"))))

        val output = captureStdout {
            repl.handle("say hello")
        }

        assertTrue(output.contains("OK say hello (commands=1, gameTime=0, outputs=+1)"), output)
        assertTrue(output.contains("[0] chat say -> Steve <Server> hello"), output)
    }

    @Test
    fun `prints keyboard input event feedback`() {
        val repl = Repl(createSandbox("26.1.2", listOf(Path.of("../core/src/test/resources/packs/counter"))))

        val output = captureStdout {
            repl.handle("event player Steve key_input key.jump release")
        }

        assertTrue(output.contains("OK event player Steve key_input"), output)
        assertTrue(output.contains("input=keyboard:key.jump/release"), output)
    }

    @Test
    fun `prints block event position feedback`() {
        val repl = Repl(createSandbox("26.1.2", listOf(Path.of("../core/src/test/resources/packs/counter"))))

        val output = captureStdout {
            repl.handle("event player Steve block_placed minecraft:stone pos=1,64,2")
        }

        assertTrue(output.contains("OK event player Steve block_placed"), output)
        assertTrue(output.contains("blockPos=1,64,2"), output)
    }

    @Test
    fun `inspects player event traces`() {
        val repl = Repl(createSandbox("26.1.2", listOf(Path.of("../core/src/test/resources/packs/counter"))))

        val output = captureStdout {
            repl.handle("event player Steve block_placed minecraft:stone pos=1,64,2")
            repl.handle("inspect event-traces")
        }

        assertTrue(output.contains("\"type\": \"block_placed\""), output)
        assertTrue(output.contains("\"block\": \"minecraft:stone\""), output)
        assertTrue(output.contains("\"blockPos\""), output)
    }

    @Test
    fun `prints trace events when trace is enabled`() {
        val repl = Repl(createSandbox("26.1.2", listOf(Path.of("../core/src/test/resources/packs/counter"))))

        val output = captureStdout {
            repl.handle("trace on")
            repl.handle("say traced")
        }

        assertTrue(output.contains("OK trace on"), output)
        assertTrue(output.contains("trace OK say traced"), output)
    }

    @Test
    fun `prints last diff and reruns last command`() {
        val repl = Repl(createSandbox("26.1.2", listOf(Path.of("../core/src/test/resources/packs/counter"))))

        val output = captureStdout {
            repl.handle("scoreboard objectives add runs dummy")
            repl.handle("scoreboard players add #repl runs 2")
            repl.handle("diff last")
            repl.handle("rerun last")
            repl.handle("inspect score #repl runs")
        }

        assertTrue(output.contains("+ /scores/runs"), output)
        assertTrue(output.contains("rerun: scoreboard players add #repl runs 2"), output)
        assertTrue(output.lines().any { it.trim() == "4" }, output)
    }

    @Test
    fun `loads fixture files and resets world`() {
        val repl = Repl(createSandbox("26.1.2", listOf(Path.of("../core/src/test/resources/packs/counter"))))
        val fixture = Files.createTempFile("dps-repl-fixture", ".json")
        Files.writeString(
            fixture,
            """
            {
              "scores": [
                { "target": "#fixture", "objective": "ready", "value": 3 }
              ]
            }
            """.trimIndent(),
        )

        val output = captureStdout {
            repl.handle("load fixture $fixture")
            repl.handle("inspect score #fixture ready")
            repl.handle("reset world")
            repl.handle("inspect score #fixture ready")
        }

        assertTrue(output.contains("OK load fixture $fixture"), output)
        assertTrue(output.lines().any { it.trim() == "3" }, output)
        assertTrue(output.contains("OK reset world"), output)
        assertTrue(output.lines().last { it.trim().toIntOrNull() != null }.trim() == "0", output)
    }

    @Test
    fun `inspects random sequence state`() {
        val sandbox = createSandbox("26.1.2", listOf(Path.of("../core/src/test/resources/packs/counter")))
        sandbox.world.randomSequences["demo:seq"] = 42
        val repl = Repl(sandbox)

        val output = captureStdout {
            repl.handle("inspect random")
            repl.handle("inspect random demo:seq")
            repl.handle("inspect random demo:missing")
        }

        assertTrue(output.contains("demo:seq = 42"), output)
        assertTrue(output.lines().any { it.trim() == "42" }, output)
        assertTrue(output.contains("<missing>"), output)
    }

    @Test
    fun `inspects gamerule state`() {
        val repl = Repl(createSandbox("26.1.2", listOf(Path.of("../core/src/test/resources/packs/counter"))))

        val output = captureStdout {
            repl.handle("gamerule doDaylightCycle false")
            repl.handle("gamerule maxEntityCramming 0")
            repl.handle("inspect gamerule")
            repl.handle("inspect gamerule doDaylightCycle")
            repl.handle("inspect gamerule missingRule")
        }

        assertTrue(output.contains("gamerule doDaylightCycle = false"), output)
        assertTrue(output.contains("gamerule maxEntityCramming = 0"), output)
        assertTrue(output.lines().any { it.trim() == "false" }, output)
        assertTrue(output.contains("<missing>"), output)
    }

    @Test
    fun `inspects scheduled function state`() {
        val repl = Repl(createSandbox("26.1.2", listOf(Path.of("../core/src/test/resources/packs/counter"))))

        val output = captureStdout {
            repl.handle("function demo:main")
            repl.handle("inspect schedule")
        }

        assertTrue(output.contains("scheduled demo:scheduled dueTick=1 remaining=1"), output)
        assertTrue(output.contains("outputs=+"), output)
    }

    @Test
    fun `inspects forced chunk state`() {
        val repl = Repl(createSandbox("26.1.2", listOf(Path.of("../core/src/test/resources/packs/counter"))))

        val output = captureStdout {
            repl.handle("forceload add 0 0")
            repl.handle("forceload add 16 16")
            repl.handle("inspect forced-chunks")
        }

        assertTrue(output.contains("forcedChunks count=2"), output)
        assertTrue(output.contains("forcedChunk 0,0"), output)
        assertTrue(output.contains("forcedChunk 1,1"), output)
    }

    @Test
    fun `inspects scoreboard UI state`() {
        val repl = Repl(createSandbox("26.1.2", listOf(Path.of("../core/src/test/resources/packs/counter"))))

        val output = captureStdout {
            repl.handle("scoreboard objectives add health dummy")
            repl.handle("scoreboard objectives modify health displayname Health Points")
            repl.handle("scoreboard objectives modify health rendertype hearts")
            repl.handle("scoreboard objectives modify health displayautoupdate false")
            repl.handle("scoreboard objectives setdisplay sidebar.team.red health")
            repl.handle("inspect scoreboard")
            repl.handle("inspect scoreboard displays")
        }

        assertTrue(output.contains("objective health criteria=dummy displayName=Health Points renderType=hearts displayAutoUpdate=false"), output)
        assertTrue(output.contains("display sidebar.team.red = health"), output)
    }

    @Test
    fun `inspects team and bossbar UI state`() {
        val repl = Repl(createSandbox("26.1.2", listOf(Path.of("../core/src/test/resources/packs/counter"))))

        val output = captureStdout {
            repl.handle("team add red Red Team")
            repl.handle("team join red Steve Alex")
            repl.handle("team modify red color blue")
            repl.handle("team modify red friendlyFire false")
            repl.handle("bossbar add demo:timer Timer")
            repl.handle("bossbar set demo:timer value 5")
            repl.handle("bossbar set demo:timer max 20")
            repl.handle("bossbar set demo:timer color red")
            repl.handle("bossbar set demo:timer style notched_10")
            repl.handle("bossbar set demo:timer visible false")
            repl.handle("bossbar set demo:timer players Steve")
            repl.handle("inspect team")
            repl.handle("inspect team red")
            repl.handle("inspect team missing")
            repl.handle("inspect bossbar")
            repl.handle("inspect bossbar demo:timer")
            repl.handle("inspect bossbar demo:missing")
        }

        assertTrue(
            output.contains("team red displayName=Red Team members=[Alex, Steve] options=[color=blue, friendlyFire=false]"),
            output,
        )
        assertTrue(
            output.contains("bossbar demo:timer name=Timer value=5 max=20 color=red style=notched_10 visible=false players=[Steve]"),
            output,
        )
        assertTrue(output.lines().count { it.trim() == "<missing>" } >= 2, output)
    }

    @Test
    fun `inspects world state and world border`() {
        val sandbox = createSandbox("26.1.2", listOf(Path.of("../core/src/test/resources/packs/counter")))
        val repl = Repl(sandbox)

        val output = captureStdout {
            repl.handle("time set 6000")
            repl.handle("weather thunder 120")
            repl.handle("difficulty hard")
            repl.handle("defaultgamemode creative")
            repl.handle("setworldspawn 4 70 5 90")
            sandbox.world.tickFrozen = true
            sandbox.world.tickRate = 30.0
            repl.handle("worldborder center 5 -6")
            repl.handle("worldborder set 100")
            repl.handle("worldborder damage buffer 3")
            repl.handle("worldborder damage amount 0.5")
            repl.handle("worldborder warning distance 8")
            repl.handle("worldborder warning time 20")
            repl.handle("inspect world")
            repl.handle("inspect worldborder")
        }

        assertTrue(output.contains("world gameTime=0 dayTime=6000 weather=thunder"), output)
        assertTrue(output.contains("weatherDuration=120"), output)
        assertTrue(output.contains("difficulty=hard defaultGameMode=creative"), output)
        assertTrue(
            output.contains("worldSpawn x=4.0 y=70.0 z=5.0 dimension=minecraft:overworld angle=90.0 forced=false"),
            output,
        )
        assertTrue(output.contains("tick rate=30.0 frozen=true"), output)
        assertTrue(output.contains("worldBorder center=5.0,-6.0 size=100.0 targetSize=100.0"), output)
        assertTrue(output.contains("damageBuffer=3.0 damageAmount=0.5 warningDistance=8 warningTime=20"), output)
    }

    @Test
    fun `inspects raw datapack resources`() {
        val pack = Files.createTempDirectory("dps-repl-raw-pack")
        Files.writeString(
            pack.resolve("pack.mcmeta"),
            """
            {
              "pack": {
                "pack_format": 107.1,
                "description": "raw resource test pack"
              }
            }
            """.trimIndent(),
        )
        val damageTypeRoot = pack.resolve("data").resolve("demo").resolve("damage_type")
        Files.createDirectories(damageTypeRoot)
        Files.writeString(
            damageTypeRoot.resolve("debug_damage.json"),
            """
            {
              "message_id": "debug",
              "scaling": "never",
              "exhaustion": 0.0,
              "marker": "raw"
            }
            """.trimIndent(),
        )

        val repl = Repl(createSandbox("26.2", listOf(pack)))
        val output = captureStdout {
            repl.handle("inspect raw")
            repl.handle("inspect raw damage_type")
            repl.handle("inspect raw damage_type demo:debug_damage")
            repl.handle("inspect resources damage_type")
        }

        assertTrue(output.contains("damage_type 1"), output)
        assertTrue(output.contains("demo:debug_damage file="), output)
        assertTrue(output.contains("\"marker\": \"raw\""), output)
        assertTrue(output.contains("damage_type demo:debug_damage observed-noop active"), output)
    }

    @Test
    fun `inspect resources prints summary overlays and missing references`() {
        val dir = Files.createTempDirectory("dps-repl-resources")
        val first = writeResourceDebugPack(dir, "first", "one", includeMissingLoad = true)
        val second = writeResourceDebugPack(dir, "second", "two", includeMissingLoad = false)
        val repl = Repl(createSandbox("26.2", listOf(first, second)))

        val output = captureStdout {
            repl.handle("inspect resources")
        }

        assertTrue(output.contains("resources 26.2 functions=2"), output)
        assertTrue(output.contains("overridden=1"), output)
        assertTrue(output.contains("overlay recipe demo:marker modeled active"), output)
        assertTrue(output.contains("overlay recipe demo:marker modeled overridden"), output)
        assertTrue(output.contains("missing-reference #minecraft:load -> function demo:missing_load"), output)
        assertTrue(output.contains("recipe demo:marker modeled active pack="), output)
    }

    @Test
    fun `inspect registry prints group entries and profile source`() {
        val dir = Files.createTempDirectory("dps-repl-registry")
        val pack = writeResourceDebugPack(dir, "registry", "profile", includeMissingLoad = false)
        val repl = Repl(createSandbox("26.2", listOf(pack)))

        val output = captureStdout {
            repl.handle("inspect registry damage_types")
            repl.handle("inspect registry loot_conditions")
            repl.handle("inspect registry missing")
        }

        assertTrue(output.contains("registry damage_types count=5 source=profile:26.2"), output)
        assertTrue(output.contains("registry damage_types minecraft:generic source=profile:26.2"), output)
        assertTrue(output.contains("registry loot_conditions minecraft:random_chance source=profile:26.2"), output)
        assertTrue(output.contains("<missing registry group missing>"), output)
    }

    private fun writeResourceDebugPack(root: Path, name: String, marker: String, includeMissingLoad: Boolean): Path {
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
        Files.writeString(functionRoot.resolve("$marker.mcfunction"), "say $marker")

        val recipeRoot = pack.resolve("data").resolve("demo").resolve("recipe")
        Files.createDirectories(recipeRoot)
        Files.writeString(
            recipeRoot.resolve("marker.json"),
            """
            {
              "type": "minecraft:crafting_shapeless",
              "category": "misc",
              "ingredients": ["minecraft:stone"],
              "result": { "id": "minecraft:stone", "count": 1 },
              "marker": "$marker"
            }
            """.trimIndent(),
        )

        if (includeMissingLoad) {
            val tagRoot = pack.resolve("data").resolve("minecraft").resolve("tags").resolve("function")
            Files.createDirectories(tagRoot)
            Files.writeString(tagRoot.resolve("load.json"), """{"values":["demo:missing_load"]}""")
        }
        return pack
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
