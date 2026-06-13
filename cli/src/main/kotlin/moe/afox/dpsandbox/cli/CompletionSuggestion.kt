package moe.afox.dpsandbox.cli

data class CompletionSuggestion(
    val value: String,
    val description: String = "",
    val group: String = "values",
    val appendSpace: Boolean = false,
)
