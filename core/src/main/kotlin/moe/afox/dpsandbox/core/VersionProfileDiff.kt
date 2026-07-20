package moe.afox.dpsandbox.core

data class VersionProfileDiff(
    val from: String,
    val to: String,
    val javaMajor: ValueChange<Int>,
    val dataVersion: ValueChange<Int?>,
    val dataPackFormat: ValueChange<String>,
    val nbtSchema: ValueChange<String>,
    val resourceDirectories: Map<String, SetChange>,
    val commandRoots: SetChange,
    val registries: Map<String, SetChange>,
)

data class ValueChange<T>(
    val from: T,
    val to: T,
) {
    val changed: Boolean get() = from != to
}

data class SetChange(
    val added: List<String>,
    val removed: List<String>,
    val unchanged: Int,
) {
    val changed: Boolean get() = added.isNotEmpty() || removed.isNotEmpty()
}

object VersionProfileDiffs {
    fun diff(
        from: VersionProfile,
        to: VersionProfile,
    ): VersionProfileDiff =
        VersionProfileDiff(
            from = from.id,
            to = to.id,
            javaMajor = ValueChange(from.javaMajor, to.javaMajor),
            dataVersion = ValueChange(from.dataVersion, to.dataVersion),
            dataPackFormat = ValueChange(from.dataPackFormat.toString(), to.dataPackFormat.toString()),
            nbtSchema = ValueChange(NbtSchemas.schemaSummary(from), NbtSchemas.schemaSummary(to)),
            resourceDirectories = resourceDirectoryDiffs(from.resourceDirectories, to.resourceDirectories),
            commandRoots = setDiff(from.commands.roots, to.commands.roots),
            registries = registryDiffs(from.registryView, to.registryView),
        )

    fun render(diff: VersionProfileDiff): String =
        buildString {
            appendLine("profile diff ${diff.from} -> ${diff.to}")
            appendLine("java: ${renderChange(diff.javaMajor)}")
            appendLine("data_version: ${renderChange(diff.dataVersion)}")
            appendLine("pack_format: ${renderChange(diff.dataPackFormat)}")
            appendLine("nbt_schema: ${renderChange(diff.nbtSchema)}")
            appendLine("resource_directories:")
            diff.resourceDirectories.forEach { (name, change) ->
                appendLine("  $name: ${renderSetChange(change)}")
            }
            appendLine("command_roots: ${renderSetChange(diff.commandRoots)}")
            appendLine("registries:")
            diff.registries.forEach { (name, change) ->
                appendLine("  $name: ${renderSetChange(change)}")
            }
        }.trimEnd()

    private fun <T> renderChange(change: ValueChange<T>): String =
        if (change.changed) "${change.from} -> ${change.to}" else "${change.to} (same)"

    private fun renderSetChange(change: SetChange): String {
        val parts = mutableListOf<String>()
        if (change.added.isNotEmpty()) parts += "added=${change.added.joinToString(",")}"
        if (change.removed.isNotEmpty()) parts += "removed=${change.removed.joinToString(",")}"
        parts += "unchanged=${change.unchanged}"
        return parts.joinToString(" ")
    }

    private fun resourceDirectoryDiffs(
        from: ResourceDirectoryProfile,
        to: ResourceDirectoryProfile,
    ): Map<String, SetChange> =
        linkedMapOf(
            "functions" to setDiff(from.functions, to.functions),
            "function_tags" to setDiff(from.functionTags, to.functionTags),
            "loot_tables" to setDiff(from.lootTables, to.lootTables),
            "predicates" to setDiff(from.predicates, to.predicates),
            "advancements" to setDiff(from.advancements, to.advancements),
            "recipes" to setDiff(from.recipes, to.recipes),
            "item_modifiers" to setDiff(from.itemModifiers, to.itemModifiers),
        )

    private fun registryDiffs(
        from: RegistryView,
        to: RegistryView,
    ): Map<String, SetChange> =
        linkedMapOf(
            "items" to setDiff(from.items.map { it.toString() }, to.items.map { it.toString() }),
            "blocks" to setDiff(from.blocks.map { it.toString() }, to.blocks.map { it.toString() }),
            "entity_types" to setDiff(from.entityTypes.map { it.toString() }, to.entityTypes.map { it.toString() }),
            "biomes" to setDiff(from.biomes.map { it.toString() }, to.biomes.map { it.toString() }),
            "damage_types" to setDiff(from.damageTypes.map { it.toString() }, to.damageTypes.map { it.toString() }),
            "enchantments" to setDiff(from.enchantments.map { it.toString() }, to.enchantments.map { it.toString() }),
            "effects" to setDiff(from.effects.map { it.toString() }, to.effects.map { it.toString() }),
            "dimensions" to setDiff(from.dimensions.map { it.toString() }, to.dimensions.map { it.toString() }),
            "loot_context_types" to setDiff(from.lootContextTypes.map { it.toString() }, to.lootContextTypes.map { it.toString() }),
            "advancement_triggers" to setDiff(from.advancementTriggers.map { it.toString() }, to.advancementTriggers.map { it.toString() }),
            "loot_conditions" to setDiff(from.lootConditions.map { it.toString() }, to.lootConditions.map { it.toString() }),
            "loot_functions" to setDiff(from.lootFunctions.map { it.toString() }, to.lootFunctions.map { it.toString() }),
        )

    private fun setDiff(
        from: Iterable<String>,
        to: Iterable<String>,
    ): SetChange {
        val fromSet = from.toSet()
        val toSet = to.toSet()
        return SetChange(
            added = (toSet - fromSet).sorted(),
            removed = (fromSet - toSet).sorted(),
            unchanged = (fromSet intersect toSet).size,
        )
    }
}
