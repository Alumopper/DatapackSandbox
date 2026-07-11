package moe.afox.dpsandbox.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import moe.afox.dpsandbox.core.CommandTraceEvent
import moe.afox.dpsandbox.core.DatapackSandbox
import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.FunctionSource
import moe.afox.dpsandbox.core.JsonValues
import moe.afox.dpsandbox.core.OutputEvent
import moe.afox.dpsandbox.core.PlayerEventTraceEvent
import moe.afox.dpsandbox.core.ResourceLocation
import moe.afox.dpsandbox.core.SandboxException
import moe.afox.dpsandbox.core.SandboxLimits
import moe.afox.dpsandbox.core.SandboxWorld
import moe.afox.dpsandbox.core.SingleFunctionDatapack
import moe.afox.dpsandbox.core.SnapshotDiff
import moe.afox.dpsandbox.core.SourceLocation
import moe.afox.dpsandbox.core.UnsupportedFeatureMode
import moe.afox.dpsandbox.core.VersionProfiles
import moe.afox.dpsandbox.core.createFunctionSandbox
import moe.afox.dpsandbox.core.createSandbox
import moe.afox.dpsandbox.core.toJson
import java.io.BufferedReader
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * JSONL stdin/stdout backend intended for editor integrations.
 *
 * Each request is one JSON object line: `{ "id": "1", "method": "snapshot", "params": {} }`.
 * Each response is one JSON object line with the same id and either `ok: true` plus `result`,
 * or `ok: false` plus a structured sandbox error.
 */
class ServeCommand : CliktCommand(name = "serve") {
    private val protocol by option("--protocol").default("jsonl")
    private val readyFile by option("--ready-file").path()

    override fun run() {
        if (protocol != "jsonl") {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Unsupported serve protocol '$protocol'; expected jsonl")
        }
        ServeSession().run(System.`in`.bufferedReader(StandardCharsets.UTF_8), System.out.bufferedWriter(StandardCharsets.UTF_8))
    }
}

internal class ServeSession {
    private data class FunctionSpec(
        val id: String,
        val text: String? = null,
        val path: Path? = null,
        val sourceName: String? = null,
    )

    private data class SessionConfig(
        val version: String,
        val packs: List<Path>,
        val functionSpecs: List<FunctionSpec>,
        val defaultPlayerName: String? = "Steve",
        val unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
        val limits: SandboxLimits = SandboxLimits(),
    )

    private var sandbox: DatapackSandbox? = null
    private var config: SessionConfig? = null
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    fun run(input: BufferedReader, output: BufferedWriter) {
        output.writeLine(success(JsonNull.INSTANCE, helloJson()))
        while (true) {
            val line = input.readLine() ?: break
            if (line.isBlank()) continue
            val response = handleLine(line)
            output.writeLine(response)
        }
    }

    private fun BufferedWriter.writeLine(json: JsonObject) {
        write(gson.toJson(json))
        newLine()
        flush()
    }

    private fun handleLine(line: String): JsonObject {
        val request = try {
            val parsed = JsonParser.parseString(line)
            if (!parsed.isJsonObject) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Serve request must be a JSON object")
            parsed.asJsonObject
        } catch (error: Exception) {
            return failure(JsonNull.INSTANCE, error.toSandboxException("Invalid serve request JSON"))
        }
        val id = request.get("id") ?: JsonNull.INSTANCE
        val method = request.string("method") ?: return failure(id, SandboxException(DiagnosticCode.INPUT_FORMAT, "Serve request is missing method"))
        val params = request.getAsJsonObjectOrNull("params") ?: JsonObject()
        return try {
            success(id, dispatch(method, params))
        } catch (error: SandboxException) {
            failure(id, error)
        } catch (error: Exception) {
            failure(id, SandboxException(DiagnosticCode.COMMAND_ERROR, error.message ?: error::class.simpleName.orEmpty(), cause = error))
        }
    }

    private fun dispatch(method: String, params: JsonObject): JsonElement =
        when (method) {
            "hello" -> helloJson()
            "versions" -> versionsJson()
            "createSandbox", "createFunctionSandbox", "open" -> create(params)
            "upsertFunctionSource" -> upsertFunctionSource(params)
            "reload" -> reload(keepWorld = params.boolean("keepWorld") ?: true)
            "resetWorld" -> resetWorld()
            "load" -> runTracked { it.runLoad().commandsExecuted }
            "tick", "ticks" -> runTracked { it.runTicks(params.int("count") ?: 1).commandsExecuted }
            "runFunction", "function" -> runTracked { box -> box.runFunction(params.requiredString("id")).commandsExecuted }
            "runManifest" -> runManifest(params)
            "runCommand", "command" -> runCommand(params)
            "runCommands", "commands" -> runCommands(params)
            "applyWorldFixture", "world" -> applyWorld(params)
            "injectPlayerEvent", "event" -> injectEvent(params)
            "snapshot" -> current().snapshotJson()
            "snapshotString" -> jsonObject("snapshot" to current().snapshotString())
            "resources" -> resourcesJson(current())
            "outputs" -> outputsJson(current().world.outputs, params.int("from") ?: 0)
            "traces" -> tracesJson(current().world.traces, params.int("from") ?: 0)
            "eventTraces" -> eventTracesJson(current().world.playerEventTraces, params.int("from") ?: 0)
            "state" -> stateJson(current())
            "completions" -> completions(params)
            "checkCommand" -> checkCommand(params)
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Unknown serve method '$method'")
        }

    private fun helloJson(): JsonObject = JsonObject().also { json ->
        json.addProperty("protocol", "dps-jsonl")
        json.addProperty("defaultVersion", VersionProfiles.default.id)
        json.add("versions", JsonArray().also { versions -> VersionProfiles.all.forEach { versions.add(it.id) } })
    }

    private fun versionsJson(): JsonObject = JsonObject().also { json ->
        json.addProperty("default", VersionProfiles.default.id)
        json.add("versions", JsonArray().also { array ->
            VersionProfiles.all.forEach { profile ->
                array.add(JsonObject().also { item ->
                    item.addProperty("id", profile.id)
                    item.addProperty("javaMajor", profile.javaMajor)
                    item.addProperty("dataVersion", profile.dataVersion)
                    item.addProperty("packFormat", profile.dataPackFormat.toString())
                })
            }
        })
    }

    private fun create(params: JsonObject): JsonObject {
        val version = params.string("version") ?: VersionProfiles.default.id
        val packs = params.stringArray("packs").map { Path.of(it).toAbsolutePath().normalize() }
        val functionSpecs = parseFunctionSpecs(params)
        val unsupported = unsupportedFeatureMode(params.string("unsupported") ?: "warn")
        val limits = parseLimits(params.getAsJsonObjectOrNull("limits"))
        val defaultPlayerName = params.get("defaultPlayerName")?.takeUnless { it.isJsonNull }?.asString
        val nextConfig = SessionConfig(version, packs, functionSpecs, defaultPlayerName, unsupported, limits)
        sandbox = createFromConfig(nextConfig, SandboxWorld())
        config = nextConfig
        return stateJson(current())
    }

    private fun reload(keepWorld: Boolean): JsonObject {
        val previous = current()
        val cfg = config ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "No sandbox has been created")
        sandbox = createFromConfig(cfg, if (keepWorld) previous.world else SandboxWorld())
        return stateJson(current())
    }

    private fun upsertFunctionSource(params: JsonObject): JsonObject {
        val previous = current()
        val cfg = config ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "No sandbox has been created")
        val id = params.requiredString("id")
        val replacement = FunctionSpec(
            id = id,
            text = params.string("text"),
            path = params.string("path")?.let { Path.of(it).toAbsolutePath().normalize() },
            sourceName = params.string("sourceName"),
        )
        if (replacement.text == null && replacement.path == null) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "upsertFunctionSource requires text or path")
        }
        val nextConfig = cfg.copy(functionSpecs = cfg.functionSpecs.filterNot { it.id == id } + replacement)
        sandbox = createFromConfig(nextConfig, previous.world)
        config = nextConfig
        return stateJson(current())
    }

    private fun runManifest(params: JsonObject): JsonObject {
        val box = current()
        val execution = ManifestRunner.runInExistingSandbox(
            Path.of(params.requiredString("path")).toAbsolutePath().normalize(),
            box,
            ManifestOptions(
                snapshotDiffOnFail = true,
                failOnMissingResources = params.boolean("strict") ?: false,
                unsupportedFeatureMode = if (params.boolean("strict") == true) UnsupportedFeatureMode.ERROR else box.unsupportedFeatureMode,
                limits = box.limits,
            ),
        )
        sandbox = execution.sandbox
        return manifestResultJson(execution.result).also { it.add("state", stateJson(execution.sandbox)) }
    }

    private fun resetWorld(): JsonObject {
        val cfg = config ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "No sandbox has been created")
        sandbox = createFromConfig(cfg, SandboxWorld())
        return stateJson(current())
    }

    private fun createFromConfig(cfg: SessionConfig, world: SandboxWorld): DatapackSandbox {
        val sources = cfg.functionSpecs.map { spec ->
            when {
                spec.text != null -> FunctionSource.text(spec.id, spec.text, spec.sourceName ?: "<serve:${spec.id}>")
                spec.path != null -> FunctionSource.file(spec.id, spec.path)
                else -> FunctionSource.text(spec.id, "", spec.sourceName ?: "<serve:${spec.id}>")
            }
        }
        return when {
            sources.isNotEmpty() && cfg.packs.isNotEmpty() -> createFunctionSandbox(cfg.version, cfg.packs, sources, world, cfg.defaultPlayerName, cfg.unsupportedFeatureMode, cfg.limits)
            sources.isNotEmpty() -> createFunctionSandbox(cfg.version, sources, world, cfg.defaultPlayerName, cfg.unsupportedFeatureMode, cfg.limits)
            cfg.packs.isNotEmpty() -> createSandbox(cfg.version, cfg.packs, world, cfg.defaultPlayerName, cfg.unsupportedFeatureMode, cfg.limits)
            else -> createFunctionSandbox(cfg.version, listOf(FunctionSource.text(SingleFunctionDatapack.DEFAULT_ID, "", "<serve:empty>")), world, cfg.defaultPlayerName, cfg.unsupportedFeatureMode, cfg.limits)
        }
    }

    private fun parseFunctionSpecs(params: JsonObject): List<FunctionSpec> {
        val specs = mutableListOf<FunctionSpec>()
        params.getAsJsonArrayOrNull("functionSources")?.forEachIndexed { index, element ->
            if (!element.isJsonObject) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "functionSources[$index] must be an object")
            val item = element.asJsonObject
            val id = item.string("id") ?: SingleFunctionDatapack.DEFAULT_ID
            val text = item.string("text") ?: item.string("content")
            val path = (item.string("path") ?: item.string("file"))?.let { Path.of(it).toAbsolutePath().normalize() }
            if (text != null && path != null) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "functionSources[$index] must not contain both text and path")
            specs += FunctionSpec(id, text, path, item.string("sourceName"))
        }
        params.string("mcfunction")?.let { specs += FunctionSpec(params.string("mcfunctionId") ?: SingleFunctionDatapack.DEFAULT_ID, path = Path.of(it).toAbsolutePath().normalize()) }
        params.string("mcfunctionText")?.let { specs += FunctionSpec(params.string("mcfunctionId") ?: SingleFunctionDatapack.DEFAULT_ID, text = it) }
        return specs
    }

    private fun runCommand(params: JsonObject): JsonObject = runTracked { box ->
        val command = params.requiredString("command").trim().removePrefix("/")
        val source = SourceLocation(
            file = params.string("file") ?: "<serve>",
            line = params.int("line"),
            command = command,
        )
        try {
            box.executeCommand(command, source).commandsExecuted
        } catch (error: SandboxException) {
            if (params.boolean("allowFailure") == true) {
                box.world.traces.lastOrNull()?.commandsExecuted ?: 0
            } else {
                throw error
            }
        }
    }

    private fun runCommands(params: JsonObject): JsonObject = runTracked { box ->
        var total = 0
        params.stringArray("commands").forEachIndexed { index, raw ->
            val command = raw.removePrefix("\uFEFF").trim().removePrefix("/")
            if (command.isNotEmpty() && !command.startsWith("#")) {
                total += try {
                    box.executeCommand(command, SourceLocation(params.string("file") ?: "<serve>", index + 1, command)).commandsExecuted
                } catch (error: SandboxException) {
                    if (params.boolean("allowFailure") == true) box.world.traces.lastOrNull()?.commandsExecuted ?: 0 else throw error
                }
            }
        }
        total
    }

    private fun applyWorld(params: JsonObject): JsonObject = runTracked { box ->
        val world = params.getAsJsonObjectOrNull("world") ?: params.getAsJsonObjectOrNull("fixture")
            ?: params.string("path")?.let { path ->
                val file = Path.of(path).toAbsolutePath().normalize()
                if (!file.exists()) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "World fixture does not exist: $file")
                val root = JsonParser.parseString(file.toFile().readText()).asJsonObject
                if (root.get("world")?.isJsonObject == true) root.getAsJsonObject("world") else root
            }
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "applyWorldFixture requires world, fixture, or path")
        val base = params.string("base")?.let { Path.of(it) } ?: params.string("path")?.let { Path.of(it).toAbsolutePath().normalize().parent } ?: Path.of(".")
        ManifestWorldSetup.apply(world, box, base)
        0
    }

    private fun injectEvent(params: JsonObject): JsonObject = runTracked { box ->
        val event = when {
            params.string("event") != null -> parsePlayerEventText(params.requiredString("event"), "serve event")
            else -> parsePlayerEventText(
                buildString {
                    append("player ").append(params.requiredString("player")).append(' ').append(params.requiredString("type"))
                    params.string("id")?.let { append(' ').append(it) }
                    params.string("detail")?.let { append(' ').append(it) }
                },
                "serve event",
            )
        }
        box.createPlayer(event.playerName)
        box.handlePlayerEvent(event)
        0
    }

    private fun completions(params: JsonObject): JsonObject {
        val engine = DpsCompletionEngine { current() }
        val buffer = params.string("buffer") ?: ""
        val cursor = (params.int("cursor") ?: buffer.length).coerceIn(0, buffer.length)
        val context = CompletionContext.parse(buffer, cursor)
        val start = (cursor - context.prefix.length).coerceAtLeast(0)
        return JsonObject().also { json ->
            json.add("suggestions", JsonArray().also { array ->
                engine.suggestions(buffer, cursor).forEach { suggestion ->
                    array.add(JsonObject().also { item ->
                        item.addProperty("value", suggestion.value)
                        item.addProperty("description", suggestion.description)
                        item.addProperty("group", suggestion.group)
                        item.addProperty("appendSpace", suggestion.appendSpace)
                        item.addProperty("start", start)
                        item.addProperty("end", cursor)
                        suggestion.behaviorLevel?.let { item.addProperty("behavior", it.id) }
                    })
                }
            })
            json.addProperty("inlineHint", engine.inlineHint(buffer, cursor))
            json.add("multilineHints", JsonArray().also { array -> engine.multilineHints(buffer, cursor).forEach { array.add(it.toString()) } })
        }
    }

    private fun checkCommand(params: JsonObject): JsonObject {
        val command = (params.string("command") ?: "").trim().removePrefix("/")
        if (command.isBlank()) {
            return commandCheckJson(valid = false, code = DiagnosticCode.INPUT_FORMAT, message = "Enter a command to check.")
        }
        val cfg = config ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "No sandbox has been created")
        val scratch = createFromConfig(cfg, SandboxWorld())
        copyValidationWorld(current().world, scratch.world)
        return try {
            scratch.executeCommand(command, SourceLocation(file = "<command-check>", line = 1, command = command))
            commandCheckJson(valid = true, message = "Command is valid for ${scratch.profile.id}.")
        } catch (error: SandboxException) {
            commandCheckJson(valid = false, code = error.code, message = error.message)
        }
    }

    private fun commandCheckJson(valid: Boolean, code: DiagnosticCode? = null, message: String): JsonObject = JsonObject().also { json ->
        json.addProperty("valid", valid)
        json.addProperty("severity", if (valid) "ok" else "error")
        code?.let { json.addProperty("code", it.name) }
        json.addProperty("message", message)
    }

    private fun copyValidationWorld(source: SandboxWorld, target: SandboxWorld) {
        target.setGameTime(source.gameTime)
        target.setDayTime(source.dayTime)
        target.weather = source.weather
        target.weatherDuration = source.weatherDuration
        target.difficulty = source.difficulty
        target.defaultGameMode = source.defaultGameMode
        target.seed = source.seed
        target.worldSpawn = source.worldSpawn.copy()
        target.tickRate = source.tickRate
        target.tickFrozen = source.tickFrozen
        target.objectives.putAll(source.objectives)
        target.scoreboardObjectiveMetadata.putAll(source.scoreboardObjectiveMetadata.mapValues { (_, value) -> value.copy() })
        target.scoreboardDisplays.putAll(source.scoreboardDisplays)
        target.scores.putAll(source.scores)
        source.storages.forEach { (id, value) -> target.storages[id] = value.deepCopy() }
        source.blocks.forEach { (position, block) ->
            target.blocks[position] = block.copy(properties = block.properties.toMutableMap(), nbt = block.nbt.deepCopy())
        }
        target.gamerules.putAll(source.gamerules)
        target.randomSequences.putAll(source.randomSequences)
        target.forcedChunks.addAll(source.forcedChunks)
        target.biomes.putAll(source.biomes)
        source.players.forEach { (name, player) ->
            target.createPlayer(name).also { copy ->
                copy.position = player.position
                copy.dimension = player.dimension
                copy.gameMode = player.gameMode
                copy.xp = player.xp
                copy.xpLevels = player.xpLevels
                copy.health = player.health
                copy.food = player.food
                copy.tags.addAll(player.tags)
            }
        }
    }

    private fun runTracked(block: (DatapackSandbox) -> Int): JsonObject {
        val box = current()
        val outputCursor = box.world.outputs.size
        val traceCursor = box.world.traces.size
        val eventTraceCursor = box.world.playerEventTraces.size
        val before = box.snapshotJson()
        val commands = block(box)
        val after = box.snapshotJson()
        return JsonObject().also { json ->
            json.addProperty("commands", commands)
            json.add("outputs", outputsJson(box.world.outputs, outputCursor).getAsJsonArray("outputs"))
            json.add("traces", tracesJson(box.world.traces, traceCursor).getAsJsonArray("traces"))
            json.add("eventTraces", eventTracesJson(box.world.playerEventTraces, eventTraceCursor).getAsJsonArray("eventTraces"))
            json.add("snapshotDiffs", SnapshotDiff.toJson(SnapshotDiff.stateDiff(before, after)))
            json.add("state", stateJson(box))
        }
    }

    private fun stateJson(box: DatapackSandbox): JsonObject = JsonObject().also { json ->
        json.addProperty("version", box.profile.id)
        json.addProperty("gameTime", box.world.gameTime)
        json.addProperty("entities", box.world.entities.size)
        json.addProperty("players", box.world.players.size)
        json.addProperty("outputs", box.world.outputs.size)
        json.addProperty("traces", box.world.traces.size)
        json.addProperty("eventTraces", box.world.playerEventTraces.size)
        json.addProperty("functions", box.datapack.functions.size)
        json.add("resources", resourcesJson(box).getAsJsonObject("summary"))
    }

    private fun resourcesJson(box: DatapackSandbox): JsonObject {
        val summary = box.datapack.resourceSummary()
        return JsonObject().also { json ->
            json.add("summary", JsonObject().also { s ->
                s.addProperty("functions", summary.functions)
                s.addProperty("lootTables", summary.lootTables)
                s.addProperty("predicates", summary.predicates)
                s.addProperty("advancements", summary.advancements)
                s.addProperty("recipes", summary.recipes)
                s.addProperty("itemModifiers", summary.itemModifiers)
                s.addProperty("tags", summary.tags)
                s.addProperty("rawResourceKinds", summary.rawResourceKinds)
                s.addProperty("rawResources", summary.rawResources)
                s.addProperty("resourceIndex", summary.resourceIndex)
                s.addProperty("activeResources", summary.activeResources)
                s.addProperty("overriddenResources", summary.overriddenResources)
                s.add("missingReferences", JsonArray().also { array ->
                    summary.missingReferences.forEach { ref ->
                        array.add(JsonObject().also { item ->
                            item.addProperty("source", ref.source)
                            item.addProperty("type", ref.type)
                            item.addProperty("id", ref.id.toString())
                        })
                    }
                })
            })
            json.add("resources", JsonArray().also { array ->
                box.datapack.resourceIndex.forEach { entry ->
                    array.add(JsonObject().also { item ->
                        item.addProperty("type", entry.type)
                        item.addProperty("id", entry.id.toString())
                        item.addProperty("file", entry.file)
                        item.addProperty("pack", entry.pack)
                        item.addProperty("order", entry.order)
                        item.addProperty("active", entry.active)
                        item.addProperty("behavior", entry.behaviorLevel.id)
                        entry.overrides?.let { item.addProperty("overrides", it) }
                        entry.overriddenBy?.let { item.addProperty("overriddenBy", it) }
                    })
                }
            })
            json.add("functionIds", stringArray(box.datapack.functions.keys.map { it.toString() }.sorted()))
            json.add("lootTableIds", stringArray(box.datapack.lootTables.keys.map { it.toString() }.sorted()))
            json.add("predicateIds", stringArray(box.datapack.predicates.keys.map { it.toString() }.sorted()))
            json.add("advancementIds", stringArray(box.datapack.advancements.keys.map { it.toString() }.sorted()))
        }
    }

    private fun outputsJson(outputs: List<OutputEvent>, from: Int): JsonObject = JsonObject().also { json ->
        json.addProperty("from", from.coerceAtLeast(0))
        json.addProperty("total", outputs.size)
        json.add("outputs", JsonArray().also { array -> outputs.drop(from.coerceAtLeast(0)).forEach { array.add(it.toJson()) } })
    }

    private fun tracesJson(traces: List<CommandTraceEvent>, from: Int): JsonObject = JsonObject().also { json ->
        json.addProperty("from", from.coerceAtLeast(0))
        json.addProperty("total", traces.size)
        json.add("traces", JsonArray().also { array -> traces.drop(from.coerceAtLeast(0)).forEach { array.add(it.toJson()) } })
    }

    private fun eventTracesJson(traces: List<PlayerEventTraceEvent>, from: Int): JsonObject = JsonObject().also { json ->
        json.addProperty("from", from.coerceAtLeast(0))
        json.addProperty("total", traces.size)
        json.add("eventTraces", JsonArray().also { array -> traces.drop(from.coerceAtLeast(0)).forEach { array.add(it.toJson()) } })
    }

    private fun manifestResultJson(result: ManifestResult): JsonObject = JsonObject().also { json ->
        json.addProperty("path", result.path.toString())
        json.addProperty("passed", result.passed)
        json.add("messages", stringArray(result.messages))
        json.add("outputs", JsonArray().also { array -> result.outputs.forEach { array.add(it.toJson()) } })
        json.add("traces", JsonArray().also { array -> result.traces.forEach { array.add(it.toJson()) } })
        json.add("diagnostics", JsonArray().also { array ->
            result.traces.filter { it.errorCode != null }.forEach { trace ->
                array.add(JsonObject().also { diagnostic ->
                    diagnostic.addProperty("code", trace.errorCode?.name)
                    diagnostic.addProperty("message", trace.errorMessage)
                    diagnostic.addProperty("command", trace.command)
                    trace.source?.let { diagnostic.add("source", it.toJson()) }
                })
            }
        })
        json.add("attempts", JsonArray().also { attempts ->
            result.attempts.forEach { attempt ->
                attempts.add(JsonObject().also { item ->
                    item.addProperty("version", attempt.version)
                    item.add("packs", stringArray(attempt.packs.map(Path::toString)))
                    item.addProperty("passed", attempt.passed)
                    item.add("messages", stringArray(attempt.messages))
                    item.add("outputs", JsonArray().also { array -> attempt.outputs.forEach { array.add(it.toJson()) } })
                    item.add("traces", JsonArray().also { array -> attempt.traces.forEach { array.add(it.toJson()) } })
                    attempt.snapshot?.let { item.add("snapshot", it.deepCopy()) }
                    item.add("snapshotDiffs", SnapshotDiff.toJson(attempt.snapshotDiffs))
                })
            }
        })
    }

    private fun current(): DatapackSandbox = sandbox
        ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "No sandbox has been created")

    private fun parseLimits(json: JsonObject?): SandboxLimits {
        val defaults = SandboxLimits()
        return try {
            SandboxLimits(
                maxCommands = json?.int("maxCommands") ?: defaults.maxCommands,
                maxFunctionDepth = json?.int("maxFunctionDepth") ?: defaults.maxFunctionDepth,
                maxTicksPerRun = json?.int("maxTicksPerRun") ?: defaults.maxTicksPerRun,
                maxOutputEvents = json?.int("maxOutputEvents") ?: defaults.maxOutputEvents,
                maxSnapshotBytes = json?.int("maxSnapshotBytes") ?: defaults.maxSnapshotBytes,
            )
        } catch (error: IllegalArgumentException) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, error.message ?: "Invalid sandbox limits", cause = error)
        }
    }

    private fun success(id: JsonElement, result: JsonElement): JsonObject = JsonObject().also { json ->
        json.add("id", id.deepCopy())
        json.addProperty("ok", true)
        json.add("result", result)
    }

    private fun failure(id: JsonElement, error: SandboxException): JsonObject = JsonObject().also { json ->
        json.add("id", id.deepCopy())
        json.addProperty("ok", false)
        json.add("error", JsonObject().also { err ->
            err.addProperty("code", error.code.name)
            err.addProperty("message", error.message)
            error.version?.let { err.addProperty("version", it) }
            error.command?.let { err.addProperty("command", it) }
            error.location?.let { location ->
                err.add("location", JsonObject().also { loc ->
                    location.file?.let { loc.addProperty("file", it) }
                    location.line?.let { loc.addProperty("line", it) }
                    location.command?.let { loc.addProperty("command", it) }
                })
            }
        })
    }

    private fun Exception.toSandboxException(message: String): SandboxException =
        this as? SandboxException ?: SandboxException(DiagnosticCode.INPUT_FORMAT, message, cause = this)

    private fun JsonObject.requiredString(name: String): String =
        string(name) ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Missing required string '$name'")

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.int(name: String): Int? =
        get(name)?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonPrimitive }?.asInt

    private fun JsonObject.boolean(name: String): Boolean? =
        get(name)?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonPrimitive }?.asBoolean

    private fun JsonObject.stringArray(name: String): List<String> {
        val value = get(name) ?: return emptyList()
        return when {
            value.isJsonArray -> value.asJsonArray.mapIndexed { index, element ->
                if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$name[$index] must be a string")
                }
                element.asString
            }
            value.isJsonPrimitive && value.asJsonPrimitive.isString -> listOf(value.asString)
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$name must be a string or array of strings")
        }
    }

    private fun JsonObject.getAsJsonObjectOrNull(name: String): JsonObject? =
        get(name)?.takeUnless { it.isJsonNull }?.let {
            if (!it.isJsonObject) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$name must be an object")
            it.asJsonObject
        }

    private fun JsonObject.getAsJsonArrayOrNull(name: String): JsonArray? =
        get(name)?.takeUnless { it.isJsonNull }?.let {
            if (!it.isJsonArray) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$name must be an array")
            it.asJsonArray
        }

    private fun jsonObject(vararg pairs: Pair<String, String>): JsonObject = JsonObject().also { json ->
        pairs.forEach { (name, value) -> json.addProperty(name, value) }
    }

    private fun stringArray(values: List<String>): JsonArray = JsonArray().also { array -> values.forEach(array::add) }
}
