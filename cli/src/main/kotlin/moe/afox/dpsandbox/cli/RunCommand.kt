package moe.afox.dpsandbox.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import moe.afox.dpsandbox.core.DatapackSandbox
import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.FunctionSource
import moe.afox.dpsandbox.core.JsonValues
import moe.afox.dpsandbox.core.PlayerEvent
import moe.afox.dpsandbox.core.Position
import moe.afox.dpsandbox.core.ResourceLocation
import moe.afox.dpsandbox.core.SandboxException
import moe.afox.dpsandbox.core.SingleFunctionDatapack
import moe.afox.dpsandbox.core.SnapshotDiff
import moe.afox.dpsandbox.core.SourceLocation
import moe.afox.dpsandbox.core.UnsupportedFeatureMode
import moe.afox.dpsandbox.core.VersionProfiles
import moe.afox.dpsandbox.core.createFunctionSandbox
import moe.afox.dpsandbox.core.createSandbox
import moe.afox.dpsandbox.core.toJson
import moe.afox.dpsandbox.render.RenderAssets
import moe.afox.dpsandbox.render.RenderCamera
import moe.afox.dpsandbox.render.RenderRequest
import moe.afox.dpsandbox.render.SandboxRenderer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

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
    private val screenshotFile by option("--screenshot-file").path()
    private val minecraftAssets by option("--minecraft-assets").path(mustExist = true)
    private val renderResourcePacks by option("--resource-pack").path(mustExist = true).multiple()
    private val playerSkins by option("--player-skin").multiple()
    private val cameraPlayer by option("--camera-player")
    private val cameraEntity by option("--camera-entity")
    private val cameraPosition by option("--camera-position")
    private val cameraYaw by option("--camera-yaw").double()
    private val cameraPitch by option("--camera-pitch").double()
    private val cameraDimension by option("--camera-dimension").default("minecraft:overworld")
    private val screenshotWidth by option("--screenshot-width").int().default(1280)
    private val screenshotHeight by option("--screenshot-height").int().default(720)
    private val screenshotFov by option("--screenshot-fov").double().default(70.0)
    private val renderDistance by option("--render-distance").double().default(128.0)
    private val transparentBackground by option("--transparent-background").flag(default = false)
    private val renderHud by option("--render-hud").flag(default = false)
    private val renderDebug by option("--render-debug").flag(default = false)
    private val requireRenderAssets by option("--require-render-assets").flag(default = false)
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
            val canUseEmptySandbox =
                commands.isNotEmpty() ||
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
                    screenshotFile != null ||
                    resources
            val sandbox =
                when {
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
                    canUseEmptySandbox ->
                        createFunctionSandbox(
                            version = version,
                            functionSources = listOf(FunctionSource.text(mcfunctionId, "", "<empty:$mcfunctionId>")),
                            unsupportedFeatureMode = effectiveUnsupportedMode,
                            limits = limits,
                        )
                    else -> throw SandboxException(
                        DiagnosticCode.INPUT_FORMAT,
                        "run requires at least one --pack path, --mcfunction file, --mcfunction-text string, --stdin, --command, --command-file, --event, --event-file, --world, --assert, or output/snapshot/trace/screenshot option",
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
                total +=
                    executeDirectCommand(
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
            val diff =
                if (snapshotDiff ||
                    snapshotDiffFile != null
                ) {
                    SnapshotDiff.stateDiff(beforeSnapshot, sandbox.snapshotJson())
                } else {
                    emptyList()
                }
            if (snapshotDiff) println(SnapshotDiff.render(diff))
            snapshotDiffFile?.let {
                Files.writeString(it, JsonValues.render(SnapshotDiff.toJson(diff)), StandardCharsets.UTF_8)
                println(ConsoleStyle.green("snapshot diff written: $it"))
            }
            traceFile?.let {
                val content =
                    traces.joinToString(separator = System.lineSeparator()) { event ->
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
            val assertionFailures =
                ManifestRunner.evaluateAssertions(parseAssertions(), sandbox, beforeSnapshot) +
                    if (failOnMissingResources || strict) {
                        ManifestRunner.missingResourceFailures(resourceSummary)
                    } else {
                        emptyList()
                    }
            reportFile?.let { writeRunReportFile(it, sandbox, total, assertionFailures, traces, beforeSnapshot, resourceSummary) }
            screenshotFile?.let { output ->
                if (requireRenderAssets && minecraftAssets == null && renderResourcePacks.isEmpty()) {
                    throw SandboxException(
                        DiagnosticCode.INPUT_FORMAT,
                        "--require-render-assets needs --minecraft-assets or at least one --resource-pack",
                    )
                }
                val request =
                    try {
                        val camera = renderCamera()
                        RenderRequest(
                            width = screenshotWidth,
                            height = screenshotHeight,
                            camera = camera,
                            fieldOfViewDegrees = screenshotFov,
                            renderDistance = renderDistance,
                            transparentBackground = transparentBackground,
                            showHud = renderHud,
                            showDebugOverlay = renderDebug,
                            strictAssets = strict || requireRenderAssets,
                        )
                    } catch (error: IllegalArgumentException) {
                        throw SandboxException(
                            DiagnosticCode.INPUT_FORMAT,
                            error.message ?: "Invalid render options",
                            cause = error,
                        )
                    }
                val frame =
                    SandboxRenderer(
                        RenderAssets(
                            minecraftAssets = minecraftAssets,
                            resourcePacks = renderResourcePacks,
                            playerSkins = renderPlayerSkins(),
                        ),
                    ).render(sandbox, request)
                frame.writePng(output)
                println(
                    ConsoleStyle.green(
                        "screenshot written: ${output.toAbsolutePath().normalize()} " +
                            "${frame.metadata.width}x${frame.metadata.height} " +
                            "diagnostics=${frame.metadata.diagnostics.size}",
                    ),
                )
            }
            if (assertionFailures.isNotEmpty()) {
                assertionFailures.forEach { println(ConsoleStyle.red(it)) }
                exitProcess(ExitCodes.ASSERTION_FAILED)
            }
            println(
                ConsoleStyle.green(
                    "OK version=${sandbox.profile.id} gameTime=${sandbox.world.gameTime} commands=$total entities=${sandbox.world.entities.size}",
                ),
            )
        } catch (error: SandboxException) {
            println(ConsoleStyle.diagnostic(error.render()))
            exitProcess(ExitCodes.forException(error))
        }
    }

    private fun renderCamera(): RenderCamera {
        val selected = listOfNotNull(cameraPlayer, cameraEntity, cameraPosition)
        if (selected.size > 1) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "Use only one of --camera-player, --camera-entity, or --camera-position",
            )
        }
        if (cameraPosition == null && (cameraYaw != null || cameraPitch != null)) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "--camera-yaw and --camera-pitch require --camera-position")
        }
        return when {
            cameraPlayer != null -> RenderCamera.Player(cameraPlayer!!)
            cameraEntity != null -> RenderCamera.Entity(cameraEntity!!)
            cameraPosition != null -> {
                val values = cameraPosition!!.trim().split(Regex("[,\\s]+"))
                if (values.size != 3 || values.any { it.toDoubleOrNull() == null }) {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "--camera-position must be x,y,z using finite numbers")
                }
                val coordinates = values.map(String::toDouble)
                if (coordinates.any { !it.isFinite() }) {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "--camera-position must contain finite numbers")
                }
                RenderCamera.Fixed(
                    Position(coordinates[0], coordinates[1], coordinates[2]),
                    cameraYaw ?: 0.0,
                    cameraPitch ?: 0.0,
                    cameraDimension,
                )
            }
            else -> RenderCamera.Auto
        }
    }

    private fun renderPlayerSkins(): Map<String, Path> =
        playerSkins.associate { value ->
            val split = value.indexOf('=')
            if (split <= 0 || split == value.lastIndex) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "--player-skin must use <player>=<png>")
            }
            val name = value.substring(0, split).trim()
            val path = Path.of(value.substring(split + 1)).toAbsolutePath().normalize()
            if (!Files.isRegularFile(path)) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Player skin does not exist: $path")
            }
            name to path
        }

    private fun parseFunctionSources(stdinFunctionText: String?): List<FunctionSource> {
        val total = mcfunctions.size + mcfunctionTexts.size + if (stdinFunctionText != null) 1 else 0
        val fileSources =
            mcfunctions.mapIndexed { index, raw ->
                val (explicitId, value) = parseFunctionSourceSpec(raw)
                FunctionSource.file(explicitId ?: implicitFunctionId(index, total, value), Path.of(value))
            }
        val textSources =
            mcfunctionTexts.mapIndexed { textIndex, raw ->
                val index = mcfunctions.size + textIndex
                val (explicitId, value) = parseFunctionSourceSpec(raw)
                FunctionSource.text(
                    explicitId ?: implicitFunctionId(index, total, "inline"),
                    value,
                    explicitId?.let { "<string:$it>" } ?: "<string:${implicitFunctionId(index, total, "inline")}>",
                )
            }
        val stdinSource =
            stdinFunctionText?.let {
                FunctionSource.text(
                    id = if (total <= 1) mcfunctionId else "sandbox:stdin",
                    content = it,
                    sourceName = "<stdin>",
                )
            }
        return fileSources + textSources + listOfNotNull(stdinSource)
    }

    private fun implicitFunctionId(
        index: Int,
        total: Int,
        value: String,
    ): String =
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
            val world =
                when {
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

    private fun applyPlayerEventLines(
        sandbox: DatapackSandbox,
        lines: List<String>,
        label: String,
    ) {
        lines.forEachIndexed { index, raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachIndexed
            applyPlayerEvent(sandbox, parsePlayerEventText(trimmed, "$label:${index + 1}"))
        }
    }

    private fun applyPlayerEvent(
        sandbox: DatapackSandbox,
        event: PlayerEvent,
    ) {
        sandbox.createPlayer(event.playerName)
        sandbox.handlePlayerEvent(event)
    }

    private fun parseInlineAssertion(
        raw: String,
        label: String,
    ): JsonObject {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) return parseJsonObject(raw, label)
        return when {
            trimmed.startsWith("score:") -> parseScoreAssertion(trimmed.removePrefix("score:"), label)
            trimmed.startsWith("storage:") -> parseStorageAssertion(trimmed.removePrefix("storage:"), label)
            trimmed.startsWith("advancement:") -> parseAdvancementAssertion(trimmed.removePrefix("advancement:"), label)
            trimmed.startsWith("predicate:") -> parsePredicateAssertion(trimmed.removePrefix("predicate:"), label)
            trimmed.startsWith("loot:") -> parseLootAssertion(trimmed.removePrefix("loot:"), label)
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
            trimmed.startsWith(
                "scoreboard-objective:",
            ) -> parseScoreboardObjectiveAssertion(trimmed.removePrefix("scoreboard-objective:"), label)
            trimmed.startsWith("scoreboard-display:") -> parseScoreboardDisplayAssertion(trimmed.removePrefix("scoreboard-display:"), label)
            trimmed.startsWith("forced-chunk:") -> parseForcedChunkAssertion(trimmed.removePrefix("forced-chunk:"), label)
            trimmed.startsWith("forceload:") -> parseForcedChunkAssertion(trimmed.removePrefix("forceload:"), label)
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
            trimmed.startsWith(
                "output-command:",
            ) -> parseOutputFieldAssertion(trimmed.removePrefix("output-command:"), "command", "output-command", label)
            trimmed.startsWith("output-channel:") -> parseOutputChannelAssertion(trimmed.removePrefix("output-channel:"), label)
            trimmed.startsWith(
                "output-target:",
            ) -> parseOutputFieldAssertion(trimmed.removePrefix("output-target:"), "target", "output-target", label)
            trimmed.startsWith("output-exact:") -> parseOutputExactAssertion(trimmed.removePrefix("output-exact:"), label)
            trimmed.startsWith("output-matches:") -> parseOutputMatchesAssertion(trimmed.removePrefix("output-matches:"), label)
            trimmed.startsWith(
                "output-normalized-exact:",
            ) -> parseNormalizedOutputExactAssertion(trimmed.removePrefix("output-normalized-exact:"), label)
            trimmed.startsWith(
                "output-normalized-matches:",
            ) -> parseNormalizedOutputMatchesAssertion(trimmed.removePrefix("output-normalized-matches:"), label)
            trimmed.startsWith("output-normalized:") -> parseNormalizedOutputAssertion(trimmed.removePrefix("output-normalized:"), label)
            trimmed.startsWith(
                "output-segment-exact:",
            ) -> parseOutputSegmentExactAssertion(trimmed.removePrefix("output-segment-exact:"), label)
            trimmed.startsWith(
                "output-segment-matches:",
            ) -> parseOutputSegmentMatchesAssertion(trimmed.removePrefix("output-segment-matches:"), label)
            trimmed.startsWith("output-segment:") -> parseOutputSegmentAssertion(trimmed.removePrefix("output-segment:"), label)
            trimmed.startsWith("output-payload:") -> parseOutputPayloadAssertion(trimmed.removePrefix("output-payload:"), label)
            trimmed.startsWith("output:") -> parseOutputAssertion(trimmed.removePrefix("output:"), label)
            else -> throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "$label must be a JSON object or shorthand score:<target>:<objective>=N, storage:<id>[:<path>]=<json>, advancement:<player>:<id>[=<true|false>][:done=<true|false>][:criterion=<name>][:criterionDone=<true|false>], predicate:<id>[=<true|false>][:player=<name>][:equals=<true|false>], loot:<table>[:context=<id>][:player=<name>][:seed=N][:count=N][:item=<id>], entity:<type|*>[@tag]=N, block:<x>,<y>,<z>=<id>, block:<x>,<y>,<z>?, block:<x>,<y>,<z>!, biome:<x>,<y>,<z>=<id>, team:<name>[?|!|=N|@member], bossbar:<id>[?|!|:<field>=<value>], item:<player>:<id>[@slot]=N, player:<name>[:<field>=<value>], world:<field>=<value>, gamerule:<rule>=<value>, gamerule:<rule>?, gamerule:<rule>!, random-sequence:<name>=N, scheduled:<id>=<dueTick>, scheduled:<id>?, scheduled:<id>!, scoreboard-objective:<name>:<field>=<value>, scoreboard-objective:<name>?, scoreboard-objective:<name>!, scoreboard-display:<slot>=<objective>, scoreboard-display:<slot>?, scoreboard-display:<slot>!, forced-chunk:<x>,<z>?, forced-chunk:<x>,<z>!, snapshot:<path>=<json>, snapshot:<path>?, snapshot:<path>!, diff:<json-pointer>[=<kind>], event-trace:<player>:<type>[=N], trace:<root>=N, trace:<text>, trace-output:<text>[@target], diagnostic=N, diagnostic:<code>[=N], diagnostic:<code>:<text>[=N], warning=N, warning:<text>, unsupported=N, unsupported:<text>, output:<text>, output-count:<text>=N, output-order:<N>:<text>, output-exact:<text>, output-matches:<regex>, output-command:<command>[=N|?|!], output-channel:<channel>[=N|?|!], output-target:<target>[=N|?|!], output-normalized:<text>, output-normalized-exact:<text>, output-normalized-matches:<regex>, output-segment:<text>[|color=<color>|bold=<true|false>][@target], output-segment-exact:<text>[...], output-segment-matches:<regex>[...], or output-payload:<command>:<path>[=<json>]",
            )
        }
    }

    private fun parseScoreAssertion(
        spec: String,
        label: String,
    ): JsonObject {
        val operator =
            listOf(">=", "<=", "=").firstOrNull { it in spec }
                ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label score shorthand requires =, >=, or <=")
        val splitAt = spec.indexOf(operator)
        val left = spec.substring(0, splitAt)
        val right = spec.substring(splitAt + operator.length).trim()
        val separator = left.lastIndexOf(':')
        if (separator <= 0 || separator == left.lastIndex) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label score shorthand must be score:<target>:<objective>${operator}N")
        }
        val expected =
            right.toIntOrNull()
                ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label score shorthand expected integer but got '$right'")
        val score =
            JsonObject().also { json ->
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

    private fun parseStorageAssertion(
        spec: String,
        label: String,
    ): JsonObject {
        val storage =
            when {
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
                    val expected =
                        try {
                            JsonParser.parseString(expectedText)
                        } catch (error: Exception) {
                            throw SandboxException(
                                DiagnosticCode.INPUT_FORMAT,
                                "$label storage shorthand expected JSON value but got '$expectedText'",
                                cause = error,
                            )
                        }
                    storageAssertionObject(spec.substring(0, splitAt), label).also { it.add("equals", expected) }
                }
            }
        return JsonObject().also { it.add("storage", storage) }
    }

    private fun parseAdvancementAssertion(
        spec: String,
        label: String,
    ): JsonObject {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "$label advancement shorthand must be advancement:<player>:<id>[=<true|false>][:done=<true|false>][:criterion=<name>][:criterionDone=<true|false>]",
            )
        }
        val optionPattern = Regex(":(done|criterion|criterionDone)=")
        val optionMatches = optionPattern.findAll(trimmed).toList()
        val head = if (optionMatches.isEmpty()) trimmed else trimmed.substring(0, optionMatches.first().range.first).trim()
        val splitAt = head.indexOf('=')
        val left = if (splitAt < 0) head else head.substring(0, splitAt)
        val separator = left.indexOf(':')
        if (separator <= 0 || separator == left.lastIndex) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "$label advancement shorthand must be advancement:<player>:<id>[=<true|false>][:done=<true|false>][:criterion=<name>][:criterionDone=<true|false>]",
            )
        }
        val player = left.substring(0, separator).trim()
        val id = left.substring(separator + 1).trim()
        if (player.isEmpty() || id.isEmpty()) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "$label advancement shorthand must be advancement:<player>:<id>[=<true|false>][:done=<true|false>][:criterion=<name>][:criterionDone=<true|false>]",
            )
        }
        var doneSpecified = splitAt >= 0
        var done =
            splitAt.takeIf { it >= 0 }?.let {
                parseBooleanShorthand(head.substring(it + 1).trim(), "$label advancement shorthand")
            }
        var criterion: String? = null
        var criterionDone: Boolean? = null
        optionMatches.forEachIndexed { index, match ->
            val key = match.groupValues[1]
            val valueStart = match.range.last + 1
            val valueEnd = optionMatches.getOrNull(index + 1)?.range?.first ?: trimmed.length
            val value = trimmed.substring(valueStart, valueEnd).trim()
            if (value.isEmpty()) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label advancement option '$key' must not be empty")
            }
            when (key) {
                "done" -> {
                    doneSpecified = true
                    done = parseBooleanShorthand(value, "$label advancement done")
                }
                "criterion" -> criterion = value
                "criterionDone" -> criterionDone = parseBooleanShorthand(value, "$label advancement criterionDone")
            }
        }
        if (criterion == null && criterionDone != null) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label advancement criterionDone requires criterion")
        }
        val advancement =
            JsonObject().also { json ->
                json.addProperty("player", player)
                json.addProperty("id", id)
                if (doneSpecified || criterion == null) {
                    json.addProperty("done", done ?: true)
                }
                criterion?.let {
                    json.addProperty("criterion", it)
                    json.addProperty("criterionDone", criterionDone ?: true)
                }
            }
        return JsonObject().also { it.add("advancement", advancement) }
    }

    private fun parsePredicateAssertion(
        spec: String,
        label: String,
    ): JsonObject {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "$label predicate shorthand must be predicate:<id>[=<true|false>][:player=<name>][:equals=<true|false>]",
            )
        }
        val optionPattern = Regex(":(player|equals)=")
        val optionMatches = optionPattern.findAll(trimmed).toList()
        val idAndExpected = if (optionMatches.isEmpty()) trimmed else trimmed.substring(0, optionMatches.first().range.first).trim()
        val splitAt = idAndExpected.indexOf('=')
        val id = if (splitAt < 0) idAndExpected else idAndExpected.substring(0, splitAt).trim()
        if (id.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label predicate shorthand must include a predicate id")
        }
        var expected =
            splitAt.takeIf { it >= 0 }?.let {
                parseBooleanShorthand(idAndExpected.substring(it + 1).trim(), "$label predicate shorthand")
            } ?: true
        var player: String? = null
        optionMatches.forEachIndexed { index, match ->
            val key = match.groupValues[1]
            val valueStart = match.range.last + 1
            val valueEnd = optionMatches.getOrNull(index + 1)?.range?.first ?: trimmed.length
            val value = trimmed.substring(valueStart, valueEnd).trim()
            if (value.isEmpty()) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label predicate option '$key' must not be empty")
            }
            when (key) {
                "player" -> player = value
                "equals" -> expected = parseBooleanShorthand(value, "$label predicate equals")
            }
        }
        val predicate =
            JsonObject().also { json ->
                json.addProperty("id", id)
                json.addProperty("equals", expected)
                player?.let { json.addProperty("player", it) }
            }
        return JsonObject().also { it.add("predicate", predicate) }
    }

    private fun parseBooleanShorthand(
        value: String,
        label: String,
    ): Boolean =
        when (value.lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label expected true or false but got '$value'")
        }

    private fun parseLootAssertion(
        spec: String,
        label: String,
    ): JsonObject {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "$label loot shorthand must be loot:<table>[:context=<id>][:player=<name>][:seed=N][:count=N][:item=<id>]",
            )
        }
        val optionPattern = Regex(":(context|player|seed|count|item)=")
        val optionMatches = optionPattern.findAll(trimmed).toList()
        val table = if (optionMatches.isEmpty()) trimmed else trimmed.substring(0, optionMatches.first().range.first).trim()
        if (table.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label loot shorthand must include a loot table id")
        }
        val loot = JsonObject().also { it.addProperty("table", table) }
        optionMatches.forEachIndexed { index, match ->
            val key = match.groupValues[1]
            val valueStart = match.range.last + 1
            val valueEnd = optionMatches.getOrNull(index + 1)?.range?.first ?: trimmed.length
            val value = trimmed.substring(valueStart, valueEnd).trim()
            if (value.isEmpty()) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label loot option '$key' must not be empty")
            }
            when (key) {
                "context", "player", "item" -> loot.addProperty(key, value)
                "seed" ->
                    loot.addProperty(
                        key,
                        value.toLongOrNull()
                            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label loot seed must be an integer"),
                    )
                "count" ->
                    loot.addProperty(
                        key,
                        value.toIntOrNull()
                            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label loot count must be an integer"),
                    )
                else -> throw SandboxException(
                    DiagnosticCode.INPUT_FORMAT,
                    "$label loot option '$key' is not supported",
                )
            }
        }
        return JsonObject().also { it.add("loot", loot) }
    }

    private fun parseEntityCountAssertion(
        spec: String,
        label: String,
    ): JsonObject {
        val operator =
            listOf(">=", "<=", "=").firstOrNull { it in spec }
                ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label entity shorthand requires =, >=, or <=")
        val splitAt = spec.indexOf(operator)
        val selector = spec.substring(0, splitAt).trim()
        val right = spec.substring(splitAt + operator.length).trim()
        if (selector.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label entity shorthand must be entity:<type|*>[@tag]${operator}N")
        }
        val expected =
            right.toIntOrNull()
                ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label entity shorthand expected integer but got '$right'")
        val tagSplit = selector.indexOf('@')
        val type = if (tagSplit < 0) selector else selector.substring(0, tagSplit).trim()
        val tag = tagSplit.takeIf { it >= 0 }?.let { selector.substring(it + 1).trim() }
        if (type.isEmpty() || tag?.isEmpty() == true) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label entity shorthand must be entity:<type|*>[@tag]${operator}N")
        }
        val entity =
            JsonObject().also { json ->
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

    private fun parseBlockAssertion(
        spec: String,
        label: String,
    ): JsonObject {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "$label block shorthand must be block:<x>,<y>,<z>=<id>, block:<x>,<y>,<z>?, or block:<x>,<y>,<z>!",
            )
        }
        val block =
            when {
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

    private fun blockAssertionObject(
        rawPos: String,
        label: String,
    ): JsonObject {
        val parts = rawPos.split(",")
        if (parts.size != 3 || parts.any { it.trim().toIntOrNull() == null }) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label block coordinates must be x,y,z")
        }
        val pos = JsonArray().also { array -> parts.map { it.trim().toInt() }.forEach { array.add(it) } }
        return JsonObject().also { it.add("pos", pos) }
    }

    private fun parseBiomeAssertion(
        spec: String,
        label: String,
    ): JsonObject {
        val trimmed = spec.trim()
        val splitAt = trimmed.indexOf('=')
        if (splitAt <= 0 || splitAt == trimmed.lastIndex) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label biome shorthand must be biome:<x>,<y>,<z>=<id>")
        }
        val id = trimmed.substring(splitAt + 1).trim()
        if (id.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label biome shorthand id must not be empty")
        }
        val biome =
            blockAssertionObject(
                trimmed.substring(0, splitAt),
                label,
            ).also { it.addProperty("id", ResourceLocation.parse(id).toString()) }
        val world = JsonObject().also { it.add("biome", biome) }
        return JsonObject().also { it.add("world", world) }
    }

    private fun parseTeamAssertion(
        spec: String,
        label: String,
    ): JsonObject {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label team shorthand must be team:<name>[?|!|=N|@member]")
        }
        val team =
            when {
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
                    val count =
                        trimmed.substring(splitAt + 1).trim().toIntOrNull()
                            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label team member count must be an integer")
                    teamAssertionObject(trimmed.substring(0, splitAt), label).also { it.addProperty("memberCount", count) }
                }
                else -> teamAssertionObject(trimmed, label).also { it.addProperty("exists", true) }
            }
        return JsonObject().also { it.add("team", team) }
    }

    private fun teamAssertionObject(
        name: String,
        label: String,
    ): JsonObject {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label team shorthand name must not be empty")
        }
        return JsonObject().also { it.addProperty("name", trimmed) }
    }

    private fun parseBossbarAssertion(
        spec: String,
        label: String,
    ): JsonObject {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label bossbar shorthand must be bossbar:<id>[?|!|:<field>=<value>]")
        }
        val bossbar =
            when {
                trimmed.endsWith("?") -> bossbarAssertionObject(trimmed.dropLast(1), label).also { it.addProperty("exists", true) }
                trimmed.endsWith("!") -> bossbarAssertionObject(trimmed.dropLast(1), label).also { it.addProperty("exists", false) }
                "=" in trimmed -> parseBossbarFieldAssertion(trimmed, label)
                else -> bossbarAssertionObject(trimmed, label).also { it.addProperty("exists", true) }
            }
        return JsonObject().also { it.add("bossbar", bossbar) }
    }

    private fun parseBossbarFieldAssertion(
        spec: String,
        label: String,
    ): JsonObject {
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
                "value", "max" ->
                    bossbar.addProperty(
                        field,
                        value.toIntOrNull()
                            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label bossbar $field must be an integer"),
                    )
                "visible" -> bossbar.addProperty("visible", parseBossbarBoolean(value, field, label))
                "name", "color", "style", "player" -> bossbar.addProperty(field, value)
                else -> throw SandboxException(
                    DiagnosticCode.INPUT_FORMAT,
                    "$label unsupported bossbar field '$field'; use name, value, max, color, style, visible, or player",
                )
            }
        }
    }

    private fun bossbarAssertionObject(
        id: String,
        label: String,
    ): JsonObject {
        val trimmed = id.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label bossbar id must not be empty")
        }
        return JsonObject().also { it.addProperty("id", ResourceLocation.parse(trimmed).toString()) }
    }

    private fun parseBossbarBoolean(
        value: String,
        field: String,
        label: String,
    ): Boolean =
        when (value.lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label bossbar $field expected true or false but got '$value'")
        }

    private fun parseItemAssertion(
        spec: String,
        label: String,
    ): JsonObject {
        val operator =
            listOf(">=", "<=", "=").firstOrNull { it in spec }
                ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label item shorthand requires =, >=, or <=")
        val splitAt = spec.indexOf(operator)
        val left = spec.substring(0, splitAt).trim()
        val right = spec.substring(splitAt + operator.length).trim()
        val separator = left.indexOf(':')
        if (separator <= 0 || separator == left.lastIndex) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label item shorthand must be item:<player>:<id>[@slot]${operator}N")
        }
        val expected =
            right.toIntOrNull()
                ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label item shorthand expected integer but got '$right'")
        val player = left.substring(0, separator).trim()
        val itemSpec = left.substring(separator + 1).trim()
        val slotSplit = itemSpec.indexOf('@')
        val itemId = if (slotSplit < 0) itemSpec else itemSpec.substring(0, slotSplit).trim()
        val slot =
            slotSplit.takeIf { it >= 0 }?.let {
                itemSpec.substring(it + 1).trim().toIntOrNull()
                    ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label item shorthand slot must be an integer")
            }
        if (player.isEmpty() || itemId.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label item shorthand must be item:<player>:<id>[@slot]${operator}N")
        }
        val item =
            JsonObject().also { json ->
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

    private fun parsePlayerAssertion(
        spec: String,
        label: String,
    ): JsonObject {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "$label player shorthand must be player:<name>, player:<name>?, player:<name>!, or player:<name>:<field>=<value>",
            )
        }
        val player =
            when {
                trimmed.endsWith("?") -> playerAssertionObject(trimmed.dropLast(1), label).also { it.addProperty("exists", true) }
                trimmed.endsWith("!") -> playerAssertionObject(trimmed.dropLast(1), label).also { it.addProperty("exists", false) }
                ":" !in trimmed -> playerAssertionObject(trimmed, label).also { it.addProperty("exists", true) }
                else -> parsePlayerFieldAssertion(trimmed, label)
            }
        return JsonObject().also { it.add("player", player) }
    }

    private fun parsePlayerFieldAssertion(
        spec: String,
        label: String,
    ): JsonObject {
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

    private fun playerAssertionObject(
        name: String,
        label: String,
    ): JsonObject {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label player shorthand name must not be empty")
        }
        return JsonObject().also { it.addProperty("name", trimmed) }
    }

    private fun parsePlayerInt(
        value: String,
        field: String,
        label: String,
    ): Int =
        value.toIntOrNull()
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label player field '$field' expected integer but got '$value'")

    private fun parsePlayerDouble(
        value: String,
        field: String,
        label: String,
    ): Double =
        value.toDoubleOrNull()
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label player field '$field' expected number but got '$value'")

    private fun parseWorldAssertion(
        spec: String,
        label: String,
    ): JsonObject {
        val splitAt = spec.indexOf('=')
        if (splitAt <= 0 || splitAt == spec.lastIndex) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label world shorthand must be world:<field>=<value>")
        }
        val field = spec.substring(0, splitAt).trim()
        val value = spec.substring(splitAt + 1).trim()
        if (field.isEmpty() || value.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label world shorthand must be world:<field>=<value>")
        }
        val world =
            JsonObject().also { json ->
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

    private fun parseWorldLong(
        value: String,
        field: String,
        label: String,
    ): Long =
        value.toLongOrNull()
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label world field '$field' expected integer but got '$value'")

    private fun parseWorldInt(
        value: String,
        field: String,
        label: String,
    ): Int =
        value.toIntOrNull()
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label world field '$field' expected integer but got '$value'")

    private fun parseGameruleAssertion(
        spec: String,
        label: String,
    ): JsonObject {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "$label gamerule shorthand must be gamerule:<rule>=<value>, gamerule:<rule>?, or gamerule:<rule>!",
            )
        }
        val snapshot =
            when {
                trimmed.endsWith("?") -> gameruleSnapshotAssertion(trimmed.dropLast(1), label).also { it.addProperty("exists", true) }
                trimmed.endsWith("!") -> gameruleSnapshotAssertion(trimmed.dropLast(1), label).also { it.addProperty("missing", true) }
                else -> {
                    val splitAt = trimmed.indexOf('=')
                    if (splitAt <= 0 || splitAt == trimmed.lastIndex) {
                        throw SandboxException(
                            DiagnosticCode.INPUT_FORMAT,
                            "$label gamerule shorthand must be gamerule:<rule>=<value>, gamerule:<rule>?, or gamerule:<rule>!",
                        )
                    }
                    val value = trimmed.substring(splitAt + 1).trim()
                    gameruleSnapshotAssertion(trimmed.substring(0, splitAt), label).also { it.add("equals", JsonPrimitive(value)) }
                }
            }
        return JsonObject().also { it.add("snapshot", snapshot) }
    }

    private fun gameruleSnapshotAssertion(
        rule: String,
        label: String,
    ): JsonObject {
        val trimmed = rule.trim()
        if (trimmed.isEmpty() || '.' in trimmed || '[' in trimmed || ']' in trimmed) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label gamerule name must be a non-empty simple path segment")
        }
        return JsonObject().also { it.addProperty("path", "gamerules.$trimmed") }
    }

    private fun parseRandomSequenceAssertion(
        spec: String,
        label: String,
    ): JsonObject {
        val splitAt = spec.indexOf('=')
        if (splitAt <= 0 || splitAt == spec.lastIndex) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label random sequence shorthand must be random-sequence:<name>=N")
        }
        val name = spec.substring(0, splitAt).trim()
        val valueText = spec.substring(splitAt + 1).trim()
        if (name.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label random sequence shorthand name must not be empty")
        }
        val expected =
            valueText.toLongOrNull()
                ?: throw SandboxException(
                    DiagnosticCode.INPUT_FORMAT,
                    "$label random sequence shorthand expected integer but got '$valueText'",
                )
        val randomSequences = JsonObject().also { it.addProperty(name, expected) }
        val world = JsonObject().also { it.add("randomSequences", randomSequences) }
        return JsonObject().also { it.add("world", world) }
    }

    private fun parseScheduledAssertion(
        spec: String,
        label: String,
    ): JsonObject {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "$label scheduled shorthand must be scheduled:<id>=<dueTick>, scheduled:<id>?, or scheduled:<id>!",
            )
        }
        val snapshot =
            when {
                trimmed.endsWith("?") ->
                    scheduledSnapshotAssertion(trimmed.dropLast(1), label).also {
                        it.addProperty("exists", true)
                    }
                trimmed.endsWith("!") ->
                    scheduledSnapshotAssertion(trimmed.dropLast(1), label).also {
                        it.addProperty("missing", true)
                    }
                else -> {
                    val splitAt = trimmed.indexOf('=')
                    if (splitAt <= 0 || splitAt == trimmed.lastIndex) {
                        throw SandboxException(
                            DiagnosticCode.INPUT_FORMAT,
                            "$label scheduled shorthand must be scheduled:<id>=<dueTick>, scheduled:<id>?, or scheduled:<id>!",
                        )
                    }
                    val expected =
                        trimmed.substring(splitAt + 1).trim().toLongOrNull()
                            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label scheduled dueTick must be an integer")
                    scheduledSnapshotAssertion(trimmed.substring(0, splitAt), label, ".dueTick").also {
                        it.add("equals", JsonPrimitive(expected))
                    }
                }
            }
        return JsonObject().also { it.add("snapshot", snapshot) }
    }

    private fun scheduledSnapshotAssertion(
        rawId: String,
        label: String,
        suffix: String = "",
    ): JsonObject {
        val idText = rawId.trim()
        if (idText.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label scheduled function id must not be empty")
        }
        val id = ResourceLocation.parse(idText)
        return JsonObject().also { it.addProperty("path", """scheduled[{function:"$id"}]$suffix""") }
    }

    private fun parseScoreboardObjectiveAssertion(
        spec: String,
        label: String,
    ): JsonObject {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "$label scoreboard objective shorthand must be scoreboard-objective:<name>:<field>=<value>, scoreboard-objective:<name>?, or scoreboard-objective:<name>!",
            )
        }
        val snapshot =
            when {
                trimmed.endsWith(
                    "?",
                ) -> scoreboardObjectiveSnapshotAssertion(trimmed.dropLast(1), label).also { it.addProperty("exists", true) }
                trimmed.endsWith(
                    "!",
                ) -> scoreboardObjectiveSnapshotAssertion(trimmed.dropLast(1), label).also { it.addProperty("missing", true) }
                else -> {
                    val splitAt = trimmed.indexOf('=')
                    if (splitAt <= 0 || splitAt == trimmed.lastIndex) {
                        throw SandboxException(
                            DiagnosticCode.INPUT_FORMAT,
                            "$label scoreboard objective shorthand must be scoreboard-objective:<name>:<field>=<value>, scoreboard-objective:<name>?, or scoreboard-objective:<name>!",
                        )
                    }
                    val left = trimmed.substring(0, splitAt).trim()
                    val fieldSplit = left.lastIndexOf(':')
                    if (fieldSplit <= 0 || fieldSplit == left.lastIndex) {
                        throw SandboxException(
                            DiagnosticCode.INPUT_FORMAT,
                            "$label scoreboard objective field shorthand must be scoreboard-objective:<name>:<field>=<value>",
                        )
                    }
                    val field = canonicalScoreboardObjectiveField(left.substring(fieldSplit + 1).trim(), label)
                    val valueText = trimmed.substring(splitAt + 1).trim()
                    if (valueText.isEmpty()) {
                        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label scoreboard objective value must not be empty")
                    }
                    scoreboardObjectiveSnapshotAssertion(left.substring(0, fieldSplit), label, ".$field").also {
                        it.add("equals", scoreboardObjectiveFieldValue(field, valueText, label))
                    }
                }
            }
        return JsonObject().also { it.add("snapshot", snapshot) }
    }

    private fun canonicalScoreboardObjectiveField(
        raw: String,
        label: String,
    ): String =
        when (raw) {
            "name", "criteria", "displayName", "displayname", "renderType", "rendertype", "displayAutoUpdate", "displayautoupdate" ->
                when (raw.lowercase()) {
                    "displayname" -> "displayName"
                    "rendertype" -> "renderType"
                    "displayautoupdate" -> "displayAutoUpdate"
                    else -> raw
                }
            else -> throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "$label scoreboard objective field must be name, criteria, displayName, renderType, or displayAutoUpdate",
            )
        }

    private fun scoreboardObjectiveFieldValue(
        field: String,
        valueText: String,
        label: String,
    ): JsonPrimitive =
        when (field) {
            "displayAutoUpdate" ->
                JsonPrimitive(
                    when (valueText.lowercase()) {
                        "true" -> true
                        "false" -> false
                        else -> throw SandboxException(
                            DiagnosticCode.INPUT_FORMAT,
                            "$label scoreboard objective displayAutoUpdate must be true or false",
                        )
                    },
                )
            else -> JsonPrimitive(valueText)
        }

    private fun scoreboardObjectiveSnapshotAssertion(
        rawName: String,
        label: String,
        suffix: String = "",
    ): JsonObject {
        val name = rawName.trim()
        if (name.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label scoreboard objective name must not be empty")
        }
        return JsonObject().also { it.addProperty("path", """objectiveDetails[{name:"${escapePathString(name)}"}]$suffix""") }
    }

    private fun parseScoreboardDisplayAssertion(
        spec: String,
        label: String,
    ): JsonObject {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "$label scoreboard display shorthand must be scoreboard-display:<slot>=<objective>, scoreboard-display:<slot>?, or scoreboard-display:<slot>!",
            )
        }
        val snapshot =
            when {
                trimmed.endsWith(
                    "?",
                ) -> scoreboardDisplaySnapshotAssertion(trimmed.dropLast(1), label).also { it.addProperty("exists", true) }
                trimmed.endsWith(
                    "!",
                ) -> scoreboardDisplaySnapshotAssertion(trimmed.dropLast(1), label).also { it.addProperty("missing", true) }
                else -> {
                    val splitAt = trimmed.indexOf('=')
                    if (splitAt <= 0 || splitAt == trimmed.lastIndex) {
                        throw SandboxException(
                            DiagnosticCode.INPUT_FORMAT,
                            "$label scoreboard display shorthand must be scoreboard-display:<slot>=<objective>, scoreboard-display:<slot>?, or scoreboard-display:<slot>!",
                        )
                    }
                    val objective = trimmed.substring(splitAt + 1).trim()
                    if (objective.isEmpty()) {
                        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label scoreboard display objective must not be empty")
                    }
                    scoreboardDisplaySnapshotAssertion(trimmed.substring(0, splitAt), label, ".objective").also {
                        it.add("equals", JsonPrimitive(objective))
                    }
                }
            }
        return JsonObject().also { it.add("snapshot", snapshot) }
    }

    private fun scoreboardDisplaySnapshotAssertion(
        rawSlot: String,
        label: String,
        suffix: String = "",
    ): JsonObject {
        val slot = rawSlot.trim()
        if (slot.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label scoreboard display slot must not be empty")
        }
        return JsonObject().also { it.addProperty("path", """scoreboardDisplays[{slot:"${escapePathString(slot)}"}]$suffix""") }
    }

    private fun escapePathString(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun parseForcedChunkAssertion(
        spec: String,
        label: String,
    ): JsonObject {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "$label forced chunk shorthand must be forced-chunk:<x>,<z>?, forced-chunk:<x>,<z>!, or forced-chunk:<x>,<z>",
            )
        }
        val snapshot =
            when {
                trimmed.endsWith("?") -> forcedChunkSnapshotAssertion(trimmed.dropLast(1), label).also { it.addProperty("exists", true) }
                trimmed.endsWith("!") -> forcedChunkSnapshotAssertion(trimmed.dropLast(1), label).also { it.addProperty("missing", true) }
                else -> forcedChunkSnapshotAssertion(trimmed, label).also { it.addProperty("exists", true) }
            }
        return JsonObject().also { it.add("snapshot", snapshot) }
    }

    private fun forcedChunkSnapshotAssertion(
        rawChunk: String,
        label: String,
    ): JsonObject {
        val parts = rawChunk.split(",")
        if (parts.size != 2 || parts.any { it.trim().toIntOrNull() == null }) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label forced chunk coordinates must be x,z")
        }
        val x = parts[0].trim().toInt()
        val z = parts[1].trim().toInt()
        return JsonObject().also { it.addProperty("path", """forcedChunks[{x:$x,z:$z}]""") }
    }

    private fun parseTraceAssertion(
        spec: String,
        label: String,
    ): JsonObject {
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
            val expected =
                countText.toIntOrNull()
                    ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label trace shorthand expected integer but got '$countText'")
            trace.addProperty("root", root)
            trace.addProperty("count", expected)
        } else {
            trace.addProperty("contains", trimmed)
        }
        return JsonObject().also { it.add("trace", trace) }
    }

    private fun parseEventTraceAssertion(
        spec: String,
        label: String,
    ): JsonObject {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "$label event trace shorthand must be event-trace:<player>:<type>[@x,y,z][=N]",
            )
        }
        val splitAt = trimmed.indexOf('=')
        val left = if (splitAt < 0) trimmed else trimmed.substring(0, splitAt)
        val countText = splitAt.takeIf { it >= 0 }?.let { trimmed.substring(it + 1).trim() }
        val separator = left.indexOf(':')
        if (separator <= 0 || separator == left.lastIndex) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "$label event trace shorthand must be event-trace:<player>:<type>[@x,y,z][=N]",
            )
        }
        val player = left.substring(0, separator).trim()
        val rawType = left.substring(separator + 1).trim()
        val coordSeparator = rawType.lastIndexOf('@')
        val type = if (coordSeparator >= 0) rawType.substring(0, coordSeparator).trim() else rawType
        val blockPos =
            coordSeparator.takeIf { it >= 0 }?.let {
                parseEventTraceBlockCoordinates(rawType.substring(it + 1), label)
            }
        if (player.isEmpty() || type.isEmpty()) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "$label event trace shorthand must be event-trace:<player>:<type>[@x,y,z][=N]",
            )
        }
        val eventTrace =
            JsonObject().also { json ->
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
                            ?: throw SandboxException(
                                DiagnosticCode.INPUT_FORMAT,
                                "$label event trace shorthand expected integer but got '$it'",
                            ),
                    )
                }
            }
        return JsonObject().also { it.add("eventTrace", eventTrace) }
    }

    private fun parseEventTraceBlockCoordinates(
        raw: String,
        label: String,
    ): List<Int> {
        val parts = raw.split(",")
        if (parts.size != 3 || parts.any { it.trim().toIntOrNull() == null }) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label event trace block coordinates must be x,y,z")
        }
        return parts.map { it.trim().toInt() }
    }

    private fun parseSnapshotAssertion(
        spec: String,
        label: String,
    ): JsonObject {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "$label snapshot shorthand must be snapshot:<path>=<json>, snapshot:<path>?, or snapshot:<path>!",
            )
        }
        val snapshot =
            when {
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
                    val expected =
                        try {
                            JsonParser.parseString(expectedText)
                        } catch (error: Exception) {
                            throw SandboxException(
                                DiagnosticCode.INPUT_FORMAT,
                                "$label snapshot shorthand expected JSON value but got '$expectedText'",
                                cause = error,
                            )
                        }
                    snapshotAssertionObject(trimmed.substring(0, splitAt), label).also { it.add("equals", expected) }
                }
            }
        return JsonObject().also { it.add("snapshot", snapshot) }
    }

    private fun snapshotAssertionObject(
        path: String,
        label: String,
    ): JsonObject {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label snapshot shorthand path must not be empty")
        }
        return JsonObject().also { it.addProperty("path", trimmed) }
    }

    private fun parseSnapshotDiffAssertion(
        spec: String,
        label: String,
    ): JsonObject {
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
        val diff =
            JsonObject().also { json ->
                json.addProperty("path", path)
                kind?.let { json.addProperty("kind", it) }
            }
        return JsonObject().also { it.add("snapshotDiff", diff) }
    }

    private fun parseTraceOutputAssertion(
        spec: String,
        label: String,
    ): JsonObject {
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
        val trace =
            JsonObject().also { json ->
                json.addProperty("outputContains", outputContains)
                outputTarget?.let { json.addProperty("outputTarget", it) }
            }
        return JsonObject().also { it.add("trace", trace) }
    }

    private fun parseDiagnosticCountAssertion(
        count: String,
        label: String,
    ): JsonObject {
        val expected =
            count.trim().toIntOrNull()
                ?: throw SandboxException(
                    DiagnosticCode.INPUT_FORMAT,
                    "$label diagnostic shorthand expected integer but got '${count.trim()}'",
                )
        val diagnostic = JsonObject().also { it.addProperty("count", expected) }
        return JsonObject().also { it.add("diagnostic", diagnostic) }
    }

    private fun parseDiagnosticAssertion(
        spec: String,
        label: String,
    ): JsonObject {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "$label diagnostic shorthand must be diagnostic:<code>[=N], diagnostic:<code>:<text>[=N], or diagnostic:<text>",
            )
        }
        val splitAt = trimmed.lastIndexOf('=')
        val left = if (splitAt < 0) trimmed else trimmed.substring(0, splitAt).trim()
        val count =
            splitAt.takeIf { it >= 0 }?.let {
                val countText = trimmed.substring(it + 1).trim()
                countText.toIntOrNull()
                    ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label diagnostic count must be an integer")
            }
        if (left.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label diagnostic shorthand must include a code or text")
        }
        val colon = left.indexOf(':')
        val code =
            when {
                colon > 0 -> diagnosticCodeName(left.substring(0, colon))
                else -> diagnosticCodeName(left)
            }
        val contains =
            when {
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
        val diagnostic =
            JsonObject().also { json ->
                code?.let { json.addProperty("code", it) }
                contains?.let { json.addProperty("contains", it) }
                count?.let { json.addProperty("count", it) }
            }
        return JsonObject().also { it.add("diagnostic", diagnostic) }
    }

    private fun diagnosticCodeName(raw: String): String? = runCatching { DiagnosticCode.valueOf(raw.trim().uppercase()).name }.getOrNull()

    private fun storageAssertionObject(
        location: String,
        label: String,
    ): JsonObject {
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

    private fun parseOutputAssertion(
        text: String,
        label: String,
    ): JsonObject = parseOutputTextAssertion(text, "contains", "output", label)

    private fun parseOutputExactAssertion(
        text: String,
        label: String,
    ): JsonObject = parseOutputTextAssertion(text, "text", "output-exact", label)

    private fun parseOutputMatchesAssertion(
        text: String,
        label: String,
    ): JsonObject = parseOutputTextAssertion(text, "matches", "output-matches", label)

    private fun parseOutputCountAssertion(
        spec: String,
        label: String,
    ): JsonObject {
        val splitAt = spec.lastIndexOf('=')
        if (splitAt <= 0 || splitAt == spec.lastIndex) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label output count shorthand must be output-count:<text>=N")
        }
        val contains = spec.substring(0, splitAt).trim()
        val countText = spec.substring(splitAt + 1).trim()
        val count =
            countText.toIntOrNull()
                ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label output count must be an integer")
        if (contains.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label output count text must not be empty")
        }
        val output =
            JsonObject().also { json ->
                json.addProperty("contains", contains)
                json.addProperty("count", count)
            }
        return JsonObject().also { it.add("output", output) }
    }

    private fun parseOutputOrderAssertion(
        spec: String,
        label: String,
    ): JsonObject {
        val separator = spec.indexOf(':')
        if (separator <= 0 || separator == spec.lastIndex) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label output order shorthand must be output-order:<N>:<text>")
        }
        val orderText = spec.substring(0, separator).trim()
        val order =
            orderText.toIntOrNull()
                ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label output order must be an integer")
        if (order <= 0) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label output order must be one or greater")
        }
        val contains = spec.substring(separator + 1).trim()
        if (contains.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label output order text must not be empty")
        }
        val output =
            JsonObject().also { json ->
                json.addProperty("contains", contains)
                json.addProperty("order", order)
            }
        return JsonObject().also { it.add("output", output) }
    }

    private fun parseNormalizedOutputAssertion(
        text: String,
        label: String,
    ): JsonObject = parseOutputTextAssertion(text, "normalizedContains", "output-normalized", label)

    private fun parseNormalizedOutputExactAssertion(
        text: String,
        label: String,
    ): JsonObject = parseOutputTextAssertion(text, "normalizedText", "output-normalized-exact", label)

    private fun parseNormalizedOutputMatchesAssertion(
        text: String,
        label: String,
    ): JsonObject = parseOutputTextAssertion(text, "normalizedMatches", "output-normalized-matches", label)

    private fun parseOutputTextAssertion(
        text: String,
        fieldName: String,
        shorthandName: String,
        label: String,
    ): JsonObject {
        val value = text.trim()
        if (value.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label $shorthandName shorthand must be $shorthandName:<text>")
        }
        val output = JsonObject().also { it.addProperty(fieldName, value) }
        return JsonObject().also { it.add("output", output) }
    }

    private fun parseOutputChannelAssertion(
        spec: String,
        label: String,
    ): JsonObject = parseOutputFieldAssertion(spec, "channel", "output-channel", label)

    private fun parseOutputFieldAssertion(
        spec: String,
        fieldName: String,
        shorthandName: String,
        label: String,
    ): JsonObject {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label $shorthandName shorthand must be $shorthandName:<value>[=N|?|!]")
        }
        val output =
            when {
                trimmed.endsWith("?") -> outputFieldObject(trimmed.dropLast(1), fieldName, shorthandName, label)
                trimmed.endsWith(
                    "!",
                ) -> outputFieldObject(trimmed.dropLast(1), fieldName, shorthandName, label).also { it.addProperty("count", 0) }
                else -> {
                    val splitAt = trimmed.indexOf('=')
                    if (splitAt <= 0 || splitAt == trimmed.lastIndex) {
                        throw SandboxException(
                            DiagnosticCode.INPUT_FORMAT,
                            "$label $shorthandName shorthand must be $shorthandName:<value>[=N|?|!]",
                        )
                    }
                    val countText = trimmed.substring(splitAt + 1).trim()
                    val count =
                        countText.toIntOrNull()
                            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label $shorthandName count must be an integer")
                    outputFieldObject(
                        trimmed.substring(0, splitAt),
                        fieldName,
                        shorthandName,
                        label,
                    ).also { it.addProperty("count", count) }
                }
            }
        return JsonObject().also { it.add("output", output) }
    }

    private fun outputFieldObject(
        value: String,
        fieldName: String,
        shorthandName: String,
        label: String,
    ): JsonObject {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label $shorthandName value must not be empty")
        }
        return JsonObject().also { it.addProperty(fieldName, trimmed) }
    }

    private fun parseOutputSegmentAssertion(
        spec: String,
        label: String,
    ): JsonObject = parseOutputSegmentAssertion(spec, "contains", "output-segment", label)

    private fun parseOutputSegmentExactAssertion(
        spec: String,
        label: String,
    ): JsonObject = parseOutputSegmentAssertion(spec, "text", "output-segment-exact", label)

    private fun parseOutputSegmentMatchesAssertion(
        spec: String,
        label: String,
    ): JsonObject = parseOutputSegmentAssertion(spec, "matches", "output-segment-matches", label)

    private fun parseOutputSegmentAssertion(
        spec: String,
        matchFieldName: String,
        shorthandName: String,
        label: String,
    ): JsonObject {
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
        val output =
            JsonObject().also { json ->
                target?.let { json.addProperty("target", it) }
                json.add("segment", segment)
            }
        return JsonObject().also { it.add("output", output) }
    }

    private fun parseOutputSegmentBoolean(
        value: String,
        name: String,
        label: String,
    ): Boolean =
        when (value.lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "$label output segment option '$name' expected true or false but got '$value'",
            )
        }

    private fun parseOutputPayloadAssertion(
        spec: String,
        label: String,
    ): JsonObject {
        val trimmed = spec.trim()
        val splitAt = trimmed.indexOf('=')
        if (splitAt == trimmed.lastIndex) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "$label output payload shorthand must be output-payload:<command>:<path>[=<json>]",
            )
        }
        val left = if (splitAt < 0) trimmed else trimmed.substring(0, splitAt)
        val separator = left.indexOf(':')
        if (separator <= 0 || separator == left.lastIndex) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "$label output payload shorthand must be output-payload:<command>:<path>[=<json>]",
            )
        }
        val command = left.substring(0, separator).trim()
        val path = left.substring(separator + 1).trim()
        if (command.isEmpty() || path.isEmpty()) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "$label output payload shorthand must be output-payload:<command>:<path>[=<json>]",
            )
        }
        val output =
            JsonObject().also { json ->
                json.addProperty("command", command)
                json.addProperty("payloadPath", path)
                if (splitAt >= 0) {
                    val expectedText = trimmed.substring(splitAt + 1).trim()
                    val expected =
                        try {
                            JsonValues.parse(expectedText)
                        } catch (error: SandboxException) {
                            throw SandboxException(
                                DiagnosticCode.INPUT_FORMAT,
                                "$label output payload shorthand expected JSON/SNBT-lite value but got '$expectedText'",
                                cause = error,
                            )
                        }
                    json.add("payloadEquals", expected)
                }
            }
        return JsonObject().also { it.add("output", output) }
    }

    private fun parseWarningContainsAssertion(
        text: String,
        label: String,
    ): JsonObject {
        val contains = text.trim()
        if (contains.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label warning shorthand must be warning:<text>")
        }
        val output =
            JsonObject().also { json ->
                json.addProperty("channel", "warning")
                json.addProperty("contains", contains)
            }
        return JsonObject().also { it.add("output", output) }
    }

    private fun parseWarningCountAssertion(
        count: String,
        label: String,
    ): JsonObject {
        val expected =
            count.trim().toIntOrNull()
                ?: throw SandboxException(
                    DiagnosticCode.INPUT_FORMAT,
                    "$label warning shorthand expected integer but got '${count.trim()}'",
                )
        val output =
            JsonObject().also { json ->
                json.addProperty("channel", "warning")
                json.addProperty("count", expected)
            }
        return JsonObject().also { it.add("output", output) }
    }

    private fun parseUnsupportedContainsAssertion(
        text: String,
        label: String,
    ): JsonObject {
        val contains = text.trim()
        if (contains.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label unsupported shorthand must be unsupported:<text>")
        }
        val output =
            JsonObject().also { json ->
                json.addProperty("command", "unsupported")
                json.addProperty("channel", "warning")
                json.addProperty("contains", contains)
            }
        return JsonObject().also { it.add("output", output) }
    }

    private fun parseUnsupportedCountAssertion(
        count: String,
        label: String,
    ): JsonObject {
        val expected =
            count.trim().toIntOrNull()
                ?: throw SandboxException(
                    DiagnosticCode.INPUT_FORMAT,
                    "$label unsupported shorthand expected integer but got '${count.trim()}'",
                )
        val output =
            JsonObject().also { json ->
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
        val parsed =
            try {
                JsonParser.parseString(text)
            } catch (error: Exception) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid JSON for --assert-file $file", cause = error)
            }
        val assertions =
            when {
                parsed.isJsonArray -> parsed.asJsonArray.toList()
                parsed.isJsonObject && parsed.asJsonObject.has("assertions") -> {
                    val element = parsed.asJsonObject.get("assertions")
                    if (!element.isJsonArray) {
                        throw SandboxException(
                            DiagnosticCode.INPUT_FORMAT,
                            "--assert-file $file field 'assertions' must be an array",
                        )
                    }
                    element.asJsonArray.toList()
                }
                parsed.isJsonObject -> listOf(parsed)
                else -> throw SandboxException(
                    DiagnosticCode.INPUT_FORMAT,
                    "--assert-file $file must contain an assertion object, assertion array, or object with assertions array",
                )
            }
        return assertions.mapIndexed { index, element ->
            if (!element.isJsonObject) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "--assert-file $file assertion ${index + 1} must be an object")
            }
            element.asJsonObject
        }
    }

    private fun parseJsonObject(
        raw: String,
        label: String,
    ): JsonObject =
        try {
            val parsed = JsonParser.parseString(raw)
            if (!parsed.isJsonObject) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must be a JSON object")
            parsed.asJsonObject
        } catch (error: SandboxException) {
            throw error
        } catch (error: Exception) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid JSON for $label", cause = error)
        }

    private fun executeCommandLines(
        sandbox: DatapackSandbox,
        lines: List<String>,
        sourceName: String,
    ): Int {
        var total = 0
        lines.forEachIndexed { index, raw ->
            val command = raw.removePrefix("\uFEFF").trim()
            if (command.isNotEmpty() && !command.startsWith("#")) {
                val normalized = command.removePrefix("/")
                total +=
                    executeDirectCommand(
                        sandbox,
                        normalized,
                        SourceLocation(file = sourceName, line = index + 1, command = normalized),
                    )
            }
        }
        return total
    }

    private fun executeDirectCommand(
        sandbox: DatapackSandbox,
        command: String,
        location: SourceLocation,
    ): Int =
        try {
            sandbox.executeCommand(command, location).commandsExecuted
        } catch (error: SandboxException) {
            if (!allowCommandFailure) throw error
            sandbox.world.traces
                .lastOrNull {
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
