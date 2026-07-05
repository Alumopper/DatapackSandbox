package moe.afox.dpsandbox.cli

import moe.afox.dpsandbox.core.CommandTraceEvent

object TraceFilters {
    fun apply(events: List<CommandTraceEvent>, filters: List<String>): List<CommandTraceEvent> =
        if (filters.isEmpty()) {
            events
        } else {
            events.filter { event -> filters.all { filter -> matches(event, filter) } }
        }

    private fun matches(event: CommandTraceEvent, raw: String): Boolean {
        val splitAt = raw.indexOf('=')
        if (splitAt < 0) return contains(event, raw)
        val key = raw.substring(0, splitAt).trim().lowercase()
        val value = raw.substring(splitAt + 1).trim()
        return when (key) {
            "root" -> event.root == value
            "command" -> event.command == value
            "contains" -> value in event.command || value in (event.errorMessage ?: "")
            "function" -> event.source?.functionStack?.any { value in it.id.toString() } == true
            "file", "source" -> value in (event.source?.file ?: "")
            "success" -> value.toBooleanStrictOrNull()?.let { event.success == it } ?: false
            "output", "outputs" -> value.toIntOrNull()?.let { event.outputs == it }
                ?: value.toBooleanStrictOrNull()?.let { if (it) event.outputs > 0 else event.outputs == 0 }
                ?: false
            else -> contains(event, raw)
        }
    }

    private fun contains(event: CommandTraceEvent, value: String): Boolean =
        value in event.command ||
            value == event.root ||
            value in (event.source?.file ?: "") ||
            event.source?.functionStack?.any { value in it.id.toString() } == true ||
            value in (event.errorMessage ?: "")
}
