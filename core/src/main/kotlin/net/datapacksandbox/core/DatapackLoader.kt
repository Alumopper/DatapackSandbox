package net.datapacksandbox.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.relativeTo

object DatapackLoader {
    fun load(paths: List<Path>, profile: VersionProfile): Datapack {
        if (paths.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "At least one datapack path is required", version = profile.id)
        }

        val functions = linkedMapOf<ResourceLocation, DatapackFunction>()
        val loadFunctions = mutableListOf<ResourceLocation>()
        val tickFunctions = mutableListOf<ResourceLocation>()
        val lootTables = linkedMapOf<ResourceLocation, LootTable>()
        val predicates = linkedMapOf<ResourceLocation, PredicateDefinition>()
        val advancements = linkedMapOf<ResourceLocation, AdvancementDefinition>()

        paths.forEach { input ->
            withPackRoot(input) { root ->
                validatePackMetadata(root, input, profile)
                functions.putAll(readFunctions(root))
                loadFunctions += readFunctionTag(root, "load")
                tickFunctions += readFunctionTag(root, "tick")
                lootTables.putAll(readLootTables(root, profile))
                predicates.putAll(readPredicates(root, profile))
                advancements.putAll(readAdvancements(root, profile))
            }
        }

        return Datapack(
            functions = functions.toSortedMap(),
            loadFunctions = loadFunctions,
            tickFunctions = tickFunctions,
            lootTables = lootTables.toSortedMap(),
            predicates = predicates.toSortedMap(),
            advancements = advancements.toSortedMap(),
        )
    }

    private fun withPackRoot(input: Path, block: (Path) -> Unit) {
        val normalized = input.toAbsolutePath().normalize()
        if (!normalized.exists()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Datapack path does not exist: $normalized")
        }

        if (normalized.isDirectory()) {
            block(normalized)
            return
        }

        if (normalized.isRegularFile() && normalized.extension.lowercase() == "zip") {
            var fs: FileSystem? = null
            try {
                fs = FileSystems.newFileSystem(URI.create("jar:${normalized.toUri()}"), mapOf<String, String>())
                block(fs.getPath("/"))
            } finally {
                fs?.close()
            }
            return
        }

        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Datapack path must be a directory or .zip file: $normalized")
    }

    private fun validatePackMetadata(root: Path, originalInput: Path, profile: VersionProfile) {
        val meta = root.resolve("pack.mcmeta")
        if (!meta.exists()) {
            throw SandboxException(
                code = DiagnosticCode.INPUT_FORMAT,
                message = "Missing pack.mcmeta in datapack: ${originalInput.toAbsolutePath().normalize()}",
                version = profile.id,
            )
        }
        try {
            val element = JsonParser.parseString(Files.readString(meta, StandardCharsets.UTF_8))
            if (!element.isJsonObject || !element.asJsonObject.has("pack")) {
                throw IllegalArgumentException("pack.mcmeta must contain a top-level 'pack' object")
            }
        } catch (error: Exception) {
            throw SandboxException(
                code = DiagnosticCode.INPUT_FORMAT,
                message = "Invalid pack.mcmeta: ${error.message}",
                location = SourceLocation(file = meta.toString()),
                version = profile.id,
                cause = error,
            )
        }
    }

    private fun readFunctions(root: Path): Map<ResourceLocation, DatapackFunction> {
        val data = root.resolve("data")
        if (!data.exists()) return emptyMap()

        val functions = linkedMapOf<ResourceLocation, DatapackFunction>()
        Files.list(data).use { namespaces ->
            namespaces.filter { it.isDirectory() }.forEach { namespaceDir ->
                val namespace = namespaceDir.name
                listOf("function", "functions").forEach { functionDirName ->
                    val functionRoot = namespaceDir.resolve(functionDirName)
                    if (!functionRoot.exists()) return@forEach
                    Files.walk(functionRoot).use { walk ->
                        walk.filter { it.isRegularFile() && it.name.endsWith(".mcfunction") }
                            .forEach { file ->
                                val relative = file.relativeTo(functionRoot).toString().replace('\\', '/')
                                val idPath = relative.removeSuffix(".mcfunction")
                                val id = ResourceLocation(namespace, idPath)
                                functions[id] = DatapackFunction(id, readFunctionLines(file))
                            }
                    }
                }
            }
        }
        return functions
    }

    private fun readFunctionLines(file: Path): List<FunctionLine> =
        Files.readAllLines(file, StandardCharsets.UTF_8).mapIndexedNotNull { index, raw ->
            val stripped = raw.trim()
            if (stripped.isEmpty() || stripped.startsWith("#")) {
                null
            } else {
                val command = stripped.removePrefix("/")
                FunctionLine(command, SourceLocation(file = file.toString(), line = index + 1, command = command))
            }
        }

    private fun readFunctionTag(root: Path, tagName: String): List<ResourceLocation> {
        val tags = root.resolve("data").resolve("minecraft").resolve("tags")
        val candidates = listOf(
            tags.resolve("function").resolve("$tagName.json"),
            tags.resolve("functions").resolve("$tagName.json"),
        )

        return candidates.filter { it.exists() }.flatMap { tagPath ->
            val json = try {
                JsonParser.parseString(Files.readString(tagPath, StandardCharsets.UTF_8)).asJsonObject
            } catch (error: Exception) {
                throw SandboxException(
                    code = DiagnosticCode.INPUT_FORMAT,
                    message = "Invalid function tag ${tagPath}: ${error.message}",
                    location = SourceLocation(file = tagPath.toString()),
                    cause = error,
                )
            }
            readTagValues(json, tagPath)
        }
    }

    private fun readTagValues(json: JsonObject, tagPath: Path): List<ResourceLocation> {
        val values = json.get("values")
        if (values !is JsonArray) {
            throw SandboxException(
                code = DiagnosticCode.INPUT_FORMAT,
                message = "Function tag must contain a 'values' array",
                location = SourceLocation(file = tagPath.toString()),
            )
        }

        return values.map { entry ->
            val id = when {
                entry.isJsonPrimitive -> entry.asString
                entry.isJsonObject && entry.asJsonObject.has("id") -> entry.asJsonObject.get("id").asString
                else -> throw SandboxException(
                    code = DiagnosticCode.INPUT_FORMAT,
                    message = "Function tag entries must be strings or objects with an 'id'",
                    location = SourceLocation(file = tagPath.toString()),
                )
            }
            ResourceLocation.parse(id)
        }
    }

    private fun readLootTables(root: Path, profile: VersionProfile): Map<ResourceLocation, LootTable> =
        readJsonResources(root, profile, listOf("loot_table", "loot_tables"), "loot table")
            .mapValues { (_, resource) ->
                val objectRoot = resource.root.asObjectOrError(resource, profile, "Loot table")
                LootTable(resource.id, resource.file, objectRoot)
            }

    private fun readPredicates(root: Path, profile: VersionProfile): Map<ResourceLocation, PredicateDefinition> =
        readJsonResources(root, profile, listOf("predicate", "predicates"), "predicate")
            .mapValues { (_, resource) -> PredicateDefinition(resource.id, resource.file, resource.root) }

    private fun readAdvancements(root: Path, profile: VersionProfile): Map<ResourceLocation, AdvancementDefinition> =
        readJsonResources(root, profile, listOf("advancement", "advancements"), "advancement")
            .mapValues { (_, resource) -> parseAdvancement(resource, profile) }

    private fun readJsonResources(
        root: Path,
        profile: VersionProfile,
        directoryNames: List<String>,
        kind: String,
    ): Map<ResourceLocation, ResourceJson> {
        val data = root.resolve("data")
        if (!data.exists()) return emptyMap()

        val resources = linkedMapOf<ResourceLocation, ResourceJson>()
        Files.list(data).use { namespaces ->
            namespaces.filter { it.isDirectory() }.forEach { namespaceDir ->
                val namespace = namespaceDir.name
                directoryNames.forEach { directoryName ->
                    val resourceRoot = namespaceDir.resolve(directoryName)
                    if (!resourceRoot.exists()) return@forEach
                    Files.walk(resourceRoot).use { walk ->
                        walk.filter { it.isRegularFile() && it.name.endsWith(".json") }
                            .forEach { file ->
                                val relative = file.relativeTo(resourceRoot).toString().replace('\\', '/')
                                val id = ResourceLocation(namespace, relative.removeSuffix(".json"))
                                val element = try {
                                    JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                                } catch (error: Exception) {
                                    throw SandboxException(
                                        code = DiagnosticCode.INPUT_FORMAT,
                                        message = "Invalid $kind JSON for '$id': ${error.message}",
                                        location = SourceLocation(file = file.toString()),
                                        version = profile.id,
                                        cause = error,
                                    )
                                }
                                resources[id] = ResourceJson(id, file.toString(), element)
                            }
                    }
                }
            }
        }
        return resources
    }

    private fun parseAdvancement(resource: ResourceJson, profile: VersionProfile): AdvancementDefinition {
        val root = resource.root.asObjectOrError(resource, profile, "Advancement")
        val parent = root.optionalString("parent")?.let { ResourceLocation.parse(it) }
        val criteriaObject = root.getAsJsonObject("criteria")
            ?: throw resourceError(resource, profile, "Advancement must contain a criteria object")

        val criteria = criteriaObject.entrySet().associate { (name, value) ->
            if (!value.isJsonObject) {
                throw resourceError(resource, profile, "Criterion '$name' must be an object")
            }
            val criterion = value.asJsonObject
            val trigger = criterion.optionalString("trigger")
                ?: throw resourceError(resource, profile, "Criterion '$name' is missing trigger")
            name to Criterion(
                name = name,
                trigger = ResourceLocation.parse(trigger),
                conditions = criterion.getAsJsonObject("conditions"),
            )
        }.toSortedMap()

        val requirements = when (val requirementsJson = root.get("requirements")) {
            null -> criteria.keys.map { listOf(it) }
            else -> {
                if (!requirementsJson.isJsonArray) {
                    throw resourceError(resource, profile, "Advancement requirements must be an array")
                }
                requirementsJson.asJsonArray.map { row ->
                    if (!row.isJsonArray) {
                        throw resourceError(resource, profile, "Advancement requirement rows must be arrays")
                    }
                    row.asJsonArray.map { it.asString }
                }
            }
        }

        val rewards = root.getAsJsonObject("rewards")?.let { rewards ->
            AdvancementReward(
                experience = rewards.get("experience")?.asInt ?: 0,
                loot = rewards.arrayStrings("loot").map { ResourceLocation.parse(it) },
                recipes = rewards.arrayStrings("recipes").map { ResourceLocation.parse(it) },
                function = rewards.optionalString("function")?.let { ResourceLocation.parse(it) },
            )
        } ?: AdvancementReward()

        return AdvancementDefinition(resource.id, resource.file, root, parent, criteria, requirements, rewards)
    }

    private fun JsonElement.asObjectOrError(resource: ResourceJson, profile: VersionProfile, kind: String): JsonObject {
        if (!isJsonObject) {
            throw resourceError(resource, profile, "$kind resource '$resource.id' must be a JSON object")
        }
        return asJsonObject
    }

    private fun JsonObject.optionalString(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.arrayStrings(name: String): List<String> {
        val value = get(name) ?: return emptyList()
        if (!value.isJsonArray) return emptyList()
        return value.asJsonArray.map { it.asString }
    }

    private fun resourceError(resource: ResourceJson, profile: VersionProfile, message: String): SandboxException =
        SandboxException(
            code = DiagnosticCode.INPUT_FORMAT,
            message = message,
            location = SourceLocation(file = resource.file),
            version = profile.id,
        )
}
