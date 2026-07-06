package moe.afox.dpsandbox.cli

import moe.afox.dpsandbox.core.createSandbox
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
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

    @Test
    fun `prints trace events when trace is enabled`() {
        val repl = Repl(createSandbox("26.1.2", listOf(Path.of("../core/src/test/resources/packs/counter"))))

        val output = captureStdout {
            repl.handle("trace on")
            repl.handle("say traced")
        }

        assertTrue(output.contains("OK trace on"), output)
        assertTrue(output.contains("trace OK say traced"), output)
    }

    @Test
    fun `prints last diff and reruns last command`() {
        val repl = Repl(createSandbox("26.1.2", listOf(Path.of("../core/src/test/resources/packs/counter"))))

        val output = captureStdout {
            repl.handle("scoreboard objectives add runs dummy")
            repl.handle("scoreboard players add #repl runs 2")
            repl.handle("diff last")
            repl.handle("rerun last")
            repl.handle("inspect score #repl runs")
        }

        assertTrue(output.contains("+ /scores/runs"), output)
        assertTrue(output.contains("rerun: scoreboard players add #repl runs 2"), output)
        assertTrue(output.lines().any { it.trim() == "4" }, output)
    }

    @Test
    fun `loads fixture files and resets world`() {
        val repl = Repl(createSandbox("26.1.2", listOf(Path.of("../core/src/test/resources/packs/counter"))))
        val fixture = Files.createTempFile("dps-repl-fixture", ".json")
        Files.writeString(
            fixture,
            """
            {
              "scores": [
                { "target": "#fixture", "objective": "ready", "value": 3 }
              ]
            }
            """.trimIndent(),
        )

        val output = captureStdout {
            repl.handle("load fixture $fixture")
            repl.handle("inspect score #fixture ready")
            repl.handle("reset world")
            repl.handle("inspect score #fixture ready")
        }

        assertTrue(output.contains("OK load fixture $fixture"), output)
        assertTrue(output.lines().any { it.trim() == "3" }, output)
        assertTrue(output.contains("OK reset world"), output)
        assertTrue(output.lines().last { it.trim().toIntOrNull() != null }.trim() == "0", output)
    }

    @Test
    fun `inspects raw datapack resources`() {
        val pack = Files.createTempDirectory("dps-repl-raw-pack")
        Files.writeString(
            pack.resolve("pack.mcmeta"),
            """
            {
              "pack": {
                "pack_format": 107.1,
                "description": "raw resource test pack"
              }
            }
            """.trimIndent(),
        )
        val damageTypeRoot = pack.resolve("data").resolve("demo").resolve("damage_type")
        Files.createDirectories(damageTypeRoot)
        Files.writeString(
            damageTypeRoot.resolve("debug_damage.json"),
            """
            {
              "message_id": "debug",
              "scaling": "never",
              "exhaustion": 0.0,
              "marker": "raw"
            }
            """.trimIndent(),
        )

        val repl = Repl(createSandbox("26.2", listOf(pack)))
        val output = captureStdout {
            repl.handle("inspect raw")
            repl.handle("inspect raw damage_type")
            repl.handle("inspect raw damage_type demo:debug_damage")
            repl.handle("inspect resources damage_type")
        }

        assertTrue(output.contains("damage_type 1"), output)
        assertTrue(output.contains("demo:debug_damage file="), output)
        assertTrue(output.contains("\"marker\": \"raw\""), output)
        assertTrue(output.contains("damage_type demo:debug_damage observed-noop active"), output)
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
