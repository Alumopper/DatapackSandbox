package moe.afox.dpsandbox.cli

import moe.afox.dpsandbox.core.Position
import moe.afox.dpsandbox.render.RealtimeRenderCamera
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmViewportModelsTest {
    @Test
    fun `camera uses the same flight axes as the Web viewport`() {
        val camera = JvmViewportCamera(initialSpeed = 6.0)
        camera.reset(RealtimeRenderCamera(Position(0.0, 4.0, 0.0), yaw = 180.0, pitch = 0.0))

        camera.move(forward = 1.0, right = 0.0, up = 1.0, deltaSeconds = 0.5)
        camera.look(deltaYaw = 15.0, deltaPitch = 200.0)

        assertEquals(4.0 + 3.0, camera.position.y, 0.0001)
        assertEquals(-3.0, camera.position.z, 0.0001)
        assertEquals(-165.0, camera.yaw, 0.0001)
        assertEquals(89.0, camera.pitch, 0.0001)
        assertFalse(camera.automatic)
    }

    @Test
    fun `A and D strafe in the expected camera-relative directions`() {
        val camera = JvmViewportCamera(initialSpeed = 6.0)
        camera.reset(RealtimeRenderCamera(Position(0.0, 4.0, 0.0), yaw = 180.0, pitch = 0.0))

        camera.move(forward = 0.0, right = 1.0, up = 0.0, deltaSeconds = 0.5)
        assertEquals(3.0, camera.position.x, 0.0001, "D should move right while looking north")

        camera.move(forward = 0.0, right = -2.0, up = 0.0, deltaSeconds = 0.5)
        assertEquals(-3.0, camera.position.x, 0.0001, "A should move left while looking north")
    }

    @Test
    fun `viewport command editor applies selected completion`() {
        val editor = JvmViewportCommandEditor()
        editor.append("setb")
        editor.applyInspection(
            ViewportCommandInspection(
                revision = editor.revision,
                suggestions = listOf(CompletionSuggestion("setblock", appendSpace = true)),
                hint = "",
                check = null,
            ),
        )

        assertTrue(editor.completeSelected())
        assertEquals("setblock ", editor.text)
    }

    @Test
    fun `viewport starts without an explicit datapack`() {
        val sandbox = createViewportSandbox("26.2", emptyList<Path>(), "Steve")

        assertEquals("26.2", sandbox.profile.id)
        assertTrue("Steve" in sandbox.world.players)
    }
}
