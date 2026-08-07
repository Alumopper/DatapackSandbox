package moe.afox.dpsandbox.core

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals

class SandboxQuickTestConsoleOutputTest {
    @Test
    fun `quick tests can print chat output while commands execute`() {
        val bytes = ByteArrayOutputStream()
        val scenario =
            PrintStream(bytes, true, StandardCharsets.UTF_8).use { console ->
                SandboxQuickTest
                    .singleFunctionText(
                        functionText =
                            """
                            say hello from console
                            tellraw Steve {"text":"gold","color":"yellow"}
                            title Steve actionbar {"text":"not chat"}
                            """.trimIndent(),
                        version = "26.2",
                    ).printChatOutput(console)
                    .function()
            }

        assertEquals(
            listOf("<Server> hello from console", "gold"),
            bytes
                .toString(StandardCharsets.UTF_8)
                .lineSequence()
                .filter(String::isNotBlank)
                .toList(),
        )
        assertEquals(listOf("say", "tellraw", "title actionbar"), scenario.outputs().map(OutputEvent::command))
    }

    @Test
    fun `quick tests can stop printing chat output`() {
        val bytes = ByteArrayOutputStream()

        PrintStream(bytes, true, StandardCharsets.UTF_8).use { console ->
            SandboxQuickTest
                .singleFunctionText("say unused", version = "26.2")
                .printChatOutput(console)
                .command("say first")
                .stopPrintingChatOutput()
                .command("say second")
        }

        assertEquals(
            listOf("<Server> first"),
            bytes
                .toString(StandardCharsets.UTF_8)
                .lineSequence()
                .filter(String::isNotBlank)
                .toList(),
        )
    }
}
