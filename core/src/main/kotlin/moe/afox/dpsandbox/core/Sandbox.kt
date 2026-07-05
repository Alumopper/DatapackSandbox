package moe.afox.dpsandbox.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.nio.file.Path
import kotlin.math.floor
import kotlin.math.max
import kotlin.random.Random

/**
 * Execution context for one command or function invocation.
 *
 * The context provides the current executor entity and base position used by
 * selectors, relative coordinates, output sender resolution, and `execute`
 * subcommands.
 */
data class ExecutionContext(
    /** Current executor entity, or null for server context. */
    val entity: SandboxEntity? = null,
    /** Base position for relative coordinates. Defaults to [entity]'s position or the origin. */
    val position: Position = entity?.position ?: Position.zero,
    /** Current execution dimension. Non-player entities default to the overworld in the sparse sandbox. */
    val dimension: ResourceLocation = (entity as? SandboxPlayer)?.dimension ?: ResourceLocation("minecraft", "overworld"),
)

/**
 * Summary returned by command/function/tick execution methods.
 */
data class ExecutionResult(
    /** Number of command lines executed by the operation. */
    val commandsExecuted: Int,
    /** Explicit value returned by `return <value>` or `return run <command>`, when present. */
    val returnValue: Int? = null,
)

/**
 * Policy for vanilla commands that exist in the active profile but are not
 * implemented by the sandbox.
 */
enum class UnsupportedFeatureMode {
    /** Record a warning output event and continue. */
    WARN,
    /** Ignore the unsupported command and continue without an output event. */
    IGNORE,
    /** Throw a [SandboxException] with [DiagnosticCode.UNSUPPORTED_FEATURE]. */
    ERROR,
}

/**
 * Mutable clean-room datapack runtime.
 *
 * This class is the lower-level API under [SandboxQuickTest]. It loads one
 * [Datapack] under a specific [VersionProfile], owns the mutable [world], and
 * exposes direct methods for load/tick/function/command execution. It is not a
 * vanilla server: only sandbox-modeled datapack-visible state is updated.
 */
class DatapackSandbox(
    /** Active Minecraft version profile used for resource layout, command roots, and NBT validation. */
    val profile: VersionProfile,
    /** Loaded datapack resources. */
    val datapack: Datapack,
    /** Mutable in-memory world state used by this runtime. */
    val world: SandboxWorld = SandboxWorld(),
    /** Policy for recognized but unimplemented vanilla commands. */
    val unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
) {
    /** Predicate evaluator bound to the loaded datapack. */
    val predicates = PredicateEngine(datapack)
    /** Loot table evaluator bound to the loaded datapack and version registry view. */
    val loot = LootEngine(datapack, profile.registryView)
    /** Advancement runtime bound to this sandbox world. */
    val advancements = AdvancementRuntime(this)

    private var commandsExecuted = 0
    private var functionDepth = 0
    private var lastFunctionReturnValue: Int? = null
    private val functionStack = mutableListOf<FunctionTraceFrame>()

    /**
     * Runs every function referenced by `#minecraft:load`.
     *
     * @return number of command lines executed by load functions.
     */
    fun runLoad(): ExecutionResult {
        val before = commandsExecuted
        datapack.loadFunctions.forEach { runFunction(it) }
        return ExecutionResult(commandsExecuted - before)
    }

    /**
     * Advances the sandbox by [count] ticks.
     *
     * Each tick advances world time, runs due scheduled functions, runs
     * `#minecraft:tick` functions, and dispatches player tick advancement
     * events. Entity AI, physics, redstone, and block updates are not simulated.
     *
     * @throws SandboxException when [count] is negative.
     * @return number of command lines executed during the ticks.
     */
    fun runTicks(count: Int): ExecutionResult {
        if (count < 0) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Tick count must be non-negative")
        }
        val before = commandsExecuted
        repeat(count) {
            world.advanceTick()
            runDueScheduledFunctions()
            datapack.tickFunctions.forEach { runFunction(it) }
            world.players.values.forEach { player ->
                advancements.handle(PlayerEvent(player.name, "tick"))
            }
        }
        return ExecutionResult(commandsExecuted - before)
    }

    /**
     * Runs a loaded function by string resource location.
     *
     * @param id Function id such as `demo:main`.
     */
    fun runFunction(id: String): ExecutionResult = runFunction(ResourceLocation.parse(id))

    /**
     * Runs a loaded function by typed resource location.
     *
     * Function recursion is capped at depth 64 to avoid runaway test execution.
     *
     * @param context Executor and position context for relative command semantics.
     * @throws SandboxException when the function does not exist or call depth exceeds 64.
     * @return number of command lines executed inside the function.
     */
    fun runFunction(id: ResourceLocation, context: ExecutionContext = ExecutionContext()): ExecutionResult {
        val before = commandsExecuted
        val function = datapack.function(id)
        functionDepth += 1
        if (functionDepth > 64) {
            functionDepth -= 1
            throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Function call depth exceeded 64", version = profile.id)
        }

        var returnValue: Int? = null
        try {
            functionStack += FunctionTraceFrame(id, function.lines.firstOrNull()?.location?.file)
            for (line in function.lines) {
                executeCommand(line.command, line.location, context)
            }
        } catch (signal: ReturnSignal) {
            returnValue = signal.value
            // A return command stops the current function only.
        } finally {
            functionStack.removeLast()
            functionDepth -= 1
        }
        return ExecutionResult(commandsExecuted - before, returnValue)
    }

    /**
     * Executes one raw Minecraft command.
     *
     * A leading slash is accepted and removed. Blank commands are ignored.
     * Unsupported command behavior is controlled by [unsupportedFeatureMode].
     *
     * @param location Optional source location used in diagnostics.
     * @param context Executor and position context for selectors and relative coordinates.
     * @return number of command lines executed, normally 0 or 1 plus nested function calls.
     */
    fun executeCommand(command: String, location: SourceLocation? = null, context: ExecutionContext = ExecutionContext()): ExecutionResult {
        val normalized = command.trim().removePrefix("/")
        if (normalized.isBlank()) return ExecutionResult(0)
        val before = commandsExecuted
        val outputsBefore = world.outputs.size
        val source = CommandSource(
            file = location?.file,
            line = location?.line,
            command = location?.command ?: normalized,
            functionStack = functionStack.toList(),
        )
        val previousSource = world.currentCommandSource
        world.currentCommandSource = source
        var success = false
        var errorCode: DiagnosticCode? = null
        var errorMessage: String? = null
        try {
            executeOne(normalized, location, context)
            success = true
            return ExecutionResult(commandsExecuted - before)
        } catch (signal: ReturnSignal) {
            success = true
            throw signal
        } catch (error: SandboxException) {
            errorCode = error.code
            errorMessage = error.message
            throw error
        } catch (error: RuntimeException) {
            errorMessage = error.message ?: error::class.simpleName
            throw error
        } finally {
            world.traces += CommandTraceEvent(
                tick = world.gameTime,
                command = normalized,
                root = normalized.substringBefore(' '),
                source = source,
                executor = context.entity?.scoreHolder ?: "Server",
                position = context.position,
                success = success,
                commandsExecuted = commandsExecuted - before,
                outputs = world.outputs.size - outputsBefore,
                errorCode = errorCode,
                errorMessage = errorMessage,
            )
            world.currentCommandSource = previousSource
        }
    }

    /**
     * Returns a deterministic JSON snapshot of the current sandbox state.
     */
    fun snapshotJson(): JsonObject {
        val snapshot = world.snapshot(profile)
        snapshot.addProperty("version", profile.id)
        return snapshot
    }

    /**
     * Returns [snapshotJson] rendered as stable JSON text.
     */
    fun snapshotString(): String = JsonValues.render(snapshotJson())

    /**
     * Creates or reuses a sandbox player.
     */
    fun createPlayer(name: String): SandboxPlayer = world.createPlayer(name)

    /**
     * Dispatches a high-level player event.
     *
     * The event may update player input records and advancement progress. The
     * player referenced by the event must already exist.
     *
     * @return advancement updates caused by the event.
     */
    fun handlePlayerEvent(event: PlayerEvent): List<AdvancementUpdate> {
        val normalized = event.normalized()
        val player = world.requirePlayer(normalized.playerName)
        normalized.input?.let(player::recordInput)
        var updates: List<AdvancementUpdate> = emptyList()
        var success = false
        var errorCode: DiagnosticCode? = null
        var errorMessage: String? = null
        try {
            updates = advancements.handle(normalized)
            success = true
            return updates
        } catch (error: SandboxException) {
            errorCode = error.code
            errorMessage = error.message
            throw error
        } catch (error: RuntimeException) {
            errorMessage = error.message ?: error::class.simpleName
            throw error
        } finally {
            world.playerEventTraces += PlayerEventTraceEvent.from(
                tick = world.gameTime,
                event = normalized,
                updates = updates,
                success = success,
                errorCode = errorCode,
                errorMessage = errorMessage,
            )
        }
    }

    /**
     * Generates loot from a loaded loot table using an explicit context type.
     *
     * The optional [player] is used as the predicate context entity/player/tool
     * source. [seed] controls deterministic random output.
     */
    fun generateLoot(table: ResourceLocation, contextType: ResourceLocation, player: SandboxPlayer? = null, seed: Long = world.gameTime): LootResult =
        loot.generate(
            table,
            LootContext(
                type = contextType,
                predicateContext = PredicateContext(
                    world = world,
                    player = player,
                    dimension = player?.dimension,
                    thisEntity = player,
                    attackingPlayer = player,
                    interactingEntity = player,
                    origin = player?.position,
                    tool = player?.selectedItem,
                    weather = currentWeatherState(),
                ),
                seed = seed,
            ),
        )

    private fun executeOne(command: String, location: SourceLocation?, context: ExecutionContext) {
        if (command.isBlank()) return
        val tokens = CommandTokenizer.tokenize(command, location)
        if (tokens.isEmpty()) return
        commandsExecuted += 1

        try {
            when (tokens[0].text) {
                "function" -> executeFunction(tokens, location, context)
                "return" -> executeReturn(command, tokens, location, context)
                "attribute" -> executeAttribute(tokens, location, context)
                "scoreboard" -> executeScoreboard(tokens, location)
                "bossbar" -> executeBossbar(command, tokens, location, context)
                "clear" -> executeClear(tokens, location, context)
                "clone" -> executeClone(tokens, location, context)
                "damage" -> executeDamage(tokens, location, context)
                "datapack" -> executeDatapack(tokens, location)
                "defaultgamemode" -> executeDefaultGameMode(tokens, location)
                "difficulty" -> executeDifficulty(tokens, location)
                "execute" -> executeExecute(command, tokens, location, context)
                "data" -> executeData(command, tokens, location, context)
                "effect" -> executeEffect(tokens, location, context)
                "enchant" -> executeEnchant(tokens, location, context)
                "experience", "xp" -> executeExperience(tokens, location, context)
                "fillbiome" -> executeFillBiome(tokens, location, context)
                "forceload" -> executeForceload(tokens, location, context)
                "tag" -> executeTag(tokens, location, context)
                "gamerule" -> executeGamerule(tokens, location)
                "gamemode" -> executeGameMode(tokens, location, context)
                "give" -> executeGive(tokens, location, context)
                "help" -> executeHelp(tokens, location)
                "item" -> executeItem(tokens, location, context)
                "summon" -> executeSummon(command, tokens, location, context)
                "kill" -> executeKill(tokens, location, context)
                "list" -> executeList(tokens, location)
                "locate" -> executeLocate(tokens, location)
                "loot" -> executeLoot(tokens, location, context)
                "tp", "teleport" -> executeTeleport(tokens, location, context)
                "reload" -> executeReload(tokens, location)
                "setblock" -> executeSetBlock(tokens, location, context)
                "fill" -> executeFill(tokens, location, context)
                "random" -> executeRandom(tokens, location)
                "recipe" -> executeRecipe(tokens, location, context)
                "ride" -> executeRide(tokens, location, context)
                "rotate" -> executeRotate(tokens, location, context)
                "schedule" -> executeSchedule(tokens, location)
                "seed" -> executeSeed(tokens, location)
                "setworldspawn" -> executeSetWorldSpawn(tokens, location, context)
                "spawnpoint" -> executeSpawnPoint(tokens, location, context)
                "spectate" -> executeSpectate(tokens, location, context)
                "spreadplayers" -> executeSpreadPlayers(tokens, location, context)
                "team" -> executeTeam(command, tokens, location)
                "time" -> executeTime(tokens, location)
                "tick" -> executeTick(tokens, location)
                "trigger" -> executeTrigger(tokens, location, context)
                "weather" -> executeWeather(tokens, location)
                "worldborder" -> executeWorldBorder(tokens, location)
                "advancement" -> executeAdvancement(tokens, location)
                "tellraw" -> executeTellraw(command, tokens, location, context)
                "title" -> executeTitle(command, tokens, location, context)
                "say" -> executeSay(command, tokens, location, context)
                "me" -> executeMe(command, tokens, location, context)
                "msg", "tell", "w" -> executePrivateMessage(command, tokens, location, context)
                "teammsg", "tm" -> executeTeamMessage(command, tokens, location, context)
                "playsound" -> executePlaySound(tokens, location, context)
                "stopsound" -> executeStopSound(tokens, location, context)
                "particle" -> executeParticle(command, tokens, location)
                else -> handleUnknownCommand(tokens[0].text, command, location)
            }
        } catch (error: SandboxException) {
            if (error.code == DiagnosticCode.UNSUPPORTED_FEATURE &&
                unsupportedFeatureMode != UnsupportedFeatureMode.ERROR &&
                tokens.firstOrNull()?.text?.let(profile.commands::hasRoot) == true
            ) {
                if (unsupportedFeatureMode == UnsupportedFeatureMode.WARN) {
                    recordUnsupportedWarning(command, error, location)
                }
                return
            }
            if (error.location == null && location != null) {
                throw SandboxException(error.code, error.message, location, error.version ?: profile.id, error.command, error)
            }
            throw error
        }
    }

    private fun handleUnknownCommand(root: String, command: String, location: SourceLocation?) {
        if (!profile.commands.hasRoot(root)) {
            throw SandboxException(
                code = DiagnosticCode.INPUT_FORMAT,
                message = "Unknown command '$root' for version ${profile.id}",
                location = location,
                version = profile.id,
                command = command,
            )
        }
        unsupportedFeature(
            message = "Command '$root' is not implemented for version ${profile.id}",
            version = profile.id,
            location = location,
            command = command,
        )
    }

    private fun recordUnsupportedWarning(command: String, error: SandboxException, location: SourceLocation?) {
        val payload = JsonObject()
        payload.addProperty("code", error.code.name)
        payload.addProperty("message", error.message)
        payload.addProperty("command", command)
        payload.addProperty("version", error.version ?: profile.id)
        location?.let {
            payload.addProperty("file", it.file)
            payload.addProperty("line", it.line)
        }
        world.recordOutput(
            command = "unsupported",
            channel = "warning",
            text = error.message,
            payload = payload,
        )
    }

    private fun executeFunction(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 2, "function <id>", location)
        lastFunctionReturnValue = runFunction(ResourceLocation.parse(tokens[1].text), context).returnValue
    }

    private fun executeReturn(command: String, tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext): Nothing {
        requireSize(tokens, 2, "return <value|fail|run ...>", location)
        when (tokens[1].text) {
            "fail" -> throw ReturnSignal(0)
            "run" -> {
                val rest = CommandTokenizer.tailAfter(command, tokens[1])
                if (rest.isBlank()) {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Expected: return run <command>", location)
                }
                val commandsBefore = commandsExecuted
                val outputsBefore = world.outputs.size
                lastFunctionReturnValue = null
                executeOne(rest, location, context)
                val commandsRun = (commandsExecuted - commandsBefore).coerceAtLeast(0)
                throw ReturnSignal(executeStoreValue("result", commandsRun, outputsBefore, lastFunctionReturnValue))
            }
            else -> throw ReturnSignal(parseInt(tokens[1].text, "return value", location))
        }
    }

    private fun executeHelp(tokens: List<CommandToken>, location: SourceLocation?) {
        requireSize(tokens, 1, "help [command]", location)
        val topic = tokens.getOrNull(1)?.text
        val text = if (topic == null) {
            profile.commands.roots.sorted().joinToString(", ")
        } else if (profile.commands.hasRoot(topic)) {
            "$topic: ${commandHelp(topic)}"
        } else {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Unknown help topic '$topic'", location, version = profile.id)
        }
        world.recordOutput("help", "data", text = text)
    }

    private fun commandHelp(root: String): String =
        when (root) {
            "function" -> "function <namespace:path>"
            "scoreboard" -> "scoreboard objectives|players ..."
            "execute" -> "execute ... run <command>"
            "data" -> "data get|merge|modify|remove <target> ..."
            "gamemode" -> "gamemode <mode> [targets]"
            "worldborder" -> "worldborder add|center|damage|get|set|warning ..."
            else -> if (profile.commands.hasRoot(root)) "vanilla root command; sandbox support may be partial" else "unknown"
        }

    private fun executeList(tokens: List<CommandToken>, location: SourceLocation?) {
        requireSize(tokens, 1, "list [uuids]", location)
        val names = world.players.keys.sorted()
        val payload = JsonObject()
        payload.addProperty("count", names.size)
        payload.add("players", JsonArray().also { array -> names.forEach(array::add) })
        if (tokens.getOrNull(1)?.text == "uuids") {
            world.players.toSortedMap().forEach { (name, player) -> payload.addProperty(name, player.uuid) }
        }
        world.recordOutput("list", "data", text = "There are ${names.size} player(s): ${names.joinToString()}", payload = payload)
    }

    private fun executeLoot(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 2, "loot <give|insert|spawn|replace> ...", location)
        when (tokens[1].text) {
            "give" -> {
                requireSize(tokens, 5, "loot give <players> loot <table>", location)
                val items = parseLootSource(tokens, 3, context, location)
                resolvePlayers(tokens[2].text, location, context).forEach { player ->
                    player.inventory += items.map { it.copy(components = it.components.deepCopy(), nbt = it.nbt.deepCopy()) }
                    items.forEach { advancements.handle(PlayerEvent(player.name, "inventory_changed", item = it)) }
                }
            }
            "insert" -> {
                requireSize(tokens, 7, "loot insert <pos> loot <table>", location)
                val pos = parseBlockPos(tokens, 2, context.position, location)
                insertLootIntoBlock(pos, parseLootSource(tokens, 5, context, location), location)
            }
            "spawn" -> {
                requireSize(tokens, 7, "loot spawn <pos> loot <table>", location)
                val pos = parsePosition(tokens, 2, context.position, location)
                spawnLootItems(pos, parseLootSource(tokens, 5, context, location))
            }
            "replace" -> executeLootReplace(tokens, location, context)
            else -> unsupportedFeature("Unsupported loot target '${tokens[1].text}'", profile.id, location)
        }
    }

    private fun executeLocate(tokens: List<CommandToken>, location: SourceLocation?) {
        requireSize(tokens, 3, "locate <biome|structure|poi> <id>", location)
        if (tokens[1].text !in setOf("biome", "structure", "poi")) {
            unsupportedFeature("Unsupported locate type '${tokens[1].text}'", profile.id, location)
        }
        val payload = JsonObject()
        payload.addProperty("type", tokens[1].text)
        payload.addProperty("id", ResourceLocation.parse(tokens[2].text).toString())
        payload.addProperty("found", false)
        world.recordOutput("locate", "data", text = "No ${tokens[1].text} result in sandbox void world", payload = payload)
    }

    private fun executeSpectate(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        val spectator = tokens.getOrNull(2)?.text?.let { resolvePlayers(it, location, context).firstOrNull() }
            ?: context.entity as? SandboxPlayer
        val target = tokens.getOrNull(1)?.text?.takeIf { it != "stop" }?.let { EntitySelectors.select(world, it, context, location).firstOrNull() }
        val payload = JsonObject()
        spectator?.let {
            it.gameMode = "spectator"
            payload.addProperty("spectator", it.name)
        }
        target?.let { payload.addProperty("target", it.uuid) }
        world.recordOutput("spectate", "data", text = if (target == null) "spectate cleared" else "spectating ${target.uuid}", payload = payload)
    }

    private fun executeSpreadPlayers(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 7, "spreadplayers <center> <spreadDistance> <maxRange> <respectTeams> <targets>", location)
        val centerX = parseCoordinate(tokens[1].text, context.position.x, location)
        val centerZ = parseCoordinate(tokens[2].text, context.position.z, location)
        val maxRange = parseDouble(tokens[4].text, "spread max range", location).coerceAtLeast(0.0)
        val targets = tokens.drop(6).flatMap { EntitySelectors.select(world, it.text, context, location) }.distinctBy { it.uuid }
        if (targets.isEmpty()) return
        val step = if (targets.size == 1) 0.0 else (maxRange * 2.0) / targets.size
        targets.forEachIndexed { index, entity ->
            val x = centerX - maxRange + step * index
            val z = centerZ + if (index % 2 == 0) maxRange / 2.0 else -maxRange / 2.0
            entity.position = Position(x, entity.position.y, z)
            if (entity is SandboxPlayer) advancements.handle(PlayerEvent(entity.name, "moved"))
        }
        world.recordOutput("spreadplayers", "data", text = targets.size.toString())
    }

    private fun executeLootReplace(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 3, "loot replace <entity|block> ...", location)
        when (tokens[2].text) {
            "entity" -> {
                requireSize(tokens, 7, "loot replace entity <entities> <slot> [count] loot <table>", location)
                val slot = inventorySlot(tokens[4].text)
                val sourceIndex = if (tokens[5].text == "loot") 5 else 6
                val count = if (sourceIndex == 6) parseInt(tokens[5].text, "loot replace count", location) else 1
                val items = parseLootSource(tokens, sourceIndex, context, location).take(count.coerceAtLeast(0))
                EntitySelectors.select(world, tokens[3].text, context, location).filterIsInstance<SandboxPlayer>().forEach { player ->
                    val item = items.firstOrNull() ?: ItemStack(ResourceLocation("minecraft", "air"), 0)
                    while (player.inventory.size <= slot) player.inventory += ItemStack(ResourceLocation("minecraft", "air"), 0)
                    player.inventory[slot] = item.copy(components = item.components.deepCopy(), nbt = item.nbt.deepCopy())
                }
            }
            "block" -> {
                requireSize(tokens, 9, "loot replace block <pos> <slot> [count] loot <table>", location)
                val pos = parseBlockPos(tokens, 3, context.position, location)
                val slot = inventorySlot(tokens[6].text)
                val sourceIndex = if (tokens[7].text == "loot") 7 else 8
                val count = if (sourceIndex == 8) parseInt(tokens[7].text, "loot replace count", location) else 1
                val item = parseLootSource(tokens, sourceIndex, context, location).take(count.coerceAtLeast(0)).firstOrNull()
                replaceBlockItem(pos, slot, item, location)
            }
            else -> unsupportedFeature("Unsupported loot replace target '${tokens[2].text}'", profile.id, location)
        }
    }

    private fun parseLootSource(tokens: List<CommandToken>, index: Int, context: ExecutionContext, location: SourceLocation?): List<ItemStack> {
        requireIndex(tokens, index, "loot source", location)
        return when (tokens[index].text) {
            "loot" -> {
                requireIndex(tokens, index + 1, "loot <table>", location)
                generateLootItems(ResourceLocation.parse(tokens[index + 1].text), ResourceLocation("minecraft", "command"), context)
            }
            "fish" -> {
                requireSizeFrom(tokens, index, 5, "loot source fish <table> <pos> [tool]", location)
                val origin = parsePosition(tokens, index + 2, context.position, location)
                val tool = tokens.getOrNull(index + 5)?.text?.let(::lootTool)
                generateLootItems(ResourceLocation.parse(tokens[index + 1].text), ResourceLocation("minecraft", "fishing"), context, origin = origin, tool = tool)
            }
            "mine" -> {
                requireSizeFrom(tokens, index, 4, "loot source mine <pos> [tool]", location)
                val pos = parseBlockPos(tokens, index + 1, context.position, location)
                val block = world.block(pos) ?: return emptyList()
                val tool = tokens.getOrNull(index + 4)?.text?.let(::lootTool)
                val table = block.nbt.get("LootTable")?.takeIf { it.isJsonPrimitive }?.asString?.let(ResourceLocation::parse)
                if (table == null) {
                    listOf(ItemStack(block.id))
                } else {
                    generateLootItems(table, ResourceLocation("minecraft", "block"), context, origin = Position(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble()), tool = tool, block = block.id)
                }
            }
            "kill" -> {
                requireIndex(tokens, index + 1, "loot source kill <target>", location)
                EntitySelectors.select(world, tokens[index + 1].text, context, location).flatMap { entity ->
                    val table = entity.nbt.get("DeathLootTable")?.takeIf { it.isJsonPrimitive }?.asString?.let(ResourceLocation::parse)
                    table?.let {
                        generateLootItems(it, ResourceLocation("minecraft", "entity"), context, origin = entity.position, thisEntity = entity)
                    }.orEmpty()
                }
            }
            else -> unsupportedFeature("Unsupported loot source '${tokens[index].text}'", profile.id, location)
        }
    }

    private fun generateLootItems(
        table: ResourceLocation,
        contextType: ResourceLocation,
        execution: ExecutionContext,
        origin: Position = execution.position,
        thisEntity: SandboxEntity? = execution.entity,
        tool: ItemStack? = (execution.entity as? SandboxPlayer)?.selectedItem,
        block: ResourceLocation? = null,
    ): List<ItemStack> {
        val player = thisEntity as? SandboxPlayer ?: execution.entity as? SandboxPlayer
        return loot.generate(
            table,
            LootContext(
                type = contextType,
                predicateContext = PredicateContext(
                    world = world,
                    origin = origin,
                    dimension = execution.dimension,
                    thisEntity = thisEntity,
                    player = player,
                    attackingPlayer = execution.entity as? SandboxPlayer,
                    interactingEntity = execution.entity,
                    tool = tool,
                    block = block,
                    weather = currentWeatherState(),
                ),
                seed = world.gameTime,
                tool = tool,
            ),
        ).items
    }

    private fun lootTool(raw: String): ItemStack =
        ItemStack(ResourceLocation.parse(raw))

    private fun insertLootIntoBlock(pos: BlockPos, items: List<ItemStack>, location: SourceLocation?) {
        val block = world.requireBlock(pos)
        val updated = block.fullNbt(pos, profile, location)
        val itemsArray = updated.getAsJsonArray("Items") ?: JsonArray().also { updated.add("Items", it) }
        var nextSlot = itemsArray.size()
        items.forEach { item ->
            val itemJson = blockItemJson(item)
            itemJson.addProperty("Slot", nextSlot++)
            itemsArray.add(itemJson)
        }
        block.writeFullNbt(pos, profile, updated, location)
    }

    private fun replaceBlockItem(pos: BlockPos, slot: Int, item: ItemStack?, location: SourceLocation?) {
        val block = world.requireBlock(pos)
        val updated = block.fullNbt(pos, profile, location)
        val itemsArray = updated.getAsJsonArray("Items") ?: JsonArray().also { updated.add("Items", it) }
        val iterator = itemsArray.iterator()
        while (iterator.hasNext()) {
            val element = iterator.next()
            if (element.isJsonObject && element.asJsonObject.get("Slot")?.asInt == slot) iterator.remove()
        }
        if (item != null && item.count > 0) {
            val itemJson = blockItemJson(item)
            itemJson.addProperty("Slot", slot)
            itemsArray.add(itemJson)
        }
        block.writeFullNbt(pos, profile, updated, location)
    }

    private fun blockItemJson(item: ItemStack): JsonObject {
        val json = item.toJson()
        json.remove("nbt")
        if (item.nbt.entrySet().isNotEmpty()) {
            json.getAsJsonObject("components").add("minecraft:custom_data", item.nbt.deepCopy())
        }
        return json
    }

    private fun spawnLootItems(position: Position, items: List<ItemStack>) {
        items.forEach { item ->
            world.entities += SandboxEntity(
                type = ResourceLocation("minecraft", "item"),
                position = position,
                nbt = JsonObject().also { it.add("Item", item.toJson()) },
            )
        }
    }

    private fun executeDatapack(tokens: List<CommandToken>, location: SourceLocation?) {
        requireSize(tokens, 2, "datapack <list|enable|disable> ...", location)
        when (tokens[1].text) {
            "list" -> {
                val payload = JsonObject()
                payload.addProperty("functions", datapack.functions.size)
                payload.addProperty("lootTables", datapack.lootTables.size)
                payload.addProperty("predicates", datapack.predicates.size)
                payload.addProperty("advancements", datapack.advancements.size)
                payload.addProperty("recipes", datapack.recipes.size)
                payload.addProperty("itemModifiers", datapack.itemModifiers.size)
                payload.addProperty("tags", datapack.tags.size)
                payload.addProperty("rawResourceKinds", datapack.rawResources.size)
                payload.addProperty("rawResources", datapack.rawResources.values.sumOf { it.size })
                payload.addProperty("resourceIndex", datapack.resourceIndex.size)
                payload.addProperty("activeResources", datapack.resourceIndex.count { it.active })
                payload.addProperty("overriddenResources", datapack.resourceIndex.count { !it.active })
                payload.add(
                    "resourceOverrides",
                    JsonArray().also { overrides ->
                        datapack.resourceIndex
                            .filter { !it.active || it.overrides != null || it.overriddenBy != null }
                            .forEach { overrides.add(it.toResourceIndexPayload()) }
                    },
                )
                world.recordOutput("datapack list", "data", text = "Loaded datapack resources", payload = payload)
            }
            "enable", "disable" -> {
                requireSize(tokens, 3, "datapack ${tokens[1].text} <name>", location)
                world.recordOutput("datapack ${tokens[1].text}", "warning", text = "Datapack order is fixed at sandbox creation; '${tokens[1].text}' is accepted as a no-op")
            }
            else -> unsupportedFeature("Unsupported datapack action '${tokens[1].text}'", profile.id, location)
        }
    }

    private fun ResourceIndexEntry.toResourceIndexPayload(): JsonObject =
        JsonObject().also { payload ->
            payload.addProperty("type", type)
            payload.addProperty("id", id.toString())
            payload.addProperty("pack", pack)
            payload.addProperty("file", file)
            payload.addProperty("active", active)
            overrides?.let { payload.addProperty("overrides", it) }
            overriddenBy?.let { payload.addProperty("overriddenBy", it) }
        }

    private fun executeReload(tokens: List<CommandToken>, location: SourceLocation?) {
        requireSize(tokens, 1, "reload", location)
        world.recordOutput("reload", "data", text = "Reload is a no-op inside this immutable sandbox instance")
    }

    private fun executeSeed(tokens: List<CommandToken>, location: SourceLocation?) {
        requireSize(tokens, 1, "seed", location)
        world.recordOutput("seed", "data", text = world.seed.toString())
    }

    private fun executeDifficulty(tokens: List<CommandToken>, location: SourceLocation?) {
        requireSize(tokens, 1, "difficulty [peaceful|easy|normal|hard]", location)
        tokens.getOrNull(1)?.let {
            val difficulty = normalizeDifficulty(it.text, location)
            world.difficulty = difficulty
        }
        world.recordOutput("difficulty", "data", text = world.difficulty)
    }

    private fun executeDefaultGameMode(tokens: List<CommandToken>, location: SourceLocation?) {
        requireSize(tokens, 2, "defaultgamemode <mode>", location)
        world.defaultGameMode = normalizeGameMode(tokens[1].text, location)
    }

    private fun executeGameMode(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 2, "gamemode <mode> [targets]", location)
        val mode = normalizeGameMode(tokens[1].text, location)
        val targets = tokens.getOrNull(2)?.text?.let { resolvePlayers(it, location, context) }
            ?: listOf(context.entity as? SandboxPlayer ?: throw SandboxException(DiagnosticCode.COMMAND_ERROR, "gamemode without targets requires a player execution context", location))
        targets.forEach { it.gameMode = mode }
    }

    private fun executeAttribute(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 4, "attribute <target> <attribute> <get|base|modifier> ...", location)
        val entity = EntitySelectors.select(world, tokens[1].text, context, location).singleOrNull()
            ?: throw SandboxException(DiagnosticCode.COMMAND_ERROR, "attribute requires exactly one target", location)
        val attribute = ResourceLocation.parse(tokens[2].text)
        when (tokens[3].text) {
            "get" -> {
                val scale = tokens.getOrNull(4)?.text?.let { parseDouble(it, "attribute scale", location) } ?: 1.0
                world.recordOutput("attribute get", "data", text = ((entity.attributes[attribute] ?: defaultAttribute(attribute)) * scale).toString())
            }
            "base" -> {
                requireSize(tokens, 5, "attribute <target> <attribute> base <get|set|reset>", location)
                when (tokens[4].text) {
                    "get" -> {
                        val scale = tokens.getOrNull(5)?.text?.let { parseDouble(it, "attribute scale", location) } ?: 1.0
                        world.recordOutput("attribute base get", "data", text = ((entity.attributes[attribute] ?: defaultAttribute(attribute)) * scale).toString())
                    }
                    "set" -> {
                        requireSize(tokens, 6, "attribute <target> <attribute> base set <value>", location)
                        entity.attributes[attribute] = parseDouble(tokens[5].text, "attribute base", location)
                    }
                    "reset" -> entity.attributes.remove(attribute)
                    else -> unsupportedFeature("Unsupported attribute base action '${tokens[4].text}'", profile.id, location)
                }
            }
            "modifier" -> {
                world.recordOutput("attribute modifier", "warning", text = "Attribute modifiers are accepted as a sandbox no-op; base values are supported")
            }
            else -> unsupportedFeature("Unsupported attribute action '${tokens[3].text}'", profile.id, location)
        }
    }

    private fun executeScoreboard(tokens: List<CommandToken>, location: SourceLocation?) {
        requireSize(tokens, 3, "scoreboard <objectives|players> ...", location)
        when (tokens[1].text) {
            "objectives" -> executeObjectives(tokens, location)
            "players" -> executePlayers(tokens, location)
            else -> unsupportedFeature("Unsupported scoreboard subcommand '${tokens[1].text}'", profile.id, location)
        }
    }

    private fun executeObjectives(tokens: List<CommandToken>, location: SourceLocation?) {
        when (tokens[2].text) {
            "add" -> {
                requireSize(tokens, 5, "scoreboard objectives add <objective> <criteria>", location)
                world.addObjective(tokens[3].text, tokens[4].text)
            }
            "remove" -> {
                requireSize(tokens, 4, "scoreboard objectives remove <objective>", location)
                world.removeObjective(tokens[3].text)
            }
            "list" -> recordObjectiveList()
            else -> unsupportedFeature("Unsupported scoreboard objectives action '${tokens[2].text}'", profile.id, location)
        }
    }

    private fun recordObjectiveList() {
        val payload = JsonObject()
        val entries = mutableListOf<String>()
        world.objectives.toSortedMap().forEach { (name, criteria) ->
            payload.addProperty(name, criteria)
            entries += "$name ($criteria)"
        }
        val text = if (entries.isEmpty()) {
            "No scoreboard objectives"
        } else {
            entries.joinToString()
        }
        world.recordOutput("scoreboard objectives list", "data", text = text, payload = payload)
    }

    private fun executePlayers(tokens: List<CommandToken>, location: SourceLocation?) {
        requireSize(tokens, 3, "scoreboard players <set|add|remove|get|operation> ...", location)
        when (tokens[2].text) {
            "set", "add", "remove" -> {
                requireSize(tokens, 6, "scoreboard players ${tokens[2].text} <target> <objective> <value>", location)
                val value = parseInt(tokens[5].text, "score value", location)
                scoreTargets(tokens[3].text, ExecutionContext(), location).forEach { target ->
                    when (tokens[2].text) {
                        "set" -> world.setScore(target, tokens[4].text, value)
                        "add" -> world.addScore(target, tokens[4].text, value)
                        "remove" -> world.addScore(target, tokens[4].text, -value)
                    }
                }
            }
            "get" -> {
                requireSize(tokens, 5, "scoreboard players get <target> <objective>", location)
                scoreTargets(tokens[3].text, ExecutionContext(), location).forEach { world.getScore(it, tokens[4].text) }
            }
            "reset" -> {
                requireSize(tokens, 4, "scoreboard players reset <target> [objective]", location)
                val targets = scoreTargets(tokens[3].text, ExecutionContext(), location).toSet()
                val objective = tokens.getOrNull(4)?.text
                world.scores.keys.filter { it.target in targets && (objective == null || it.objective == objective) }
                    .forEach { world.scores.remove(it) }
            }
            "list" -> {
                val target = tokens.getOrNull(3)?.text
                val entries = world.scores.toSortedMap().filter { (key, _) -> target == null || key.target == target }
                val payload = JsonObject()
                entries.forEach { (key, value) -> payload.addProperty("${key.target} ${key.objective}", value) }
                world.recordOutput("scoreboard players list", "data", text = entries.entries.joinToString { "${it.key.target} ${it.key.objective}=${it.value}" }, payload = payload)
            }
            "enable" -> Unit
            "operation" -> executeScoreOperation(tokens, location)
            else -> unsupportedFeature("Unsupported scoreboard players action '${tokens[2].text}'", profile.id, location)
        }
    }

    private fun executeBossbar(command: String, tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 2, "bossbar <add|remove|list|get|set> ...", location)
        when (tokens[1].text) {
            "add" -> {
                requireSize(tokens, 4, "bossbar add <id> <name>", location)
                val id = ResourceLocation.parse(tokens[2].text)
                world.bossbars[id] = SandboxBossbar(id, CommandTokenizer.tailFrom(command, tokens[3]))
            }
            "remove" -> {
                requireSize(tokens, 3, "bossbar remove <id>", location)
                world.bossbars.remove(ResourceLocation.parse(tokens[2].text))
            }
            "list" -> world.recordOutput("bossbar list", "data", text = world.bossbars.keys.sorted().joinToString())
            "get" -> {
                requireSize(tokens, 4, "bossbar get <id> <field>", location)
                val bar = world.bossbars[ResourceLocation.parse(tokens[2].text)] ?: throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Bossbar '${tokens[2].text}' does not exist", location)
                val value = when (tokens[3].text) {
                    "value" -> bar.value.toString()
                    "max" -> bar.max.toString()
                    "visible" -> bar.visible.toString()
                    "players" -> bar.players.joinToString()
                    else -> bar.toJson().get(tokens[3].text)?.asString ?: ""
                }
                world.recordOutput("bossbar get", "data", text = value)
            }
            "set" -> {
                requireSize(tokens, 5, "bossbar set <id> <field> <value>", location)
                val bar = world.bossbars[ResourceLocation.parse(tokens[2].text)] ?: throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Bossbar '${tokens[2].text}' does not exist", location)
                when (tokens[3].text) {
                    "name" -> bar.name = CommandTokenizer.tailFrom(command, tokens[4])
                    "value" -> bar.value = parseInt(tokens[4].text, "bossbar value", location)
                    "max" -> bar.max = parseInt(tokens[4].text, "bossbar max", location).coerceAtLeast(1)
                    "color" -> bar.color = tokens[4].text
                    "style" -> bar.style = tokens[4].text
                    "visible" -> bar.visible = parseBoolean(tokens[4].text, "bossbar visible", location)
                    "players" -> {
                        bar.players.clear()
                        if (tokens.size > 4) bar.players += resolvePlayers(tokens[4].text, location, context).map { it.name }
                    }
                    else -> unsupportedFeature("Unsupported bossbar field '${tokens[3].text}'", profile.id, location)
                }
            }
            else -> unsupportedFeature("Unsupported bossbar action '${tokens[1].text}'", profile.id, location)
        }
    }

    private fun executeGamerule(tokens: List<CommandToken>, location: SourceLocation?) {
        requireSize(tokens, 2, "gamerule <rule> [value]", location)
        val rule = tokens[1].text
        if (tokens.size >= 3) {
            world.gamerules[rule] = tokens[2].text
        } else {
            world.recordOutput("gamerule", "data", text = world.gamerules[rule] ?: "<unset>")
        }
    }

    private fun executeTime(tokens: List<CommandToken>, location: SourceLocation?) {
        requireSize(tokens, 2, "time <set|add|query> ...", location)
        when (tokens[1].text) {
            "set" -> {
                requireSize(tokens, 3, "time set <value|day|noon|night|midnight>", location)
                world.setDayTime(parseTimeOfDay(tokens[2].text, location))
            }
            "add" -> {
                requireSize(tokens, 3, "time add <time>", location)
                world.addDayTime(parseLong(tokens[2].text, "time delta", location))
            }
            "query" -> {
                requireSize(tokens, 3, "time query <daytime|gametime|day>", location)
                val value = when (tokens[2].text) {
                    "daytime" -> world.dayTime
                    "gametime" -> world.gameTime
                    "day" -> world.gameTime / 24000
                    else -> unsupportedFeature("Unsupported time query '${tokens[2].text}'", profile.id, location)
                }
                world.recordOutput("time query", "data", text = value.toString())
            }
            else -> unsupportedFeature("Unsupported time action '${tokens[1].text}'", profile.id, location)
        }
    }

    private fun executeWeather(tokens: List<CommandToken>, location: SourceLocation?) {
        requireSize(tokens, 2, "weather <clear|rain|thunder> [duration]", location)
        val weather = tokens[1].text
        if (weather !in setOf("clear", "rain", "thunder")) {
            unsupportedFeature("Unsupported weather '${tokens[1].text}'", profile.id, location)
        }
        world.weather = weather
        world.weatherDuration = tokens.getOrNull(2)?.text?.let { parseInt(it, "weather duration", location) } ?: 0
    }

    private fun executeRandom(tokens: List<CommandToken>, location: SourceLocation?) {
        requireSize(tokens, 2, "random <value|roll|reset> ...", location)
        when (tokens[1].text) {
            "value", "roll" -> {
                requireSize(tokens, 3, "random ${tokens[1].text} <range> [sequence]", location)
                val (min, max) = parseIntRange(tokens[2].text, location)
                val sequence = tokens.getOrNull(3)?.text ?: "default"
                val seed = world.randomSequences.getOrPut(sequence) { world.gameTime xor sequence.hashCode().toLong() }
                val random = Random(seed + world.gameTime + world.outputs.size)
                val value = if (max <= min) min else random.nextInt(min, max + 1)
                world.recordOutput("random ${tokens[1].text}", "data", text = value.toString())
            }
            "reset" -> {
                tokens.getOrNull(2)?.text?.let { world.randomSequences[it] = tokens.getOrNull(3)?.text?.toLongOrNull() ?: world.gameTime }
                    ?: world.randomSequences.clear()
            }
            else -> unsupportedFeature("Unsupported random action '${tokens[1].text}'", profile.id, location)
        }
    }

    private fun executeScoreOperation(tokens: List<CommandToken>, location: SourceLocation?) {
        requireSize(tokens, 8, "scoreboard players operation <target> <objective> <op> <source> <sourceObjective>", location)
        val operation = tokens[5].text
        val sourceValue = world.getScore(tokens[6].text, tokens[7].text)
        scoreTargets(tokens[3].text, ExecutionContext(), location).forEach { target ->
            val current = world.getScore(target, tokens[4].text)
            val result = when (operation) {
                "=" -> sourceValue
                "+=" -> current + sourceValue
                "-=" -> current - sourceValue
                "*=" -> current * sourceValue
                "/=" -> if (sourceValue == 0) current else current / sourceValue
                "%=" -> if (sourceValue == 0) current else current % sourceValue
                "<" -> minOf(current, sourceValue)
                ">" -> maxOf(current, sourceValue)
                else -> unsupportedFeature("Unsupported scoreboard operation '$operation'", profile.id, location)
            }
            world.setScore(target, tokens[4].text, result)
        }
    }

    private fun scoreTargets(token: String, context: ExecutionContext, location: SourceLocation?): List<String> =
        if (EntitySelectors.isSelector(token)) {
            EntitySelectors.select(world, token, context, location).map { it.scoreHolder }
        } else {
            listOf(token)
        }

    private fun executeExecute(command: String, tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        var index = 1
        var contexts = listOf(context)

        while (index < tokens.size) {
            when (tokens[index].text) {
                "run" -> {
                    val rest = CommandTokenizer.tailAfter(command, tokens[index])
                    contexts.forEach { executeOne(rest, location, it) }
                    return
                }
                "as" -> {
                    requireIndex(tokens, index + 1, "execute as <selector>", location)
                    contexts = contexts.flatMap { ctx ->
                        EntitySelectors.select(world, tokens[index + 1].text, ctx, location).map {
                            ctx.copy(entity = it)
                        }
                    }
                    index += 2
                }
                "at" -> {
                    requireIndex(tokens, index + 1, "execute at <selector>", location)
                    contexts = contexts.flatMap { ctx ->
                        EntitySelectors.select(world, tokens[index + 1].text, ctx, location).map {
                            ctx.copy(position = it.position, dimension = entityDimension(it))
                        }
                    }
                    index += 2
                }
                "positioned" -> {
                    requireIndex(tokens, index + 1, "execute positioned <x> <y> <z>|as <selector>", location)
                    if (tokens[index + 1].text == "as") {
                        requireIndex(tokens, index + 2, "execute positioned as <selector>", location)
                        contexts = contexts.flatMap { ctx ->
                            EntitySelectors.select(world, tokens[index + 2].text, ctx, location).map {
                                ctx.copy(position = it.position)
                            }
                        }
                        index += 3
                    } else {
                        requireSizeFrom(tokens, index, 4, "execute positioned <x> <y> <z>", location)
                        contexts = contexts.map { ctx ->
                            ctx.copy(position = parsePosition(tokens, index + 1, ctx.position, location))
                        }
                        index += 4
                    }
                }
                "align" -> {
                    requireIndex(tokens, index + 1, "execute align <axes>", location)
                    contexts = contexts.map { ctx ->
                        var pos = ctx.position
                        if ('x' in tokens[index + 1].text) pos = pos.copy(x = floor(pos.x))
                        if ('y' in tokens[index + 1].text) pos = pos.copy(y = floor(pos.y))
                        if ('z' in tokens[index + 1].text) pos = pos.copy(z = floor(pos.z))
                        ctx.copy(position = pos)
                    }
                    index += 2
                }
                "anchored" -> {
                    requireIndex(tokens, index + 1, "execute anchored <eyes|feet>", location)
                    index += 2
                }
                "facing" -> {
                    requireIndex(tokens, index + 1, "execute facing <pos|entity>", location)
                    index += if (tokens[index + 1].text == "entity") 4 else 4
                }
                "in" -> {
                    requireIndex(tokens, index + 1, "execute in <dimension>", location)
                    contexts = contexts.map { ctx -> ctx.copy(dimension = ResourceLocation.parse(tokens[index + 1].text)) }
                    index += 2
                }
                "rotated" -> {
                    requireIndex(tokens, index + 1, "execute rotated <yaw pitch>|as <target>", location)
                    index += if (tokens[index + 1].text == "as") 3 else 3
                }
                "store" -> {
                    val runIndex = tokens.indexOfFirst { it.text == "run" }
                    if (runIndex < 0) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "execute store requires run", location)
                    val commandsBefore = commandsExecuted
                    val outputsBefore = world.outputs.size
                    val rest = CommandTokenizer.tailAfter(command, tokens[runIndex])
                    lastFunctionReturnValue = null
                    contexts.forEach { executeOne(rest, location, it) }
                    val commandsRun = (commandsExecuted - commandsBefore).coerceAtLeast(0)
                    val stored = executeStoreValue(
                        tokens.getOrNull(index + 1)?.text ?: "result",
                        commandsRun,
                        outputsBefore,
                        lastFunctionReturnValue,
                    )
                    storeExecuteValue(tokens, index + 1, stored, location, contexts.firstOrNull() ?: context)
                    return
                }
                "if", "unless" -> {
                    val positive = tokens[index].text == "if"
                    val evaluated = contexts.map { it to evaluateCondition(tokens, index + 1, location, it) }
                    contexts = evaluated
                        .filter { (_, result) -> result.first == positive }
                        .map { (ctx, _) -> ctx }
                    index = evaluated.firstOrNull()?.second?.second ?: nextConditionIndex(tokens, index + 1, location)
                }
                else -> unsupportedFeature("Unsupported execute subcommand '${tokens[index].text}'", profile.id, location, command)
            }
        }
    }

    private fun evaluateCondition(tokens: List<CommandToken>, index: Int, location: SourceLocation?, context: ExecutionContext): Pair<Boolean, Int> {
        requireIndex(tokens, index, "execute if|unless <condition>", location)
        return when (tokens[index].text) {
            "entity" -> {
                requireIndex(tokens, index + 1, "execute if entity <selector>", location)
                EntitySelectors.select(world, tokens[index + 1].text, context, location).isNotEmpty() to index + 2
            }
            "score" -> evaluateScoreCondition(tokens, index, location) to nextScoreConditionIndex(tokens, index)
            "data" -> evaluateDataCondition(tokens, index, location, context)
            "block" -> {
                requireSizeFrom(tokens, index, 5, "execute if|unless block <pos> <block>", location)
                val pos = parseBlockPos(tokens, index + 1, context.position, location)
                (matchesBlock(pos, tokens[index + 4].text, location) to index + 5)
            }
            "blocks" -> evaluateBlocksCondition(tokens, index, location, context)
            "predicate" -> {
                requireIndex(tokens, index + 1, "execute if|unless predicate <id>", location)
                predicates.test(ResourceLocation.parse(tokens[index + 1].text), executePredicateContext(context)) to index + 2
            }
            "function" -> {
                requireIndex(tokens, index + 1, "execute if|unless function <id|#tag>", location)
                evaluateFunctionCondition(tokens[index + 1].text, context, location) to index + 2
            }
            "dimension" -> {
                requireIndex(tokens, index + 1, "execute if|unless dimension <id>", location)
                (context.dimension == ResourceLocation.parse(tokens[index + 1].text)) to index + 2
            }
            "biome" -> {
                requireSizeFrom(tokens, index, 5, "execute if|unless biome <pos> <biome>", location)
                val pos = parseBlockPos(tokens, index + 1, context.position, location)
                (world.biomes[pos] == ResourceLocation.parse(tokens[index + 4].text)) to index + 5
            }
            "loaded" -> {
                requireSizeFrom(tokens, index, 4, "execute if|unless loaded <pos>", location)
                val pos = parseBlockPos(tokens, index + 1, context.position, location)
                (ChunkPos(Math.floorDiv(pos.x, 16), Math.floorDiv(pos.z, 16)) in world.forcedChunks) to index + 4
            }
            else -> unsupportedFeature("Unsupported execute condition '${tokens[index].text}'", profile.id, location)
        }
    }

    private fun nextConditionIndex(tokens: List<CommandToken>, index: Int, location: SourceLocation?): Int {
        requireIndex(tokens, index, "execute if|unless <condition>", location)
        return when (tokens[index].text) {
            "entity", "predicate", "function", "dimension" -> {
                requireIndex(tokens, index + 1, "execute if|unless ${tokens[index].text} <argument>", location)
                index + 2
            }
            "score" -> nextScoreConditionIndex(tokens, index)
            "data" -> {
                val (_, pathIndex) = parseDataTarget(tokens, index + 1, ExecutionContext(), location)
                requireIndex(tokens, pathIndex, "execute if data <target> <path>", location)
                pathIndex + 1
            }
            "block", "biome" -> {
                requireSizeFrom(tokens, index, 5, "execute if|unless ${tokens[index].text} <pos> <id>", location)
                index + 5
            }
            "blocks" -> {
                requireSizeFrom(tokens, index, 11, "execute if|unless blocks <begin> <end> <destination> <all|masked>", location)
                index + 11
            }
            "loaded" -> {
                requireSizeFrom(tokens, index, 4, "execute if|unless loaded <pos>", location)
                index + 4
            }
            else -> unsupportedFeature("Unsupported execute condition '${tokens[index].text}'", profile.id, location)
        }
    }

    private fun evaluateFunctionCondition(raw: String, context: ExecutionContext, location: SourceLocation?): Boolean {
        val ids = if (raw.startsWith("#")) {
            resolveFunctionTag(ResourceLocation.parse(raw.removePrefix("#")), location)
        } else {
            listOf(ResourceLocation.parse(raw))
        }
        if (ids.isEmpty()) return false
        return ids.map { runFunction(it, context) }.any { functionConditionPassed(it) }
    }

    private fun functionConditionPassed(result: ExecutionResult): Boolean =
        result.returnValue?.let { it > 0 } ?: (result.commandsExecuted > 0)

    private fun resolveFunctionTag(
        id: ResourceLocation,
        location: SourceLocation?,
        visited: MutableSet<ResourceLocation> = linkedSetOf(),
    ): List<ResourceLocation> {
        if (!visited.add(id)) {
            throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Function tag '#$id' contains a cycle", location)
        }
        val definition = datapack.tags[TagKey("function", id)]
            ?: datapack.tags[TagKey("functions", id)]
            ?: throw SandboxException(DiagnosticCode.RESOURCE_NOT_FOUND, "Function tag '#$id' was not found", location)
        val functions = definition.values.flatMap { value ->
            if (value.id.startsWith("#")) {
                try {
                    resolveFunctionTag(ResourceLocation.parse(value.id.removePrefix("#")), location, visited)
                } catch (error: SandboxException) {
                    if (error.code == DiagnosticCode.RESOURCE_NOT_FOUND && !value.required) emptyList() else throw error
                }
            } else {
                val functionId = ResourceLocation.parse(value.id)
                if (datapack.functions.containsKey(functionId)) {
                    listOf(functionId)
                } else if (!value.required) {
                    emptyList()
                } else {
                    throw SandboxException(DiagnosticCode.RESOURCE_NOT_FOUND, "Function '$functionId' in tag '#$id' was not found", location)
                }
            }
        }
        visited.remove(id)
        return functions
    }

    private fun executePredicateContext(context: ExecutionContext): PredicateContext {
        val player = context.entity as? SandboxPlayer
        return PredicateContext(
            world = world,
            origin = context.position,
            dimension = context.dimension,
            thisEntity = context.entity,
            player = player,
            tool = player?.selectedItem,
            weather = currentWeatherState(),
        )
    }

    private fun currentWeatherState(): WeatherState =
        WeatherState(raining = world.weather == "rain" || world.weather == "thunder", thundering = world.weather == "thunder")

    private fun entityDimension(entity: SandboxEntity): ResourceLocation =
        (entity as? SandboxPlayer)?.dimension ?: ResourceLocation("minecraft", "overworld")

    private fun evaluateBlocksCondition(tokens: List<CommandToken>, index: Int, location: SourceLocation?, context: ExecutionContext): Pair<Boolean, Int> {
        requireSizeFrom(tokens, index, 11, "execute if|unless blocks <begin> <end> <destination> <all|masked>", location)
        val from = parseBlockPos(tokens, index + 1, context.position, location)
        val to = parseBlockPos(tokens, index + 4, context.position, location)
        val dest = parseBlockPos(tokens, index + 7, context.position, location)
        val mode = tokens[index + 10].text
        if (mode !in setOf("all", "masked")) {
            unsupportedFeature("Unsupported execute blocks mode '$mode'", profile.id, location)
        }

        val xs = minOf(from.x, to.x)..maxOf(from.x, to.x)
        val ys = minOf(from.y, to.y)..maxOf(from.y, to.y)
        val zs = minOf(from.z, to.z)..maxOf(from.z, to.z)
        val volume = xs.count() * ys.count() * zs.count()
        if (volume > 32768) {
            throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Execute blocks volume $volume exceeds sandbox limit 32768", location)
        }

        for ((dx, x) in xs.withIndex()) for ((dy, y) in ys.withIndex()) for ((dz, z) in zs.withIndex()) {
            val source = world.block(BlockPos(x, y, z))
            if (mode == "masked" && source == null) continue
            val target = world.block(BlockPos(dest.x + dx, dest.y + dy, dest.z + dz))
            if (!sameBlock(source, target)) return false to index + 11
        }
        return true to index + 11
    }

    private fun sameBlock(left: SandboxBlock?, right: SandboxBlock?): Boolean {
        if (left == null || left.id == ResourceLocation("minecraft", "air")) {
            return right == null || right.id == ResourceLocation("minecraft", "air")
        }
        if (right == null || right.id == ResourceLocation("minecraft", "air")) return false
        return left.id == right.id && left.properties == right.properties && left.nbt == right.nbt
    }

    private fun evaluateScoreCondition(tokens: List<CommandToken>, index: Int, location: SourceLocation?): Boolean {
        requireSizeFrom(tokens, index, 5, "execute if score <target> <objective> matches <range>", location)
        val value = world.getScore(tokens[index + 1].text, tokens[index + 2].text)
        return when (val operator = tokens[index + 3].text) {
            "matches" -> scoreMatches(value, tokens[index + 4].text, location)
            "=", "<", "<=", ">", ">=" -> {
                requireIndex(tokens, index + 5, "execute if score comparison requires source target and objective", location)
                val other = world.getScore(tokens[index + 4].text, tokens[index + 5].text)
                when (operator) {
                    "=" -> value == other
                    "<" -> value < other
                    "<=" -> value <= other
                    ">" -> value > other
                    else -> value >= other
                }
            }
            else -> unsupportedFeature("Unsupported score condition operator '$operator'", profile.id, location)
        }
    }

    private fun nextScoreConditionIndex(tokens: List<CommandToken>, index: Int): Int =
        if (index + 3 < tokens.size && tokens[index + 3].text == "matches") index + 5 else index + 6

    private fun evaluateDataCondition(tokens: List<CommandToken>, index: Int, location: SourceLocation?, context: ExecutionContext): Pair<Boolean, Int> {
        requireSizeFrom(tokens, index, 4, "execute if data <storage|entity> ...", location)
        val (target, pathIndex) = parseDataTarget(tokens, index + 1, context, location)
        requireIndex(tokens, pathIndex, "execute if data <target> <path>", location)
        return (dataTargetNbtValues(target, location).any { JsonPaths.exists(it, tokens[pathIndex].text) } to pathIndex + 1)
    }

    private fun executeData(command: String, tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 3, "data <modify|get|remove> ...", location)
        when (tokens[1].text) {
            "modify" -> executeDataModify(command, tokens, location, context)
            "merge" -> executeDataMerge(command, tokens, location, context)
            "get" -> executeDataGet(tokens, location, context)?.let {
                world.recordOutput("get", "data", text = JsonValues.render(it), payload = it)
            }
            "remove" -> executeDataRemove(tokens, location, context)
            else -> unsupportedFeature("Unsupported data action '${tokens[1].text}'", profile.id, location)
        }
    }

    private fun executeDataModify(command: String, tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 6, "data modify <storage|entity|block> <target> <path> <set|merge|append|prepend|insert> ...", location)
        val (target, pathIndex) = parseDataTarget(tokens, 2, context, location)
        requireIndex(tokens, pathIndex + 1, "data modify <target> <path> <operation>", location)
        val path = tokens[pathIndex].text
        val mutation = tokens[pathIndex + 1].text
        val insertIndex = if (mutation == "insert") {
            requireIndex(tokens, pathIndex + 3, "data modify ... insert <index> <value|from> ...", location)
            parseInt(tokens[pathIndex + 2].text, "insert index", location)
        } else {
            null
        }
        val sourceKindIndex = when (mutation) {
            "set", "merge", "append", "prepend" -> pathIndex + 2
            "insert" -> pathIndex + 3
            else -> unsupportedFeature("Unsupported data modify operation '$mutation'", profile.id, location, command)
        }
        requireIndex(tokens, sourceKindIndex, "data modify ... $mutation <value|from> ...", location)
        val value = readDataModifyValue(command, tokens, sourceKindIndex, context, location)
        mutateDataTarget(target, location) { targetNbt ->
            when (mutation) {
                "set" -> JsonPaths.set(targetNbt, path, value)
                "merge" -> JsonPaths.merge(targetNbt, path, value)
                "append" -> JsonPaths.append(targetNbt, path, value)
                "prepend" -> JsonPaths.prepend(targetNbt, path, value)
                "insert" -> JsonPaths.insert(targetNbt, path, insertIndex ?: 0, value)
            }
        }
    }

    private fun readDataModifyValue(
        command: String,
        tokens: List<CommandToken>,
        sourceKindIndex: Int,
        context: ExecutionContext,
        location: SourceLocation?,
    ): JsonElement =
        when (tokens[sourceKindIndex].text) {
            "value" -> {
                requireIndex(tokens, sourceKindIndex + 1, "data modify ... value <value>", location)
                JsonValues.parse(CommandTokenizer.tailFrom(command, tokens[sourceKindIndex + 1]), location)
            }
            "from" -> {
                val (sourceTarget, sourcePathIndex) = parseDataTarget(tokens, sourceKindIndex + 1, context, location)
                val sourcePath = tokens.getOrNull(sourcePathIndex)?.text
                dataTargetNbtValues(sourceTarget, location).firstOrNull()?.let { JsonPaths.get(it, sourcePath)?.deepCopy() }
                    ?: throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Data modify source did not resolve", location)
            }
            "string" -> readDataModifyString(tokens, sourceKindIndex, context, location)
            else -> unsupportedFeature("Unsupported data modify source '${tokens[sourceKindIndex].text}'", profile.id, location, command)
        }

    private fun readDataModifyString(
        tokens: List<CommandToken>,
        sourceKindIndex: Int,
        context: ExecutionContext,
        location: SourceLocation?,
    ): JsonPrimitive {
        val (sourceTarget, sourcePathIndex) = parseDataTarget(tokens, sourceKindIndex + 1, context, location)
        requireIndex(tokens, sourcePathIndex, "data modify ... string <source> <path> [start] [end]", location)
        val source = dataTargetNbtValues(sourceTarget, location).firstOrNull()
            ?.let { JsonPaths.get(it, tokens[sourcePathIndex].text) }
            ?: throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Data modify string source did not resolve", location)
        if (!source.isJsonPrimitive) {
            throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Data modify string source must be a primitive value", location)
        }
        val text = source.asJsonPrimitive.asString
        val start = tokens.getOrNull(sourcePathIndex + 1)?.text?.let { parseInt(it, "string start", location) } ?: 0
        val end = tokens.getOrNull(sourcePathIndex + 2)?.text?.let { parseInt(it, "string end", location) } ?: text.length
        return JsonPrimitive(sliceString(text, start, end, location))
    }

    private fun sliceString(text: String, rawStart: Int, rawEnd: Int, location: SourceLocation?): String {
        val start = normalizeStringIndex(rawStart, text.length)
        val end = normalizeStringIndex(rawEnd, text.length)
        if (start > end) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "String slice start $rawStart is after end $rawEnd", location)
        }
        return text.substring(start, end)
    }

    private fun normalizeStringIndex(index: Int, length: Int): Int =
        (if (index < 0) length + index else index).coerceIn(0, length)

    private fun executeDataMerge(command: String, tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 4, "data merge <storage|entity|block> <target> <nbt>", location)
        val (target, valueIndex) = parseDataTarget(tokens, 2, context, location)
        requireIndex(tokens, valueIndex, "data merge <target> <nbt>", location)
        val value = JsonValues.parse(CommandTokenizer.tailFrom(command, tokens[valueIndex]), location)
        mutateDataTarget(target, location) { JsonPaths.merge(it, null, value) }
    }

    private fun executeDataGet(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext): JsonElement? {
        requireSize(tokens, 3, "data get <storage|entity|block> <target> [path]", location)
        val (target, pathIndex) = parseDataTarget(tokens, 2, context, location)
        val path = tokens.getOrNull(pathIndex)?.text
        return dataTargetNbtValues(target, location).firstOrNull()?.let { JsonPaths.get(it, path) }
    }

    private fun executeDataRemove(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 4, "data remove <storage|entity|block> <target> <path>", location)
        val (target, pathIndex) = parseDataTarget(tokens, 2, context, location)
        requireIndex(tokens, pathIndex, "data remove <target> <path>", location)
        mutateDataTarget(target, location) { JsonPaths.remove(it, tokens[pathIndex].text) }
    }

    private fun parseDataTarget(tokens: List<CommandToken>, index: Int, context: ExecutionContext, location: SourceLocation?): Pair<DataTargetSpec, Int> {
        requireIndex(tokens, index, "data target", location)
        return when (tokens[index].text) {
            "storage" -> {
                requireIndex(tokens, index + 1, "data storage <id>", location)
                DataTargetSpec.Storage(ResourceLocation.parse(tokens[index + 1].text)) to index + 2
            }
            "entity" -> {
                requireIndex(tokens, index + 1, "data entity <target>", location)
                val selected = EntitySelectors.select(world, tokens[index + 1].text, context, location)
                DataTargetSpec.Entities(selected) to index + 2
            }
            "block" -> {
                requireSizeFrom(tokens, index, 4, "data block <x> <y> <z>", location)
                DataTargetSpec.Block(parseBlockPos(tokens, index + 1, context.position, location)) to index + 4
            }
            else -> unsupportedFeature("Unsupported data target '${tokens[index].text}'", profile.id, location)
        }
    }

    private fun dataTargetNbtValues(target: DataTargetSpec, location: SourceLocation?): List<JsonObject> =
        when (target) {
            is DataTargetSpec.Storage -> listOf(world.storage(target.id))
            is DataTargetSpec.Entities -> target.entities.map { it.fullNbt(profile, location) }
            is DataTargetSpec.Block -> listOf(world.requireBlock(target.pos).fullNbt(target.pos, profile, location))
        }

    private fun mutateDataTarget(target: DataTargetSpec, location: SourceLocation?, mutation: (JsonObject) -> Unit) {
        when (target) {
            is DataTargetSpec.Storage -> mutation(world.storage(target.id))
            is DataTargetSpec.Block -> {
                val block = world.requireBlock(target.pos)
                val updated = block.fullNbt(target.pos, profile, location)
                mutation(updated)
                block.writeFullNbt(target.pos, profile, updated, location)
            }
            is DataTargetSpec.Entities -> target.entities.forEach { entity ->
                if (entity is SandboxPlayer) {
                    throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Player NBT is read-only in this sandbox; use player events or movement commands", location)
                }
                val updated = entity.fullNbt(profile, location)
                mutation(updated)
                entity.writeFullNbt(profile, updated, location)
            }
        }
    }

    private fun executeClear(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        val targets = tokens.getOrNull(1)?.text?.let { resolvePlayers(it, location, context) } ?: world.players.values.toList()
        val item = tokens.getOrNull(2)?.text?.let(ResourceLocation::parse)
        val maxCount = tokens.getOrNull(3)?.text?.let { parseInt(it, "clear max count", location) } ?: Int.MAX_VALUE
        targets.forEach { player ->
            var remaining = maxCount
            val iterator = player.inventory.listIterator()
            while (iterator.hasNext() && remaining > 0) {
                val stack = iterator.next()
                if (item == null || stack.id == item) {
                    val removed = minOf(stack.count, remaining)
                    stack.count -= removed
                    remaining -= removed
                    if (stack.count <= 0) iterator.remove()
                }
            }
        }
    }

    private fun executeGive(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 3, "give <targets> <item> [count]", location)
        val item = ResourceLocation.parse(tokens[2].text)
        val count = tokens.getOrNull(3)?.text?.let { parseInt(it, "item count", location) } ?: 1
        resolvePlayers(tokens[1].text, location, context).forEach { player ->
            player.inventory += ItemStack(item, count.coerceAtLeast(0))
            advancements.handle(PlayerEvent(player.name, "inventory_changed", item = ItemStack(item, count)))
        }
    }

    private fun executeEffect(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 2, "effect <give|clear> ...", location)
        when (tokens[1].text) {
            "give" -> {
                requireSize(tokens, 4, "effect give <targets> <effect> [seconds] [amplifier] [hideParticles]", location)
                val effect = ResourceLocation.parse(tokens[3].text)
                val duration = tokens.getOrNull(4)?.text?.let { parseInt(it, "effect seconds", location) * 20 } ?: 600
                val amplifier = tokens.getOrNull(5)?.text?.let { parseInt(it, "effect amplifier", location) } ?: 0
                val hide = tokens.getOrNull(6)?.text?.let { parseBoolean(it, "hide particles", location) } ?: false
                resolvePlayers(tokens[2].text, location, context).forEach { player ->
                    player.effects += effect
                    player.effectDetails[effect] = PlayerEffect(effect, duration, amplifier, hide)
                    advancements.handle(PlayerEvent(player.name, "effects_changed"))
                }
            }
            "clear" -> {
                requireSize(tokens, 3, "effect clear <targets> [effect]", location)
                val effect = tokens.getOrNull(3)?.text?.let(ResourceLocation::parse)
                resolvePlayers(tokens[2].text, location, context).forEach { player ->
                    if (effect == null) {
                        player.effects.clear()
                        player.effectDetails.clear()
                    } else {
                        player.effects -= effect
                        player.effectDetails.remove(effect)
                    }
                    advancements.handle(PlayerEvent(player.name, "effects_changed"))
                }
            }
            else -> unsupportedFeature("Unsupported effect action '${tokens[1].text}'", profile.id, location)
        }
    }

    private fun executeEnchant(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 3, "enchant <targets> <enchantment> [level]", location)
        val enchantment = ResourceLocation.parse(tokens[2].text)
        val level = tokens.getOrNull(3)?.text?.let { parseInt(it, "enchantment level", location) } ?: 1
        resolvePlayers(tokens[1].text, location, context).forEach { player ->
            val item = player.selectedItem ?: ItemStack(ResourceLocation("minecraft", "air")).also { player.inventory += it }
            val enchantments = item.components.getAsJsonObject("minecraft:enchantments") ?: JsonObject().also {
                item.components.add("minecraft:enchantments", it)
            }
            enchantments.addProperty(enchantment.toString(), level)
        }
    }

    private fun executeExperience(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 2, "${tokens[0].text} <add|set|query> ...", location)
        when (tokens[1].text) {
            "add" -> {
                requireSize(tokens, 4, "${tokens[0].text} add <targets> <amount> [points|levels]", location)
                val amount = parseInt(tokens[3].text, "experience amount", location)
                resolvePlayers(tokens[2].text, location, context).forEach { it.xp += amount }
            }
            "set" -> {
                requireSize(tokens, 4, "${tokens[0].text} set <targets> <amount> [points|levels]", location)
                val amount = parseInt(tokens[3].text, "experience amount", location)
                resolvePlayers(tokens[2].text, location, context).forEach { it.xp = amount }
            }
            "query" -> {
                requireSize(tokens, 4, "${tokens[0].text} query <target> <points|levels>", location)
                val player = resolvePlayers(tokens[2].text, location, context).first()
                world.recordOutput("${tokens[0].text} query", "data", text = player.xp.toString())
            }
            else -> unsupportedFeature("Unsupported ${tokens[0].text} action '${tokens[1].text}'", profile.id, location)
        }
    }

    private fun executeRecipe(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 4, "recipe <give|take> <targets> <recipe|*>", location)
        val targets = resolvePlayers(tokens[2].text, location, context)
        val recipe = tokens[3].text
        targets.forEach { player ->
            when (tokens[1].text) {
                "give" -> if (recipe != "*") player.recipes += ResourceLocation.parse(recipe)
                "take" -> if (recipe == "*") player.recipes.clear() else player.recipes -= ResourceLocation.parse(recipe)
                else -> unsupportedFeature("Unsupported recipe action '${tokens[1].text}'", profile.id, location)
            }
            advancements.handle(PlayerEvent(player.name, "recipe_unlocked", recipe = recipe.takeIf { it != "*" }?.let(ResourceLocation::parse)))
        }
    }

    private fun executeForceload(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 2, "forceload <add|remove|query> ...", location)
        when (tokens[1].text) {
            "add", "remove" -> {
                if (tokens[1].text == "remove" && tokens.getOrNull(2)?.text == "all") {
                    world.forcedChunks.clear()
                    return
                }
                requireSize(tokens, 4, "forceload ${tokens[1].text} <from> [to]", location)
                val from = parseColumnPos(tokens, 2, context.position, location)
                val to = if (tokens.size >= 6) parseColumnPos(tokens, 4, context.position, location) else from
                chunksInBlockRange(from, to).forEach { chunk ->
                    if (tokens[1].text == "add") world.forcedChunks += chunk else world.forcedChunks -= chunk
                }
            }
            "query" -> {
                val payload = JsonArray()
                world.forcedChunks.sorted().forEach { chunk ->
                    payload.add(JsonObject().also {
                        it.addProperty("x", chunk.x)
                        it.addProperty("z", chunk.z)
                    })
                }
                world.recordOutput("forceload query", "data", text = world.forcedChunks.sorted().joinToString { "${it.x},${it.z}" }, payload = payload)
            }
            else -> unsupportedFeature("Unsupported forceload action '${tokens[1].text}'", profile.id, location)
        }
    }

    private fun executeFillBiome(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 8, "fillbiome <from> <to> <biome> [replace <filter>]", location)
        val from = parseBlockPos(tokens, 1, context.position, location)
        val to = parseBlockPos(tokens, 4, context.position, location)
        val biome = ResourceLocation.parse(tokens[7].text)
        val filter = if (tokens.getOrNull(8)?.text == "replace") tokens.getOrNull(9)?.text?.let(ResourceLocation::parse) else null
        val xs = minOf(from.x, to.x)..maxOf(from.x, to.x)
        val ys = minOf(from.y, to.y)..maxOf(from.y, to.y)
        val zs = minOf(from.z, to.z)..maxOf(from.z, to.z)
        val volume = xs.count() * ys.count() * zs.count()
        if (volume > 32768) {
            throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Fillbiome volume $volume exceeds sandbox limit 32768", location)
        }
        var changed = 0
        for (x in xs) for (y in ys) for (z in zs) {
            val pos = BlockPos(x, y, z)
            if (filter == null || world.biomes[pos] == filter) {
                world.biomes[pos] = biome
                changed += 1
            }
        }
        world.recordOutput("fillbiome", "data", text = changed.toString())
    }

    private fun executeDamage(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 3, "damage <target> <amount> [damageType] ...", location)
        val amount = tokens[2].text.toDoubleOrNull() ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid damage amount '${tokens[2].text}'", location)
        val damageSource = tokens.getOrNull(3)
            ?.text
            ?.takeUnless { it in setOf("at", "by", "from") }
            ?.let(ResourceLocation::parse)
            ?: ResourceLocation("minecraft", "generic")
        val sourceEntity = damageCommandSourceEntity(tokens, context, location)
        val targets = EntitySelectors.select(world, tokens[1].text, context, location)
        targets.forEach { entity ->
            val beforeHealth = if (entity is SandboxPlayer) entity.health else entity.fullNbt(profile, location).get("Health")?.asDouble ?: 20.0
            val afterHealth = (beforeHealth - amount).coerceAtLeast(0.0)
            if (entity is SandboxPlayer) {
                entity.health = afterHealth
                advancements.handle(
                    PlayerEvent(
                        entity.name,
                        "damage",
                        entity = sourceEntity,
                        damageAmount = amount,
                        damageSource = damageSource,
                    ),
                )
                if (beforeHealth > 0.0 && afterHealth <= 0.0) {
                    advancements.handle(
                        PlayerEvent(
                            entity.name,
                            "death",
                            entity = sourceEntity,
                            damageAmount = amount,
                            damageSource = damageSource,
                        ),
                    )
                    if (sourceEntity != null) {
                        advancements.handle(
                            PlayerEvent(
                                entity.name,
                                "entity_killed_player",
                                entity = sourceEntity,
                                damageAmount = amount,
                                damageSource = damageSource,
                            ),
                        )
                    }
                }
            } else {
                val updated = entity.fullNbt(profile, location)
                updated.addProperty("Health", afterHealth)
                entity.writeFullNbt(profile, updated, location)
                if (sourceEntity is SandboxPlayer) {
                    advancements.handle(
                        PlayerEvent(
                            sourceEntity.name,
                            "player_hurt_entity",
                            entity = entity,
                            damageAmount = amount,
                            damageSource = damageSource,
                        ),
                    )
                    if (beforeHealth > 0.0 && afterHealth <= 0.0) {
                        advancements.handle(
                            PlayerEvent(
                                sourceEntity.name,
                                "killed_entity",
                                entity = entity,
                                damageAmount = amount,
                                damageSource = damageSource,
                            ),
                        )
                    }
                }
            }
        }
        world.entities.removeIf { entity ->
            when (entity) {
                is SandboxPlayer -> entity.health <= 0.0
                else -> entity.fullNbt(profile, location).get("Health")?.asDouble == 0.0
            }
        }
    }

    private fun damageCommandSourceEntity(tokens: List<CommandToken>, context: ExecutionContext, location: SourceLocation?): SandboxEntity? {
        val markerIndex = tokens.indexOfFirst { it.text == "by" || it.text == "from" }
        if (markerIndex < 0) return null
        val selector = tokens.getOrNull(markerIndex + 1)
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "damage ${tokens[markerIndex].text} requires an entity selector", location)
        return EntitySelectors.select(world, selector.text, context, location).firstOrNull()
            ?: throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Damage source '${selector.text}' did not match an entity", location)
    }

    private fun executeRide(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 3, "ride <target> <mount|dismount> ...", location)
        val riders = EntitySelectors.select(world, tokens[1].text, context, location)
        when (tokens[2].text) {
            "mount" -> {
                requireSize(tokens, 4, "ride <target> mount <vehicle>", location)
                val vehicle = EntitySelectors.select(world, tokens[3].text, context, location).firstOrNull()
                    ?: throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Ride vehicle '${tokens[3].text}' did not match an entity", location)
                riders.forEach { rider ->
                    rider.vehicle = vehicle.uuid
                    vehicle.passengers += rider.uuid
                }
            }
            "dismount" -> riders.forEach { rider ->
                world.entities.firstOrNull { it.uuid == rider.vehicle }?.passengers?.remove(rider.uuid)
                rider.vehicle = null
            }
            else -> unsupportedFeature("Unsupported ride action '${tokens[2].text}'", profile.id, location)
        }
    }

    private fun executeRotate(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 4, "rotate <targets> <yaw> <pitch>", location)
        val yaw = tokens[2].text.toDoubleOrNull() ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid yaw '${tokens[2].text}'", location)
        val pitch = tokens[3].text.toDoubleOrNull() ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid pitch '${tokens[3].text}'", location)
        EntitySelectors.select(world, tokens[1].text, context, location).forEach {
            it.yaw = yaw
            it.pitch = pitch
        }
    }

    private fun executeItem(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 2, "item <replace|modify> ...", location)
        when (tokens[1].text) {
            "replace" -> executeItemReplace(tokens, location, context)
            "modify" -> executeItemModify(tokens, location, context)
            else -> unsupportedFeature("Unsupported item action '${tokens[1].text}'", profile.id, location)
        }
    }

    private fun executeItemModify(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 3, "item modify <entity|block> ...", location)
        when (tokens[2].text) {
            "entity" -> {
                requireSize(tokens, 6, "item modify entity <targets> <slot> <modifier>", location)
                val slot = inventorySlot(tokens[4].text)
                val modifier = datapack.itemModifier(ResourceLocation.parse(tokens[5].text))
                resolvePlayers(tokens[3].text, location, context).forEach { player ->
                    val item = player.inventory.getOrNull(slot) ?: return@forEach
                    if (item.id == ResourceLocation("minecraft", "air") || item.count <= 0) return@forEach
                    player.inventory[slot] = applyItemModifier(item, modifier.root, { stack -> itemModifierPredicateContext(player, stack) }, location)
                    advancements.handle(PlayerEvent(player.name, "inventory_changed", item = player.inventory[slot]))
                }
            }
            "block" -> {
                requireSize(tokens, 8, "item modify block <pos> <slot> <modifier>", location)
                val pos = parseBlockPos(tokens, 3, context.position, location)
                val slot = inventorySlot(tokens[6].text)
                val modifier = datapack.itemModifier(ResourceLocation.parse(tokens[7].text))
                val item = blockItem(pos, slot, location) ?: return
                if (item.id == ResourceLocation("minecraft", "air") || item.count <= 0) return
                replaceBlockItem(pos, slot, applyItemModifier(item, modifier.root, { stack -> itemModifierPredicateContext(pos, stack) }, location), location)
            }
            else -> unsupportedFeature("Unsupported item modify target '${tokens[2].text}'", profile.id, location)
        }
    }

    private fun executeItemReplace(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 3, "item replace <entity|block> ...", location)
        when (tokens[2].text) {
            "entity" -> {
                requireSize(tokens, 7, "item replace entity <targets> <slot> with <item> [count]", location)
                if (tokens[5].text != "with") {
                    unsupportedFeature("Expected 'with' in item replace entity", profile.id, location)
                }
                val slot = inventorySlot(tokens[4].text)
                val item = ItemStack(ResourceLocation.parse(tokens[6].text), tokens.getOrNull(7)?.text?.let { parseInt(it, "item count", location) } ?: 1)
                resolvePlayers(tokens[3].text, location, context).forEach { player ->
                    while (player.inventory.size <= slot) player.inventory += ItemStack(ResourceLocation("minecraft", "air"), 0)
                    player.inventory[slot] = item.copy(components = item.components.deepCopy(), nbt = item.nbt.deepCopy())
                }
            }
            "block" -> {
                requireSize(tokens, 9, "item replace block <pos> <slot> with <item> [count]", location)
                if (tokens[7].text != "with") {
                    unsupportedFeature("Expected 'with' in item replace block", profile.id, location)
                }
                val pos = parseBlockPos(tokens, 3, context.position, location)
                val slot = inventorySlot(tokens[6].text)
                val item = ItemStack(ResourceLocation.parse(tokens[8].text), tokens.getOrNull(9)?.text?.let { parseInt(it, "item count", location) } ?: 1)
                replaceBlockItem(pos, slot, item, location)
            }
            else -> unsupportedFeature("Unsupported item replace target '${tokens[2].text}'", profile.id, location)
        }
    }

    private fun blockItem(pos: BlockPos, slot: Int, location: SourceLocation?): ItemStack? {
        val block = world.requireBlock(pos)
        val items = block.fullNbt(pos, profile, location).getAsJsonArray("Items") ?: return null
        val itemJson = items.firstOrNull {
            it.isJsonObject && it.asJsonObject.get("Slot")?.asInt == slot
        }?.asJsonObject ?: return null
        return itemStackFromJson(itemJson)
    }

    private fun itemStackFromJson(json: JsonObject): ItemStack? {
        val id = json.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val count = (json.get("count") ?: json.get("Count"))?.takeIf { it.isJsonPrimitive }?.asInt ?: 1
        val components = json.getAsJsonObject("components")?.deepCopy() ?: JsonObject()
        val nbt = (json.getAsJsonObject("nbt")
            ?: json.getAsJsonObject("tag")
            ?: components.getAsJsonObject("minecraft:custom_data"))?.deepCopy() ?: JsonObject()
        return ItemStack(ResourceLocation.parse(id), count, components, nbt)
    }

    private fun applyItemModifier(
        item: ItemStack,
        root: JsonElement,
        predicateContext: (ItemStack) -> PredicateContext,
        location: SourceLocation?,
    ): ItemStack {
        var stack = item.copy(components = item.components.deepCopy(), nbt = item.nbt.deepCopy())
        val functions = when {
            root.isJsonArray -> root.asJsonArray.toList()
            root.isJsonObject -> listOf(root)
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Item modifier must be an object or array", location)
        }
        functions.forEach { element ->
            if (!element.isJsonObject) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Item modifier entries must be objects", location)
            }
            val function = element.asJsonObject
            val conditions = function.get("conditions")
            if (conditions != null && !predicates.testConditions(conditions, predicateContext(stack))) {
                return@forEach
            }
            when (val type = itemModifierType(function, location)) {
                "set_components" -> {
                    function.getAsJsonObject("components")?.entrySet()?.forEach { (key, value) ->
                        stack.components.add(key, value.deepCopy())
                    }
                }
                "set_custom_data", "set_nbt" -> {
                    val tag = function.get("tag") ?: function.get("nbt")
                    val parsed = when {
                        tag == null -> JsonObject()
                        tag.isJsonPrimitive && tag.asJsonPrimitive.isString -> JsonValues.parse(tag.asString, location)
                        else -> tag
                    }
                    if (!parsed.isJsonObject) {
                        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Item modifier custom data must be an object", location)
                    }
                    JsonPaths.merge(stack.nbt, null, parsed)
                }
                "set_count" -> stack = stack.copy(count = itemModifierCount(function.get("count"), stack.count).coerceAtLeast(0))
                else -> unsupportedFeature("Item modifier function '$type' is not implemented", profile.id, location)
            }
        }
        return stack
    }

    private fun itemModifierPredicateContext(pos: BlockPos, stack: ItemStack): PredicateContext =
        PredicateContext(
            world = world,
            origin = Position(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble()),
            dimension = ResourceLocation("minecraft", "overworld"),
            tool = stack,
            block = world.block(pos)?.id,
            weather = currentWeatherState(),
        )

    private fun itemModifierPredicateContext(player: SandboxPlayer, stack: ItemStack): PredicateContext =
        PredicateContext(
            world = world,
            player = player,
            thisEntity = player,
            origin = player.position,
            dimension = player.dimension,
            tool = stack,
            weather = currentWeatherState(),
        )

    private fun itemModifierType(function: JsonObject, location: SourceLocation?): String {
        val raw = function.get("function")?.takeIf { it.isJsonPrimitive }?.asString
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Item modifier function is missing", location)
        return ResourceLocation.parse(raw).path
    }

    private fun itemModifierCount(element: JsonElement?, fallback: Int): Int =
        when {
            element == null || element.isJsonNull -> fallback
            element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> element.asInt
            element.isJsonObject -> element.asJsonObject.get("min")?.asInt ?: element.asJsonObject.get("base")?.asInt ?: fallback
            else -> fallback
        }

    private fun executeTag(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 3, "tag <targets> <add|remove|list> [name]", location)
        val entities = EntitySelectors.select(world, tokens[1].text, context, location)
        when (tokens[2].text) {
            "add" -> {
                requireSize(tokens, 4, "tag <targets> add <name>", location)
                entities.forEach { it.tags += tokens[3].text }
            }
            "remove" -> {
                requireSize(tokens, 4, "tag <targets> remove <name>", location)
                entities.forEach { it.tags -= tokens[3].text }
            }
            "list" -> Unit
            else -> unsupportedFeature("Unsupported tag action '${tokens[2].text}'", profile.id, location)
        }
    }

    private fun executeSummon(command: String, tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 2, "summon <entity> [x y z] [nbt]", location)
        val type = ResourceLocation.parse(tokens[1].text)
        var position = context.position
        var nbtStartIndex = 2
        if (isCoordinateTriple(tokens, 2)) {
            position = parsePosition(tokens, 2, context.position, location)
            nbtStartIndex = 5
        }
        val nbt = if (tokens.size > nbtStartIndex) parseSummonNbt(CommandTokenizer.tailFrom(command, tokens[nbtStartIndex]), location) else JsonObject()
        val tags = extractTags(nbt).toMutableSet()
        val entity = SandboxEntity(type = type, position = position, tags = tags, nbt = nbt)
        entity.fullNbt(profile, location)
        world.entities += entity
    }

    private fun executeKill(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        val targetToken = tokens.getOrNull(1)?.text ?: "@s"
        val selected = EntitySelectors.select(world, targetToken, context, location).toSet()
        (context.entity as? SandboxPlayer)?.let { player ->
            selected.filterNot { it is SandboxPlayer }.forEach { target ->
                advancements.handle(PlayerEvent(player.name, "killed_entity", entity = target))
            }
        }
        world.entities.removeIf { it in selected }
    }

    private fun executeTeleport(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 2, "${tokens[0].text} <targets> <location>|<destination>", location)
        when {
            tokens.size >= 4 && isCoordinateTriple(tokens, 1) -> {
                val entity = context.entity ?: throw SandboxException(DiagnosticCode.COMMAND_ERROR, "${tokens[0].text} <location> requires an execution entity", location)
                moveEntities(listOf(entity), parsePosition(tokens, 1, entity.position, location))
            }
            tokens.size >= 5 && isCoordinateTriple(tokens, 2) -> {
                val targets = EntitySelectors.select(world, tokens[1].text, context, location)
                moveEntities(targets, parsePosition(tokens, 2, context.position, location))
            }
            tokens.size >= 3 -> {
                val targets = EntitySelectors.select(world, tokens[1].text, context, location)
                val destination = EntitySelectors.select(world, tokens[2].text, context, location).firstOrNull()
                    ?: throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Teleport destination '${tokens[2].text}' did not match an entity", location)
                moveEntities(targets, destination.position)
            }
            else -> unsupportedFeature("Unsupported ${tokens[0].text} form", profile.id, location)
        }
    }

    private fun moveEntities(entities: List<SandboxEntity>, position: Position) {
        entities.forEach { entity ->
            entity.position = position
            if (entity is SandboxPlayer) {
                advancements.handle(PlayerEvent(entity.name, "moved"))
            }
        }
    }

    private fun executeSetBlock(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 5, "setblock <pos> <block> [destroy|keep|replace]", location)
        val pos = parseBlockPos(tokens, 1, context.position, location)
        val block = parseBlockArgument(tokens[4].text, location)
        when (val mode = tokens.getOrNull(5)?.text ?: "replace") {
            "replace", "destroy", "strict" -> world.setBlock(pos, block.toBlock(pos, profile, location))
            "keep" -> if (world.block(pos) == null) world.setBlock(pos, block.toBlock(pos, profile, location))
            else -> unsupportedFeature("Unsupported setblock mode '$mode'", profile.id, location)
        }
    }

    private fun executeClone(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 10, "clone <begin> <end> <destination> [replace|masked|filtered] [force|move|normal]", location)
        val from = parseBlockPos(tokens, 1, context.position, location)
        val to = parseBlockPos(tokens, 4, context.position, location)
        val dest = parseBlockPos(tokens, 7, context.position, location)
        val maskMode = tokens.getOrNull(10)?.text ?: "replace"
        val cloneMode = tokens.getOrNull(11)?.text ?: "normal"
        val xs = minOf(from.x, to.x)..maxOf(from.x, to.x)
        val ys = minOf(from.y, to.y)..maxOf(from.y, to.y)
        val zs = minOf(from.z, to.z)..maxOf(from.z, to.z)
        val copied = mutableListOf<Pair<BlockPos, SandboxBlock>>()
        for ((dx, x) in xs.withIndex()) for ((dy, y) in ys.withIndex()) for ((dz, z) in zs.withIndex()) {
            val sourcePos = BlockPos(x, y, z)
            val source = world.block(sourcePos)
            if (source == null && maskMode == "masked") continue
            source?.let { copied += BlockPos(dest.x + dx, dest.y + dy, dest.z + dz) to it.copyForClone() }
        }
        copied.forEach { (pos, block) -> world.setBlock(pos, block) }
        if (cloneMode == "move") {
            for (x in xs) for (y in ys) for (z in zs) world.setBlock(BlockPos(x, y, z), null)
        } else if (cloneMode !in setOf("normal", "force")) {
            unsupportedFeature("Unsupported clone mode '$cloneMode'", profile.id, location)
        }
    }

    private fun executeFill(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 8, "fill <from> <to> <block> [replace|keep|destroy|hollow|outline]", location)
        val from = parseBlockPos(tokens, 1, context.position, location)
        val to = parseBlockPos(tokens, 4, context.position, location)
        val block = parseBlockArgument(tokens[7].text, location)
        val mode = tokens.getOrNull(8)?.text ?: "replace"
        if (mode !in setOf("replace", "keep", "destroy", "hollow", "outline")) {
            unsupportedFeature("Unsupported fill mode '$mode'", profile.id, location)
        }
        val xs = minOf(from.x, to.x)..maxOf(from.x, to.x)
        val ys = minOf(from.y, to.y)..maxOf(from.y, to.y)
        val zs = minOf(from.z, to.z)..maxOf(from.z, to.z)
        val volume = xs.count() * ys.count() * zs.count()
        if (volume > 32768) {
            throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Fill volume $volume exceeds sandbox limit 32768", location)
        }
        for (x in xs) for (y in ys) for (z in zs) {
            val pos = BlockPos(x, y, z)
            val boundary = x == xs.first || x == xs.last || y == ys.first || y == ys.last || z == zs.first || z == zs.last
            when {
                mode == "keep" && world.block(pos) != null -> Unit
                mode == "outline" && !boundary -> Unit
                mode == "hollow" && !boundary -> world.setBlock(pos, null)
                else -> world.setBlock(pos, block.toBlock(pos, profile, location))
            }
        }
    }

    private fun executeTellraw(command: String, tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 3, "tellraw <targets> <message>", location)
        val targets = resolvePlayers(tokens[1].text, location, context)
        val payload = TextComponents.parse(CommandTokenizer.tailFrom(command, tokens[2]), location)
        if (targets.isEmpty()) {
            val rendered = TextComponents.resolve(payload, world, context, null, location)
            world.recordOutput("tellraw", "chat", emptyList(), rendered.plain, payload, rendered.segments)
        } else {
            targets.forEach { target ->
                val rendered = TextComponents.resolve(payload, world, context, target, location)
                world.recordOutput("tellraw", "chat", listOf(target.name), rendered.plain, payload, rendered.segments)
            }
        }
    }

    private fun executeTitle(command: String, tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 3, "title <targets> <clear|reset|title|subtitle|actionbar|times> ...", location)
        val targets = resolvePlayers(tokens[1].text, location, context).map { it.name }
        when (val action = tokens[2].text) {
            "clear", "reset" -> world.recordOutput("title $action", "title", targets, action)
            "times" -> {
                requireSize(tokens, 6, "title <targets> times <fadeIn> <stay> <fadeOut>", location)
                val payload = JsonObject()
                payload.addProperty("fadeIn", parseInt(tokens[3].text, "title fadeIn", location))
                payload.addProperty("stay", parseInt(tokens[4].text, "title stay", location))
                payload.addProperty("fadeOut", parseInt(tokens[5].text, "title fadeOut", location))
                world.recordOutput("title times", "title", targets, "${tokens[3].text} ${tokens[4].text} ${tokens[5].text}", payload)
            }
            "title", "subtitle", "actionbar" -> {
                requireSize(tokens, 4, "title <targets> $action <title>", location)
                val payload = TextComponents.parse(CommandTokenizer.tailFrom(command, tokens[3]), location)
                if (targets.isEmpty()) {
                    val rendered = TextComponents.resolve(payload, world, context, null, location)
                    world.recordOutput("title $action", "title", emptyList(), rendered.plain, payload, rendered.segments)
                } else {
                    targets.forEach { target ->
                        val player = world.requirePlayer(target)
                        val rendered = TextComponents.resolve(payload, world, context, player, location)
                        world.recordOutput("title $action", "title", listOf(target), rendered.plain, payload, rendered.segments)
                    }
                }
            }
            else -> unsupportedFeature("Unsupported title action '$action'", profile.id, location, command)
        }
    }

    private fun executeSay(command: String, tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 2, "say <message>", location)
        val sender = outputSender(context)
        val text = CommandTokenizer.tailFrom(command, tokens[1])
        world.recordOutput("say", "chat", world.players.keys.toList(), "<$sender> $text", JsonObject().also {
            it.addProperty("sender", sender)
            it.addProperty("message", text)
        })
    }

    private fun executeMe(command: String, tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 2, "me <action>", location)
        val sender = outputSender(context)
        val text = CommandTokenizer.tailFrom(command, tokens[1])
        world.recordOutput("me", "chat", world.players.keys.toList(), "* $sender $text", JsonObject().also {
            it.addProperty("sender", sender)
            it.addProperty("message", text)
        })
    }

    private fun executePrivateMessage(command: String, tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 3, "${tokens[0].text} <targets> <message>", location)
        val targets = resolvePlayers(tokens[1].text, location, context).map { it.name }
        val sender = outputSender(context)
        val text = CommandTokenizer.tailFrom(command, tokens[2])
        world.recordOutput(tokens[0].text, "chat", targets, "[$sender -> ${targets.joinToString()}] $text", JsonObject().also {
            it.addProperty("sender", sender)
            it.addProperty("message", text)
        })
    }

    private fun executeTeamMessage(command: String, tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 2, "${tokens[0].text} <message>", location)
        val sender = outputSender(context)
        val text = CommandTokenizer.tailFrom(command, tokens[1])
        world.recordOutput(tokens[0].text, "chat", world.players.keys.toList(), "[team] <$sender> $text", JsonObject().also {
            it.addProperty("sender", sender)
            it.addProperty("message", text)
        })
    }

    private fun executePlaySound(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 4, "playsound <sound> <source> <targets> [pos] [volume] [pitch] [minVolume]", location)
        val targets = resolvePlayers(tokens[3].text, location, context).map { it.name }
        val payload = JsonObject()
        payload.addProperty("sound", ResourceLocation.parse(tokens[1].text).toString())
        payload.addProperty("source", tokens[2].text)
        world.recordOutput("playsound", "sound", targets, "${tokens[2].text}:${tokens[1].text}", payload)
    }

    private fun executeStopSound(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 2, "stopsound <targets> [source] [sound]", location)
        val targets = resolvePlayers(tokens[1].text, location, context).map { it.name }
        val payload = JsonObject()
        tokens.getOrNull(2)?.text?.let { payload.addProperty("source", it) }
        tokens.getOrNull(3)?.text?.let { payload.addProperty("sound", ResourceLocation.parse(it).toString()) }
        world.recordOutput("stopsound", "sound", targets, "stop sound", payload)
    }

    private fun executeParticle(command: String, tokens: List<CommandToken>, location: SourceLocation?) {
        requireSize(tokens, 2, "particle <name> [pos] [delta] [speed] [count] [force|normal] [viewers]", location)
        val payload = JsonObject()
        payload.addProperty("particle", ResourceLocation.parse(tokens[1].text).toString())
        payload.addProperty("arguments", CommandTokenizer.tailAfter(command, tokens[1]))
        world.recordOutput("particle", "visual", emptyList(), tokens[1].text, payload)
    }

    private fun executeSchedule(tokens: List<CommandToken>, location: SourceLocation?) {
        requireSize(tokens, 2, "schedule <function|clear> ...", location)
        if (tokens[1].text == "clear") {
            requireSize(tokens, 3, "schedule clear <id>", location)
            val id = ResourceLocation.parse(tokens[2].text)
            val removed = world.scheduledFunctions.count { it.id == id }
            world.scheduledFunctions.removeIf { it.id == id }
            world.recordOutput("schedule clear", "data", text = removed.toString())
            return
        }
        requireSize(tokens, 4, "schedule function <id> <time> [append|replace]", location)
        if (tokens[1].text != "function") {
            unsupportedFeature("Only 'schedule function' and 'schedule clear' are implemented", profile.id, location)
        }
        val id = ResourceLocation.parse(tokens[2].text)
        val delay = parseTime(tokens[3].text, location)
        val mode = tokens.getOrNull(4)?.text ?: "replace"
        if (mode == "replace") {
            world.scheduledFunctions.removeIf { it.id == id }
        } else if (mode != "append") {
            unsupportedFeature("Unsupported schedule mode '$mode'", profile.id, location)
        }
        world.scheduledFunctions += ScheduledFunction(id, world.gameTime + delay)
    }

    private fun executeSetWorldSpawn(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        val position = if (tokens.size >= 4) parsePosition(tokens, 1, context.position, location) else context.position
        val angle = tokens.getOrNull(if (tokens.size >= 4) 4 else 1)?.text?.let { parseDouble(it, "spawn angle", location) }
        world.worldSpawn = SpawnPoint(position = position, dimension = ResourceLocation("minecraft", "overworld"), angle = angle)
    }

    private fun executeSpawnPoint(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        val targetToken = tokens.getOrNull(1)?.text
        val targets = if (targetToken != null && !isCoordinateToken(targetToken)) {
            resolvePlayers(targetToken, location, context)
        } else {
            listOf(context.entity as? SandboxPlayer ?: world.players.values.firstOrNull()
                ?: throw SandboxException(DiagnosticCode.COMMAND_ERROR, "spawnpoint requires a target player", location))
        }
        val posIndex = if (targetToken != null && !isCoordinateToken(targetToken)) 2 else 1
        val position = if (tokens.size >= posIndex + 3) parsePosition(tokens, posIndex, context.position, location) else context.position
        val angle = tokens.getOrNull(posIndex + 3)?.text?.let { parseDouble(it, "spawn angle", location) }
        targets.forEach {
            it.spawnPoint = SpawnPoint(position = position, dimension = it.dimension, angle = angle)
        }
    }

    private fun executeTick(tokens: List<CommandToken>, location: SourceLocation?) {
        requireSize(tokens, 2, "tick <query|rate|freeze|unfreeze|step|sprint|stop>", location)
        when (tokens[1].text) {
            "query" -> {
                val payload = JsonObject()
                payload.addProperty("rate", world.tickRate)
                payload.addProperty("frozen", world.tickFrozen)
                payload.addProperty("gameTime", world.gameTime)
                world.recordOutput("tick query", "data", text = "rate=${world.tickRate}, frozen=${world.tickFrozen}, gameTime=${world.gameTime}", payload = payload)
            }
            "rate" -> {
                requireSize(tokens, 3, "tick rate <rate>", location)
                world.tickRate = parseDouble(tokens[2].text, "tick rate", location).coerceAtLeast(1.0)
            }
            "freeze" -> world.tickFrozen = true
            "unfreeze" -> world.tickFrozen = false
            "step", "sprint" -> {
                val ticks = tokens.getOrNull(2)?.text?.let { parseTime(it, location).toInt() } ?: 1
                runTicks(ticks)
            }
            "stop" -> world.recordOutput("tick stop", "data", text = "No active tick sprint")
            else -> unsupportedFeature("Unsupported tick action '${tokens[1].text}'", profile.id, location)
        }
    }

    private fun executeTrigger(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 2, "trigger <objective> [add|set] [value]", location)
        val player = context.entity as? SandboxPlayer ?: world.players.values.firstOrNull()
            ?: throw SandboxException(DiagnosticCode.COMMAND_ERROR, "trigger requires a player", location)
        val objective = tokens[1].text
        world.ensureObjective(objective)
        val action = tokens.getOrNull(2)?.text ?: "add"
        val value = tokens.getOrNull(3)?.text?.let { parseInt(it, "trigger value", location) } ?: 1
        when (action) {
            "add" -> world.addScore(player.name, objective, value)
            "set" -> world.setScore(player.name, objective, value)
            else -> unsupportedFeature("Unsupported trigger action '$action'", profile.id, location)
        }
    }

    private fun executeWorldBorder(tokens: List<CommandToken>, location: SourceLocation?) {
        requireSize(tokens, 2, "worldborder <add|center|damage|get|set|warning>", location)
        val border = world.worldBorder
        when (tokens[1].text) {
            "get" -> world.recordOutput("worldborder get", "data", text = border.size.toString(), payload = border.toJson())
            "set" -> {
                requireSize(tokens, 3, "worldborder set <distance> [time]", location)
                border.targetSize = parseDouble(tokens[2].text, "worldborder size", location).coerceAtLeast(1.0)
                border.size = border.targetSize
                border.lerpTimeSeconds = tokens.getOrNull(3)?.text?.let { parseLong(it, "worldborder time", location) } ?: 0
            }
            "add" -> {
                requireSize(tokens, 3, "worldborder add <distance> [time]", location)
                border.targetSize = (border.size + parseDouble(tokens[2].text, "worldborder size delta", location)).coerceAtLeast(1.0)
                border.size = border.targetSize
                border.lerpTimeSeconds = tokens.getOrNull(3)?.text?.let { parseLong(it, "worldborder time", location) } ?: 0
            }
            "center" -> {
                requireSize(tokens, 4, "worldborder center <x> <z>", location)
                border.centerX = parseCoordinate(tokens[2].text, border.centerX, location)
                border.centerZ = parseCoordinate(tokens[3].text, border.centerZ, location)
            }
            "damage" -> {
                requireSize(tokens, 4, "worldborder damage <amount|buffer> <value>", location)
                when (tokens[2].text) {
                    "amount" -> border.damageAmount = parseDouble(tokens[3].text, "worldborder damage amount", location)
                    "buffer" -> border.damageBuffer = parseDouble(tokens[3].text, "worldborder damage buffer", location)
                    else -> unsupportedFeature("Unsupported worldborder damage field '${tokens[2].text}'", profile.id, location)
                }
            }
            "warning" -> {
                requireSize(tokens, 4, "worldborder warning <distance|time> <value>", location)
                when (tokens[2].text) {
                    "distance" -> border.warningDistance = parseInt(tokens[3].text, "worldborder warning distance", location)
                    "time" -> border.warningTime = parseInt(tokens[3].text, "worldborder warning time", location)
                    else -> unsupportedFeature("Unsupported worldborder warning field '${tokens[2].text}'", profile.id, location)
                }
            }
            else -> unsupportedFeature("Unsupported worldborder action '${tokens[1].text}'", profile.id, location)
        }
    }

    private fun executeAdvancement(tokens: List<CommandToken>, location: SourceLocation?) {
        requireSize(tokens, 4, "advancement <grant|revoke|test> <targets> <mode> ...", location)
        val action = tokens[1].text
        val targets = resolvePlayers(tokens[2].text, location)
        val mode = tokens[3].text
        targets.forEach { player ->
            if (action == "test") {
                requireSize(tokens, 4, "advancement test <targets> <advancement> [criterion]", location)
                val id = ResourceLocation.parse(tokens[3].text)
                advancements.progress(player, id)
                return@forEach
            }
            val ids = advancementTargets(mode, tokens.getOrNull(4)?.text, location)
            val criterion = if (mode == "only") tokens.getOrNull(5)?.text else null
            ids.forEach { advancement ->
                when (action) {
                    "grant" -> advancements.grant(player, advancement, criterion)
                    "revoke" -> advancements.revoke(player, advancement, criterion)
                    else -> unsupportedFeature("Unsupported advancement action '$action'", profile.id, location)
                }
            }
        }
    }

    private fun advancementTargets(mode: String, idText: String?, location: SourceLocation?): List<ResourceLocation> =
        when (mode) {
            "everything" -> datapack.advancements.keys.sorted()
            "only", "from", "through", "until" -> listOf(ResourceLocation.parse(idText ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Advancement id is required", location)))
            else -> unsupportedFeature("Unsupported advancement mode '$mode'", profile.id, location)
        }

    private fun executeTeam(command: String, tokens: List<CommandToken>, location: SourceLocation?) {
        requireSize(tokens, 2, "team <add|remove|list|join|leave|empty|modify> ...", location)
        when (tokens[1].text) {
            "add" -> {
                requireSize(tokens, 3, "team add <team> [displayName]", location)
                val name = tokens[2].text
                world.teams[name] = SandboxTeam(name, tokens.getOrNull(3)?.let { CommandTokenizer.tailFrom(command, it) } ?: name)
            }
            "remove" -> {
                requireSize(tokens, 3, "team remove <team>", location)
                world.teams.remove(tokens[2].text)
            }
            "list" -> {
                val team = tokens.getOrNull(2)?.text
                val text = if (team == null) world.teams.keys.sorted().joinToString() else world.teams[team]?.members?.joinToString().orEmpty()
                world.recordOutput("team list", "data", text = text)
            }
            "join" -> {
                requireSize(tokens, 4, "team join <team> <members...>", location)
                val team = world.teams.getOrPut(tokens[2].text) { SandboxTeam(tokens[2].text) }
                tokens.drop(3).forEach { team.members += it.text }
            }
            "leave" -> {
                requireSize(tokens, 3, "team leave <members...>", location)
                tokens.drop(2).forEach { member -> world.teams.values.forEach { it.members -= member.text } }
            }
            "empty" -> {
                requireSize(tokens, 3, "team empty <team>", location)
                world.teams[tokens[2].text]?.members?.clear()
            }
            "modify" -> {
                requireSize(tokens, 5, "team modify <team> <option> <value>", location)
                val team = world.teams.getOrPut(tokens[2].text) { SandboxTeam(tokens[2].text) }
                if (tokens[3].text == "displayName") {
                    team.displayName = CommandTokenizer.tailFrom(command, tokens[4])
                } else {
                    team.options[tokens[3].text] = tokens[4].text
                }
            }
            else -> unsupportedFeature("Unsupported team action '${tokens[1].text}'", profile.id, location)
        }
    }

    private fun resolvePlayers(target: String, location: SourceLocation?, context: ExecutionContext = ExecutionContext()): List<SandboxPlayer> =
        when {
            target == "@a" -> world.players.values.toList()
            EntitySelectors.isSelector(target) -> EntitySelectors.select(world, target, context, location).filterIsInstance<SandboxPlayer>()
            else -> listOf(world.requirePlayer(target))
        }

    private fun runDueScheduledFunctions() {
        val due = world.scheduledFunctions.filter { it.dueTick <= world.gameTime }
        world.scheduledFunctions.removeAll(due.toSet())
        due.sortedWith(compareBy<ScheduledFunction> { it.dueTick }.thenBy { it.id.toString() }).forEach {
            runFunction(it.id)
        }
    }

    private fun scoreMatches(value: Int, range: String, location: SourceLocation?): Boolean {
        if (!range.contains("..")) return value == parseInt(range, "score range", location)
        val startText = range.substringBefore("..")
        val endText = range.substringAfter("..")
        val start = if (startText.isBlank()) Int.MIN_VALUE else parseInt(startText, "score range start", location)
        val end = if (endText.isBlank()) Int.MAX_VALUE else parseInt(endText, "score range end", location)
        return value in start..end
    }

    private fun parseSummonNbt(raw: String, location: SourceLocation?): JsonObject {
        val json = JsonValues.parse(raw, location)
        if (!json.isJsonObject) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Summon NBT must be an object", location)
        }
        return json.asJsonObject
    }

    private fun extractTags(nbt: JsonObject): Set<String> {
        val tags = nbt.get("Tags") ?: nbt.get("tags") ?: return emptySet()
        if (!tags.isJsonArray) return emptySet()
        return tags.asJsonArray.mapNotNull { if (it.isJsonPrimitive) it.asString else null }.toSet()
    }

    private fun parsePosition(tokens: List<CommandToken>, index: Int, base: Position, location: SourceLocation?): Position {
        requireSizeFrom(tokens, index, 3, "<x> <y> <z>", location)
        return Position(
            parseCoordinate(tokens[index].text, base.x, location),
            parseCoordinate(tokens[index + 1].text, base.y, location),
            parseCoordinate(tokens[index + 2].text, base.z, location),
        )
    }

    private fun parseBlockPos(tokens: List<CommandToken>, index: Int, base: Position, location: SourceLocation?): BlockPos {
        val pos = parsePosition(tokens, index, base, location)
        return BlockPos(floor(pos.x).toInt(), floor(pos.y).toInt(), floor(pos.z).toInt())
    }

    private fun parseColumnPos(tokens: List<CommandToken>, index: Int, base: Position, location: SourceLocation?): BlockPos {
        requireSizeFrom(tokens, index, 2, "<x> <z>", location)
        return BlockPos(
            floor(parseCoordinate(tokens[index].text, base.x, location)).toInt(),
            0,
            floor(parseCoordinate(tokens[index + 1].text, base.z, location)).toInt(),
        )
    }

    private fun isCoordinateTriple(tokens: List<CommandToken>, index: Int): Boolean =
        index + 2 < tokens.size && listOf(tokens[index], tokens[index + 1], tokens[index + 2]).all {
            it.text == "~" || it.text.startsWith("~") || it.text.toDoubleOrNull() != null
        }

    private fun isCoordinateToken(raw: String): Boolean =
        raw == "~" || raw.startsWith("~") || raw.toDoubleOrNull() != null

    private fun matchesBlock(pos: BlockPos, rawBlock: String, location: SourceLocation?): Boolean {
        val expected = parseBlockArgument(rawBlock, location)
        val actual = world.block(pos) ?: return expected.id == ResourceLocation("minecraft", "air")
        if (actual.id != expected.id) return false
        return expected.properties.all { (key, value) -> actual.properties[key] == value }
    }

    private fun parseBlockArgument(raw: String, location: SourceLocation?): BlockArgument {
        val nbtStart = raw.indexOf('{')
        val blockStateText = if (nbtStart >= 0) raw.substring(0, nbtStart) else raw
        val nbt = if (nbtStart >= 0) {
            if (!raw.endsWith("}")) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Malformed block NBT '$raw'", location)
            }
            val parsed = JsonValues.parse(raw.substring(nbtStart), location)
            if (!parsed.isJsonObject) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Block NBT must be an object", location)
            }
            parsed.asJsonObject
        } else {
            JsonObject()
        }
        val stateStart = blockStateText.indexOf('[')
        val idText = if (stateStart >= 0) blockStateText.substring(0, stateStart) else blockStateText
        val id = ResourceLocation.parse(idText)
        val properties = linkedMapOf<String, String>()
        if (stateStart >= 0) {
            if (!blockStateText.endsWith("]")) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Malformed block state '$raw'", location)
            }
            blockStateText.substring(stateStart + 1, blockStateText.length - 1)
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { pair ->
                    val key = pair.substringBefore("=", missingDelimiterValue = "")
                    val value = pair.substringAfter("=", missingDelimiterValue = "")
                    if (key.isBlank() || value.isBlank()) {
                        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Malformed block property '$pair'", location)
                    }
                    properties[key] = value
                }
        }
        return BlockArgument(id, properties, nbt)
    }

    private fun parseCoordinate(raw: String, base: Double): Double =
        parseCoordinate(raw, base, null)

    private fun parseCoordinate(raw: String, base: Double, location: SourceLocation?): Double =
        when {
            raw == "~" -> base
            raw.startsWith("~") -> base + (raw.drop(1).takeIf { it.isNotBlank() }?.toDoubleOrNull()
                ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid relative coordinate '$raw'", location))
            raw.startsWith("^") -> unsupportedFeature("Local coordinates '^' are not implemented", profile.id, location)
            else -> raw.toDoubleOrNull()
                ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid coordinate '$raw'", location)
        }


    private fun parseTime(raw: String, location: SourceLocation?): Long {
        val suffix = raw.last()
        val amountText = if (suffix.isLetter()) raw.dropLast(1) else raw
        val amount = amountText.toLongOrNull()
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid schedule time '$raw'", location)
        val ticks = when (suffix) {
            't' -> amount
            's' -> amount * 20
            'd' -> amount * 24000
            else -> if (suffix.isLetter()) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Unsupported schedule time unit '$suffix'", location)
            } else {
                amount
            }
        }
        return max(1, ticks)
    }

    private fun storeExecuteValue(tokens: List<CommandToken>, index: Int, value: Int, location: SourceLocation?, context: ExecutionContext) {
        requireSizeFrom(tokens, index, 4, "execute store <result|success> <score|storage|entity|block|bossbar> ...", location)
        val valueToStore = if (tokens[index].text == "success") {
            if (value > 0) 1 else 0
        } else if (tokens[index].text == "result") {
            value
        } else {
            unsupportedFeature("Unsupported execute store type '${tokens[index].text}'", profile.id, location)
        }
        when (tokens[index + 1].text) {
            "score" -> {
                requireSizeFrom(tokens, index, 4, "execute store ${tokens[index].text} score <target> <objective>", location)
                scoreTargets(tokens[index + 2].text, context, location).forEach { target ->
                    world.setScore(target, tokens[index + 3].text, valueToStore)
                }
            }
            "storage" -> {
                requireSizeFrom(tokens, index, 6, "execute store ${tokens[index].text} storage <id> <path> <type> <scale>", location)
                val storage = world.storage(ResourceLocation.parse(tokens[index + 2].text))
                val scaled = scaledStoreValue(valueToStore, tokens[index + 5].text, location)
                JsonPaths.set(storage, tokens[index + 3].text, scaled)
            }
            "entity" -> {
                requireSizeFrom(tokens, index, 6, "execute store ${tokens[index].text} entity <target> <path> <type> <scale>", location)
                val scaled = scaledStoreValue(valueToStore, tokens[index + 5].text, location)
                val target = EntitySelectors.select(world, tokens[index + 2].text, context, location).singleOrNull()
                    ?: throw SandboxException(DiagnosticCode.COMMAND_ERROR, "execute store entity requires exactly one target", location)
                if (target is SandboxPlayer) {
                    throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Player NBT is read-only in this sandbox; use player events or movement commands", location)
                }
                val updated = target.fullNbt(profile, location)
                JsonPaths.set(updated, tokens[index + 3].text, scaled)
                target.writeFullNbt(profile, updated, location)
            }
            "block" -> {
                requireSizeFrom(tokens, index, 8, "execute store ${tokens[index].text} block <pos> <path> <type> <scale>", location)
                val pos = parseBlockPos(tokens, index + 2, context.position, location)
                val block = world.requireBlock(pos)
                val scaled = scaledStoreValue(valueToStore, tokens[index + 7].text, location)
                val updated = block.fullNbt(pos, profile, location)
                JsonPaths.set(updated, tokens[index + 5].text, scaled)
                block.writeFullNbt(pos, profile, updated, location)
            }
            "bossbar" -> {
                requireSizeFrom(tokens, index, 4, "execute store ${tokens[index].text} bossbar <id> <value|max>", location)
                val bar = world.bossbars[ResourceLocation.parse(tokens[index + 2].text)]
                    ?: throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Bossbar '${tokens[index + 2].text}' does not exist", location)
                when (tokens[index + 3].text) {
                    "value" -> bar.value = valueToStore
                    "max" -> bar.max = valueToStore.coerceAtLeast(1)
                    else -> unsupportedFeature("Unsupported execute store bossbar field '${tokens[index + 3].text}'", profile.id, location)
                }
            }
            else -> unsupportedFeature("Unsupported execute store target '${tokens[index + 1].text}'", profile.id, location)
        }
    }

    private fun scaledStoreValue(value: Int, scaleText: String, location: SourceLocation?): JsonPrimitive {
        val scale = scaleText.toDoubleOrNull()
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid execute store scale '$scaleText'", location)
        val scaled = value * scale
        return if (scaled % 1.0 == 0.0) JsonPrimitive(scaled.toLong()) else JsonPrimitive(scaled)
    }

    private fun executeStoreValue(storeType: String, commandsRun: Int, outputsBefore: Int, returnValue: Int? = null): Int =
        when (storeType) {
            "success" -> if (commandsRun > 0) 1 else 0
            else -> returnValue ?: latestNumericOutput(outputsBefore) ?: commandsRun
        }

    private fun latestNumericOutput(outputsBefore: Int): Int? =
        world.outputs.drop(outputsBefore)
            .asReversed()
            .firstNotNullOfOrNull { output -> output.text.trim().toDoubleOrNull()?.toInt() }

    private fun outputSender(context: ExecutionContext): String =
        when (val entity = context.entity) {
            is SandboxPlayer -> entity.name
            null -> "Server"
            else -> entity.uuid
        }

    private fun parseInt(raw: String, label: String, location: SourceLocation?): Int =
        raw.toIntOrNull()
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid $label: '$raw'", location)

    private fun parseLong(raw: String, label: String, location: SourceLocation?): Long =
        raw.toLongOrNull()
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid $label: '$raw'", location)

    private fun parseDouble(raw: String, label: String, location: SourceLocation?): Double =
        raw.toDoubleOrNull()
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid $label: '$raw'", location)

    private fun parseBoolean(raw: String, label: String, location: SourceLocation?): Boolean =
        when (raw.lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid $label: '$raw'", location)
        }

    private fun parseTimeOfDay(raw: String, location: SourceLocation?): Long =
        when (raw) {
            "day" -> 1000
            "noon" -> 6000
            "night" -> 13000
            "midnight" -> 18000
            else -> parseLong(raw, "time value", location)
        }

    private fun normalizeGameMode(raw: String, location: SourceLocation?): String =
        when (raw.lowercase()) {
            "survival", "s", "0" -> "survival"
            "creative", "c", "1" -> "creative"
            "adventure", "a", "2" -> "adventure"
            "spectator", "sp", "3" -> "spectator"
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid game mode '$raw'", location)
        }

    private fun normalizeDifficulty(raw: String, location: SourceLocation?): String =
        when (raw.lowercase()) {
            "peaceful", "p", "0" -> "peaceful"
            "easy", "e", "1" -> "easy"
            "normal", "n", "2" -> "normal"
            "hard", "h", "3" -> "hard"
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid difficulty '$raw'", location)
        }

    private fun defaultAttribute(attribute: ResourceLocation): Double =
        when (attribute.toString()) {
            "minecraft:max_health" -> 20.0
            "minecraft:movement_speed" -> 0.1
            "minecraft:attack_damage" -> 2.0
            "minecraft:armor", "minecraft:armor_toughness", "minecraft:knockback_resistance" -> 0.0
            else -> 0.0
        }

    private fun parseIntRange(raw: String, location: SourceLocation?): Pair<Int, Int> {
        if (!raw.contains("..")) {
            val value = parseInt(raw, "random range", location)
            return value to value
        }
        val minText = raw.substringBefore("..")
        val maxText = raw.substringAfter("..")
        val min = if (minText.isBlank()) Int.MIN_VALUE else parseInt(minText, "random range start", location)
        val max = if (maxText.isBlank()) Int.MAX_VALUE else parseInt(maxText, "random range end", location)
        if (min > max) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid random range '$raw': minimum is greater than maximum", location)
        }
        return min to max
    }

    private fun inventorySlot(raw: String): Int =
        when {
            raw == "weapon.mainhand" || raw == "hotbar.selected" -> 0
            raw.startsWith("container.") -> raw.substringAfter('.').toIntOrNull()?.coerceAtLeast(0) ?: 0
            raw.startsWith("hotbar.") -> raw.substringAfter('.').toIntOrNull()?.coerceIn(0, 8) ?: 0
            raw.startsWith("inventory.") -> raw.substringAfter('.').toIntOrNull()?.let { it + 9 } ?: 0
            raw == "weapon.offhand" -> 40
            raw == "armor.feet" -> 36
            raw == "armor.legs" -> 37
            raw == "armor.chest" -> 38
            raw == "armor.head" -> 39
            else -> raw.substringAfterLast('.').toIntOrNull()?.coerceAtLeast(0) ?: 0
        }

    private fun requireSize(tokens: List<CommandToken>, size: Int, usage: String, location: SourceLocation?) {
        if (tokens.size < size) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Expected: $usage", location)
        }
    }

    private fun requireSizeFrom(tokens: List<CommandToken>, index: Int, size: Int, usage: String, location: SourceLocation?) {
        if (tokens.size < index + size) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Expected: $usage", location)
        }
    }

    private fun requireIndex(tokens: List<CommandToken>, index: Int, usage: String, location: SourceLocation?) {
        if (tokens.size <= index) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Expected: $usage", location)
        }
    }
}

private class ReturnSignal(val value: Int? = null) : RuntimeException() {
    override fun fillInStackTrace(): Throwable = this
}

private sealed interface DataTargetSpec {
    data class Storage(val id: ResourceLocation) : DataTargetSpec
    data class Entities(val entities: List<SandboxEntity>) : DataTargetSpec
    data class Block(val pos: BlockPos) : DataTargetSpec
}

private data class BlockArgument(
    val id: ResourceLocation,
    val properties: Map<String, String> = emptyMap(),
    val nbt: JsonObject = JsonObject(),
) {
    fun toBlock(pos: BlockPos, profile: VersionProfile, location: SourceLocation?): SandboxBlock? {
        if (id == ResourceLocation("minecraft", "air")) return null
        val block = SandboxBlock(id, properties.toMutableMap())
        if (nbt.entrySet().isNotEmpty()) {
            val updated = block.fullNbt(pos, profile, location)
            JsonPaths.merge(updated, null, nbt)
            block.writeFullNbt(pos, profile, updated, location)
        }
        return block
    }
}

private fun SandboxBlock.copyForClone(): SandboxBlock =
    SandboxBlock(
        id = id,
        properties = properties.toMutableMap(),
        nbt = nbt.deepCopy(),
    )

/**
 * Creates a [DatapackSandbox] from one or more datapack paths.
 *
 * @param version Minecraft version profile id.
 * @param packs Datapack directories or zip files to load, in pack priority order.
 * @param world Mutable world instance to use. Pass a preconfigured world when
 * tests need custom fixtures or imported save data.
 * @param defaultPlayerName Name of the initial player to create when [world]
 * has no players, or `null` to start without an implicit player.
 * @param unsupportedFeatureMode Policy for vanilla commands recognized by the
 * active profile but not implemented by the sandbox.
 */
@JvmOverloads
fun createSandbox(
    version: String,
    packs: List<Path>,
    world: SandboxWorld = SandboxWorld(),
    defaultPlayerName: String? = "Steve",
    unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
): DatapackSandbox {
    val profile = VersionProfiles.get(version)
    val datapack = DatapackLoader.load(packs, profile)
    defaultPlayerName?.let { if (world.players.isEmpty()) world.createPlayer(it) }
    return DatapackSandbox(profile, datapack, world, unsupportedFeatureMode)
}

/**
 * Creates a [DatapackSandbox] backed by multiple synthetic `.mcfunction` sources.
 *
 * This is the recommended low-level factory when tests need several functions
 * but do not need a full datapack directory.
 *
 * @param version Minecraft version profile id.
 * @param functionSources In-memory or file-backed function sources.
 * @param world Mutable world instance to use.
 * @param defaultPlayerName Name of the initial player to create when [world]
 * has no players, or `null` to start without an implicit player.
 * @param unsupportedFeatureMode Policy for recognized but unimplemented vanilla commands.
 */
@JvmOverloads
fun createFunctionSandbox(
    version: String,
    functionSources: List<FunctionSource>,
    world: SandboxWorld = SandboxWorld(),
    defaultPlayerName: String? = "Steve",
    unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
): DatapackSandbox {
    val profile = VersionProfiles.get(version)
    val datapack = DatapackLoader.loadFunctionSources(functionSources, profile)
    defaultPlayerName?.let { if (world.players.isEmpty()) world.createPlayer(it) }
    return DatapackSandbox(profile, datapack, world, unsupportedFeatureMode)
}

/**
 * Creates a [DatapackSandbox] from datapack dependencies plus synthetic functions.
 *
 * Dependency packs are loaded first from [packs], then [functionSources] overlay
 * additional top-priority functions. This keeps lightweight function tests able
 * to call functions, loot tables, predicates, and advancements from real
 * datapack dependencies.
 */
@JvmOverloads
fun createFunctionSandbox(
    version: String,
    packs: List<Path>,
    functionSources: List<FunctionSource>,
    world: SandboxWorld = SandboxWorld(),
    defaultPlayerName: String? = "Steve",
    unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
): DatapackSandbox {
    val profile = VersionProfiles.get(version)
    val datapack = DatapackLoader.loadFunctionSources(packs, functionSources, profile)
    defaultPlayerName?.let { if (world.players.isEmpty()) world.createPlayer(it) }
    return DatapackSandbox(profile, datapack, world, unsupportedFeatureMode)
}

/**
 * Creates a [DatapackSandbox] backed by a single `.mcfunction` file.
 *
 * This factory is intended for lightweight tests that do not need a full
 * datapack directory. The file is exposed as [functionId] inside a generated
 * in-memory datapack.
 *
 * @param version Minecraft version profile id.
 * @param functionFile Path to the `.mcfunction` file.
 * @param functionId Temporary function id assigned to [functionFile].
 * @param world Mutable world instance to use.
 * @param defaultPlayerName Name of the initial player to create when [world]
 * has no players, or `null` to start without an implicit player.
 * @param unsupportedFeatureMode Policy for recognized but unimplemented vanilla commands.
 */
@JvmOverloads
fun createFunctionSandbox(
    version: String,
    functionFile: Path,
    functionId: String = SingleFunctionDatapack.DEFAULT_ID,
    world: SandboxWorld = SandboxWorld(),
    defaultPlayerName: String? = "Steve",
    unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
): DatapackSandbox =
    createFunctionSandbox(
        version = version,
        functionSources = listOf(FunctionSource.file(functionId, functionFile)),
        world = world,
        defaultPlayerName = defaultPlayerName,
        unsupportedFeatureMode = unsupportedFeatureMode,
    )

/**
 * Creates a [DatapackSandbox] backed by one in-memory `.mcfunction` string.
 *
 * @param functionText Raw `.mcfunction` content.
 * @param functionId Temporary function id assigned to [functionText].
 * @param sourceName Label used in diagnostics.
 */
@JvmOverloads
fun createFunctionSandboxFromString(
    version: String,
    functionText: String,
    functionId: String = SingleFunctionDatapack.DEFAULT_ID,
    sourceName: String = "<string:$functionId>",
    world: SandboxWorld = SandboxWorld(),
    defaultPlayerName: String? = "Steve",
    unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
): DatapackSandbox =
    createFunctionSandbox(
        version = version,
        functionSources = listOf(FunctionSource.text(functionId, functionText, sourceName)),
        world = world,
        defaultPlayerName = defaultPlayerName,
        unsupportedFeatureMode = unsupportedFeatureMode,
    )

/**
 * Creates a [DatapackSandbox] backed by dependencies and one in-memory function string.
 */
@JvmOverloads
fun createFunctionSandboxFromString(
    version: String,
    packs: List<Path>,
    functionText: String,
    functionId: String = SingleFunctionDatapack.DEFAULT_ID,
    sourceName: String = "<string:$functionId>",
    world: SandboxWorld = SandboxWorld(),
    defaultPlayerName: String? = "Steve",
    unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
): DatapackSandbox =
    createFunctionSandbox(
        version = version,
        packs = packs,
        functionSources = listOf(FunctionSource.text(functionId, functionText, sourceName)),
        world = world,
        defaultPlayerName = defaultPlayerName,
        unsupportedFeatureMode = unsupportedFeatureMode,
    )
