package moe.afox.dpsandbox.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.nio.file.Path

/**
 * One executable command line loaded from a `.mcfunction` file.
 */
data class FunctionLine(
    /** Command text without a leading slash. */
    val command: String,
    /** Source file and line used for diagnostics. */
    val location: SourceLocation,
)

/**
 * Loaded datapack function.
 */
data class DatapackFunction(
    /** Function resource id, for example `demo:main`. */
    val id: ResourceLocation,
    /** Parsed command lines in source order. */
    val lines: List<FunctionLine>,
)

/**
 * Synthetic datapack wrapper used by single-file `.mcfunction` tests.
 */
object SingleFunctionDatapack {
    /** Default function id assigned to a single function file. */
    const val DEFAULT_ID: String = "sandbox:main"

    /**
     * Returns [DEFAULT_ID] as a typed [ResourceLocation].
     */
    @JvmStatic
    fun defaultId(): ResourceLocation = ResourceLocation.parse(DEFAULT_ID)
}

/**
 * Source for a synthetic `.mcfunction` resource.
 *
 * A source can be backed by in-memory text or by a file. Multiple sources can
 * be loaded together through `DatapackLoader.loadFunctionSources(...)`, which
 * allows lightweight tests without creating a full datapack directory.
 */
class FunctionSource private constructor(
    /** Function id exposed to `function <id>` commands. */
    val id: ResourceLocation,
    /** In-memory function content, or null when [path] is used. */
    val content: String?,
    /** File path for this function, or null when [content] is used. */
    val path: Path?,
    /** Human-readable source label used in diagnostics. */
    val sourceName: String,
) {
    companion object {
        /**
         * Creates an in-memory function source.
         *
         * @param id Function id such as `demo:main`.
         * @param content Raw `.mcfunction` text.
         * @param sourceName Label used in diagnostics; defaults to a synthetic string label.
         */
        @JvmStatic
        @JvmOverloads
        fun text(id: String, content: String, sourceName: String = "<string:$id>"): FunctionSource =
            FunctionSource(ResourceLocation.parse(id), content, null, sourceName)

        /**
         * Creates a file-backed function source.
         *
         * The file is validated by the loader so callers receive a structured
         * [SandboxException] with the active version profile.
         */
        @JvmStatic
        fun file(id: String, path: Path): FunctionSource =
            FunctionSource(ResourceLocation.parse(id), null, path, path.toAbsolutePath().normalize().toString())
    }
}

/**
 * Loaded raw JSON resource before it is converted to a typed model.
 */
data class ResourceJson(
    val id: ResourceLocation,
    val file: String,
    val root: JsonElement,
)

/**
 * Loaded raw JSON resource with a stable kind label.
 *
 * This is used for datapack resources whose full runtime semantics are not yet
 * modeled, while still making them loadable, inspectable, and version-aware.
 */
data class RawJsonResource(
    val kind: String,
    val id: ResourceLocation,
    val file: String,
    val root: JsonElement,
)

/**
 * One value entry inside a datapack tag.
 */
data class TagValue(
    /** Raw id text. Tag references keep their leading '#'. */
    val id: String,
    /** Vanilla tag object entries may mark a value as optional. */
    val required: Boolean = true,
)

/**
 * Resource key for a tag under a registry directory such as `item` or `block`.
 */
data class TagKey(
    val registry: String,
    val id: ResourceLocation,
) : Comparable<TagKey> {
    override fun compareTo(other: TagKey): Int =
        compareValuesBy(this, other, TagKey::registry, { it.id.toString() })

    override fun toString(): String = "$registry/$id"
}

/**
 * Loaded datapack tag definition.
 */
data class TagDefinition(
    val key: TagKey,
    val file: String,
    val replace: Boolean = false,
    val values: List<TagValue> = emptyList(),
)

/**
 * One resource contribution recorded while loading datapacks.
 *
 * [active] is false when a later pack overrode the resource. [overrides] and
 * [overriddenBy] make pack overlay behavior inspectable without changing the
 * loaded runtime maps.
 */
data class ResourceIndexEntry(
    val type: String,
    val id: ResourceLocation,
    val file: String,
    val pack: String,
    val order: Int,
    val active: Boolean = true,
    val overrides: String? = null,
    val overriddenBy: String? = null,
)

/**
 * Loaded loot table resource.
 */
data class LootTable(
    val id: ResourceLocation,
    val file: String,
    val root: JsonObject,
)

/**
 * Parsed loot pool model used by [LootEngine].
 */
data class LootPool(
    val rolls: JsonElement,
    val bonusRolls: JsonElement?,
    val entries: List<LootEntry>,
    val conditions: List<LootCondition>,
    val functions: List<LootFunction>,
)

/** Loot entry with its raw JSON payload. */
data class LootEntry(val type: String, val root: JsonObject)
/** Loot condition with its raw JSON payload. */
data class LootCondition(val type: String, val root: JsonObject)
/** Loot function with its raw JSON payload. */
data class LootFunction(val type: String, val root: JsonObject)

/**
 * Loaded predicate resource.
 */
data class PredicateDefinition(
    val id: ResourceLocation,
    val file: String,
    val root: JsonElement,
)

/**
 * Loaded advancement definition.
 */
data class AdvancementDefinition(
    val id: ResourceLocation,
    val file: String,
    val root: JsonObject,
    val parent: ResourceLocation?,
    val criteria: Map<String, Criterion>,
    val requirements: List<List<String>>,
    val rewards: AdvancementReward,
)

/**
 * One advancement criterion.
 */
data class Criterion(
    val name: String,
    val trigger: ResourceLocation,
    val conditions: JsonObject?,
)

/**
 * Advancement rewards executed when an advancement becomes complete.
 */
data class AdvancementReward(
    val experience: Int = 0,
    val loot: List<ResourceLocation> = emptyList(),
    val recipes: List<ResourceLocation> = emptyList(),
    val function: ResourceLocation? = null,
)

/**
 * Immutable collection of loaded datapack resources.
 */
data class Datapack(
    val functions: Map<ResourceLocation, DatapackFunction>,
    val loadFunctions: List<ResourceLocation>,
    val tickFunctions: List<ResourceLocation>,
    val lootTables: Map<ResourceLocation, LootTable> = emptyMap(),
    val predicates: Map<ResourceLocation, PredicateDefinition> = emptyMap(),
    val advancements: Map<ResourceLocation, AdvancementDefinition> = emptyMap(),
    val recipes: Map<ResourceLocation, RawJsonResource> = emptyMap(),
    val itemModifiers: Map<ResourceLocation, RawJsonResource> = emptyMap(),
    val tags: Map<TagKey, TagDefinition> = emptyMap(),
    val resourceIndex: List<ResourceIndexEntry> = emptyList(),
) {
    /**
     * Returns a function by id.
     *
     * @throws SandboxException with [DiagnosticCode.RESOURCE_NOT_FOUND] when missing.
     */
    fun function(id: ResourceLocation): DatapackFunction =
        functions[id]
            ?: throw SandboxException(
                code = DiagnosticCode.RESOURCE_NOT_FOUND,
                message = "Function '$id' was not found",
            )

    /**
     * Returns a loot table by id.
     *
     * @throws SandboxException with [DiagnosticCode.RESOURCE_NOT_FOUND] when missing.
     */
    fun lootTable(id: ResourceLocation): LootTable =
        lootTables[id]
            ?: throw SandboxException(
                code = DiagnosticCode.RESOURCE_NOT_FOUND,
                message = "Loot table '$id' was not found",
            )

    /**
     * Returns a predicate by id.
     *
     * @throws SandboxException with [DiagnosticCode.RESOURCE_NOT_FOUND] when missing.
     */
    fun predicate(id: ResourceLocation): PredicateDefinition =
        predicates[id]
            ?: throw SandboxException(
                code = DiagnosticCode.RESOURCE_NOT_FOUND,
                message = "Predicate '$id' was not found",
            )

    /**
     * Returns an advancement by id.
     *
     * @throws SandboxException with [DiagnosticCode.RESOURCE_NOT_FOUND] when missing.
     */
    fun advancement(id: ResourceLocation): AdvancementDefinition =
        advancements[id]
            ?: throw SandboxException(
                code = DiagnosticCode.RESOURCE_NOT_FOUND,
                message = "Advancement '$id' was not found",
            )

    /**
     * Returns a recipe by id.
     *
     * @throws SandboxException with [DiagnosticCode.RESOURCE_NOT_FOUND] when missing.
     */
    fun recipe(id: ResourceLocation): RawJsonResource =
        recipes[id]
            ?: throw SandboxException(
                code = DiagnosticCode.RESOURCE_NOT_FOUND,
                message = "Recipe '$id' was not found",
            )

    /**
     * Returns an item modifier by id.
     *
     * @throws SandboxException with [DiagnosticCode.RESOURCE_NOT_FOUND] when missing.
     */
    fun itemModifier(id: ResourceLocation): RawJsonResource =
        itemModifiers[id]
            ?: throw SandboxException(
                code = DiagnosticCode.RESOURCE_NOT_FOUND,
                message = "Item modifier '$id' was not found",
            )

    /**
     * Returns a datapack tag by registry and id.
     *
     * @throws SandboxException with [DiagnosticCode.RESOURCE_NOT_FOUND] when missing.
     */
    fun tag(registry: String, id: ResourceLocation): TagDefinition =
        tags[TagKey(registry, id)]
            ?: throw SandboxException(
                code = DiagnosticCode.RESOURCE_NOT_FOUND,
                message = "Tag '$registry/$id' was not found",
            )
}
