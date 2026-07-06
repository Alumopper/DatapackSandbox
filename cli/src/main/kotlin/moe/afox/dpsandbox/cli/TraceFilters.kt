package moe.afox.dpsandbox.cli

import moe.afox.dpsandbox.core.CommandTraceEvent
import moe.afox.dpsandbox.core.JsonPaths
import moe.afox.dpsandbox.core.JsonValues
import moe.afox.dpsandbox.core.OutputEvent

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
            "selector", "target" -> selectorMatches(event, value)
            "success" -> value.toBooleanStrictOrNull()?.let { event.success == it } ?: false
            "outputs" -> outputCountMatches(event, value)
            "output" -> outputCountMatches(event, value) || event.outputEvents.any { outputMatches(it, value) }
            "output-channel" -> event.outputEvents.any { it.channel == value }
            "output-payload" -> event.outputEvents.any { outputPayloadMatches(it, value) }
            "diff", "path", "state" -> event.snapshotDiffs.any { value in it.path || value in it.render() }
            "score", "scores" -> event.snapshotDiffs.any { it.path.startsWith("/scores") && (value in it.path || value in it.render()) }
            "storage" -> event.snapshotDiffs.any { it.path.startsWith("/storage") && (value in it.path || value in it.render()) }
            else -> contains(event, raw)
        }
    }

    private fun contains(event: CommandTraceEvent, value: String): Boolean =
        value in event.command ||
            value == event.root ||
            value in (event.source?.file ?: "") ||
            event.source?.functionStack?.any { value in it.id.toString() } == true ||
            value in (event.errorMessage ?: "") ||
            event.outputEvents.any { outputMatches(it, value) } ||
            event.snapshotDiffs.any { value in it.path || value in it.render() }

    private fun outputCountMatches(event: CommandTraceEvent, value: String): Boolean =
        value.toIntOrNull()?.let { event.outputs == it }
            ?: value.toBooleanStrictOrNull()?.let { if (it) event.outputs > 0 else event.outputs == 0 }
            ?: false

    private fun selectorMatches(event: CommandTraceEvent, value: String): Boolean =
        value in event.command ||
            value == event.executor ||
            event.outputEvents.any { output ->
                output.targets.any { value in it } ||
                    output.payload?.let { value in JsonValues.render(it) } == true
            }

    private fun outputMatches(output: OutputEvent, value: String): Boolean =
        value in output.text ||
            value == output.channel ||
            output.targets.any { value in it } ||
            output.payload?.let { value in JsonValues.render(it) } == true

    private fun outputPayloadMatches(output: OutputEvent, value: String): Boolean {
        val payload = output.payload?.takeIf { it.isJsonObject }?.asJsonObject ?: return false
        val splitAt = value.indexOf('=')
        if (splitAt < 0) {
            return try {
                JsonPaths.exists(payload, value.trim())
            } catch (_: Exception) {
                false
            }
        }
        val path = value.substring(0, splitAt).trim()
        val expectedText = value.substring(splitAt + 1).trim()
        if (path.isEmpty() || expectedText.isEmpty()) return false
        val expected = try {
            JsonValues.parse(expectedText)
        } catch (_: Exception) {
            return false
        }
        val actual = try {
            JsonPaths.get(payload, path)
        } catch (_: Exception) {
            return false
        }
        return actual == expected
    }
}
