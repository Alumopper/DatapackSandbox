package moe.afox.dpsandbox.core

import com.google.gson.JsonObject
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

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
        id in items ||
            id in blocks ||
            id in entityTypes ||
            id in biomes ||
            id in damageTypes ||
            id in enchantments ||
            id in effects ||
            id in dimensions

    companion object {
        private val commandCatalog: JsonObject? by lazy {
            val stream = RegistryView::class.java.classLoader.getResourceAsStream(COMMAND_CATALOG_RESOURCE) ?: return@lazy null
            runCatching {
                InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
                    JsonValues.parse(reader.readText()).asJsonObject
                }
            }.getOrNull()
        }

        private fun catalogIds(
            name: String,
            vararg fallback: String,
        ): Set<ResourceLocation> =
            commandCatalog
                ?.get(name)
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?.mapNotNull { it.takeIf { value -> value.isJsonPrimitive }?.asString }
                ?.takeIf { it.isNotEmpty() }
                ?.map { ResourceLocation.parse(it) }
                ?.toSortedSet()
                ?: fallback.map { ResourceLocation.parse(it) }.toSortedSet()

        val vanilla262 by lazy {
            RegistryView(
                items = catalogIds("items", "air", "stone", "diamond", "stick", "carrot_on_a_stick", "apple", "experience_bottle"),
                blocks = catalogIds("blocks", "air", "stone", "dirt", "grass_block", "diamond_ore", "chest"),
                entityTypes = catalogIds("entityTypes", "player", "marker", "zombie", "skeleton", "item", "experience_orb"),
                biomes = catalogIds("biomes", "plains", "forest", "desert"),
                damageTypes = catalogIds("damageTypes", "generic", "player_attack", "mob_attack", "fall", "out_of_world"),
                enchantments = catalogIds("enchantments", "sharpness", "fortune", "looting", "unbreaking"),
                effects = catalogIds("effects", "speed", "strength", "regeneration", "poison"),
                dimensions = catalogIds("dimensions", "overworld", "the_nether", "the_end"),
                lootContextTypes =
                    catalogIds(
                        "lootContextTypes",
                        "empty",
                        "block",
                        "entity",
                        "chest",
                        "fishing",
                        "advancement_reward",
                        "advancement_entity",
                    ),
                advancementTriggers =
                    catalogIds(
                        "advancementTriggers",
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
                lootConditions =
                    catalogIds(
                        "lootConditions",
                        "all_of",
                        "any_of",
                        "inverted",
                        "alternative",
                        "reference",
                        "random_chance",
                        "random_chance_with_enchanted_bonus",
                        "table_bonus",
                        "value_check",
                        "killed_by_player",
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
                lootFunctions =
                    catalogIds(
                        "lootFunctions",
                        "set_count",
                        "set_item",
                        "set_components",
                        "set_custom_data",
                        "set_name",
                        "set_lore",
                        "discard",
                        "copy_name",
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
        }

        val vanilla2612 = vanilla262.copy()
        val vanilla1204 = vanilla2612.copy()

        private const val COMMAND_CATALOG_RESOURCE = "vanilla-command-catalog-26.2.json"
    }
}

data class VanillaReference(
    val targetVersion: String = "26.2",
    val latestRelease: String = "26.2",
    val latestSnapshot: String = "26.3-snapshot-1",
    val serverJarUrl: String = "https://piston-data.mojang.com/v1/objects/823e2250d24b3ddac457a60c92a6a941943fcd6a/server.jar",
)
