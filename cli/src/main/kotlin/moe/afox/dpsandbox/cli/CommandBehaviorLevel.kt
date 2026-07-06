package moe.afox.dpsandbox.cli

enum class CommandBehaviorLevel(
    val id: String,
    val summary: String,
) {
    EXACT("exact", "matches vanilla-observable behavior for the documented surface"),
    MODELED("modeled", "uses the sandbox's deterministic model for datapack-visible behavior"),
    OBSERVED_NOOP("observed-noop", "accepts the command and records observable output or diagnostics without real side effects"),
    UNSUPPORTED("unsupported", "delegates to the active unsupported-command policy"),
}
