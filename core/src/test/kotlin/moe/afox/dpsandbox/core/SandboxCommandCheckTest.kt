package moe.afox.dpsandbox.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SandboxCommandCheckTest {
    @Test
    fun `command check validates against an isolated current world copy`() {
        val sandbox = createFunctionSandboxFromString("26.2", "")
        val before = sandbox.snapshotString()

        val valid = sandbox.checkCommand("setblock 1 2 3 minecraft:stone")
        val invalid = sandbox.checkCommand("setblock 1 2")

        assertTrue(valid.valid, valid.message)
        assertTrue(valid.stateChanges > 0)
        assertFalse(invalid.valid)
        assertEquals(DiagnosticCode.INPUT_FORMAT, invalid.errorCode)
        assertEquals(before, sandbox.snapshotString())
        assertTrue(sandbox.world.traces.isEmpty())
    }

    @Test
    fun `function command check validates resource without executing a large body`() {
        val functionText = (1..2_000).joinToString("\n") { index -> "setblock $index 2 3 minecraft:stone" }
        val sandbox = createFunctionSandboxFromString("26.2", functionText)
        val before = sandbox.snapshotString()

        val valid = sandbox.checkCommand("function sandbox:main")
        val missing = sandbox.checkCommand("function sandbox:missing")

        assertTrue(valid.valid, valid.message)
        assertEquals(1, valid.commandsExecuted)
        assertEquals(0, valid.stateChanges)
        assertFalse(missing.valid)
        assertEquals(DiagnosticCode.RESOURCE_NOT_FOUND, missing.errorCode)
        assertEquals(before, sandbox.snapshotString())
    }
}
