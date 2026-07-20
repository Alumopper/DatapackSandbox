package moe.afox.dpsandbox.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExecutionCancellationTest {
    @Test
    fun `cooperative cancellation stops at command boundary and can be cleared`() {
        val sandbox =
            createFunctionSandboxFromString(
                version = "26.2",
                functionText = "say first\nsay second",
                functionId = "demo:main",
            )
        sandbox.requestExecutionCancellation()

        val interrupted = assertFailsWith<SandboxException> { sandbox.runFunction("demo:main") }

        assertEquals(DiagnosticCode.EXECUTION_INTERRUPTED, interrupted.code)
        assertTrue("interrupted" in interrupted.message)
        assertEquals(0, sandbox.world.outputs.count { it.command == "say" })
        sandbox.clearExecutionCancellation()
        assertEquals(2, sandbox.runFunction("demo:main").commandsExecuted)
        assertEquals(2, sandbox.world.outputs.count { it.command == "say" })
    }
}
