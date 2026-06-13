package moe.afox.dpsandbox.cli

import moe.afox.dpsandbox.core.createSandbox
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class ReplTest {
    @Test
    fun `prints clear feedback for manually entered commands`() {
        val repl = Repl(createSandbox("26.1.2", listOf(Path.of("../core/src/test/resources/packs/counter"))))

        val output = captureStdout {
            repl.handle("say hello")
        }

        assertTrue(output.contains("OK say hello (commands=1, gameTime=0, outputs=+1)"), output)
        assertTrue(output.contains("[0] chat say -> Steve <Server> hello"), output)
    }

    @Test
    fun `prints keyboard input event feedback`() {
        val repl = Repl(createSandbox("26.1.2", listOf(Path.of("../core/src/test/resources/packs/counter"))))

        val output = captureStdout {
            repl.handle("event player Steve key_input key.jump release")
        }

        assertTrue(output.contains("OK event player Steve key_input"), output)
        assertTrue(output.contains("input=keyboard:key.jump/release"), output)
    }

    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val bytes = ByteArrayOutputStream()
        System.setOut(PrintStream(bytes, true, Charsets.UTF_8))
        return try {
            block()
            bytes.toString(Charsets.UTF_8)
        } finally {
            System.setOut(original)
        }
    }
}
