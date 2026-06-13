package net.datapacksandbox.cli

import net.datapacksandbox.core.createSandbox
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DpsCompleterTest {
    private fun completer(): DpsCompleter =
        DpsCompleter { createSandbox("26.1.2", listOf(Path.of("../core/src/test/resources/packs/counter"))) }

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

        assertSuggests(completer, "bo", "bossbar")
        assertSuggests(completer, "scoreboard players ", "list")
        assertSuggests(completer, "scoreboard players ", "reset")
        assertSuggests(completer, "schedule ", "clear")
        assertSuggests(completer, "advancement grant Steve ", "everything")
        assertSuggests(completer, "execute store result ", "score")
        assertSuggests(completer, "give Steve ", "minecraft:apple")
        assertSuggests(completer, "effect give Steve ", "minecraft:speed")
        assertSuggests(completer, "item replace entity Steve ", "hotbar.0")
    }

    @Test
    fun `slash commands keep their slash in candidates`() {
        val completer = completer()

        assertSuggests(completer, "/fun", "/function")
    }

    private fun assertSuggests(completer: DpsCompleter, line: String, expected: String) {
        val values = completer.suggestions(line).map { it.value }
        assertTrue(expected in values, "Expected '$expected' in suggestions for '$line', got $values")
    }
}
