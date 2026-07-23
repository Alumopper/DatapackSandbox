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
import moe.afox.dpsandbox.core.CommandTraceEvent
import moe.afox.dpsandbox.core.DatapackSandbox
import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.JsonValues
import moe.afox.dpsandbox.core.OutputEvent
import moe.afox.dpsandbox.core.PlayerEventTraceEvent
import moe.afox.dpsandbox.core.SandboxException
import moe.afox.dpsandbox.core.SandboxLimits
import moe.afox.dpsandbox.core.SnapshotDiff
import moe.afox.dpsandbox.core.UnsupportedFeatureMode
import moe.afox.dpsandbox.core.VersionProfiles
import moe.afox.dpsandbox.core.toJson
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) =
    DatapackSandboxCli()
        .subcommands(
            ReplCommand(),
            ViewportCommand(),
            ServeCommand(),
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
        ).main(args)

class DatapackSandboxCli :
    CliktCommand(
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
                        "Manifest schema validation failed:${System.lineSeparator()}${schemaFailures.joinToString(
                            System.lineSeparator(),
                        ) { "- $it" }}",
                    )
                }
            }
            var failed = false
            val traces = mutableListOf<CommandTraceEvent>()
            val eventTraces = mutableListOf<PlayerEventTraceEvent>()
            val outputs = mutableListOf<OutputEvent>()
            val results = mutableListOf<ManifestResult>()
            manifests.forEach { manifest ->
                val result =
                    ManifestRunner.run(
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
                val versionLabel =
                    result.attempts
                        .map { it.version }
                        .takeIf { it.size > 1 }
                        ?.joinToString(prefix = " [", postfix = "]")
                        .orEmpty()
                if (result.passed) {
                    println(ConsoleStyle.green("PASS ${manifest}$versionLabel"))
                } else {
                    failed = true
                    println(ConsoleStyle.red("FAIL ${manifest}$versionLabel"))
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

    private fun writeTraceFile(
        path: Path,
        traces: List<CommandTraceEvent>,
    ) {
        val content =
            traces.joinToString(separator = System.lineSeparator()) { event ->
                JsonValues.render(event.toJson())
            }
        Files.writeString(path, content, StandardCharsets.UTF_8)
        println(ConsoleStyle.green("trace written: $path"))
    }
}

internal fun writeOutputsFile(
    path: Path,
    outputs: List<OutputEvent>,
) {
    val content =
        outputs.joinToString(separator = System.lineSeparator()) { event ->
            JsonValues.render(event.toJson())
        }
    Files.writeString(path, content, StandardCharsets.UTF_8)
    println(ConsoleStyle.green("outputs written: $path"))
}

internal fun writeEventTraceFile(
    path: Path,
    events: List<PlayerEventTraceEvent>,
) {
    val content =
        events.joinToString(separator = System.lineSeparator()) { event ->
            JsonValues.render(event.toJson())
        }
    Files.writeString(path, content, StandardCharsets.UTF_8)
    println(ConsoleStyle.green("event trace written: $path"))
}

internal fun writeRunReportFile(
    path: Path,
    sandbox: DatapackSandbox,
    commandsExecuted: Int,
    assertionFailures: List<String>,
    traces: List<CommandTraceEvent>,
    beforeSnapshot: JsonObject,
    resourceSummary: ManifestResourceSummary,
) {
    val json =
        JsonObject().also { report ->
            report.addProperty("version", sandbox.profile.id)
            report.addProperty("passed", assertionFailures.isEmpty())
            report.addProperty("gameTime", sandbox.world.gameTime)
            report.addProperty("commands", commandsExecuted)
            report.addProperty("entities", sandbox.world.entities.size)
            report.add("assertionFailures", stringArray(assertionFailures))
            report.add(
                "outputs",
                JsonArray().also { array ->
                    sandbox.world.outputs.forEach { array.add(it.toJson()) }
                },
            )
            report.add(
                "traces",
                JsonArray().also { array ->
                    traces.forEach { array.add(it.toJson()) }
                },
            )
            val diagnostics = diagnosticsFromTraces(traces, sandbox.profile.id)
            report.addProperty("diagnosticCount", diagnostics.size())
            report.add("diagnostics", diagnostics)
            report.add(
                "eventTraces",
                JsonArray().also { array ->
                    sandbox.world.playerEventTraces.forEach { array.add(it.toJson()) }
                },
            )
            val snapshot = sandbox.snapshotJson()
            report.add("snapshot", snapshot)
            report.add("snapshotDiffs", SnapshotDiff.toJson(SnapshotDiff.stateDiff(beforeSnapshot, snapshot)))
            report.add("resources", resourceSummary.toReportJson())
        }
    Files.writeString(path, JsonValues.render(json), StandardCharsets.UTF_8)
    println(ConsoleStyle.green("report written: $path"))
}

internal fun writeManifestReportFile(
    path: Path,
    results: List<ManifestResult>,
) {
    val json = JsonArray()
    results.forEach { json.add(it.toReportJson()) }
    Files.writeString(path, JsonValues.render(json), StandardCharsets.UTF_8)
    println(ConsoleStyle.green("report written: $path"))
}

internal fun ManifestResult.toReportJson(): JsonObject =
    JsonObject().also { json ->
        json.addProperty("path", path.toString())
        json.addProperty("passed", passed)
        json.add("messages", stringArray(messages))
        json.addProperty("outputCount", outputs.size)
        json.addProperty("traceCount", traces.size)
        val diagnostics = diagnosticsFromTraces(traces, version = null)
        json.addProperty("diagnosticCount", diagnostics.size())
        json.addProperty("eventTraceCount", eventTraces.size)
        json.add(
            "outputs",
            JsonArray().also { outputsJson ->
                outputs.forEach { outputsJson.add(it.toJson()) }
            },
        )
        json.add(
            "traces",
            JsonArray().also { tracesJson ->
                traces.forEach { tracesJson.add(it.toJson()) }
            },
        )
        json.add("diagnostics", diagnostics)
        json.add(
            "eventTraces",
            JsonArray().also { eventTracesJson ->
                eventTraces.forEach { eventTracesJson.add(it.toJson()) }
            },
        )
        json.add(
            "attempts",
            JsonArray().also { attemptsJson ->
                attempts.forEach { attemptsJson.add(it.toReportJson()) }
            },
        )
    }

internal fun ManifestAttemptResult.toReportJson(): JsonObject =
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
        json.add(
            "outputs",
            JsonArray().also { outputsJson ->
                outputs.forEach { outputsJson.add(it.toJson()) }
            },
        )
        json.add(
            "traces",
            JsonArray().also { tracesJson ->
                traces.forEach { tracesJson.add(it.toJson()) }
            },
        )
        json.add("diagnostics", diagnostics)
        json.add(
            "eventTraces",
            JsonArray().also { eventTracesJson ->
                eventTraces.forEach { eventTracesJson.add(it.toJson()) }
            },
        )
        snapshot?.let { json.add("snapshot", it.deepCopy()) }
        json.add("snapshotDiffs", SnapshotDiff.toJson(snapshotDiffs))
        resourceSummary?.let { json.add("resources", it.toReportJson()) }
    }

internal fun diagnosticsFromTraces(
    traces: List<CommandTraceEvent>,
    version: String?,
): JsonArray =
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

internal fun ManifestResourceSummary.toReportJson(): JsonObject =
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
        json.add(
            "overlays",
            JsonArray().also { overlaysJson ->
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
            },
        )
        json.add(
            "missingReferences",
            JsonArray().also { missingJson ->
                missingReferences.forEach { reference ->
                    missingJson.add(
                        JsonObject().also { missing ->
                            missing.addProperty("source", reference.source)
                            missing.addProperty("type", reference.type)
                            missing.addProperty("id", reference.id.toString())
                        },
                    )
                }
            },
        )
    }

internal fun stringArray(values: List<String>): JsonArray = JsonArray().also { array -> values.forEach(array::add) }

internal fun sandboxLimits(
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
