package moe.afox.dpsandbox.cli

import moe.afox.dpsandbox.core.CommandTraceEvent

object TraceRenderer {
    fun render(event: CommandTraceEvent): String {
        val status = if (event.success) ConsoleStyle.green("OK") else ConsoleStyle.red("ERR")
        val source = event.source?.let { src ->
            listOfNotNull(
                src.file,
                src.line?.let { ":$it" },
            ).joinToString(separator = "").ifBlank { null }
        }?.let { " ${ConsoleStyle.dim("@ $it")}" }.orEmpty()
        val stack = event.source?.functionStack
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = " stack=", separator = " > ") { it.id.toString() }
            .orEmpty()
        val changes = event.snapshotDiffs
            .takeIf { it.isNotEmpty() }
            ?.let { " changes=+${it.size}" }
            .orEmpty()
        val error = event.errorCode?.let { " ${ConsoleStyle.red("${it.name}: ${event.errorMessage}")}" }.orEmpty()
        return "${ConsoleStyle.dim("[${event.tick}]")} trace $status ${ConsoleStyle.bold(event.command)} commands=${event.commandsExecuted} outputs=${event.outputs}$changes$source$stack$error"
    }

    fun print(events: List<CommandTraceEvent>) {
        events.forEach { println(render(it)) }
    }
}
