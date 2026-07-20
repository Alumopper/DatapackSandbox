package moe.afox.dpsandbox.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

object VersionProfileJson {
    fun profiles(profiles: List<VersionProfile> = VersionProfiles.all): JsonObject =
        JsonObject().also { root ->
            root.addProperty("default", VersionProfiles.default.id)
            root.add(
                "profiles",
                JsonArray().also { array ->
                    profiles.forEach { array.add(profile(it)) }
                },
            )
        }

    fun profile(profile: VersionProfile): JsonObject =
        JsonObject().also { json ->
            json.addProperty("id", profile.id)
            json.addProperty("javaMajor", profile.javaMajor)
            json.add("dataVersion", value(profile.dataVersion))
            json.addProperty("dataPackFormat", profile.dataPackFormat.toString())
            json.addProperty("description", profile.description)
            json.addProperty("nbtSchema", NbtSchemas.schemaSummary(profile))
            json.add("resourceDirectories", resourceDirectories(profile.resourceDirectories))
            json.add("commandRoots", stringArray(profile.commands.roots.sorted()))
            json.add("registryCounts", registryCounts(profile.registryView))
            json.add("registries", registries(profile.registryView))
        }

    fun diff(diff: VersionProfileDiff): JsonObject =
        JsonObject().also { json ->
            json.addProperty("from", diff.from)
            json.addProperty("to", diff.to)
            json.add("javaMajor", valueChange(diff.javaMajor))
            json.add("dataVersion", valueChange(diff.dataVersion))
            json.add("dataPackFormat", valueChange(diff.dataPackFormat))
            json.add("nbtSchema", valueChange(diff.nbtSchema))
            json.add("resourceDirectories", setChangeMap(diff.resourceDirectories))
            json.add("commandRoots", setChange(diff.commandRoots))
            json.add("registries", setChangeMap(diff.registries))
        }

    private fun resourceDirectories(profile: ResourceDirectoryProfile): JsonObject =
        JsonObject().also { json ->
            json.add("functions", stringArray(profile.functions))
            json.add("functionTags", stringArray(profile.functionTags))
            json.add("lootTables", stringArray(profile.lootTables))
            json.add("predicates", stringArray(profile.predicates))
            json.add("advancements", stringArray(profile.advancements))
            json.add("recipes", stringArray(profile.recipes))
            json.add("itemModifiers", stringArray(profile.itemModifiers))
        }

    private fun registryCounts(view: RegistryView): JsonObject =
        JsonObject().also { json ->
            json.addProperty("items", view.items.size)
            json.addProperty("blocks", view.blocks.size)
            json.addProperty("entityTypes", view.entityTypes.size)
            json.addProperty("biomes", view.biomes.size)
            json.addProperty("damageTypes", view.damageTypes.size)
            json.addProperty("enchantments", view.enchantments.size)
            json.addProperty("effects", view.effects.size)
            json.addProperty("dimensions", view.dimensions.size)
            json.addProperty("lootContextTypes", view.lootContextTypes.size)
            json.addProperty("advancementTriggers", view.advancementTriggers.size)
            json.addProperty("lootConditions", view.lootConditions.size)
            json.addProperty("lootFunctions", view.lootFunctions.size)
        }

    private fun registries(view: RegistryView): JsonObject =
        JsonObject().also { json ->
            json.add("items", stringArray(view.items.map { it.toString() }.sorted()))
            json.add("blocks", stringArray(view.blocks.map { it.toString() }.sorted()))
            json.add("entity_types", stringArray(view.entityTypes.map { it.toString() }.sorted()))
            json.add("biomes", stringArray(view.biomes.map { it.toString() }.sorted()))
            json.add("damage_types", stringArray(view.damageTypes.map { it.toString() }.sorted()))
            json.add("enchantments", stringArray(view.enchantments.map { it.toString() }.sorted()))
            json.add("effects", stringArray(view.effects.map { it.toString() }.sorted()))
            json.add("dimensions", stringArray(view.dimensions.map { it.toString() }.sorted()))
            json.add("loot_context_types", stringArray(view.lootContextTypes.map { it.toString() }.sorted()))
            json.add("advancement_triggers", stringArray(view.advancementTriggers.map { it.toString() }.sorted()))
            json.add("loot_conditions", stringArray(view.lootConditions.map { it.toString() }.sorted()))
            json.add("loot_functions", stringArray(view.lootFunctions.map { it.toString() }.sorted()))
        }

    private fun setChangeMap(changes: Map<String, SetChange>): JsonObject =
        JsonObject().also { json ->
            changes.forEach { (name, change) -> json.add(name, setChange(change)) }
        }

    private fun setChange(change: SetChange): JsonObject =
        JsonObject().also { json ->
            json.add("added", stringArray(change.added))
            json.add("removed", stringArray(change.removed))
            json.addProperty("unchanged", change.unchanged)
            json.addProperty("changed", change.changed)
        }

    private fun <T> valueChange(change: ValueChange<T>): JsonObject =
        JsonObject().also { json ->
            json.add("from", value(change.from))
            json.add("to", value(change.to))
            json.addProperty("changed", change.changed)
        }

    private fun stringArray(values: Iterable<String>): JsonArray = JsonArray().also { array -> values.forEach { array.add(it) } }

    private fun value(value: Any?): JsonElement =
        when (value) {
            null -> JsonNull.INSTANCE
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }
}
