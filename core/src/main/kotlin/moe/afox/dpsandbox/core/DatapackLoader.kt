package moe.afox.dpsandbox.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.relativeTo

/**
 * Loads datapack resources from directories, zip files, or synthetic function sources.
 */
object DatapackLoader {
    private data class FunctionTagDefinition(
        val id: ResourceLocation,
        val file: String,
        val replace: Boolean,
        val values: List<FunctionTagEntry>,
    )

    private data class FunctionTagEntry(
        val id: ResourceLocation,
        val required: Boolean,
    )

    private data class RawJsonResourceSpec(
        val kind: String,
        val directories: List<String> = listOf(kind),
    ) {
        val errorLabel: String = kind.replace('/', ' ').replace('_', ' ')
    }

    private val additionalRawJsonResourceSpecs = ResourceCatalog.additionalRawJsonTypes.map(::RawJsonResourceSpec)

    private data class LoadCacheKey(
        val profile: String,
        val packs: List<PackFingerprint>,
    )

    private data class PackFingerprint(
        val path: String,
        val type: String,
        val hash: String,
        val files: List<FileFingerprint> = emptyList(),
    )

    private data class FileFingerprint(
        val relativePath: String,
        val hash: String,
    )

    private const val MAX_LOAD_CACHE_ENTRIES = 16
    private const val MAX_PACK_ENTRIES = 100_000
    private const val MAX_PACK_ARCHIVE_BYTES = 512L * 1024L * 1024L
    private const val MAX_PACK_EXPANDED_BYTES = 512L * 1024L * 1024L
    private const val MAX_PACK_ENTRY_BYTES = 64L * 1024L * 1024L
    private const val MAX_TEXT_RESOURCE_BYTES = 16L * 1024L * 1024L

    private val loadCache =
        object : LinkedHashMap<LoadCacheKey, Datapack>(MAX_LOAD_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LoadCacheKey, Datapack>?): Boolean =
                size > MAX_LOAD_CACHE_ENTRIES
        }

    /**
     * Clears cached parsed datapacks. Content fingerprints normally invalidate
     * entries automatically, but REPL/watch integrations can call this before a
     * forced reload when they want to discard all retained pack objects.
     */
    @JvmStatic
    fun clearCache() {
        synchronized(loadCache) {
            loadCache.clear()
        }
    }

    /**
     * Loads and validates one or more datapacks for [profile].
     *
     * Each path must be a datapack directory containing `pack.mcmeta` or a `.zip`
     * file. Resource directories, pack formats, and legacy aliases are resolved
     * through [profile]. Later packs override earlier resources with the same id
     * in maps such as functions, loot tables, predicates, and advancements.
     *
     * @param paths Datapack directories or zip files.
     * @throws SandboxException for missing paths, invalid `pack.mcmeta`,
     * malformed JSON, or invalid resource ids. Pack format mismatches are
     * returned as [Datapack.warnings].
     */
    fun load(
        paths: List<Path>,
        profile: VersionProfile,
    ): Datapack {
        if (paths.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "At least one datapack path is required", version = profile.id)
        }
        val key = loadCacheKey(paths, profile)
        synchronized(loadCache) {
            loadCache[key]?.let { return it.cacheCopy() }
        }
        val datapack = loadUncached(paths, profile)
        synchronized(loadCache) {
            loadCache[key] = datapack.cacheCopy()
        }
        return datapack
    }

    private fun loadUncached(
        paths: List<Path>,
        profile: VersionProfile,
    ): Datapack {
        val functions = linkedMapOf<ResourceLocation, DatapackFunction>()
        val loadFunctionEntries = mutableListOf<FunctionTagEntry>()
        val tickFunctionEntries = mutableListOf<FunctionTagEntry>()
        val lootTables = linkedMapOf<ResourceLocation, LootTable>()
        val predicates = linkedMapOf<ResourceLocation, PredicateDefinition>()
        val advancements = linkedMapOf<ResourceLocation, AdvancementDefinition>()
        val recipes = linkedMapOf<ResourceLocation, RawJsonResource>()
        val itemModifiers = linkedMapOf<ResourceLocation, RawJsonResource>()
        val rawResources = linkedMapOf<String, MutableMap<ResourceLocation, RawJsonResource>>()
        val tags = linkedMapOf<TagKey, TagDefinition>()
        val resourceIndex = ResourceIndexBuilder()
        val warnings = mutableListOf<DatapackWarning>()

        fun mergeRawResources(
            kind: String,
            resources: Map<ResourceLocation, RawJsonResource>,
        ) {
            if (resources.isEmpty()) return
            rawResources.getOrPut(kind) { linkedMapOf() }.putAll(resources)
        }

        paths.forEach { input ->
            val packLabel = input.toAbsolutePath().normalize().toString()
            withPackRoot(input) { root ->
                validatePackMetadata(root, input, profile, warnings)
                val packFunctions = readFunctions(root, profile)
                resourceIndex.recordAll("function", packFunctions, packLabel)
                functions.putAll(packFunctions)
                val packLoadFunctionTags = readFunctionTag(root, profile, "load")
                val packTickFunctionTags = readFunctionTag(root, profile, "tick")
                (packLoadFunctionTags + packTickFunctionTags).forEach { tag ->
                    resourceIndex.record(
                        type = "tag/function",
                        id = tag.id,
                        file = tag.file,
                        pack = packLabel,
                        overridesPrevious = tag.replace,
                    )
                }
                mergeFunctionTags(loadFunctionEntries, packLoadFunctionTags)
                mergeFunctionTags(tickFunctionEntries, packTickFunctionTags)

                val packLootTables = readLootTables(root, profile)
                resourceIndex.recordAll("loot_table", packLootTables, packLabel)
                lootTables.putAll(packLootTables)

                val packPredicates = readPredicates(root, profile)
                resourceIndex.recordAll("predicate", packPredicates, packLabel)
                predicates.putAll(packPredicates)

                val packAdvancements = readAdvancements(root, profile)
                resourceIndex.recordAll("advancement", packAdvancements, packLabel)
                advancements.putAll(packAdvancements)

                val packRecipes = readRecipes(root, profile)
                resourceIndex.recordAll("recipe", packRecipes, packLabel)
                recipes.putAll(packRecipes)
                mergeRawResources("recipe", packRecipes)

                val packItemModifiers = readItemModifiers(root, profile)
                resourceIndex.recordAll("item_modifier", packItemModifiers, packLabel)
                itemModifiers.putAll(packItemModifiers)
                mergeRawResources("item_modifier", packItemModifiers)

                readAdditionalRawJsonResources(root, profile).forEach { (kind, resources) ->
                    resourceIndex.recordAll(kind, resources, packLabel)
                    mergeRawResources(kind, resources)
                }

                val packBinaryStructures = readBinaryStructureResources(root, profile)
                resourceIndex.recordAll("worldgen/structure", packBinaryStructures, packLabel)
                mergeRawResources("worldgen/structure", packBinaryStructures)

                val packTags = readTags(root, profile)
                mergeTags(tags, packTags)
                packTags.values.forEach { tag ->
                    resourceIndex.record(
                        type = "tag/${tag.key.registry}",
                        id = tag.key.id,
                        file = tag.file,
                        pack = packLabel,
                        overridesPrevious = tag.replace,
                    )
                }
            }
        }

        return Datapack(
            functions = functions.toSortedMap(),
            loadFunctions = effectiveFunctionTagFunctions(loadFunctionEntries, functions),
            tickFunctions = effectiveFunctionTagFunctions(tickFunctionEntries, functions),
            lootTables = lootTables.toSortedMap(),
            predicates = predicates.toSortedMap(),
            advancements = advancements.toSortedMap(),
            recipes = recipes.toSortedMap(),
            itemModifiers = itemModifiers.toSortedMap(),
            rawResources = rawResources.toSortedRawResourceMap(),
            tags = tags.toSortedMap(),
            resourceIndex = resourceIndex.entries(),
            warnings = warnings.toList(),
        )
    }

    /**
     * Loads multiple `.mcfunction` sources as a synthetic datapack.
     *
     * Sources can be backed by files or in-memory strings. The resulting
     * datapack contains exactly the supplied functions and no load/tick tags.
     *
     * @throws SandboxException when no sources are provided, a file source is
     * missing/invalid, or duplicate function ids are supplied.
     */
    @JvmStatic
    fun loadFunctionSources(
        sources: List<FunctionSource>,
        profile: VersionProfile,
    ): Datapack {
        if (sources.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "At least one function source is required", version = profile.id)
        }
        val functions = linkedMapOf<ResourceLocation, DatapackFunction>()
        sources.forEach { source ->
            if (functions.containsKey(source.id)) {
                throw SandboxException(
                    code = DiagnosticCode.INPUT_FORMAT,
                    message = "Duplicate function source id '${source.id}'",
                    version = profile.id,
                )
            }
            functions[source.id] = DatapackFunction(source.id, readFunctionLines(source, profile))
        }
        return Datapack(
            functions = functions.toSortedMap(),
            loadFunctions = emptyList(),
            tickFunctions = emptyList(),
            resourceIndex =
                functions.values.mapIndexed { index, function ->
                    ResourceIndexEntry(
                        type = "function",
                        id = function.id,
                        file =
                            function.lines
                                .firstOrNull()
                                ?.location
                                ?.file ?: "<synthetic:${function.id}>",
                        pack = "<synthetic>",
                        order = index,
                    )
                },
        )
    }

    /**
     * Loads datapack dependencies and overlays synthetic function sources.
     *
     * Dependency packs may be directories or zip files. Their resources are
     * loaded first, then [sources] are added as top-priority functions. This is
     * useful for testing generated or inline functions against real datapack
     * dependencies without creating another datapack directory.
     *
     * @throws SandboxException when both [paths] and [sources] are empty, a pack
     * is invalid, a function source is invalid, or duplicate synthetic function
     * ids are supplied.
     */
    @JvmStatic
    fun loadFunctionSources(
        paths: List<Path>,
        sources: List<FunctionSource>,
        profile: VersionProfile,
    ): Datapack {
        if (paths.isEmpty()) return loadFunctionSources(sources, profile)
        if (sources.isEmpty()) return load(paths, profile)

        val dependencies = load(paths, profile)
        val overlay = loadFunctionSources(sources, profile)
        return Datapack(
            functions = (dependencies.functions + overlay.functions).toSortedMap(),
            loadFunctions = dependencies.loadFunctions,
            tickFunctions = dependencies.tickFunctions,
            lootTables = dependencies.lootTables,
            predicates = dependencies.predicates,
            advancements = dependencies.advancements,
            recipes = dependencies.recipes,
            itemModifiers = dependencies.itemModifiers,
            rawResources = dependencies.rawResources,
            tags = dependencies.tags,
            resourceIndex =
                dependencies.resourceIndex +
                    overlay.resourceIndex.map { entry ->
                        if (dependencies.functions.containsKey(entry.id) && entry.type == "function") {
                            entry.copy(
                                overrides =
                                    dependencies.functions[entry.id]
                                        ?.lines
                                        ?.firstOrNull()
                                        ?.location
                                        ?.file,
                            )
                        } else {
                            entry
                        }
                    },
        )
    }

    /**
     * Loads one `.mcfunction` file as a synthetic datapack.
     *
     * The resulting datapack contains exactly one function with [functionId] and
     * no load/tick tags. This is the low-level helper used by
     * [createFunctionSandbox] and the testkit module's `SandboxQuickTest.singleFunction` facade.
     *
     * @throws SandboxException when [functionFile] is missing or not a regular
     * `.mcfunction` file.
     */
    @JvmStatic
    @JvmOverloads
    fun loadSingleFunction(
        functionFile: Path,
        profile: VersionProfile,
        functionId: ResourceLocation = SingleFunctionDatapack.defaultId(),
    ): Datapack = loadFunctionSources(listOf(FunctionSource.file(functionId.toString(), functionFile)), profile)

    /**
     * Loads one in-memory `.mcfunction` string as a synthetic datapack.
     */
    @JvmStatic
    @JvmOverloads
    fun loadFunctionText(
        content: String,
        profile: VersionProfile,
        functionId: ResourceLocation = SingleFunctionDatapack.defaultId(),
        sourceName: String = "<string:$functionId>",
    ): Datapack = loadFunctionSources(listOf(FunctionSource.text(functionId.toString(), content, sourceName)), profile)

    private fun loadCacheKey(
        paths: List<Path>,
        profile: VersionProfile,
    ): LoadCacheKey = LoadCacheKey(profile.id, paths.map(::fingerprintPack))

    private fun fingerprintPack(input: Path): PackFingerprint {
        val normalized = input.toAbsolutePath().normalize()
        if (!normalized.exists()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Datapack path does not exist: $normalized")
        }
        if (normalized.isDirectory()) {
            val files = mutableListOf<FileFingerprint>()
            val realRoot = normalized.toRealPath()
            var entries = 0
            var expandedBytes = 0L
            Files.walk(normalized).use { walk ->
                walk.forEach { file ->
                    entries++
                    if (file.isRegularFile()) {
                        validateDirectoryEntry(normalized, realRoot, file)
                        expandedBytes = validatePackEntry(normalized, file.toString(), Files.size(file), entries, expandedBytes)
                        files +=
                            FileFingerprint(
                                relativePath = file.relativeTo(normalized).toString().replace('\\', '/'),
                                hash = sha256(file),
                            )
                    } else {
                        expandedBytes = validatePackEntry(normalized, file.toString(), null, entries, expandedBytes)
                    }
                }
            }
            return PackFingerprint(
                path = normalized.toString(),
                type = "directory",
                hash = "",
                files = files.sortedBy { it.relativePath },
            )
        }
        if (normalized.isRegularFile() && normalized.extension.lowercase() == "zip") {
            val archiveBytes = Files.size(normalized)
            if (archiveBytes > MAX_PACK_ARCHIVE_BYTES) {
                throw SandboxException(
                    DiagnosticCode.INPUT_FORMAT,
                    "Datapack archive '$normalized' exceeds the $MAX_PACK_ARCHIVE_BYTES-byte archive limit",
                )
            }
            validateZipContents(normalized)
            return PackFingerprint(
                path = normalized.toString(),
                type = "zip",
                hash = sha256(normalized),
            )
        }
        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Datapack path must be a directory or .zip file: $normalized")
    }

    private fun validateZipContents(file: Path) {
        try {
            ZipFile(file.toFile()).use { zip ->
                val entries = zip.entries()
                val paths = hashSetOf<String>()
                var entryCount = 0
                var expandedBytes = 0L
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val path = normalizedArchiveEntry(file, entry.name)
                    if (!paths.add(path)) {
                        throw SandboxException(
                            DiagnosticCode.INPUT_FORMAT,
                            "Datapack '$file' contains duplicate archive path '$path'",
                        )
                    }
                    entryCount++
                    expandedBytes =
                        validatePackEntry(file, entry.name, entry.size.takeUnless { entry.isDirectory }, entryCount, expandedBytes)
                }
            }
        } catch (error: SandboxException) {
            throw error
        } catch (error: Exception) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "Invalid datapack archive '$file': ${error.message}",
                cause = error,
            )
        }
    }

    private fun validateDirectoryEntry(
        pack: Path,
        realRoot: Path,
        file: Path,
    ) {
        val realFile = file.toRealPath()
        if (!realFile.startsWith(realRoot)) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "Datapack '$pack' entry '$file' resolves outside the datapack directory",
            )
        }
    }

    private fun normalizedArchiveEntry(
        pack: Path,
        name: String,
    ): String {
        fun reject(): Nothing =
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "Datapack '$pack' contains unsafe archive path '$name'",
            )

        val portable = name.replace('\\', '/').removeSuffix("/")
        if (portable.isEmpty() || portable.startsWith('/') || (portable.length >= 2 && portable[1] == ':')) reject()
        val parts = portable.split('/')
        if (parts.any { part -> part.isEmpty() || part == "." || part == ".." || part.any(Char::isISOControl) }) reject()
        return parts.joinToString("/")
    }

    private fun validatePackEntry(
        pack: Path,
        name: String,
        size: Long?,
        entryCount: Int,
        expandedBytes: Long,
    ): Long {
        fun reject(reason: String): Nothing =
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Datapack '$pack' $reason")

        if (entryCount > MAX_PACK_ENTRIES) reject("contains more than $MAX_PACK_ENTRIES entries")
        if (size == null) return expandedBytes
        if (size < 0L) reject("entry '$name' has an unknown expanded size")
        if (size > MAX_PACK_ENTRY_BYTES) reject("entry '$name' exceeds the $MAX_PACK_ENTRY_BYTES-byte single-entry limit")
        if (size > MAX_PACK_EXPANDED_BYTES - expandedBytes) {
            reject("expanded content exceeds the $MAX_PACK_EXPANDED_BYTES-byte limit")
        }
        return expandedBytes + size
    }

    private fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String {
        val digits = "0123456789abcdef".toCharArray()
        val chars = CharArray(size * 2)
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            chars[index * 2] = digits[value ushr 4]
            chars[index * 2 + 1] = digits[value and 0x0f]
        }
        return String(chars)
    }

    private fun Datapack.cacheCopy(): Datapack =
        copy(
            functions = functions.mapValues { (_, function) -> function.copy(lines = function.lines.toList()) }.toSortedMap(),
            loadFunctions = loadFunctions.toList(),
            tickFunctions = tickFunctions.toList(),
            lootTables = lootTables.mapValues { (_, table) -> table.copy(root = table.root.deepCopy().asJsonObject) }.toSortedMap(),
            predicates = predicates.mapValues { (_, predicate) -> predicate.copy(root = predicate.root.deepCopy()) }.toSortedMap(),
            advancements = advancements.mapValues { (_, advancement) -> advancement.cacheCopy() }.toSortedMap(),
            recipes = recipes.mapValues { (_, resource) -> resource.cacheCopy() }.toSortedMap(),
            itemModifiers = itemModifiers.mapValues { (_, resource) -> resource.cacheCopy() }.toSortedMap(),
            rawResources =
                rawResources
                    .mapValues { (_, resources) -> resources.mapValues { (_, resource) -> resource.cacheCopy() }.toSortedMap() }
                    .toSortedMap(),
            tags = tags.mapValues { (_, tag) -> tag.copy(values = tag.values.toList()) }.toSortedMap(),
            resourceIndex = resourceIndex.toList(),
            warnings = warnings.toList(),
        )

    private fun RawJsonResource.cacheCopy(): RawJsonResource = copy(root = root.deepCopy())

    private fun AdvancementDefinition.cacheCopy(): AdvancementDefinition =
        copy(
            root = root.deepCopy().asJsonObject,
            criteria =
                criteria
                    .mapValues { (_, criterion) ->
                        criterion.copy(conditions = criterion.conditions?.deepCopy()?.asJsonObject)
                    }.toSortedMap(),
            requirements = requirements.map { it.toList() },
            rewards = rewards.copy(loot = rewards.loot.toList(), recipes = rewards.recipes.toList()),
        )

    private fun withPackRoot(
        input: Path,
        block: (Path) -> Unit,
    ) {
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

    private fun validatePackMetadata(
        root: Path,
        originalInput: Path,
        profile: VersionProfile,
        warnings: MutableList<DatapackWarning>,
    ) {
        val meta = root.resolve("pack.mcmeta")
        if (!meta.exists()) {
            throw SandboxException(
                code = DiagnosticCode.INPUT_FORMAT,
                message = "Missing pack.mcmeta in datapack: ${originalInput.toAbsolutePath().normalize()}",
                version = profile.id,
            )
        }
        try {
            val element = JsonParser.parseString(readUtf8(meta))
            if (!element.isJsonObject || !element.asJsonObject.has("pack")) {
                throw IllegalArgumentException("pack.mcmeta must contain a top-level 'pack' object")
            }
            val pack =
                element.asJsonObject.getAsJsonObject("pack")
                    ?: throw IllegalArgumentException("pack.mcmeta must contain a top-level 'pack' object")
            val declaration = parsePackFormatDeclaration(pack)
            if (!declaration.matches(profile.dataPackFormat)) {
                warnings +=
                    DatapackWarning(
                        code = DiagnosticCode.VERSION_MISMATCH,
                        message = "Datapack format ${declaration.describe()} is not compatible with version ${profile.id}; expected ${profile.dataPackFormat}",
                        file = meta.toString(),
                        version = profile.id,
                    )
            }
        } catch (error: Exception) {
            if (error is SandboxException) throw error
            throw SandboxException(
                code = DiagnosticCode.INPUT_FORMAT,
                message = "Invalid pack.mcmeta: ${error.message}",
                location = SourceLocation(file = meta.toString()),
                version = profile.id,
                cause = error,
            )
        }
    }

    private fun parsePackFormatDeclaration(pack: JsonObject): PackFormatDeclaration {
        val exact = pack.get("pack_format")?.let(DataPackFormat.Companion::parse)
        val supported = pack.get("supported_formats")?.let(::parseSupportedFormats)
        val min = pack.get("min_format")?.let(DataPackFormat.Companion::parse)
        val max = pack.get("max_format")?.let(DataPackFormat.Companion::parse)
        if (exact == null && supported == null && min == null && max == null) {
            throw IllegalArgumentException("pack.mcmeta pack object must contain 'pack_format' or a supported format range")
        }
        val range = supported ?: if (min != null || max != null) FormatRange(min, max) else null
        return PackFormatDeclaration(exact, range)
    }

    private fun parseSupportedFormats(element: JsonElement): FormatRange =
        when {
            element.isJsonPrimitive -> {
                val exact = DataPackFormat.parse(element)
                FormatRange(exact, exact)
            }
            element.isJsonArray -> {
                val values = element.asJsonArray
                if (values.size() != 2) {
                    throw IllegalArgumentException("supported_formats array must contain [min, max]")
                }
                FormatRange(DataPackFormat.parse(values[0]), DataPackFormat.parse(values[1]))
            }
            element.isJsonObject -> {
                val obj = element.asJsonObject
                val range =
                    FormatRange(
                        min = (obj.get("min_format") ?: obj.get("min") ?: obj.get("min_inclusive"))?.let(DataPackFormat.Companion::parse),
                        max = (obj.get("max_format") ?: obj.get("max") ?: obj.get("max_inclusive"))?.let(DataPackFormat.Companion::parse),
                    )
                if (range.min == null && range.max == null) {
                    throw IllegalArgumentException("supported_formats object must declare a min or max format")
                }
                range
            }
            else -> throw IllegalArgumentException("supported_formats must be a number, [min, max], or object")
        }

    private data class PackFormatDeclaration(
        val exact: DataPackFormat?,
        val range: FormatRange?,
    ) {
        fun matches(expected: DataPackFormat): Boolean = exact?.matches(expected) == true || range?.contains(expected) == true

        fun describe(): String =
            listOfNotNull(
                exact?.let { "pack_format $it" },
                range?.takeIf { it.min != null || it.max != null }?.let { "range ${it.describe()}" },
            ).joinToString(" or ")
    }

    private data class FormatRange(
        val min: DataPackFormat?,
        val max: DataPackFormat?,
    ) {
        fun contains(value: DataPackFormat): Boolean = (min == null || value >= min) && (max == null || value <= max)

        fun describe(): String = "${min?.toString() ?: "-inf"}..${max?.toString() ?: "+inf"}"
    }

    private fun readFunctions(
        root: Path,
        profile: VersionProfile,
    ): Map<ResourceLocation, DatapackFunction> {
        val data = root.resolve("data")
        if (!data.exists()) return emptyMap()

        val functions = linkedMapOf<ResourceLocation, DatapackFunction>()
        Files.list(data).use { namespaces ->
            namespaces.filter { it.isDirectory() }.forEach { namespaceDir ->
                val namespace = namespaceDir.name
                profile.resourceDirectories.functions.forEach { functionDirName ->
                    val functionRoot = namespaceDir.resolve(functionDirName)
                    if (!functionRoot.exists()) return@forEach
                    Files.walk(functionRoot).use { walk ->
                        walk
                            .filter { it.isRegularFile() && it.name.endsWith(".mcfunction") }
                            .forEach { file ->
                                val relative = file.relativeTo(functionRoot).toString().replace('\\', '/')
                                val idPath = relative.removeSuffix(".mcfunction")
                                val id = ResourceLocation(namespace, idPath)
                                functions[id] =
                                    DatapackFunction(
                                        id,
                                        readFunctionLines(file.toString(), readUtf8Lines(file)),
                                    )
                            }
                    }
                }
            }
        }
        return functions
    }

    private fun readFunctionLines(
        source: FunctionSource,
        profile: VersionProfile,
    ): List<FunctionLine> {
        source.path?.let { path ->
            val normalized = path.toAbsolutePath().normalize()
            if (!normalized.exists()) {
                throw SandboxException(
                    code = DiagnosticCode.INPUT_FORMAT,
                    message = "mcfunction file does not exist: $normalized",
                    version = profile.id,
                )
            }
            if (!normalized.isRegularFile() || normalized.extension.lowercase() != "mcfunction") {
                throw SandboxException(
                    code = DiagnosticCode.INPUT_FORMAT,
                    message = "Function source must be a .mcfunction file: $normalized",
                    version = profile.id,
                )
            }
            return readFunctionLines(normalized.toString(), readUtf8Lines(normalized))
        }
        return readFunctionLines(source.sourceName, source.content.orEmpty().lines())
    }

    private fun readFunctionLines(
        sourceName: String,
        lines: List<String>,
    ): List<FunctionLine> {
        val result = mutableListOf<FunctionLine>()
        var continued: StringBuilder? = null
        var continuedFromLine = 0

        lines.forEachIndexed { index, raw ->
            val stripped = raw.removePrefix("\uFEFF").trim()
            if (continued == null && (stripped.isEmpty() || stripped.startsWith("#"))) return@forEachIndexed

            val continues = stripped.endsWith('\\')
            val fragment = if (continues) stripped.dropLast(1).trimEnd() else stripped
            val commandBuilder =
                continued ?: StringBuilder().also {
                    continued = it
                    continuedFromLine = index + 1
                }
            if (commandBuilder.isNotEmpty() && fragment.isNotEmpty()) commandBuilder.append(' ')
            commandBuilder.append(fragment)

            if (!continues) {
                val command = commandBuilder.toString().removePrefix("/")
                if (command.isNotEmpty()) {
                    result +=
                        FunctionLine(
                            command,
                            SourceLocation(file = sourceName, line = continuedFromLine, command = command),
                        )
                }
                continued = null
            }
        }

        continued?.toString()?.removePrefix("/")?.takeIf { it.isNotEmpty() }?.let { command ->
            result += FunctionLine(command, SourceLocation(file = sourceName, line = continuedFromLine, command = command))
        }
        return result
    }

    private fun readFunctionTag(
        root: Path,
        profile: VersionProfile,
        tagName: String,
    ): List<FunctionTagDefinition> {
        val tags = root.resolve("data").resolve("minecraft").resolve("tags")
        val candidates =
            profile.resourceDirectories.functionTags
                .map { tags.resolve(it).resolve("$tagName.json") }

        return candidates.filter { it.exists() }.map { tagPath ->
            val json =
                try {
                    val parsed = JsonParser.parseString(readUtf8(tagPath))
                    if (!parsed.isJsonObject) {
                        throw IllegalArgumentException("Function tag must be a JSON object")
                    }
                    parsed.asJsonObject
                } catch (error: Exception) {
                    throw SandboxException(
                        code = DiagnosticCode.INPUT_FORMAT,
                        message = "Invalid function tag $tagPath: ${error.message}",
                        location = SourceLocation(file = tagPath.toString()),
                        version = profile.id,
                        cause = error,
                    )
                }
            FunctionTagDefinition(
                id = ResourceLocation("minecraft", tagName),
                file = tagPath.toString(),
                replace = json.optionalBoolean("replace", "Function tag", tagPath.toString(), profile) ?: false,
                values = readFunctionTagValues(json, tagPath, profile),
            )
        }
    }

    private fun mergeFunctionTags(
        target: MutableList<FunctionTagEntry>,
        definitions: List<FunctionTagDefinition>,
    ) {
        definitions.forEach { definition ->
            if (definition.replace) target.clear()
            target += definition.values
        }
    }

    private fun effectiveFunctionTagFunctions(
        entries: List<FunctionTagEntry>,
        functions: Map<ResourceLocation, DatapackFunction>,
    ): List<ResourceLocation> = entries.filter { it.required || it.id in functions }.map { it.id }

    private fun readFunctionTagValues(
        json: JsonObject,
        tagPath: Path,
        profile: VersionProfile,
    ): List<FunctionTagEntry> {
        val values = json.get("values")
        if (values !is JsonArray) {
            throw SandboxException(
                code = DiagnosticCode.INPUT_FORMAT,
                message = "Function tag must contain a 'values' array",
                location = SourceLocation(file = tagPath.toString()),
                version = profile.id,
            )
        }

        return values.mapIndexed { index, entry ->
            val id: String
            val required: Boolean
            when {
                entry.isJsonPrimitive && entry.asJsonPrimitive.isString -> {
                    id = entry.asString
                    required = true
                }
                entry.isJsonObject -> {
                    val obj = entry.asJsonObject
                    id = obj.requiredString("id", "Function tag values[$index]", tagPath.toString(), profile)
                    required = obj.optionalBoolean("required", "Function tag values[$index]", tagPath.toString(), profile) ?: true
                }
                else -> throw SandboxException(
                    code = DiagnosticCode.INPUT_FORMAT,
                    message = "Function tag values[$index] must be a string or object",
                    location = SourceLocation(file = tagPath.toString()),
                    version = profile.id,
                )
            }
            FunctionTagEntry(parseResourceLocation(id, "Function tag values[$index]", tagPath.toString(), profile), required)
        }
    }

    private fun readLootTables(
        root: Path,
        profile: VersionProfile,
    ): Map<ResourceLocation, LootTable> =
        readJsonResources(root, profile, profile.resourceDirectories.lootTables, "loot table")
            .mapValues { (_, resource) ->
                val objectRoot = resource.root.asObjectOrError(resource, profile, "Loot table")
                LootTable(resource.id, resource.file, objectRoot)
            }

    private fun readPredicates(
        root: Path,
        profile: VersionProfile,
    ): Map<ResourceLocation, PredicateDefinition> =
        readJsonResources(root, profile, profile.resourceDirectories.predicates, "predicate")
            .mapValues { (_, resource) -> PredicateDefinition(resource.id, resource.file, resource.root) }

    private fun readAdvancements(
        root: Path,
        profile: VersionProfile,
    ): Map<ResourceLocation, AdvancementDefinition> =
        readJsonResources(root, profile, profile.resourceDirectories.advancements, "advancement")
            .mapValues { (_, resource) -> parseAdvancement(resource, profile) }

    private fun readRecipes(
        root: Path,
        profile: VersionProfile,
    ): Map<ResourceLocation, RawJsonResource> = readRawJsonResources(root, profile, "recipe", profile.resourceDirectories.recipes)

    private fun readItemModifiers(
        root: Path,
        profile: VersionProfile,
    ): Map<ResourceLocation, RawJsonResource> =
        readRawJsonResources(root, profile, "item_modifier", profile.resourceDirectories.itemModifiers, "item modifier")

    private fun readAdditionalRawJsonResources(
        root: Path,
        profile: VersionProfile,
    ): Map<String, Map<ResourceLocation, RawJsonResource>> {
        val resources = linkedMapOf<String, Map<ResourceLocation, RawJsonResource>>()
        additionalRawJsonResourceSpecs.forEach { spec ->
            val loaded = readRawJsonResources(root, profile, spec.kind, spec.directories, spec.errorLabel)
            if (loaded.isNotEmpty()) {
                resources[spec.kind] = loaded
            }
        }
        return resources
    }

    private fun readRawJsonResources(
        root: Path,
        profile: VersionProfile,
        kind: String,
        directoryNames: List<String>,
        errorLabel: String = kind.replace('/', ' ').replace('_', ' '),
    ): Map<ResourceLocation, RawJsonResource> =
        readJsonResources(root, profile, directoryNames, errorLabel)
            .mapValues { (_, resource) -> RawJsonResource(kind, resource.id, resource.file, resource.root, profile.id) }

    private fun readBinaryStructureResources(
        root: Path,
        profile: VersionProfile,
    ): Map<ResourceLocation, RawJsonResource> {
        val data = root.resolve("data")
        if (!data.exists()) return emptyMap()

        val resources = linkedMapOf<ResourceLocation, RawJsonResource>()
        val directories = listOf("worldgen/structure", "structure", "structures")
        Files.list(data).use { namespaces ->
            namespaces.filter { it.isDirectory() }.forEach { namespaceDir ->
                val namespace = namespaceDir.name
                directories.forEach { directoryName ->
                    val resourceRoot = namespaceDir.resolve(directoryName)
                    if (!resourceRoot.exists()) return@forEach
                    Files.walk(resourceRoot).use { walk ->
                        walk
                            .filter { it.isRegularFile() && it.name.lowercase().endsWith(".nbt") }
                            .forEach { file ->
                                val relative = file.relativeTo(resourceRoot).toString().replace('\\', '/')
                                val idPath = relative.substring(0, relative.length - ".nbt".length)
                                val id =
                                    try {
                                        ResourceLocation(namespace, idPath)
                                    } catch (error: IllegalArgumentException) {
                                        throw SandboxException(
                                            code = DiagnosticCode.INPUT_FORMAT,
                                            message = "Invalid binary structure resource id '$namespace:$idPath': ${error.message}",
                                            location = SourceLocation(file = file.toString()),
                                            version = profile.id,
                                            cause = error,
                                        )
                                    }
                                val rootJson =
                                    try {
                                        readNbtCompoundJson(file)
                                    } catch (error: Exception) {
                                        throw SandboxException(
                                            code = DiagnosticCode.INPUT_FORMAT,
                                            message = "Invalid binary structure NBT for '$id': ${error.message}",
                                            location = SourceLocation(file = file.toString()),
                                            version = profile.id,
                                            cause = error,
                                        )
                                    }
                                resources[id] =
                                    RawJsonResource(
                                        kind = "worldgen/structure",
                                        id = id,
                                        file = file.toString(),
                                        root = rootJson,
                                        version = profile.id,
                                        sourceFormat = "binary-structure-nbt",
                                    )
                            }
                    }
                }
            }
        }
        return resources
    }

    private fun readNbtCompoundJson(file: Path): JsonObject =
        Files.newInputStream(file).use { fileInput ->
            val input = BufferedInputStream(fileInput)
            input.mark(2)
            val gzip = input.read() == GZIP_MAGIC_FIRST && input.read() == GZIP_MAGIC_SECOND
            input.reset()
            val payload = if (gzip) GZIPInputStream(input) else input
            payload.use {
                val root = BinaryNbt.read(it).value
                if (!root.isJsonObject) {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "NBT root must be a compound")
                }
                root.asJsonObject
            }
        }

    private fun readTags(
        root: Path,
        profile: VersionProfile,
    ): Map<TagKey, TagDefinition> {
        val data = root.resolve("data")
        if (!data.exists()) return emptyMap()

        val tags = linkedMapOf<TagKey, TagDefinition>()
        Files.list(data).use { namespaces ->
            namespaces.filter { it.isDirectory() }.forEach { namespaceDir ->
                val namespace = namespaceDir.name
                val tagsRoot = namespaceDir.resolve("tags")
                if (!tagsRoot.exists()) return@forEach
                Files.walk(tagsRoot).use { walk ->
                    walk
                        .filter { it.isRegularFile() && it.name.endsWith(".json") }
                        .forEach { file ->
                            val relative = file.relativeTo(tagsRoot).toString().replace('\\', '/')
                            val registry = relative.substringBefore('/', missingDelimiterValue = "")
                            val idPath = relative.substringAfter('/', missingDelimiterValue = "").removeSuffix(".json")
                            if (registry in profile.resourceDirectories.functionTags &&
                                namespace == "minecraft" &&
                                idPath in setOf("load", "tick")
                            ) {
                                return@forEach
                            }
                            if (registry.isBlank() || idPath.isBlank()) {
                                throw SandboxException(
                                    code = DiagnosticCode.INPUT_FORMAT,
                                    message = "Tag resource must be under data/<namespace>/tags/<registry>/<path>.json",
                                    location = SourceLocation(file = file.toString()),
                                    version = profile.id,
                                )
                            }
                            val key = TagKey(registry, ResourceLocation(namespace, idPath))
                            val json =
                                try {
                                    JsonParser.parseString(readUtf8(file)).asJsonObject
                                } catch (error: Exception) {
                                    throw SandboxException(
                                        code = DiagnosticCode.INPUT_FORMAT,
                                        message = "Invalid tag JSON for '$key': ${error.message}",
                                        location = SourceLocation(file = file.toString()),
                                        version = profile.id,
                                        cause = error,
                                    )
                                }
                            tags[key] = parseTagDefinition(key, file.toString(), json, profile)
                        }
                }
            }
        }
        return tags
    }

    private fun parseTagDefinition(
        key: TagKey,
        file: String,
        json: JsonObject,
        profile: VersionProfile,
    ): TagDefinition {
        val values = json.get("values")
        if (values !is JsonArray) {
            throw SandboxException(
                code = DiagnosticCode.INPUT_FORMAT,
                message = "Tag '$key' must contain a 'values' array",
                location = SourceLocation(file = file),
                version = profile.id,
            )
        }
        return TagDefinition(
            key = key,
            file = file,
            replace = json.optionalBoolean("replace", "Tag '$key'", file, profile) ?: false,
            values = values.mapIndexed { index, entry -> parseTagValue(key, index, entry, file, profile) },
        )
    }

    private fun parseTagValue(
        key: TagKey,
        index: Int,
        entry: JsonElement,
        file: String,
        profile: VersionProfile,
    ): TagValue {
        val id: String
        val required: Boolean
        when {
            entry.isJsonPrimitive && entry.asJsonPrimitive.isString -> {
                id = entry.asString
                required = true
            }
            entry.isJsonObject -> {
                val obj = entry.asJsonObject
                id = obj.requiredString("id", "Tag '$key' values[$index]", file, profile)
                required = obj.optionalBoolean("required", "Tag '$key' values[$index]", file, profile) ?: true
            }
            else -> throw SandboxException(
                code = DiagnosticCode.INPUT_FORMAT,
                message = "Tag '$key' values[$index] must be a string or object",
                location = SourceLocation(file = file),
                version = profile.id,
            )
        }

        val parsedId = id.removePrefix("#")
        parseResourceLocation(parsedId, "Tag '$key' values[$index]", file, profile)
        return TagValue(id, required)
    }

    private fun mergeTags(
        target: MutableMap<TagKey, TagDefinition>,
        source: Map<TagKey, TagDefinition>,
    ) {
        source.forEach { (key, tag) ->
            val existing = target[key]
            target[key] =
                if (existing == null || tag.replace) {
                    tag
                } else {
                    tag.copy(values = existing.values + tag.values, replace = false)
                }
        }
    }

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
                        walk
                            .filter { it.isRegularFile() && it.name.endsWith(".json") }
                            .forEach { file ->
                                val relative = file.relativeTo(resourceRoot).toString().replace('\\', '/')
                                val idPath = relative.removeSuffix(".json")
                                val id =
                                    try {
                                        ResourceLocation(namespace, idPath)
                                    } catch (error: IllegalArgumentException) {
                                        throw SandboxException(
                                            code = DiagnosticCode.INPUT_FORMAT,
                                            message = "Invalid $kind resource id '$namespace:$idPath': ${error.message}",
                                            location = SourceLocation(file = file.toString()),
                                            version = profile.id,
                                            cause = error,
                                        )
                                    }
                                val element =
                                    try {
                                        JsonParser.parseString(readUtf8(file))
                                    } catch (error: Exception) {
                                        throw SandboxException(
                                            code = DiagnosticCode.INPUT_FORMAT,
                                            message = "Invalid $kind JSON for '$id': ${error.message}",
                                            location = SourceLocation(file = file.toString()),
                                            version = profile.id,
                                            cause = error,
                                        )
                                    }
                                resources[id] = ResourceJson(id, file.toString(), element, kind)
                            }
                    }
                }
            }
        }
        return resources
    }

    private fun readUtf8Lines(file: Path): List<String> = readUtf8(file).lineSequence().toList()

    private fun readUtf8(file: Path): String {
        val declaredSize = Files.size(file)
        if (declaredSize > MAX_TEXT_RESOURCE_BYTES) throw textResourceLimit(file)
        return Files.newInputStream(file).use { input ->
            ByteArrayOutputStream(declaredSize.toInt()).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var consumed = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count.toLong() > MAX_TEXT_RESOURCE_BYTES - consumed) throw textResourceLimit(file)
                    output.write(buffer, 0, count)
                    consumed += count
                }
                try {
                    // String(byte[], UTF_8) replaces malformed input. Resource files must
                    // instead fail deterministically, matching Files.readString semantics.
                    StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(output.toByteArray()))
                        .toString()
                } catch (error: CharacterCodingException) {
                    throw SandboxException(
                        DiagnosticCode.INPUT_FORMAT,
                        "Datapack text resource '$file' is not valid UTF-8",
                        cause = error,
                    )
                }
            }
        }
    }

    private fun textResourceLimit(file: Path): SandboxException =
        SandboxException(
            DiagnosticCode.INPUT_FORMAT,
            "Datapack text resource '$file' exceeds the $MAX_TEXT_RESOURCE_BYTES-byte limit",
        )

    private fun parseAdvancement(
        resource: ResourceJson,
        profile: VersionProfile,
    ): AdvancementDefinition {
        val root = resource.root.asObjectOrError(resource, profile, "Advancement")
        val parent = root.optionalString("parent")?.let { ResourceLocation.parse(it) }
        val criteriaObject =
            root.getAsJsonObject("criteria")
                ?: throw resourceError(resource, profile, "Advancement must contain a criteria object")

        val criteria =
            criteriaObject
                .entrySet()
                .associate { (name, value) ->
                    if (!value.isJsonObject) {
                        throw resourceError(resource, profile, "Criterion '$name' must be an object")
                    }
                    val criterion = value.asJsonObject
                    val trigger =
                        criterion.optionalString("trigger")
                            ?: throw resourceError(resource, profile, "Criterion '$name' is missing trigger")
                    name to
                        Criterion(
                            name = name,
                            trigger = ResourceLocation.parse(trigger),
                            conditions = criterion.getAsJsonObject("conditions"),
                        )
                }.toSortedMap()

        val requirements =
            when (val requirementsJson = root.get("requirements")) {
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

        val rewards =
            root.getAsJsonObject("rewards")?.let { rewards ->
                AdvancementReward(
                    experience = rewards.get("experience")?.asInt ?: 0,
                    loot = rewards.arrayStrings("loot").map { ResourceLocation.parse(it) },
                    recipes = rewards.arrayStrings("recipes").map { ResourceLocation.parse(it) },
                    function = rewards.optionalString("function")?.let { ResourceLocation.parse(it) },
                )
            } ?: AdvancementReward()

        return AdvancementDefinition(resource.id, resource.file, root, parent, criteria, requirements, rewards)
    }

    private fun JsonElement.asObjectOrError(
        resource: ResourceJson,
        profile: VersionProfile,
        kind: String,
    ): JsonObject {
        if (!isJsonObject) {
            throw resourceError(resource, profile, "$kind must be a JSON object")
        }
        return asJsonObject
    }

    private fun JsonObject.optionalString(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.requiredString(
        name: String,
        label: String,
        file: String,
        profile: VersionProfile,
    ): String {
        val value = get(name)
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
            throw SandboxException(
                code = DiagnosticCode.INPUT_FORMAT,
                message = "$label object must contain string '$name'",
                location = SourceLocation(file = file),
                version = profile.id,
            )
        }
        return value.asString
    }

    private fun JsonObject.optionalBoolean(
        name: String,
        label: String,
        file: String,
        profile: VersionProfile,
    ): Boolean? {
        val value = get(name) ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) {
            throw SandboxException(
                code = DiagnosticCode.INPUT_FORMAT,
                message = "$label '$name' must be a boolean",
                location = SourceLocation(file = file),
                version = profile.id,
            )
        }
        return value.asBoolean
    }

    private fun parseResourceLocation(
        value: String,
        label: String,
        file: String,
        profile: VersionProfile,
    ): ResourceLocation =
        try {
            ResourceLocation.parse(value)
        } catch (error: Exception) {
            throw SandboxException(
                code = DiagnosticCode.INPUT_FORMAT,
                message = "$label has invalid resource location '$value': ${error.message}",
                location = SourceLocation(file = file),
                version = profile.id,
                cause = error,
            )
        }

    private fun JsonObject.arrayStrings(name: String): List<String> {
        val value = get(name) ?: return emptyList()
        if (!value.isJsonArray) return emptyList()
        return value.asJsonArray.map { it.asString }
    }

    private fun resourceError(
        resource: ResourceJson,
        profile: VersionProfile,
        message: String,
    ): SandboxException =
        SandboxException(
            code = DiagnosticCode.INPUT_FORMAT,
            message = "Invalid ${resource.kind} resource '${resource.id}': $message",
            location = SourceLocation(file = resource.file),
            version = profile.id,
        )

    private const val GZIP_MAGIC_FIRST = 0x1f
    private const val GZIP_MAGIC_SECOND = 0x8b

    private fun Map<String, Map<ResourceLocation, RawJsonResource>>.toSortedRawResourceMap(): Map<String, Map<ResourceLocation, RawJsonResource>> =
        toSortedMap().mapValues { (_, resources) -> resources.toSortedMap() }

    private class ResourceIndexBuilder {
        private val entries = mutableListOf<ResourceIndexEntry>()
        private val activeByKey = linkedMapOf<Pair<String, ResourceLocation>, MutableList<Int>>()

        fun recordAll(
            type: String,
            resources: Map<ResourceLocation, *>,
            pack: String,
        ) {
            resources.toSortedMap().forEach { (id, resource) ->
                record(
                    type = type,
                    id = id,
                    file = resourceFile(resource) ?: "<unknown:$type/$id>",
                    pack = pack,
                )
            }
        }

        fun record(
            type: String,
            id: ResourceLocation,
            file: String,
            pack: String,
            overridesPrevious: Boolean = true,
        ) {
            val key = type to id
            val previous = if (overridesPrevious) activeByKey[key].orEmpty().toList() else emptyList()
            previous.forEach { index ->
                entries[index] = entries[index].copy(active = false, overriddenBy = file)
            }
            val nextIndex = entries.size
            entries +=
                ResourceIndexEntry(
                    type = type,
                    id = id,
                    file = file,
                    pack = pack,
                    order = nextIndex,
                    active = true,
                    overrides = previous.takeIf { it.isNotEmpty() }?.joinToString(";") { entries[it].file },
                )
            if (overridesPrevious) {
                activeByKey[key] = mutableListOf(nextIndex)
            } else {
                activeByKey.getOrPut(key) { mutableListOf() } += nextIndex
            }
        }

        fun entries(): List<ResourceIndexEntry> =
            entries.sortedWith(compareBy<ResourceIndexEntry> { it.type }.thenBy { it.id.toString() }.thenBy { it.order })

        private fun resourceFile(resource: Any?): String? =
            when (resource) {
                is DatapackFunction ->
                    resource.lines
                        .firstOrNull()
                        ?.location
                        ?.file ?: "<function:${resource.id}>"
                is LootTable -> resource.file
                is PredicateDefinition -> resource.file
                is AdvancementDefinition -> resource.file
                is RawJsonResource -> resource.file
                is ResourceJson -> resource.file
                else -> null
            }
    }
}
