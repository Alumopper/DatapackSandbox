package moe.afox.dpsandbox.cli

import moe.afox.dpsandbox.core.ResourceLocation
import moe.afox.dpsandbox.core.VersionProfile

data class RegistryInspectionGroup(
    val name: String,
    val entries: Set<ResourceLocation>,
)

object RegistryInspection {
    val groupNames: List<String> = listOf(
        "items",
        "blocks",
        "entity_types",
        "biomes",
        "damage_types",
        "enchantments",
        "effects",
        "dimensions",
        "loot_context_types",
        "advancement_triggers",
        "loot_conditions",
        "loot_functions",
    )

    fun groups(profile: VersionProfile): List<RegistryInspectionGroup> {
        val view = profile.registryView
        return listOf(
            RegistryInspectionGroup("items", view.items),
            RegistryInspectionGroup("blocks", view.blocks),
            RegistryInspectionGroup("entity_types", view.entityTypes),
            RegistryInspectionGroup("biomes", view.biomes),
            RegistryInspectionGroup("damage_types", view.damageTypes),
            RegistryInspectionGroup("enchantments", view.enchantments),
            RegistryInspectionGroup("effects", view.effects),
            RegistryInspectionGroup("dimensions", view.dimensions),
            RegistryInspectionGroup("loot_context_types", view.lootContextTypes),
            RegistryInspectionGroup("advancement_triggers", view.advancementTriggers),
            RegistryInspectionGroup("loot_conditions", view.lootConditions),
            RegistryInspectionGroup("loot_functions", view.lootFunctions),
        )
    }

    fun select(profile: VersionProfile, groupFilter: String?): List<RegistryInspectionGroup> {
        val normalizedFilter = groupFilter?.replace('-', '_')
        val groups = groups(profile)
        return if (normalizedFilter == null) {
            groups
        } else {
            groups.filter { group -> normalizedFilter in aliases(group.name) }
        }
    }

    fun aliases(name: String): Set<String> {
        val singular = when (name) {
            "items" -> setOf("item")
            "blocks" -> setOf("block")
            "entity_types" -> setOf("entity_type", "entities", "entity")
            "biomes" -> setOf("biome")
            "damage_types" -> setOf("damage_type")
            "enchantments" -> setOf("enchantment")
            "effects" -> setOf("effect")
            "dimensions" -> setOf("dimension")
            "loot_context_types" -> setOf("loot_context_type", "loot_contexts")
            "advancement_triggers" -> setOf("advancement_trigger", "triggers")
            "loot_conditions" -> setOf("loot_condition", "conditions")
            "loot_functions" -> setOf("loot_function")
            else -> emptySet()
        }
        return (setOf(name, name.replace('_', '-')) + singular).map { it.replace('-', '_') }.toSet()
    }
}
