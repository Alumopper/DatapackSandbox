package net.datapacksandbox.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject

data class FunctionLine(
    val command: String,
    val location: SourceLocation,
)

data class DatapackFunction(
    val id: ResourceLocation,
    val lines: List<FunctionLine>,
)

data class ResourceJson(
    val id: ResourceLocation,
    val file: String,
    val root: JsonElement,
)

data class LootTable(
    val id: ResourceLocation,
    val file: String,
    val root: JsonObject,
)

data class LootPool(
    val rolls: JsonElement,
    val bonusRolls: JsonElement?,
    val entries: List<LootEntry>,
    val conditions: List<LootCondition>,
    val functions: List<LootFunction>,
)

data class LootEntry(val type: String, val root: JsonObject)
data class LootCondition(val type: String, val root: JsonObject)
data class LootFunction(val type: String, val root: JsonObject)

data class PredicateDefinition(
    val id: ResourceLocation,
    val file: String,
    val root: JsonElement,
)

data class AdvancementDefinition(
    val id: ResourceLocation,
    val file: String,
    val root: JsonObject,
    val parent: ResourceLocation?,
    val criteria: Map<String, Criterion>,
    val requirements: List<List<String>>,
    val rewards: AdvancementReward,
)

data class Criterion(
    val name: String,
    val trigger: ResourceLocation,
    val conditions: JsonObject?,
)

data class AdvancementReward(
    val experience: Int = 0,
    val loot: List<ResourceLocation> = emptyList(),
    val recipes: List<ResourceLocation> = emptyList(),
    val function: ResourceLocation? = null,
)

data class Datapack(
    val functions: Map<ResourceLocation, DatapackFunction>,
    val loadFunctions: List<ResourceLocation>,
    val tickFunctions: List<ResourceLocation>,
    val lootTables: Map<ResourceLocation, LootTable> = emptyMap(),
    val predicates: Map<ResourceLocation, PredicateDefinition> = emptyMap(),
    val advancements: Map<ResourceLocation, AdvancementDefinition> = emptyMap(),
) {
    fun function(id: ResourceLocation): DatapackFunction =
        functions[id]
            ?: throw SandboxException(
                code = DiagnosticCode.RESOURCE_NOT_FOUND,
                message = "Function '$id' was not found",
            )

    fun lootTable(id: ResourceLocation): LootTable =
        lootTables[id]
            ?: throw SandboxException(
                code = DiagnosticCode.RESOURCE_NOT_FOUND,
                message = "Loot table '$id' was not found",
            )

    fun predicate(id: ResourceLocation): PredicateDefinition =
        predicates[id]
            ?: throw SandboxException(
                code = DiagnosticCode.RESOURCE_NOT_FOUND,
                message = "Predicate '$id' was not found",
            )

    fun advancement(id: ResourceLocation): AdvancementDefinition =
        advancements[id]
            ?: throw SandboxException(
                code = DiagnosticCode.RESOURCE_NOT_FOUND,
                message = "Advancement '$id' was not found",
            )
}
