package moe.afox.dpsandbox.cli

import moe.afox.dpsandbox.core.AdvancementProgress
import moe.afox.dpsandbox.core.Datapack
import moe.afox.dpsandbox.core.DatapackSandbox
import moe.afox.dpsandbox.core.ResourceLocation
import moe.afox.dpsandbox.core.VersionProfiles
import moe.afox.dpsandbox.core.createSandbox
import org.jline.reader.Candidate
import org.jline.reader.LineReader
import org.jline.reader.impl.DefaultParser
import java.lang.reflect.Proxy
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DpsCompleterTest {
    @Test
    fun `hint cache suppresses unchanged terminal status updates`() {
        val cache = DpsHintCache<String>()

        assertTrue(cache.update("first"))
        assertFalse(cache.update("first"))
        assertTrue(cache.update("second"))
        assertFalse(cache.update("second"))
    }

    @Test
    fun `multiline status hints default off on Windows terminals`() {
        assertFalse(DpsInlineHintPolicy.multilineDescriptionsEnabled("Windows 11", override = null))
        assertTrue(DpsInlineHintPolicy.multilineDescriptionsEnabled("Linux", override = null))
        assertTrue(DpsInlineHintPolicy.multilineDescriptionsEnabled("Windows 11", override = "true"))
        assertFalse(DpsInlineHintPolicy.multilineDescriptionsEnabled("Linux", override = "off"))
    }

    private fun completer(): DpsCompleter = DpsCompleter { createSandbox("26.1.2", listOf(Path.of("../core/src/test/resources/packs/counter"))) }

    private fun emptyCompleter(version: String): DpsCompleter =
        DpsCompleter {
            DatapackSandbox(
                profile = VersionProfiles.get(version),
                datapack = Datapack(emptyMap(), emptyList(), emptyList()),
            )
        }

    @Test
    fun `inline hints are single line and update by context`() {
        val completer = completer()

        val rootHint = completer.inlineHint("fun")
        val functionHint = completer.inlineHint("function")
        val functionIdHint = completer.inlineHint("function ")
        val emptyHint = completer.inlineHint("")

        assertTrue(rootHint == "[function]", rootHint)
        assertTrue(functionHint.startsWith(" <namespace:path>"), functionHint)
        assertTrue("demo:main" in functionIdHint, functionIdHint)
        assertTrue("load" in emptyHint, emptyHint)
        assertFalse("\n" in rootHint + functionHint + functionIdHint + emptyHint)
    }

    @Test
    fun `completes newly supported root commands and subcommands`() {
        val completer = completer()

        assertSuggests(completer, "attr", "attribute")
        assertSuggests(completer, "attribute Steve ", "minecraft:generic.max_health")
        assertSuggests(completer, "attribute Steve ", "minecraft:max_health")
        assertSuggests(completer, "attribute Steve minecraft:generic.max_health ", "base")
        assertSuggests(completer, "attribute Steve minecraft:generic.max_health base ", "set")
        assertSuggests(completer, "bo", "bossbar")
        assertSuggests(completer, "difficulty ", "hard")
        assertSuggests(completer, "fillbiome 0 0 0 1 1 1 ", "minecraft:plains")
        assertSuggests(completer, "forceload ", "add")
        assertSuggests(completer, "gamem", "gamemode")
        assertSuggests(completer, "gamemode ", "survival")
        assertSuggests(completer, "seed", "seed")
        assertSuggests(completer, "setworld", "setworldspawn")
        assertSuggests(completer, "spawnpoint ", "Steve")
        assertSuggests(completer, "spectate ", "@e")
        assertSuggests(completer, "spreadplayers 0 0 1 5 ", "true")
        assertSuggests(completer, "trigger", "trigger")
        assertSuggests(completer, "trigger objective ", "set")
        assertSuggests(completer, "worldb", "worldborder")
        assertSuggests(completer, "worldborder ", "damage")
        assertSuggests(completer, "worldborder warning ", "distance")
        assertSuggests(completer, "scoreboard objectives ", "modify")
        assertSuggests(completer, "scoreboard objectives modify runs ", "displayname")
        assertSuggests(completer, "scoreboard objectives modify runs rendertype ", "hearts")
        assertSuggests(completer, "scoreboard objectives ", "setdisplay")
        assertSuggests(completer, "scoreboard objectives setdisplay ", "sidebar")
        assertSuggests(completer, "scoreboard players ", "list")
        assertSuggests(completer, "scoreboard players ", "reset")
        assertSuggests(completer, "scoreboard players display ", "numberformat")
        assertFalse("players" in completer.suggestions("scoreboard players ").map { it.value })
        assertSuggests(completer, "schedule ", "clear")
        assertSuggests(completer, "advancement grant Steve ", "everything")
        assertSuggests(completer, "execute store result ", "score")
        assertSuggests(completer, "execute if ", "predicate")
        assertSuggests(completer, "execute if ", "blocks")
        assertSuggests(completer, "execute unless ", "loaded")
        assertSuggests(completer, "execute if dimension ", "minecraft:overworld")
        assertSuggests(completer, "execute if biome 0 64 0 ", "minecraft:plains")
        assertSuggests(completer, "execute if score Steve runs ", "matches")
        assertSuggests(completer, "execute if score Steve runs matches ", "0..")
        assertSuggests(completer, "execute run scoreboard players ", "operation")
        assertSuggests(completer, "data modify storage demo:dst path set ", "from")
        assertSuggests(completer, "data modify storage demo:dst path set from ", "storage")
        assertSuggests(completer, "give Steve ", "minecraft:apple")
        assertSuggests(completer, "effect give Steve ", "minecraft:speed")
        assertSuggests(completer, "item replace entity Steve ", "hotbar.0")
        assertSuggests(completer, "place ", "structure")
        assertSuggests(completer, "particle ", "minecraft:flame")
        assertSuggests(completer, "particle minecraft:flame ~ ~ ~ 0 0 0 0 16 ", "force")
        assertSuggests(completer, "particle minecraft:flame ~ ~ ~ 0 0 0 0 16 force ", "@a")
        assertSuggests(completer, "tellraw Steve ", "{\"text\":\"\"}")
        assertSuggests(completer, "title Steve actionbar ", "{\"text\":\"\"}")
        assertSuggests(completer, "title Steve times ", "10")
        assertSuggests(completer, "tr", "trace")
        assertSuggests(completer, "trace ", "on")
        assertSuggests(completer, "diff ", "last")
        assertSuggests(completer, "rerun ", "last")
        assertSuggests(completer, "reset ", "world")
        assertSuggests(completer, "load ", "fixture")
        assertSuggests(completer, "inspect ", "world")
        assertSuggests(completer, "inspect ", "worldborder")
        assertSuggests(completer, "inspect ", "team")
        assertSuggests(completer, "inspect ", "bossbar")
        assertSuggests(completer, "inspect ", "entity")
        assertSuggests(completer, "inspect ", "block")
        assertSuggests(completer, "inspect ", "biome")
        assertSuggests(completer, "inspect ", "item")
        assertSuggests(completer, "inspect ", "recipes")
        assertSuggests(completer, "inspect ", "advancement-progress")
        assertSuggests(completer, "inspect ", "raw")
        assertSuggests(completer, "inspect ", "gamerule")
        assertSuggests(completer, "inspect ", "random")
        assertSuggests(completer, "inspect ", "schedule")
        assertSuggests(completer, "inspect ", "forced-chunks")
        assertSuggests(completer, "inspect ", "scoreboard")
        assertSuggests(completer, "inspect scoreboard ", "displays")
        assertSuggests(completer, "inspect ", "event-traces")
        assertSuggests(completer, "inspect resources ", "function")
        assertSuggests(completer, "inspect registry ", "damage_types")

        val scoreSandbox = createSandbox("26.1.2", listOf(Path.of("../core/src/test/resources/packs/counter")))
        scoreSandbox.executeCommand("scoreboard objectives add runs dummy")
        scoreSandbox.executeCommand("scoreboard players set #counter runs 1")
        val scoreCompleter = DpsCompleter { scoreSandbox }
        assertSuggests(scoreCompleter, "scoreboard players operation Steve runs = ", "#counter")
        assertSuggests(scoreCompleter, "scoreboard players operation Steve runs = #counter ", "runs")

        val randomSandbox = createSandbox("26.1.2", listOf(Path.of("../core/src/test/resources/packs/counter")))
        randomSandbox.world.randomSequences["demo:seq"] = 42
        assertSuggests(DpsCompleter { randomSandbox }, "inspect random ", "demo:seq")
        randomSandbox.world.gamerules["doDaylightCycle"] = "false"
        assertSuggests(DpsCompleter { randomSandbox }, "inspect gamerule ", "doDaylightCycle")

        val uiSandbox = createSandbox("26.1.2", listOf(Path.of("../core/src/test/resources/packs/counter")))
        uiSandbox.executeCommand("team add red Red Team")
        uiSandbox.executeCommand("bossbar add demo:timer Timer")
        assertSuggests(DpsCompleter { uiSandbox }, "inspect team ", "red")
        assertSuggests(DpsCompleter { uiSandbox }, "inspect bossbar ", "demo:timer")

        val playerStateSandbox = createSandbox("26.1.2", listOf(Path.of("../core/src/test/resources/packs/counter")))
        val steve = playerStateSandbox.createPlayer("Steve")
        steve.recipes += ResourceLocation.parse("demo:toast")
        steve.advancementProgress[ResourceLocation.parse("demo:root")] =
            AdvancementProgress(linkedMapOf("start" to true))
        assertSuggests(DpsCompleter { playerStateSandbox }, "inspect recipes ", "Steve")
        assertSuggests(DpsCompleter { playerStateSandbox }, "inspect recipes Steve ", "demo:toast")
        assertSuggests(DpsCompleter { playerStateSandbox }, "inspect advancement-progress ", "Steve")
        assertSuggests(DpsCompleter { playerStateSandbox }, "inspect advancement-progress Steve ", "demo:root")
        assertSuggests(DpsCompleter { playerStateSandbox }, "inspect item ", "Steve")
        assertSuggests(DpsCompleter { playerStateSandbox }, "inspect item Steve ", "hotbar.0")
        assertSuggests(DpsCompleter { playerStateSandbox }, "inspect item Steve ", "enderchest.0")

        val entitySandbox = createSandbox("26.1.2", listOf(Path.of("../core/src/test/resources/packs/counter")))
        entitySandbox.executeCommand("""summon minecraft:zombie 1 64 2 {Tags:["mob"]}""")
        entitySandbox.executeCommand("setblock 0 64 0 minecraft:stone")
        entitySandbox.executeCommand("fillbiome 0 64 0 0 64 0 minecraft:forest")
        assertSuggests(DpsCompleter { entitySandbox }, "inspect entity ", "minecraft:zombie")
        assertSuggests(DpsCompleter { entitySandbox }, "inspect entity ", "mob")
        assertSuggests(DpsCompleter { entitySandbox }, "inspect block ", "0,64,0")
        assertSuggests(DpsCompleter { entitySandbox }, "inspect biome ", "0,64,0")
    }

    @Test
    fun `slash commands keep their slash in candidates`() {
        val completer = completer()

        assertSuggests(completer, "/fun", "/function")
    }

    @Test
    fun `JVM completion matches structured selector NBT and text component ranges`() {
        val sandbox =
            DatapackSandbox(
                profile = VersionProfiles.get("26.2"),
                datapack = Datapack(emptyMap(), emptyList(), emptyList()),
            )
        sandbox.createPlayer("Steve")
        sandbox.executeCommand("scoreboard objectives add runs dummy")
        val engine = DpsCompletionEngine { sandbox }

        val selectorSource = "execute as @e[ty"
        val selector = engine.rangedSuggestions(selectorSource).single { it.value == "type=" }
        assertEquals(selectorSource.indexOf("ty"), selector.start)
        assertFalse(selector.appendSpace)

        val nbtSource = "summon minecraft:zombie 0 0 0 {NoG"
        val nbt = engine.rangedSuggestions(nbtSource).single { it.value == "NoGravity:" }
        assertEquals(nbtSource.indexOf("NoG"), nbt.start)
        assertFalse(nbt.appendSpace)

        val textSource = "tellraw @s {\"co"
        val text = engine.rangedSuggestions(textSource).single { it.value == "\"color\":" }
        assertEquals(textSource.indexOf("\"co"), text.start)
        assertFalse(text.appendSpace)

        assertTrue(engine.suggestions("execute as @e[type=minecraft:zo").any { it.value == "minecraft:zombie" })
        assertTrue(engine.suggestions("execute as @e[scores={ru").any { it.value == "runs=" })
        assertTrue(engine.suggestions("tellraw @s {\"color\":\"gr").any { it.value == "\"green\"" })

        val completer = DpsCompleter { sandbox }
        val reader =
            Proxy.newProxyInstance(LineReader::class.java.classLoader, arrayOf(LineReader::class.java)) { _, method, _ ->
                error("Unexpected LineReader call: ${method.name}")
            } as LineReader
        val candidates = mutableListOf<Candidate>()
        completer.complete(reader, DefaultParser().parse(selectorSource, selectorSource.length), candidates)
        val jlineCandidate = candidates.single { it.value() == "@e[type=" }
        assertEquals(null, jlineCandidate.suffix())
    }

    @Test
    fun `root command suggestions are scoped by version profile`() {
        val latest = emptyCompleter("26.2")
        val legacy = emptyCompleter("1.20.4")

        assertSuggests(latest, "tran", "transfer")
        val legacyValues = legacy.suggestions("tran").map { it.value }
        assertFalse("transfer" in legacyValues, "transfer should not be suggested for 1.20.4: $legacyValues")
    }

    @Test
    fun `multiline hints describe the current command`() {
        val hint = DpsMultilineHints.describe("fun")

        assertTrue(hint.size >= 2, hint.joinToString("\n"))
        assertTrue("function <namespace:path>" in hint[0].toString(), hint[0].toString())
        assertTrue("run one loaded function" in hint[1].toString(), hint[1].toString())
        assertTrue("behavior: modeled" in hint[2].toString(), hint.joinToString("\n"))
    }

    @Test
    fun `catalog describes implemented partial root commands`() {
        val commands = DpsCommandCatalog.rootCommands(VersionProfiles.default).associateBy { it.value }
        val implementedRoots =
            listOf(
                "attribute",
                "datapack",
                "defaultgamemode",
                "difficulty",
                "fillbiome",
                "forceload",
                "gamemode",
                "place",
                "seed",
                "setworldspawn",
                "spawnpoint",
                "spectate",
                "spreadplayers",
                "trigger",
                "worldborder",
            )

        assertEquals("read or edit stored entity attributes", commands.getValue("attribute").description)
        assertEquals(CommandBehaviorLevel.MODELED, commands.getValue("attribute").behaviorLevel)
        assertEquals(CommandBehaviorLevel.OBSERVED_NOOP, commands.getValue("playsound").behaviorLevel)
        assertEquals(CommandBehaviorLevel.MODELED, commands.getValue("place").behaviorLevel)
        assertEquals(CommandBehaviorLevel.OBSERVED_NOOP, commands.getValue("ban").behaviorLevel)
        assertEquals("edit stored world border state", commands.getValue("worldborder").description)
        implementedRoots.forEach { root ->
            assertFalse(
                commands.getValue(root).description.startsWith("vanilla command:"),
                "$root should be listed as an implemented sandbox command",
            )
        }
    }

    private fun assertSuggests(
        completer: DpsCompleter,
        line: String,
        expected: String,
    ) {
        val values = completer.suggestions(line).map { it.value }
        assertTrue(expected in values, "Expected '$expected' in suggestions for '$line', got $values")
    }
}
