package moe.afox.dpsandbox.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.JsonValues
import moe.afox.dpsandbox.core.ResourceCatalog
import moe.afox.dpsandbox.core.ResourceCatalogEntry
import moe.afox.dpsandbox.core.ResourceIndexEntry
import moe.afox.dpsandbox.core.ResourceLocation
import moe.afox.dpsandbox.core.SandboxException
import moe.afox.dpsandbox.core.VersionProfiles
import moe.afox.dpsandbox.core.createSandbox
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

class ResourcesCommand : CliktCommand(name = "resources") {
    private val version by option("--version", "-v").default(VersionProfiles.default.id)
    private val packs by option("--pack", "-p").path(mustExist = true).multiple()
    private val types by option("--type").multiple()
    private val ids by option("--id").multiple()
    private val namespaces by option("--namespace").multiple()
    private val sourcePacks by option("--source-pack").multiple()
    private val orderMin by option("--order-min").int()
    private val orderMax by option("--order-max").int()
    private val activeOnly by option("--active-only").flag(default = false)
    private val overriddenOnly by option("--overridden-only").flag(default = false)
    private val registry by option("--registry").flag(default = false)
    private val registryGroup by option("--registry-group")
    private val docs by option("--docs").flag(default = false)
    private val json by option("--json").flag(default = false)
    private val output by option("--output", "-o").path()
    private val check by option("--check").path()
    private val docsLocale by option("--locale").default("en")

    override fun run() {
        try {
            validateModes()
            val locale = normalizedDocsLocale(docsLocale)
            if (registry) {
                emitRegistryResources()
                return
            }
            if (packs.isNotEmpty()) {
                emitLoadedResources()
                return
            }
            validateCatalogFilters()
            val entries = filterCatalog(ResourceCatalog.all)
            val checkPath = check
            when {
                checkPath != null -> checkResourceDocs(checkPath, ResourceCatalog.all, locale)
                docs -> emit(renderMarkdown(entries, locale))
                json -> emit(JsonValues.render(renderJson(entries)))
                else -> emit(renderPlain(entries))
            }
        } catch (error: SandboxException) {
            println(ConsoleStyle.diagnostic(error.render()))
            exitProcess(ExitCodes.forException(error))
        }
    }

    private fun validateModes() {
        if (docs && json) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "resources accepts only one output mode: --docs or --json")
        }
        if (check != null && output != null) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "resources accepts only one file mode: --output or --check")
        }
        if (activeOnly && overriddenOnly) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "resources accepts only one state filter: --active-only or --overridden-only",
            )
        }
        if (orderMin != null && orderMin!! < 0) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "resources --order-min must be >= 0")
        }
        if (orderMax != null && orderMax!! < 0) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "resources --order-max must be >= 0")
        }
        if (orderMin != null && orderMax != null && orderMin!! > orderMax!!) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "resources --order-min cannot be greater than --order-max")
        }
        if (packs.isNotEmpty() && docs) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "resources --docs describes the catalog and cannot be combined with --pack")
        }
        if (packs.isNotEmpty() && check != null) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "resources --check validates docs and cannot be combined with --pack")
        }
        if (check != null && hasFilters()) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "resources --check always validates the full catalog and accepts no filters",
            )
        }
        if (!docs && check == null && docsLocale != "en") {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "resources --locale is only supported with --docs or --check")
        }
        if (registry && (packs.isNotEmpty() || docs || check != null)) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "resources --registry cannot be combined with --pack, --docs, or --check")
        }
        if (!registry && registryGroup != null) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "resources --registry-group requires --registry")
        }
        if (registry && hasResourceIndexFilters()) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "resources --registry cannot be combined with resource-index filters: --id, --namespace, --source-pack, --order-min, --order-max, --active-only, --overridden-only",
            )
        }
        if (registry && types.isNotEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "resources --registry uses --registry-group instead of --type")
        }
    }

    private fun validateCatalogFilters() {
        if (hasResourceIndexFilters()) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "resources resource-index filters require --pack: --id, --namespace, --source-pack, --order-min, --order-max, --active-only, --overridden-only",
            )
        }
    }

    private fun emitLoadedResources() {
        val sandbox = createSandbox(version, packs)
        val summary = ManifestRunner.summarizeResources(sandbox)
        val entries = filterLoadedResources(sandbox.datapack.resourceIndex)
        val rendered =
            if (json) {
                JsonValues.render(renderLoadedJson(summary, entries))
            } else {
                renderLoadedPlain(summary, entries)
            }
        emit(rendered)
    }

    private fun emitRegistryResources() {
        val profile = VersionProfiles.get(version)
        val normalizedGroup = registryGroup?.replace('-', '_')
        val groups = RegistryInspection.select(profile, normalizedGroup)
        if (normalizedGroup != null && groups.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "resources registry group '$normalizedGroup' was not found")
        }
        val source = "profile:${profile.id}"
        val rendered =
            if (json) {
                JsonValues.render(renderRegistryJson(profile.id, source, groups, normalizedGroup))
            } else {
                renderRegistryPlain(profile.id, source, groups)
            }
        emit(rendered)
    }

    private fun filterCatalog(entries: List<ResourceCatalogEntry>): List<ResourceCatalogEntry> {
        val typeSet = types.toSet()
        return entries.filter { entry -> typeSet.isEmpty() || entry.type in typeSet }
    }

    private fun filterLoadedResources(entries: List<ResourceIndexEntry>): List<ResourceIndexEntry> {
        val typeSet = types.toSet()
        val idSet = ids.map { ResourceLocation.parse(it) }.toSet()
        val namespaceSet = namespaces.toSet()
        val sourcePackSet = sourcePackFilters()
        return entries.filter { entry ->
            (typeSet.isEmpty() || entry.type in typeSet) &&
                (idSet.isEmpty() || entry.id in idSet) &&
                (namespaceSet.isEmpty() || entry.id.namespace in namespaceSet) &&
                (sourcePackSet.isEmpty() || entry.pack in sourcePackSet) &&
                (orderMin == null || entry.order >= orderMin!!) &&
                (orderMax == null || entry.order <= orderMax!!) &&
                (!activeOnly || entry.active) &&
                (!overriddenOnly || !entry.active)
        }
    }

    private fun sourcePackFilters(): Set<String> =
        sourcePacks
            .flatMap { value ->
                listOf(
                    value,
                    Path
                        .of(value)
                        .toAbsolutePath()
                        .normalize()
                        .toString(),
                )
            }.toSet()

    private fun hasFilters(): Boolean =
        types.isNotEmpty() ||
            ids.isNotEmpty() ||
            namespaces.isNotEmpty() ||
            sourcePacks.isNotEmpty() ||
            orderMin != null ||
            orderMax != null ||
            activeOnly ||
            overriddenOnly

    private fun hasResourceIndexFilters(): Boolean =
        ids.isNotEmpty() ||
            namespaces.isNotEmpty() ||
            sourcePacks.isNotEmpty() ||
            orderMin != null ||
            orderMax != null ||
            activeOnly ||
            overriddenOnly

    private fun renderLoadedPlain(
        summary: ManifestResourceSummary,
        entries: List<ResourceIndexEntry>,
    ): String =
        buildString {
            appendLine(
                "resources version=$version " +
                    "packs=${packs.size} " +
                    "resourceIndex=${summary.resourceIndex} " +
                    "active=${summary.activeResources} " +
                    "overridden=${summary.overriddenResources} " +
                    "selected=${entries.size}",
            )
            entries.forEach { entry ->
                appendLine(renderLoadedEntry(entry))
            }
        }.trimEnd()

    private fun renderLoadedEntry(entry: ResourceIndexEntry): String {
        val state = if (entry.active) "active" else "overridden"
        val overlay =
            listOfNotNull(
                entry.overrides?.let { "overrides=$it" },
                entry.overriddenBy?.let { "overriddenBy=$it" },
            ).joinToString(separator = " ").takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        return "resource ${entry.type} ${entry.id} ${entry.behaviorLevel.id} $state " +
            "pack=${entry.pack} order=${entry.order} file=${entry.file}$overlay"
    }

    private fun renderLoadedJson(
        summary: ManifestResourceSummary,
        entries: List<ResourceIndexEntry>,
    ): JsonObject =
        JsonObject().also { root ->
            root.addProperty("version", version)
            root.add("packs", stringArray(packs.map { it.toString() }))
            root.add("filters", renderFiltersJson())
            root.add("summary", summary.toReportJson())
            root.add(
                "resources",
                JsonArray().also { array ->
                    entries.forEach { entry ->
                        array.add(
                            JsonObject().also { json ->
                                json.addProperty("type", entry.type)
                                json.addProperty("id", entry.id.toString())
                                json.addProperty("namespace", entry.id.namespace)
                                json.addProperty("path", entry.id.path)
                                json.addProperty("file", entry.file)
                                json.addProperty("pack", entry.pack)
                                json.addProperty("order", entry.order)
                                json.addProperty("behavior", entry.behaviorLevel.id)
                                json.addProperty("active", entry.active)
                                entry.overrides?.let { json.addProperty("overrides", it) }
                                entry.overriddenBy?.let { json.addProperty("overriddenBy", it) }
                            },
                        )
                    }
                },
            )
        }

    private fun renderFiltersJson(): JsonObject =
        JsonObject().also { json ->
            json.add("types", stringArray(types))
            json.add("ids", stringArray(ids))
            json.add("namespaces", stringArray(namespaces))
            json.add("sourcePacks", stringArray(sourcePacks))
            orderMin?.let { json.addProperty("orderMin", it) }
            orderMax?.let { json.addProperty("orderMax", it) }
            json.addProperty("activeOnly", activeOnly)
            json.addProperty("overriddenOnly", overriddenOnly)
        }

    private fun renderRegistryPlain(
        version: String,
        source: String,
        groups: List<RegistryInspectionGroup>,
    ): String =
        buildString {
            appendLine("registry version=$version groups=${RegistryInspection.groupNames.size} selected=${groups.size} source=$source")
            groups.forEach { group ->
                appendLine("registry ${group.name} count=${group.entries.size} source=$source")
                group.entries.forEach { entry ->
                    appendLine("registry ${group.name} $entry source=$source")
                }
            }
        }.trimEnd()

    private fun renderRegistryJson(
        version: String,
        source: String,
        groups: List<RegistryInspectionGroup>,
        groupFilter: String?,
    ): JsonObject =
        JsonObject().also { root ->
            root.addProperty("version", version)
            root.addProperty("source", source)
            root.add(
                "filters",
                JsonObject().also { filters ->
                    groupFilter?.let { filters.addProperty("registryGroup", it) }
                },
            )
            root.add(
                "registries",
                JsonArray().also { array ->
                    groups.forEach { group ->
                        array.add(
                            JsonObject().also { json ->
                                json.addProperty("group", group.name)
                                json.addProperty("count", group.entries.size)
                                json.addProperty("source", source)
                                json.add("entries", stringArray(group.entries.map { it.toString() }))
                            },
                        )
                    }
                },
            )
        }

    private fun renderPlain(entries: List<ResourceCatalogEntry>): String =
        entries.joinToString(System.lineSeparator()) { entry ->
            "${entry.type} ${entry.behaviorLevel.id} - ${entry.summary}"
        }

    private fun renderMarkdown(
        entries: List<ResourceCatalogEntry>,
        locale: String,
    ): String {
        val zh = locale.isZhCnDocsLocale()
        val resourceHeader = if (zh) "资源" else "Resource"
        val behaviorHeader = if (zh) "行为等级" else "Behavior"
        val surfaceHeader = if (zh) "运行时 / debug 表面" else "Runtime/debug surface"
        return buildString {
            appendLine("| $resourceHeader | $behaviorHeader | $surfaceHeader |")
            appendLine("|---|---|---|")
            entries.forEach { entry ->
                appendLine(
                    "| `${markdownCell(entry.type)}` | `${entry.behaviorLevel.id}` | ${markdownCell(resourceSummary(entry, locale))} |",
                )
            }
        }.trimEnd()
    }

    private fun resourceSummary(
        entry: ResourceCatalogEntry,
        locale: String,
    ): String {
        if (!locale.isZhCnDocsLocale()) return entry.summary
        return when (entry.type) {
            "function" -> "mcfunction 执行、trace source location 和缺失引用检查。"
            "tag/function" -> "load/tick/function tag 执行和 `replace` 语义。"
            "loot_table" -> "支持上下文内的确定性 loot 生成和命令输出。"
            "predicate" -> "predicate 命令/API、advancement 条件、loot 条件和 item modifier。"
            "advancement" -> "玩家 progress、criteria 匹配、rewards、output 和事件 trace。"
            "recipe" -> "进入资源索引和玩家 recipe 状态，供命令与 rewards 使用。"
            "item_modifier" -> "`item modify` 会应用常用 item modifier 函数。"
            "banner_pattern" -> "item 输出会暴露 banner pattern JSON 元数据。"
            "cat_variant", "chicken_variant", "cow_variant", "frog_variant", "painting_variant", "pig_variant", "wolf_variant" ->
                "summon 命令会暴露实体 variant JSON 元数据。"
            "wolf_sound_variant" -> "summon wolf 会暴露 wolf sound variant JSON 元数据。"
            "instrument" -> "item 输出会暴露 instrument JSON 元数据。"
            "jukebox_song" -> "item 输出会暴露 jukebox song JSON 元数据。"
            "trim_material" -> "item 输出会暴露 armor trim material JSON 元数据。"
            "trim_pattern" -> "item 输出会暴露 armor trim pattern JSON 元数据。"
            "chat_type" -> "聊天命令会暴露 chat type JSON 元数据。"
            "damage_type" -> "damage 命令会暴露 damage type JSON 元数据。"
            "dimension" -> "维度感知命令输出会暴露 dimension JSON 元数据。"
            "dimension_type" -> "dimension 资源会暴露关联的 dimension type JSON 元数据。"
            "enchantment" -> "enchant 命令会暴露 enchantment JSON 元数据。"
            "equipment_asset" -> "item 输出会暴露 equipment asset JSON 元数据。"
            "tag/<registry>" -> "普通 tag 保留 `replace` 语义，并进入资源索引供 inspect。"
            "worldgen/configured_feature" -> "simple_block、block_column、disk、vegetation_patch、tree、basalt_columns、delta_feature、lake、spring_feature、block_pile、glowstone_blob、forest_rock、netherrack_replace_blobs、chorus_plant、replace_single_block、replace_blob、selector、random_patch、flower 和 ore feature JSON 可被 place feature 消费。"
            "worldgen/placed_feature" -> "placed feature 会解析 configured simple_block/block_column/disk/vegetation_patch/tree/basalt_columns/delta_feature/lake/spring_feature/block_pile/glowstone_blob/forest_rock/netherrack_replace_blobs/chorus_plant/replace_single_block/replace_blob/selector/random_patch/flower/ore 资源，供 place feature 使用。"
            "worldgen/processor_list" -> "block_ignore、protected_blocks、jigsaw_replacement、capped、nop 和带 block/tag 谓词的 rule processor 可被沙盒结构放置消费。"
            "worldgen/structure" -> "沙盒结构 JSON 的 blocks/entities 与 palette-style blocks 可被 place structure/template 展开。"
            "worldgen/template_pool" -> "single/legacy/list/feature pool element、fallback pool 和确定性 jigsaw connector 可被 place jigsaw 展开。"
            else -> "经版本校验的 raw JSON 资源，进入索引供 inspect。"
        }
    }

    private fun renderJson(entries: List<ResourceCatalogEntry>): JsonObject =
        JsonObject().also { root ->
            root.add(
                "resources",
                JsonArray().also { array ->
                    entries.forEach { entry ->
                        array.add(
                            JsonObject().also { json ->
                                json.addProperty("type", entry.type)
                                json.addProperty("behavior", entry.behaviorLevel.id)
                                json.addProperty("summary", entry.summary)
                            },
                        )
                    }
                },
            )
        }

    private fun markdownCell(value: String): String = value.replace("|", "\\|").replace("\r", " ").replace("\n", " ")

    private fun emit(content: String) {
        val outputPath = output
        if (outputPath == null) {
            println(content)
        } else {
            outputPath.parent?.let(Files::createDirectories)
            Files.writeString(outputPath, content, StandardCharsets.UTF_8)
            println(ConsoleStyle.green("resources output written: $outputPath"))
        }
    }

    private fun checkResourceDocs(
        path: Path,
        entries: List<ResourceCatalogEntry>,
        locale: String,
    ) {
        if (!Files.exists(path)) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "resources check file does not exist: $path")
        }
        val docLines = normalizeDoc(Files.readString(path, StandardCharsets.UTF_8)).lineSequence().toList()
        val missing =
            entries.mapNotNull { entry ->
                val resourcePattern = Regex("""`[^`]*${Regex.escape(entry.type)}[^`]*`""")
                val covered = docLines.any { line -> resourcePattern.containsMatchIn(line) && "`${entry.behaviorLevel.id}`" in line }
                if (covered) null else "${entry.type} (${entry.behaviorLevel.id})"
            }
        if (missing.isNotEmpty()) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "resources docs are out of date: $path; missing ${missing.joinToString()}; regenerate with resources --docs ${localeOption(
                    locale,
                )}--output <file>",
            )
        }
        println(ConsoleStyle.green("resources docs cover catalog: $path"))
    }

    private fun normalizeDoc(value: String): String = value.replace("\r\n", "\n").replace('\r', '\n')

    private fun normalizedDocsLocale(value: String): String =
        when (value.lowercase().replace('_', '-')) {
            "en", "en-us" -> "en"
            "zh", "zh-cn", "zh-hans", "zh-hans-cn" -> "zh-CN"
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Unsupported resources docs locale '$value'; expected en or zh-CN")
        }

    private fun localeOption(locale: String): String = if (locale == "en") "" else "--locale $locale "

    private fun String.isZhCnDocsLocale(): Boolean = lowercase().replace('_', '-') in setOf("zh", "zh-cn", "zh-hans", "zh-hans-cn")
}
