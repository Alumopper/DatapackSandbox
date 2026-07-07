package moe.afox.dpsandbox.cli

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import moe.afox.dpsandbox.core.DatapackSandbox
import moe.afox.dpsandbox.core.ExecutionResult
import moe.afox.dpsandbox.core.JsonPaths
import moe.afox.dpsandbox.core.JsonValues
import moe.afox.dpsandbox.core.ResourceLocation
import moe.afox.dpsandbox.core.SandboxException
import moe.afox.dpsandbox.core.SandboxWorld
import moe.afox.dpsandbox.core.SnapshotDiff
import moe.afox.dpsandbox.core.UnsupportedFeatureMode
import moe.afox.dpsandbox.core.createSandbox
import moe.afox.dpsandbox.core.toPlayerJson
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.terminal.TerminalBuilder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

class Repl(
    private val version: String,
    private val packs: List<Path>,
    private val watch: Boolean = false,
    private val unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
    initialSandbox: DatapackSandbox? = null,
) {
    private var sandbox: DatapackSandbox = initialSandbox ?: createSandbox(version, packs, unsupportedFeatureMode = unsupportedFeatureMode)
    private var outputCursor = sandbox.world.outputs.size
    private var traceCursor = sandbox.world.traces.size
    private var traceEnabled = false
    private var lastCommandLine: String? = null
    private var lastBeforeSnapshot: JsonObject? = null
    private var lastAfterSnapshot: JsonObject? = null
    private var packStamp = fingerprintPacks()

    constructor(sandbox: DatapackSandbox) : this(sandbox.profile.id, emptyList(), false, sandbox.unsupportedFeatureMode, sandbox)

    fun run() {
        if (System.console() == null) {
            runDumb()
            return
        }

        val nonInteractive = System.console() == null
        val terminalBuilder = TerminalBuilder.builder()
            .system(true)
            .dumb(nonInteractive)
        if (nonInteractive) {
            terminalBuilder.jna(false).jni(false).ffm(false)
        }
        val terminal = terminalBuilder
            .build()
        val completer = DpsCompleter { sandbox }
        val reader = LineReaderBuilder.builder()
            .terminal(terminal)
            .completer(completer)
            .highlighter(DpsHighlighter { sandbox.profile })
            .variable(LineReader.HISTORY_FILE, Path.of(".dps_history"))
            .option(LineReader.Option.AUTO_MENU, true)
            .option(LineReader.Option.AUTO_LIST, true)
            .option(LineReader.Option.AUTO_MENU_LIST, true)
            .build()

        runCatching { DpsInlineHints.install(reader, completer) }

        terminal.writer().println(ConsoleStyle.bold("Datapack Sandbox REPL ${sandbox.profile.id}"))
        terminal.writer().println(helpText())
        terminal.writer().flush()

        while (true) {
            val line = try {
                reader.readLine(ConsoleStyle.cyan("dps> "))
            } catch (_: UserInterruptException) {
                terminal.writer().println()
                terminal.writer().flush()
                break
            } catch (_: EndOfFileException) {
                break
            }
            val keepGoing = handle(line.trim())
            if (!keepGoing) break
        }
    }

    private fun runDumb() {
        println(ConsoleStyle.bold("Datapack Sandbox REPL ${sandbox.profile.id}"))
        println(helpText())
        while (true) {
            print("dps> ")
            val line = readlnOrNull() ?: break
            val keepGoing = handle(line.trim())
            if (!keepGoing) break
        }
    }

    fun handle(line: String): Boolean {
        if (line.isBlank()) return true
        if (watch && !line.startsWith("reload")) reloadIfChanged()

        val parts = line.split(Regex("\\s+"))
        var keepGoing = true
        val outputBefore = sandbox.world.outputs.size
        val beforeSnapshot = sandbox.snapshotJson()
        var trackLast = false
        try {
            when (parts[0]) {
                "exit", "quit" -> keepGoing = false
                "help" -> printHelp(parts.getOrNull(1))
                "reload" -> reload()
                "load" -> {
                    if (parts.getOrNull(1) == "fixture") {
                        trackLast = true
                        loadFixture(parts.getOrNull(2) ?: throw IllegalArgumentException("fixture file is required"))
                    } else {
                        trackLast = true
                        printCommandResult("load", sandbox.runLoad(), outputBefore)
                    }
                }
                "tick" -> {
                    val count = parts.getOrNull(1)?.toIntOrNull() ?: 1
                    trackLast = true
                    printCommandResult("tick $count", sandbox.runTicks(count), outputBefore)
                }
                "function" -> {
                    val id = parts.getOrNull(1) ?: throw IllegalArgumentException("function id is required")
                    trackLast = true
                    printCommandResult("function $id", sandbox.runFunction(id), outputBefore)
                }
                "player" -> {
                    val name = parts.getOrNull(1) ?: throw IllegalArgumentException("player name is required")
                    trackLast = true
                    sandbox.createPlayer(name)
                    printManualResult("player $name", "created/reused player")
                }
                "event" -> {
                    trackLast = true
                    runEvent(parts, outputBefore)
                }
                "trace" -> trace(parts.drop(1))
                "diff" -> {
                    if (parts.getOrNull(1) == "last") printLastDiff() else println("Usage: diff last")
                }
                "rerun" -> {
                    if (parts.getOrNull(1) == "last") rerunLast() else println("Usage: rerun last")
                }
                "reset" -> {
                    if (parts.getOrNull(1) == "world") {
                        trackLast = true
                        resetWorld()
                    } else {
                        println("Usage: reset world")
                    }
                }
                "inspect" -> inspect(parts.drop(1))
                "snapshot" -> snapshot(parts.getOrNull(1))
                else -> {
                    trackLast = true
                    printCommandResult(line, sandbox.executeCommand(line), outputBefore)
                }
            }
        } catch (error: SandboxException) {
            println(ConsoleStyle.diagnostic(error.render()))
        } catch (error: Exception) {
            println(ConsoleStyle.red("ERROR: ${error.message}"))
        } finally {
            if (trackLast) {
                lastCommandLine = line
                lastBeforeSnapshot = beforeSnapshot
                lastAfterSnapshot = sandbox.snapshotJson()
            }
            printNewOutputs()
            printNewTraces()
        }
        return keepGoing
    }

    private fun printCommandResult(label: String, result: ExecutionResult, outputBefore: Int) {
        val newOutputs = sandbox.world.outputs.size - outputBefore
        val outputText = if (newOutputs > 0) ", outputs=+$newOutputs" else ""
        println(
            ConsoleStyle.green("OK") +
                " ${ConsoleStyle.bold(label)} " +
                ConsoleStyle.dim("(commands=${result.commandsExecuted}, gameTime=${sandbox.world.gameTime}$outputText)"),
        )
    }

    private fun printManualResult(label: String, detail: String) {
        println(ConsoleStyle.green("OK") + " ${ConsoleStyle.bold(label)} ${ConsoleStyle.dim("($detail)")}")
    }

    private fun printHelp(command: String?) {
        val text = when (command) {
            null -> helpText()
            "reload" -> "reload - reload datapack files from disk while keeping the in-memory world state"
            "event" -> eventHelp()
            "trace" -> "trace <on|off|status> - print command trace events produced after trace is enabled"
            "diff" -> "diff last - print the snapshot diff for the last executed world-changing command"
            "rerun" -> "rerun last - run the last executed world-changing command again"
            "reset" -> "reset world - replace the current world with a fresh sparse world"
            "load" -> "load - run #minecraft:load; load fixture <file> - apply a manifest-style world fixture JSON"
            "tellraw" -> "tellraw <targets> <message-json> - record a chat output event from a JSON text component"
            "title" -> "title <targets> <title|subtitle|actionbar|clear|reset|times> ... - record title output events"
            "inspect" -> inspectUsage()
            else -> "No detailed help for '$command'. Try TAB for available forms."
        }
        println(text)
    }

    private fun eventHelp(): String =
        """
        event player <name> <type> [id] [detail/action|x y z|pos=x,y,z]
        Injects a sandbox player behavior event. It is not a vanilla command; it drives advancement triggers, predicate context, and rewards.
        Examples:
          event player Steve item_used minecraft:carrot_on_a_stick
          event player Steve killed_entity minecraft:zombie
          event player Steve placed_block minecraft:oak_log 0 64 0
          event player Steve block_broken minecraft:oak_log pos=0,64,0
          event player Steve changed_dimension minecraft:overworld minecraft:the_nether
          event player Steve key_input key.jump
          event player Steve mouse_input left
        Use inspect player Steve, inspect advancement, and inspect outputs after dispatching events.
        """.trimIndent()

    private fun helpText(): String =
        "Commands: load, load fixture <file>, reload, tick [n], function <id>, player <name>, event player <name> <type> [id] [detail/action|x y z|pos=x,y,z], trace <on|off|status>, diff last, rerun last, reset world, ${inspectUsage()}, snapshot [file], exit"

    private fun inspectUsage(): String =
        "inspect <score|storage|gamerule|random|schedule|forced-chunks|scoreboard|entities|blocks|player|loot|predicate|advancement|recipe|item_modifier|raw|tags|resources|registry [group]|outputs|event-traces>"

    private fun reload() {
        if (packs.isEmpty()) {
            println(ConsoleStyle.yellow("reload is unavailable because this REPL was created from an existing sandbox instance"))
            return
        }
        sandbox = createSandbox(version, packs, sandbox.world, unsupportedFeatureMode = unsupportedFeatureMode, limits = sandbox.limits)
        packStamp = fingerprintPacks()
        println(
            ConsoleStyle.green(
                "reloaded packs: functions=${sandbox.datapack.functions.size} loot=${sandbox.datapack.lootTables.size} predicates=${sandbox.datapack.predicates.size} advancements=${sandbox.datapack.advancements.size} recipes=${sandbox.datapack.recipes.size} itemModifiers=${sandbox.datapack.itemModifiers.size} raw=${sandbox.datapack.rawResources.values.sumOf { it.size }} tags=${sandbox.datapack.tags.size}",
            ),
        )
    }

    private fun reloadIfChanged() {
        val current = fingerprintPacks()
        if (current > packStamp) {
            try {
                sandbox = createSandbox(version, packs, sandbox.world, unsupportedFeatureMode = unsupportedFeatureMode, limits = sandbox.limits)
                packStamp = current
                println(ConsoleStyle.yellow("packs changed; reloaded"))
            } catch (error: SandboxException) {
                println(ConsoleStyle.diagnostic(error.render()))
            }
        }
    }

    private fun trace(args: List<String>) {
        when (args.firstOrNull() ?: "status") {
            "on" -> {
                traceEnabled = true
                traceCursor = sandbox.world.traces.size
                printManualResult("trace on", "new command trace events will be printed")
            }
            "off" -> {
                traceEnabled = false
                printManualResult("trace off", "trace printing disabled")
            }
            "status" -> {
                val state = if (traceEnabled) "on" else "off"
                printManualResult("trace status", "state=$state total=${sandbox.world.traces.size}")
            }
            else -> println("Usage: trace <on|off|status>")
        }
    }

    private fun printLastDiff() {
        val before = lastBeforeSnapshot
        val after = lastAfterSnapshot
        if (before == null || after == null) {
            println("<no previous command>")
            return
        }
        println(SnapshotDiff.render(SnapshotDiff.stateDiff(before, after)))
    }

    private fun rerunLast() {
        val command = lastCommandLine
        if (command == null) {
            println("<no previous command>")
            return
        }
        println(ConsoleStyle.dim("rerun: $command"))
        handle(command)
    }

    private fun resetWorld() {
        val world = SandboxWorld().also { it.createPlayer("Steve") }
        sandbox = DatapackSandbox(sandbox.profile, sandbox.datapack, world, sandbox.unsupportedFeatureMode, sandbox.limits)
        outputCursor = sandbox.world.outputs.size
        traceCursor = sandbox.world.traces.size
        printManualResult("reset world", "gameTime=${sandbox.world.gameTime}, players=${sandbox.world.players.keys.joinToString()}")
    }

    private fun loadFixture(fileName: String) {
        val file = Path.of(fileName)
        val root = parseJsonObject(Files.readString(file, StandardCharsets.UTF_8), "fixture $file")
        val world = when {
            !root.has("world") -> root
            root.get("world").isJsonObject -> root.getAsJsonObject("world")
            else -> throw IllegalArgumentException("fixture file contains non-object world")
        }
        ManifestWorldSetup.apply(world, sandbox, file.parent ?: Path.of("."))
        printManualResult("load fixture $file", "applied")
    }

    private fun parseJsonObject(raw: String, label: String): JsonObject =
        try {
            val parsed = JsonParser.parseString(raw)
            if (!parsed.isJsonObject) throw IllegalArgumentException("$label must be a JSON object")
            parsed.asJsonObject
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid JSON for $label: ${error.message}", error)
        }

    private fun inspect(args: List<String>) {
        when (args.firstOrNull()) {
            "score" -> {
                if (args.size >= 3) {
                    println(sandbox.world.getScore(args[1], args[2]))
                } else {
                    sandbox.world.scores.toSortedMap().forEach { (key, value) ->
                        println("${key.objective} ${key.target} = $value")
                    }
                }
            }
            "storage" -> {
                if (args.size >= 3) {
                    val value = sandbox.world.storages[ResourceLocation.parse(args[1])]?.let { JsonPaths.get(it, args[2]) }
                    println(value?.let(JsonValues::render) ?: "<missing>")
                } else {
                    sandbox.world.storages.toSortedMap().forEach { (id, value) ->
                        println("$id = ${JsonValues.render(value)}")
                    }
                }
            }
            "gamerule", "gamerules" -> {
                val name = args.getOrNull(1)
                if (name == null) {
                    sandbox.world.gamerules.toSortedMap().forEach { (rule, value) ->
                        println("gamerule $rule = $value")
                    }
                } else {
                    println(sandbox.world.gamerules[name] ?: "<missing>")
                }
            }
            "random", "random-sequence", "random-sequences" -> {
                val name = args.getOrNull(1)
                if (name == null) {
                    sandbox.world.randomSequences.toSortedMap().forEach { (sequence, state) ->
                        println("$sequence = $state")
                    }
                } else {
                    println(sandbox.world.randomSequences[name]?.toString() ?: "<missing>")
                }
            }
            "schedule", "scheduled", "scheduled-functions", "scheduled_functions" -> {
                sandbox.world.scheduledFunctions
                    .sortedWith(compareBy({ it.dueTick }, { it.id.toString() }))
                    .forEach { scheduled ->
                        val remaining = (scheduled.dueTick - sandbox.world.gameTime).coerceAtLeast(0)
                        println("scheduled ${scheduled.id} dueTick=${scheduled.dueTick} remaining=$remaining")
                    }
            }
            "forced-chunks", "forced_chunks", "forceload", "force-loaded", "force_loaded" -> {
                println("forcedChunks count=${sandbox.world.forcedChunks.size}")
                sandbox.world.forcedChunks.sorted().forEach { chunk ->
                    println("forcedChunk ${chunk.x},${chunk.z}")
                }
            }
            "scoreboard" -> inspectScoreboard(args)
            "entities" -> {
                sandbox.world.entities.forEach { entity ->
                    println("${entity.uuid} ${entity.type} tags=${entity.tags.sorted().joinToString(prefix = "[", postfix = "]")}")
                }
            }
            "blocks" -> {
                sandbox.world.blocks.toSortedMap().forEach { (pos, block) ->
                    val properties = block.properties.toSortedMap().entries.joinToString(prefix = "[", postfix = "]") { "${it.key}=${it.value}" }
                    println("$pos ${block.id}$properties nbt=${JsonValues.render(block.nbt)}")
                }
            }
            "player" -> {
                val name = args.getOrNull(1)
                val players = if (name == null) sandbox.world.players.values else listOf(sandbox.world.requirePlayer(name))
                players.forEach { println(JsonValues.render(it.toPlayerJson(sandbox.profile))) }
            }
            "loot" -> sandbox.datapack.lootTables.keys.forEach { println(it) }
            "predicate" -> sandbox.datapack.predicates.keys.forEach { println(it) }
            "advancement" -> sandbox.datapack.advancements.keys.forEach { println(it) }
            "recipe" -> sandbox.datapack.recipes.keys.forEach { println(it) }
            "item_modifier", "item-modifier" -> sandbox.datapack.itemModifiers.keys.forEach { println(it) }
            "raw", "raw_resource", "raw-resource" -> inspectRawResource(args)
            "tag", "tags" -> {
                val registryFilter = args.getOrNull(1)
                sandbox.datapack.tags.toSortedMap()
                    .filterKeys { registryFilter == null || it.registry == registryFilter }
                    .forEach { (key, tag) ->
                        val values = tag.values.joinToString(prefix = "[", postfix = "]") { value ->
                            if (value.required) value.id else "${value.id}?"
                        }
                        println("${key.registry} ${key.id} replace=${tag.replace} values=$values")
                    }
            }
            "resource", "resources" -> {
                val typeFilter = args.getOrNull(1)
                ResourceSummaryRenderer.print(sandbox.profile.id, ManifestRunner.summarizeResources(sandbox))
                inspectResourceIndex(typeFilter)
            }
            "registry" -> inspectRegistry(args)
            "outputs" -> OutputRenderer.print(sandbox.world.outputs)
            "event-traces", "event_traces", "player-event-traces", "player_event_traces" -> {
                sandbox.world.playerEventTraces.forEach { println(JsonValues.render(it.toJson())) }
            }
            else -> println("Usage: ${inspectUsage()}")
        }
    }

    private fun inspectScoreboard(args: List<String>) {
        when (args.getOrNull(1)) {
            null, "objectives" -> {
                sandbox.world.objectives.toSortedMap().forEach { (name, criteria) ->
                    val metadata = sandbox.world.scoreboardObjectiveMetadata[name]
                    println(
                        "objective $name criteria=$criteria displayName=${metadata?.displayName ?: name} renderType=${metadata?.renderType ?: "integer"} displayAutoUpdate=${metadata?.displayAutoUpdate ?: true}",
                    )
                }
                if (args.getOrNull(1) == null && sandbox.world.scoreboardDisplays.isNotEmpty()) {
                    sandbox.world.scoreboardDisplays.toSortedMap().forEach { (slot, objective) ->
                        println("display $slot = $objective")
                    }
                }
            }
            "displays", "display" -> {
                sandbox.world.scoreboardDisplays.toSortedMap().forEach { (slot, objective) ->
                    println("display $slot = $objective")
                }
            }
            else -> println("Usage: inspect scoreboard [objectives|displays]")
        }
    }

    private fun inspectResourceIndex(typeFilter: String?) {
        sandbox.datapack.resourceIndex
            .filter { typeFilter == null || it.type == typeFilter }
            .forEach { entry ->
                val active = if (entry.active) "active" else "overridden"
                val overlay = listOfNotNull(
                    entry.overrides?.let { "overrides=$it" },
                    entry.overriddenBy?.let { "overriddenBy=$it" },
                ).joinToString(prefix = " ", separator = " ").takeIf { it.isNotBlank() }.orEmpty()
                println("${entry.type} ${entry.id} ${entry.behaviorLevel.id} $active pack=${entry.pack} file=${entry.file}$overlay")
            }
    }

    private fun inspectRegistry(args: List<String>) {
        val groupFilter = args.getOrNull(1)?.replace('-', '_')
        val selected = RegistryInspection.select(sandbox.profile, groupFilter)

        if (groupFilter != null && selected.isEmpty()) {
            println("<missing registry group $groupFilter>")
            return
        }

        val source = "profile:${sandbox.profile.id}"
        selected.forEach { group ->
            println("registry ${group.name} count=${group.entries.size} source=$source")
            group.entries.forEach { entry ->
                println("registry ${group.name} $entry source=$source")
            }
        }
    }

    private fun inspectRawResource(args: List<String>) {
        val kind = args.getOrNull(1)?.replace('-', '_')
        if (kind == null) {
            sandbox.datapack.rawResources.toSortedMap().forEach { (resourceKind, resources) ->
                println("$resourceKind ${resources.size}")
            }
            return
        }

        val resources = sandbox.datapack.rawResources[kind]
        if (resources == null) {
            println("<missing raw resource type $kind>")
            return
        }

        val id = args.getOrNull(2)?.let { ResourceLocation.parse(it) }
        if (id == null) {
            resources.toSortedMap().forEach { (resourceId, resource) ->
                println("$resourceId file=${resource.file}")
            }
            return
        }

        println(resources[id]?.let { JsonValues.render(it.root) } ?: "<missing>")
    }

    private fun runEvent(parts: List<String>, outputBefore: Int) {
        if (parts.getOrNull(1) != "player") {
            println("Usage: event player <name> <type> [id] [detail/action|x y z|pos=x,y,z]")
            return
        }
        val event = try {
            parsePlayerEventArgs(parts.drop(1), "event")
        } catch (error: SandboxException) {
            println(ConsoleStyle.diagnostic(error.render()))
            return
        }
        val player = sandbox.createPlayer(event.playerName)
        val updates = sandbox.handlePlayerEvent(event)
        val outputText = (sandbox.world.outputs.size - outputBefore).takeIf { it > 0 }?.let { ", outputs=+$it" }.orEmpty()
        val inputText = event.input?.let { ", input=${it.device}:${it.code}/${it.action}" }.orEmpty()
        val blockPosText = event.blockPos?.let { ", blockPos=${it.x},${it.y},${it.z}" }.orEmpty()
        printManualResult("event player ${player.name} ${event.type}", "updates=${updates.size}, gameTime=${sandbox.world.gameTime}$inputText$blockPosText$outputText")
        updates.forEach { println(it) }
    }

    private fun snapshot(file: String?) {
        val content = sandbox.snapshotString()
        if (file == null) {
            println(content)
        } else {
            Files.writeString(Path.of(file), content, StandardCharsets.UTF_8)
            println(ConsoleStyle.green("snapshot written: $file"))
        }
    }

    private fun printNewOutputs() {
        val newOutputs = sandbox.world.outputs.drop(outputCursor)
        outputCursor = sandbox.world.outputs.size
        OutputRenderer.print(newOutputs)
    }

    private fun printNewTraces() {
        if (!traceEnabled) return
        val newTraces = sandbox.world.traces.drop(traceCursor)
        traceCursor = sandbox.world.traces.size
        TraceRenderer.print(newTraces)
    }

    private fun fingerprintPacks(): Long =
        packs.maxOfOrNull { pack ->
            when {
                pack.isRegularFile() -> Files.getLastModifiedTime(pack).toMillis()
                pack.isDirectory() -> Files.walk(pack).use { walk ->
                    walk.filter { it.isRegularFile() }
                        .mapToLong { Files.getLastModifiedTime(it).toMillis() }
                        .max()
                        .orElse(0L)
                }
                else -> 0L
            }
        } ?: 0L
}
