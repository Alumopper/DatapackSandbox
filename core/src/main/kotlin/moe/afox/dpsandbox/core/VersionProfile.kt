package moe.afox.dpsandbox.core

import com.google.gson.JsonElement
import java.math.BigDecimal

/**
 * Data pack format value.
 *
 * Recent Minecraft versions use decimal values such as `101.1`; this wrapper
 * preserves exact decimal comparison and stable string rendering.
 */
data class DataPackFormat(val value: BigDecimal) : Comparable<DataPackFormat> {
    /**
     * Returns true when this format is numerically equal to [other].
     */
    fun matches(other: DataPackFormat): Boolean = value.compareTo(other.value) == 0

    override fun compareTo(other: DataPackFormat): Int = value.compareTo(other.value)

    override fun toString(): String = value.stripTrailingZeros().toPlainString()

    companion object {
        /**
         * Creates a whole-number data pack format.
         */
        fun of(value: Int): DataPackFormat = DataPackFormat(BigDecimal(value))
        /**
         * Creates a data pack format from a decimal string.
         */
        fun of(value: String): DataPackFormat = DataPackFormat(BigDecimal(value))

        /**
         * Parses a JSON numeric `pack_format` value.
         *
         * @throws SandboxException when [element] is not a number.
         */
        fun parse(element: JsonElement): DataPackFormat {
            if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "pack_format must be a number")
            }
            return DataPackFormat(element.asBigDecimal)
        }
    }
}

/**
 * Version-specific datapack resource directory names.
 *
 * The lists are ordered by lookup priority and may include legacy aliases where
 * the active profile permits them.
 */
data class ResourceDirectoryProfile(
    val functions: List<String>,
    val functionTags: List<String>,
    val lootTables: List<String>,
    val predicates: List<String>,
    val advancements: List<String>,
) {
    companion object {
        /** Resource directory layout used by older plural-directory packs. */
        val legacyPlural = ResourceDirectoryProfile(
            functions = listOf("functions"),
            functionTags = listOf("functions"),
            lootTables = listOf("loot_tables"),
            predicates = listOf("predicates"),
            advancements = listOf("advancements"),
        )

        /** Current singular directory layout plus legacy aliases for compatibility profiles. */
        val currentWithLegacyAliases = ResourceDirectoryProfile(
            functions = listOf("function", "functions"),
            functionTags = listOf("function", "functions"),
            lootTables = listOf("loot_table", "loot_tables"),
            predicates = listOf("predicate", "predicates"),
            advancements = listOf("advancement", "advancements"),
        )
    }
}

/**
 * Set of vanilla command roots known to a version profile.
 */
data class CommandProfile(
    val roots: Set<String>,
) {
    /**
     * Returns whether [command] is a root command recognized by this profile.
     */
    fun hasRoot(command: String): Boolean = command in roots

    companion object {
        private val commonRoots = setOf(
            "advancement",
            "attribute",
            "ban",
            "ban-ip",
            "banlist",
            "bossbar",
            "clear",
            "clone",
            "damage",
            "data",
            "datapack",
            "debug",
            "defaultgamemode",
            "deop",
            "difficulty",
            "effect",
            "enchant",
            "execute",
            "experience",
            "fill",
            "fillbiome",
            "forceload",
            "function",
            "gamemode",
            "gamerule",
            "give",
            "help",
            "item",
            "jfr",
            "kick",
            "kill",
            "list",
            "locate",
            "loot",
            "me",
            "msg",
            "op",
            "pardon",
            "pardon-ip",
            "particle",
            "perf",
            "place",
            "playsound",
            "publish",
            "random",
            "recipe",
            "reload",
            "return",
            "ride",
            "rotate",
            "save-all",
            "save-off",
            "save-on",
            "say",
            "schedule",
            "scoreboard",
            "seed",
            "setblock",
            "setidletimeout",
            "setworldspawn",
            "spawnpoint",
            "spectate",
            "spreadplayers",
            "stop",
            "stopsound",
            "summon",
            "tag",
            "team",
            "teammsg",
            "tell",
            "tellraw",
            "tick",
            "time",
            "title",
            "tm",
            "tp",
            "trigger",
            "w",
            "weather",
            "whitelist",
            "worldborder",
            "xp",
        )

        val minecraft1204 = CommandProfile(commonRoots)
        val modern = CommandProfile(commonRoots + "transfer")
        val minecraft2612 = modern
        val minecraft262 = modern
    }
}

/**
 * Complete version profile used by the sandbox runtime.
 *
 * A profile determines datapack format validation, resource directory lookup,
 * command-root recognition, registry references, and NBT schema selection.
 */
data class VersionProfile(
    val id: String,
    val javaMajor: Int,
    val dataVersion: Int? = null,
    val dataPackFormat: DataPackFormat,
    val description: String,
    val resourceDirectories: ResourceDirectoryProfile,
    val commands: CommandProfile = CommandProfile.minecraft262,
    val registryView: RegistryView = RegistryView.vanilla262,
)

/**
 * Registry of built-in Minecraft Java version profiles.
 */
object VersionProfiles {
    val minecraft1204 = profile("1.20.4", java = 17, data = 3700, pack = "26", legacy = true)
    val minecraft1205 = profile("1.20.5", java = 21, data = 3837, pack = "41")
    val minecraft1206 = profile("1.20.6", java = 21, data = 3839, pack = "41")
    val minecraft121 = profile("1.21", java = 21, data = 3953, pack = "48")
    val minecraft1211 = profile("1.21.1", java = 21, data = 3955, pack = "48")
    val minecraft1212 = profile("1.21.2", java = 21, data = 4080, pack = "57")
    val minecraft1213 = profile("1.21.3", java = 21, data = 4082, pack = "57")
    val minecraft1214 = profile("1.21.4", java = 21, data = 4189, pack = "61")
    val minecraft1215 = profile("1.21.5", java = 21, data = 4325, pack = "71")
    val minecraft1216 = profile("1.21.6", java = 21, data = 4435, pack = "80")
    val minecraft1217 = profile("1.21.7", java = 21, data = 4438, pack = "81")
    val minecraft1218 = profile("1.21.8", java = 21, data = 4440, pack = "81")
    val minecraft1219 = profile("1.21.9", java = 21, data = 4554, pack = "88")
    val minecraft12110 = profile("1.21.10", java = 21, data = 4556, pack = "88")
    val minecraft12111 = profile("1.21.11", java = 21, data = 4671, pack = "94.1")
    val minecraft261 = profile("26.1", java = 25, data = 4786, pack = "101.1")
    val minecraft2611 = profile("26.1.1", java = 25, data = 4788, pack = "101.1")
    val minecraft2612 = profile("26.1.2", java = 25, data = 4790, pack = "101.1", registryView = RegistryView.vanilla2612)
    val minecraft262 = profile("26.2", java = 25, data = 4903, pack = "107.1", registryView = RegistryView.vanilla262)

    val default: VersionProfile = minecraft262
    val all: List<VersionProfile> = listOf(
        minecraft1204,
        minecraft1205,
        minecraft1206,
        minecraft121,
        minecraft1211,
        minecraft1212,
        minecraft1213,
        minecraft1214,
        minecraft1215,
        minecraft1216,
        minecraft1217,
        minecraft1218,
        minecraft1219,
        minecraft12110,
        minecraft12111,
        minecraft261,
        minecraft2611,
        minecraft2612,
        minecraft262,
    )

    /**
     * Returns a built-in profile by id.
     *
     * @throws SandboxException when [id] is unknown.
     */
    fun get(id: String): VersionProfile =
        all.firstOrNull { it.id == id }
            ?: throw SandboxException(
                code = DiagnosticCode.INPUT_FORMAT,
                message = "Unknown version profile '$id'. Available profiles: ${all.joinToString { it.id }}",
                version = id,
            )

    private fun profile(
        id: String,
        java: Int,
        data: Int,
        pack: String,
        legacy: Boolean = false,
        registryView: RegistryView = RegistryView.vanilla262,
    ): VersionProfile =
        VersionProfile(
            id = id,
            javaMajor = java,
            dataVersion = data,
            dataPackFormat = DataPackFormat.of(pack),
            description = "Minecraft Java $id datapack profile",
            resourceDirectories = if (legacy) ResourceDirectoryProfile.legacyPlural else ResourceDirectoryProfile.currentWithLegacyAliases,
            commands = if (legacy) CommandProfile.minecraft1204 else CommandProfile.modern,
            registryView = registryView,
        )
}
