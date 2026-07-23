package moe.afox.dpsandbox.cli

import moe.afox.dpsandbox.core.OutputEvent
import moe.afox.dpsandbox.core.createFunctionSandboxFromString
import moe.afox.dpsandbox.render.RenderAssets
import moe.afox.dpsandbox.render.SandboxRealtimeRenderer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmViewportControllerTest {
    @Test
    fun `viewport completes checks executes and reports commands`() {
        val assets = RenderAssets()
        val output = CopyOnWriteArrayList<String>()
        val ready = CountDownLatch(1)
        val changedScene = CountDownLatch(1)
        val visual = CopyOnWriteArrayList<OutputEvent>()
        val particle = CountDownLatch(1)
        val controller =
            JvmViewportController(
                sandboxFactory = { createFunctionSandboxFromString("26.2", "") },
                initializeSandbox = {},
                realtimeRenderer = SandboxRealtimeRenderer(assets),
                assets = assets,
                options = JvmViewportOptions(width = 320, height = 180),
                sceneSink = { scene, _ -> if (scene.visibleBlocks == 1) changedScene.countDown() },
                outputSink = { text, _ ->
                    output += text
                    if (text.startsWith("JVM sandbox ready")) ready.countDown()
                },
                playStateSink = {},
                visualSink = { event ->
                    visual += event
                    if (event.command == "particle") particle.countDown()
                },
                environmentSink = {},
            )
        try {
            controller.start()
            assertTrue(ready.await(10, TimeUnit.SECONDS), output.joinToString("\n"))

            val completion = CountDownLatch(1)
            controller.inspectCommand("setb", 1) { inspection ->
                assertTrue(inspection.suggestions.any { it.value == "setblock" })
                completion.countDown()
            }
            assertTrue(completion.await(10, TimeUnit.SECONDS))

            val validation = CountDownLatch(1)
            controller.inspectCommand("setblock 1 2", 2) { inspection ->
                assertFalse(inspection.check?.valid ?: true)
                validation.countDown()
            }
            assertTrue(validation.await(10, TimeUnit.SECONDS))

            controller.execute("setblock 2 0 0 minecraft:stone")
            assertTrue(changedScene.await(10, TimeUnit.SECONDS), output.joinToString("\n"))
            assertTrue(output.any { it == "> setblock 2 0 0 minecraft:stone" })
            assertTrue(output.any { it.startsWith("OK:") })

            controller.execute("particle minecraft:flame 2 1 0 0.2 0.2 0.2 0.05 8 force Steve")
            assertTrue(particle.await(10, TimeUnit.SECONDS), output.joinToString("\n"))
            assertEquals("visual", visual.single { it.command == "particle" }.channel)
        } finally {
            controller.close()
        }
    }

    @Test
    fun `playing a static world does not rebuild the GPU scene every tick`() {
        val assets = RenderAssets()
        val output = CopyOnWriteArrayList<String>()
        val initialScene = CountDownLatch(1)
        val environmentUpdates = AtomicInteger()
        val sceneUpdates = AtomicInteger()
        val controller =
            JvmViewportController(
                sandboxFactory = { createFunctionSandboxFromString("26.2", "") },
                initializeSandbox = {},
                realtimeRenderer = SandboxRealtimeRenderer(assets),
                assets = assets,
                options = JvmViewportOptions(width = 320, height = 180, autoplay = true),
                sceneSink = { _, _ ->
                    sceneUpdates.incrementAndGet()
                    initialScene.countDown()
                },
                outputSink = { text, _ -> output += text },
                playStateSink = {},
                visualSink = {},
                environmentSink = { environmentUpdates.incrementAndGet() },
            )
        try {
            controller.start()
            assertTrue(initialScene.await(10, TimeUnit.SECONDS), output.joinToString("\n"))
            Thread.sleep(650)
            assertEquals(1, sceneUpdates.get(), "static ticks should keep the compiled scene")
            assertTrue(environmentUpdates.get() >= 5, "world time should still reach the sky renderer")
            assertFalse(output.any { it.startsWith("Dropped ") }, output.joinToString("\n"))
        } finally {
            controller.close()
        }
    }
}
