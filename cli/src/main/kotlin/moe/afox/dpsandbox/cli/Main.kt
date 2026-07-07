package moe.afox.dpsandbox.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import moe.afox.dpsandbox.core.CommandTraceEvent
import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.DatapackSandbox
import moe.afox.dpsandbox.core.FunctionSource
import moe.afox.dpsandbox.core.JsonValues
import moe.afox.dpsandbox.core.OutputEvent
import moe.afox.dpsandbox.core.PlayerEvent
import moe.afox.dpsandbox.core.PlayerEventTraceEvent
import moe.afox.dpsandbox.core.ResourceCatalog
import moe.afox.dpsandbox.core.ResourceCatalogEntry
import moe.afox.dpsandbox.core.ResourceIndexEntry
import moe.afox.dpsandbox.core.ResourceLocation
import moe.afox.dpsandbox.core.SandboxException
import moe.afox.dpsandbox.core.SandboxLimits
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
        DiffCommand(),
        BenchmarkCommand(),
        LootCommand(),
        AdvancementCommand(),
        EventCommand(),
        ManifestSchemaCommand(),
        VersionCommand(),
        CommandsCommand(),
        ResourcesCommand(),
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
    private val maxCommands by option("--max-commands").int()
    private val maxFunctionDepth by option("--max-function-depth").int()
    private val maxTicksPerRun by option("--max-ticks-per-run").int()
    private val maxOutputEvents by option("--max-output-events").int()
    private val maxSnapshotBytes by option("--max-snapshot-bytes").int()

    override fun run() {
        try {
            val limits = sandboxLimits(maxCommands, maxFunctionDepth, maxTicksPerRun, maxOutputEvents, maxSnapshotBytes)
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
                        limits = limits,
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
        val diagnostics = diagnosticsFromTraces(traces, sandbox.profile.id)
        report.addProperty("diagnosticCount", diagnostics.size())
        report.add("diagnostics", diagnostics)
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
        val diagnostics = diagnosticsFromTraces(traces, version = null)
        json.addProperty("diagnosticCount", diagnostics.size())
        json.addProperty("eventTraceCount", eventTraces.size)
        json.add("outputs", JsonArray().also { outputsJson ->
            outputs.forEach { outputsJson.add(it.toJson()) }
        })
        json.add("traces", JsonArray().also { tracesJson ->
            traces.forEach { tracesJson.add(it.toJson()) }
        })
        json.add("diagnostics", diagnostics)
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
        val diagnostics = diagnosticsFromTraces(traces, version)
        json.addProperty("diagnosticCount", diagnostics.size())
        json.addProperty("eventTraceCount", eventTraces.size)
        json.add("outputs", JsonArray().also { outputsJson ->
            outputs.forEach { outputsJson.add(it.toJson()) }
        })
        json.add("traces", JsonArray().also { tracesJson ->
            traces.forEach { tracesJson.add(it.toJson()) }
        })
        json.add("diagnostics", diagnostics)
        json.add("eventTraces", JsonArray().also { eventTracesJson ->
            eventTraces.forEach { eventTracesJson.add(it.toJson()) }
        })
        snapshot?.let { json.add("snapshot", it.deepCopy()) }
        json.add("snapshotDiffs", SnapshotDiff.toJson(snapshotDiffs))
        resourceSummary?.let { json.add("resources", it.toReportJson()) }
    }

private fun diagnosticsFromTraces(traces: List<CommandTraceEvent>, version: String?): JsonArray =
    JsonArray().also { diagnostics ->
        traces.filter { it.errorCode != null }.forEach { trace ->
            diagnostics.add(
                JsonObject().also { diagnostic ->
                    version?.let { diagnostic.addProperty("version", it) }
                    diagnostic.addProperty("code", trace.errorCode?.name)
                    diagnostic.addProperty("message", trace.errorMessage ?: trace.errorCode?.name.orEmpty())
                    diagnostic.addProperty("command", trace.command)
                    diagnostic.addProperty("root", trace.root)
                    trace.source?.file?.let { diagnostic.addProperty("file", it) }
                    trace.source?.line?.let { diagnostic.addProperty("line", it) }
                    diagnostic.addProperty("success", trace.success)
                    diagnostic.addProperty("commandsExecuted", trace.commandsExecuted)
                },
            )
        }
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

private fun sandboxLimits(
    maxCommands: Int?,
    maxFunctionDepth: Int?,
    maxTicksPerRun: Int?,
    maxOutputEvents: Int?,
    maxSnapshotBytes: Int?,
): SandboxLimits {
    val defaults = SandboxLimits()
    return try {
        SandboxLimits(
            maxCommands = maxCommands ?: defaults.maxCommands,
            maxFunctionDepth = maxFunctionDepth ?: defaults.maxFunctionDepth,
            maxTicksPerRun = maxTicksPerRun ?: defaults.maxTicksPerRun,
            maxOutputEvents = maxOutputEvents ?: defaults.maxOutputEvents,
            maxSnapshotBytes = maxSnapshotBytes ?: defaults.maxSnapshotBytes,
        )
    } catch (error: IllegalArgumentException) {
        throw SandboxException(DiagnosticCode.INPUT_FORMAT, error.message ?: "Invalid sandbox limits", cause = error)
    }
}

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
    private val allowCommandFailure by option(
        "--allow-command-failure",
        help = "Continue after direct --command, --command-file, or stdin command errors so diagnostic assertions can inspect them.",
    ).flag(default = false)
    private val unsupported by option("--unsupported").default("warn")
    private val maxCommands by option("--max-commands").int()
    private val maxFunctionDepth by option("--max-function-depth").int()
    private val maxTicksPerRun by option("--max-ticks-per-run").int()
    private val maxOutputEvents by option("--max-output-events").int()
    private val maxSnapshotBytes by option("--max-snapshot-bytes").int()

    override fun run() {
        try {
            val limits = sandboxLimits(maxCommands, maxFunctionDepth, maxTicksPerRun, maxOutputEvents, maxSnapshotBytes)
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
                        limits = limits,
                    )
                }
                packs.isNotEmpty() -> createSandbox(version, packs, unsupportedFeatureMode = effectiveUnsupportedMode, limits = limits)
                canUseEmptySandbox -> createFunctionSandbox(
                    version = version,
                    functionSources = listOf(FunctionSource.text(mcfunctionId, "", "<empty:$mcfunctionId>")),
                    unsupportedFeatureMode = effectiveUnsupportedMode,
                    limits = limits,
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
                total += executeDirectCommand(
                    sandbox,
                    normalized,
                    SourceLocation(file = "<arg:--command>", line = index + 1, command = normalized),
                )
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
            trimmed.startsWith("block:") -> parseBlockAssertion(trimmed.removePrefix("block:"), label)
            trimmed.startsWith("biome:") -> parseBiomeAssertion(trimmed.removePrefix("biome:"), label)
            trimmed.startsWith("team:") -> parseTeamAssertion(trimmed.removePrefix("team:"), label)
            trimmed.startsWith("bossbar:") -> parseBossbarAssertion(trimmed.removePrefix("bossbar:"), label)
            trimmed.startsWith("item:") -> parseItemAssertion(trimmed.removePrefix("item:"), label)
            trimmed.startsWith("player:") -> parsePlayerAssertion(trimmed.removePrefix("player:"), label)
            trimmed.startsWith("world:") -> parseWorldAssertion(trimmed.removePrefix("world:"), label)
            trimmed.startsWith("gamerule:") -> parseGameruleAssertion(trimmed.removePrefix("gamerule:"), label)
            trimmed.startsWith("random-sequence:") -> parseRandomSequenceAssertion(trimmed.removePrefix("random-sequence:"), label)
            trimmed.startsWith("scheduled:") -> parseScheduledAssertion(trimmed.removePrefix("scheduled:"), label)
            trimmed.startsWith("snapshot:") -> parseSnapshotAssertion(trimmed.removePrefix("snapshot:"), label)
            trimmed.startsWith("diff:") -> parseSnapshotDiffAssertion(trimmed.removePrefix("diff:"), label)
            trimmed.startsWith("event-trace:") -> parseEventTraceAssertion(trimmed.removePrefix("event-trace:"), label)
            trimmed.startsWith("trace:") -> parseTraceAssertion(trimmed.removePrefix("trace:"), label)
            trimmed.startsWith("trace-output:") -> parseTraceOutputAssertion(trimmed.removePrefix("trace-output:"), label)
            trimmed.startsWith("diagnostic:") -> parseDiagnosticAssertion(trimmed.removePrefix("diagnostic:"), label)
            trimmed.startsWith("diagnostic=") -> parseDiagnosticCountAssertion(trimmed.removePrefix("diagnostic="), label)
            trimmed.startsWith("warning:") -> parseWarningContainsAssertion(trimmed.removePrefix("warning:"), label)
            trimmed.startsWith("warning=") -> parseWarningCountAssertion(trimmed.removePrefix("warning="), label)
            trimmed.startsWith("unsupported:") -> parseUnsupportedContainsAssertion(trimmed.removePrefix("unsupported:"), label)
            trimmed.startsWith("unsupported=") -> parseUnsupportedCountAssertion(trimmed.removePrefix("unsupported="), label)
            trimmed.startsWith("output-count:") -> parseOutputCountAssertion(trimmed.removePrefix("output-count:"), label)
            trimmed.startsWith("output-order:") -> parseOutputOrderAssertion(trimmed.removePrefix("output-order:"), label)
            trimmed.startsWith("output-command:") -> parseOutputFieldAssertion(trimmed.removePrefix("output-command:"), "command", "output-command", label)
            trimmed.startsWith("output-channel:") -> parseOutputChannelAssertion(trimmed.removePrefix("output-channel:"), label)
            trimmed.startsWith("output-target:") -> parseOutputFieldAssertion(trimmed.removePrefix("output-target:"), "target", "output-target", label)
            trimmed.startsWith("output-exact:") -> parseOutputExactAssertion(trimmed.removePrefix("output-exact:"), label)
            trimmed.startsWith("output-matches:") -> parseOutputMatchesAssertion(trimmed.removePrefix("output-matches:"), label)
            trimmed.startsWith("output-normalized-exact:") -> parseNormalizedOutputExactAssertion(trimmed.removePrefix("output-normalized-exact:"), label)
            trimmed.startsWith("output-normalized-matches:") -> parseNormalizedOutputMatchesAssertion(trimmed.removePrefix("output-normalized-matches:"), label)
            trimmed.startsWith("output-normalized:") -> parseNormalizedOutputAssertion(trimmed.removePrefix("output-normalized:"), label)
            trimmed.startsWith("output-segment-exact:") -> parseOutputSegmentExactAssertion(trimmed.removePrefix("output-segment-exact:"), label)
            trimmed.startsWith("output-segment-matches:") -> parseOutputSegmentMatchesAssertion(trimmed.removePrefix("output-segment-matches:"), label)
            trimmed.startsWith("output-segment:") -> parseOutputSegmentAssertion(trimmed.removePrefix("output-segment:"), label)
            trimmed.startsWith("output-payload:") -> parseOutputPayloadAssertion(trimmed.removePrefix("output-payload:"), label)
            trimmed.startsWith("output:") -> parseOutputAssertion(trimmed.removePrefix("output:"), label)
            else -> throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "$label must be a JSON object or shorthand score:<target>:<objective>=N, storage:<id>[:<path>]=<json>, advancement:<player>:<id>[=<true|false>], entity:<type|*>[@tag]=N, block:<x>,<y>,<z>=<id>, block:<x>,<y>,<z>?, block:<x>,<y>,<z>!, biome:<x>,<y>,<z>=<id>, team:<name>[?|!|=N|@member], bossbar:<id>[?|!|:<field>=<value>], item:<player>:<id>[@slot]=N, player:<name>[:<field>=<value>], world:<field>=<value>, gamerule:<rule>=<value>, gamerule:<rule>?, gamerule:<rule>!, random-sequence:<name>=N, scheduled:<id>=<dueTick>, scheduled:<id>?, scheduled:<id>!, snapshot:<path>=<json>, snapshot:<path>?, snapshot:<path>!, diff:<json-pointer>[=<kind>], event-trace:<player>:<type>[=N], trace:<root>=N, trace:<text>, trace-output:<text>[@target], diagnostic=N, diagnostic:<code>[=N], diagnostic:<code>:<text>[=N], warning=N, warning:<text>, unsupported=N, unsupported:<text>, output:<text>, output-count:<text>=N, output-order:<N>:<text>, output-exact:<text>, output-matches:<regex>, output-command:<command>[=N|?|!], output-channel:<channel>[=N|?|!], output-target:<target>[=N|?|!], output-normalized:<text>, output-normalized-exact:<text>, output-normalized-matches:<regex>, output-segment:<text>[|color=<color>|bold=<true|false>][@target], output-segment-exact:<text>[...], output-segment-matches:<regex>[...], or output-payload:<command>:<path>[=<json>]",
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

    private fun parseBlockAssertion(spec: String, label: String): JsonObject {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "$label block shorthand must be block:<x>,<y>,<z>=<id>, block:<x>,<y>,<z>?, or block:<x>,<y>,<z>!",
            )
        }
        val block = when {
            trimmed.endsWith("?") -> blockAssertionObject(trimmed.dropLast(1), label).also { it.addProperty("exists", true) }
            trimmed.endsWith("!") -> blockAssertionObject(trimmed.dropLast(1), label).also { it.addProperty("exists", false) }
            else -> {
                val splitAt = trimmed.indexOf('=')
                if (splitAt <= 0) {
                    throw SandboxException(
                        DiagnosticCode.INPUT_FORMAT,
                        "$label block shorthand must be block:<x>,<y>,<z>=<id>, block:<x>,<y>,<z>?, or block:<x>,<y>,<z>!",
                    )
                }
                val id = trimmed.substring(splitAt + 1).trim()
                if (id.isEmpty()) {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label block shorthand id must not be empty")
                }
                blockAssertionObject(trimmed.substring(0, splitAt), label).also { it.addProperty("id", id) }
            }
        }
        return JsonObject().also { it.add("block", block) }
    }

    private fun blockAssertionObject(rawPos: String, label: String): JsonObject {
        val parts = rawPos.split(",")
        if (parts.size != 3 || parts.any { it.trim().toIntOrNull() == null }) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label block coordinates must be x,y,z")
        }
        val pos = JsonArray().also { array -> parts.map { it.trim().toInt() }.forEach { array.add(it) } }
        return JsonObject().also { it.add("pos", pos) }
    }

    private fun parseBiomeAssertion(spec: String, label: String): JsonObject {
        val trimmed = spec.trim()
        val splitAt = trimmed.indexOf('=')
        if (splitAt <= 0 || splitAt == trimmed.lastIndex) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label biome shorthand must be biome:<x>,<y>,<z>=<id>")
        }
        val id = trimmed.substring(splitAt + 1).trim()
        if (id.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label biome shorthand id must not be empty")
        }
        val biome = blockAssertionObject(trimmed.substring(0, splitAt), label).also { it.addProperty("id", ResourceLocation.parse(id).toString()) }
        val world = JsonObject().also { it.add("biome", biome) }
        return JsonObject().also { it.add("world", world) }
    }

    private fun parseTeamAssertion(spec: String, label: String): JsonObject {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label team shorthand must be team:<name>[?|!|=N|@member]")
        }
        val team = when {
            trimmed.endsWith("?") -> teamAssertionObject(trimmed.dropLast(1), label).also { it.addProperty("exists", true) }
            trimmed.endsWith("!") -> teamAssertionObject(trimmed.dropLast(1), label).also { it.addProperty("exists", false) }
            "@" in trimmed -> {
                val splitAt = trimmed.indexOf('@')
                val member = trimmed.substring(splitAt + 1).trim()
                if (member.isEmpty()) {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label team member shorthand must be team:<name>@<member>")
                }
                teamAssertionObject(trimmed.substring(0, splitAt), label).also { it.addProperty("member", member) }
            }
            "=" in trimmed -> {
                val splitAt = trimmed.indexOf('=')
                val count = trimmed.substring(splitAt + 1).trim().toIntOrNull()
                    ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label team member count must be an integer")
                teamAssertionObject(trimmed.substring(0, splitAt), label).also { it.addProperty("memberCount", count) }
            }
            else -> teamAssertionObject(trimmed, label).also { it.addProperty("exists", true) }
        }
        return JsonObject().also { it.add("team", team) }
    }

    private fun teamAssertionObject(name: String, label: String): JsonObject {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label team shorthand name must not be empty")
        }
        return JsonObject().also { it.addProperty("name", trimmed) }
    }

    private fun parseBossbarAssertion(spec: String, label: String): JsonObject {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label bossbar shorthand must be bossbar:<id>[?|!|:<field>=<value>]")
        }
        val bossbar = when {
            trimmed.endsWith("?") -> bossbarAssertionObject(trimmed.dropLast(1), label).also { it.addProperty("exists", true) }
            trimmed.endsWith("!") -> bossbarAssertionObject(trimmed.dropLast(1), label).also { it.addProperty("exists", false) }
            "=" in trimmed -> parseBossbarFieldAssertion(trimmed, label)
            else -> bossbarAssertionObject(trimmed, label).also { it.addProperty("exists", true) }
        }
        return JsonObject().also { it.add("bossbar", bossbar) }
    }

    private fun parseBossbarFieldAssertion(spec: String, label: String): JsonObject {
        val splitAt = spec.indexOf('=')
        val left = spec.substring(0, splitAt).trim()
        val value = spec.substring(splitAt + 1).trim()
        val fieldSeparator = left.lastIndexOf(':')
        if (fieldSeparator <= 0 || fieldSeparator == left.lastIndex || value.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label bossbar field shorthand must be bossbar:<id>:<field>=<value>")
        }
        val field = left.substring(fieldSeparator + 1).trim()
        return bossbarAssertionObject(left.substring(0, fieldSeparator), label).also { bossbar ->
            when (field) {
                "value", "max" -> bossbar.addProperty(
                    field,
                    value.toIntOrNull() ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label bossbar $field must be an integer"),
                )
                "visible" -> bossbar.addProperty("visible", parseBossbarBoolean(value, field, label))
                "name", "color", "style", "player" -> bossbar.addProperty(field, value)
                else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label unsupported bossbar field '$field'; use name, value, max, color, style, visible, or player")
            }
        }
    }

    private fun bossbarAssertionObject(id: String, label: String): JsonObject {
        val trimmed = id.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label bossbar id must not be empty")
        }
        return JsonObject().also { it.addProperty("id", ResourceLocation.parse(trimmed).toString()) }
    }

    private fun parseBossbarBoolean(value: String, field: String, label: String): Boolean =
        when (value.lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label bossbar $field expected true or false but got '$value'")
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
                "xp", "xpLevels", "xpLevel", "food", "inventoryCount" -> player.addProperty(field, parsePlayerInt(value, field, label))
                "selectedSlot", "slot" -> player.addProperty("selectedSlot", parsePlayerInt(value, field, label))
                "health" -> player.addProperty("health", parsePlayerDouble(value, field, label))
                "gameMode", "gamemode" -> player.addProperty("gameMode", value)
                "dimension" -> player.addProperty("dimension", value)
                else -> throw SandboxException(
                    DiagnosticCode.INPUT_FORMAT,
                    "$label unsupported player shorthand field '$field'; use xp, xpLevels, health, food, selectedSlot, slot, inventoryCount, gameMode, gamemode, or dimension",
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

    private fun parseWorldAssertion(spec: String, label: String): JsonObject {
        val splitAt = spec.indexOf('=')
        if (splitAt <= 0 || splitAt == spec.lastIndex) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label world shorthand must be world:<field>=<value>")
        }
        val field = spec.substring(0, splitAt).trim()
        val value = spec.substring(splitAt + 1).trim()
        if (field.isEmpty() || value.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label world shorthand must be world:<field>=<value>")
        }
        val world = JsonObject().also { json ->
            when (field) {
                "gameTime", "dayTime", "time", "seed" -> json.addProperty(field, parseWorldLong(value, field, label))
                "weatherDuration" -> json.addProperty(field, parseWorldInt(value, field, label))
                "weather", "difficulty", "defaultGameMode", "defaultGamemode" -> json.addProperty(field, value)
                else -> throw SandboxException(
                    DiagnosticCode.INPUT_FORMAT,
                    "$label unsupported world shorthand field '$field'; use gameTime, dayTime, time, seed, weather, weatherDuration, difficulty, defaultGameMode, or defaultGamemode",
                )
            }
        }
        return JsonObject().also { it.add("world", world) }
    }

    private fun parseWorldLong(value: String, field: String, label: String): Long =
        value.toLongOrNull()
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label world field '$field' expected integer but got '$value'")

    private fun parseWorldInt(value: String, field: String, label: String): Int =
        value.toIntOrNull()
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label world field '$field' expected integer but got '$value'")

    private fun parseGameruleAssertion(spec: String, label: String): JsonObject {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label gamerule shorthand must be gamerule:<rule>=<value>, gamerule:<rule>?, or gamerule:<rule>!")
        }
        val snapshot = when {
            trimmed.endsWith("?") -> gameruleSnapshotAssertion(trimmed.dropLast(1), label).also { it.addProperty("exists", true) }
            trimmed.endsWith("!") -> gameruleSnapshotAssertion(trimmed.dropLast(1), label).also { it.addProperty("missing", true) }
            else -> {
                val splitAt = trimmed.indexOf('=')
                if (splitAt <= 0 || splitAt == trimmed.lastIndex) {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label gamerule shorthand must be gamerule:<rule>=<value>, gamerule:<rule>?, or gamerule:<rule>!")
                }
                val value = trimmed.substring(splitAt + 1).trim()
                gameruleSnapshotAssertion(trimmed.substring(0, splitAt), label).also { it.add("equals", JsonPrimitive(value)) }
            }
        }
        return JsonObject().also { it.add("snapshot", snapshot) }
    }

    private fun gameruleSnapshotAssertion(rule: String, label: String): JsonObject {
        val trimmed = rule.trim()
        if (trimmed.isEmpty() || '.' in trimmed || '[' in trimmed || ']' in trimmed) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label gamerule name must be a non-empty simple path segment")
        }
        return JsonObject().also { it.addProperty("path", "gamerules.$trimmed") }
    }

    private fun parseRandomSequenceAssertion(spec: String, label: String): JsonObject {
        val splitAt = spec.indexOf('=')
        if (splitAt <= 0 || splitAt == spec.lastIndex) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label random sequence shorthand must be random-sequence:<name>=N")
        }
        val name = spec.substring(0, splitAt).trim()
        val valueText = spec.substring(splitAt + 1).trim()
        if (name.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label random sequence shorthand name must not be empty")
        }
        val expected = valueText.toLongOrNull()
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label random sequence shorthand expected integer but got '$valueText'")
        val randomSequences = JsonObject().also { it.addProperty(name, expected) }
        val world = JsonObject().also { it.add("randomSequences", randomSequences) }
        return JsonObject().also { it.add("world", world) }
    }

    private fun parseScheduledAssertion(spec: String, label: String): JsonObject {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label scheduled shorthand must be scheduled:<id>=<dueTick>, scheduled:<id>?, or scheduled:<id>!")
        }
        val snapshot = when {
            trimmed.endsWith("?") -> scheduledSnapshotAssertion(trimmed.dropLast(1), label).also {
                it.addProperty("exists", true)
            }
            trimmed.endsWith("!") -> scheduledSnapshotAssertion(trimmed.dropLast(1), label).also {
                it.addProperty("missing", true)
            }
            else -> {
                val splitAt = trimmed.indexOf('=')
                if (splitAt <= 0 || splitAt == trimmed.lastIndex) {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label scheduled shorthand must be scheduled:<id>=<dueTick>, scheduled:<id>?, or scheduled:<id>!")
                }
                val expected = trimmed.substring(splitAt + 1).trim().toLongOrNull()
                    ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label scheduled dueTick must be an integer")
                scheduledSnapshotAssertion(trimmed.substring(0, splitAt), label, ".dueTick").also {
                    it.add("equals", JsonPrimitive(expected))
                }
            }
        }
        return JsonObject().also { it.add("snapshot", snapshot) }
    }

    private fun scheduledSnapshotAssertion(rawId: String, label: String, suffix: String = ""): JsonObject {
        val idText = rawId.trim()
        if (idText.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label scheduled function id must not be empty")
        }
        val id = ResourceLocation.parse(idText)
        return JsonObject().also { it.addProperty("path", """scheduled[{function:"$id"}]$suffix""") }
    }

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
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label event trace shorthand must be event-trace:<player>:<type>[@x,y,z][=N]")
        }
        val splitAt = trimmed.indexOf('=')
        val left = if (splitAt < 0) trimmed else trimmed.substring(0, splitAt)
        val countText = splitAt.takeIf { it >= 0 }?.let { trimmed.substring(it + 1).trim() }
        val separator = left.indexOf(':')
        if (separator <= 0 || separator == left.lastIndex) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label event trace shorthand must be event-trace:<player>:<type>[@x,y,z][=N]")
        }
        val player = left.substring(0, separator).trim()
        val rawType = left.substring(separator + 1).trim()
        val coordSeparator = rawType.lastIndexOf('@')
        val type = if (coordSeparator >= 0) rawType.substring(0, coordSeparator).trim() else rawType
        val blockPos = coordSeparator.takeIf { it >= 0 }?.let {
            parseEventTraceBlockCoordinates(rawType.substring(it + 1), label)
        }
        if (player.isEmpty() || type.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label event trace shorthand must be event-trace:<player>:<type>[@x,y,z][=N]")
        }
        val eventTrace = JsonObject().also { json ->
            json.addProperty("player", player)
            json.addProperty("type", type)
            blockPos?.let { (x, y, z) ->
                json.addProperty("blockX", x)
                json.addProperty("blockY", y)
                json.addProperty("blockZ", z)
            }
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

    private fun parseEventTraceBlockCoordinates(raw: String, label: String): List<Int> {
        val parts = raw.split(",")
        if (parts.size != 3 || parts.any { it.trim().toIntOrNull() == null }) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label event trace block coordinates must be x,y,z")
        }
        return parts.map { it.trim().toInt() }
    }

    private fun parseSnapshotAssertion(spec: String, label: String): JsonObject {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "$label snapshot shorthand must be snapshot:<path>=<json>, snapshot:<path>?, or snapshot:<path>!",
            )
        }
        val snapshot = when {
            trimmed.endsWith("?") -> snapshotAssertionObject(trimmed.dropLast(1), label).also { it.addProperty("exists", true) }
            trimmed.endsWith("!") -> snapshotAssertionObject(trimmed.dropLast(1), label).also { it.addProperty("missing", true) }
            else -> {
                val splitAt = trimmed.indexOf('=')
                if (splitAt <= 0) {
                    throw SandboxException(
                        DiagnosticCode.INPUT_FORMAT,
                        "$label snapshot shorthand must be snapshot:<path>=<json>, snapshot:<path>?, or snapshot:<path>!",
                    )
                }
                val expectedText = trimmed.substring(splitAt + 1).trim()
                val expected = try {
                    JsonParser.parseString(expectedText)
                } catch (error: Exception) {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label snapshot shorthand expected JSON value but got '$expectedText'", cause = error)
                }
                snapshotAssertionObject(trimmed.substring(0, splitAt), label).also { it.add("equals", expected) }
            }
        }
        return JsonObject().also { it.add("snapshot", snapshot) }
    }

    private fun snapshotAssertionObject(path: String, label: String): JsonObject {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label snapshot shorthand path must not be empty")
        }
        return JsonObject().also { it.addProperty("path", trimmed) }
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

    private fun parseDiagnosticCountAssertion(count: String, label: String): JsonObject {
        val expected = count.trim().toIntOrNull()
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label diagnostic shorthand expected integer but got '${count.trim()}'")
        val diagnostic = JsonObject().also { it.addProperty("count", expected) }
        return JsonObject().also { it.add("diagnostic", diagnostic) }
    }

    private fun parseDiagnosticAssertion(spec: String, label: String): JsonObject {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label diagnostic shorthand must be diagnostic:<code>[=N], diagnostic:<code>:<text>[=N], or diagnostic:<text>")
        }
        val splitAt = trimmed.lastIndexOf('=')
        val left = if (splitAt < 0) trimmed else trimmed.substring(0, splitAt).trim()
        val count = splitAt.takeIf { it >= 0 }?.let {
            val countText = trimmed.substring(it + 1).trim()
            countText.toIntOrNull()
                ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label diagnostic count must be an integer")
        }
        if (left.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label diagnostic shorthand must include a code or text")
        }
        val colon = left.indexOf(':')
        val code = when {
            colon > 0 -> diagnosticCodeName(left.substring(0, colon))
            else -> diagnosticCodeName(left)
        }
        val contains = when {
            colon > 0 && code != null -> left.substring(colon + 1).trim()
            code == null -> left
            else -> null
        }
        if (colon > 0 && code == null) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label diagnostic code '${left.substring(0, colon)}' is not supported")
        }
        if (contains != null && contains.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label diagnostic contains text must not be empty")
        }
        val diagnostic = JsonObject().also { json ->
            code?.let { json.addProperty("code", it) }
            contains?.let { json.addProperty("contains", it) }
            count?.let { json.addProperty("count", it) }
        }
        return JsonObject().also { it.add("diagnostic", diagnostic) }
    }

    private fun diagnosticCodeName(raw: String): String? =
        runCatching { DiagnosticCode.valueOf(raw.trim().uppercase()).name }.getOrNull()

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
        return parseOutputTextAssertion(text, "contains", "output", label)
    }

    private fun parseOutputExactAssertion(text: String, label: String): JsonObject {
        return parseOutputTextAssertion(text, "text", "output-exact", label)
    }

    private fun parseOutputMatchesAssertion(text: String, label: String): JsonObject {
        return parseOutputTextAssertion(text, "matches", "output-matches", label)
    }

    private fun parseOutputCountAssertion(spec: String, label: String): JsonObject {
        val splitAt = spec.lastIndexOf('=')
        if (splitAt <= 0 || splitAt == spec.lastIndex) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label output count shorthand must be output-count:<text>=N")
        }
        val contains = spec.substring(0, splitAt).trim()
        val countText = spec.substring(splitAt + 1).trim()
        val count = countText.toIntOrNull()
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label output count must be an integer")
        if (contains.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label output count text must not be empty")
        }
        val output = JsonObject().also { json ->
            json.addProperty("contains", contains)
            json.addProperty("count", count)
        }
        return JsonObject().also { it.add("output", output) }
    }

    private fun parseOutputOrderAssertion(spec: String, label: String): JsonObject {
        val separator = spec.indexOf(':')
        if (separator <= 0 || separator == spec.lastIndex) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label output order shorthand must be output-order:<N>:<text>")
        }
        val orderText = spec.substring(0, separator).trim()
        val order = orderText.toIntOrNull()
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label output order must be an integer")
        if (order <= 0) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label output order must be one or greater")
        }
        val contains = spec.substring(separator + 1).trim()
        if (contains.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label output order text must not be empty")
        }
        val output = JsonObject().also { json ->
            json.addProperty("contains", contains)
            json.addProperty("order", order)
        }
        return JsonObject().also { it.add("output", output) }
    }

    private fun parseNormalizedOutputAssertion(text: String, label: String): JsonObject {
        return parseOutputTextAssertion(text, "normalizedContains", "output-normalized", label)
    }

    private fun parseNormalizedOutputExactAssertion(text: String, label: String): JsonObject {
        return parseOutputTextAssertion(text, "normalizedText", "output-normalized-exact", label)
    }

    private fun parseNormalizedOutputMatchesAssertion(text: String, label: String): JsonObject {
        return parseOutputTextAssertion(text, "normalizedMatches", "output-normalized-matches", label)
    }

    private fun parseOutputTextAssertion(text: String, fieldName: String, shorthandName: String, label: String): JsonObject {
        val value = text.trim()
        if (value.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label $shorthandName shorthand must be $shorthandName:<text>")
        }
        val output = JsonObject().also { it.addProperty(fieldName, value) }
        return JsonObject().also { it.add("output", output) }
    }

    private fun parseOutputChannelAssertion(spec: String, label: String): JsonObject {
        return parseOutputFieldAssertion(spec, "channel", "output-channel", label)
    }

    private fun parseOutputFieldAssertion(spec: String, fieldName: String, shorthandName: String, label: String): JsonObject {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label $shorthandName shorthand must be $shorthandName:<value>[=N|?|!]")
        }
        val output = when {
            trimmed.endsWith("?") -> outputFieldObject(trimmed.dropLast(1), fieldName, shorthandName, label)
            trimmed.endsWith("!") -> outputFieldObject(trimmed.dropLast(1), fieldName, shorthandName, label).also { it.addProperty("count", 0) }
            else -> {
                val splitAt = trimmed.indexOf('=')
                if (splitAt <= 0 || splitAt == trimmed.lastIndex) {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label $shorthandName shorthand must be $shorthandName:<value>[=N|?|!]")
                }
                val countText = trimmed.substring(splitAt + 1).trim()
                val count = countText.toIntOrNull()
                    ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label $shorthandName count must be an integer")
                outputFieldObject(trimmed.substring(0, splitAt), fieldName, shorthandName, label).also { it.addProperty("count", count) }
            }
        }
        return JsonObject().also { it.add("output", output) }
    }

    private fun outputFieldObject(value: String, fieldName: String, shorthandName: String, label: String): JsonObject {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label $shorthandName value must not be empty")
        }
        return JsonObject().also { it.addProperty(fieldName, trimmed) }
    }

    private fun parseOutputSegmentAssertion(spec: String, label: String): JsonObject {
        return parseOutputSegmentAssertion(spec, "contains", "output-segment", label)
    }

    private fun parseOutputSegmentExactAssertion(spec: String, label: String): JsonObject {
        return parseOutputSegmentAssertion(spec, "text", "output-segment-exact", label)
    }

    private fun parseOutputSegmentMatchesAssertion(spec: String, label: String): JsonObject {
        return parseOutputSegmentAssertion(spec, "matches", "output-segment-matches", label)
    }

    private fun parseOutputSegmentAssertion(spec: String, matchFieldName: String, shorthandName: String, label: String): JsonObject {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "$label $shorthandName shorthand must be $shorthandName:<text>[|color=<color>|bold=<true|false>|italic=<true|false>|underlined=<true|false>|strikethrough=<true|false>|obfuscated=<true|false>][@target]",
            )
        }
        val targetSplit = trimmed.lastIndexOf('@')
        val segmentSpec = if (targetSplit < 0) trimmed else trimmed.substring(0, targetSplit).trim()
        val target = targetSplit.takeIf { it >= 0 }?.let { trimmed.substring(it + 1).trim() }
        if (segmentSpec.isEmpty() || target?.isEmpty() == true) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "$label $shorthandName shorthand must be $shorthandName:<text>[|color=<color>|bold=<true|false>|italic=<true|false>|underlined=<true|false>|strikethrough=<true|false>|obfuscated=<true|false>][@target]",
            )
        }
        val parts = segmentSpec.split('|').map { it.trim() }
        val matchText = parts.first()
        if (matchText.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label $shorthandName text must not be empty")
        }
        val segment = JsonObject().also { it.addProperty(matchFieldName, matchText) }
        parts.drop(1).forEach { option ->
            val splitAt = option.indexOf('=')
            if (splitAt <= 0 || splitAt == option.lastIndex) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label output segment option must be <name>=<value>")
            }
            val name = option.substring(0, splitAt).trim()
            val value = option.substring(splitAt + 1).trim()
            when (name) {
                "color" -> segment.addProperty("color", value)
                "bold", "italic", "underlined", "strikethrough", "obfuscated" ->
                    segment.addProperty(name, parseOutputSegmentBoolean(value, name, label))
                else -> throw SandboxException(
                    DiagnosticCode.INPUT_FORMAT,
                    "$label unsupported output segment option '$name'; use color, bold, italic, underlined, strikethrough, or obfuscated",
                )
            }
        }
        val output = JsonObject().also { json ->
            target?.let { json.addProperty("target", it) }
            json.add("segment", segment)
        }
        return JsonObject().also { it.add("output", output) }
    }

    private fun parseOutputSegmentBoolean(value: String, name: String, label: String): Boolean =
        when (value.lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label output segment option '$name' expected true or false but got '$value'")
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
                total += executeDirectCommand(
                    sandbox,
                    normalized,
                    SourceLocation(file = sourceName, line = index + 1, command = normalized),
                )
            }
        }
        return total
    }

    private fun executeDirectCommand(sandbox: DatapackSandbox, command: String, location: SourceLocation): Int =
        try {
            sandbox.executeCommand(command, location).commandsExecuted
        } catch (error: SandboxException) {
            if (!allowCommandFailure) throw error
            sandbox.world.traces.lastOrNull {
                it.command == command &&
                    it.source?.file == location.file &&
                    it.source?.line == location.line
            }?.commandsExecuted ?: 0
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
            val blockPosText = event.blockPos?.let { ", blockPos=${it.x},${it.y},${it.z}" }.orEmpty()
            println(ConsoleStyle.green("OK event player ${player.name} ${event.type}") + ConsoleStyle.dim(" (updates=${updates.size}$inputText$blockPosText)"))
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
    private val check by option("--check").path()

    override fun run() {
        try {
            if (output != null && check != null) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "schema accepts only one file mode: --output or --check")
            }
            val schema = ManifestSchema.readText()
            val checkPath = check
            if (checkPath != null) {
                checkSchemaFile(checkPath, schema)
                return
            }
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

    private fun checkSchemaFile(path: Path, schema: String) {
        if (!Files.exists(path)) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "schema check file does not exist: $path")
        }
        val actual = normalizeNewlines(Files.readString(path, StandardCharsets.UTF_8)).trimEnd()
        val expected = normalizeNewlines(schema).trimEnd()
        if (actual != expected) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "schema is out of date: $path; regenerate with schema --output <file>",
            )
        }
        println(ConsoleStyle.green("schema up to date: $path"))
    }

    private fun normalizeNewlines(value: String): String =
        value.replace("\r\n", "\n").replace('\r', '\n')
}

class DiffCommand : CliktCommand(name = "diff") {
    private val before by argument("before").path(mustExist = true)
    private val after by argument("after").path(mustExist = true).optional()
    private val snapshot by option("--snapshot").flag(default = false)
    private val state by option("--state").flag(default = false)
    private val json by option("--json").flag(default = false)
    private val output by option("--output", "-o").path()
    private val check by option("--check").flag(default = false)
    private val script by option("--script").flag(default = false)

    override fun run() {
        try {
            if (script) {
                if (after != null) {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "diff --script expects one manifest path, not before/after JSON inputs")
                }
                if (snapshot || state || json || check) {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "diff --script supports --output only; remove --snapshot, --state, --json, or --check")
                }
                emit(ManifestRunner.exportExternalDiffScript(before), writtenMessage = "diff script written")
                return
            }
            val afterPath = after
                ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "diff requires <after.json>; use --script with a single manifest to export an external replay script")
            val beforeJson = readComparisonJson(before)
            val afterJson = readComparisonJson(afterPath)
            val entries = if (state) {
                SnapshotDiff.stateDiff(beforeJson, afterJson)
            } else {
                SnapshotDiff.diff(beforeJson, afterJson)
            }
            val content = if (json) JsonValues.render(SnapshotDiff.toJson(entries)) else SnapshotDiff.render(entries)
            emit(content, writtenMessage = "diff output written")
            if (check && entries.isNotEmpty()) {
                exitProcess(ExitCodes.ASSERTION_FAILED)
            }
        } catch (error: SandboxException) {
            println(ConsoleStyle.diagnostic(error.render()))
            exitProcess(ExitCodes.forException(error))
        }
    }

    private fun readComparisonJson(path: Path): JsonElement {
        val parsed = try {
            JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
        } catch (error: Exception) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid JSON for diff input $path", cause = error)
        }
        return if (snapshot) extractSingleSnapshot(path, parsed) else parsed
    }

    private fun extractSingleSnapshot(path: Path, root: JsonElement): JsonElement {
        val snapshots = collectSnapshots(root)
        return when (snapshots.size) {
            1 -> snapshots.single().deepCopy()
            0 -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "diff --snapshot input has no snapshot field: $path")
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "diff --snapshot input has ${snapshots.size} snapshots; compare a single attempt or a raw snapshot: $path")
        }
    }

    private fun collectSnapshots(root: JsonElement): List<JsonElement> =
        when {
            root.isJsonObject -> {
                val obj = root.asJsonObject
                when {
                    obj.has("snapshot") -> listOf(obj.get("snapshot"))
                    obj.has("attempts") && obj.get("attempts").isJsonArray -> obj.getAsJsonArray("attempts").flatMap(::collectSnapshots)
                    else -> emptyList()
                }
            }
            root.isJsonArray -> root.asJsonArray.flatMap(::collectSnapshots)
            else -> emptyList()
        }

    private fun emit(content: String, writtenMessage: String) {
        val outputPath = output
        if (outputPath == null) {
            println(content)
        } else {
            outputPath.parent?.let(Files::createDirectories)
            Files.writeString(outputPath, content, StandardCharsets.UTF_8)
            println(ConsoleStyle.green("$writtenMessage: $outputPath"))
        }
    }
}

class BenchmarkCommand : CliktCommand(name = "benchmark") {
    private val version by option("--version", "-v").default(VersionProfiles.default.id)
    private val packs by option("--pack", "-p").path(mustExist = true).multiple()
    private val scale by option("--scale").int().default(50)
    private val lootTable by option("--loot-table")
    private val lootContext by option("--loot-context").default("minecraft:empty")
    private val seed by option("--seed").long().default(0)
    private val json by option("--json").flag(default = false)
    private val output by option("--output", "-o").path()

    override fun run() {
        try {
            require(scale > 0) { "benchmark --scale must be positive" }
            val results = mutableListOf<BenchmarkScenario>()
            if (packs.isNotEmpty()) {
                results += timed("pack-load") {
                    val sandbox = createSandbox(version, packs)
                    val resources = ManifestRunner.summarizeResources(sandbox)
                    mapOf(
                        "packs" to packs.size,
                        "resources" to resources.resourceIndex,
                    )
                }
            }
            results += runScoreboardBenchmark()
            results += runStorageBenchmark()
            results += runFunctionChainBenchmark()
            results += runManifestBatchBenchmark()
            lootTable?.let { results += runLootBenchmark(it) }
            emit(if (json) renderJson(results) else renderPlain(results))
        } catch (error: IllegalArgumentException) {
            println(ConsoleStyle.diagnostic("${DiagnosticCode.INPUT_FORMAT}: ${error.message}"))
            exitProcess(ExitCodes.INPUT_FORMAT)
        } catch (error: SandboxException) {
            println(ConsoleStyle.diagnostic(error.render()))
            exitProcess(ExitCodes.forException(error))
        }
    }

    private fun runScoreboardBenchmark(): BenchmarkScenario =
        timed("scoreboard") {
            val sandbox = createBenchmarkSandbox()
            sandbox.executeCommand("scoreboard objectives add bench dummy")
            repeat(scale) { index ->
                sandbox.executeCommand("scoreboard players set #bench_$index bench $index")
            }
            mapOf(
                "commands" to scale + 1,
                "scores" to scale,
                "snapshotBytes" to sandbox.snapshotString().toByteArray(StandardCharsets.UTF_8).size,
            )
        }

    private fun runStorageBenchmark(): BenchmarkScenario =
        timed("storage") {
            val sandbox = createBenchmarkSandbox()
            val payload = (0 until scale).joinToString(prefix = "{", postfix = "}") { index -> "v$index:$index" }
            sandbox.executeCommand("data merge storage benchmark:large $payload")
            mapOf(
                "commands" to 1,
                "entries" to scale,
                "snapshotBytes" to sandbox.snapshotString().toByteArray(StandardCharsets.UTF_8).size,
            )
        }

    private fun runFunctionChainBenchmark(): BenchmarkScenario =
        timed("function-chain") {
            val depth = minOf(scale, 48)
            val sources = buildList {
                add(FunctionSource.text("bench:main", "scoreboard objectives add bench dummy\nfunction bench:f0", "<benchmark:main>"))
                repeat(depth) { index ->
                    val next = if (index == depth - 1) {
                        "scoreboard players add #chain bench 1"
                    } else {
                        "function bench:f${index + 1}"
                    }
                    add(FunctionSource.text("bench:f$index", next, "<benchmark:f$index>"))
                }
            }
            val sandbox = createFunctionSandbox(version, packs, sources)
            val executed = sandbox.runFunction("bench:main").commandsExecuted
            mapOf(
                "depth" to depth,
                "commands" to executed,
                "score" to sandbox.world.getScore("#chain", "bench"),
            )
        }

    private fun runManifestBatchBenchmark(): BenchmarkScenario =
        timed("manifest-batch") {
            val dir = Files.createTempDirectory("dps-benchmark-manifest")
            val pack = dir.resolve("pack")
            Files.createDirectories(pack)
            Files.writeString(
                pack.resolve("pack.mcmeta"),
                """{"pack":{"pack_format":${VersionProfiles.get(version).dataPackFormat},"description":"Benchmark manifest pack"}}""",
                StandardCharsets.UTF_8,
            )
            val commands = buildString {
                append("scoreboard objectives add bench dummy")
                repeat(scale) { index ->
                    append("\nscoreboard players set #manifest_$index bench $index")
                }
            }
            val manifest = dir.resolve("benchmark.dps.json")
            val root = JsonObject().also { json ->
                json.addProperty("version", version)
                json.add(
                    "packs",
                    JsonArray().also { packArray -> packArray.add(pack.toString()) },
                )
                json.add(
                    "steps",
                    JsonArray().also { steps ->
                        steps.add(
                            JsonObject().also { step ->
                                step.addProperty("functionText", commands)
                                step.addProperty("source", "<benchmark:manifest>")
                            },
                        )
                    },
                )
                json.add(
                    "assertions",
                    JsonArray().also { assertions ->
                        assertions.add(
                            JsonObject().also { assertion ->
                                assertion.add(
                                    "score",
                                    JsonObject().also { score ->
                                        score.addProperty("target", "#manifest_${scale - 1}")
                                        score.addProperty("objective", "bench")
                                        score.addProperty("equals", scale - 1)
                                    },
                                )
                            },
                        )
                    },
                )
            }
            Files.writeString(manifest, JsonValues.render(root), StandardCharsets.UTF_8)
            val result = ManifestRunner.run(manifest)
            if (!result.passed) {
                throw SandboxException(DiagnosticCode.ASSERTION_FAILED, "benchmark manifest failed: ${result.messages.joinToString("; ")}")
            }
            mapOf(
                "manifests" to 1,
                "commands" to scale + 1,
                "assertions" to 1,
            )
        }

    private fun runLootBenchmark(table: String): BenchmarkScenario =
        timed("loot-sampling") {
            val sandbox = createBenchmarkSandbox()
            var items = 0
            repeat(scale) { index ->
                items += sandbox.generateLoot(
                    ResourceLocation.parse(table),
                    ResourceLocation.parse(lootContext),
                    seed = seed + index,
                ).items.size
            }
            mapOf(
                "samples" to scale,
                "items" to items,
                "table" to table,
                "context" to lootContext,
            )
        }

    private fun createBenchmarkSandbox(): DatapackSandbox =
        if (packs.isEmpty()) {
            createFunctionSandbox(version, listOf(FunctionSource.text("bench:noop", "", "<benchmark:noop>")))
        } else {
            createSandbox(version, packs)
        }

    private fun timed(name: String, block: () -> Map<String, Any>): BenchmarkScenario {
        val start = System.nanoTime()
        val metrics = block()
        val elapsedNanos = System.nanoTime() - start
        return BenchmarkScenario(name, elapsedNanos, metrics)
    }

    private fun renderPlain(results: List<BenchmarkScenario>): String =
        buildString {
            appendLine("benchmark version=$version scale=$scale")
            results.forEach { result ->
                append(result.name)
                append(" elapsedMs=")
                append(result.elapsedMillisString())
                result.metrics.forEach { (key, value) -> append(" $key=$value") }
                appendLine()
            }
        }.trimEnd()

    private fun renderJson(results: List<BenchmarkScenario>): String =
        JsonValues.render(
            JsonObject().also { root ->
                root.addProperty("version", version)
                root.addProperty("scale", scale)
                root.add(
                    "scenarios",
                    JsonArray().also { array ->
                        results.forEach { result -> array.add(result.toJson()) }
                    },
                )
            },
        )

    private fun emit(content: String) {
        val outputPath = output
        if (outputPath == null) {
            println(content)
        } else {
            outputPath.parent?.let(Files::createDirectories)
            Files.writeString(outputPath, content, StandardCharsets.UTF_8)
            println(ConsoleStyle.green("benchmark output written: $outputPath"))
        }
    }

    private data class BenchmarkScenario(
        val name: String,
        val elapsedNanos: Long,
        val metrics: Map<String, Any>,
    ) {
        fun elapsedMillisString(): String = "%.3f".format(java.util.Locale.ROOT, elapsedNanos / 1_000_000.0)

        fun toJson(): JsonObject =
            JsonObject().also { json ->
                json.addProperty("name", name)
                json.addProperty("elapsedNanos", elapsedNanos)
                json.addProperty("elapsedMs", elapsedNanos / 1_000_000.0)
                metrics.forEach { (key, value) ->
                    when (value) {
                        is Int -> json.addProperty(key, value)
                        is Long -> json.addProperty(key, value)
                        is Double -> json.addProperty(key, value)
                        is Float -> json.addProperty(key, value)
                        is Boolean -> json.addProperty(key, value)
                        else -> json.addProperty(key, value.toString())
                    }
                }
            }
    }
}

class VersionCommand : CliktCommand(name = "version") {
    private val versions by argument("version").multiple(required = false)
    private val docs by option("--docs").flag(default = false)
    private val json by option("--json").flag(default = false)
    private val output by option("--output", "-o").path()
    private val docsCheck by option("--check").path()
    private val docsLocale by option("--locale").default("en")

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
            if (!docs && docsLocale != "en") {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "version --locale is only supported with --docs")
            }
            if (docs) {
                if (versions.isNotEmpty()) {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "version --docs does not accept profile arguments")
                }
                val locale = normalizedDocsLocale(docsLocale)
                val table = VersionProfileDocs.renderMarkdownTable(locale = locale)
                val checkPath = docsCheck
                if (checkPath == null) {
                    emit(table)
                } else {
                    checkDocsTable(checkPath, table, locale)
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

    private fun checkDocsTable(path: Path, table: String, locale: String) {
        if (!Files.exists(path)) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "version docs check file does not exist: $path")
        }
        val actual = normalizeNewlines(Files.readString(path, StandardCharsets.UTF_8))
        val expected = normalizeNewlines(table)
        if (!actual.contains(expected)) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "version docs are out of date: $path; regenerate with version --docs ${localeOption(locale)}--output <file>",
            )
        }
        println(ConsoleStyle.green("version docs up to date: $path"))
    }

    private fun normalizedDocsLocale(value: String): String =
        when (value.lowercase().replace('_', '-')) {
            "en", "en-us" -> "en"
            "zh", "zh-cn", "zh-hans", "zh-hans-cn" -> "zh-CN"
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Unsupported version docs locale '$value'; expected en or zh-CN")
        }

    private fun localeOption(locale: String): String =
        if (locale == "en") "" else "--locale $locale "

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
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "resources --check always validates the full catalog and accepts no filters")
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
        val rendered = if (json) {
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
        val rendered = if (json) {
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
        sourcePacks.flatMap { value ->
            listOf(value, Path.of(value).toAbsolutePath().normalize().toString())
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

    private fun renderLoadedPlain(summary: ManifestResourceSummary, entries: List<ResourceIndexEntry>): String =
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
        val overlay = listOfNotNull(
            entry.overrides?.let { "overrides=$it" },
            entry.overriddenBy?.let { "overriddenBy=$it" },
        ).joinToString(separator = " ").takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        return "resource ${entry.type} ${entry.id} ${entry.behaviorLevel.id} $state " +
            "pack=${entry.pack} order=${entry.order} file=${entry.file}$overlay"
    }

    private fun renderLoadedJson(summary: ManifestResourceSummary, entries: List<ResourceIndexEntry>): JsonObject =
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

    private fun renderRegistryPlain(version: String, source: String, groups: List<RegistryInspectionGroup>): String =
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

    private fun renderMarkdown(entries: List<ResourceCatalogEntry>, locale: String): String {
        val zh = locale.isZhCnDocsLocale()
        val resourceHeader = if (zh) "资源" else "Resource"
        val behaviorHeader = if (zh) "行为等级" else "Behavior"
        val surfaceHeader = if (zh) "运行时 / debug 表面" else "Runtime/debug surface"
        return buildString {
            appendLine("| $resourceHeader | $behaviorHeader | $surfaceHeader |")
            appendLine("|---|---|---|")
            entries.forEach { entry ->
                appendLine("| `${markdownCell(entry.type)}` | `${entry.behaviorLevel.id}` | ${markdownCell(resourceSummary(entry, locale))} |")
            }
        }.trimEnd()
    }

    private fun resourceSummary(entry: ResourceCatalogEntry, locale: String): String {
        if (!locale.isZhCnDocsLocale()) return entry.summary
        return when (entry.type) {
            "function" -> "mcfunction 执行、trace source location 和缺失引用检查。"
            "tag/function" -> "load/tick/function tag 执行和 `replace` 语义。"
            "loot_table" -> "支持上下文内的确定性 loot 生成和命令输出。"
            "predicate" -> "predicate 命令/API、advancement 条件、loot 条件和 item modifier。"
            "advancement" -> "玩家 progress、criteria 匹配、rewards、output 和事件 trace。"
            "recipe" -> "进入资源索引和玩家 recipe 状态，供命令与 rewards 使用。"
            "item_modifier" -> "`item modify` 会应用常用 item modifier 函数。"
            "tag/<registry>" -> "普通 tag 保留 `replace` 语义，并进入资源索引供 inspect。"
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

    private fun markdownCell(value: String): String =
        value.replace("|", "\\|").replace("\r", " ").replace("\n", " ")

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

    private fun checkResourceDocs(path: Path, entries: List<ResourceCatalogEntry>, locale: String) {
        if (!Files.exists(path)) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "resources check file does not exist: $path")
        }
        val docLines = normalizeDoc(Files.readString(path, StandardCharsets.UTF_8)).lineSequence().toList()
        val missing = entries.mapNotNull { entry ->
            val resourcePattern = Regex("""`[^`]*${Regex.escape(entry.type)}[^`]*`""")
            val covered = docLines.any { line -> resourcePattern.containsMatchIn(line) && "`${entry.behaviorLevel.id}`" in line }
            if (covered) null else "${entry.type} (${entry.behaviorLevel.id})"
        }
        if (missing.isNotEmpty()) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "resources docs are out of date: $path; missing ${missing.joinToString()}; regenerate with resources --docs ${localeOption(locale)}--output <file>",
            )
        }
        println(ConsoleStyle.green("resources docs cover catalog: $path"))
    }

    private fun normalizeDoc(value: String): String =
        value.replace("\r\n", "\n").replace('\r', '\n')

    private fun normalizedDocsLocale(value: String): String =
        when (value.lowercase().replace('_', '-')) {
            "en", "en-us" -> "en"
            "zh", "zh-cn", "zh-hans", "zh-hans-cn" -> "zh-CN"
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Unsupported resources docs locale '$value'; expected en or zh-CN")
        }

    private fun localeOption(locale: String): String =
        if (locale == "en") "" else "--locale $locale "

    private fun String.isZhCnDocsLocale(): Boolean =
        lowercase().replace('_', '-') in setOf("zh", "zh-cn", "zh-hans", "zh-hans-cn")
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
