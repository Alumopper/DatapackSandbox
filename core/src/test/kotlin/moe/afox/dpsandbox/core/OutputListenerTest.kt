package moe.afox.dpsandbox.core

import java.util.function.Consumer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OutputListenerTest {
    @Test
    fun `world output listeners observe future events and can be removed`() {
        val world = SandboxWorld()
        val observed = mutableListOf<OutputEvent>()
        val listener = Consumer<OutputEvent> { observed += it }

        assertTrue(world.addOutputListener(listener))
        assertFalse(world.addOutputListener(listener))

        world.recordOutput(command = "say", channel = "chat", text = "<Server> first", rawText = "first")

        assertEquals(1, observed.size)
        assertSame(world.outputs.single(), observed.single())
        assertTrue(world.removeOutputListener(listener))
        assertFalse(world.removeOutputListener(listener))

        world.recordOutput(command = "say", channel = "chat", text = "<Server> second", rawText = "second")

        assertEquals(1, observed.size)
        assertEquals(2, world.outputs.size)
    }

    @Test
    fun `listener changes during dispatch apply only to future events`() {
        val world = SandboxWorld()
        val observed = mutableListOf<String>()
        lateinit var selfRemoving: Consumer<OutputEvent>
        val addedDuringDispatch = Consumer<OutputEvent> { observed += "added:${it.rawText}" }
        selfRemoving =
            Consumer { event ->
                observed += "initial:${event.rawText}"
                world.removeOutputListener(selfRemoving)
                world.addOutputListener(addedDuringDispatch)
            }
        world.addOutputListener(selfRemoving)

        world.recordOutput(command = "say", channel = "chat", text = "first", rawText = "first")
        world.recordOutput(command = "say", channel = "chat", text = "second", rawText = "second")

        assertEquals(listOf("initial:first", "added:second"), observed)
    }
}
