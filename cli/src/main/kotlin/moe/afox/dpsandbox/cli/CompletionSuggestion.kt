package moe.afox.dpsandbox.cli

data class CompletionSuggestion(
    val value: String,
    val description: String = "",
    val group: String = "values",
    val appendSpace: Boolean = false,
    val behaviorLevel: CommandBehaviorLevel? = null,
)

internal data class RangedCompletionSuggestion(
    val suggestion: CompletionSuggestion,
    val start: Int,
    val end: Int,
) {
    val value: String
        get() = suggestion.value
    val description: String
        get() = suggestion.description
    val appendSpace: Boolean
        get() = suggestion.appendSpace
}
