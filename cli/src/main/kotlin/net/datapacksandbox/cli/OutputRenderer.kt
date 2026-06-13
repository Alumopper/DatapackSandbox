package net.datapacksandbox.cli

import net.datapacksandbox.core.OutputEvent

object OutputRenderer {
    fun render(event: OutputEvent): String {
        val label = when (event.channel) {
            "chat" -> ConsoleStyle.green("chat")
            "title" -> ConsoleStyle.magenta("title")
            "sound" -> ConsoleStyle.cyan("sound")
            "visual" -> ConsoleStyle.yellow("visual")
            "data" -> ConsoleStyle.blue("data")
            "warning" -> ConsoleStyle.yellow("warn")
            else -> ConsoleStyle.blue(event.channel)
        }
        val targets = if (event.targets.isEmpty()) "" else " ${ConsoleStyle.dim("->")} ${event.targets.joinToString()}"
        val text = if (event.segments.isNotEmpty()) ConsoleStyle.minecraft(event.segments) else event.text
        return "${ConsoleStyle.dim("[${event.tick}]")} $label ${ConsoleStyle.bold(event.command)}$targets $text"
    }

    fun print(events: List<OutputEvent>) {
        events.forEach { println(render(it)) }
    }
}
