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
}
