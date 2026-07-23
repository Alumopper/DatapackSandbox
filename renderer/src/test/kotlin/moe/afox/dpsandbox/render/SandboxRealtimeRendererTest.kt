package moe.afox.dpsandbox.render

import moe.afox.dpsandbox.core.Position
import moe.afox.dpsandbox.core.createFunctionSandboxFromString
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SandboxRealtimeRendererTest {
    @Test
    fun `compiled JVM scene moves the camera without mutating the sandbox`() {
        val sandbox = createFunctionSandboxFromString("26.2", "", defaultPlayerName = null)
        sandbox.executeCommand("setblock 0 0 2 minecraft:stone")
        val before = sandbox.snapshotString()
        val renderer = SandboxRealtimeRenderer()

        val scene = renderer.compile(sandbox, RenderRequest(width = 320, height = 180))
        val gpuScene = renderer.compileGpu(sandbox, RenderRequest(width = 320, height = 180))
        val automatic = renderer.render(scene, width = 320, height = 180)
        val moved =
            renderer.render(
                scene,
                RealtimeRenderCamera(Position(3.0, 2.0, 6.0), yaw = 155.0, pitch = 8.0),
                width = 320,
                height = 180,
            )

        assertEquals(1, scene.visibleBlocks)
        assertTrue(scene.triangles >= 12)
        assertEquals(1, gpuScene.visibleBlocks)
        assertEquals(0, gpuScene.blocks.vertices.size % CompiledRealtimeGpuScene.FLOATS_PER_VERTEX)
        assertTrue(gpuScene.atlas.rgba.isNotEmpty())
        assertEquals(320, automatic.image.width)
        assertEquals(180, automatic.image.height)
        assertNotEquals(
            automatic.image.getRGB(0, 0, 320, 180, null, 0, 320).contentHashCode(),
            moved.image.getRGB(0, 0, 320, 180, null, 0, 320).contentHashCode(),
        )
        assertEquals(before, sandbox.snapshotString())
    }
}
