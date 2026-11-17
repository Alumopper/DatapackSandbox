package moe.afox.dpsandbox.core

import kotlin.test.Test
import kotlin.test.assertEquals

class CommandTraceOutputTest {
    @Test
    fun `function callers do not repeat their nested commands output payloads`() {
        val sandbox = createFunctionSandboxFromString(version = "26.3", functionText = "say nested", functionId = "demo:main")
        sandbox.executeCommand("function demo:main")
        val child = sandbox.world.traces.single { it.command == "say nested" }
        val caller = sandbox.world.traces.single { it.command == "function demo:main" }
        assertEquals(1, child.outputEvents.size)
        assertEquals(1, caller.outputs)
        assertEquals(0, caller.outputEvents.size)
        assertEquals(
            "<Server> nested",
            sandbox.world.outputs
                .single()
                .text,
        )
    }

    @Test
    fun `command diffs retain new output without copying previous output history`() {
        val sandbox = createFunctionSandboxFromString(version = "26.3", functionText = "", functionId = "demo:empty")
        sandbox.executeCommand("say first")
        sandbox.executeCommand("say second")
        val trace = sandbox.world.traces.last()
        val outputDiff = trace.snapshotDiffs.single { it.path.startsWith("/outputs/") }
        assertEquals("/outputs/0", outputDiff.path)
        assertEquals(null, outputDiff.before)
        assertEquals(
            "<Server> second",
            outputDiff.after!!
                .asJsonObject
                .get("text")
                .asString,
        )
        assertEquals(listOf("<Server> first", "<Server> second"), sandbox.world.outputs.map { it.text })
        assertEquals(2, sandbox.snapshotJson().getAsJsonArray("outputs").size())
    }
}
