package net.datapacksandbox.cli

import org.jline.utils.AttributedStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DpsHighlighterTest {
    @Test
    fun `styles commands selectors resource locations and numbers`() {
        val highlighted = DpsHighlighter().highlightLine("execute as @a run function demo:reward")

        assertEquals("execute as @a run function demo:reward", highlighted.toString())
        assertNotEquals(AttributedStyle.DEFAULT, highlighted.styleAt(0))
        assertNotEquals(AttributedStyle.DEFAULT, highlighted.styleAt(11))
        assertNotEquals(AttributedStyle.DEFAULT, highlighted.styleAt(27))
    }
}
