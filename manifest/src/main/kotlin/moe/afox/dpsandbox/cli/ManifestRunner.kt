package moe.afox.dpsandbox.cli

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import moe.afox.dpsandbox.core.BlockPos
import moe.afox.dpsandbox.core.CommandTraceEvent
import moe.afox.dpsandbox.core.DatapackMissingResourceReference
import moe.afox.dpsandbox.core.DatapackResourceSummary
import moe.afox.dpsandbox.core.DatapackSandbox
import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.ItemStack
import moe.afox.dpsandbox.core.JsonPaths
import moe.afox.dpsandbox.core.JsonValues
import moe.afox.dpsandbox.core.OutputEvent
import moe.afox.dpsandbox.core.PlayerEvent
import moe.afox.dpsandbox.core.PlayerEventTraceEvent
import moe.afox.dpsandbox.core.PlayerInput
import moe.afox.dpsandbox.core.ResourceLocation
import moe.afox.dpsandbox.core.SandboxBlock
import moe.afox.dpsandbox.core.SandboxEntity
import moe.afox.dpsandbox.core.SandboxException
import moe.afox.dpsandbox.core.SandboxLimits
import moe.afox.dpsandbox.core.SandboxWorld
import moe.afox.dpsandbox.core.SnapshotDiff
import moe.afox.dpsandbox.core.SnapshotDiffEntry
import moe.afox.dpsandbox.core.SourceLocation
import moe.afox.dpsandbox.core.UnsupportedFeatureMode
import moe.afox.dpsandbox.core.VersionProfiles
import moe.afox.dpsandbox.core.createSandbox
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

typealias ManifestResourceSummary = DatapackResourceSummary
typealias ManifestMissingResourceReference = DatapackMissingResourceReference

data class ManifestResult(
    val path: Path,
    val passed: Boolean,
    val messages: List<String>,
    val outputs: List<OutputEvent> = emptyList(),
    val traces: List<CommandTraceEvent> = emptyList(),
    val eventTraces: List<PlayerEventTraceEvent> = emptyList(),
    val attempts: List<ManifestAttemptResult> = emptyList(),
)

data class ManifestAttemptResult(
    val version: String,
    val packs: List<Path>,
    val passed: Boolean,
    val messages: List<String>,
    val outputs: List<OutputEvent> = emptyList(),
    val traces: List<CommandTraceEvent> = emptyList(),
    val eventTraces: List<PlayerEventTraceEvent> = emptyList(),
    val snapshot: JsonObject? = null,
    val snapshotDiffs: List<SnapshotDiffEntry> = emptyList(),
    val resourceSummary: ManifestResourceSummary? = null,
)

data class ExistingSandboxManifestResult(
    val result: ManifestResult,
    val sandbox: DatapackSandbox,
)

data class ManifestOptions(
    val seed: Long = 0,
    val verbose: Boolean = false,
    val snapshotOnFail: Boolean = false,
    val snapshotDiffOnFail: Boolean = false,
    val failOnMissingResources: Boolean = false,
    val unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
    val limits: SandboxLimits = SandboxLimits(),
)

internal data class ManifestDiagnostic(
    val step: Int?,
    val version: String,
    val code: DiagnosticCode,
    val message: String,
    val command: String?,
    val root: String?,
)

object ManifestRunner {
    private data class ManifestRunConfig(
        val version: String,
        val packs: List<Path>,
    )

    private data class ManifestSection(
        val element: JsonElement,
        val base: Path,
        val source: Path,
        val pointer: String,
    )

    private data class ResolvedManifest(
        val path: Path,
        val base: Path,
        val root: JsonObject,
        val worlds: List<ManifestSection>,
        val steps: List<ManifestSection>,
        val assertions: List<ManifestSection>,
    )

    fun discover(inputs: List<Path>): List<Path> {
        val manifests = mutableListOf<Path>()
        inputs.forEach { input ->
            val path = input.toAbsolutePath().normalize()
            when {
                path.isRegularFile() -> manifests.add(path)
                path.isDirectory() ->
                    Files.walk(path).use { walk ->
                        walk
                            .filter { it.isRegularFile() && it.name.endsWith(".dps.json") }
                            .sorted()
                            .forEach { manifests.add(it.toAbsolutePath().normalize()) }
                    }
                else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Input is not a file or directory: $path")
            }
        }
        if (manifests.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "No .dps.json manifests found")
        }
        return manifests
    }

    fun run(
        path: Path,
        options: ManifestOptions = ManifestOptions(),
    ): ManifestResult {
        val json =
            try {
                JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).asJsonObject
            } catch (error: Exception) {
                throw SandboxException(
                    DiagnosticCode.INPUT_FORMAT,
                    "Invalid manifest JSON: ${path.toAbsolutePath().normalize()}",
                    cause = error,
                )
            }

        val document = resolveManifest(path, json)
        val configs = runConfigs(document.root, document.base, document.path)
        val unsupportedMode = document.root.manifestString("unsupported")?.let(::unsupportedFeatureMode) ?: options.unsupportedFeatureMode
        val effectiveOptions =
            options.copy(
                seed = document.root.get("seed")?.asLong ?: options.seed,
                failOnMissingResources = document.root.get("failOnMissingResources")?.asBoolean ?: options.failOnMissingResources,
            )
        val attempts =
            configs.map { config ->
                runOne(document, config, unsupportedMode, effectiveOptions)
            }

        val multiVersion = attempts.size > 1
        val messages =
            attempts.flatMap { attempt ->
                attempt.messages.map { message ->
                    if (multiVersion) "[${attempt.version}] $message" else message
                }
            }
        return ManifestResult(
            path = path,
            passed = attempts.all { it.passed },
            messages = messages,
            outputs = attempts.flatMap { it.outputs },
            traces = attempts.flatMap { it.traces },
            eventTraces = attempts.flatMap { it.eventTraces },
            attempts = attempts,
        )
    }

    fun runInExistingSandbox(
        path: Path,
        initialSandbox: DatapackSandbox,
        options: ManifestOptions = ManifestOptions(),
    ): ExistingSandboxManifestResult {
        val json =
            try {
                JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).asJsonObject
            } catch (error: Exception) {
                throw SandboxException(
                    DiagnosticCode.INPUT_FORMAT,
                    "Invalid manifest JSON: ${path.toAbsolutePath().normalize()}",
                    cause = error,
                )
            }
        val document = resolveManifest(path, json)
        val effectiveOptions =
            options.copy(
                seed = document.root.get("seed")?.asLong ?: initialSandbox.world.seed,
                failOnMissingResources = document.root.get("failOnMissingResources")?.asBoolean ?: options.failOnMissingResources,
            )
        var sandbox = initialSandbox
        sandbox.world.seed = effectiveOptions.seed
        val failures = mutableListOf<String>()
        val diagnostics = mutableListOf<ManifestDiagnostic>()

        document.worlds.forEach { world ->
            if (!world.element.isJsonObject) {
                throw SandboxException(
                    DiagnosticCode.INPUT_FORMAT,
                    "Manifest world must be an object: ${document.path}",
                )
            }
            ManifestWorldSetup.apply(world.element.asJsonObject, sandbox, world.base)
        }
        val initialWorld = sandbox.world
        val outputCursor = initialWorld.outputs.size
        val traceCursor = initialWorld.traces.size
        val eventTraceCursor = initialWorld.playerEventTraces.size
        val beforeSnapshot = sandbox.snapshotJson()
        document.steps.forEachIndexed { index, step ->
            if (!step.element.isJsonObject) {
                throw SandboxException(
                    DiagnosticCode.INPUT_FORMAT,
                    "Manifest steps must be objects: ${document.path}",
                )
            }
            val stepObject = step.element.asJsonObject
            try {
                sandbox = runStep(stepObject, sandbox, effectiveOptions, step.base)
            } catch (error: SandboxException) {
                if (stepObject.get("allowFailure")?.asBoolean == true) {
                    diagnostics += manifestDiagnostic(index + 1, sandbox.profile.id, error, sandbox)
                } else {
                    throw SandboxException(
                        error.code,
                        "Step ${index + 1} failed in active sandbox: ${error.message}",
                        error.location,
                        error.version,
                        error.command,
                        error,
                    )
                }
            }
        }
        val outputs = if (sandbox.world === initialWorld) sandbox.world.outputs.drop(outputCursor) else sandbox.world.outputs.toList()
        val traces = if (sandbox.world === initialWorld) sandbox.world.traces.drop(traceCursor) else sandbox.world.traces.toList()
        val eventTraces =
            if (sandbox.world ===
                initialWorld
            ) {
                sandbox.world.playerEventTraces.drop(eventTraceCursor)
            } else {
                sandbox.world.playerEventTraces.toList()
            }
        document.assertions.forEachIndexed { index, assertion ->
            if (!assertion.element.isJsonObject) {
                throw SandboxException(
                    DiagnosticCode.INPUT_FORMAT,
                    "Manifest assertions must be objects: ${document.path}",
                )
            }
            val assertionObject = assertion.element.asJsonObject
            failures +=
                ManifestAssertionEvaluator
                    .evaluate(assertionObject, sandbox, diagnostics, beforeSnapshot, effectiveOptions.seed, assertion.base)
                    .map { "${assertionLabel(index, assertion, assertionObject, document.path)}: $it" }
        }
        val resourceSummary = summarizeResources(sandbox)
        if (effectiveOptions.failOnMissingResources) failures += missingResourceFailures(resourceSummary)
        val finalSnapshot = sandbox.snapshotJson()
        val attempt =
            ManifestAttemptResult(
                version = sandbox.profile.id,
                packs = emptyList(),
                passed = failures.isEmpty(),
                messages = failures,
                outputs = outputs,
                traces = traces,
                eventTraces = eventTraces,
                snapshot = finalSnapshot,
                snapshotDiffs = SnapshotDiff.stateDiff(beforeSnapshot, finalSnapshot),
                resourceSummary = resourceSummary,
            )
        return ExistingSandboxManifestResult(
            result =
                ManifestResult(
                    path = path,
                    passed = attempt.passed,
                    messages = failures,
                    outputs = attempt.outputs,
                    traces = attempt.traces,
                    eventTraces = attempt.eventTraces,
                    attempts = listOf(attempt),
                ),
            sandbox = sandbox,
        )
    }

    fun exportExternalDiffScript(path: Path): String {
        val json =
            try {
                JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).asJsonObject
            } catch (error: Exception) {
                throw SandboxException(
                    DiagnosticCode.INPUT_FORMAT,
                    "Invalid manifest JSON: ${path.toAbsolutePath().normalize()}",
                    cause = error,
                )
            }
        val document = resolveManifest(path, json)
        val configs = runConfigs(document.root, document.base, document.path)
        val lines = mutableListOf<String>()
        lines += "# Datapack Sandbox external differential replay script"
        lines += "# manifest: ${document.path}"
        lines += "# versions: ${configs.joinToString { it.version }}"
        configs.forEach { config ->
            lines += "# packs[${config.version}]: ${config.packs.joinToString()}"
        }
        if (document.worlds.isNotEmpty()) {
            lines += "# world fixtures are sandbox setup only; recreate equivalent state in the external runtime before replay."
        }
        lines += ""
        document.steps.forEachIndexed { index, section ->
            if (!section.element.isJsonObject) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest steps must be objects: ${document.path}")
            }
            appendExternalScriptStep(lines, index + 1, section.element.asJsonObject, section.base)
        }
        return lines.joinToString(System.lineSeparator()) + System.lineSeparator()
    }

    fun evaluateAssertions(
        assertions: List<JsonObject>,
        sandbox: DatapackSandbox,
    ): List<String> = evaluateAssertions(assertions, sandbox, beforeSnapshot = null)

    fun evaluateAssertions(
        assertions: List<JsonObject>,
        sandbox: DatapackSandbox,
        beforeSnapshot: JsonObject?,
    ): List<String> = evaluateAssertions(assertions, sandbox, emptyList(), beforeSnapshot, defaultLootSeed = 0)

    private fun evaluateAssertions(
        assertions: List<JsonObject>,
        sandbox: DatapackSandbox,
        diagnostics: List<ManifestDiagnostic>,
        beforeSnapshot: JsonObject?,
        defaultLootSeed: Long,
        base: Path = Path.of("."),
    ): List<String> =
        assertions.flatMapIndexed { index, assertion ->
            ManifestAssertionEvaluator
                .evaluate(
                    assertion,
                    sandbox,
                    diagnostics,
                    beforeSnapshot,
                    defaultLootSeed,
                    base,
                ).map { "${assertionLabel(index, assertion)}: $it" }
        }

    private fun runOne(
        document: ResolvedManifest,
        config: ManifestRunConfig,
        unsupportedMode: UnsupportedFeatureMode,
        options: ManifestOptions,
    ): ManifestAttemptResult {
        var sandbox = createSandbox(config.version, config.packs, unsupportedFeatureMode = unsupportedMode, limits = options.limits)
        sandbox.world.seed = options.seed
        document.worlds.forEach { world ->
            if (!world.element.isJsonObject) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest world must be an object: ${document.path}")
            }
            ManifestWorldSetup.apply(world.element.asJsonObject, sandbox, world.base)
        }
        val beforeSnapshot = sandbox.snapshotJson()
        val diagnostics = mutableListOf<ManifestDiagnostic>()
        document.steps.forEachIndexed { index, step ->
            if (!step.element.isJsonObject) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest steps must be objects: ${document.path}")
            }
            val stepObject = step.element.asJsonObject
            try {
                sandbox = runStep(stepObject, sandbox, options, step.base)
            } catch (error: SandboxException) {
                if (stepObject.get("allowFailure")?.asBoolean == true) {
                    diagnostics += manifestDiagnostic(index + 1, config.version, error, sandbox)
                    return@forEachIndexed
                }
                throw SandboxException(
                    error.code,
                    "Step ${index + 1} failed for ${config.version}: ${error.message}",
                    error.location,
                    error.version,
                    error.command,
                    error,
                )
            }
        }

        val resourceSummary = summarizeResources(sandbox)
        val failures = mutableListOf<String>()
        if (options.failOnMissingResources) {
            failures += missingResourceFailures(resourceSummary)
        }
        document.assertions.forEachIndexed { index, assertion ->
            if (!assertion.element.isJsonObject) {
                failures += "${assertionLabel(index, assertion, document.path)}: Assertion must be an object"
            } else {
                val assertionObject = assertion.element.asJsonObject
                failures +=
                    ManifestAssertionEvaluator.evaluate(assertionObject, sandbox, diagnostics, beforeSnapshot, options.seed, assertion.base).map {
                        "${assertionLabel(index, assertion, assertionObject, document.path)}: $it"
                    }
            }
        }
        if (failures.isNotEmpty() && options.snapshotOnFail) {
            failures += "snapshot: ${sandbox.snapshotString()}"
        }
        if (failures.isNotEmpty() && options.snapshotDiffOnFail) {
            failures +=
                "snapshot diff:${System.lineSeparator()}${SnapshotDiff.render(
                    SnapshotDiff.stateDiff(beforeSnapshot, sandbox.snapshotJson()),
                )}"
        }
        val finalSnapshot = sandbox.snapshotJson()

        return ManifestAttemptResult(
            version = config.version,
            packs = config.packs,
            passed = failures.isEmpty(),
            messages = failures,
            outputs = sandbox.world.outputs.toList(),
            traces = sandbox.world.traces.toList(),
            eventTraces = sandbox.world.playerEventTraces.toList(),
            snapshot = finalSnapshot,
            snapshotDiffs = SnapshotDiff.stateDiff(beforeSnapshot, finalSnapshot),
            resourceSummary = resourceSummary,
        )
    }

    fun missingResourceFailures(summary: ManifestResourceSummary): List<String> =
        summary.missingReferences.map { reference ->
            "missing-reference ${reference.source} -> ${reference.type} ${reference.id}"
        }

    fun summarizeResources(sandbox: DatapackSandbox): ManifestResourceSummary = sandbox.datapack.resourceSummary()

    private fun manifestDiagnostic(
        step: Int,
        version: String,
        error: SandboxException,
        sandbox: DatapackSandbox,
    ): ManifestDiagnostic {
        val trace = sandbox.world.traces.lastOrNull { it.errorCode == error.code && it.errorMessage == error.message }
        val command = error.command ?: trace?.command
        return ManifestDiagnostic(
            step = step,
            version = error.version ?: version,
            code = error.code,
            message = error.message,
            command = command,
            root = command?.substringBefore(' ') ?: trace?.root,
        )
    }

    private fun resolveManifest(
        path: Path,
        json: JsonObject,
        stack: MutableSet<Path> = linkedSetOf(),
    ): ResolvedManifest {
        val normalized = path.toAbsolutePath().normalize()
        if (!stack.add(normalized)) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest include cycle detected: $normalized")
        }
        try {
            val base = normalized.parent ?: Path.of(".")
            val included =
                manifestIncludes(json, base).map { include ->
                    val includeJson =
                        try {
                            JsonParser.parseString(Files.readString(include, StandardCharsets.UTF_8)).asJsonObject
                        } catch (error: Exception) {
                            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid included manifest JSON: $include", cause = error)
                        }
                    resolveManifest(include, includeJson, stack)
                }
            val root = manifestRootWithIncludedDefaults(json, included)
            return ResolvedManifest(
                path = normalized,
                base = base,
                root = root,
                worlds =
                    included.flatMap { it.worlds } +
                        listOfNotNull(json.get("world")?.let { ManifestSection(it, base, normalized, "/world") }),
                steps =
                    included.flatMap { it.steps } +
                        json.manifestArray("steps").mapIndexed { index, step -> ManifestSection(step, base, normalized, "/steps/$index") },
                assertions =
                    included.flatMap { it.assertions } +
                        json.manifestArray("assertions").mapIndexed { index, assertion ->
                            ManifestSection(assertion, base, normalized, "/assertions/$index")
                        },
            )
        } finally {
            stack.remove(normalized)
        }
    }

    private fun manifestIncludes(
        json: JsonObject,
        base: Path,
    ): List<Path> {
        val include = json.get("include") ?: return emptyList()
        val entries =
            when {
                include.isJsonPrimitive && include.asJsonPrimitive.isString -> listOf(include)
                include.isJsonArray -> include.asJsonArray.toList()
                else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest include must be a string or array of strings")
            }
        return entries.map { entry ->
            if (!entry.isJsonPrimitive || !entry.asJsonPrimitive.isString) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest include entries must be strings")
            }
            base.resolve(entry.asString).normalize()
        }
    }

    private fun manifestRootWithIncludedDefaults(
        json: JsonObject,
        included: List<ResolvedManifest>,
    ): JsonObject {
        val root = json.deepCopy()
        listOf("version", "versions", "unsupported", "seed", "failOnMissingResources").forEach { key ->
            if (!root.has(key)) {
                included.firstOrNull { it.root.has(key) }?.let { root.add(key, it.root.get(key).deepCopy()) }
            }
        }
        val packSources =
            included
                .mapNotNull { document -> document.root.get("packs")?.let { rebasePacks(it, document.base) } } +
                listOfNotNull(json.get("packs")?.deepCopy())
        if (packSources.isNotEmpty()) {
            root.add("packs", mergeManifestPacks(packSources))
        }
        return root
    }

    private fun mergeManifestPacks(packs: List<JsonElement>): JsonElement {
        if (packs.none { it.isJsonObject }) {
            return JsonArray().also { array ->
                packs.forEach { packElement ->
                    if (!packElement.isJsonArray) {
                        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest packs must be an array or object")
                    }
                    packElement.asJsonArray.forEach { array.add(it.deepCopy()) }
                }
            }
        }

        return JsonObject().also { merged ->
            packs.forEach { packElement ->
                when {
                    packElement.isJsonArray -> appendPackEntries(merged, "default", packElement.asJsonArray)
                    packElement.isJsonObject ->
                        packElement.asJsonObject.entrySet().forEach { (version, value) ->
                            if (!value.isJsonArray) {
                                throw SandboxException(
                                    DiagnosticCode.INPUT_FORMAT,
                                    "Manifest packs for version '$version' must be an array",
                                )
                            }
                            appendPackEntries(merged, version, value.asJsonArray)
                        }
                    else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest packs must be an array or object")
                }
            }
        }
    }

    private fun appendPackEntries(
        target: JsonObject,
        key: String,
        entries: JsonArray,
    ) {
        val array = target.getAsJsonArray(key) ?: JsonArray().also { target.add(key, it) }
        entries.forEach { array.add(it.deepCopy()) }
    }

    private fun rebasePacks(
        packs: JsonElement,
        base: Path,
    ): JsonElement =
        when {
            packs.isJsonArray ->
                JsonArray().also { array ->
                    packs.asJsonArray.forEach { entry -> array.add(rebasePackPath(entry, base)) }
                }
            packs.isJsonObject ->
                JsonObject().also { root ->
                    packs.asJsonObject.entrySet().forEach { (version, value) ->
                        if (!value.isJsonArray) {
                            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest packs for version '$version' must be an array")
                        }
                        root.add(version, rebasePacks(value, base))
                    }
                }
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest packs must be an array or object")
        }

    private fun rebasePackPath(
        entry: JsonElement,
        base: Path,
    ): JsonElement {
        if (!entry.isJsonPrimitive || !entry.asJsonPrimitive.isString) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest pack entries must be strings")
        }
        return com.google.gson.JsonPrimitive(base.resolve(entry.asString).normalize().toString())
    }

    private fun runConfigs(
        json: JsonObject,
        base: Path,
        path: Path,
    ): List<ManifestRunConfig> {
        if (json.has("version") && json.has("versions")) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest must use either 'version' or 'versions', not both: $path")
        }

        val versions =
            when {
                json.has("versions") -> json.manifestStringArray("versions")
                json.has("version") -> listOf(json.requiredManifestString("version"))
                else -> listOf(VersionProfiles.default.id)
            }.distinct()

        if (versions.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest must contain at least one version: $path")
        }

        val packsElement =
            json.get("packs")
                ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest must contain packs: $path")
        return versions.map { version ->
            ManifestRunConfig(
                version = version,
                packs = parsePacksForVersion(packsElement, version, base, path),
            )
        }
    }

    private fun parsePacksForVersion(
        packsElement: JsonElement,
        version: String,
        base: Path,
        path: Path,
    ): List<Path> {
        val packEntries =
            when {
                packsElement.isJsonArray -> packsElement.asJsonArray.toList()
                packsElement.isJsonObject -> {
                    val packsObject = packsElement.asJsonObject
                    val defaultEntries =
                        packsObject.get("default")?.let { value ->
                            if (!value.isJsonArray) {
                                throw SandboxException(
                                    DiagnosticCode.INPUT_FORMAT,
                                    "Manifest packs for version 'default' must be an array: $path",
                                )
                            }
                            value.asJsonArray.toList()
                        }
                    val versionEntries =
                        packsObject.get(version)?.let { value ->
                            if (!value.isJsonArray) {
                                throw SandboxException(
                                    DiagnosticCode.INPUT_FORMAT,
                                    "Manifest packs for version '$version' must be an array: $path",
                                )
                            }
                            value.asJsonArray.toList()
                        }
                    (defaultEntries.orEmpty() + versionEntries.orEmpty()).ifEmpty {
                        throw SandboxException(
                            DiagnosticCode.INPUT_FORMAT,
                            "Manifest packs object is missing entry for version '$version': $path",
                        )
                    }
                }
                else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest packs must be an array or object: $path")
            }

        val packs =
            packEntries.map { entry ->
                if (!entry.isJsonPrimitive || !entry.asJsonPrimitive.isString) {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest pack entries must be strings: $path")
                }
                base.resolve(entry.asString).normalize()
            }
        if (packs.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest must contain at least one pack for version '$version': $path")
        }
        return packs
    }

    private fun assertionLabel(index: Int): String = "assertion ${index + 1} (/assertions/$index)"

    private fun assertionLabel(
        index: Int,
        assertion: JsonObject,
    ): String {
        val kind = assertionKinds.firstOrNull { assertion.has(it) }
        return if (kind == null) {
            assertionLabel(index)
        } else {
            "assertion ${index + 1} (/assertions/$index/$kind)"
        }
    }

    private fun assertionLabel(
        index: Int,
        section: ManifestSection,
        documentPath: Path,
    ): String = "assertion ${index + 1} (${section.sourcePointer(documentPath)})"

    private fun assertionLabel(
        index: Int,
        section: ManifestSection,
        assertion: JsonObject,
        documentPath: Path,
    ): String {
        val kind = assertionKinds.firstOrNull { assertion.has(it) }
        val pointer = if (kind == null) section.sourcePointer(documentPath) else "${section.sourcePointer(documentPath)}/$kind"
        return "assertion ${index + 1} ($pointer)"
    }

    private fun ManifestSection.sourcePointer(documentPath: Path): String {
        val normalizedSource = source.toAbsolutePath().normalize()
        val normalizedDocument = documentPath.toAbsolutePath().normalize()
        return if (normalizedSource == normalizedDocument) {
            pointer
        } else {
            "$normalizedSource$pointer"
        }
    }

    private val assertionKinds =
        listOf(
            "score",
            "storage",
            "entityCount",
            "entity",
            "world",
            "player",
            "team",
            "bossbar",
            "scheduled",
            "block",
            "advancement",
            "predicate",
            "loot",
            "item",
            "trace",
            "eventTrace",
            "diagnostic",
            "snapshot",
            "snapshotDiff",
            "output",
        )

    private fun runStep(
        step: JsonObject,
        sandbox: DatapackSandbox,
        options: ManifestOptions,
        base: Path,
    ): DatapackSandbox {
        when {
            step.has("load") && step.get("load").asBoolean -> sandbox.runLoad()
            step.has("ticks") -> sandbox.runTicks(step.get("ticks").asInt)
            step.has("function") -> sandbox.runFunction(step.get("function").asString)
            step.has("command") -> sandbox.executeCommand(step.get("command").asString)
            step.has("commands") ->
                runCommandLines(
                    step.manifestStringArray("commands", "Manifest step 'commands'"),
                    sandbox,
                    step.manifestString("source") ?: "<manifest:commands>",
                )
            step.has("functionText") ->
                runCommandLines(
                    step.requiredManifestString("functionText").lines(),
                    sandbox,
                    step.manifestString("source") ?: "<manifest:functionText>",
                )
            step.has("mcfunction") -> {
                val file = base.resolve(step.requiredManifestString("mcfunction")).normalize()
                runCommandLines(Files.readAllLines(file, StandardCharsets.UTF_8), sandbox, file.toString())
            }
            step.has("player") -> runPlayerStep(step.getAsJsonObject("player"), sandbox)
            step.has("block") -> runBlockStep(step.getAsJsonObject("block"), sandbox)
            step.has("event") -> sandbox.handlePlayerEvent(parseEvent(step.getAsJsonObject("event"), sandbox))
            step.has("snapshot") -> runSnapshotStep(step.get("snapshot"), sandbox, base)
            step.has("trace") -> runTraceStep(step.get("trace"), sandbox, base)
            step.has("reset") -> return resetSandbox(step.get("reset"), sandbox, options)
            step.has("loot") -> {
                val loot = step.getAsJsonObject("loot")
                sandbox.generateLoot(
                    ResourceLocation.parse(loot.requiredManifestString("table")),
                    ResourceLocation.parse(loot.manifestString("context") ?: "minecraft:empty"),
                    loot.manifestString("player")?.let { sandbox.world.requirePlayer(it) },
                    loot.get("seed")?.asLong ?: options.seed,
                )
            }
            else -> throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "Step must contain load, ticks, function, command, commands, functionText, mcfunction, player, block, event, snapshot, trace, reset, or loot",
            )
        }
        return sandbox
    }

    private fun resetSandbox(
        reset: JsonElement,
        sandbox: DatapackSandbox,
        options: ManifestOptions,
    ): DatapackSandbox {
        if (reset.isJsonPrimitive && !reset.asBoolean) return sandbox
        return DatapackSandbox(
            sandbox.profile,
            sandbox.datapack,
            SandboxWorld().also { world ->
                world.seed = options.seed
                world.createPlayer("Steve")
            },
            sandbox.unsupportedFeatureMode,
            sandbox.limits,
        )
    }

    private fun runSnapshotStep(
        snapshot: JsonElement,
        sandbox: DatapackSandbox,
        base: Path,
    ) {
        val target = stepTarget(snapshot)
        target.file?.let { writeTextStepOutput(base.resolve(it).normalize(), sandbox.snapshotString()) }
        if (target.output) {
            sandbox.world.recordOutput("manifest snapshot", "debug", text = sandbox.snapshotString(), payload = sandbox.snapshotJson())
        }
    }

    private fun runTraceStep(
        trace: JsonElement,
        sandbox: DatapackSandbox,
        base: Path,
    ) {
        val target = stepTarget(trace)
        val traceLines = sandbox.world.traces.joinToString(System.lineSeparator()) { JsonValues.render(it.toJson()) }
        target.file?.let { writeTextStepOutput(base.resolve(it).normalize(), traceLines) }
        if (target.output) {
            val payload = JsonArray().also { array -> sandbox.world.traces.forEach { array.add(it.toJson()) } }
            sandbox.world.recordOutput(
                "manifest trace",
                "debug",
                text =
                    sandbox.world.traces.size
                        .toString(),
                payload = payload,
            )
        }
    }

    private data class StepOutputTarget(
        val file: String?,
        val output: Boolean,
    )

    private fun stepTarget(value: JsonElement): StepOutputTarget =
        when {
            value.isJsonPrimitive && value.asJsonPrimitive.isBoolean -> StepOutputTarget(file = null, output = value.asBoolean)
            value.isJsonPrimitive && value.asJsonPrimitive.isString -> StepOutputTarget(file = value.asString, output = false)
            value.isJsonObject -> {
                val root = value.asJsonObject
                StepOutputTarget(
                    file = root.manifestString("file"),
                    output = root.get("output")?.asBoolean ?: false,
                )
            }
            else -> throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "Manifest snapshot/trace step must be a boolean, file path string, or object",
            )
        }

    private fun writeTextStepOutput(
        path: Path,
        content: String,
    ) {
        path.parent?.let(Files::createDirectories)
        Files.writeString(path, content, StandardCharsets.UTF_8)
    }

    private fun runCommandLines(
        lines: List<String>,
        sandbox: DatapackSandbox,
        sourceName: String,
    ) {
        lines
            .mapIndexedNotNull { index, raw ->
                val command = raw.removePrefix("\uFEFF").trim()
                if (command.isBlank() || command.startsWith("#")) {
                    null
                } else {
                    val normalized = command.removePrefix("/")
                    normalized to SourceLocation(file = sourceName, line = index + 1, command = normalized)
                }
            }.forEach { (command, location) ->
                sandbox.executeCommand(command, location)
            }
    }

    private fun appendExternalScriptStep(
        lines: MutableList<String>,
        stepNumber: Int,
        step: JsonObject,
        base: Path,
    ) {
        lines += "# step $stepNumber"
        when {
            step.has("load") -> {
                if (step.get("load").asBoolean) lines += "function #minecraft:load" else lines += "# load=false"
            }
            step.has("ticks") -> {
                val ticks = step.get("ticks").asInt
                lines += "# ticks $ticks: replaying the tick function tag once per requested tick"
                repeat(ticks) { lines += "function #minecraft:tick" }
            }
            step.has("function") -> lines += "function ${step.requiredManifestString("function")}"
            step.has("command") -> appendExternalCommand(lines, step.requiredManifestString("command"))
            step.has("commands") -> {
                val source = step.manifestString("source") ?: "<manifest commands step $stepNumber>"
                lines += "# source: $source"
                step.manifestStringArray("commands", "Manifest step commands").forEach { appendExternalCommand(lines, it) }
            }
            step.has("functionText") -> {
                val source = step.manifestString("source") ?: "<manifest functionText step $stepNumber>"
                lines += "# source: $source"
                step.requiredManifestString("functionText").lines().forEach { appendExternalCommand(lines, it) }
            }
            step.has("mcfunction") -> {
                val file = base.resolve(step.requiredManifestString("mcfunction")).normalize()
                lines += "# source: $file"
                if (!Files.isRegularFile(file)) {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest mcfunction file not found: $file")
                }
                Files.readAllLines(file, StandardCharsets.UTF_8).forEach { appendExternalCommand(lines, it) }
            }
            step.has("event") -> lines += "# sandbox event step: ${JsonValues.render(step.get("event"))}"
            step.has("player") -> lines += "# sandbox player fixture step: ${JsonValues.render(step.get("player"))}"
            step.has("block") -> lines += "# sandbox block fixture step: ${JsonValues.render(step.get("block"))}"
            step.has("snapshot") -> lines += "# sandbox snapshot artifact step"
            step.has("trace") -> lines += "# sandbox trace artifact step"
            step.has("reset") -> lines += "# sandbox reset-world step; recreate a fresh external world before continuing"
            step.has("loot") -> lines += "# sandbox loot generator step: ${JsonValues.render(step.get("loot"))}"
            else -> lines += "# unsupported manifest step for external script export: ${JsonValues.render(step)}"
        }
        lines += ""
    }

    private fun appendExternalCommand(
        lines: MutableList<String>,
        raw: String,
    ) {
        val command = raw.removePrefix("\uFEFF").trim()
        if (command.isBlank()) return
        if (command.startsWith("#")) {
            lines += command
        } else {
            lines += command.removePrefix("/")
        }
    }

    private fun runPlayerStep(
        player: JsonObject,
        sandbox: DatapackSandbox,
    ) {
        val name = player.requiredManifestString("name")
        val sandboxPlayer = sandbox.createPlayer(name)
        player.manifestArray("inventory").forEach { entry ->
            if (!entry.isJsonObject) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Inventory entries must be objects")
            sandboxPlayer.inventory += parseManifestItem(entry.asJsonObject)
        }
        player.getAsJsonArray("position")?.let { sandboxPlayer.position = parseManifestPosition(it) }
        player.manifestString("dimension")?.let { sandboxPlayer.dimension = ResourceLocation.parse(it) }
        player.get("xp")?.let { sandboxPlayer.xp = it.asInt }
        (player.get("xpLevels") ?: player.get("xpLevel"))?.let { sandboxPlayer.xpLevels = it.asInt }
    }

    private fun runBlockStep(
        block: JsonObject,
        sandbox: DatapackSandbox,
    ) {
        val pos =
            parseManifestBlockPos(
                block.getAsJsonArray("pos") ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Block step requires pos"),
            )
        val id = ResourceLocation.parse(block.requiredManifestString("id"))
        val properties = linkedMapOf<String, String>()
        block.getAsJsonObject("properties")?.entrySet()?.forEach { (key, value) -> properties[key] = value.asString }
        val nbt = parseManifestNbtObject(block.get("nbt"), "block nbt") ?: JsonObject()
        val sandboxBlock = SandboxBlock(id, properties)
        if (nbt.entrySet().isNotEmpty()) {
            val updated = sandboxBlock.fullNbt(pos, sandbox.profile)
            JsonPaths.merge(updated, null, nbt)
            sandboxBlock.writeFullNbt(pos, sandbox.profile, updated)
        }
        sandbox.world.setBlock(pos, sandboxBlock)
    }

    private fun parseEvent(
        event: JsonObject,
        sandbox: DatapackSandbox,
    ): PlayerEvent {
        val playerName = event.requiredManifestString("player")
        sandbox.createPlayer(playerName)
        val target = event.manifestString("target")
        val id =
            event.manifestString("item")?.let { ResourceLocation.parse(it) }
                ?: event.manifestString("entity")?.let { ResourceLocation.parse(it) }
                ?: event.manifestString("block")?.let { ResourceLocation.parse(it) }
                ?: event.manifestString("recipe")?.let { ResourceLocation.parse(it) }
        val damageSource =
            event.manifestString("damageSource")
                ?: event.manifestString("damageType")
        val damageAmount =
            event.get("amount")?.asDouble
                ?: event.get("damage")?.takeIf { it.isJsonPrimitive }?.asDouble
        return PlayerEvent(
            playerName = playerName,
            type = event.requiredManifestString("type").replace('-', '_'),
            item =
                event.manifestString("item")?.let { parseManifestItem(event) }
                    ?: id?.takeIf { event.manifestString("item") != null }?.let { ItemStack(it) },
            entity = event.manifestString("entity")?.takeIf { target == null }?.let { SandboxEntity(type = ResourceLocation.parse(it)) },
            target = target,
            damageAmount = damageAmount,
            damageSource = damageSource?.let { ResourceLocation.parse(it) },
            block = event.manifestString("block")?.let { ResourceLocation.parse(it) },
            blockPos = parseEventBlockPos(event),
            fromDimension = event.manifestString("from")?.let { ResourceLocation.parse(it) },
            toDimension = event.manifestString("to")?.let { ResourceLocation.parse(it) },
            recipe = event.manifestString("recipe")?.let { ResourceLocation.parse(it) },
            input = parseInput(event),
        )
    }

    private fun parseEventBlockPos(event: JsonObject): BlockPos? {
        val pos = event.getAsJsonArray("blockPos") ?: event.getAsJsonArray("pos")
        if (pos != null) {
            if (pos.size() != 3) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Event blockPos must contain exactly three coordinates")
            }
            return BlockPos(pos[0].asInt, pos[1].asInt, pos[2].asInt)
        }
        val x = event.get("blockX")?.asInt
        val y = event.get("blockY")?.asInt
        val z = event.get("blockZ")?.asInt
        return if (x != null || y != null || z != null) {
            if (x == null || y == null || z == null) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Event blockX, blockY, and blockZ must be provided together")
            }
            BlockPos(x, y, z)
        } else {
            null
        }
    }

    private fun parseInput(event: JsonObject): PlayerInput? {
        val key = event.manifestString("key")
        if (key != null) {
            return PlayerInput(device = "keyboard", code = key, action = event.manifestString("action") ?: "press")
        }
        val button = event.manifestString("mouseButton") ?: event.manifestString("button")
        if (button != null) {
            return PlayerInput(
                device = "mouse",
                code = button,
                action = event.manifestString("action") ?: "click",
                x = event.get("x")?.asDouble,
                y = event.get("y")?.asDouble,
            )
        }
        return null
    }
}
