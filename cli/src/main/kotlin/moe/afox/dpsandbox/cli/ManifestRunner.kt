package moe.afox.dpsandbox.cli

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import moe.afox.dpsandbox.core.BlockPos
import moe.afox.dpsandbox.core.CommandTraceEvent
import moe.afox.dpsandbox.core.DatapackMissingResourceReference
import moe.afox.dpsandbox.core.DatapackResourceSummary
import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.DatapackSandbox
import moe.afox.dpsandbox.core.ItemStack
import moe.afox.dpsandbox.core.JsonPaths
import moe.afox.dpsandbox.core.JsonValues
import moe.afox.dpsandbox.core.OutputEvent
import moe.afox.dpsandbox.core.PlayerEvent
import moe.afox.dpsandbox.core.PlayerEventTraceAssertions
import moe.afox.dpsandbox.core.PlayerEventTraceEvent
import moe.afox.dpsandbox.core.PlayerEventTraceExpectation
import moe.afox.dpsandbox.core.PlayerInput
import moe.afox.dpsandbox.core.PredicateContext
import moe.afox.dpsandbox.core.ResourceLocation
import moe.afox.dpsandbox.core.SandboxException
import moe.afox.dpsandbox.core.SandboxEntity
import moe.afox.dpsandbox.core.SandboxBlock
import moe.afox.dpsandbox.core.SandboxPlayer
import moe.afox.dpsandbox.core.SandboxLimits
import moe.afox.dpsandbox.core.SandboxWorld
import moe.afox.dpsandbox.core.SandboxWorldBorder
import moe.afox.dpsandbox.core.SnapshotDiff
import moe.afox.dpsandbox.core.SnapshotDiffEntry
import moe.afox.dpsandbox.core.SnapshotDiffKind
import moe.afox.dpsandbox.core.SourceLocation
import moe.afox.dpsandbox.core.TraceAssertions
import moe.afox.dpsandbox.core.TraceExpectation
import moe.afox.dpsandbox.core.UnsupportedFeatureMode
import moe.afox.dpsandbox.core.VersionProfiles
import moe.afox.dpsandbox.core.createSandbox
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
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

data class ManifestOptions(
    val seed: Long = 0,
    val verbose: Boolean = false,
    val snapshotOnFail: Boolean = false,
    val snapshotDiffOnFail: Boolean = false,
    val failOnMissingResources: Boolean = false,
    val unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
    val limits: SandboxLimits = SandboxLimits(),
)

object ManifestRunner {
    private data class ManifestRunConfig(
        val version: String,
        val packs: List<Path>,
    )

    private data class ManifestSection(
        val element: JsonElement,
        val base: Path,
    )

    private data class ResolvedManifest(
        val path: Path,
        val base: Path,
        val root: JsonObject,
        val worlds: List<ManifestSection>,
        val steps: List<ManifestSection>,
        val assertions: List<ManifestSection>,
    )

    private data class ManifestDiagnostic(
        val step: Int?,
        val version: String,
        val code: DiagnosticCode,
        val message: String,
        val command: String?,
        val root: String?,
    )

    fun discover(inputs: List<Path>): List<Path> {
        val manifests = mutableListOf<Path>()
        inputs.forEach { input ->
            val path = input.toAbsolutePath().normalize()
            when {
                path.isRegularFile() -> manifests.add(path)
                path.isDirectory() -> Files.walk(path).use { walk ->
                    walk.filter { it.isRegularFile() && it.name.endsWith(".dps.json") }
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

    fun run(path: Path, options: ManifestOptions = ManifestOptions()): ManifestResult {
        val json = try {
            JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).asJsonObject
        } catch (error: Exception) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid manifest JSON: ${path.toAbsolutePath().normalize()}", cause = error)
        }

        val document = resolveManifest(path, json)
        val configs = runConfigs(document.root, document.base, document.path)
        val unsupportedMode = document.root.manifestString("unsupported")?.let(::unsupportedFeatureMode) ?: options.unsupportedFeatureMode
        val effectiveOptions = options.copy(
            seed = document.root.get("seed")?.asLong ?: options.seed,
            failOnMissingResources = document.root.get("failOnMissingResources")?.asBoolean ?: options.failOnMissingResources,
        )
        val attempts = configs.map { config ->
            runOne(document, config, unsupportedMode, effectiveOptions)
        }

        val multiVersion = attempts.size > 1
        val messages = attempts.flatMap { attempt ->
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

    fun exportExternalDiffScript(path: Path): String {
        val json = try {
            JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).asJsonObject
        } catch (error: Exception) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid manifest JSON: ${path.toAbsolutePath().normalize()}", cause = error)
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

    internal fun evaluateAssertions(assertions: List<JsonObject>, sandbox: DatapackSandbox): List<String> =
        evaluateAssertions(assertions, sandbox, beforeSnapshot = null)

    internal fun evaluateAssertions(assertions: List<JsonObject>, sandbox: DatapackSandbox, beforeSnapshot: JsonObject?): List<String> =
        evaluateAssertions(assertions, sandbox, emptyList(), beforeSnapshot, defaultLootSeed = 0)

    private fun evaluateAssertions(
        assertions: List<JsonObject>,
        sandbox: DatapackSandbox,
        diagnostics: List<ManifestDiagnostic>,
        beforeSnapshot: JsonObject?,
        defaultLootSeed: Long,
        base: Path = Path.of("."),
    ): List<String> =
        assertions.flatMapIndexed { index, assertion ->
            evaluateAssertion(assertion, sandbox, diagnostics, beforeSnapshot, defaultLootSeed, base).map { "${assertionLabel(index, assertion)}: $it" }
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
                throw SandboxException(error.code, "Step ${index + 1} failed for ${config.version}: ${error.message}", error.location, error.version, error.command, error)
            }
        }

        val resourceSummary = summarizeResources(sandbox)
        val failures = mutableListOf<String>()
        if (options.failOnMissingResources) {
            failures += missingResourceFailures(resourceSummary)
        }
        document.assertions.forEachIndexed { index, assertion ->
            if (!assertion.element.isJsonObject) {
                failures += "${assertionLabel(index)}: Assertion must be an object"
            } else {
                val assertionObject = assertion.element.asJsonObject
                failures += evaluateAssertion(assertionObject, sandbox, diagnostics, beforeSnapshot, options.seed, assertion.base).map { "${assertionLabel(index, assertionObject)}: $it" }
            }
        }
        if (failures.isNotEmpty() && options.snapshotOnFail) {
            failures += "snapshot: ${sandbox.snapshotString()}"
        }
        if (failures.isNotEmpty() && options.snapshotDiffOnFail) {
            failures += "snapshot diff:${System.lineSeparator()}${SnapshotDiff.render(SnapshotDiff.stateDiff(beforeSnapshot, sandbox.snapshotJson()))}"
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

    internal fun missingResourceFailures(summary: ManifestResourceSummary): List<String> =
        summary.missingReferences.map { reference ->
            "missing-reference ${reference.source} -> ${reference.type} ${reference.id}"
        }

    internal fun summarizeResources(sandbox: DatapackSandbox): ManifestResourceSummary =
        sandbox.datapack.resourceSummary()

    private fun manifestDiagnostic(step: Int, version: String, error: SandboxException, sandbox: DatapackSandbox): ManifestDiagnostic {
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

    private fun resolveManifest(path: Path, json: JsonObject, stack: MutableSet<Path> = linkedSetOf()): ResolvedManifest {
        val normalized = path.toAbsolutePath().normalize()
        if (!stack.add(normalized)) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest include cycle detected: $normalized")
        }
        try {
            val base = normalized.parent ?: Path.of(".")
            val included = manifestIncludes(json, base).map { include ->
                val includeJson = try {
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
                worlds = included.flatMap { it.worlds } + listOfNotNull(json.get("world")?.let { ManifestSection(it, base) }),
                steps = included.flatMap { it.steps } + json.manifestArray("steps").map { ManifestSection(it, base) },
                assertions = included.flatMap { it.assertions } + json.manifestArray("assertions").map { ManifestSection(it, base) },
            )
        } finally {
            stack.remove(normalized)
        }
    }

    private fun manifestIncludes(json: JsonObject, base: Path): List<Path> {
        val include = json.get("include") ?: return emptyList()
        val entries = when {
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

    private fun manifestRootWithIncludedDefaults(json: JsonObject, included: List<ResolvedManifest>): JsonObject {
        val root = json.deepCopy()
        listOf("version", "versions", "unsupported", "seed", "failOnMissingResources").forEach { key ->
            if (!root.has(key)) {
                included.firstOrNull { it.root.has(key) }?.let { root.add(key, it.root.get(key).deepCopy()) }
            }
        }
        val packSources = included
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
                    packElement.isJsonObject -> packElement.asJsonObject.entrySet().forEach { (version, value) ->
                        if (!value.isJsonArray) {
                            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest packs for version '$version' must be an array")
                        }
                        appendPackEntries(merged, version, value.asJsonArray)
                    }
                    else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest packs must be an array or object")
                }
            }
        }
    }

    private fun appendPackEntries(target: JsonObject, key: String, entries: JsonArray) {
        val array = target.getAsJsonArray(key) ?: JsonArray().also { target.add(key, it) }
        entries.forEach { array.add(it.deepCopy()) }
    }

    private fun rebasePacks(packs: JsonElement, base: Path): JsonElement =
        when {
            packs.isJsonArray -> JsonArray().also { array ->
                packs.asJsonArray.forEach { entry -> array.add(rebasePackPath(entry, base)) }
            }
            packs.isJsonObject -> JsonObject().also { root ->
                packs.asJsonObject.entrySet().forEach { (version, value) ->
                    if (!value.isJsonArray) {
                        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest packs for version '$version' must be an array")
                    }
                    root.add(version, rebasePacks(value, base))
                }
            }
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest packs must be an array or object")
        }

    private fun rebasePackPath(entry: JsonElement, base: Path): JsonElement {
        if (!entry.isJsonPrimitive || !entry.asJsonPrimitive.isString) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest pack entries must be strings")
        }
        return com.google.gson.JsonPrimitive(base.resolve(entry.asString).normalize().toString())
    }

    private fun runConfigs(json: JsonObject, base: Path, path: Path): List<ManifestRunConfig> {
        if (json.has("version") && json.has("versions")) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest must use either 'version' or 'versions', not both: $path")
        }

        val versions = when {
            json.has("versions") -> json.manifestStringArray("versions")
            json.has("version") -> listOf(json.requiredManifestString("version"))
            else -> listOf(VersionProfiles.default.id)
        }.distinct()

        if (versions.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest must contain at least one version: $path")
        }

        val packsElement = json.get("packs")
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest must contain packs: $path")
        return versions.map { version ->
            ManifestRunConfig(
                version = version,
                packs = parsePacksForVersion(packsElement, version, base, path),
            )
        }
    }

    private fun parsePacksForVersion(packsElement: JsonElement, version: String, base: Path, path: Path): List<Path> {
        val packEntries = when {
            packsElement.isJsonArray -> packsElement.asJsonArray.toList()
            packsElement.isJsonObject -> {
                val packsObject = packsElement.asJsonObject
                val defaultEntries = packsObject.get("default")?.let { value ->
                    if (!value.isJsonArray) {
                        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest packs for version 'default' must be an array: $path")
                    }
                    value.asJsonArray.toList()
                }
                val versionEntries = packsObject.get(version)?.let { value ->
                    if (!value.isJsonArray) {
                        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest packs for version '$version' must be an array: $path")
                    }
                    value.asJsonArray.toList()
                }
                (defaultEntries.orEmpty() + versionEntries.orEmpty()).ifEmpty {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest packs object is missing entry for version '$version': $path")
                }
            }
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest packs must be an array or object: $path")
        }

        val packs = packEntries.map { entry ->
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

    private fun assertionLabel(index: Int): String =
        "assertion ${index + 1} (/assertions/$index)"

    private fun assertionLabel(index: Int, assertion: JsonObject): String {
        val kind = assertionKinds.firstOrNull { assertion.has(it) }
        return if (kind == null) {
            assertionLabel(index)
        } else {
            "assertion ${index + 1} (/assertions/$index/$kind)"
        }
    }

    private val assertionKinds = listOf(
        "score",
        "storage",
        "entityCount",
        "entity",
        "world",
        "player",
        "team",
        "bossbar",
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

    private fun runStep(step: JsonObject, sandbox: DatapackSandbox, options: ManifestOptions, base: Path): DatapackSandbox {
        when {
            step.has("load") && step.get("load").asBoolean -> sandbox.runLoad()
            step.has("ticks") -> sandbox.runTicks(step.get("ticks").asInt)
            step.has("function") -> sandbox.runFunction(step.get("function").asString)
            step.has("command") -> sandbox.executeCommand(step.get("command").asString)
            step.has("commands") -> runCommandLines(
                step.manifestStringArray("commands", "Manifest step 'commands'"),
                sandbox,
                step.manifestString("source") ?: "<manifest:commands>",
            )
            step.has("functionText") -> runCommandLines(
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
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Step must contain load, ticks, function, command, commands, functionText, mcfunction, player, block, event, snapshot, trace, reset, or loot")
        }
        return sandbox
    }

    private fun resetSandbox(reset: JsonElement, sandbox: DatapackSandbox, options: ManifestOptions): DatapackSandbox {
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

    private fun runSnapshotStep(snapshot: JsonElement, sandbox: DatapackSandbox, base: Path) {
        val target = stepTarget(snapshot)
        target.file?.let { writeTextStepOutput(base.resolve(it).normalize(), sandbox.snapshotString()) }
        if (target.output) {
            sandbox.world.recordOutput("manifest snapshot", "debug", text = sandbox.snapshotString(), payload = sandbox.snapshotJson())
        }
    }

    private fun runTraceStep(trace: JsonElement, sandbox: DatapackSandbox, base: Path) {
        val target = stepTarget(trace)
        val traceLines = sandbox.world.traces.joinToString(System.lineSeparator()) { JsonValues.render(it.toJson()) }
        target.file?.let { writeTextStepOutput(base.resolve(it).normalize(), traceLines) }
        if (target.output) {
            val payload = JsonArray().also { array -> sandbox.world.traces.forEach { array.add(it.toJson()) } }
            sandbox.world.recordOutput("manifest trace", "debug", text = sandbox.world.traces.size.toString(), payload = payload)
        }
    }

    private data class StepOutputTarget(val file: String?, val output: Boolean)

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
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest snapshot/trace step must be a boolean, file path string, or object")
        }

    private fun writeTextStepOutput(path: Path, content: String) {
        path.parent?.let(Files::createDirectories)
        Files.writeString(path, content, StandardCharsets.UTF_8)
    }

    private fun runCommandLines(lines: List<String>, sandbox: DatapackSandbox, sourceName: String) {
        lines.mapIndexedNotNull { index, raw ->
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

    private fun appendExternalScriptStep(lines: MutableList<String>, stepNumber: Int, step: JsonObject, base: Path) {
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

    private fun appendExternalCommand(lines: MutableList<String>, raw: String) {
        val command = raw.removePrefix("\uFEFF").trim()
        if (command.isBlank()) return
        if (command.startsWith("#")) {
            lines += command
        } else {
            lines += command.removePrefix("/")
        }
    }

    private fun runPlayerStep(player: JsonObject, sandbox: DatapackSandbox) {
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

    private fun runBlockStep(block: JsonObject, sandbox: DatapackSandbox) {
        val pos = parseManifestBlockPos(block.getAsJsonArray("pos") ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Block step requires pos"))
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

    private fun evaluateAssertion(
        assertion: JsonObject,
        sandbox: DatapackSandbox,
        diagnostics: List<ManifestDiagnostic> = emptyList(),
        beforeSnapshot: JsonObject? = null,
        defaultLootSeed: Long = 0,
        base: Path = Path.of("."),
    ): List<String> {
        val failures = mutableListOf<String>()
        when {
            assertion.has("score") -> {
                val score = assertion.getAsJsonObject("score")
                val target = score.requiredManifestString("target")
                val objective = score.requiredManifestString("objective")
                val actual = sandbox.world.getScore(target, objective)
                var checked = false
                score.get("equals")?.let { expected ->
                    checked = true
                    if (actual != expected.asInt) {
                        failures += "score $target $objective expected ${expected.asInt} but was $actual"
                    }
                }
                score.get("min")?.let { expected ->
                    checked = true
                    if (actual < expected.asInt) {
                        failures += "score $target $objective expected >= ${expected.asInt} but was $actual"
                    }
                }
                score.get("max")?.let { expected ->
                    checked = true
                    if (actual > expected.asInt) {
                        failures += "score $target $objective expected <= ${expected.asInt} but was $actual"
                    }
                }
                if (!checked) {
                    failures += "score $target $objective assertion requires equals, min, or max"
                }
            }
            assertion.has("storage") -> {
                failures += evaluateStorageAssertion(assertion.getAsJsonObject("storage"), sandbox)
            }
            assertion.has("entityCount") -> {
                val entity = assertion.getAsJsonObject("entityCount")
                val actual = sandbox.world.entities.count { sandboxEntity ->
                    val typeOk = entity.manifestString("type")?.let { sandboxEntity.type == ResourceLocation.parse(it) } ?: true
                    val tagOk = entity.manifestString("tag")?.let { it in sandboxEntity.tags } ?: true
                    val dimensionOk = entity.manifestString("dimension")?.let { sandboxEntity.dimension == ResourceLocation.parse(it) } ?: true
                    typeOk && tagOk && dimensionOk
                }
                var checked = false
                entity.get("equals")?.let { expected ->
                    checked = true
                    if (actual != expected.asInt) {
                        failures += "entityCount ${describeEntityCountExpectation(entity)} expected ${expected.asInt} but was $actual"
                    }
                }
                entity.get("min")?.let { expected ->
                    checked = true
                    if (actual < expected.asInt) {
                        failures += "entityCount ${describeEntityCountExpectation(entity)} expected >= ${expected.asInt} but was $actual"
                    }
                }
                entity.get("max")?.let { expected ->
                    checked = true
                    if (actual > expected.asInt) {
                        failures += "entityCount ${describeEntityCountExpectation(entity)} expected <= ${expected.asInt} but was $actual"
                    }
                }
                if (!checked) {
                    failures += "entityCount ${describeEntityCountExpectation(entity)} assertion requires equals, min, or max"
                }
            }
            assertion.has("entity") -> {
                failures += evaluateEntityAssertion(assertion.getAsJsonObject("entity"), sandbox)
            }
            assertion.has("world") -> {
                failures += evaluateWorldAssertion(assertion.getAsJsonObject("world"), sandbox)
            }
            assertion.has("player") -> {
                val player = assertion.getAsJsonObject("player")
                val name = player.requiredManifestString("name")
                val actual = sandbox.world.players[name]
                val expectedExists = player.get("exists")?.asBoolean ?: true
                if (!expectedExists) {
                    if (actual != null) failures += "player $name expected missing but exists"
                    return failures
                }
                if (actual == null) {
                    failures += "player $name expected to exist"
                } else {
                    player.get("xp")?.let { if (actual.xp != it.asInt) failures += "player ${actual.name} xp expected ${it.asInt} but was ${actual.xp}" }
                    (player.get("xpLevels") ?: player.get("xpLevel"))?.let {
                        if (actual.xpLevels != it.asInt) failures += "player ${actual.name} xpLevels expected ${it.asInt} but was ${actual.xpLevels}"
                    }
                    player.get("inventoryCount")?.let { if (actual.inventory.size != it.asInt) failures += "player ${actual.name} inventoryCount expected ${it.asInt} but was ${actual.inventory.size}" }
                    player.get("enderItemCount")?.let { if (actual.enderItems.size != it.asInt) failures += "player ${actual.name} enderItemCount expected ${it.asInt} but was ${actual.enderItems.size}" }
                    player.manifestString("dimension")?.let { if (actual.dimension != ResourceLocation.parse(it)) failures += "player ${actual.name} dimension expected $it but was ${actual.dimension}" }
                    player.manifestString("gameMode")?.let { if (actual.gameMode != it) failures += "player ${actual.name} gameMode expected $it but was ${actual.gameMode}" }
                    player.manifestString("gamemode")?.let { if (actual.gameMode != it) failures += "player ${actual.name} gameMode expected $it but was ${actual.gameMode}" }
                    player.get("health")?.let { if (actual.health != it.asDouble) failures += "player ${actual.name} health expected ${it.asDouble} but was ${actual.health}" }
                    player.get("food")?.let { if (actual.food != it.asInt) failures += "player ${actual.name} food expected ${it.asInt} but was ${actual.food}" }
                    player.get("selectedSlot")?.let { if (actual.selectedSlot != it.asInt) failures += "player ${actual.name} selectedSlot expected ${it.asInt} but was ${actual.selectedSlot}" }
                    player.manifestString("recipe")?.let { if (ResourceLocation.parse(it) !in actual.recipes) failures += "player ${actual.name} expected recipe $it" }
                    player.manifestString("effect")?.let { if (ResourceLocation.parse(it) !in actual.effects) failures += "player ${actual.name} expected effect $it" }
                    player.getAsJsonObject("stat")?.let { stat ->
                        val id = ResourceLocation.parse(stat.requiredManifestString("id"))
                        val expected = stat.get("equals").asInt
                        val actualValue = actual.stats[id] ?: 0
                        if (actualValue != expected) failures += "player ${actual.name} stat $id expected $expected but was $actualValue"
                    }
                    player.getAsJsonObject("nbt")?.let { nbt ->
                        if (!itemPathMatches(actual.fullNbt(sandbox.profile), nbt)) {
                            failures += "player ${actual.name} expected ${describePathExpectation("nbt", nbt)}"
                        }
                    }
                    player.getAsJsonArray("position")?.let {
                        val expected = parseManifestPosition(it)
                        if (actual.position != expected) failures += "player ${actual.name} position expected $expected but was ${actual.position}"
                    }
                    player.getAsJsonObject("lastInput")?.let { expected ->
                        val input = actual.lastInput
                        if (input == null) {
                            failures += "player ${actual.name} lastInput expected ${JsonValues.render(expected)} but was <none>"
                        } else {
                            expected.manifestString("device")?.let { if (input.device != it) failures += "player ${actual.name} lastInput device expected $it but was ${input.device}" }
                            expected.manifestString("code")?.let { if (input.code != it) failures += "player ${actual.name} lastInput code expected $it but was ${input.code}" }
                            expected.manifestString("action")?.let { if (input.action != it) failures += "player ${actual.name} lastInput action expected $it but was ${input.action}" }
                        }
                    }
                    player.getAsJsonObject("spawn")?.let { spawn ->
                        val expected = spawn.getAsJsonArray("pos")?.let { parseManifestPosition(it) }
                            ?: spawn.getAsJsonArray("position")?.let { parseManifestPosition(it) }
                        if (expected != null && actual.spawnPoint?.position != expected) failures += "player ${actual.name} spawn position expected $expected but was ${actual.spawnPoint?.position}"
                        spawn.manifestString("dimension")?.let { if (actual.spawnPoint?.dimension != ResourceLocation.parse(it)) failures += "player ${actual.name} spawn dimension expected $it but was ${actual.spawnPoint?.dimension}" }
                        spawn.get("angle")?.let {
                            if (actual.spawnPoint?.angle != it.asDouble) {
                                failures += "player ${actual.name} spawn angle expected ${it.asDouble} but was ${actual.spawnPoint?.angle ?: "<missing>"}"
                            }
                        }
                        spawn.get("forced")?.let {
                            if (actual.spawnPoint?.forced != it.asBoolean) {
                                failures += "player ${actual.name} spawn forced expected ${it.asBoolean} but was ${actual.spawnPoint?.forced ?: "<missing>"}"
                            }
                        }
                    }
                }
            }
            assertion.has("team") -> {
                failures += evaluateTeamAssertion(assertion.getAsJsonObject("team"), sandbox)
            }
            assertion.has("bossbar") -> {
                failures += evaluateBossbarAssertion(assertion.getAsJsonObject("bossbar"), sandbox)
            }
            assertion.has("block") -> {
                val block = assertion.getAsJsonObject("block")
                val posArray = block.getAsJsonArray("pos")
                if (posArray == null) {
                    failures += "block assertion requires pos"
                } else {
                    val pos = parseManifestBlockPos(posArray)
                    val actual = sandbox.world.block(pos)
                    block.get("exists")?.let { expected ->
                        if ((actual != null) != expected.asBoolean) failures += "block $pos exists expected ${expected.asBoolean} but was ${actual != null}"
                    }
                    block.manifestString("id")?.let { expected ->
                        if (actual?.id != ResourceLocation.parse(expected)) failures += "block $pos id expected $expected but was ${actual?.id ?: "void"}"
                    }
                    block.getAsJsonObject("nbt")?.let { expected ->
                        val path = expected.manifestString("path")
                        val actualValue = actual?.fullNbt(pos, sandbox.profile)?.let { JsonPaths.get(it, path) }
                        pathExpectationFailure("block $pos nbt ${path ?: "<root>"}", actualValue, expected)?.let { failures += it }
                    }
                }
            }
            assertion.has("advancement") -> {
                val advancement = assertion.getAsJsonObject("advancement")
                val player = sandbox.world.requirePlayer(advancement.requiredManifestString("player"))
                val id = ResourceLocation.parse(advancement.requiredManifestString("id"))
                val definition = sandbox.datapack.advancements[id]
                val progress = player.advancementProgress[id]
                val done = definition != null && progress?.isDone(definition.requirements) == true
                advancement.get("done")?.let { if (done != it.asBoolean) failures += "advancement $id done expected ${it.asBoolean} but was $done" }
                advancement.manifestString("criterion")?.let { criterion ->
                    val criterionDone = progress?.criteria?.get(criterion) == true
                    advancement.get("criterionDone")?.let { expected ->
                        if (criterionDone != expected.asBoolean) failures += "advancement $id criterion $criterion expected ${expected.asBoolean} but was $criterionDone"
                    }
                }
            }
            assertion.has("predicate") -> {
                val predicate = assertion.getAsJsonObject("predicate")
                val result = sandbox.predicates.test(ResourceLocation.parse(predicate.requiredManifestString("id")), PredicateContext(world = sandbox.world, player = predicate.manifestString("player")?.let { sandbox.world.requirePlayer(it) }))
                val expected = predicate.get("equals")?.asBoolean ?: true
                if (result != expected) failures += "predicate ${predicate.requiredManifestString("id")} expected $expected but was $result"
            }
            assertion.has("loot") -> {
                val loot = assertion.getAsJsonObject("loot")
                val result = sandbox.generateLoot(
                    ResourceLocation.parse(loot.requiredManifestString("table")),
                    ResourceLocation.parse(loot.manifestString("context") ?: "minecraft:empty"),
                    loot.manifestString("player")?.let { sandbox.world.requirePlayer(it) },
                    loot.get("seed")?.asLong ?: defaultLootSeed,
                )
                loot.get("count")?.let { if (result.items.size != it.asInt) failures += "loot count expected ${it.asInt} but was ${result.items.size}" }
                loot.manifestString("item")?.let { expected ->
                    if (result.items.none { it.id == ResourceLocation.parse(expected) }) failures += "loot expected item $expected but got ${result.items.map { it.id }}"
                }
            }
            assertion.has("item") -> {
                failures += evaluateItemAssertion(assertion.getAsJsonObject("item"), sandbox)
            }
            assertion.has("trace") -> {
                failures += evaluateTraceAssertion(assertion.getAsJsonObject("trace"), sandbox)
            }
            assertion.has("eventTrace") -> {
                failures += evaluateEventTraceAssertion(assertion.getAsJsonObject("eventTrace"), sandbox)
            }
            assertion.has("output") -> {
                failures += ManifestOutputAssertions.evaluate(assertion.getAsJsonObject("output"), sandbox)
            }
            assertion.has("diagnostic") -> {
                failures += evaluateDiagnosticAssertion(assertion.getAsJsonObject("diagnostic"), diagnostics, sandbox)
            }
            assertion.has("snapshot") -> {
                failures += evaluateSnapshotAssertion(assertion.getAsJsonObject("snapshot"), sandbox, base)
            }
            assertion.has("snapshotDiff") -> {
                failures += evaluateSnapshotDiffAssertion(assertion.getAsJsonObject("snapshotDiff"), beforeSnapshot, sandbox)
            }
            else -> failures += "Unknown assertion kind: ${assertion.keySet().joinToString()}"
        }
        return failures
    }

    private fun evaluateSnapshotAssertion(snapshot: JsonObject, sandbox: DatapackSandbox, base: Path): List<String> {
        val path = snapshot.manifestString("path")
        val actual = JsonPaths.get(sandbox.snapshotJson(), path)
        val label = "snapshot ${path ?: "<root>"}"
        val failures = mutableListOf<String>()
        var checked = false

        snapshot.get("equals")?.let { expected ->
            checked = true
            if (actual != expected) {
                failures += "$label expected ${JsonValues.render(expected).truncateForManifestFailure()} but was ${renderManifestActual(actual)}"
            }
        }
        snapshot.manifestString("equalsFile")?.let { expectedFile ->
            checked = true
            val expectedPath = base.resolve(expectedFile).normalize()
            if (!Files.isRegularFile(expectedPath)) {
                failures += "$label expected file does not exist: $expectedFile"
            } else {
                val expected = try {
                    JsonParser.parseString(Files.readString(expectedPath, StandardCharsets.UTF_8))
                } catch (error: RuntimeException) {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Manifest snapshot expected file is not valid JSON: $expectedPath", cause = error)
                }
                if (actual != expected) {
                    failures += "$label expected file $expectedFile to match ${JsonValues.render(expected).truncateForManifestFailure()} but was ${renderManifestActual(actual)}"
                }
            }
        }
        snapshot.get("exists")?.let { expected ->
            checked = true
            val exists = actual != null
            if (exists != expected.asBoolean) {
                failures += "$label exists expected ${expected.asBoolean} but was $exists"
            }
        }
        snapshot.get("missing")?.let { expected ->
            checked = true
            val shouldBeMissing = expected.asBoolean
            when {
                shouldBeMissing && actual != null -> failures += "$label expected missing but was ${JsonValues.render(actual).truncateForManifestFailure()}"
                !shouldBeMissing && actual == null -> failures += "$label expected present but was <missing>"
            }
        }
        if (!checked) {
            failures += "$label assertion requires equals, equalsFile, exists, or missing"
        }
        return failures
    }

    private fun renderManifestActual(actual: JsonElement?): String =
        actual?.let { JsonValues.render(it).truncateForManifestFailure() } ?: "<missing>"

    private fun evaluateSnapshotDiffAssertion(snapshotDiff: JsonObject, beforeSnapshot: JsonObject?, sandbox: DatapackSandbox): List<String> {
        if (beforeSnapshot == null) {
            return listOf("snapshotDiff assertion requires a manifest execution context")
        }
        val entries = SnapshotDiff.stateDiff(beforeSnapshot, sandbox.snapshotJson())
        val expectedPath = snapshotDiff.manifestString("path")
        val expectedPathContains = snapshotDiff.manifestString("pathContains")
        val expectedKind = snapshotDiff.manifestString("kind")
        val expectedBefore = snapshotDiff.get("before")
        val expectedAfter = snapshotDiff.get("after")
        val expectedContains = snapshotDiff.manifestString("contains")
        val matches = entries.filter { entry ->
            (expectedPath == null || entry.path.ifBlank { "/" } == expectedPath) &&
                (expectedPathContains == null || expectedPathContains in entry.path) &&
                (expectedKind == null || entry.kind.name.equals(expectedKind, ignoreCase = true)) &&
                (expectedBefore == null || entry.before == expectedBefore) &&
                (expectedAfter == null || entry.after == expectedAfter) &&
                (expectedContains == null || expectedContains in entry.render())
        }
        snapshotDiff.get("count")?.let { expected ->
            if (matches.size != expected.asInt) {
                return listOf("snapshotDiff ${describeSnapshotDiffExpectation(snapshotDiff)} expected count ${expected.asInt} but was ${matches.size}; ${actualSnapshotDiffs(entries)}")
            }
            return emptyList()
        }
        return if (matches.isEmpty()) {
            listOf("snapshotDiff ${describeSnapshotDiffExpectation(snapshotDiff)} did not match any snapshot change; ${actualSnapshotDiffs(entries)}")
        } else {
            emptyList()
        }
    }

    private fun actualSnapshotDiffs(entries: List<SnapshotDiffEntry>): String {
        if (entries.isEmpty()) return "actual snapshot diffs: <none>"
        val rendered = entries.take(5).joinToString("; ") { it.render().truncateForManifestFailure() }
        val suffix = if (entries.size > 5) "; ... +${entries.size - 5} more" else ""
        return "actual snapshot diffs: $rendered$suffix"
    }

    private fun describeSnapshotDiffExpectation(snapshotDiff: JsonObject): String =
        listOfNotNull(
            snapshotDiff.manifestString("path")?.let { "path=$it" },
            snapshotDiff.manifestString("pathContains")?.let { "pathContains=$it" },
            snapshotDiff.manifestString("kind")?.let { "kind=$it" },
            snapshotDiff.get("before")?.let { "before=${JsonValues.render(it)}" },
            snapshotDiff.get("after")?.let { "after=${JsonValues.render(it)}" },
            snapshotDiff.manifestString("contains")?.let { "contains=$it" },
        ).ifEmpty { listOf("<any snapshot diff>") }.joinToString(", ")

    private fun evaluateDiagnosticAssertion(diagnostic: JsonObject, diagnostics: List<ManifestDiagnostic>, sandbox: DatapackSandbox): List<String> {
        val records = diagnostics.ifEmpty {
            sandbox.world.traces
                .filter { it.errorCode != null }
                .map {
                    ManifestDiagnostic(
                        step = null,
                        version = sandbox.profile.id,
                        code = it.errorCode ?: DiagnosticCode.COMMAND_ERROR,
                        message = it.errorMessage ?: it.errorCode?.name.orEmpty(),
                        command = it.command,
                        root = it.root,
                    )
                }
        }
        val expectedCode = diagnostic.manifestString("code")?.uppercase()
        val expectedCommand = diagnostic.manifestString("command")
        val expectedRoot = diagnostic.manifestString("root")
        val expectedMessage = diagnostic.manifestString("message")
        val expectedContains = diagnostic.manifestString("contains")
        val expectedVersion = diagnostic.manifestString("version")
        val expectedStep = diagnostic.get("step")?.asInt
        val matches = records.filter { record ->
            (expectedCode == null || record.code.name == expectedCode) &&
                (expectedCommand == null || record.command == expectedCommand) &&
                (expectedRoot == null || record.root == expectedRoot) &&
                (expectedMessage == null || record.message == expectedMessage) &&
                (expectedContains == null || expectedContains in record.message || expectedContains in (record.command ?: "")) &&
                (expectedVersion == null || record.version == expectedVersion) &&
                (expectedStep == null || record.step == expectedStep)
        }
        diagnostic.get("count")?.let { expected ->
            if (matches.size != expected.asInt) {
                return listOf("diagnostic ${describeDiagnosticExpectation(diagnostic)} expected count ${expected.asInt} but was ${matches.size}; ${actualDiagnostics(records)}")
            }
            return emptyList()
        }
        return if (matches.isEmpty()) {
            listOf("diagnostic ${describeDiagnosticExpectation(diagnostic)} did not match any recorded diagnostic; ${actualDiagnostics(records)}")
        } else {
            emptyList()
        }
    }

    private fun actualDiagnostics(records: List<ManifestDiagnostic>): String {
        if (records.isEmpty()) return "actual diagnostics: <none>"
        val rendered = records.take(5).joinToString("; ") { record ->
            listOfNotNull(
                record.step?.let { "step=$it" },
                "version=${record.version}",
                "code=${record.code.name}",
                record.root?.let { "root=$it" },
                record.command?.let { "command=${it.truncateForManifestFailure()}" },
                "message=${record.message.truncateForManifestFailure()}",
            ).joinToString(" ")
        }
        val suffix = if (records.size > 5) "; ... +${records.size - 5} more" else ""
        return "actual diagnostics: $rendered$suffix"
    }

    private fun describeDiagnosticExpectation(diagnostic: JsonObject): String =
        listOfNotNull(
            diagnostic.get("step")?.let { "step=${it.asInt}" },
            diagnostic.manifestString("version")?.let { "version=$it" },
            diagnostic.manifestString("code")?.let { "code=$it" },
            diagnostic.manifestString("command")?.let { "command=$it" },
            diagnostic.manifestString("root")?.let { "root=$it" },
            diagnostic.manifestString("message")?.let { "message=$it" },
            diagnostic.manifestString("contains")?.let { "contains=$it" },
        ).ifEmpty { listOf("<any diagnostic>") }.joinToString(", ")

    private fun String.truncateForManifestFailure(): String =
        replace("\r", "\\r")
            .replace("\n", "\\n")
            .let { if (it.length <= 160) it else it.take(157) + "..." }

    private fun evaluateWorldAssertion(world: JsonObject, sandbox: DatapackSandbox): List<String> {
        val failures = mutableListOf<String>()
        world.get("gameTime")?.let { if (sandbox.world.gameTime != it.asLong) failures += "world gameTime expected ${it.asLong} but was ${sandbox.world.gameTime}" }
        world.get("dayTime")?.let { if (sandbox.world.dayTime != it.asLong) failures += "world dayTime expected ${it.asLong} but was ${sandbox.world.dayTime}" }
        world.get("time")?.let { if (sandbox.world.dayTime != it.asLong) failures += "world dayTime expected ${it.asLong} but was ${sandbox.world.dayTime}" }
        world.get("seed")?.let { if (sandbox.world.seed != it.asLong) failures += "world seed expected ${it.asLong} but was ${sandbox.world.seed}" }
        world.manifestString("weather")?.let { if (sandbox.world.weather != it) failures += "world weather expected $it but was ${sandbox.world.weather}" }
        world.get("weatherDuration")?.let { if (sandbox.world.weatherDuration != it.asInt) failures += "world weatherDuration expected ${it.asInt} but was ${sandbox.world.weatherDuration}" }
        world.manifestString("difficulty")?.let { if (sandbox.world.difficulty != it) failures += "world difficulty expected $it but was ${sandbox.world.difficulty}" }
        (world.manifestString("defaultGameMode") ?: world.manifestString("defaultGamemode"))?.let {
            if (sandbox.world.defaultGameMode != it) failures += "world defaultGameMode expected $it but was ${sandbox.world.defaultGameMode}"
        }
        world.getAsJsonObject("randomSequences")?.entrySet()?.forEach { (name, expected) ->
            val actual = sandbox.world.randomSequences[name]
            if (actual != expected.asLong) failures += "world random sequence $name expected ${expected.asLong} but was ${actual ?: "<missing>"}"
        }
        world.getAsJsonArray("forcedChunk")?.let {
            val chunk = parseManifestChunk(it, "world assertion forcedChunk")
            if (chunk !in sandbox.world.forcedChunks) failures += "world expected forced chunk ${chunk.x},${chunk.z}"
        }
        world.getAsJsonObject("biome")?.let { biome ->
            val pos = parseManifestBlockPos(biome.getAsJsonArray("pos"), "world assertion biome pos")
            val expected = ResourceLocation.parse(biome.requiredManifestString("id"))
            val actual = sandbox.world.biomes[pos]
            if (actual != expected) failures += "world biome $pos expected $expected but was ${actual ?: "<missing>"}"
        }
        world.getAsJsonObject("worldSpawn")?.let { spawn ->
            val expected = spawn.getAsJsonArray("pos")?.let { parseManifestPosition(it) }
                ?: spawn.getAsJsonArray("position")?.let { parseManifestPosition(it) }
            if (expected != null && sandbox.world.worldSpawn.position != expected) failures += "world spawn position expected $expected but was ${sandbox.world.worldSpawn.position}"
            spawn.manifestString("dimension")?.let { if (sandbox.world.worldSpawn.dimension != ResourceLocation.parse(it)) failures += "world spawn dimension expected $it but was ${sandbox.world.worldSpawn.dimension}" }
            spawn.get("angle")?.let {
                if (sandbox.world.worldSpawn.angle != it.asDouble) {
                    failures += "world spawn angle expected ${it.asDouble} but was ${sandbox.world.worldSpawn.angle}"
                }
            }
            spawn.get("forced")?.let {
                if (sandbox.world.worldSpawn.forced != it.asBoolean) {
                    failures += "world spawn forced expected ${it.asBoolean} but was ${sandbox.world.worldSpawn.forced}"
                }
            }
        }
        world.getAsJsonObject("worldBorder")?.let { failures += evaluateWorldBorderAssertion(it, sandbox.world.worldBorder) }
        return failures
    }

    private fun evaluateWorldBorderAssertion(border: JsonObject, actual: SandboxWorldBorder): List<String> {
        val failures = mutableListOf<String>()
        border.get("centerX")?.asDouble?.let { if (actual.centerX != it) failures += "world border centerX expected $it but was ${actual.centerX}" }
        border.get("centerZ")?.asDouble?.let { if (actual.centerZ != it) failures += "world border centerZ expected $it but was ${actual.centerZ}" }
        border.get("size")?.asDouble?.let { if (actual.size != it) failures += "world border size expected $it but was ${actual.size}" }
        border.get("targetSize")?.asDouble?.let { if (actual.targetSize != it) failures += "world border targetSize expected $it but was ${actual.targetSize}" }
        border.get("lerpTimeSeconds")?.asLong?.let { if (actual.lerpTimeSeconds != it) failures += "world border lerpTimeSeconds expected $it but was ${actual.lerpTimeSeconds}" }
        border.get("damageBuffer")?.asDouble?.let { if (actual.damageBuffer != it) failures += "world border damageBuffer expected $it but was ${actual.damageBuffer}" }
        border.get("damageAmount")?.asDouble?.let { if (actual.damageAmount != it) failures += "world border damageAmount expected $it but was ${actual.damageAmount}" }
        border.get("warningDistance")?.asInt?.let { if (actual.warningDistance != it) failures += "world border warningDistance expected $it but was ${actual.warningDistance}" }
        border.get("warningTime")?.asInt?.let { if (actual.warningTime != it) failures += "world border warningTime expected $it but was ${actual.warningTime}" }
        return failures
    }

    private fun evaluateTeamAssertion(team: JsonObject, sandbox: DatapackSandbox): List<String> {
        val name = team.requiredManifestString("name")
        val actual = sandbox.world.teams[name]
        val exists = team.get("exists")?.asBoolean ?: true
        if (!exists) return if (actual == null) emptyList() else listOf("team $name expected missing but exists")
        if (actual == null) return listOf("team $name expected to exist")
        val failures = mutableListOf<String>()
        team.manifestString("displayName")?.let { if (actual.displayName != it) failures += "team $name displayName expected $it but was ${actual.displayName}" }
        team.manifestString("member")?.let { if (it !in actual.members) failures += "team $name expected member $it" }
        team.get("memberCount")?.let { if (actual.members.size != it.asInt) failures += "team $name memberCount expected ${it.asInt} but was ${actual.members.size}" }
        team.getAsJsonObject("option")?.let { option ->
            val key = option.requiredManifestString("name")
            val expected = option.requiredManifestString("equals")
            val actualValue = actual.options[key]
            if (actualValue != expected) failures += "team $name option $key expected $expected but was ${actualValue ?: "<missing>"}"
        }
        return failures
    }

    private fun evaluateBossbarAssertion(bossbar: JsonObject, sandbox: DatapackSandbox): List<String> {
        val id = ResourceLocation.parse(bossbar.requiredManifestString("id"))
        val actual = sandbox.world.bossbars[id]
        val exists = bossbar.get("exists")?.asBoolean ?: true
        if (!exists) return if (actual == null) emptyList() else listOf("bossbar $id expected missing but exists")
        if (actual == null) return listOf("bossbar $id expected to exist")
        val failures = mutableListOf<String>()
        bossbar.manifestString("name")?.let { if (actual.name != it) failures += "bossbar $id name expected $it but was ${actual.name}" }
        bossbar.get("value")?.let { if (actual.value != it.asInt) failures += "bossbar $id value expected ${it.asInt} but was ${actual.value}" }
        bossbar.get("max")?.let { if (actual.max != it.asInt) failures += "bossbar $id max expected ${it.asInt} but was ${actual.max}" }
        bossbar.manifestString("color")?.let { if (actual.color != it) failures += "bossbar $id color expected $it but was ${actual.color}" }
        bossbar.manifestString("style")?.let { if (actual.style != it) failures += "bossbar $id style expected $it but was ${actual.style}" }
        bossbar.get("visible")?.let { if (actual.visible != it.asBoolean) failures += "bossbar $id visible expected ${it.asBoolean} but was ${actual.visible}" }
        bossbar.manifestString("player")?.let { if (it !in actual.players) failures += "bossbar $id expected player $it" }
        return failures
    }

    private fun evaluateEntityAssertion(entity: JsonObject, sandbox: DatapackSandbox): List<String> {
        val matches = matchingManifestEntities(entity, sandbox)
        val failures = mutableListOf<String>()
        entity.get("count")?.let { expected ->
            if (matches.size != expected.asInt) {
                failures += "entity ${describeEntityExpectation(entity)} expected ${expected.asInt} match(es) but found ${matches.size}"
            }
        }

        val exists = entity.get("exists")?.asBoolean
        if (exists == false) {
            if (matches.isNotEmpty()) failures += "entity ${describeEntityExpectation(entity)} expected missing but found ${matches.size} match(es)"
            return failures
        }
        val needsEntity = exists == true ||
            entity.get("count") == null ||
            entity.has("equipment") ||
            entity.has("effect") ||
            entity.has("effects") ||
            entity.has("attribute")
        if (matches.isEmpty() && needsEntity) {
            failures += "entity ${describeEntityExpectation(entity)} expected to exist"
            return failures
        }

        entity.getAsJsonObject("equipment")?.let { failures += evaluateEntityEquipmentAssertion(it, matches, entity) }
        entity.get("effect")?.let { failures += evaluateEntityEffectAssertion(it, matches, entity) }
        entity.manifestArray("effects", "entity assertion effects").forEach {
            failures += evaluateEntityEffectAssertion(it, matches, entity)
        }
        entity.getAsJsonObject("attribute")?.let { failures += evaluateEntityAttributeAssertion(it, matches, entity) }
        return failures
    }

    private fun matchingManifestEntities(entity: JsonObject, sandbox: DatapackSandbox): List<SandboxEntity> {
        val expectedType = entity.manifestString("type")?.let(ResourceLocation::parse)
        val expectedPosition = entity.getAsJsonArray("pos")?.let { parseManifestPosition(it) }
            ?: entity.getAsJsonArray("position")?.let { parseManifestPosition(it) }
        val expectedDimension = entity.manifestString("dimension")?.let(ResourceLocation::parse)
        val expectedHealth = entity.get("health")?.asDouble
        val expectedUuid = entity.manifestString("uuid")
        val expectedTag = entity.manifestString("tag")
        val expectedVehicle = entity.manifestString("vehicle")
        val expectedPassenger = entity.manifestString("passenger")
        val expectedPassengerCount = entity.get("passengerCount")?.asInt
        val expectedNbt = entity.getAsJsonObject("nbt")
        return sandbox.world.entities.filter { sandboxEntity ->
            (expectedType == null || sandboxEntity.type == expectedType) &&
                (expectedTag == null || expectedTag in sandboxEntity.tags) &&
                (expectedUuid == null || sandboxEntity.uuid == expectedUuid) &&
                (expectedPosition == null || sandboxEntity.position == expectedPosition) &&
                (expectedDimension == null || sandboxEntity.dimension == expectedDimension) &&
                (expectedHealth == null || manifestEntityHealth(sandboxEntity, sandbox) == expectedHealth) &&
                (expectedVehicle == null || sandboxEntity.vehicle == expectedVehicle) &&
                (expectedPassenger == null || expectedPassenger in sandboxEntity.passengers) &&
                (expectedPassengerCount == null || sandboxEntity.passengers.size == expectedPassengerCount) &&
                entityNbtMatches(sandboxEntity, sandbox, expectedNbt)
        }
    }

    private fun manifestEntityHealth(entity: SandboxEntity, sandbox: DatapackSandbox): Double? =
        entity.fullNbt(sandbox.profile).get("Health")?.takeIf { it.isJsonPrimitive }?.asDouble

    private fun entityNbtMatches(entity: SandboxEntity, sandbox: DatapackSandbox, expectation: JsonObject?): Boolean =
        itemPathMatches(entity.fullNbt(sandbox.profile), expectation)

    private fun evaluateEntityEquipmentAssertion(equipment: JsonObject, entities: List<SandboxEntity>, entity: JsonObject): List<String> {
        val slot = equipment.requiredManifestString("slot")
        val candidates = entities.mapNotNull { it.equipment[slot] }
        val matches = candidates.filter { itemMatches(it, equipment) }
        val exists = equipment.get("exists")?.asBoolean ?: true
        if (exists && matches.isEmpty()) {
            return listOf("entity ${describeEntityExpectation(entity)} equipment $slot expected ${describeItemExpectation(equipment)} but found ${candidates.map { "${it.id}x${it.count}" }}")
        }
        if (!exists && matches.isNotEmpty()) {
            return listOf("entity ${describeEntityExpectation(entity)} equipment $slot expected missing ${describeItemExpectation(equipment)} but found ${matches.map { "${it.id}x${it.count}" }}")
        }
        return emptyList()
    }

    private fun evaluateEntityEffectAssertion(effect: JsonElement, entities: List<SandboxEntity>, entity: JsonObject): List<String> {
        val expectation = parseEffectExpectation(effect, "entity assertion effect")
        val candidates = entities.mapNotNull { it.activeEffects[expectation.id] }
        val matches = candidates.filter { active ->
            (expectation.durationTicks == null || active.durationTicks == expectation.durationTicks) &&
                (expectation.amplifier == null || active.amplifier == expectation.amplifier) &&
                (expectation.hideParticles == null || active.hideParticles == expectation.hideParticles)
        }
        if (expectation.exists && matches.isEmpty()) {
            return listOf("entity ${describeEntityExpectation(entity)} expected effect ${describeEffectExpectation(expectation)} but found ${candidates.map { it.toJson() }}")
        }
        if (!expectation.exists && matches.isNotEmpty()) {
            return listOf("entity ${describeEntityExpectation(entity)} expected missing effect ${describeEffectExpectation(expectation)} but found ${matches.map { it.toJson() }}")
        }
        return emptyList()
    }

    private fun parseEffectExpectation(effect: JsonElement, label: String): ManifestEffectExpectation =
        when {
            effect.isJsonPrimitive && effect.asJsonPrimitive.isString ->
                ManifestEffectExpectation(ResourceLocation.parse(effect.asString))
            effect.isJsonObject -> {
                val root = effect.asJsonObject
                ManifestEffectExpectation(
                    id = ResourceLocation.parse(root.requiredManifestString("id")),
                    durationTicks = root.get("durationTicks")?.asInt ?: root.get("duration")?.asInt,
                    amplifier = root.get("amplifier")?.asInt,
                    hideParticles = root.get("hideParticles")?.asBoolean,
                    exists = root.get("exists")?.asBoolean ?: true,
                )
            }
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must be a string or object")
        }

    private data class ManifestEffectExpectation(
        val id: ResourceLocation,
        val durationTicks: Int? = null,
        val amplifier: Int? = null,
        val hideParticles: Boolean? = null,
        val exists: Boolean = true,
    )

    private fun evaluateEntityAttributeAssertion(attribute: JsonObject, entities: List<SandboxEntity>, entity: JsonObject): List<String> {
        val id = ResourceLocation.parse(attribute.requiredManifestString("id"))
        val expected = attribute.get("equals")?.asDouble ?: attribute.get("value")?.asDouble
        val min = attribute.get("min")?.asDouble
        val max = attribute.get("max")?.asDouble
        val exists = attribute.get("exists")?.asBoolean ?: true
        val candidates = entities.mapNotNull { it.attributes[id] }
        val matches = candidates.filter { actual ->
            (expected == null || actual == expected) &&
                (min == null || actual >= min) &&
                (max == null || actual <= max)
        }
        if (exists && matches.isEmpty()) {
            return listOf("entity ${describeEntityExpectation(entity)} expected attribute ${describeAttributeExpectation(id, expected, min, max)} but found $candidates")
        }
        if (!exists && matches.isNotEmpty()) {
            return listOf("entity ${describeEntityExpectation(entity)} expected missing attribute ${describeAttributeExpectation(id, expected, min, max)} but found $matches")
        }
        return emptyList()
    }

    private fun evaluateItemAssertion(item: JsonObject, sandbox: DatapackSandbox): List<String> {
        val player = sandbox.world.requirePlayer(item.requiredManifestString("player"))
        val container = normalizeItemContainer(item.manifestString("container") ?: "inventory")
            ?: return listOf("item for player ${player.name} container ${item.manifestString("container")} is unsupported; use inventory or enderItems")
        val items = playerItems(player, container)
        val slot = item.get("slot")?.asInt
        val candidates = slot?.let { items.getOrNull(it)?.let(::listOf) ?: emptyList() } ?: items
        val exists = item.get("exists")?.asBoolean ?: true
        val matches = candidates.filter { stack -> itemMatches(stack, item) }
        val prefix = itemAssertionPrefix(player.name, container)
        if (exists && matches.isEmpty()) {
            return listOf("$prefix expected ${describeItemExpectation(item)} but ${itemContainerLabel(container)} was ${items.map { "${it.id}x${it.count}" }}")
        }
        if (!exists && matches.isNotEmpty()) {
            return listOf("$prefix expected missing ${describeItemExpectation(item)} but found ${matches.map { "${it.id}x${it.count}" }}")
        }
        return emptyList()
    }

    private fun normalizeItemContainer(raw: String): String? =
        when (raw) {
            "inventory" -> "inventory"
            "enderItems", "ender", "ender_items", "enderChest", "ender_chest" -> "enderItems"
            else -> null
        }

    private fun playerItems(player: SandboxPlayer, container: String): List<ItemStack> =
        when (container) {
            "inventory" -> player.inventory
            "enderItems" -> player.enderItems
            else -> emptyList()
        }

    private fun itemAssertionPrefix(playerName: String, container: String): String =
        if (container == "inventory") "item for player $playerName" else "item for player $playerName in $container"

    private fun itemContainerLabel(container: String): String =
        if (container == "inventory") "inventory" else container

    private fun itemMatches(stack: ItemStack, item: JsonObject): Boolean {
        val expectedId = item.manifestString("id")?.let(ResourceLocation::parse)
        val expectedCount = item.get("count")?.asInt
        val minCount = item.get("minCount")?.asInt
        val maxCount = item.get("maxCount")?.asInt
        return (expectedId == null || stack.id == expectedId) &&
            (expectedCount == null || stack.count == expectedCount) &&
            (minCount == null || stack.count >= minCount) &&
            (maxCount == null || stack.count <= maxCount) &&
            itemPathMatches(stack.components, item.getAsJsonObject("components")) &&
            itemPathMatches(stack.nbt, item.getAsJsonObject("nbt"))
    }

    private fun itemPathMatches(root: JsonObject, expectation: JsonObject?): Boolean {
        if (expectation == null) return true
        val path = expectation.manifestString("path")
        val actual = JsonPaths.get(root, path)
        return pathExpectationFailure("path ${path ?: "<root>"}", actual, expectation) == null
    }

    private fun describeItemExpectation(item: JsonObject): String =
        listOfNotNull(
            item.manifestString("id")?.let { "id=$it" },
            item.get("count")?.let { "count=${it.asInt}" },
            item.get("minCount")?.let { "minCount=${it.asInt}" },
            item.get("maxCount")?.let { "maxCount=${it.asInt}" },
            item.get("slot")?.let { "slot=${manifestPrimitiveString(it)}" },
            item.manifestString("container")?.takeIf { normalizeItemContainer(it) != "inventory" }?.let { "container=$it" },
        ).ifEmpty { listOf("<any item>") }.joinToString(", ")

    private fun describeEntityExpectation(entity: JsonObject): String =
        listOfNotNull(
            entity.manifestString("type")?.let { "type=$it" },
            entity.manifestString("tag")?.let { "tag=$it" },
            entity.manifestString("uuid")?.let { "uuid=$it" },
            (entity.getAsJsonArray("pos") ?: entity.getAsJsonArray("position"))?.let { "position=${parseManifestPosition(it)}" },
            entity.manifestString("dimension")?.let { "dimension=$it" },
            entity.get("health")?.let { "health=${it.asDouble}" },
            entity.manifestString("vehicle")?.let { "vehicle=$it" },
            entity.manifestString("passenger")?.let { "passenger=$it" },
            entity.get("passengerCount")?.let { "passengerCount=${it.asInt}" },
            entity.getAsJsonObject("nbt")?.let { describePathExpectation("nbt", it) },
        ).ifEmpty { listOf("<any entity>") }.joinToString(", ")

    private fun describePathExpectation(label: String, expectation: JsonObject): String =
        listOfNotNull(
            expectation.manifestString("path")?.let { "$label.path=$it" },
            expectation.get("equals")?.let { "$label.equals=${JsonValues.render(it)}" },
            expectation.get("exists")?.let { "$label.exists=${it.asBoolean}" },
            expectation.get("missing")?.let { "$label.missing=${it.asBoolean}" },
            expectation.manifestString("contains")?.let { "$label.contains=$it" },
            expectation.manifestString("matches")?.let { "$label.matches=$it" },
        ).joinToString(", ")

    private fun describeEntityCountExpectation(entity: JsonObject): String =
        listOfNotNull(
            entity.manifestString("type")?.let { "type=$it" },
            entity.manifestString("tag")?.let { "tag=$it" },
            entity.manifestString("dimension")?.let { "dimension=$it" },
        ).ifEmpty { listOf("<any entity>") }.joinToString(", ")

    private fun describeEffectExpectation(effect: ManifestEffectExpectation): String =
        listOfNotNull(
            "id=${effect.id}",
            effect.durationTicks?.let { "durationTicks=$it" },
            effect.amplifier?.let { "amplifier=$it" },
            effect.hideParticles?.let { "hideParticles=$it" },
        ).joinToString(", ")

    private fun describeAttributeExpectation(attribute: ResourceLocation, value: Double?, min: Double?, max: Double?): String =
        listOfNotNull(
            "id=$attribute",
            value?.let { "value=$it" },
            min?.let { "min=$it" },
            max?.let { "max=$it" },
        ).joinToString(", ")

    private fun evaluateStorageAssertion(storage: JsonObject, sandbox: DatapackSandbox): List<String> {
        val id = ResourceLocation.parse(storage.requiredManifestString("id"))
        val path = storage.manifestString("path")
        val actual = sandbox.world.storages[id]?.let { root -> JsonPaths.get(root, path) }
        val label = "storage $id ${path ?: "<root>"}"
        return pathExpectationFailure(label, actual, storage)?.let(::listOf).orEmpty()
    }

    private fun pathExpectationFailure(label: String, actual: JsonElement?, expectation: JsonObject): String? {
        var checked = false
        expectation.get("equals")?.let { expected ->
            checked = true
            if (actual != expected) {
                return "$label expected ${JsonValues.render(expected)} but was ${actual?.let(JsonValues::render) ?: "<missing>"}"
            }
        }
        expectation.get("exists")?.let { expected ->
            checked = true
            val exists = actual != null
            if (exists != expected.asBoolean) {
                return "$label exists expected ${expected.asBoolean} but was $exists"
            }
        }
        expectation.get("missing")?.let { expected ->
            checked = true
            val shouldBeMissing = expected.asBoolean
            when {
                shouldBeMissing && actual != null -> return "$label expected missing but was ${JsonValues.render(actual)}"
                !shouldBeMissing && actual == null -> return "$label expected present but was <missing>"
            }
        }
        expectation.manifestString("contains")?.let { expected ->
            checked = true
            val actualText = actual?.pathExpectationText()
            if (actualText == null || expected !in actualText) {
                return "$label expected text containing '$expected' but was ${actual?.let(JsonValues::render) ?: "<missing>"}"
            }
        }
        expectation.manifestString("matches")?.let { expected ->
            checked = true
            val actualText = actual?.pathExpectationText()
            val regex = try {
                Regex(expected)
            } catch (error: IllegalArgumentException) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Path expectation 'matches' must be a valid regex", cause = error)
            }
            if (actualText == null || !regex.containsMatchIn(actualText)) {
                return "$label expected text matching '$expected' but was ${actual?.let(JsonValues::render) ?: "<missing>"}"
            }
        }
        return if (checked) null else "$label assertion requires equals, exists, missing, contains, or matches"
    }

    private fun JsonElement.pathExpectationText(): String =
        if (isJsonPrimitive && asJsonPrimitive.isString) asString else JsonValues.render(this)

    private fun evaluateTraceAssertion(trace: JsonObject, sandbox: DatapackSandbox): List<String> {
        return TraceAssertions.failures(
            traces = sandbox.world.traces,
            expectation = TraceExpectation(
                command = trace.manifestString("command"),
                root = trace.manifestString("root"),
                contains = trace.manifestString("contains"),
                success = trace.get("success")?.asBoolean,
                fileContains = trace.manifestString("fileContains"),
                function = trace.manifestString("function"),
                count = trace.get("count")?.asInt,
                outputs = trace.get("outputs")?.asInt,
                outputContains = trace.manifestString("outputContains")
                    ?: trace.manifestString("output"),
                outputTarget = trace.manifestString("outputTarget")
                    ?: trace.manifestString("target"),
                hasDiff = trace.get("hasDiff")?.asBoolean,
                diffPath = trace.manifestString("diffPath"),
                diffKind = trace.manifestString("diffKind")?.let(::parseTraceDiffKind),
                diffContains = trace.manifestString("diffContains"),
            ),
        )
    }

    private fun parseTraceDiffKind(value: String): SnapshotDiffKind =
        runCatching { SnapshotDiffKind.valueOf(value.uppercase()) }.getOrElse {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Trace assertion diffKind must be added, removed, or changed")
        }

    private fun evaluateEventTraceAssertion(trace: JsonObject, sandbox: DatapackSandbox): List<String> {
        return PlayerEventTraceAssertions.failures(
            events = sandbox.world.playerEventTraces,
            expectation = PlayerEventTraceExpectation(
                player = trace.manifestString("player"),
                type = trace.manifestString("type"),
                success = trace.get("success")?.asBoolean,
                advancement = trace.manifestString("advancement")?.let(ResourceLocation::parse),
                criterion = trace.manifestString("criterion"),
                failedAdvancement = trace.manifestString("failedAdvancement")?.let(ResourceLocation::parse),
                failedCriterion = trace.manifestString("failedCriterion"),
                failureContains = trace.manifestString("failureContains")
                    ?: trace.manifestString("failureReasonContains"),
                item = trace.manifestString("item")?.let(ResourceLocation::parse),
                entity = trace.manifestString("entity")?.let(ResourceLocation::parse),
                block = trace.manifestString("block")?.let(ResourceLocation::parse),
                blockX = trace.get("blockX")?.asInt,
                blockY = trace.get("blockY")?.asInt,
                blockZ = trace.get("blockZ")?.asInt,
                recipe = trace.manifestString("recipe")?.let(ResourceLocation::parse),
                fromDimension = trace.manifestString("from")?.let(ResourceLocation::parse),
                toDimension = trace.manifestString("to")?.let(ResourceLocation::parse),
                damageSource = (trace.manifestString("damageSource") ?: trace.manifestString("damageType"))
                    ?.let(ResourceLocation::parse),
                damageAmount = trace.get("damageAmount")?.asDouble
                    ?: trace.get("amount")?.asDouble
                    ?: trace.get("damage")?.asDouble,
                inputDevice = trace.manifestString("inputDevice") ?: trace.manifestString("device"),
                inputCode = trace.manifestString("inputCode")
                    ?: trace.manifestString("key")
                    ?: trace.manifestString("button")
                    ?: trace.manifestString("mouseButton"),
                inputAction = trace.manifestString("inputAction") ?: trace.manifestString("action"),
                count = trace.get("count")?.asInt,
            ),
        )
    }

    private fun parseEvent(event: JsonObject, sandbox: DatapackSandbox): PlayerEvent {
        val playerName = event.requiredManifestString("player")
        sandbox.createPlayer(playerName)
        val id = event.manifestString("item")?.let { ResourceLocation.parse(it) }
            ?: event.manifestString("entity")?.let { ResourceLocation.parse(it) }
            ?: event.manifestString("block")?.let { ResourceLocation.parse(it) }
            ?: event.manifestString("recipe")?.let { ResourceLocation.parse(it) }
        val damageSource = event.manifestString("damageSource")
            ?: event.manifestString("damageType")
        val damageAmount = event.get("amount")?.asDouble
            ?: event.get("damage")?.takeIf { it.isJsonPrimitive }?.asDouble
        return PlayerEvent(
            playerName = playerName,
            type = event.requiredManifestString("type").replace('-', '_'),
            item = event.manifestString("item")?.let { parseManifestItem(event) } ?: id?.takeIf { event.manifestString("item") != null }?.let { ItemStack(it) },
            entity = event.manifestString("entity")?.let { SandboxEntity(type = ResourceLocation.parse(it)) },
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
