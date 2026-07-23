package moe.afox.dpsandbox.cli

import moe.afox.dpsandbox.core.CommandCheckResult

internal data class ViewportCommandInspection(
    val revision: Long,
    val suggestions: List<CompletionSuggestion>,
    val hint: String,
    val check: CommandCheckResult?,
)

internal class JvmViewportCommandEditor {
    private val buffer = StringBuilder()

    var revision: Long = 0
        private set
    var selectedSuggestion: Int = 0
        private set
    var inspection: ViewportCommandInspection? = null
        private set

    val text: String
        get() = buffer.toString()

    fun append(character: Char) {
        buffer.append(character)
        changed()
    }

    fun append(value: String) {
        buffer.append(value)
        changed()
    }

    fun backspace() {
        if (buffer.isEmpty()) return
        buffer.deleteCharAt(buffer.lastIndex)
        changed()
    }

    fun clear() {
        buffer.clear()
        changed()
    }

    fun applyInspection(next: ViewportCommandInspection) {
        if (next.revision != revision) return
        inspection = next
        selectedSuggestion = selectedSuggestion.coerceIn(0, (next.suggestions.size - 1).coerceAtLeast(0))
    }

    fun moveSelection(delta: Int) {
        val size = inspection?.suggestions?.size ?: return
        if (size == 0) return
        selectedSuggestion = (selectedSuggestion + delta).mod(size)
    }

    fun completeSelected(): Boolean {
        val suggestion = inspection?.suggestions?.getOrNull(selectedSuggestion) ?: return false
        val completed = applyCompletion(text, suggestion)
        buffer.clear()
        buffer.append(completed)
        changed()
        return true
    }

    private fun changed() {
        revision += 1
        selectedSuggestion = 0
        inspection = null
    }
}

internal fun applyCompletion(
    buffer: String,
    suggestion: CompletionSuggestion,
): String {
    val context = CompletionContext.parse(buffer)
    val prefixLength = context.prefix.length.coerceAtMost(buffer.length)
    val base = buffer.dropLast(prefixLength)
    val suffix = if (suggestion.appendSpace) " " else ""
    return base + suggestion.value + suffix
}
