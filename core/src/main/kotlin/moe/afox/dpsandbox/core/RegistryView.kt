package moe.afox.dpsandbox.core

data class RegistryView(
    val items: Set<ResourceLocation>,
    val blocks: Set<ResourceLocation>,
    val entityTypes: Set<ResourceLocation>,
    val biomes: Set<ResourceLocation>,
    val damageTypes: Set<ResourceLocation>,
    val enchantments: Set<ResourceLocation>,
    val effects: Set<ResourceLocation>,
    val dimensions: Set<ResourceLocation>,
    val lootContextTypes: Set<ResourceLocation>,
    val advancementTriggers: Set<ResourceLocation>,
    val lootConditions: Set<ResourceLocation>,
    val lootFunctions: Set<ResourceLocation>,
) {
    fun containsAny(id: ResourceLocation): Boolean =
        id in items || id in blocks || id in entityTypes || id in biomes || id in damageTypes ||
            id in enchantments || id in effects || id in dimensions

    companion object {
        val vanilla262 = RegistryView(
            items = ids("air", "stone", "diamond", "stick", "carrot_on_a_stick", "apple", "experience_bottle"),
            blocks = ids("air", "stone", "dirt", "grass_block", "diamond_ore", "chest"),
            entityTypes = ids("player", "marker", "zombie", "skeleton", "item", "minecraft:experience_orb"),
            biomes = ids("plains", "forest", "desert"),
            damageTypes = ids("generic", "player_attack", "mob_attack", "fall", "out_of_world"),
            enchantments = ids("sharpness", "fortune", "looting", "unbreaking"),
            effects = ids("speed", "strength", "regeneration", "poison"),
            dimensions = ids("overworld", "the_nether", "the_end"),
            lootContextTypes = ids("empty", "block", "entity", "chest", "fishing", "advancement_reward", "advancement_entity"),
            advancementTriggers = ids(
                "impossible",
                "tick",
                "inventory_changed",
                "item_used_on_block",
                "using_item",
                "consume_item",
                "player_interacted_with_entity",
                "damage",
                "death",
                "entity_hurt_player",
                "player_hurt_entity",
                "player_killed_entity",
                "entity_killed_player",
                "location",
                "changed_dimension",
                "placed_block",
                "bee_nest_destroyed",
                "recipe_unlocked",
                "effects_changed",
            ),
            lootConditions = ids(
                "all_of",
                "any_of",
                "inverted",
                "alternative",
                "reference",
                "random_chance",
                "random_chance_with_enchanted_bonus",
                "entity_properties",
                "entity_scores",
                "location_check",
                "match_tool",
                "block_state_property",
                "damage_source_properties",
                "time_check",
                "weather_check",
                "survives_explosion",
            ),
            lootFunctions = ids(
                "set_count",
                "set_item",
                "set_components",
                "set_custom_data",
                "set_name",
                "set_lore",
                "discard",
                "copy_components",
                "copy_nbt",
                "set_damage",
                "enchant_randomly",
                "enchant_with_levels",
                "apply_bonus",
                "explosion_decay",
                "limit_count",
                "filtered",
                "reference",
                "sequence",
            ),
        )

        val vanilla2612 = vanilla262.copy()
        val vanilla1204 = vanilla2612.copy()

        private fun ids(vararg values: String): Set<ResourceLocation> =
            values.map { ResourceLocation.parse(it) }.toSortedSet()
    }
}

data class VanillaReference(
    val targetVersion: String = "26.2",
    val latestRelease: String = "26.2",
    val latestSnapshot: String = "26.3-snapshot-1",
    val serverJarUrl: String = "https://piston-data.mojang.com/v1/objects/823e2250d24b3ddac457a60c92a6a941943fcd6a/server.jar",
)
