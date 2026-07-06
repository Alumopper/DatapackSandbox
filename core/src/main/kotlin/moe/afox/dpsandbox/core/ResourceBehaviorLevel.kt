package moe.afox.dpsandbox.core

enum class ResourceBehaviorLevel(
    val id: String,
    val summary: String,
) {
    EXACT("exact", "matches vanilla-observable behavior for the documented resource surface"),
    MODELED("modeled", "loaded into deterministic sandbox runtime behavior"),
    OBSERVED_NOOP("observed-noop", "indexed, version-checked, and inspectable without full runtime semantics"),
    UNSUPPORTED("unsupported", "not loaded or intentionally rejected by the current sandbox"),
}

object ResourceBehaviorLevels {
    fun forType(type: String): ResourceBehaviorLevel =
        when (type) {
            in modeledTypes -> ResourceBehaviorLevel.MODELED
            else -> ResourceBehaviorLevel.OBSERVED_NOOP
        }

    private val modeledTypes = setOf(
        "function",
        "tag/function",
        "loot_table",
        "predicate",
        "advancement",
        "recipe",
        "item_modifier",
    )
}
