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

data class ResourceCatalogEntry(
    val type: String,
    val behaviorLevel: ResourceBehaviorLevel = ResourceBehaviorLevels.forType(type),
    val summary: String,
)

object ResourceCatalog {
    val additionalRawJsonTypes: List<String> = listOf(
        "banner_pattern",
        "cat_variant",
        "chat_type",
        "chicken_variant",
        "cow_variant",
        "damage_type",
        "dialog",
        "dimension",
        "dimension_type",
        "enchantment",
        "enchantment_provider",
        "equipment_asset",
        "frog_variant",
        "instrument",
        "jukebox_song",
        "painting_variant",
        "pig_variant",
        "test_environment",
        "test_instance",
        "trim_material",
        "trim_pattern",
        "wolf_sound_variant",
        "wolf_variant",
        "worldgen/biome",
        "worldgen/configured_carver",
        "worldgen/configured_feature",
        "worldgen/density_function",
        "worldgen/flat_level_generator_preset",
        "worldgen/multi_noise_biome_source_parameter_list",
        "worldgen/noise",
        "worldgen/noise_settings",
        "worldgen/placed_feature",
        "worldgen/processor_list",
        "worldgen/structure",
        "worldgen/structure_set",
        "worldgen/template_pool",
        "worldgen/world_preset",
    )

    private val modeledRawJsonSummaries = mapOf(
        "banner_pattern" to "item component registry JSON metadata exposed by item command outputs",
        "cat_variant" to "entity variant JSON metadata exposed by the summon command",
        "chat_type" to "chat type JSON metadata exposed by modeled chat commands",
        "chicken_variant" to "entity variant JSON metadata exposed by the summon command",
        "cow_variant" to "entity variant JSON metadata exposed by the summon command",
        "damage_type" to "damage type JSON metadata exposed by the damage command",
        "dimension" to "dimension JSON metadata exposed by dimension-aware command outputs",
        "dimension_type" to "dimension type JSON metadata exposed through dimension resources",
        "enchantment" to "enchantment JSON metadata exposed by the enchant command",
        "equipment_asset" to "equipment asset JSON metadata exposed by item command outputs",
        "frog_variant" to "entity variant JSON metadata exposed by the summon command",
        "instrument" to "instrument JSON metadata exposed by item command outputs",
        "jukebox_song" to "jukebox song JSON metadata exposed by item command outputs",
        "painting_variant" to "entity variant JSON metadata exposed by the summon command",
        "pig_variant" to "entity variant JSON metadata exposed by the summon command",
        "trim_material" to "armor trim material JSON metadata exposed by item command outputs",
        "trim_pattern" to "armor trim pattern JSON metadata exposed by item command outputs",
        "wolf_sound_variant" to "wolf sound variant JSON metadata exposed by the summon command",
        "wolf_variant" to "entity variant JSON metadata exposed by the summon command",
        "worldgen/configured_feature" to "simple_block, block_column, disk, vegetation_patch, tree, basalt_columns, delta_feature, lake, replace_single_block, replace_blob, selector, random_patch, flower, and ore feature JSON consumed by place feature",
        "worldgen/placed_feature" to "placed feature JSON resolving configured simple_block/block_column/disk/vegetation_patch/tree/basalt_columns/delta_feature/lake/replace_single_block/replace_blob/selector/random_patch/flower/ore resources for place feature",
        "worldgen/processor_list" to "block_ignore, protected_blocks, jigsaw_replacement, and simple rule processors consumed by sandbox structure placement",
        "worldgen/structure" to "sandbox structure JSON blocks/entities consumed by place structure/template",
        "worldgen/template_pool" to "single/legacy pool elements consumed by sandbox place jigsaw",
    )

    val all: List<ResourceCatalogEntry> = listOf(
        ResourceCatalogEntry("function", summary = "mcfunction execution, trace source locations, and missing-reference checks"),
        ResourceCatalogEntry("tag/function", summary = "load/tick/function tag execution and replace semantics"),
        ResourceCatalogEntry("loot_table", summary = "deterministic loot generation for supported contexts and commands"),
        ResourceCatalogEntry("predicate", summary = "predicate command/API checks, advancement conditions, loot conditions, and item modifiers"),
        ResourceCatalogEntry("advancement", summary = "player progress, criteria matching, rewards, output, and event trace"),
        ResourceCatalogEntry("recipe", summary = "resource index entries plus player recipe state for commands and rewards"),
        ResourceCatalogEntry("item_modifier", summary = "common item modifier functions applied by item modify"),
        ResourceCatalogEntry("tag/<registry>", ResourceBehaviorLevel.OBSERVED_NOOP, "general tags with replace semantics and resource-index visibility"),
    ) + additionalRawJsonTypes.map { type ->
        ResourceCatalogEntry(
            type,
            if (type in modeledRawJsonSummaries) ResourceBehaviorLevel.MODELED else ResourceBehaviorLevel.OBSERVED_NOOP,
            modeledRawJsonSummaries[type] ?: "version-checked raw JSON resource indexed for inspection",
        )
    }
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
        "banner_pattern",
        "cat_variant",
        "chat_type",
        "chicken_variant",
        "cow_variant",
        "damage_type",
        "dimension",
        "dimension_type",
        "enchantment",
        "equipment_asset",
        "frog_variant",
        "instrument",
        "jukebox_song",
        "painting_variant",
        "pig_variant",
        "trim_material",
        "trim_pattern",
        "wolf_sound_variant",
        "wolf_variant",
        "worldgen/configured_feature",
        "worldgen/placed_feature",
        "worldgen/processor_list",
        "worldgen/structure",
        "worldgen/template_pool",
    )
}
