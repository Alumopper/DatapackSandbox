package moe.afox.dpsandbox.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import moe.afox.dpsandbox.core.CommandTraceEvent
import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.DatapackSandbox
import moe.afox.dpsandbox.core.FunctionSource
import moe.afox.dpsandbox.core.JsonValues
import moe.afox.dpsandbox.core.OutputEvent
import moe.afox.dpsandbox.core.PlayerEvent
import moe.afox.dpsandbox.core.PlayerEventTraceEvent
import moe.afox.dpsandbox.core.PlayerEvents
import moe.afox.dpsandbox.core.ResourceLocation
import moe.afox.dpsandbox.core.SandboxException
import moe.afox.dpsandbox.core.SingleFunctionDatapack
import moe.afox.dpsandbox.core.SnapshotDiff
import moe.afox.dpsandbox.core.SourceLocation
import moe.afox.dpsandbox.core.UnsupportedFeatureMode
import moe.afox.dpsandbox.core.VersionProfileDiffs
import moe.afox.dpsandbox.core.VersionProfileDocs
import moe.afox.dpsandbox.core.VersionProfileJson
import moe.afox.dpsandbox.core.VersionProfiles
import moe.afox.dpsandbox.core.createFunctionSandbox
import moe.afox.dpsandbox.core.createSandbox
import moe.afox.dpsandbox.core.toJson
import moe.afox.dpsandbox.core.toPlayerJson
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) = DatapackSandboxCli()
    .subcommands(
        ReplCommand(),
        CheckCommand(),
        RunCommand(),
        LootCommand(),
        AdvancementCommand(),
        EventCommand(),
        ManifestSchemaCommand(),
        VersionCommand(),
        CommandsCommand(),
    )
    .main(args)

class DatapackSandboxCli : CliktCommand(
    name = "datapack-sandbox",
) {
    override fun run() = Unit
}

class ReplCommand : CliktCommand(name = "repl") {
    private val version by option("--version", "-v").default(VersionProfiles.default.id)
    private val packs by option("--pack", "-p").path(mustExist = true).multiple(required = true)
    private val watch by option("--watch").flag(default = false)
    private val unsupported by option("--unsupported").default("warn")

    override fun run() {
        try {
            Repl(version, packs, watch, unsupportedFeatureMode = unsupportedFeatureMode(unsupported)).run()
        } catch (error: SandboxException) {
            println(ConsoleStyle.diagnostic(error.render()))
            exitProcess(ExitCodes.forException(error))
        }
    }
}

class CheckCommand : CliktCommand(name = "check") {
    private val inputs by argument("input").path(mustExist = true).multiple(required = true)
    private val failFast by option("--fail-fast").flag(default = false)
    private val verbose by option("--verbose").flag(default = false)
    private val snapshotOnFail by option("--snapshot-on-fail").flag(default = false)
    private val snapshotDiffOnFail by option("--snapshot-diff-on-fail").flag(default = false)
    private val validateSchema by option("--validate-schema").flag(default = false)
    private val failOnMissingResources by option("--fail-on-missing-resources").flag(default = false)
    private val strict by option("--strict").flag(default = false)
    private val trace by option("--trace").flag(default = false)
    private val traceFile by option("--trace-file").path()
    private val eventTraceFile by option("--event-trace-file").path()
    private val traceFilters by option("--trace-filter").multiple()
    private val outputsFile by option("--outputs-file").path()
    private val reportFile by option("--report-file").path()
    private val seed by option("--seed").long().default(0)
    private val unsupported by option("--unsupported").default("warn")

    override fun run() {
        try {
            val manifests = ManifestRunner.discover(inputs)
            if (validateSchema || strict) {
                val schemaFailures = manifests.flatMap(ManifestSchemaValidator::validateFileTree)
                if (schemaFailures.isNotEmpty()) {
                    throw SandboxException(
                        DiagnosticCode.INPUT_FORMAT,
                        "Manifest schema validation failed:${System.lineSeparator()}${schemaFailures.joinToString(System.lineSeparator()) { "- $it" }}",
                    )
                }
            }
            var failed = false
            val traces = mutableListOf<CommandTraceEvent>()
            val eventTraces = mutableListOf<PlayerEventTraceEvent>()
            val outputs = mutableListOf<OutputEvent>()
            val results = mutableListOf<ManifestResult>()
            manifests.forEach { manifest ->
                val result = ManifestRunner.run(
                    manifest,
                    ManifestOptions(
                        seed = seed,
                        verbose = verbose,
                        snapshotOnFail = snapshotOnFail,
                        snapshotDiffOnFail = snapshotDiffOnFail,
                        failOnMissingResources = failOnMissingResources || strict,
                        unsupportedFeatureMode = if (strict) UnsupportedFeatureMode.ERROR else unsupportedFeatureMode(unsupported),
                    ),
                )
                val resultTraces = TraceFilters.apply(result.traces, traceFilters)
                traces += resultTraces
                eventTraces += result.eventTraces
                outputs += result.outputs
                results += result
                val versionLabel = result.attempts
                    .map { it.version }
                    .takeIf { it.size > 1 }
                    ?.joinToString(prefix = " [", postfix = "]")
                    .orEmpty()
                if (result.passed) {
                    println(ConsoleStyle.green("PASS ${manifest}${versionLabel}"))
                } else {
                    failed = true
                    println(ConsoleStyle.red("FAIL ${manifest}${versionLabel}"))
                    result.messages.forEach { println("  - $it") }
                }
                if (verbose) {
                    result.attempts.forEach { attempt ->
                        attempt.resourceSummary?.let { ResourceSummaryRenderer.print(attempt.version, it) }
                    }
                    if (result.outputs.isNotEmpty()) {
                        OutputRenderer.print(result.outputs)
                    }
                }
                if (trace && resultTraces.isNotEmpty()) {
                    TraceRenderer.print(resultTraces)
                }
                if (!result.passed && failFast) {
                    traceFile?.let { writeTraceFile(it, traces) }
                    eventTraceFile?.let { writeEventTraceFile(it, eventTraces) }
                    outputsFile?.let { writeOutputsFile(it, outputs) }
                    reportFile?.let { writeManifestReportFile(it, results) }
                    exitProcess(ExitCodes.ASSERTION_FAILED)
                }
            }
            traceFile?.let { writeTraceFile(it, traces) }
            eventTraceFile?.let { writeEventTraceFile(it, eventTraces) }
            outputsFile?.let { writeOutputsFile(it, outputs) }
            reportFile?.let { writeManifestReportFile(it, results) }
            if (failed) exitProcess(ExitCodes.ASSERTION_FAILED)
        } catch (error: SandboxException) {
            println(ConsoleStyle.diagnostic(error.render()))
            exitProcess(ExitCodes.forException(error))
        }
    }

    private fun writeTraceFile(path: Path, traces: List<CommandTraceEvent>) {
        val content = traces.joinToString(separator = System.lineSeparator()) { event ->
            JsonValues.render(event.toJson())
        }
        Files.writeString(path, content, StandardCharsets.UTF_8)
        println(ConsoleStyle.green("trace written: $path"))
    }
}

private fun writeOutputsFile(path: Path, outputs: List<OutputEvent>) {
    val content = outputs.joinToString(separator = System.lineSeparator()) { event ->
        JsonValues.render(event.toJson())
    }
    Files.writeString(path, content, StandardCharsets.UTF_8)
    println(ConsoleStyle.green("outputs written: $path"))
}

private fun writeEventTraceFile(path: Path, events: List<PlayerEventTraceEvent>) {
    val content = events.joinToString(separator = System.lineSeparator()) { event ->
        JsonValues.render(event.toJson())
    }
    Files.writeString(path, content, StandardCharsets.UTF_8)
    println(ConsoleStyle.green("event trace written: $path"))
}

private fun writeRunReportFile(
    path: Path,
    sandbox: DatapackSandbox,
    commandsExecuted: Int,
    assertionFailures: List<String>,
    traces: List<CommandTraceEvent>,
    beforeSnapshot: JsonObject,
    resourceSummary: ManifestResourceSummary,
) {
    val json = JsonObject().also { report ->
        report.addProperty("version", sandbox.profile.id)
        report.addProperty("passed", assertionFailures.isEmpty())
        report.addProperty("gameTime", sandbox.world.gameTime)
        report.addProperty("commands", commandsExecuted)
        report.addProperty("entities", sandbox.world.entities.size)
        report.add("assertionFailures", stringArray(assertionFailures))
        report.add("outputs", JsonArray().also { array ->
            sandbox.world.outputs.forEach { array.add(it.toJson()) }
        })
        report.add("traces", JsonArray().also { array ->
            traces.forEach { array.add(it.toJson()) }
        })
        report.add("eventTraces", JsonArray().also { array ->
            sandbox.world.playerEventTraces.forEach { array.add(it.toJson()) }
        })
        val snapshot = sandbox.snapshotJson()
        report.add("snapshot", snapshot)
        report.add("snapshotDiffs", SnapshotDiff.toJson(SnapshotDiff.stateDiff(beforeSnapshot, snapshot)))
        report.add("resources", resourceSummary.toReportJson())
    }
    Files.writeString(path, JsonValues.render(json), StandardCharsets.UTF_8)
    println(ConsoleStyle.green("report written: $path"))
}

private fun writeManifestReportFile(path: Path, results: List<ManifestResult>) {
    val json = JsonArray()
    results.forEach { json.add(it.toReportJson()) }
    Files.writeString(path, JsonValues.render(json), StandardCharsets.UTF_8)
    println(ConsoleStyle.green("report written: $path"))
}

private fun ManifestResult.toReportJson(): JsonObject =
    JsonObject().also { json ->
        json.addProperty("path", path.toString())
        json.addProperty("passed", passed)
        json.add("messages", stringArray(messages))
        json.addProperty("outputCount", outputs.size)
        json.addProperty("traceCount", traces.size)
        json.addProperty("eventTraceCount", eventTraces.size)
        json.add("outputs", JsonArray().also { outputsJson ->
            outputs.forEach { outputsJson.add(it.toJson()) }
        })
        json.add("traces", JsonArray().also { tracesJson ->
            traces.forEach { tracesJson.add(it.toJson()) }
        })
        json.add("eventTraces", JsonArray().also { eventTracesJson ->
            eventTraces.forEach { eventTracesJson.add(it.toJson()) }
        })
        json.add("attempts", JsonArray().also { attemptsJson ->
            attempts.forEach { attemptsJson.add(it.toReportJson()) }
        })
    }

private fun ManifestAttemptResult.toReportJson(): JsonObject =
    JsonObject().also { json ->
        json.addProperty("version", version)
        json.add("packs", stringArray(packs.map { it.toString() }))
        json.addProperty("passed", passed)
        json.add("messages", stringArray(messages))
        json.addProperty("outputCount", outputs.size)
        json.addProperty("traceCount", traces.size)
        json.addProperty("eventTraceCount", eventTraces.size)
        json.add("outputs", JsonArray().also { outputsJson ->
            outputs.forEach { outputsJson.add(it.toJson()) }
        })
        json.add("traces", JsonArray().also { tracesJson ->
            traces.forEach { tracesJson.add(it.toJson()) }
        })
        json.add("eventTraces", JsonArray().also { eventTracesJson ->
            eventTraces.forEach { eventTracesJson.add(it.toJson()) }
        })
        snapshot?.let { json.add("snapshot", it.deepCopy()) }
        json.add("snapshotDiffs", SnapshotDiff.toJson(snapshotDiffs))
        resourceSummary?.let { json.add("resources", it.toReportJson()) }
    }

private fun ManifestResourceSummary.toReportJson(): JsonObject =
    JsonObject().also { json ->
        json.addProperty("functions", functions)
        json.addProperty("lootTables", lootTables)
        json.addProperty("predicates", predicates)
        json.addProperty("advancements", advancements)
        json.addProperty("recipes", recipes)
        json.addProperty("itemModifiers", itemModifiers)
        json.addProperty("tags", tags)
        json.addProperty("rawResourceKinds", rawResourceKinds)
        json.addProperty("rawResources", rawResources)
        json.addProperty("resourceIndex", resourceIndex)
        json.addProperty("activeResources", activeResources)
        json.addProperty("overriddenResources", overriddenResources)
        json.add("overlays", JsonArray().also { overlaysJson ->
            overlays.forEach { entry ->
                overlaysJson.add(
                    JsonObject().also { overlay ->
                        overlay.addProperty("type", entry.type)
                        overlay.addProperty("id", entry.id.toString())
                        overlay.addProperty("file", entry.file)
                        overlay.addProperty("pack", entry.pack)
                        overlay.addProperty("order", entry.order)
                        overlay.addProperty("behavior", entry.behaviorLevel.id)
                        overlay.addProperty("active", entry.active)
                        entry.overrides?.let { overlay.addProperty("overrides", it) }
                        entry.overriddenBy?.let { overlay.addProperty("overriddenBy", it) }
                    },
                )
            }
        })
        json.add("missingReferences", JsonArray().also { missingJson ->
            missingReferences.forEach { reference ->
                missingJson.add(
                    JsonObject().also { missing ->
                        missing.addProperty("source", reference.source)
                        missing.addProperty("type", reference.type)
                        missing.addProperty("id", reference.id.toString())
                    },
                )
            }
        })
    }

private fun stringArray(values: List<String>): JsonArray =
    JsonArray().also { array -> values.forEach(array::add) }

class RunCommand : CliktCommand(name = "run") {
    private val version by option("--version", "-v").default(VersionProfiles.default.id)
    private val packs by option("--pack", "-p").path(mustExist = true).multiple()
    private val mcfunctions by option("--mcfunction", "--function-file").multiple()
    private val mcfunctionTexts by option("--mcfunction-text", "--function-text").multiple()
    private val mcfunctionId by option("--mcfunction-id").default(SingleFunctionDatapack.DEFAULT_ID)
    private val stdin by option("--stdin").flag(default = false)
    private val stdinMode by option("--stdin-mode").default("function")
    private val worldFiles by option("--world").path(mustExist = true).multiple()
    private val seed by option("--seed").long()
    private val shouldLoad by option("--load").flag(default = false)
    private val ticks by option("--ticks").int().default(0)
    private val functions by option("--function", "-f").multiple()
    private val commands by option("--command", "-c").multiple()
    private val commandFiles by option("--command-file").path(mustExist = true).multiple()
    private val events by option("--event").multiple()
    private val eventFiles by option("--event-file").path(mustExist = true).multiple()
    private val assertions by option("--assert").multiple()
    private val assertionFiles by option("--assert-file").path(mustExist = true).multiple()
    private val failOnMissingResources by option("--fail-on-missing-resources").flag(default = false)
    private val snapshot by option("--snapshot").flag(default = false)
    private val snapshotFile by option("--snapshot-file").path()
    private val snapshotDiff by option("--snapshot-diff").flag(default = false)
    private val snapshotDiffFile by option("--snapshot-diff-file").path()
    private val trace by option("--trace").flag(default = false)
    private val traceFile by option("--trace-file").path()
    private val eventTraceFile by option("--event-trace-file").path()
    private val traceFilters by option("--trace-filter").multiple()
    private val outputsFile by option("--outputs-file").path()
    private val reportFile by option("--report-file").path()
    private val resources by option("--resources").flag(default = false)
    private val strict by option("--strict").flag(default = false)
    private val unsupported by option("--unsupported").default("warn")

    override fun run() {
        try {
            val effectiveUnsupportedMode = if (strict) UnsupportedFeatureMode.ERROR else unsupportedFeatureMode(unsupported)
            val stdinText = if (stdin) String(System.`in`.readAllBytes(), StandardCharsets.UTF_8) else null
            val stdinAsFunction = stdinText?.takeIf { stdinMode == "function" }
            val stdinAsCommands = stdinText?.takeIf { stdinMode == "commands" }
            if (stdin && stdinAsFunction == null && stdinAsCommands == null) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "--stdin-mode must be function or commands")
            }
            val functionSources = parseFunctionSources(stdinAsFunction)
            val canUseEmptySandbox = commands.isNotEmpty() ||
                commandFiles.isNotEmpty() ||
                stdinAsCommands != null ||
                events.isNotEmpty() ||
                eventFiles.isNotEmpty() ||
                worldFiles.isNotEmpty() ||
                assertions.isNotEmpty() ||
                assertionFiles.isNotEmpty() ||
                snapshot ||
                snapshotFile != null ||
                snapshotDiff ||
                snapshotDiffFile != null ||
                trace ||
                traceFile != null ||
                eventTraceFile != null ||
                outputsFile != null ||
                reportFile != null ||
                resources
            val sandbox = when {
                functionSources.isNotEmpty() -> {
                    createFunctionSandbox(
                        version = version,
                        packs = packs,
                        functionSources = functionSources,
                        unsupportedFeatureMode = effectiveUnsupportedMode,
                    )
                }
                packs.isNotEmpty() -> createSandbox(version, packs, unsupportedFeatureMode = effectiveUnsupportedMode)
                canUseEmptySandbox -> createFunctionSandbox(
                    version = version,
                    functionSources = listOf(FunctionSource.text(mcfunctionId, "", "<empty:$mcfunctionId>")),
                    unsupportedFeatureMode = effectiveUnsupportedMode,
                )
                else -> throw SandboxException(
                    DiagnosticCode.INPUT_FORMAT,
                    "run requires at least one --pack path, --mcfunction file, --mcfunction-text string, --stdin, --command, --command-file, --event, --event-file, --world, --assert, or output/snapshot/trace option",
                )
            }
            applyWorldFixtures(sandbox)
            seed?.let { sandbox.world.seed = it }
            val beforeSnapshot = sandbox.snapshotJson()
            var total = 0
            if (functionSources.isNotEmpty()) total += sandbox.runFunction(mcfunctionId).commandsExecuted
            if (shouldLoad) total += sandbox.runLoad().commandsExecuted
            if (ticks > 0) total += sandbox.runTicks(ticks).commandsExecuted
            functions.forEach { total += sandbox.runFunction(it).commandsExecuted }
            commandFiles.forEach { file ->
                total += executeCommandLines(sandbox, Files.readAllLines(file, StandardCharsets.UTF_8), file.toString())
            }
            stdinAsCommands?.let {
                total += executeCommandLines(sandbox, it.lines(), "<stdin>")
            }
            commands.forEachIndexed { index, command ->
                val normalized = command.trim().removePrefix("/")
                total += sandbox.executeCommand(
                    normalized,
                    SourceLocation(file = "<arg:--command>", line = index + 1, command = normalized),
                ).commandsExecuted
            }
            eventFiles.forEach { file ->
                applyPlayerEventLines(sandbox, Files.readAllLines(file, StandardCharsets.UTF_8), "--event-file $file")
            }
            events.forEachIndexed { index, raw ->
                applyPlayerEvent(sandbox, parsePlayerEventText(raw, "--event ${index + 1}"))
            }
            val traces = TraceFilters.apply(sandbox.world.traces, traceFilters)
            OutputRenderer.print(sandbox.world.outputs)
            if (trace) TraceRenderer.print(traces)
            if (snapshot) println(sandbox.snapshotString())
            snapshotFile?.let {
                Files.writeString(it, sandbox.snapshotString(), StandardCharsets.UTF_8)
                println(ConsoleStyle.green("snapshot written: $it"))
            }
            val diff = if (snapshotDiff || snapshotDiffFile != null) SnapshotDiff.stateDiff(beforeSnapshot, sandbox.snapshotJson()) else emptyList()
            if (snapshotDiff) println(SnapshotDiff.render(diff))
            snapshotDiffFile?.let {
                Files.writeString(it, JsonValues.render(SnapshotDiff.toJson(diff)), StandardCharsets.UTF_8)
                println(ConsoleStyle.green("snapshot diff written: $it"))
            }
            traceFile?.let {
                val content = traces.joinToString(separator = System.lineSeparator()) { event ->
                    JsonValues.render(event.toJson())
                }
                Files.writeString(it, content, StandardCharsets.UTF_8)
                println(ConsoleStyle.green("trace written: $it"))
            }
            eventTraceFile?.let {
                writeEventTraceFile(it, sandbox.world.playerEventTraces)
            }
            outputsFile?.let { writeOutputsFile(it, sandbox.world.outputs) }
            val resourceSummary = ManifestRunner.summarizeResources(sandbox)
            if (resources) {
                ResourceSummaryRenderer.print(sandbox.profile.id, resourceSummary)
            }
            val assertionFailures = ManifestRunner.evaluateAssertions(parseAssertions(), sandbox, beforeSnapshot) +
                if (failOnMissingResources || strict) {
                    ManifestRunner.missingResourceFailures(resourceSummary)
                } else {
                    emptyList()
                }
            reportFile?.let { writeRunReportFile(it, sandbox, total, assertionFailures, traces, beforeSnapshot, resourceSummary) }
            if (assertionFailures.isNotEmpty()) {
                assertionFailures.forEach { println(ConsoleStyle.red(it)) }
                exitProcess(ExitCodes.ASSERTION_FAILED)
            }
            println(ConsoleStyle.green("OK version=${sandbox.profile.id} gameTime=${sandbox.world.gameTime} commands=$total entities=${sandbox.world.entities.size}"))
        } catch (error: SandboxException) {
            println(ConsoleStyle.diagnostic(error.render()))
            exitProcess(ExitCodes.forException(error))
        }
    }

    private fun parseFunctionSources(stdinFunctionText: String?): List<FunctionSource> {
        val total = mcfunctions.size + mcfunctionTexts.size + if (stdinFunctionText != null) 1 else 0
        val fileSources = mcfunctions.mapIndexed { index, raw ->
            val (explicitId, value) = parseFunctionSourceSpec(raw)
            FunctionSource.file(explicitId ?: implicitFunctionId(index, total, value), Path.of(value))
        }
        val textSources = mcfunctionTexts.mapIndexed { textIndex, raw ->
            val index = mcfunctions.size + textIndex
            val (explicitId, value) = parseFunctionSourceSpec(raw)
            FunctionSource.text(
                explicitId ?: implicitFunctionId(index, total, "inline"),
                value,
                explicitId?.let { "<string:$it>" } ?: "<string:${implicitFunctionId(index, total, "inline")}>",
            )
        }
        val stdinSource = stdinFunctionText?.let {
            FunctionSource.text(
                id = if (total <= 1) mcfunctionId else "sandbox:stdin",
                content = it,
                sourceName = "<stdin>",
            )
        }
        return fileSources + textSources + listOfNotNull(stdinSource)
    }

    private fun implicitFunctionId(index: Int, total: Int, value: String): String =
        when {
            total <= 1 -> mcfunctionId
            index == 0 -> mcfunctionId
            value != "inline" -> "sandbox:${Path.of(value).fileName.toString().removeSuffix(".mcfunction").sanitizeFunctionPath()}"
            else -> throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "Multiple --mcfunction-text values require explicit ids using <namespace:path>=<content>",
            )
        }

    private fun parseFunctionSourceSpec(raw: String): Pair<String?, String> {
        val splitAt = raw.indexOf('=')
        if (splitAt <= 0) return null to raw
        val candidate = raw.substring(0, splitAt)
        val value = raw.substring(splitAt + 1)
        return try {
            ResourceLocation.parse(candidate)
            candidate to value
        } catch (_: SandboxException) {
            null to raw
        }
    }

    private fun applyWorldFixtures(sandbox: DatapackSandbox) {
        worldFiles.forEach { file ->
            val root = parseJsonObject(Files.readString(file, StandardCharsets.UTF_8), "--world $file")
            val world = when {
                !root.has("world") -> root
                root.get("world").isJsonObject -> root.getAsJsonObject("world")
                else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "--world file '$file' contains non-object world")
            }
            ManifestWorldSetup.apply(world, sandbox, file.parent ?: Path.of("."))
        }
    }

    private fun parseAssertions(): List<JsonObject> =
        assertions.mapIndexed { index, raw -> parseInlineAssertion(raw, "--assert ${index + 1}") } +
            assertionFiles.flatMap { file -> parseAssertionFile(file) }

    private fun applyPlayerEventLines(sandbox: DatapackSandbox, lines: List<String>, label: String) {
        lines.forEachIndexed { index, raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachIndexed
            applyPlayerEvent(sandbox, parsePlayerEventText(trimmed, "$label:${index + 1}"))
        }
    }

    private fun applyPlayerEvent(sandbox: DatapackSandbox, event: PlayerEvent) {
        sandbox.createPlayer(event.playerName)
        sandbox.handlePlayerEvent(event)
    }

    private fun parseInlineAssertion(raw: String, label: String): JsonObject {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) return parseJsonObject(raw, label)
        return when {
            trimmed.startsWith("score:") -> parseScoreAssertion(trimmed.removePrefix("score:"), label)
            trimmed.startsWith("storage:") -> parseStorageAssertion(trimmed.removePrefix("storage:"), label)
            trimmed.startsWith("advancement:") -> parseAdvancementAssertion(trimmed.removePrefix("advancement:"), label)
            trimmed.startsWith("entity:") -> parseEntityCountAssertion(trimmed.removePrefix("entity:"), label)
            trimmed.startsWith("item:") -> parseItemAssertion(trimmed.removePrefix("item:"), label)
            trimmed.startsWith("player:") -> parsePlayerAssertion(trimmed.removePrefix("player:"), label)
            trimmed.startsWith("diff:") -> parseSnapshotDiffAssertion(trimmed.removePrefix("diff:"), label)
            trimmed.startsWith("event-trace:") -> parseEventTraceAssertion(trimmed.removePrefix("event-trace:"), label)
            trimmed.startsWith("trace:") -> parseTraceAssertion(trimmed.removePrefix("trace:"), label)
            trimmed.startsWith("trace-output:") -> parseTraceOutputAssertion(trimmed.removePrefix("trace-output:"), label)
            trimmed.startsWith("warning:") -> parseWarningContainsAssertion(trimmed.removePrefix("warning:"), label)
            trimmed.startsWith("warning=") -> parseWarningCountAssertion(trimmed.removePrefix("warning="), label)
            trimmed.startsWith("unsupported:") -> parseUnsupportedContainsAssertion(trimmed.removePrefix("unsupported:"), label)
            trimmed.startsWith("unsupported=") -> parseUnsupportedCountAssertion(trimmed.removePrefix("unsupported="), label)
            trimmed.startsWith("output-normalized:") -> parseNormalizedOutputAssertion(trimmed.removePrefix("output-normalized:"), label)
            trimmed.startsWith("output-payload:") -> parseOutputPayloadAssertion(trimmed.removePrefix("output-payload:"), label)
            trimmed.startsWith("output:") -> parseOutputAssertion(trimmed.removePrefix("output:"), label)
            else -> throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "$label must be a JSON object or shorthand score:<target>:<objective>=N, storage:<id>[:<path>]=<json>, advancement:<player>:<id>[=<true|false>], entity:<type|*>[@tag]=N, item:<player>:<id>[@slot]=N, player:<name>[:<field>=<value>], diff:<json-pointer>[=<kind>], event-trace:<player>:<type>[=N], trace:<root>=N, trace:<text>, trace-output:<text>[@target], warning=N, warning:<text>, unsupported=N, unsupported:<text>, output:<text>, output-normalized:<text>, or output-payload:<command>:<path>[=<json>]",
            )
        }
    }

    private fun parseScoreAssertion(spec: String, label: String): JsonObject {
        val operator = listOf(">=", "<=", "=").firstOrNull { it in spec }
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label score shorthand requires =, >=, or <=")
        val splitAt = spec.indexOf(operator)
        val left = spec.substring(0, splitAt)
        val right = spec.substring(splitAt + operator.length).trim()
        val separator = left.lastIndexOf(':')
        if (separator <= 0 || separator == left.lastIndex) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label score shorthand must be score:<target>:<objective>${operator}N")
        }
        val expected = right.toIntOrNull()
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label score shorthand expected integer but got '$right'")
        val score = JsonObject().also { json ->
            json.addProperty("target", left.substring(0, separator).trim())
            json.addProperty("objective", left.substring(separator + 1).trim())
            when (operator) {
                ">=" -> json.addProperty("min", expected)
                "<=" -> json.addProperty("max", expected)
                else -> json.addProperty("equals", expected)
            }
        }
        return JsonObject().also { it.add("score", score) }
    }

    private fun parseStorageAssertion(spec: String, label: String): JsonObject {
        val storage = when {
            spec.endsWith("?") -> storageAssertionObject(spec.dropLast(1), label).also { it.addProperty("exists", true) }
            spec.endsWith("!") -> storageAssertionObject(spec.dropLast(1), label).also { it.addProperty("missing", true) }
            else -> {
                val splitAt = spec.indexOf('=')
                if (splitAt <= 0) {
                    throw SandboxException(
                        DiagnosticCode.INPUT_FORMAT,
                        "$label storage shorthand must be storage:<id>[:<path>]=<json>, storage:<id>[:<path>]?, or storage:<id>[:<path>]!",
                    )
                }
                val expectedText = spec.substring(splitAt + 1).trim()
                val expected = try {
                    JsonParser.parseString(expectedText)
                } catch (error: Exception) {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label storage shorthand expected JSON value but got '$expectedText'", cause = error)
                }
                storageAssertionObject(spec.substring(0, splitAt), label).also { it.add("equals", expected) }
            }
        }
        return JsonObject().also { it.add("storage", storage) }
    }

    private fun parseAdvancementAssertion(spec: String, label: String): JsonObject {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label advancement shorthand must be advancement:<player>:<id>[=<true|false>]")
        }
        val splitAt = trimmed.indexOf('=')
        val left = if (splitAt < 0) trimmed else trimmed.substring(0, splitAt)
        val doneText = splitAt.takeIf { it >= 0 }?.let { trimmed.substring(it + 1).trim() }
        val separator = left.indexOf(':')
        if (separator <= 0 || separator == left.lastIndex) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label advancement shorthand must be advancement:<player>:<id>[=<true|false>]")
        }
        val player = left.substring(0, separator).trim()
        val id = left.substring(separator + 1).trim()
        if (player.isEmpty() || id.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label advancement shorthand must be advancement:<player>:<id>[=<true|false>]")
        }
        val done = doneText?.let {
            when (it.lowercase()) {
                "true" -> true
                "false" -> false
                else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label advancement shorthand expected true or false but got '$it'")
            }
        } ?: true
        val advancement = JsonObject().also { json ->
            json.addProperty("player", player)
            json.addProperty("id", id)
            json.addProperty("done", done)
        }
        return JsonObject().also { it.add("advancement", advancement) }
    }

    private fun parseEntityCountAssertion(spec: String, label: String): JsonObject {
        val operator = listOf(">=", "<=", "=").firstOrNull { it in spec }
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label entity shorthand requires =, >=, or <=")
        val splitAt = spec.indexOf(operator)
        val selector = spec.substring(0, splitAt).trim()
        val right = spec.substring(splitAt + operator.length).trim()
        if (selector.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label entity shorthand must be entity:<type|*>[@tag]${operator}N")
        }
        val expected = right.toIntOrNull()
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label entity shorthand expected integer but got '$right'")
        val tagSplit = selector.indexOf('@')
        val type = if (tagSplit < 0) selector else selector.substring(0, tagSplit).trim()
        val tag = tagSplit.takeIf { it >= 0 }?.let { selector.substring(it + 1).trim() }
        if (type.isEmpty() || tag?.isEmpty() == true) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label entity shorthand must be entity:<type|*>[@tag]${operator}N")
        }
        val entity = JsonObject().also { json ->
            if (type != "*") json.addProperty("type", type)
            tag?.let { json.addProperty("tag", it) }
            when (operator) {
                ">=" -> json.addProperty("min", expected)
                "<=" -> json.addProperty("max", expected)
                else -> json.addProperty("equals", expected)
            }
        }
        return JsonObject().also { it.add("entityCount", entity) }
    }

    private fun parseItemAssertion(spec: String, label: String): JsonObject {
        val operator = listOf(">=", "<=", "=").firstOrNull { it in spec }
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label item shorthand requires =, >=, or <=")
        val splitAt = spec.indexOf(operator)
        val left = spec.substring(0, splitAt).trim()
        val right = spec.substring(splitAt + operator.length).trim()
        val separator = left.indexOf(':')
        if (separator <= 0 || separator == left.lastIndex) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label item shorthand must be item:<player>:<id>[@slot]${operator}N")
        }
        val expected = right.toIntOrNull()
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label item shorthand expected integer but got '$right'")
        val player = left.substring(0, separator).trim()
        val itemSpec = left.substring(separator + 1).trim()
        val slotSplit = itemSpec.indexOf('@')
        val itemId = if (slotSplit < 0) itemSpec else itemSpec.substring(0, slotSplit).trim()
        val slot = slotSplit.takeIf { it >= 0 }?.let {
            itemSpec.substring(it + 1).trim().toIntOrNull()
                ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label item shorthand slot must be an integer")
        }
        if (player.isEmpty() || itemId.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label item shorthand must be item:<player>:<id>[@slot]${operator}N")
        }
        val item = JsonObject().also { json ->
            json.addProperty("player", player)
            json.addProperty("id", itemId)
            slot?.let { json.addProperty("slot", it) }
            when (operator) {
                ">=" -> json.addProperty("minCount", expected)
                "<=" -> json.addProperty("maxCount", expected)
                else -> json.addProperty("count", expected)
            }
        }
        return JsonObject().also { it.add("item", item) }
    }

    private fun parsePlayerAssertion(spec: String, label: String): JsonObject {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label player shorthand must be player:<name>, player:<name>?, player:<name>!, or player:<name>:<field>=<value>")
        }
        val player = when {
            trimmed.endsWith("?") -> playerAssertionObject(trimmed.dropLast(1), label).also { it.addProperty("exists", true) }
            trimmed.endsWith("!") -> playerAssertionObject(trimmed.dropLast(1), label).also { it.addProperty("exists", false) }
            ":" !in trimmed -> playerAssertionObject(trimmed, label).also { it.addProperty("exists", true) }
            else -> parsePlayerFieldAssertion(trimmed, label)
        }
        return JsonObject().also { it.add("player", player) }
    }

    private fun parsePlayerFieldAssertion(spec: String, label: String): JsonObject {
        val firstColon = spec.indexOf(':')
        if (firstColon <= 0 || firstColon == spec.lastIndex) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label player shorthand must be player:<name>:<field>=<value>")
        }
        val name = spec.substring(0, firstColon).trim()
        val fieldSpec = spec.substring(firstColon + 1)
        val splitAt = fieldSpec.indexOf('=')
        if (splitAt <= 0) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label player field shorthand must be player:<name>:<field>=<value>")
        }
        val field = fieldSpec.substring(0, splitAt).trim()
        val value = fieldSpec.substring(splitAt + 1).trim()
        if (name.isEmpty() || field.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label player field shorthand must be player:<name>:<field>=<value>")
        }
        return playerAssertionObject(name, label).also { player ->
            when (field) {
                "xp", "food", "inventoryCount" -> player.addProperty(field, parsePlayerInt(value, field, label))
                "selectedSlot", "slot" -> player.addProperty("selectedSlot", parsePlayerInt(value, field, label))
                "health" -> player.addProperty("health", parsePlayerDouble(value, field, label))
                "gameMode", "gamemode" -> player.addProperty("gameMode", value)
                "dimension" -> player.addProperty("dimension", value)
                else -> throw SandboxException(
                    DiagnosticCode.INPUT_FORMAT,
                    "$label unsupported player shorthand field '$field'; use xp, health, food, selectedSlot, slot, inventoryCount, gameMode, gamemode, or dimension",
                )
            }
        }
    }

    private fun playerAssertionObject(name: String, label: String): JsonObject {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label player shorthand name must not be empty")
        }
        return JsonObject().also { it.addProperty("name", trimmed) }
    }

    private fun parsePlayerInt(value: String, field: String, label: String): Int =
        value.toIntOrNull()
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label player field '$field' expected integer but got '$value'")

    private fun parsePlayerDouble(value: String, field: String, label: String): Double =
        value.toDoubleOrNull()
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label player field '$field' expected number but got '$value'")

    private fun parseTraceAssertion(spec: String, label: String): JsonObject {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label trace shorthand must be trace:<root>=N or trace:<text>")
        }
        val trace = JsonObject()
        val splitAt = trimmed.indexOf('=')
        if (splitAt > 0) {
            val root = trimmed.substring(0, splitAt).trim()
            val countText = trimmed.substring(splitAt + 1).trim()
            if (root.isEmpty() || root.any(Char::isWhitespace)) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label trace count shorthand must be trace:<root>=N")
            }
            val expected = countText.toIntOrNull()
                ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label trace shorthand expected integer but got '$countText'")
            trace.addProperty("root", root)
            trace.addProperty("count", expected)
        } else {
            trace.addProperty("contains", trimmed)
        }
        return JsonObject().also { it.add("trace", trace) }
    }

    private fun parseEventTraceAssertion(spec: String, label: String): JsonObject {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label event trace shorthand must be event-trace:<player>:<type>[=N]")
        }
        val splitAt = trimmed.indexOf('=')
        val left = if (splitAt < 0) trimmed else trimmed.substring(0, splitAt)
        val countText = splitAt.takeIf { it >= 0 }?.let { trimmed.substring(it + 1).trim() }
        val separator = left.indexOf(':')
        if (separator <= 0 || separator == left.lastIndex) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label event trace shorthand must be event-trace:<player>:<type>[=N]")
        }
        val player = left.substring(0, separator).trim()
        val type = left.substring(separator + 1).trim()
        if (player.isEmpty() || type.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label event trace shorthand must be event-trace:<player>:<type>[=N]")
        }
        val eventTrace = JsonObject().also { json ->
            json.addProperty("player", player)
            json.addProperty("type", type)
            countText?.let {
                json.addProperty(
                    "count",
                    it.toIntOrNull()
                        ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label event trace shorthand expected integer but got '$it'"),
                )
            }
        }
        return JsonObject().also { it.add("eventTrace", eventTrace) }
    }

    private fun parseSnapshotDiffAssertion(spec: String, label: String): JsonObject {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label diff shorthand must be diff:<json-pointer>[=<kind>]")
        }
        val splitAt = trimmed.indexOf('=')
        val path = if (splitAt < 0) trimmed else trimmed.substring(0, splitAt).trim()
        val kind = splitAt.takeIf { it >= 0 }?.let { trimmed.substring(it + 1).trim() }
        if (path.isEmpty() || !path.startsWith("/")) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label diff shorthand path must be a JSON Pointer starting with '/'")
        }
        if (kind != null && kind !in setOf("added", "removed", "changed", "ADDED", "REMOVED", "CHANGED")) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label diff shorthand kind must be added, removed, or changed")
        }
        val diff = JsonObject().also { json ->
            json.addProperty("path", path)
            kind?.let { json.addProperty("kind", it) }
        }
        return JsonObject().also { it.add("snapshotDiff", diff) }
    }

    private fun parseTraceOutputAssertion(spec: String, label: String): JsonObject {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label trace output shorthand must be trace-output:<text>[@target]")
        }
        val splitAt = trimmed.lastIndexOf('@')
        val outputContains = if (splitAt < 0) trimmed else trimmed.substring(0, splitAt).trim()
        val outputTarget = splitAt.takeIf { it >= 0 }?.let { trimmed.substring(it + 1).trim() }
        if (outputContains.isEmpty() || outputTarget?.isEmpty() == true) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label trace output shorthand must be trace-output:<text>[@target]")
        }
        val trace = JsonObject().also { json ->
            json.addProperty("outputContains", outputContains)
            outputTarget?.let { json.addProperty("outputTarget", it) }
        }
        return JsonObject().also { it.add("trace", trace) }
    }

    private fun storageAssertionObject(location: String, label: String): JsonObject {
        val trimmed = location.trim()
        val firstColon = trimmed.indexOf(':')
        if (firstColon <= 0 || firstColon == trimmed.lastIndex) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label storage shorthand must include a namespaced id")
        }
        val secondColon = trimmed.indexOf(':', firstColon + 1)
        val id = if (secondColon < 0) trimmed else trimmed.substring(0, secondColon)
        val path = secondColon.takeIf { it >= 0 }?.let { trimmed.substring(it + 1).trim() }
        if (path != null && path.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label storage shorthand path must not be empty")
        }
        return JsonObject().also { json ->
            json.addProperty("id", id.trim())
            path?.let { json.addProperty("path", it) }
        }
    }

    private fun parseOutputAssertion(text: String, label: String): JsonObject {
        val contains = text.trim()
        if (contains.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label output shorthand must be output:<text>")
        }
        val output = JsonObject().also { it.addProperty("contains", contains) }
        return JsonObject().also { it.add("output", output) }
    }

    private fun parseNormalizedOutputAssertion(text: String, label: String): JsonObject {
        val contains = text.trim()
        if (contains.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label normalized output shorthand must be output-normalized:<text>")
        }
        val output = JsonObject().also { it.addProperty("normalizedContains", contains) }
        return JsonObject().also { it.add("output", output) }
    }

    private fun parseOutputPayloadAssertion(spec: String, label: String): JsonObject {
        val trimmed = spec.trim()
        val splitAt = trimmed.indexOf('=')
        if (splitAt == trimmed.lastIndex) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label output payload shorthand must be output-payload:<command>:<path>[=<json>]")
        }
        val left = if (splitAt < 0) trimmed else trimmed.substring(0, splitAt)
        val separator = left.indexOf(':')
        if (separator <= 0 || separator == left.lastIndex) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label output payload shorthand must be output-payload:<command>:<path>[=<json>]")
        }
        val command = left.substring(0, separator).trim()
        val path = left.substring(separator + 1).trim()
        if (command.isEmpty() || path.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label output payload shorthand must be output-payload:<command>:<path>[=<json>]")
        }
        val output = JsonObject().also { json ->
            json.addProperty("command", command)
            json.addProperty("payloadPath", path)
            if (splitAt >= 0) {
                val expectedText = trimmed.substring(splitAt + 1).trim()
                val expected = try {
                    JsonValues.parse(expectedText)
                } catch (error: SandboxException) {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label output payload shorthand expected JSON/SNBT-lite value but got '$expectedText'", cause = error)
                }
                json.add("payloadEquals", expected)
            }
        }
        return JsonObject().also { it.add("output", output) }
    }

    private fun parseWarningContainsAssertion(text: String, label: String): JsonObject {
        val contains = text.trim()
        if (contains.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label warning shorthand must be warning:<text>")
        }
        val output = JsonObject().also { json ->
            json.addProperty("channel", "warning")
            json.addProperty("contains", contains)
        }
        return JsonObject().also { it.add("output", output) }
    }

    private fun parseWarningCountAssertion(count: String, label: String): JsonObject {
        val expected = count.trim().toIntOrNull()
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label warning shorthand expected integer but got '${count.trim()}'")
        val output = JsonObject().also { json ->
            json.addProperty("channel", "warning")
            json.addProperty("count", expected)
        }
        return JsonObject().also { it.add("output", output) }
    }

    private fun parseUnsupportedContainsAssertion(text: String, label: String): JsonObject {
        val contains = text.trim()
        if (contains.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label unsupported shorthand must be unsupported:<text>")
        }
        val output = JsonObject().also { json ->
            json.addProperty("command", "unsupported")
            json.addProperty("channel", "warning")
            json.addProperty("contains", contains)
        }
        return JsonObject().also { it.add("output", output) }
    }

    private fun parseUnsupportedCountAssertion(count: String, label: String): JsonObject {
        val expected = count.trim().toIntOrNull()
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label unsupported shorthand expected integer but got '${count.trim()}'")
        val output = JsonObject().also { json ->
            json.addProperty("command", "unsupported")
            json.addProperty("channel", "warning")
            json.addProperty("count", expected)
        }
        return JsonObject().also { it.add("output", output) }
    }

    private fun parseAssertionFile(file: Path): List<JsonObject> {
        val text = Files.readString(file, StandardCharsets.UTF_8)
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return text.lines().mapIndexedNotNull { index, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) {
                    null
                } else {
                    parseInlineAssertion(line, "--assert-file $file:${index + 1}")
                }
            }
        }
        val parsed = try {
            JsonParser.parseString(text)
        } catch (error: Exception) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid JSON for --assert-file $file", cause = error)
        }
        val assertions = when {
            parsed.isJsonArray -> parsed.asJsonArray.toList()
            parsed.isJsonObject && parsed.asJsonObject.has("assertions") -> {
                val element = parsed.asJsonObject.get("assertions")
                if (!element.isJsonArray) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "--assert-file $file field 'assertions' must be an array")
                element.asJsonArray.toList()
            }
            parsed.isJsonObject -> listOf(parsed)
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "--assert-file $file must contain an assertion object, assertion array, or object with assertions array")
        }
        return assertions.mapIndexed { index, element ->
            if (!element.isJsonObject) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "--assert-file $file assertion ${index + 1} must be an object")
            }
            element.asJsonObject
        }
    }

    private fun parseJsonObject(raw: String, label: String): JsonObject =
        try {
            val parsed = JsonParser.parseString(raw)
            if (!parsed.isJsonObject) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must be a JSON object")
            parsed.asJsonObject
        } catch (error: SandboxException) {
            throw error
        } catch (error: Exception) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid JSON for $label", cause = error)
        }

    private fun executeCommandLines(sandbox: DatapackSandbox, lines: List<String>, sourceName: String): Int {
        var total = 0
        lines.forEachIndexed { index, raw ->
            val command = raw.removePrefix("\uFEFF").trim()
            if (command.isNotEmpty() && !command.startsWith("#")) {
                val normalized = command.removePrefix("/")
                total += sandbox.executeCommand(
                    normalized,
                    SourceLocation(file = sourceName, line = index + 1, command = normalized),
                ).commandsExecuted
            }
        }
        return total
    }

    private fun String.sanitizeFunctionPath(): String =
        lowercase()
            .replace('\\', '/')
            .replace(Regex("[^a-z0-9_./-]"), "_")
            .trim('/')
            .ifBlank { "function" }
}

class LootCommand : CliktCommand(name = "loot") {
    private val version by option("--version", "-v").default(VersionProfiles.default.id)
    private val packs by option("--pack", "-p").path(mustExist = true).multiple(required = true)
    private val table by option("--table").default("minecraft:empty")
    private val context by option("--context").default("minecraft:empty")
    private val player by option("--player")
    private val seed by option("--seed").long().default(0)

    override fun run() {
        try {
            val sandbox = createSandbox(version, packs)
            val sandboxPlayer = player?.let { sandbox.createPlayer(it) }
            val result = sandbox.generateLoot(ResourceLocation.parse(table), ResourceLocation.parse(context), sandboxPlayer, seed)
            println(JsonValues.render(com.google.gson.JsonArray().also { array ->
                result.items.forEach { array.add(it.toJson()) }
            }))
        } catch (error: SandboxException) {
            println(ConsoleStyle.diagnostic(error.render()))
            exitProcess(ExitCodes.forException(error))
        }
    }
}

class AdvancementCommand : CliktCommand(name = "advancement") {
    private val version by option("--version", "-v").default(VersionProfiles.default.id)
    private val packs by option("--pack", "-p").path(mustExist = true).multiple(required = true)
    private val args by argument("args").multiple(required = true)
    private val unsupported by option("--unsupported").default("warn")

    override fun run() {
        try {
            val sandbox = createSandbox(version, packs, unsupportedFeatureMode = unsupportedFeatureMode(unsupported))
            val action = args.getOrNull(0) ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "advancement action is required")
            val player = sandbox.createPlayer(args.getOrNull(1) ?: "Steve")
            val id = args.getOrNull(2)?.let(ResourceLocation::parse)
            when (action) {
                "list" -> sandbox.datapack.advancements.keys.forEach { println(it) }
                "progress" -> {
                    val progress = sandbox.advancements.progress(player, id ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "advancement id is required"))
                    println(JsonValues.render(player.toPlayerJson(sandbox.profile)))
                    println(progress.criteria)
                }
                "grant" -> sandbox.advancements.grant(player, id ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "advancement id is required"), args.getOrNull(3)).forEach { println(it) }
                "revoke" -> sandbox.advancements.revoke(player, id ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "advancement id is required"), args.getOrNull(3)).forEach { println(it) }
                else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Unknown advancement action '$action'")
            }
        } catch (error: SandboxException) {
            println(ConsoleStyle.diagnostic(error.render()))
            exitProcess(ExitCodes.forException(error))
        }
    }
}

private fun parsePlayerEventText(raw: String, label: String): PlayerEvent {
    val args = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return parsePlayerEventArgs(args, label)
}

private fun parsePlayerEventArgs(args: List<String>, label: String): PlayerEvent {
    if (args.getOrNull(0) != "player" || args.size < 3) {
        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Usage: $label player <name> <type> [id] [detail/action]")
    }
    val playerName = args[1].trim()
    val eventType = args[2].trim()
    if (playerName.isEmpty() || eventType.isEmpty()) {
        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Usage: $label player <name> <type> [id] [detail/action]")
    }
    return try {
        PlayerEvents.shorthand(playerName, eventType, args.getOrNull(3), args.getOrNull(4))
    } catch (error: IllegalArgumentException) {
        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label invalid player event: ${error.message}", cause = error)
    }
}

class EventCommand : CliktCommand(name = "event") {
    private val version by option("--version", "-v").default(VersionProfiles.default.id)
    private val packs by option("--pack", "-p").path(mustExist = true).multiple(required = true)
    private val args by argument("args").multiple(required = true)
    private val unsupported by option("--unsupported").default("warn")

    override fun run() {
        try {
            val sandbox = createSandbox(version, packs, unsupportedFeatureMode = unsupportedFeatureMode(unsupported))
            val event = parsePlayerEventArgs(args, "event")
            val player = sandbox.createPlayer(event.playerName)
            val updates = sandbox.handlePlayerEvent(event)
            val inputText = event.input?.let { ", input=${it.device}:${it.code}/${it.action}" }.orEmpty()
            println(ConsoleStyle.green("OK event player ${player.name} ${event.type}") + ConsoleStyle.dim(" (updates=${updates.size}$inputText)"))
            if (updates.isEmpty()) {
                println(ConsoleStyle.yellow("No advancement criteria changed. Check the event type/id against the advancement trigger conditions."))
            } else {
                updates.forEach { println(it) }
            }
            println(sandbox.snapshotString())
        } catch (error: SandboxException) {
            println(ConsoleStyle.diagnostic(error.render()))
            exitProcess(ExitCodes.forException(error))
        }
    }
}

class ManifestSchemaCommand : CliktCommand(name = "schema") {
    private val output by option("--output", "-o").path()

    override fun run() {
        try {
            val schema = ManifestSchema.readText()
            val outputPath = output
            if (outputPath == null) {
                print(schema)
            } else {
                outputPath.parent?.let(Files::createDirectories)
                Files.writeString(outputPath, schema, StandardCharsets.UTF_8)
                println(ConsoleStyle.green("schema written: $outputPath"))
            }
        } catch (error: SandboxException) {
            println(ConsoleStyle.diagnostic(error.render()))
            exitProcess(ExitCodes.forException(error))
        }
    }
}

class VersionCommand : CliktCommand(name = "version") {
    private val versions by argument("version").multiple(required = false)
    private val docs by option("--docs").flag(default = false)
    private val json by option("--json").flag(default = false)
    private val output by option("--output", "-o").path()
    private val docsCheck by option("--check").path()

    override fun run() {
        try {
            if (docs && json) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "version accepts only one output mode: --docs or --json")
            }
            if (docsCheck != null && output != null) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "version accepts only one file mode: --output or --check")
            }
            if (docsCheck != null && !docs) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "version --check is only supported with --docs")
            }
            if (docs) {
                if (versions.isNotEmpty()) {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "version --docs does not accept profile arguments")
                }
                val table = VersionProfileDocs.renderMarkdownTable()
                val checkPath = docsCheck
                if (checkPath == null) {
                    emit(table)
                } else {
                    checkDocsTable(checkPath, table)
                }
                return
            }
            if (json) {
                val report = when (versions.size) {
                    0 -> VersionProfileJson.profiles()
                    2 -> {
                        val from = VersionProfiles.get(versions[0])
                        val to = VersionProfiles.get(versions[1])
                        VersionProfileJson.diff(VersionProfileDiffs.diff(from, to))
                    }
                    else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "version --json accepts either no arguments or two profile ids to diff")
                }
                emit(JsonValues.render(report))
                return
            }
            when (versions.size) {
                0 -> emit(
                    VersionProfiles.all.joinToString(System.lineSeparator()) { profile ->
                        val marker = if (profile == VersionProfiles.default) " default" else ""
                        val dataVersion = profile.dataVersion?.let { " data=$it" }.orEmpty()
                        "${profile.id} java=${profile.javaMajor} pack_format=${profile.dataPackFormat}$dataVersion$marker - ${profile.description}"
                    },
                )
                2 -> {
                    val from = VersionProfiles.get(versions[0])
                    val to = VersionProfiles.get(versions[1])
                    emit(VersionProfileDiffs.render(VersionProfileDiffs.diff(from, to)))
                }
                else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "version accepts either no arguments or two profile ids to diff")
            }
        } catch (error: SandboxException) {
            println(ConsoleStyle.diagnostic(error.render()))
            exitProcess(ExitCodes.forException(error))
        }
    }

    private fun emit(content: String) {
        val outputPath = output
        if (outputPath == null) {
            println(content)
        } else {
            outputPath.parent?.let(Files::createDirectories)
            Files.writeString(outputPath, content, StandardCharsets.UTF_8)
            println(ConsoleStyle.green("version output written: $outputPath"))
        }
    }

    private fun checkDocsTable(path: Path, table: String) {
        if (!Files.exists(path)) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "version docs check file does not exist: $path")
        }
        val actual = normalizeNewlines(Files.readString(path, StandardCharsets.UTF_8))
        val expected = normalizeNewlines(table)
        if (!actual.contains(expected)) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "version docs are out of date: $path; regenerate with version --docs --output <file>",
            )
        }
        println(ConsoleStyle.green("version docs up to date: $path"))
    }

    private fun normalizeNewlines(value: String): String =
        value.replace("\r\n", "\n").replace('\r', '\n')
}

class CommandsCommand : CliktCommand(name = "commands") {
    private val version by option("--version", "-v").default(VersionProfiles.default.id)
    private val docs by option("--docs").flag(default = false)
    private val json by option("--json").flag(default = false)
    private val output by option("--output", "-o").path()
    private val check by option("--check").path()

    override fun run() {
        try {
            if (docs && json) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "commands accepts only one output mode: --docs or --json")
            }
            if (check != null && output != null) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "commands accepts only one file mode: --output or --check")
            }
            val profile = VersionProfiles.get(version)
            val commands = DpsCommandCatalog.rootCommands(profile)
            val checkPath = check
            when {
                checkPath != null -> checkCommandDocs(checkPath, commands)
                docs -> emit(renderMarkdown(commands))
                json -> emit(JsonValues.render(renderJson(profile.id, commands)))
                else -> emit(renderPlain(commands))
            }
        } catch (error: SandboxException) {
            println(ConsoleStyle.diagnostic(error.render()))
            exitProcess(ExitCodes.forException(error))
        }
    }

    private fun renderPlain(commands: List<CompletionSuggestion>): String =
        commands.joinToString(System.lineSeparator()) { command ->
            val behavior = command.behaviorLevel?.id ?: "unknown"
            "${command.value} $behavior - ${command.description}"
        }

    private fun renderMarkdown(commands: List<CompletionSuggestion>): String =
        buildString {
            appendLine("| Command | Behavior | Description |")
            appendLine("|---|---|---|")
            commands.forEach { command ->
                val behavior = command.behaviorLevel?.id ?: "unknown"
                appendLine("| `${markdownCell(command.value)}` | `$behavior` | ${markdownCell(command.description)} |")
            }
        }.trimEnd()

    private fun renderJson(version: String, commands: List<CompletionSuggestion>): JsonObject =
        JsonObject().also { root ->
            root.addProperty("version", version)
            root.add(
                "commands",
                JsonArray().also { array ->
                    commands.forEach { command ->
                        array.add(
                            JsonObject().also { json ->
                                json.addProperty("command", command.value)
                                json.addProperty("behavior", command.behaviorLevel?.id ?: "unknown")
                                json.addProperty("description", command.description)
                                json.addProperty("group", command.group)
                            },
                        )
                    }
                },
            )
        }

    private fun markdownCell(value: String): String =
        value.replace("|", "\\|").replace("\r", " ").replace("\n", " ")

    private fun emit(content: String) {
        val outputPath = output
        if (outputPath == null) {
            println(content)
        } else {
            outputPath.parent?.let(Files::createDirectories)
            Files.writeString(outputPath, content, StandardCharsets.UTF_8)
            println(ConsoleStyle.green("commands output written: $outputPath"))
        }
    }

    private fun checkCommandDocs(path: Path, commands: List<CompletionSuggestion>) {
        if (!Files.exists(path)) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "commands check file does not exist: $path")
        }
        val docLines = normalizeDoc(Files.readString(path, StandardCharsets.UTF_8)).lineSequence().toList()
        val missing = commands.mapNotNull { command ->
            val behavior = command.behaviorLevel?.id ?: "unknown"
            val commandPattern = Regex("""`[^`]*\b${Regex.escape(command.value)}\b[^`]*`""")
            val covered = docLines.any { line -> commandPattern.containsMatchIn(line) && "`$behavior`" in line }
            if (covered) null else "${command.value} ($behavior)"
        }
        if (missing.isNotEmpty()) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "commands docs are out of date: $path; missing ${missing.joinToString()}",
            )
        }
        println(ConsoleStyle.green("commands docs cover catalog: $path"))
    }

    private fun normalizeDoc(value: String): String =
        value.replace("\r\n", "\n").replace('\r', '\n')
}

fun unsupportedFeatureMode(value: String): UnsupportedFeatureMode =
    when (value.lowercase()) {
        "warn", "warning" -> UnsupportedFeatureMode.WARN
        "ignore", "silent" -> UnsupportedFeatureMode.IGNORE
        "error", "strict" -> UnsupportedFeatureMode.ERROR
        else -> throw SandboxException(
            DiagnosticCode.INPUT_FORMAT,
            "Unsupported --unsupported mode '$value'; expected warn, ignore, or error",
        )
    }
