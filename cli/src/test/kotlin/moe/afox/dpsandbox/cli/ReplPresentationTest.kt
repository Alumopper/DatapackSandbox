package moe.afox.dpsandbox.cli

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class ReplPresentationTest {
    @Test
    fun `dashboard presents session state as a compact card`() {
        val dashboard = ReplPresentation.dashboard("26.2", 2, true, false, 40, 1, 3)

        assertContains(dashboard, "Datapack Sandbox")
        assertContains(dashboard, "profile")
        assertContains(dashboard, "26.2")
        assertContains(dashboard, "players / entities")
        assertContains(dashboard, "1 / 3")
        assertTrue(dashboard.lines().first().startsWith("+-"), dashboard)
        assertTrue(dashboard.lines().last().startsWith("+"), dashboard)
    }

    @Test
    fun `help is grouped into scannable sections`() {
        val help = ReplPresentation.help()

        assertContains(help, "RUN")
        assertContains(help, "SANDBOX")
        assertContains(help, "INSPECT")
        assertContains(help, "WORKFLOW")
        assertContains(help, "function <id>")
        assertContains(help, "trace on|off|status")
    }

    @Test
    fun `prompt exposes active workflow modes`() {
        val prompt = ReplPresentation.prompt("26.2", watch = true, trace = true)

        assertContains(prompt, "dps@26.2")
        assertContains(prompt, "[watch,trace]")
        assertTrue(prompt.endsWith("> "), prompt)
    }

    @Test
    fun `success output keeps stable OK label`() {
        val result = ReplPresentation.success("tick 2", "commands=4, gameTime=2")

        assertContains(result, "OK tick 2")
        assertContains(result, "commands=4")
    }
}
