package moe.afox.dpsandbox.cli

import moe.afox.dpsandbox.core.Datapack
import moe.afox.dpsandbox.core.DatapackSandbox
import moe.afox.dpsandbox.core.VersionProfiles
import moe.afox.dpsandbox.core.createSandbox
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DpsCompleterTest {
    private fun completer(): DpsCompleter =
        DpsCompleter { createSandbox("26.1.2", listOf(Path.of("../core/src/test/resources/packs/counter"))) }

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
        assertSuggests(completer, "schedule ", "clear")
        assertSuggests(completer, "advancement grant Steve ", "everything")
        assertSuggests(completer, "execute store result ", "score")
        assertSuggests(completer, "execute if ", "predicate")
        assertSuggests(completer, "execute if ", "blocks")
        assertSuggests(completer, "execute unless ", "loaded")
        assertSuggests(completer, "execute if dimension ", "minecraft:overworld")
        assertSuggests(completer, "execute if biome 0 64 0 ", "minecraft:plains")
        assertSuggests(completer, "data modify storage demo:dst path set ", "from")
        assertSuggests(completer, "data modify storage demo:dst path set from ", "storage")
        assertSuggests(completer, "give Steve ", "minecraft:apple")
        assertSuggests(completer, "effect give Steve ", "minecraft:speed")
        assertSuggests(completer, "item replace entity Steve ", "hotbar.0")
        assertSuggests(completer, "place ", "structure")
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
    }

    @Test
    fun `slash commands keep their slash in candidates`() {
        val completer = completer()

        assertSuggests(completer, "/fun", "/function")
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
        val implementedRoots = listOf(
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
        assertEquals(CommandBehaviorLevel.OBSERVED_NOOP, commands.getValue("place").behaviorLevel)
        assertEquals(CommandBehaviorLevel.OBSERVED_NOOP, commands.getValue("ban").behaviorLevel)
        assertEquals("edit stored world border state", commands.getValue("worldborder").description)
        implementedRoots.forEach { root ->
            assertFalse(
                commands.getValue(root).description.startsWith("vanilla command:"),
                "$root should be listed as an implemented sandbox command",
            )
        }
    }

    private fun assertSuggests(completer: DpsCompleter, line: String, expected: String) {
        val values = completer.suggestions(line).map { it.value }
        assertTrue(expected in values, "Expected '$expected' in suggestions for '$line', got $values")
    }
}
