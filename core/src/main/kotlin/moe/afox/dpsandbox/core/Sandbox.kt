package moe.afox.dpsandbox.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.nio.file.Path
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
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
    /** Current execution dimension. Defaults to [entity]'s dimension or the overworld. */
    val dimension: ResourceLocation = entity?.dimension ?: ResourceLocation("minecraft", "overworld"),
    /** Base yaw used by rotation-sensitive commands and relative rotation arguments. */
    val yaw: Double = entity?.yaw ?: 0.0,
    /** Base pitch used by rotation-sensitive commands and relative rotation arguments. */
    val pitch: Double = entity?.pitch ?: 0.0,
    /** Current execution anchor used as the base for local coordinates. */
    val anchor: String = "feet",
    /** Predicate evaluator available to selector `predicate=` filters. */
    val predicateEngine: PredicateEngine? = null,
)

/**
 * Summary returned by command/function/tick execution methods.
 */
data class ExecutionResult(
    /** Number of command lines executed by the operation. */
    val commandsExecuted: Int,
    /** Explicit value returned by `return <value>` or `return run <command>`, when present. */
    val returnValue: Int? = null,
    /** Whether the operation produced a successful command result. */
    val success: Boolean = commandsExecuted > 0,
)

private data class SandboxStructurePlacement(
    val rotation: String = "none",
    val mirror: String = "none",
    val integrity: Double = 1.0,
    val seed: Long? = null,
) {
    fun transform(offset: BlockPos): BlockPos {
        val mirrored = mirror(offset.x, offset.z)
        val rotated = rotate(mirrored.first, mirrored.second)
        return BlockPos(rotated.first, offset.y, rotated.second)
    }

    fun transform(offset: Position): Position {
        val mirrored = mirror(offset.x, offset.z)
        val rotated = rotate(mirrored.first, mirrored.second)
        return Position(rotated.first, offset.y, rotated.second)
    }

    fun shouldPlace(id: ResourceLocation, origin: BlockPos, index: Int): Boolean {
        if (integrity >= 1.0) return true
        if (integrity <= 0.0) return false
        val raw = "$id|$origin|$index|${seed ?: 0L}".hashCode().toLong()
        val normalized = (raw - Int.MIN_VALUE.toLong()).toDouble() / 4_294_967_296.0
        return normalized < integrity
    }

    private fun mirror(x: Int, z: Int): Pair<Int, Int> =
        when (mirror) {
            "front_back" -> x to -z
            "left_right" -> -x to z
            else -> x to z
        }

    private fun mirror(x: Double, z: Double): Pair<Double, Double> =
        when (mirror) {
            "front_back" -> x to -z
            "left_right" -> -x to z
            else -> x to z
        }

    private fun rotate(x: Int, z: Int): Pair<Int, Int> =
        when (rotation) {
            "clockwise_90" -> -z to x
            "clockwise_180" -> -x to -z
            "counterclockwise_90" -> z to -x
            else -> x to z
        }

    private fun rotate(x: Double, z: Double): Pair<Double, Double> =
        when (rotation) {
            "clockwise_90" -> -z to x
            "clockwise_180" -> -x to -z
            "counterclockwise_90" -> z to -x
            else -> x to z
        }

    companion object {
        val rotations = setOf("none", "clockwise_90", "clockwise_180", "counterclockwise_90")
        val mirrors = setOf("none", "front_back", "left_right")
    }
}

private data class SandboxFeaturePlacement(
    val kind: String,
    val root: JsonObject,
    val format: String,
)

private data class SandboxStructureProcessorList(
    val ids: List<ResourceLocation>,
    val processors: List<SandboxStructureProcessor>,
) {
    val unsupportedCount: Int = processors.count { !it.supported }

    fun plus(other: SandboxStructureProcessorList): SandboxStructureProcessorList =
        SandboxStructureProcessorList(ids = ids + other.ids, processors = processors + other.processors)

    companion object {
        val empty = SandboxStructureProcessorList(emptyList(), emptyList())
    }
}

private data class SandboxStructureProcessor(
    val type: String,
    val ignoredBlocks: Set<ResourceLocation> = emptySet(),
    val rules: List<SandboxStructureProcessorRule> = emptyList(),
    val supported: Boolean = true,
) {
    fun apply(block: BlockArgument): SandboxProcessedStructureBlock {
        if (block.id in ignoredBlocks) return SandboxProcessedStructureBlock(block = null, processed = true)
        val rule = rules.firstOrNull { it.matches(block) } ?: return SandboxProcessedStructureBlock(block)
        return SandboxProcessedStructureBlock(block = rule.output, processed = rule.output != block)
    }
}

private data class SandboxStructureProcessorRule(
    val inputBlocks: Set<ResourceLocation>?,
    val output: BlockArgument,
) {
    fun matches(block: BlockArgument): Boolean =
        inputBlocks?.contains(block.id) ?: true
}

private data class SandboxProcessedStructureBlock(
    val block: BlockArgument?,
    val processed: Boolean = false,
)

private data class SandboxTemplatePoolElement(
    val type: String,
    val structure: ResourceLocation? = null,
    val feature: ResourceLocation? = null,
    val processors: SandboxStructureProcessorList = SandboxStructureProcessorList.empty,
)

/**
 * Execution safety limits used to stop runaway tests deterministically.
 */
data class SandboxLimits(
    /** Maximum command lines that may execute during the lifetime of one sandbox instance. */
    val maxCommands: Int = 100_000,
    /** Maximum nested function call depth. */
    val maxFunctionDepth: Int = 64,
    /** Maximum tick count accepted by one [DatapackSandbox.runTicks] call. */
    val maxTicksPerRun: Int = 100_000,
    /** Maximum output events retained by one sandbox world. */
    val maxOutputEvents: Int = 100_000,
    /** Maximum rendered snapshot size in UTF-8 bytes. */
    val maxSnapshotBytes: Int = 10_000_000,
) {
    init {
        require(maxCommands > 0) { "maxCommands must be positive" }
        require(maxFunctionDepth > 0) { "maxFunctionDepth must be positive" }
        require(maxTicksPerRun > 0) { "maxTicksPerRun must be positive" }
        require(maxOutputEvents > 0) { "maxOutputEvents must be positive" }
        require(maxSnapshotBytes > 0) { "maxSnapshotBytes must be positive" }
    }
}

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
    /** Execution safety limits used to stop runaway tests. */
    val limits: SandboxLimits = SandboxLimits(),
) {
    /** Predicate evaluator bound to the loaded datapack. */
    val predicates = PredicateEngine(datapack, profile)
    /** Loot table evaluator bound to the loaded datapack and version registry view. */
    val loot = LootEngine(datapack, profile.registryView, profile)
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
        if (count > limits.maxTicksPerRun) {
            throw SandboxException(
                DiagnosticCode.COMMAND_ERROR,
                "Tick count $count exceeds sandbox limit ${limits.maxTicksPerRun}",
                version = profile.id,
            )
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
     * Function recursion is capped by [limits] to avoid runaway test execution.
     *
     * @param context Executor and position context for relative command semantics.
     * @throws SandboxException when the function does not exist or call depth exceeds [SandboxLimits.maxFunctionDepth].
     * @return number of command lines executed inside the function.
     */
    fun runFunction(id: ResourceLocation, context: ExecutionContext = ExecutionContext()): ExecutionResult {
        val before = commandsExecuted
        val function = datapack.function(id)
        functionDepth += 1
        if (functionDepth > limits.maxFunctionDepth) {
            functionDepth -= 1
            throw SandboxException(
                DiagnosticCode.COMMAND_ERROR,
                "Function call depth exceeded sandbox limit ${limits.maxFunctionDepth}",
                version = profile.id,
            )
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
        val executed = commandsExecuted - before
        val success = returnValue?.let { it > 0 } ?: (executed > 0)
        return ExecutionResult(executed, returnValue, success)
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
        val runtimeContext = context.copy(predicateEngine = predicates)
        val before = commandsExecuted
        val outputsBefore = world.outputs.size
        val beforeSnapshot = buildSnapshotJson()
        checkSnapshotSize(beforeSnapshot)
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
        var afterSnapshot: JsonObject? = null
        try {
            val commandSuccess = executeOne(normalized, location, runtimeContext)
            checkOutputLimit(location)
            afterSnapshot = buildSnapshotJson()
            checkSnapshotSize(afterSnapshot)
            success = commandSuccess
            return ExecutionResult(commandsExecuted - before, success = commandSuccess)
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
                executor = runtimeContext.entity?.scoreHolder ?: "Server",
                position = runtimeContext.position,
                success = success,
                commandsExecuted = commandsExecuted - before,
                outputs = world.outputs.size - outputsBefore,
                outputEvents = world.outputs.drop(outputsBefore),
                snapshotDiffs = SnapshotDiff.stateDiff(beforeSnapshot, afterSnapshot ?: buildSnapshotJson()),
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
        val snapshot = buildSnapshotJson()
        checkSnapshotSize(snapshot)
        return snapshot
    }

    private fun buildSnapshotJson(): JsonObject {
        val snapshot = world.snapshot(profile)
        snapshot.addProperty("version", profile.id)
        return snapshot
    }

    /**
     * Returns [snapshotJson] rendered as stable JSON text.
     */
    fun snapshotString(): String {
        val rendered = JsonValues.render(buildSnapshotJson())
        checkSnapshotSize(rendered)
        return rendered
    }

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
        applyPlayerEventState(normalized, player)
        var updates: List<AdvancementUpdate> = emptyList()
        var advancementFailures: List<AdvancementCriterionFailure> = emptyList()
        var success = false
        var errorCode: DiagnosticCode? = null
        var errorMessage: String? = null
        try {
            val result = advancements.handleWithDebug(normalized)
            updates = result.updates
            advancementFailures = result.failures
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
                advancementFailures = advancementFailures,
                success = success,
                errorCode = errorCode,
                errorMessage = errorMessage,
            )
        }
    }

    private fun applyPlayerEventState(event: PlayerEvent, player: SandboxPlayer) {
        when (event.type) {
            "item_consumed", "consume_item" -> applyConsumedItemEvent(player, event.item)
            "inventory_changed", "item_picked_up", "item_added" -> event.item?.let { player.inventory += it.copyForInventory() }
            "changed_dimension" -> event.toDimension?.let { player.dimension = it }
            "damage" -> event.damageAmount?.takeIf { it > 0.0 }?.let { amount ->
                player.health = (player.health - amount).coerceAtLeast(0.0)
            }
            "death" -> player.health = 0.0
            "recipe_unlocked" -> event.recipe?.let { player.recipes += it }
            "placed_block", "block_placed" -> if (event.block != null && event.blockPos != null) {
                world.setBlock(event.blockPos, SandboxBlock(event.block))
            }
            "block_broken", "broken_block", "broke_block" -> event.blockPos?.let { world.setBlock(it, null) }
        }
    }

    private fun applyConsumedItemEvent(player: SandboxPlayer, requested: ItemStack?) {
        val consumed = consumeInventoryItem(player, requested) ?: requested ?: return
        foodRestoredBy(consumed.id)?.let { player.food = (player.food + it).coerceAtMost(20) }
    }

    private fun consumeInventoryItem(player: SandboxPlayer, requested: ItemStack?): ItemStack? {
        val index = if (requested == null) {
            player.selectedSlot.takeIf { it in player.inventory.indices }
        } else {
            player.inventory.indexOfFirst { it.matchesEventItem(requested) }.takeIf { it >= 0 }
        } ?: return null
        val stack = player.inventory[index]
        val consumed = stack.copyForInventory().also { it.count = 1 }
        stack.count -= 1
        if (stack.count <= 0) {
            player.inventory.removeAt(index)
            if (player.selectedSlot >= player.inventory.size) {
                player.selectedSlot = player.inventory.lastIndex.coerceAtLeast(0)
            }
        }
        return consumed
    }

    private fun ItemStack.matchesEventItem(expected: ItemStack): Boolean =
        id == expected.id &&
            (expected.components.entrySet().isEmpty() || components == expected.components) &&
            (expected.nbt.entrySet().isEmpty() || nbt == expected.nbt)

    private fun ItemStack.copyForInventory(): ItemStack =
        copy(components = components.deepCopy(), nbt = nbt.deepCopy())

    private fun foodRestoredBy(id: ResourceLocation): Int? =
        when (id.toString()) {
            "minecraft:apple",
            "minecraft:golden_apple",
            "minecraft:enchanted_golden_apple",
            -> 4
            "minecraft:bread",
            "minecraft:baked_potato",
            "minecraft:cooked_cod",
            -> 5
            "minecraft:carrot",
            "minecraft:porkchop",
            "minecraft:beef",
            "minecraft:rabbit",
            -> 3
            "minecraft:cooked_beef",
            "minecraft:cooked_porkchop",
            "minecraft:pumpkin_pie",
            -> 8
            "minecraft:cooked_chicken",
            "minecraft:cooked_mutton",
            "minecraft:cooked_salmon",
            "minecraft:golden_carrot",
            "minecraft:mushroom_stew",
            "minecraft:rabbit_stew",
            "minecraft:beetroot_soup",
            -> 6
            "minecraft:cooked_rabbit" -> 5
            "minecraft:potato",
            "minecraft:beetroot",
            "minecraft:dried_kelp",
            "minecraft:tropical_fish",
            -> 1
            "minecraft:melon_slice",
            "minecraft:sweet_berries",
            "minecraft:glow_berries",
            "minecraft:cookie",
            "minecraft:cod",
            "minecraft:salmon",
            "minecraft:chicken",
            "minecraft:mutton",
            -> 2
            "minecraft:rotten_flesh",
            "minecraft:spider_eye",
            "minecraft:poisonous_potato",
            -> 2
            else -> null
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

    private fun executeOne(command: String, location: SourceLocation?, context: ExecutionContext): Boolean {
        if (command.isBlank()) return false
        val tokens = CommandTokenizer.tokenize(command, location)
        if (tokens.isEmpty()) return false
        countCommand(location)

        try {
            when (tokens[0].text) {
                "ban", "ban-ip", "banlist", "deop", "kick", "op", "pardon", "pardon-ip", "whitelist" ->
                    executeServerAdminNoop(command, tokens, location)
                "function" -> return executeFunction(tokens, location, context).success
                "return" -> executeReturn(command, tokens, location, context)
                "attribute" -> executeAttribute(tokens, location, context)
                "scoreboard" -> executeScoreboard(command, tokens, location)
                "bossbar" -> executeBossbar(command, tokens, location, context)
                "clear" -> executeClear(tokens, location, context)
                "clone" -> executeClone(tokens, location, context)
                "damage" -> executeDamage(tokens, location, context)
                "datapack" -> executeDatapack(tokens, location)
                "debug", "jfr", "perf" -> executeProfilingNoop(tokens, location)
                "defaultgamemode" -> executeDefaultGameMode(tokens, location)
                "difficulty" -> executeDifficulty(tokens, location)
                "execute" -> return executeExecute(command, tokens, location, context)
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
                "place" -> executePlace(tokens, location, context)
                "loot" -> executeLoot(tokens, location, context)
                "tp", "teleport" -> executeTeleport(tokens, location, context)
                "publish" -> executePublish(tokens, location)
                "reload" -> executeReload(tokens, location)
                "setblock" -> executeSetBlock(tokens, location, context)
                "fill" -> executeFill(tokens, location, context)
                "random" -> executeRandom(tokens, location)
                "recipe" -> executeRecipe(tokens, location, context)
                "ride" -> executeRide(tokens, location, context)
                "rotate" -> executeRotate(tokens, location, context)
                "schedule" -> executeSchedule(tokens, location)
                "save-all", "save-off", "save-on" -> executeSaveLifecycleNoop(tokens, location)
                "seed" -> executeSeed(tokens, location)
                "setidletimeout" -> executeSetIdleTimeout(tokens, location)
                "setworldspawn" -> executeSetWorldSpawn(tokens, location, context)
                "spawnpoint" -> executeSpawnPoint(tokens, location, context)
                "spectate" -> executeSpectate(tokens, location, context)
                "spreadplayers" -> executeSpreadPlayers(tokens, location, context)
                "stop" -> executeStop(tokens, location)
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
                "transfer" -> executeTransfer(command, tokens, location, context)
                else -> handleUnknownCommand(tokens[0].text, command, location)
            }
            return true
        } catch (error: SandboxException) {
            if (error.code == DiagnosticCode.UNSUPPORTED_FEATURE &&
                unsupportedFeatureMode != UnsupportedFeatureMode.ERROR &&
                tokens.firstOrNull()?.text?.let(profile.commands::hasRoot) == true
            ) {
                if (unsupportedFeatureMode == UnsupportedFeatureMode.WARN) {
                    recordUnsupportedWarning(command, error, location)
                }
                return true
            }
            if (error.location == null && location != null) {
                throw SandboxException(error.code, error.message, location, error.version ?: profile.id, error.command, error)
            }
            throw error
        }
    }

    private fun countCommand(location: SourceLocation?) {
        if (commandsExecuted >= limits.maxCommands) {
            throw SandboxException(
                DiagnosticCode.COMMAND_ERROR,
                "Command execution count exceeded sandbox limit ${limits.maxCommands}",
                location = location,
                version = profile.id,
            )
        }
        commandsExecuted += 1
    }

    private fun checkOutputLimit(location: SourceLocation?) {
        if (world.outputs.size > limits.maxOutputEvents) {
            throw SandboxException(
                DiagnosticCode.COMMAND_ERROR,
                "Output event count ${world.outputs.size} exceeds sandbox limit ${limits.maxOutputEvents}",
                location = location,
                version = profile.id,
            )
        }
    }

    private fun checkSnapshotSize(snapshot: JsonObject) {
        checkSnapshotSize(JsonValues.render(snapshot))
    }

    private fun checkSnapshotSize(renderedSnapshot: String) {
        val bytes = renderedSnapshot.toByteArray(Charsets.UTF_8).size
        if (bytes > limits.maxSnapshotBytes) {
            throw SandboxException(
                DiagnosticCode.COMMAND_ERROR,
                "Snapshot size $bytes bytes exceeds sandbox limit ${limits.maxSnapshotBytes} bytes",
                version = profile.id,
            )
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

    private fun executeFunction(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext): ExecutionResult {
        requireSize(tokens, 2, "function <id>", location)
        val result = runFunction(ResourceLocation.parse(tokens[1].text), context)
        lastFunctionReturnValue = result.returnValue
        return result
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
                val success = executeOne(rest, location, context)
                val commandsRun = (commandsExecuted - commandsBefore).coerceAtLeast(0)
                throw ReturnSignal(executeStoreValue("result", commandsRun, outputsBefore, lastFunctionReturnValue, success))
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
                val players = resolvePlayers(tokens[2].text, location, context)
                players.forEach { player ->
                    player.inventory += items.map { it.copy(components = it.components.deepCopy(), nbt = it.nbt.deepCopy()) }
                    items.forEach { advancements.handle(PlayerEvent(player.name, "inventory_changed", item = it)) }
                }
                recordLootOutput("loot give", "players", players.map { it.name }, items)
            }
            "insert" -> {
                requireSize(tokens, 7, "loot insert <pos> loot <table>", location)
                val pos = parseBlockPos(tokens, 2, context, location)
                val items = parseLootSource(tokens, 5, context, location)
                insertLootIntoBlock(pos, items, location)
                recordLootOutput("loot insert", "block", listOf(pos.toString()), items)
            }
            "spawn" -> {
                requireSize(tokens, 7, "loot spawn <pos> loot <table>", location)
                val pos = parsePosition(tokens, 2, context, location)
                val items = parseLootSource(tokens, 5, context, location)
                spawnLootItems(pos, items, context.dimension)
                recordLootOutput(
                    "loot spawn",
                    "position",
                    listOf("${pos.x} ${pos.y} ${pos.z}"),
                    items,
                    JsonObject().also {
                        it.addProperty("dimension", context.dimension.toString())
                        it.addDimensionMetadata("dimensionResource", context.dimension, location)
                    },
                )
            }
            "replace" -> executeLootReplace(tokens, location, context)
            else -> unsupportedFeature("Unsupported loot target '${tokens[1].text}'", profile.id, location)
        }
    }

    private fun recordLootOutput(
        command: String,
        targetKind: String,
        targets: List<String>,
        items: List<ItemStack>,
        extraPayload: JsonObject = JsonObject(),
    ) {
        world.recordOutput(
            command,
            "data",
            targets = targets,
            text = items.sumOf { it.count }.toString(),
            payload = extraPayload.also { payload ->
                payload.addProperty("targetKind", targetKind)
                payload.add("targets", JsonArray().also { array -> targets.forEach { array.add(it) } })
                payload.add("items", itemStacksJson(items))
                payload.addProperty("stacks", items.size)
                payload.addProperty("totalCount", items.sumOf { it.count })
            },
        )
    }

    private fun itemStacksJson(items: List<ItemStack>): JsonArray =
        JsonArray().also { array -> items.forEach { array.add(it.toJson()) } }

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

    private fun executePlace(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 3, "place <feature|jigsaw|structure|template> ...", location)
        val kind = tokens[1].text
        val payload = JsonObject()
        payload.addProperty("kind", kind)
        var placedTargets = emptyList<String>()

        var placeId: ResourceLocation? = null
        val positionIndex = when (kind) {
            "feature", "structure", "template" -> {
                placeId = ResourceLocation.parse(tokens[2].text)
                payload.addProperty("id", placeId.toString())
                3
            }
            "jigsaw" -> {
                requireSize(tokens, 5, "place jigsaw <pool> <target> <maxDepth> [pos]", location)
                payload.addProperty("pool", ResourceLocation.parse(tokens[2].text).toString())
                payload.addProperty("target", ResourceLocation.parse(tokens[3].text).toString())
                payload.addProperty("maxDepth", parseInt(tokens[4].text, "jigsaw max depth", location))
                5
            }
            else -> unsupportedFeature("Unsupported place kind '$kind'", profile.id, location)
        }

        val position: Position
        val extraStart = if (isCoordinateTriple(tokens, positionIndex)) {
            position = parsePosition(tokens, positionIndex, context, location)
            payload.add("position", positionOutput(position))
            positionIndex + 3
        } else {
            position = context.position
            payload.add("position", positionOutput(position))
            positionIndex
        }
        val extras = tokens.drop(extraStart).map { it.text }
        if (extras.isNotEmpty()) {
            payload.add(
                "extra",
                JsonArray().also { extra ->
                    extras.forEach { extra.add(it) }
                },
            )
        }
        when (kind) {
            "feature" -> {
                placedTargets = placeSandboxFeature(placeId ?: error("missing place id"), position, payload, location)
            }
            "jigsaw" -> {
                placedTargets = placeSandboxJigsaw(ResourceLocation.parse(tokens[2].text), position, context, payload, location)
            }
            "structure", "template" -> {
                val placement = if (kind == "template") parseTemplatePlacement(extras, payload, location) else SandboxStructurePlacement()
                placedTargets = placeSandboxStructure(placeId ?: error("missing place id"), position, context, payload, placement, location)
            }
            else -> {
                payload.addProperty("placed", false)
                payload.addProperty("reason", "Sandbox records place commands but does not simulate this worldgen kind")
            }
        }

        val idText = payload.get("id")?.asString ?: payload.get("pool")?.asString.orEmpty()
        world.recordOutput("place $kind", "worldgen", targets = placedTargets, text = "$kind:$idText", payload = payload)
    }

    private fun placeSandboxJigsaw(
        pool: ResourceLocation,
        position: Position,
        context: ExecutionContext,
        payload: JsonObject,
        location: SourceLocation?,
    ): List<String> {
        val resource = datapack.rawResources["worldgen/template_pool"]?.get(pool)
        if (resource == null) {
            payload.addProperty("placed", false)
            payload.addProperty("reason", "Sandbox records place jigsaw but no template pool resource was loaded")
            return emptyList()
        }
        if (!resource.root.isJsonObject) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Template pool resource '$pool' must be a JSON object", location)
        }
        val element = selectTemplatePoolElement(resource.root.asJsonObject, "template pool $pool", location)
        if (element == null) {
            payload.addProperty("placed", false)
            payload.addProperty("format", "raw-json-index-only")
            payload.addProperty("reason", "Loaded template pool has no supported single/legacy structure or feature element")
            return emptyList()
        }
        payload.addProperty("elementType", element.type)
        element.structure?.let { payload.addProperty("structure", it.toString()) }
        element.feature?.let { payload.addProperty("feature", it.toString()) }
        val targets = when {
            element.structure != null -> placeSandboxStructure(
                id = element.structure,
                position = position,
                context = context,
                payload = payload,
                placement = SandboxStructurePlacement(),
                location = location,
                extraProcessors = element.processors,
            )
            element.feature != null -> placeSandboxFeature(element.feature, position, payload, location)
            else -> emptyList()
        }
        payload.addProperty("format", "sandbox-template-pool")
        return targets
    }

    private fun selectTemplatePoolElement(
        root: JsonObject,
        label: String,
        location: SourceLocation?,
    ): SandboxTemplatePoolElement? {
        val elements = root.getAsJsonArrayOrNull("elements") ?: return parseTemplatePoolElement(root, label, location)
        elements.forEachIndexed { index, element ->
            parseTemplatePoolElement(element.asPlaceJsonObject("$label element $index", location), "$label element $index", location)
                ?.let { return it }
        }
        return null
    }

    private fun parseTemplatePoolElement(
        root: JsonObject,
        label: String,
        location: SourceLocation?,
    ): SandboxTemplatePoolElement? {
        val elementRoot = root.get("element")
            ?.asPlaceJsonObject("$label element", location)
            ?: root
        val type = (elementRoot.placeString("element_type", location) ?: elementRoot.placeString("type", location) ?: "minecraft:single_pool_element")
        return when (type.removePrefix("minecraft:")) {
            "single_pool_element", "legacy_single_pool_element" -> {
                val structure = elementRoot.placeString("location", location)
                    ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label location is required", location)
                SandboxTemplatePoolElement(
                    type = type,
                    structure = ResourceLocation.parse(structure),
                    processors = parseTemplatePoolProcessors(elementRoot, "$label processors", location),
                )
            }
            "list_pool_element" -> {
                val children = elementRoot.getAsJsonArrayOrNull("elements") ?: return null
                children.forEachIndexed { index, child ->
                    parseTemplatePoolElement(child.asPlaceJsonObject("$label child $index", location), "$label child $index", location)
                        ?.let { return it.copy(type = type) }
                }
                null
            }
            "feature_pool_element" -> {
                val feature = elementRoot.placeString("feature", location)
                feature?.let {
                    SandboxTemplatePoolElement(
                        type = type,
                        feature = ResourceLocation.parse(it),
                        processors = parseTemplatePoolProcessors(elementRoot, "$label processors", location),
                    )
                }
            }
            "empty_pool_element" -> null
            else -> null
        }
    }

    private fun parseTemplatePoolProcessors(
        root: JsonObject,
        label: String,
        location: SourceLocation?,
    ): SandboxStructureProcessorList {
        val value = root.get("processors") ?: return SandboxStructureProcessorList.empty
        return parseStructureProcessorSource(value, label, location)
    }

    private fun placeSandboxFeature(
        id: ResourceLocation,
        position: Position,
        payload: JsonObject,
        location: SourceLocation?,
    ): List<String> {
        val feature = resolvePlaceFeature(id, payload, location)
        if (feature == null) {
            payload.addProperty("placed", false)
            payload.addProperty("reason", "Sandbox records place commands but no placed/configured feature resource was loaded")
            return emptyList()
        }
        val block = feature.root.parseFeatureBlockArgument(location)
        if (block == null) {
            payload.addProperty("placed", false)
            payload.addProperty("format", "raw-json-index-only")
            payload.addProperty("reason", "Loaded feature resource has no sandbox block or supported simple_block state")
            return emptyList()
        }

        val pos = BlockPos(floor(position.x).toInt(), floor(position.y).toInt(), floor(position.z).toInt())
        val before = world.block(pos)?.copyForClone()
        world.setBlock(pos, block.toBlock(pos, profile, location))
        val after = world.block(pos)?.copyForClone()
        val changed = !sameBlock(before, after)
        payload.addProperty("placed", true)
        payload.addProperty("format", feature.format)
        payload.addProperty("resourceKind", feature.kind)
        payload.addProperty("changedBlocks", if (changed) 1 else 0)
        payload.add("origin", blockPosOutput(pos))
        payload.add("positions", blockPosArrayOutput(if (changed) listOf(pos) else emptyList()))
        payload.add("block", blockArgumentOutput(block))
        return if (changed) listOf(pos.toString()) else emptyList()
    }

    private fun resolvePlaceFeature(id: ResourceLocation, payload: JsonObject, location: SourceLocation?): SandboxFeaturePlacement? {
        datapack.rawResources["worldgen/placed_feature"]?.get(id)?.let { placed ->
            if (!placed.root.isJsonObject) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Placed feature resource '$id' must be a JSON object", location)
            }
            val root = placed.root.asJsonObject
            payload.addProperty("resourceKind", "worldgen/placed_feature")
            root.get("feature")?.let { feature ->
                if (feature.isJsonPrimitive) {
                    val configuredId = ResourceLocation.parse(feature.asJsonPrimitive.asString)
                    val configured = datapack.rawResources["worldgen/configured_feature"]?.get(configuredId)
                        ?: throw SandboxException(DiagnosticCode.RESOURCE_NOT_FOUND, "Configured feature '$configuredId' referenced by placed feature '$id' was not found", location)
                    if (!configured.root.isJsonObject) {
                        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Configured feature resource '$configuredId' must be a JSON object", location)
                    }
                    payload.addProperty("configuredFeature", configuredId.toString())
                    return SandboxFeaturePlacement("worldgen/configured_feature", configured.root.asJsonObject, "configured-simple-block")
                }
                if (feature.isJsonObject) {
                    payload.addProperty("configuredFeature", "inline")
                    return SandboxFeaturePlacement("worldgen/placed_feature", feature.asJsonObject, "configured-simple-block")
                }
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Placed feature resource '$id' field 'feature' must be a string id or object", location)
            }
            return SandboxFeaturePlacement("worldgen/placed_feature", root, "sandbox-feature-json")
        }
        datapack.rawResources["worldgen/configured_feature"]?.get(id)?.let { configured ->
            if (!configured.root.isJsonObject) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Configured feature resource '$id' must be a JSON object", location)
            }
            payload.addProperty("resourceKind", "worldgen/configured_feature")
            return SandboxFeaturePlacement("worldgen/configured_feature", configured.root.asJsonObject, "configured-simple-block")
        }
        return null
    }

    private fun JsonObject.parseFeatureBlockArgument(location: SourceLocation?): BlockArgument? {
        get("block")?.let { block ->
            return when {
                block.isJsonPrimitive -> BlockArgument(ResourceLocation.parse(block.asJsonPrimitive.asString))
                block.isJsonObject -> block.asJsonObject.parseBlockStateObject("feature block", location)
                else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "feature block must be a string or object", location)
            }
        }
        getAsJsonObjectOrNull("state", location)?.let { return it.parseBlockStateObject("feature state", location) }
        val type = placeString("type", location)
        if (type == null || type == "minecraft:simple_block" || type == "simple_block") {
            val config = getAsJsonObjectOrNull("config", location)
            val provider = config?.getAsJsonObjectOrNull("to_place", location) ?: getAsJsonObjectOrNull("to_place", location)
            val state = provider?.getAsJsonObjectOrNull("state", location) ?: provider?.takeIf { it.has("Name") || it.has("id") }
            if (state != null) return state.parseBlockStateObject("simple_block state", location)
        }
        return null
    }

    private fun JsonObject.parseBlockStateObject(label: String, location: SourceLocation?): BlockArgument {
        val rawId = placeString("id", location) ?: placeString("Name", location)
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label requires id or Name", location)
        val properties = (getAsJsonObjectOrNull("properties", location) ?: getAsJsonObjectOrNull("Properties", location))
            ?.entrySet()
            ?.associate { (key, value) ->
                if (!value.isJsonPrimitive) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label property '$key' must be a primitive", location)
                key to value.asJsonPrimitive.asString
            }
            ?: emptyMap()
        val nbt = getAsJsonObjectOrNull("nbt", location) ?: getAsJsonObjectOrNull("Nbt", location) ?: JsonObject()
        return BlockArgument(ResourceLocation.parse(rawId), properties, nbt)
    }

    private fun placeSandboxStructure(
        id: ResourceLocation,
        position: Position,
        context: ExecutionContext,
        payload: JsonObject,
        placement: SandboxStructurePlacement,
        location: SourceLocation?,
        extraProcessors: SandboxStructureProcessorList = SandboxStructureProcessorList.empty,
    ): List<String> {
        val resource = datapack.rawResources["worldgen/structure"]?.get(id)
        if (resource == null) {
            payload.addProperty("placed", false)
            payload.addProperty("reason", "Sandbox records place commands but no sandbox structure JSON resource was loaded")
            return emptyList()
        }
        if (!resource.root.isJsonObject) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Structure resource '$id' must be a JSON object", location)
        }
        val root = resource.root.asJsonObject
        val blocks = root.getAsJsonArrayOrNull("blocks")
        val entities = root.getAsJsonArrayOrNull("entities")
        val processors = parseStructureProcessors(root, location).plus(extraProcessors)
        if (blocks == null && entities == null) {
            payload.addProperty("placed", false)
            payload.addProperty("format", "raw-json-index-only")
            payload.addProperty("reason", "Loaded structure resource has no sandbox blocks or entities")
            return emptyList()
        }
        if ((blocks?.size() ?: 0) > 32768) {
            throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Structure resource '$id' has ${blocks?.size()} blocks; limit is 32768", location)
        }

        val origin = BlockPos(floor(position.x).toInt(), floor(position.y).toInt(), floor(position.z).toInt())
        val changedBlocks = mutableListOf<BlockPos>()
        var skippedBlocks = 0
        var processedBlocks = 0
        blocks?.forEachIndexed { index, element ->
            val block = element.asPlaceJsonObject("structure block $index", location)
            val offset = block.placeBlockPos("offset", "pos", "structure block $index offset", location)
            if (!placement.shouldPlace(id, origin, index)) {
                skippedBlocks++
                return@forEachIndexed
            }
            val transformedOffset = placement.transform(offset)
            val pos = BlockPos(origin.x + transformedOffset.x, origin.y + transformedOffset.y, origin.z + transformedOffset.z)
            val properties = block.placeProperties(location)
            val blockNbt = block.placeJsonObject("nbt", "structure block $index nbt", location)
            val blockArgument = BlockArgument(
                id = ResourceLocation.parse(block.requiredPlaceString("id", "structure block $index id", location)),
                properties = properties,
                nbt = blockNbt ?: JsonObject(),
            )
            val processed = applyStructureProcessors(blockArgument, processors.processors)
            if (processed.processed) processedBlocks++
            val processedArgument = processed.block
            if (processedArgument == null) {
                skippedBlocks++
                return@forEachIndexed
            }
            val placedBlock = processedArgument.toBlock(pos, profile, location)
            val before = world.block(pos)?.copyForClone()
            world.setBlock(pos, placedBlock)
            val after = world.block(pos)?.copyForClone()
            if (!sameBlock(before, after)) changedBlocks += pos
        }

        val createdEntities = mutableListOf<SandboxEntity>()
        entities?.forEachIndexed { index, element ->
            val entityRoot = element.asPlaceJsonObject("structure entity $index", location)
            val offset = entityRoot.optionalPlacePosition("offset", "pos", "structure entity $index offset", location)
            val transformedOffset = placement.transform(offset)
            val positionAtOffset = Position(origin.x + transformedOffset.x, origin.y + transformedOffset.y, origin.z + transformedOffset.z)
            val entityNbt = entityRoot.placeJsonObject("nbt", "structure entity $index nbt", location)
            val tags = entityRoot.placeStringArray("tags", "structure entity $index tags", location).toMutableSet()
            if (entityNbt != null) tags += extractTags(entityNbt)
            val entity = SandboxEntity(
                type = ResourceLocation.parse(entityRoot.requiredPlaceString("type", "structure entity $index type", location)),
                position = positionAtOffset,
                tags = tags,
                yaw = entityRoot.placeDouble("yaw", 0.0, "structure entity $index yaw", location),
                pitch = entityRoot.placeDouble("pitch", 0.0, "structure entity $index pitch", location),
                dimension = entityRoot.placeString("dimension", location)?.let(ResourceLocation::parse) ?: context.dimension,
            )
            val fullNbt = entity.fullNbt(profile, location)
            if (entityNbt != null) JsonPaths.merge(fullNbt, null, entityNbt.deepCopy())
            entityRoot.get("health")?.let { fullNbt.addProperty("Health", it.placeDouble("structure entity $index health", location)) }
            entity.writeFullNbt(profile, fullNbt, location)
            world.entities += entity
            createdEntities += entity
        }

        payload.addProperty("placed", true)
        payload.addProperty("format", "sandbox-structure-json")
        payload.addProperty("changedBlocks", changedBlocks.size)
        payload.addProperty("skippedBlocks", skippedBlocks)
        payload.addProperty("processedBlocks", processedBlocks)
        payload.addProperty("unsupportedProcessors", processors.unsupportedCount)
        payload.addProperty("entities", createdEntities.size)
        if (processors.ids.isNotEmpty()) {
            payload.add(
                "processorLists",
                JsonArray().also { array -> processors.ids.map { it.toString() }.sorted().forEach { array.add(it) } },
            )
        }
        payload.add("origin", blockPosOutput(origin))
        payload.add("positions", blockPosArrayOutput(changedBlocks))
        payload.add(
            "entityTargets",
            JsonArray().also { targets -> createdEntities.map { it.scoreHolder }.sorted().forEach { targets.add(it) } },
        )
        return changedBlocks.map { it.toString() } + createdEntities.map { it.scoreHolder }
    }

    private fun parseStructureProcessors(root: JsonObject, location: SourceLocation?): SandboxStructureProcessorList {
        val value = root.get("processors") ?: root.get("processor_list") ?: return SandboxStructureProcessorList.empty
        return parseStructureProcessorSource(value, "structure processors", location)
    }

    private fun parseStructureProcessorSource(
        value: JsonElement,
        label: String,
        location: SourceLocation?,
    ): SandboxStructureProcessorList =
        when {
            value.isJsonPrimitive -> {
                val id = ResourceLocation.parse(value.asJsonPrimitive.asString)
                val resource = datapack.rawResources["worldgen/processor_list"]?.get(id)
                    ?: throw SandboxException(DiagnosticCode.RESOURCE_NOT_FOUND, "Processor list '$id' referenced by structure was not found", location)
                if (!resource.root.isJsonObject) {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Processor list resource '$id' must be a JSON object", location)
                }
                parseStructureProcessorListObject(resource.root.asJsonObject, "$label $id", location, listOf(id))
            }
            value.isJsonObject -> parseStructureProcessorListObject(value.asJsonObject, label, location)
            value.isJsonArray -> SandboxStructureProcessorList(
                ids = emptyList(),
                processors = value.asJsonArray.mapIndexed { index, element ->
                    element.asPlaceJsonObject("$label entry $index", location).parseStructureProcessor("$label entry $index", location)
                },
            )
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must be a resource id, object, or array", location)
        }

    private fun parseStructureProcessorListObject(
        root: JsonObject,
        label: String,
        location: SourceLocation?,
        ids: List<ResourceLocation> = emptyList(),
    ): SandboxStructureProcessorList {
        val processors = root.getAsJsonArrayOrNull("processors")
        if (processors != null) {
            return SandboxStructureProcessorList(
                ids = ids,
                processors = processors.mapIndexed { index, element ->
                    element.asPlaceJsonObject("$label processor $index", location).parseStructureProcessor("$label processor $index", location)
                },
            )
        }
        return SandboxStructureProcessorList(
            ids = ids,
            processors = listOf(root.parseStructureProcessor(label, location)),
        )
    }

    private fun JsonObject.parseStructureProcessor(label: String, location: SourceLocation?): SandboxStructureProcessor {
        val type = placeString("processor_type", location) ?: placeString("type", location)
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label processor type is required", location)
        return when (type.removePrefix("minecraft:")) {
            "block_ignore" -> SandboxStructureProcessor(
                type = type,
                ignoredBlocks = processorBlockIds("blocks", "block", "$label block_ignore blocks", location),
            )
            "rule" -> SandboxStructureProcessor(
                type = type,
                rules = (getAsJsonArrayOrNull("rules") ?: JsonArray()).mapIndexed { index, element ->
                    element.asPlaceJsonObject("$label rule $index", location).parseStructureProcessorRule("$label rule $index", location)
                },
            )
            else -> SandboxStructureProcessor(type = type, supported = false)
        }
    }

    private fun JsonObject.parseStructureProcessorRule(label: String, location: SourceLocation?): SandboxStructureProcessorRule {
        val input = placeJsonObject("input_predicate", "$label input_predicate", location)
            ?: placeJsonObject("input", "$label input", location)
        val output = get("output_state") ?: get("output") ?: get("block")
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label output_state is required", location)
        return SandboxStructureProcessorRule(
            inputBlocks = input?.structureProcessorPredicateBlocks("$label input_predicate", location),
            output = output.parseStructureProcessorBlockArgument("$label output_state", location),
        )
    }

    private fun JsonObject.structureProcessorPredicateBlocks(label: String, location: SourceLocation?): Set<ResourceLocation>? {
        val type = placeString("predicate_type", location) ?: placeString("type", location) ?: "minecraft:always_true"
        return when (type.removePrefix("minecraft:")) {
            "always_true" -> null
            "matching_blocks" -> processorBlockIds("blocks", "block", "$label blocks", location)
            else -> emptySet()
        }
    }

    private fun JsonObject.processorBlockIds(
        primary: String,
        secondary: String,
        label: String,
        location: SourceLocation?,
    ): Set<ResourceLocation> {
        val value = get(primary) ?: get(secondary) ?: return emptySet()
        return when {
            value.isJsonPrimitive -> setOf(ResourceLocation.parse(value.asJsonPrimitive.asString))
            value.isJsonArray -> value.asJsonArray.mapIndexed { index, element ->
                if (!element.isJsonPrimitive) {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label entry $index must be a resource id string", location)
                }
                ResourceLocation.parse(element.asJsonPrimitive.asString)
            }.toSet()
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must be a resource id string or array", location)
        }
    }

    private fun JsonElement.parseStructureProcessorBlockArgument(label: String, location: SourceLocation?): BlockArgument =
        when {
            isJsonPrimitive -> BlockArgument(ResourceLocation.parse(asJsonPrimitive.asString))
            isJsonObject -> asJsonObject.parseBlockStateObject(label, location)
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must be a block id string or state object", location)
        }

    private fun applyStructureProcessors(
        block: BlockArgument,
        processors: List<SandboxStructureProcessor>,
    ): SandboxProcessedStructureBlock {
        var current = block
        var processed = false
        processors.forEach { processor ->
            val result = processor.apply(current)
            if (result.processed) processed = true
            current = result.block ?: return SandboxProcessedStructureBlock(block = null, processed = true)
        }
        return SandboxProcessedStructureBlock(current, processed)
    }

    private fun parseTemplatePlacement(
        extras: List<String>,
        payload: JsonObject,
        location: SourceLocation?,
    ): SandboxStructurePlacement {
        val rotation = extras.getOrNull(0) ?: "none"
        val mirror = extras.getOrNull(1) ?: "none"
        val integrity = extras.getOrNull(2)?.let { parseDouble(it, "template integrity", location).coerceIn(0.0, 1.0) } ?: 1.0
        val seed = extras.getOrNull(3)?.let { parseLong(it, "template seed", location) }
        if (rotation !in SandboxStructurePlacement.rotations) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Unsupported template rotation '$rotation'", location)
        }
        if (mirror !in SandboxStructurePlacement.mirrors) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Unsupported template mirror '$mirror'", location)
        }
        payload.addProperty("rotation", rotation)
        payload.addProperty("mirror", mirror)
        payload.addProperty("integrity", integrity)
        seed?.let { payload.addProperty("seed", it) }
        return SandboxStructurePlacement(rotation = rotation, mirror = mirror, integrity = integrity, seed = seed)
    }

    private fun JsonObject.getAsJsonArrayOrNull(name: String): JsonArray? {
        val value = get(name) ?: return null
        if (!value.isJsonArray) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Structure field '$name' must be an array")
        }
        return value.asJsonArray
    }

    private fun JsonObject.getAsJsonObjectOrNull(name: String, location: SourceLocation? = null): JsonObject? {
        val value = get(name) ?: return null
        if (!value.isJsonObject) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "JSON field '$name' must be an object", location)
        }
        return value.asJsonObject
    }

    private fun JsonElement.asPlaceJsonObject(label: String, location: SourceLocation?): JsonObject {
        if (!isJsonObject) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must be an object", location)
        return asJsonObject
    }

    private fun JsonObject.requiredPlaceString(name: String, label: String, location: SourceLocation?): String =
        placeString(name, location) ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label is required", location)

    private fun JsonObject.placeString(name: String, location: SourceLocation?): String? {
        val value = get(name) ?: return null
        if (!value.isJsonPrimitive) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Structure field '$name' must be a primitive", location)
        return value.asJsonPrimitive.asString
    }

    private fun JsonObject.placeProperties(location: SourceLocation?): Map<String, String> {
        val value = get("properties") ?: return emptyMap()
        if (!value.isJsonObject) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Structure block properties must be an object", location)
        return value.asJsonObject.entrySet().associate { (key, property) ->
            if (!property.isJsonPrimitive) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Structure block property '$key' must be a primitive", location)
            key to property.asJsonPrimitive.asString
        }
    }

    private fun JsonObject.placeJsonObject(name: String, label: String, location: SourceLocation?): JsonObject? {
        val value = get(name) ?: return null
        if (!value.isJsonObject) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must be an object", location)
        return value.asJsonObject
    }

    private fun JsonObject.placeBlockPos(primary: String, secondary: String, label: String, location: SourceLocation?): BlockPos {
        val value = get(primary) ?: get(secondary) ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label is required", location)
        if (!value.isJsonArray) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must be [x,y,z]", location)
        val array = value.asJsonArray
        if (array.size() != 3) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must have exactly 3 entries", location)
        return BlockPos(array[0].placeInt("$label x", location), array[1].placeInt("$label y", location), array[2].placeInt("$label z", location))
    }

    private fun JsonObject.optionalPlacePosition(primary: String, secondary: String, label: String, location: SourceLocation?): Position {
        val value = get(primary) ?: get(secondary) ?: return Position.zero
        if (!value.isJsonArray) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must be [x,y,z]", location)
        val array = value.asJsonArray
        if (array.size() != 3) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must have exactly 3 entries", location)
        return Position(array[0].placeDouble("$label x", location), array[1].placeDouble("$label y", location), array[2].placeDouble("$label z", location))
    }

    private fun JsonObject.placeStringArray(name: String, label: String, location: SourceLocation?): List<String> {
        val value = get(name) ?: return emptyList()
        if (!value.isJsonArray) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must be an array", location)
        return value.asJsonArray.mapIndexed { index, element ->
            if (!element.isJsonPrimitive) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label entry $index must be a primitive", location)
            element.asJsonPrimitive.asString
        }
    }

    private fun JsonObject.placeDouble(name: String, default: Double, label: String, location: SourceLocation?): Double =
        get(name)?.placeDouble(label, location) ?: default

    private fun JsonElement.placeInt(label: String, location: SourceLocation?): Int =
        try {
            asInt
        } catch (error: Exception) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must be an integer", location, cause = error)
        }

    private fun JsonElement.placeDouble(label: String, location: SourceLocation?): Double =
        try {
            asDouble
        } catch (error: Exception) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must be a number", location, cause = error)
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
                val sourceIndex = if (tokens[5].text == "loot") 5 else 6
                val count = if (sourceIndex == 6) parseInt(tokens[5].text, "loot replace count", location) else 1
                val items = parseLootSource(tokens, sourceIndex, context, location).take(count.coerceAtLeast(0))
                val entities = EntitySelectors.select(world, tokens[3].text, context, location)
                entities.forEach { entity ->
                    val item = items.firstOrNull() ?: ItemStack(ResourceLocation("minecraft", "air"), 0)
                    entityItemAccess(entity, tokens[4].text, location).set(item)
                }
                recordLootOutput(
                    "loot replace",
                    "entity",
                    entities.map { it.scoreHolder },
                    items,
                    JsonObject().also { payload ->
                        payload.addProperty("slot", tokens[4].text)
                        payload.addProperty("count", count)
                    },
                )
            }
            "block" -> {
                requireSize(tokens, 9, "loot replace block <pos> <slot> [count] loot <table>", location)
                val pos = parseBlockPos(tokens, 3, context, location)
                val slot = inventorySlot(tokens[6].text)
                val sourceIndex = if (tokens[7].text == "loot") 7 else 8
                val count = if (sourceIndex == 8) parseInt(tokens[7].text, "loot replace count", location) else 1
                val item = parseLootSource(tokens, sourceIndex, context, location).take(count.coerceAtLeast(0)).firstOrNull()
                replaceBlockItem(pos, slot, item, location)
                recordLootOutput(
                    "loot replace",
                    "block",
                    listOf(pos.toString()),
                    listOfNotNull(item),
                    JsonObject().also { payload ->
                        payload.addProperty("slot", tokens[6].text)
                        payload.addProperty("count", count)
                    },
                )
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
                val origin = parsePosition(tokens, index + 2, context, location)
                val tool = tokens.getOrNull(index + 5)?.text?.let { lootTool(it, location) }
                generateLootItems(ResourceLocation.parse(tokens[index + 1].text), ResourceLocation("minecraft", "fishing"), context, origin = origin, tool = tool)
            }
            "mine" -> {
                requireSizeFrom(tokens, index, 4, "loot source mine <pos> [tool]", location)
                val pos = parseBlockPos(tokens, index + 1, context, location)
                val block = world.block(pos) ?: return emptyList()
                val tool = tokens.getOrNull(index + 4)?.text?.let { lootTool(it, location) }
                val table = block.nbt.get("LootTable")?.takeIf { it.isJsonPrimitive }?.asString?.let(ResourceLocation::parse)
                if (table == null) {
                    listOf(ItemStack(block.id))
                } else {
                    generateLootItems(table, ResourceLocation("minecraft", "block"), context, origin = Position(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble()), tool = tool, blockState = block)
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
            "entity" -> {
                requireSizeFrom(tokens, index, 3, "loot source entity <table> <target>", location)
                val table = ResourceLocation.parse(tokens[index + 1].text)
                EntitySelectors.select(world, tokens[index + 2].text, context, location).flatMap { entity ->
                    generateLootItems(table, ResourceLocation("minecraft", "entity"), context, origin = entity.position, thisEntity = entity)
                }
            }
            "block" -> {
                requireSizeFrom(tokens, index, 5, "loot source block <table> <pos> [tool]", location)
                val table = ResourceLocation.parse(tokens[index + 1].text)
                val pos = parseBlockPos(tokens, index + 2, context, location)
                val block = world.block(pos) ?: return emptyList()
                val tool = tokens.getOrNull(index + 5)?.text?.let { lootTool(it, location) }
                generateLootItems(table, ResourceLocation("minecraft", "block"), context, origin = Position(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble()), tool = tool, blockState = block)
            }
            "equipment" -> {
                requireSizeFrom(tokens, index, 4, "loot source equipment <table> <target> <slot>", location)
                val table = ResourceLocation.parse(tokens[index + 1].text)
                EntitySelectors.select(world, tokens[index + 2].text, context, location).flatMap { entity ->
                    val tool = entityItemAccess(entity, tokens[index + 3].text, location).get()
                    generateLootItems(table, ResourceLocation("minecraft", "equipment"), context, origin = entity.position, thisEntity = entity, tool = tool)
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
        blockState: SandboxBlock? = null,
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
                    block = blockState?.id,
                    blockState = blockState,
                    weather = currentWeatherState(),
                ),
                seed = world.gameTime,
                tool = tool,
            ),
        ).items
    }

    private fun lootTool(raw: String, location: SourceLocation?): ItemStack =
        parseItemStackArgument(raw, 1, location)

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
        return item.toNbtJson()
    }

    private fun spawnLootItems(position: Position, items: List<ItemStack>, dimension: ResourceLocation) {
        items.forEach { item ->
            world.entities += SandboxEntity(
                type = ResourceLocation("minecraft", "item"),
                position = position,
                dimension = dimension,
                nbt = JsonObject().also { it.add("Item", blockItemJson(item)) },
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
            payload.addProperty("behavior", behaviorLevel.id)
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
        val before = world.difficulty
        tokens.getOrNull(1)?.let {
            val difficulty = normalizeDifficulty(it.text, location)
            world.difficulty = difficulty
        }
        world.recordOutput(
            "difficulty",
            "data",
            text = world.difficulty,
            payload = JsonObject().also { payload ->
                payload.addProperty("before", before)
                payload.addProperty("after", world.difficulty)
                payload.addProperty("changed", before != world.difficulty)
            },
        )
    }

    private fun executeDefaultGameMode(tokens: List<CommandToken>, location: SourceLocation?) {
        requireSize(tokens, 2, "defaultgamemode <mode>", location)
        val before = world.defaultGameMode
        world.defaultGameMode = normalizeGameMode(tokens[1].text, location)
        world.recordOutput(
            "defaultgamemode",
            "data",
            text = world.defaultGameMode,
            payload = JsonObject().also { payload ->
                payload.addProperty("before", before)
                payload.addProperty("after", world.defaultGameMode)
                payload.addProperty("changed", before != world.defaultGameMode)
            },
        )
    }

    private fun executeGameMode(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 2, "gamemode <mode> [targets]", location)
        val mode = normalizeGameMode(tokens[1].text, location)
        val targets = tokens.getOrNull(2)?.text?.let { resolvePlayers(it, location, context) }
            ?: listOf(context.entity as? SandboxPlayer ?: throw SandboxException(DiagnosticCode.COMMAND_ERROR, "gamemode without targets requires a player execution context", location))
        val changes = targets.map { player -> player to player.gameMode }
        targets.forEach { it.gameMode = mode }
        world.recordOutput(
            "gamemode",
            "data",
            targets = targets.map { it.name },
            text = targets.size.toString(),
            payload = JsonObject().also { payload ->
                payload.addProperty("mode", mode)
                payload.addProperty("count", targets.size)
                payload.add(
                    "players",
                    JsonArray().also { players ->
                        changes.forEach { (player, before) ->
                            players.add(
                                JsonObject().also { entry ->
                                    entry.addProperty("player", player.name)
                                    entry.addProperty("uuid", player.uuid)
                                    entry.addProperty("before", before)
                                    entry.addProperty("after", player.gameMode)
                                    entry.addProperty("changed", before != player.gameMode)
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    private fun executeAttribute(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 4, "attribute <target> <attribute> <get|base|modifier> ...", location)
        val entity = EntitySelectors.select(world, tokens[1].text, context, location).singleOrNull()
            ?: throw SandboxException(DiagnosticCode.COMMAND_ERROR, "attribute requires exactly one target", location)
        val attribute = ResourceLocation.parse(tokens[2].text)
        when (tokens[3].text) {
            "get" -> {
                val scale = tokens.getOrNull(4)?.text?.let { parseDouble(it, "attribute scale", location) } ?: 1.0
                recordAttributeOutput("attribute get", entity, attribute, "total", scale)
            }
            "base" -> {
                requireSize(tokens, 5, "attribute <target> <attribute> base <get|set|reset>", location)
                when (tokens[4].text) {
                    "get" -> {
                        val scale = tokens.getOrNull(5)?.text?.let { parseDouble(it, "attribute scale", location) } ?: 1.0
                        recordAttributeOutput("attribute base get", entity, attribute, "base", scale)
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
                requireSize(tokens, 5, "attribute <target> <attribute> modifier <add|remove|value>", location)
                when (tokens[4].text) {
                    "add" -> {
                        requireSize(tokens, 8, "attribute <target> <attribute> modifier add <id> <value> <operation>", location)
                        val modifier = AttributeModifier(
                            id = ResourceLocation.parse(tokens[5].text),
                            amount = parseDouble(tokens[6].text, "attribute modifier value", location),
                            operation = normalizeAttributeModifierOperation(tokens[7].text, location),
                        )
                        entity.attributeModifiers.getOrPut(attribute) { linkedMapOf() }[modifier.id] = modifier
                        recordAttributeModifierOutput("attribute modifier add", entity, attribute, modifier, attributeTotal(entity, attribute))
                    }
                    "remove" -> {
                        requireSize(tokens, 6, "attribute <target> <attribute> modifier remove <id>", location)
                        val id = ResourceLocation.parse(tokens[5].text)
                        val removed = entity.attributeModifiers[attribute]?.remove(id)
                        if (entity.attributeModifiers[attribute]?.isEmpty() == true) {
                            entity.attributeModifiers.remove(attribute)
                        }
                        recordAttributeModifierOutput("attribute modifier remove", entity, attribute, removed, attributeTotal(entity, attribute), id)
                    }
                    "value" -> {
                        requireSize(tokens, 7, "attribute <target> <attribute> modifier value get <id> [scale]", location)
                        if (tokens[5].text != "get") {
                            unsupportedFeature("Unsupported attribute modifier value action '${tokens[5].text}'", profile.id, location)
                        }
                        val id = ResourceLocation.parse(tokens[6].text)
                        val modifier = entity.attributeModifiers[attribute]?.get(id)
                            ?: throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Attribute modifier '$id' was not found for '$attribute'", location)
                        val scale = tokens.getOrNull(7)?.text?.let { parseDouble(it, "attribute modifier scale", location) } ?: 1.0
                        recordAttributeModifierValueOutput(entity, attribute, modifier, scale)
                    }
                    else -> unsupportedFeature("Unsupported attribute modifier action '${tokens[4].text}'", profile.id, location)
                }
            }
            else -> unsupportedFeature("Unsupported attribute action '${tokens[3].text}'", profile.id, location)
        }
    }

    private fun recordAttributeOutput(
        command: String,
        entity: SandboxEntity,
        attribute: ResourceLocation,
        field: String,
        scale: Double,
    ) {
        val rawValue = if (field == "total") attributeTotal(entity, attribute) else entity.attributes[attribute] ?: defaultAttribute(attribute)
        val value = rawValue * scale
        world.recordOutput(
            command,
            "data",
            text = value.toString(),
            payload = JsonObject().also { payload ->
                payload.addProperty("target", entity.scoreHolder)
                payload.addProperty("attribute", attribute.toString())
                payload.addProperty("field", field)
                payload.addProperty("scale", scale)
                payload.addProperty("rawValue", rawValue)
                payload.addProperty("value", value)
            },
        )
    }

    private fun recordAttributeModifierOutput(
        command: String,
        entity: SandboxEntity,
        attribute: ResourceLocation,
        modifier: AttributeModifier?,
        total: Double,
        id: ResourceLocation? = modifier?.id,
    ) {
        world.recordOutput(
            command,
            "data",
            targets = listOf(entity.scoreHolder),
            text = if (modifier == null) "0" else "1",
            payload = JsonObject().also { payload ->
                payload.addProperty("target", entity.scoreHolder)
                payload.addProperty("attribute", attribute.toString())
                id?.let { payload.addProperty("modifier", it.toString()) }
                payload.addProperty("changed", modifier != null)
                payload.addProperty("total", total)
                modifier?.let {
                    payload.addProperty("amount", it.amount)
                    payload.addProperty("operation", it.operation)
                }
            },
        )
    }

    private fun recordAttributeModifierValueOutput(
        entity: SandboxEntity,
        attribute: ResourceLocation,
        modifier: AttributeModifier,
        scale: Double,
    ) {
        val value = modifier.amount * scale
        world.recordOutput(
            "attribute modifier value get",
            "data",
            targets = listOf(entity.scoreHolder),
            text = value.toString(),
            payload = JsonObject().also { payload ->
                payload.addProperty("target", entity.scoreHolder)
                payload.addProperty("attribute", attribute.toString())
                payload.addProperty("modifier", modifier.id.toString())
                payload.addProperty("operation", modifier.operation)
                payload.addProperty("scale", scale)
                payload.addProperty("rawValue", modifier.amount)
                payload.addProperty("value", value)
            },
        )
    }

    private fun attributeTotal(entity: SandboxEntity, attribute: ResourceLocation): Double {
        val base = entity.attributes[attribute] ?: defaultAttribute(attribute)
        val modifiers = entity.attributeModifiers[attribute]?.values.orEmpty()
        val withBaseModifiers = base +
            modifiers.filter { it.operation == "add_value" }.sumOf { it.amount } +
            modifiers.filter { it.operation == "add_multiplied_base" }.sumOf { base * it.amount }
        return modifiers
            .filter { it.operation == "add_multiplied_total" }
            .fold(withBaseModifiers) { total, modifier -> total * (1.0 + modifier.amount) }
    }

    private fun normalizeAttributeModifierOperation(raw: String, location: SourceLocation?): String =
        when (raw) {
            "add_value", "addition" -> "add_value"
            "add_multiplied_base", "multiply_base" -> "add_multiplied_base"
            "add_multiplied_total", "multiply_total" -> "add_multiplied_total"
            else -> throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "Unsupported attribute modifier operation '$raw'; expected add_value, add_multiplied_base, or add_multiplied_total",
                location,
            )
        }

    private fun executeScoreboard(command: String, tokens: List<CommandToken>, location: SourceLocation?) {
        requireSize(tokens, 3, "scoreboard <objectives|players> ...", location)
        when (tokens[1].text) {
            "objectives" -> executeObjectives(command, tokens, location)
            "players" -> executePlayers(tokens, location)
            else -> unsupportedFeature("Unsupported scoreboard subcommand '${tokens[1].text}'", profile.id, location)
        }
    }

    private fun executeObjectives(command: String, tokens: List<CommandToken>, location: SourceLocation?) {
        when (tokens[2].text) {
            "add" -> {
                requireSize(tokens, 5, "scoreboard objectives add <objective> <criteria>", location)
                world.addObjective(tokens[3].text, tokens[4].text)
            }
            "remove" -> {
                requireSize(tokens, 4, "scoreboard objectives remove <objective>", location)
                world.removeObjective(tokens[3].text)
            }
            "setdisplay" -> {
                requireSize(tokens, 4, "scoreboard objectives setdisplay <slot> [objective]", location)
                val slot = tokens[3].text
                val before = world.scoreboardDisplays[slot]
                val objective = tokens.getOrNull(4)?.text
                if (objective == null) {
                    world.scoreboardDisplays.remove(slot)
                } else {
                    world.ensureObjective(objective)
                    world.scoreboardDisplays[slot] = objective
                }
                recordScoreboardDisplay(slot, objective, before)
            }
            "modify" -> executeObjectiveModify(command, tokens, location)
            "list" -> recordObjectiveList()
            else -> unsupportedFeature("Unsupported scoreboard objectives action '${tokens[2].text}'", profile.id, location)
        }
    }

    private fun executeObjectiveModify(command: String, tokens: List<CommandToken>, location: SourceLocation?) {
        requireSize(tokens, 6, "scoreboard objectives modify <objective> <displayname|rendertype|displayautoupdate> <value>", location)
        val objective = tokens[3].text
        world.ensureObjective(objective)
        val metadata = world.scoreboardObjectiveMetadata.getOrPut(objective) { ScoreboardObjectiveMetadata() }
        val field = when (tokens[4].text.lowercase()) {
            "displayname" -> "displayName"
            "rendertype" -> "renderType"
            "displayautoupdate" -> "displayAutoUpdate"
            else -> unsupportedFeature("Unsupported scoreboard objective field '${tokens[4].text}'", profile.id, location)
        }
        val before = when (field) {
            "displayName" -> JsonPrimitive(metadata.displayName ?: objective)
            "renderType" -> JsonPrimitive(metadata.renderType)
            "displayAutoUpdate" -> JsonPrimitive(metadata.displayAutoUpdate)
            else -> JsonPrimitive("")
        }
        val value = when (field) {
            "displayName" -> JsonPrimitive(CommandTokenizer.tailFrom(command, tokens[5]))
            "renderType" -> {
                val renderType = tokens[5].text
                if (renderType !in setOf("integer", "hearts")) {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "scoreboard objective render type must be integer or hearts", location)
                }
                JsonPrimitive(renderType)
            }
            "displayAutoUpdate" -> JsonPrimitive(parseBoolean(tokens[5].text, "scoreboard objective displayAutoUpdate", location))
            else -> JsonPrimitive("")
        }
        when (field) {
            "displayName" -> metadata.displayName = value.asString
            "renderType" -> metadata.renderType = value.asString
            "displayAutoUpdate" -> metadata.displayAutoUpdate = value.asBoolean
        }
        recordScoreboardObjectiveModify(objective, field, value, before)
    }

    private fun recordScoreboardObjectiveModify(objective: String, field: String, value: JsonElement, before: JsonElement) {
        world.recordOutput(
            "scoreboard objectives modify",
            "data",
            text = "$objective $field=${JsonValues.render(value).trim()}",
            payload = JsonObject().also { payload ->
                payload.addProperty("objective", objective)
                payload.addProperty("field", field)
                payload.add("value", value)
                payload.add("previous", before)
            },
        )
    }

    private fun recordScoreboardDisplay(slot: String, objective: String?, before: String?) {
        world.recordOutput(
            "scoreboard objectives setdisplay",
            "data",
            text = objective?.let { "$slot=$it" } ?: "cleared $slot",
            payload = JsonObject().also { payload ->
                payload.addProperty("slot", slot)
                objective?.let { payload.addProperty("objective", it) }
                before?.let { payload.addProperty("previous", it) }
                payload.addProperty("cleared", objective == null)
            },
        )
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
                val objective = tokens[4].text
                scoreTargets(tokens[3].text, ExecutionContext(), location).forEach { target ->
                    val value = world.getScore(target, objective)
                    world.recordOutput(
                        "scoreboard players get",
                        "data",
                        text = value.toString(),
                        payload = JsonObject().also { payload ->
                            payload.addProperty("target", target)
                            payload.addProperty("objective", objective)
                            payload.addProperty("value", value)
                        },
                    )
                }
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
        fun recordBossbarMutation(
            command: String,
            action: String,
            id: ResourceLocation,
            text: String,
            bar: SandboxBossbar? = null,
            before: JsonObject? = null,
            extra: JsonObject.() -> Unit = {},
        ) {
            world.recordOutput(
                command,
                "data",
                text = text,
                payload = (bar?.toJson() ?: JsonObject().also { it.addProperty("id", id.toString()) }).also { payload ->
                    payload.addProperty("action", action)
                    before?.let { payload.add("before", it) }
                    payload.extra()
                },
            )
        }
        when (tokens[1].text) {
            "add" -> {
                requireSize(tokens, 4, "bossbar add <id> <name>", location)
                val id = ResourceLocation.parse(tokens[2].text)
                val before = world.bossbars[id]?.toJson()
                val bar = SandboxBossbar(id, CommandTokenizer.tailFrom(command, tokens[3]))
                world.bossbars[id] = bar
                recordBossbarMutation("bossbar add", "add", id, id.toString(), bar, before) {
                    addProperty("replaced", before != null)
                }
            }
            "remove" -> {
                requireSize(tokens, 3, "bossbar remove <id>", location)
                val id = ResourceLocation.parse(tokens[2].text)
                val removed = world.bossbars.remove(id)
                recordBossbarMutation("bossbar remove", "remove", id, (removed != null).toString(), before = removed?.toJson()) {
                    addProperty("removed", removed != null)
                }
            }
            "list" -> world.recordOutput("bossbar list", "data", text = world.bossbars.keys.sorted().joinToString())
            "get" -> {
                requireSize(tokens, 4, "bossbar get <id> <field>", location)
                val id = ResourceLocation.parse(tokens[2].text)
                val bar = world.bossbars[id] ?: throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Bossbar '${tokens[2].text}' does not exist", location)
                val field = tokens[3].text
                val value = when (field) {
                    "value" -> JsonPrimitive(bar.value)
                    "max" -> JsonPrimitive(bar.max)
                    "visible" -> JsonPrimitive(bar.visible)
                    "players" -> JsonArray().also { array -> bar.players.forEach { array.add(it) } }
                    else -> bar.toJson().get(field)?.deepCopy() ?: JsonPrimitive("")
                }
                world.recordOutput(
                    "bossbar get",
                    "data",
                    text = if (value.isJsonPrimitive) value.asJsonPrimitive.asString else JsonValues.render(value),
                    payload = JsonObject().also { payload ->
                        payload.addProperty("id", id.toString())
                        payload.addProperty("field", field)
                        payload.add("value", value)
                    },
                )
            }
            "set" -> {
                requireSize(tokens, 5, "bossbar set <id> <field> <value>", location)
                val id = ResourceLocation.parse(tokens[2].text)
                val bar = world.bossbars[id] ?: throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Bossbar '${tokens[2].text}' does not exist", location)
                val before = bar.toJson()
                val field = tokens[3].text
                val value = when (field) {
                    "name" -> JsonPrimitive(CommandTokenizer.tailFrom(command, tokens[4]))
                    "value" -> JsonPrimitive(parseInt(tokens[4].text, "bossbar value", location))
                    "max" -> JsonPrimitive(parseInt(tokens[4].text, "bossbar max", location).coerceAtLeast(1))
                    "color" -> JsonPrimitive(tokens[4].text)
                    "style" -> JsonPrimitive(tokens[4].text)
                    "visible" -> JsonPrimitive(parseBoolean(tokens[4].text, "bossbar visible", location))
                    "players" -> {
                        JsonArray().also { array ->
                            if (tokens.size > 4) resolvePlayers(tokens[4].text, location, context).map { it.name }.forEach { array.add(it) }
                        }
                    }
                    else -> unsupportedFeature("Unsupported bossbar field '${tokens[3].text}'", profile.id, location)
                }
                when (field) {
                    "name" -> bar.name = value.asString
                    "value" -> bar.value = value.asInt
                    "max" -> bar.max = value.asInt
                    "color" -> bar.color = value.asString
                    "style" -> bar.style = value.asString
                    "visible" -> bar.visible = value.asBoolean
                    "players" -> {
                        bar.players.clear()
                        value.asJsonArray.forEach { bar.players.add(it.asString) }
                    }
                }
                recordBossbarMutation(
                    "bossbar set",
                    "set",
                    id,
                    if (value.isJsonPrimitive) value.asJsonPrimitive.asString else JsonValues.render(value),
                    bar,
                    before,
                ) {
                    addProperty("field", field)
                    add("fieldValue", value)
                }
            }
            else -> unsupportedFeature("Unsupported bossbar action '${tokens[1].text}'", profile.id, location)
        }
    }

    private fun executeGamerule(tokens: List<CommandToken>, location: SourceLocation?) {
        requireSize(tokens, 2, "gamerule <rule> [value]", location)
        val rule = tokens[1].text
        if (tokens.size >= 3) {
            val before = world.gamerules[rule]
            val value = tokens[2].text
            world.gamerules[rule] = value
            world.recordOutput(
                "gamerule set",
                "data",
                text = value,
                payload = JsonObject().also { payload ->
                    payload.addProperty("action", "set")
                    payload.addProperty("rule", rule)
                    payload.addProperty("value", value)
                    payload.addProperty("beforeExists", before != null)
                    before?.let { payload.addProperty("beforeValue", it) }
                },
            )
        } else {
            val value = world.gamerules[rule]
            world.recordOutput(
                "gamerule",
                "data",
                text = value ?: "<unset>",
                payload = JsonObject().also { payload ->
                    payload.addProperty("rule", rule)
                    payload.addProperty("exists", value != null)
                    value?.let { payload.addProperty("value", it) }
                },
            )
        }
    }

    private fun executeTime(tokens: List<CommandToken>, location: SourceLocation?) {
        requireSize(tokens, 2, "time <set|add|query> ...", location)
        when (tokens[1].text) {
            "set" -> {
                requireSize(tokens, 3, "time set <value|day|noon|night|midnight>", location)
                val before = world.dayTime
                val value = parseTimeOfDay(tokens[2].text, location)
                world.setDayTime(value)
                recordTimeMutationOutput("time set", tokens[2].text, before)
            }
            "add" -> {
                requireSize(tokens, 3, "time add <time>", location)
                val before = world.dayTime
                world.addDayTime(parseLong(tokens[2].text, "time delta", location))
                recordTimeMutationOutput("time add", tokens[2].text, before)
            }
            "query" -> {
                requireSize(tokens, 3, "time query <daytime|gametime|day>", location)
                val query = tokens[2].text
                val value = when (query) {
                    "daytime" -> world.dayTime
                    "gametime" -> world.gameTime
                    "day" -> world.gameTime / 24000
                    else -> unsupportedFeature("Unsupported time query '$query'", profile.id, location)
                }
                world.recordOutput(
                    "time query",
                    "data",
                    text = value.toString(),
                    payload = JsonObject().also { payload ->
                        payload.addProperty("query", query)
                        payload.addProperty("value", value)
                    },
                )
            }
            else -> unsupportedFeature("Unsupported time action '${tokens[1].text}'", profile.id, location)
        }
    }

    private fun recordTimeMutationOutput(command: String, argument: String, beforeDayTime: Long) {
        world.recordOutput(
            command,
            "data",
            text = world.dayTime.toString(),
            payload = JsonObject().also { payload ->
                payload.addProperty("argument", argument)
                payload.addProperty("beforeDayTime", beforeDayTime)
                payload.addProperty("afterDayTime", world.dayTime)
                payload.addProperty("gameTime", world.gameTime)
            },
        )
    }

    private fun executeWeather(tokens: List<CommandToken>, location: SourceLocation?) {
        requireSize(tokens, 2, "weather <clear|rain|thunder> [duration]", location)
        val weather = tokens[1].text
        if (weather !in setOf("clear", "rain", "thunder")) {
            unsupportedFeature("Unsupported weather '${tokens[1].text}'", profile.id, location)
        }
        world.weather = weather
        world.weatherDuration = tokens.getOrNull(2)?.text?.let { parseInt(it, "weather duration", location) } ?: 0
        world.recordOutput(
            "weather",
            "data",
            text = weather,
            payload = JsonObject().also { payload ->
                payload.addProperty("weather", world.weather)
                payload.addProperty("duration", world.weatherDuration)
                payload.addProperty("raining", world.weather == "rain" || world.weather == "thunder")
                payload.addProperty("thundering", world.weather == "thunder")
            },
        )
    }

    private fun executeRandom(tokens: List<CommandToken>, location: SourceLocation?) {
        requireSize(tokens, 2, "random <value|roll|reset> ...", location)
        when (tokens[1].text) {
            "value", "roll" -> {
                requireSize(tokens, 3, "random ${tokens[1].text} <range> [sequence]", location)
                val (min, max) = parseIntRange(tokens[2].text, location)
                val sequence = tokens.getOrNull(3)?.text ?: "default"
                val stateBefore = world.randomSequences.getOrPut(sequence) { randomSequenceSeed(sequence) }
                val random = Random(stateBefore)
                val value = if (max <= min) min else random.nextInt(min, max + 1)
                val stateAfter = random.nextLong()
                world.randomSequences[sequence] = stateAfter
                world.recordOutput(
                    "random ${tokens[1].text}",
                    "data",
                    targets = listOf(sequence),
                    text = value.toString(),
                    payload = JsonObject().also { payload ->
                        payload.addProperty("action", tokens[1].text)
                        payload.addProperty("range", tokens[2].text)
                        payload.addProperty("min", min)
                        payload.addProperty("max", max)
                        payload.addProperty("sequence", sequence)
                        payload.addProperty("worldSeed", world.seed)
                        payload.addProperty("gameTime", world.gameTime)
                        payload.addProperty("sequenceStateBefore", stateBefore)
                        payload.addProperty("sequenceStateAfter", stateAfter)
                        payload.addProperty("value", value)
                    },
                )
            }
            "reset" -> {
                tokens.getOrNull(2)?.text?.let { sequence ->
                    val before = world.randomSequences[sequence]
                    val explicitSeed = tokens.getOrNull(3)?.let { parseLong(it.text, "random seed", location) }
                    val seed = explicitSeed ?: randomSequenceSeed(sequence)
                    world.randomSequences[sequence] = seed
                    world.recordOutput(
                        "random reset",
                        "data",
                        targets = listOf(sequence),
                        text = seed.toString(),
                        payload = JsonObject().also { payload ->
                            payload.addProperty("action", "reset")
                            payload.addProperty("sequence", sequence)
                            payload.addProperty("seed", seed)
                            payload.addProperty("explicitSeed", explicitSeed != null)
                            payload.addProperty("hadPrevious", before != null)
                            before?.let { payload.addProperty("previousState", it) }
                            payload.addProperty("worldSeed", world.seed)
                            payload.addProperty("gameTime", world.gameTime)
                        },
                    )
                } ?: run {
                    val sequences = world.randomSequences.keys.sorted()
                    world.randomSequences.clear()
                    world.recordOutput(
                        "random reset",
                        "data",
                        text = sequences.size.toString(),
                        payload = JsonObject().also { payload ->
                            payload.addProperty("action", "reset")
                            payload.addProperty("cleared", sequences.size)
                            payload.add("sequences", JsonArray().also { array -> sequences.forEach { array.add(it) } })
                        },
                    )
                }
            }
            else -> unsupportedFeature("Unsupported random action '${tokens[1].text}'", profile.id, location)
        }
    }

    private fun randomSequenceSeed(sequence: String): Long =
        world.seed xor world.gameTime xor sequence.hashCode().toLong()

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

    private fun executeExecute(command: String, tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext): Boolean {
        var index = 1
        var contexts = listOf(context)

        while (index < tokens.size) {
            when (tokens[index].text) {
                "run" -> {
                    val rest = CommandTokenizer.tailAfter(command, tokens[index])
                    return contexts.map { executeOne(rest, location, it) }.any { it }
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
                            ctx.copy(position = it.position, dimension = entityDimension(it), yaw = it.yaw, pitch = it.pitch)
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
                            ctx.copy(position = parsePosition(tokens, index + 1, ctx, location))
                        }
                        index += 4
                    }
                }
                "align" -> {
                    requireIndex(tokens, index + 1, "execute align <axes>", location)
                    val axes = tokens[index + 1].text
                    if (axes.isBlank() || axes.any { it !in setOf('x', 'y', 'z') } || axes.toSet().size != axes.length) {
                        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "execute align axes must be a unique combination of x, y, and z", location)
                    }
                    contexts = contexts.map { ctx ->
                        var pos = ctx.position
                        if ('x' in axes) pos = pos.copy(x = floor(pos.x))
                        if ('y' in axes) pos = pos.copy(y = floor(pos.y))
                        if ('z' in axes) pos = pos.copy(z = floor(pos.z))
                        ctx.copy(position = pos)
                    }
                    index += 2
                }
                "anchored" -> {
                    requireIndex(tokens, index + 1, "execute anchored <eyes|feet>", location)
                    val anchor = tokens[index + 1].text
                    if (anchor !in setOf("eyes", "feet")) {
                        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "execute anchored must be eyes or feet", location)
                    }
                    contexts = contexts.map { it.copy(anchor = anchor) }
                    index += 2
                }
                "facing" -> {
                    requireIndex(tokens, index + 1, "execute facing <pos|entity>", location)
                    if (tokens[index + 1].text == "entity") {
                        requireSizeFrom(tokens, index, 4, "execute facing entity <target> <eyes|feet>", location)
                        val anchor = tokens[index + 3].text
                        contexts = contexts.flatMap { ctx ->
                            EntitySelectors.select(world, tokens[index + 2].text, ctx, location).map { target ->
                                ctx.facing(facingPosition(target, anchor, location))
                            }
                        }
                    } else {
                        requireSizeFrom(tokens, index, 4, "execute facing <x> <y> <z>", location)
                        contexts = contexts.map { ctx ->
                            ctx.facing(parsePosition(tokens, index + 1, ctx, location))
                        }
                    }
                    index += 4
                }
                "in" -> {
                    requireIndex(tokens, index + 1, "execute in <dimension>", location)
                    contexts = contexts.map { ctx -> ctx.copy(dimension = ResourceLocation.parse(tokens[index + 1].text)) }
                    index += 2
                }
                "rotated" -> {
                    requireIndex(tokens, index + 1, "execute rotated <yaw pitch>|as <target>", location)
                    if (tokens[index + 1].text == "as") {
                        requireIndex(tokens, index + 2, "execute rotated as <target>", location)
                        contexts = contexts.flatMap { ctx ->
                            EntitySelectors.select(world, tokens[index + 2].text, ctx, location).map {
                                ctx.copy(yaw = it.yaw, pitch = it.pitch)
                            }
                        }
                    } else {
                        requireSizeFrom(tokens, index, 3, "execute rotated <yaw> <pitch>", location)
                        contexts = contexts.map { ctx ->
                            ctx.copy(
                                yaw = parseRotation(tokens[index + 1].text, ctx.yaw, "yaw", location),
                                pitch = parseRotation(tokens[index + 2].text, ctx.pitch, "pitch", location),
                            )
                        }
                    }
                    index += 3
                }
                "store" -> {
                    val runIndex = tokens.indexOfFirst { it.text == "run" }
                    if (runIndex < 0) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "execute store requires run", location)
                    val commandsBefore = commandsExecuted
                    val outputsBefore = world.outputs.size
                    val rest = CommandTokenizer.tailAfter(command, tokens[runIndex])
                    lastFunctionReturnValue = null
                    val commandSuccess = contexts.map { executeOne(rest, location, it) }.any { it }
                    val commandsRun = (commandsExecuted - commandsBefore).coerceAtLeast(0)
                    val stored = executeStoreValue(
                        tokens.getOrNull(index + 1)?.text ?: "result",
                        commandsRun,
                        outputsBefore,
                        lastFunctionReturnValue,
                        commandSuccess,
                    )
                    storeExecuteValue(tokens, index + 1, stored, location, contexts.firstOrNull() ?: context)
                    return commandSuccess
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
        return contexts.isNotEmpty()
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
                val pos = parseBlockPos(tokens, index + 1, context, location)
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
                val pos = parseBlockPos(tokens, index + 1, context, location)
                (world.biomes[pos] == ResourceLocation.parse(tokens[index + 4].text)) to index + 5
            }
            "loaded" -> {
                requireSizeFrom(tokens, index, 4, "execute if|unless loaded <pos>", location)
                val pos = parseBlockPos(tokens, index + 1, context, location)
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
        entity.dimension

    private fun evaluateBlocksCondition(tokens: List<CommandToken>, index: Int, location: SourceLocation?, context: ExecutionContext): Pair<Boolean, Int> {
        requireSizeFrom(tokens, index, 11, "execute if|unless blocks <begin> <end> <destination> <all|masked>", location)
        val from = parseBlockPos(tokens, index + 1, context, location)
        val to = parseBlockPos(tokens, index + 4, context, location)
        val dest = parseBlockPos(tokens, index + 7, context, location)
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
        val before = dataTargetNbtValues(target, location).map { it.deepCopy() }
        mutateDataTarget(target, location) { targetNbt ->
            when (mutation) {
                "set" -> JsonPaths.set(targetNbt, path, value)
                "merge" -> JsonPaths.merge(targetNbt, path, value)
                "append" -> JsonPaths.append(targetNbt, path, value, location)
                "prepend" -> JsonPaths.prepend(targetNbt, path, value, location)
                "insert" -> JsonPaths.insert(targetNbt, path, insertIndex ?: 0, value, location)
            }
        }
        val after = dataTargetNbtValues(target, location).map { it.deepCopy() }
        recordDataMutationOutput(
            "data modify",
            target,
            value,
            before,
            after,
            JsonObject().also { details ->
                details.addProperty("operation", mutation)
                details.addProperty("path", path)
                insertIndex?.let { details.addProperty("insertIndex", it) }
            },
        )
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
        val before = dataTargetNbtValues(target, location).map { it.deepCopy() }
        mutateDataTarget(target, location) { JsonPaths.merge(it, null, value) }
        val after = dataTargetNbtValues(target, location).map { it.deepCopy() }
        recordDataMutationOutput("data merge", target, value, before, after)
    }

    private fun executeDataGet(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext): JsonElement? {
        requireSize(tokens, 3, "data get <storage|entity|block> <target> [path] [scale]", location)
        val (target, pathIndex) = parseDataTarget(tokens, 2, context, location)
        val path = tokens.getOrNull(pathIndex)?.text
        val value = dataTargetNbtValues(target, location).firstOrNull()?.let { JsonPaths.get(it, path) }
        val scale = if (path != null) tokens.getOrNull(pathIndex + 1)?.text?.let { parseDouble(it, "data get scale", location) } else null
        return if (scale == null) value else value?.let { scaleDataGetResult(it, scale, location) }
    }

    private fun scaleDataGetResult(value: JsonElement, scale: Double, location: SourceLocation?): JsonPrimitive {
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) {
            throw SandboxException(DiagnosticCode.COMMAND_ERROR, "data get scale requires a numeric value", location)
        }
        return JsonPrimitive(value.asDouble * scale)
    }

    private fun executeDataRemove(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 4, "data remove <storage|entity|block> <target> <path>", location)
        val (target, pathIndex) = parseDataTarget(tokens, 2, context, location)
        requireIndex(tokens, pathIndex, "data remove <target> <path>", location)
        val path = tokens[pathIndex].text
        val before = dataTargetNbtValues(target, location).map { it.deepCopy() }
        mutateDataTarget(target, location) { JsonPaths.remove(it, path) }
        val after = dataTargetNbtValues(target, location).map { it.deepCopy() }
        recordDataMutationOutput(
            "data remove",
            target,
            null,
            before,
            after,
            JsonObject().also { details ->
                details.addProperty("operation", "remove")
                details.addProperty("path", path)
            },
        )
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
                DataTargetSpec.Block(parseBlockPos(tokens, index + 1, context, location)) to index + 4
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

    private fun recordDataMutationOutput(
        command: String,
        target: DataTargetSpec,
        value: JsonElement?,
        before: List<JsonObject>,
        after: List<JsonObject>,
        details: JsonObject? = null,
    ) {
        val targetNames = dataTargetNames(target)
        val changed = before.zip(after).count { (beforeValue, afterValue) -> beforeValue != afterValue }
        world.recordOutput(
            command,
            "data",
            targets = targetNames,
            text = changed.toString(),
            payload = JsonObject().also { payload ->
                payload.addProperty("targetKind", dataTargetKind(target))
                payload.addProperty("count", after.size)
                payload.addProperty("changed", changed)
                payload.add("targets", JsonArray().also { array -> targetNames.forEach { array.add(it) } })
                value?.let { payload.add("value", it.deepCopy()) }
                details?.let { payload.add("details", it) }
                payload.add(
                    "results",
                    JsonArray().also { results ->
                        after.forEachIndexed { index, afterValue ->
                            val beforeValue = before.getOrNull(index)
                            results.add(
                                JsonObject().also { entry ->
                                    entry.addProperty("target", targetNames.getOrElse(index) { index.toString() })
                                    beforeValue?.let { entry.add("before", it.deepCopy()) }
                                    entry.add("after", afterValue.deepCopy())
                                    entry.addProperty("changed", beforeValue != afterValue)
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    private fun dataTargetKind(target: DataTargetSpec): String =
        when (target) {
            is DataTargetSpec.Storage -> "storage"
            is DataTargetSpec.Entities -> "entity"
            is DataTargetSpec.Block -> "block"
        }

    private fun dataTargetNames(target: DataTargetSpec): List<String> =
        when (target) {
            is DataTargetSpec.Storage -> listOf(target.id.toString())
            is DataTargetSpec.Entities -> target.entities.map { it.scoreHolder }
            is DataTargetSpec.Block -> listOf(target.pos.toString())
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
        val item = tokens.getOrNull(2)?.text?.let { parseItemStackArgument(it, 1, location) }
        val maxCount = tokens.getOrNull(3)?.text?.let { parseInt(it, "clear max count", location) } ?: Int.MAX_VALUE
        if (maxCount < 0) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "clear max count must be non-negative", location)
        }
        val queryOnly = maxCount == 0
        var matched = 0
        var removed = 0
        targets.forEach { player ->
            var remaining = maxCount
            val iterator = player.inventory.listIterator()
            while (iterator.hasNext()) {
                val stack = iterator.next()
                if (item == null || stack.matchesClearItem(item)) {
                    matched += stack.count
                    if (!queryOnly && remaining > 0) {
                        val removedFromStack = minOf(stack.count, remaining)
                        stack.count -= removedFromStack
                        remaining -= removedFromStack
                        removed += removedFromStack
                        if (stack.count <= 0) iterator.remove()
                    }
                }
            }
        }
        val result = if (queryOnly) matched else removed
        world.recordOutput(
            "clear",
            "data",
            text = result.toString(),
            payload = JsonObject().also { payload ->
                payload.add("targets", JsonArray().also { array -> targets.forEach { array.add(it.name) } })
                item?.let { payload.addProperty("item", it.id.toString()) }
                payload.addProperty("maxCount", maxCount)
                payload.addProperty("query", queryOnly)
                payload.addProperty("matched", matched)
                payload.addProperty("removed", removed)
            },
        )
    }

    private fun executeGive(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 3, "give <targets> <item> [count]", location)
        val count = tokens.getOrNull(3)?.text?.let { parseInt(it, "item count", location) } ?: 1
        val item = parseItemStackArgument(tokens[2].text, count.coerceAtLeast(0), location)
        val players = resolvePlayers(tokens[1].text, location, context)
        players.forEach { player ->
            player.inventory += item.copyStack()
            advancements.handle(PlayerEvent(player.name, "inventory_changed", item = item.copyStack()))
        }
        world.recordOutput(
            "give",
            "data",
            targets = players.map { it.name },
            text = (item.count * players.size).toString(),
            payload = JsonObject().also { payload ->
                payload.add("targets", JsonArray().also { array -> players.forEach { array.add(it.name) } })
                payload.add("item", item.toJson())
                payload.addProperty("count", item.count)
                payload.addProperty("totalCount", item.count * players.size)
            },
        )
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
                val entities = EntitySelectors.select(world, tokens[2].text, context, location)
                val effectState = PlayerEffect(effect, duration, amplifier, hide)
                entities.forEach { entity ->
                    applyEffect(entity, effectState)
                }
                recordEffectOutput("effect give", entities, effectState)
            }
            "clear" -> {
                requireSize(tokens, 3, "effect clear <targets> [effect]", location)
                val effect = tokens.getOrNull(3)?.text?.let(ResourceLocation::parse)
                val entities = EntitySelectors.select(world, tokens[2].text, context, location)
                entities.forEach { entity ->
                    clearEffect(entity, effect)
                }
                recordEffectClearOutput(entities, effect)
            }
            else -> unsupportedFeature("Unsupported effect action '${tokens[1].text}'", profile.id, location)
        }
    }

    private fun applyEffect(entity: SandboxEntity, effect: PlayerEffect) {
        if (entity is SandboxPlayer) {
            entity.effects += effect.id
            entity.effectDetails[effect.id] = effect
            advancements.handle(PlayerEvent(entity.name, "effects_changed"))
        } else {
            entity.activeEffects[effect.id] = effect
        }
    }

    private fun clearEffect(entity: SandboxEntity, effect: ResourceLocation?) {
        if (entity is SandboxPlayer) {
            if (effect == null) {
                entity.effects.clear()
                entity.effectDetails.clear()
            } else {
                entity.effects -= effect
                entity.effectDetails.remove(effect)
            }
            advancements.handle(PlayerEvent(entity.name, "effects_changed"))
        } else if (effect == null) {
            entity.activeEffects.clear()
        } else {
            entity.activeEffects.remove(effect)
        }
    }

    private fun recordEffectOutput(command: String, entities: List<SandboxEntity>, effect: PlayerEffect) {
        world.recordOutput(
            command,
            "data",
            targets = entities.map { it.scoreHolder },
            text = entities.size.toString(),
            payload = JsonObject().also { payload ->
                payload.add("targets", JsonArray().also { array -> entities.forEach { array.add(it.scoreHolder) } })
                payload.add("effect", effect.toJson())
                payload.addProperty("count", entities.size)
            },
        )
    }

    private fun recordEffectClearOutput(entities: List<SandboxEntity>, effect: ResourceLocation?) {
        world.recordOutput(
            "effect clear",
            "data",
            targets = entities.map { it.scoreHolder },
            text = entities.size.toString(),
            payload = JsonObject().also { payload ->
                payload.add("targets", JsonArray().also { array -> entities.forEach { array.add(it.scoreHolder) } })
                effect?.let { payload.addProperty("effect", it.toString()) }
                payload.addProperty("all", effect == null)
                payload.addProperty("count", entities.size)
            },
        )
    }

    private fun executeEnchant(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 3, "enchant <targets> <enchantment> [level]", location)
        val enchantment = ResourceLocation.parse(tokens[2].text)
        val level = tokens.getOrNull(3)?.text?.let { parseInt(it, "enchantment level", location) } ?: 1
        val enchanted = mutableListOf<Pair<String, ItemStack>>()
        EntitySelectors.select(world, tokens[1].text, context, location).forEach { entity ->
            val item = if (entity is SandboxPlayer) {
                playerSelectedItemForEnchant(entity)
            } else {
                entity.equipment[EquipmentSlots.MAINHAND] ?: return@forEach
            }
            enchantItem(item, enchantment, level)
            enchanted += entity.scoreHolder to item.copyStack()
        }
        recordEnchantOutput(enchantment, level, enchanted, location)
    }

    private fun playerSelectedItemForEnchant(player: SandboxPlayer): ItemStack {
        while (player.inventory.size <= player.selectedSlot) {
            player.inventory += ItemStack(ResourceLocation("minecraft", "air"))
        }
        return player.inventory[player.selectedSlot]
    }

    private fun enchantItem(item: ItemStack, enchantment: ResourceLocation, level: Int) {
        val enchantments = item.components.getAsJsonObject("minecraft:enchantments") ?: JsonObject().also {
            item.components.add("minecraft:enchantments", it)
        }
        enchantments.addProperty(enchantment.toString(), level)
    }

    private fun recordEnchantOutput(enchantment: ResourceLocation, level: Int, enchanted: List<Pair<String, ItemStack>>, location: SourceLocation?) {
        world.recordOutput(
            "enchant",
            "data",
            targets = enchanted.map { it.first },
            text = enchanted.size.toString(),
            payload = JsonObject().also { payload ->
                payload.addProperty("enchantment", enchantment.toString())
                enchantmentPayload(enchantment, location)?.let { payload.add("enchantmentResource", it) }
                payload.addProperty("level", level)
                payload.addProperty("modified", enchanted.size)
                payload.add("items", JsonArray().also { items ->
                    enchanted.forEach { (target, item) ->
                        items.add(JsonObject().also { entry ->
                            entry.addProperty("target", target)
                            entry.add("item", item.toJson())
                        })
                    }
                })
            },
        )
    }

    private fun enchantmentPayload(id: ResourceLocation, location: SourceLocation?): JsonObject? {
        val resource = datapack.rawResources["enchantment"]?.get(id) ?: return null
        if (!resource.root.isJsonObject) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Enchantment resource '$id' must be a JSON object", location)
        }
        return JsonObject().also { payload ->
            payload.addProperty("id", id.toString())
            payload.addProperty("resource", resource.file)
            payload.addProperty("version", resource.version ?: profile.id)
            payload.add("definition", resource.root.deepCopy())
        }
    }

    private fun executeExperience(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 2, "${tokens[0].text} <add|set|query> ...", location)
        when (tokens[1].text) {
            "add" -> {
                requireSize(tokens, 4, "${tokens[0].text} add <targets> <amount> [points|levels]", location)
                val amount = parseInt(tokens[3].text, "experience amount", location)
                val kind = experienceKind(tokens.getOrNull(4)?.text ?: "points", tokens[0].text, location)
                val players = resolvePlayers(tokens[2].text, location, context)
                players.forEach { player ->
                    when (kind) {
                        "points" -> player.xp += amount
                        "levels" -> player.xpLevels += amount
                    }
                }
                recordExperienceOutput("${tokens[0].text} add", players, kind, amount)
            }
            "set" -> {
                requireSize(tokens, 4, "${tokens[0].text} set <targets> <amount> [points|levels]", location)
                val amount = parseInt(tokens[3].text, "experience amount", location)
                val kind = experienceKind(tokens.getOrNull(4)?.text ?: "points", tokens[0].text, location)
                val players = resolvePlayers(tokens[2].text, location, context)
                players.forEach { player ->
                    when (kind) {
                        "points" -> player.xp = amount
                        "levels" -> player.xpLevels = amount
                    }
                }
                recordExperienceOutput("${tokens[0].text} set", players, kind, amount)
            }
            "query" -> {
                requireSize(tokens, 4, "${tokens[0].text} query <target> <points|levels>", location)
                val player = resolvePlayers(tokens[2].text, location, context).first()
                val kind = experienceKind(tokens[3].text, tokens[0].text, location)
                val value = experienceValue(player, kind)
                world.recordOutput(
                    "${tokens[0].text} query",
                    "data",
                    text = value.toString(),
                    payload = JsonObject().also { payload ->
                        payload.addProperty("player", player.name)
                        payload.addProperty("kind", kind)
                        payload.addProperty("value", value)
                        payload.addProperty("points", player.xp)
                        payload.addProperty("levels", player.xpLevels)
                    },
                )
            }
            else -> unsupportedFeature("Unsupported ${tokens[0].text} action '${tokens[1].text}'", profile.id, location)
        }
    }

    private fun experienceKind(raw: String, command: String, location: SourceLocation?): String =
        when (raw) {
            "points", "levels" -> raw
            else -> unsupportedFeature("Unsupported $command experience type '$raw'", profile.id, location)
        }

    private fun experienceValue(player: SandboxPlayer, kind: String): Int =
        when (kind) {
            "points" -> player.xp
            "levels" -> player.xpLevels
            else -> player.xp
        }

    private fun recordExperienceOutput(command: String, players: List<SandboxPlayer>, kind: String, amount: Int) {
        world.recordOutput(
            command,
            "data",
            targets = players.map { it.name },
            text = amount.toString(),
            payload = JsonObject().also { payload ->
                payload.addProperty("kind", kind)
                payload.addProperty("amount", amount)
                payload.add("players", JsonArray().also { array ->
                    players.sortedBy { it.name }.forEach { player ->
                        array.add(
                            JsonObject().also { item ->
                                item.addProperty("name", player.name)
                                item.addProperty("points", player.xp)
                                item.addProperty("levels", player.xpLevels)
                            },
                        )
                    }
                })
            },
        )
    }

    private fun executeRecipe(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 4, "recipe <give|take> <targets> <recipe|*>", location)
        val targets = resolvePlayers(tokens[2].text, location, context)
        val recipe = tokens[3].text
        val recipeIds = if (recipe == "*") datapack.recipes.keys.sorted() else listOf(ResourceLocation.parse(recipe))
        var changed = 0
        val changedRecipes = sortedSetOf<ResourceLocation>()
        targets.forEach { player ->
            when (tokens[1].text) {
                "give" -> recipeIds.forEach { id ->
                    if (player.recipes.add(id)) {
                        changed += 1
                        changedRecipes += id
                        advancements.handle(PlayerEvent(player.name, "recipe_unlocked", recipe = id))
                    }
                }
                "take" -> {
                    if (recipe == "*") {
                        val removed = player.recipes.toList()
                        changed += removed.size
                        changedRecipes += removed
                        player.recipes.clear()
                    } else if (player.recipes.remove(recipeIds.single())) {
                        changed += 1
                        changedRecipes += recipeIds.single()
                    }
                }
                else -> unsupportedFeature("Unsupported recipe action '${tokens[1].text}'", profile.id, location)
            }
        }
        world.recordOutput(
            "recipe ${tokens[1].text}",
            "data",
            text = changed.toString(),
            payload = JsonObject().also { payload ->
                payload.add("targets", JsonArray().also { array -> targets.forEach { array.add(it.name) } })
                payload.addProperty("recipe", recipe)
                payload.addProperty("changed", changed)
                payload.add("changedRecipes", JsonArray().also { array ->
                    changedRecipes.forEach { array.add(it.toString()) }
                })
            },
        )
    }

    private fun executeForceload(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 2, "forceload <add|remove|query> ...", location)
        when (tokens[1].text) {
            "add", "remove" -> {
                if (tokens[1].text == "remove" && tokens.getOrNull(2)?.text == "all") {
                    val removed = world.forcedChunks.sorted()
                    world.forcedChunks.clear()
                    recordForceloadMutationOutput("remove all", removed, removed.size)
                    return
                }
                requireSize(tokens, 4, "forceload ${tokens[1].text} <from> [to]", location)
                val from = parseColumnPos(tokens, 2, context.position, location)
                val to = if (tokens.size >= 6) parseColumnPos(tokens, 4, context.position, location) else from
                val chunks = chunksInBlockRange(from, to).toList()
                var changed = 0
                chunks.forEach { chunk ->
                    val didChange = if (tokens[1].text == "add") {
                        world.forcedChunks.add(chunk)
                    } else {
                        world.forcedChunks.remove(chunk)
                    }
                    if (didChange) changed += 1
                }
                recordForceloadMutationOutput(tokens[1].text, chunks, changed)
            }
            "query" -> {
                if (tokens.size > 2) {
                    val pos = parseColumnPos(tokens, 2, context.position, location)
                    val chunk = ChunkPos(Math.floorDiv(pos.x, 16), Math.floorDiv(pos.z, 16))
                    val forced = chunk in world.forcedChunks
                    world.recordOutput(
                        "forceload query",
                        "data",
                        text = forced.toString(),
                        payload = JsonObject().also { payload ->
                            payload.add("position", JsonObject().also {
                                it.addProperty("x", pos.x)
                                it.addProperty("z", pos.z)
                            })
                            payload.add("chunk", JsonObject().also {
                                it.addProperty("x", chunk.x)
                                it.addProperty("z", chunk.z)
                            })
                            payload.addProperty("forced", forced)
                            payload.addProperty("forcedCount", world.forcedChunks.size)
                        },
                    )
                } else {
                    val payload = JsonArray()
                    world.forcedChunks.sorted().forEach { chunk ->
                        payload.add(JsonObject().also {
                            it.addProperty("x", chunk.x)
                            it.addProperty("z", chunk.z)
                        })
                    }
                    world.recordOutput("forceload query", "data", text = world.forcedChunks.sorted().joinToString { "${it.x},${it.z}" }, payload = payload)
                }
            }
            else -> unsupportedFeature("Unsupported forceload action '${tokens[1].text}'", profile.id, location)
        }
    }

    private fun recordForceloadMutationOutput(action: String, chunks: List<ChunkPos>, changed: Int) {
        world.recordOutput(
            "forceload $action",
            "data",
            targets = chunks.map { "${it.x},${it.z}" },
            text = changed.toString(),
            payload = JsonObject().also { payload ->
                payload.addProperty("action", action)
                payload.addProperty("changed", changed)
                payload.addProperty("forcedCount", world.forcedChunks.size)
                payload.add(
                    "chunks",
                    JsonArray().also { array ->
                        chunks.forEach { chunk ->
                            array.add(
                                JsonObject().also { entry ->
                                    entry.addProperty("x", chunk.x)
                                    entry.addProperty("z", chunk.z)
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    private fun executeFillBiome(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 8, "fillbiome <from> <to> <biome> [replace <filter>]", location)
        val from = parseBlockPos(tokens, 1, context, location)
        val to = parseBlockPos(tokens, 4, context, location)
        val biome = ResourceLocation.parse(tokens[7].text)
        val filter = if (tokens.getOrNull(8)?.text == "replace") tokens.getOrNull(9)?.text?.let(ResourceLocation::parse) else null
        val xs = minOf(from.x, to.x)..maxOf(from.x, to.x)
        val ys = minOf(from.y, to.y)..maxOf(from.y, to.y)
        val zs = minOf(from.z, to.z)..maxOf(from.z, to.z)
        val volume = xs.count() * ys.count() * zs.count()
        if (volume > 32768) {
            throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Fillbiome volume $volume exceeds sandbox limit 32768", location)
        }
        val changedPositions = mutableListOf<BlockPos>()
        for (x in xs) for (y in ys) for (z in zs) {
            val pos = BlockPos(x, y, z)
            if (filter == null || world.biomes[pos] == filter) {
                world.biomes[pos] = biome
                changedPositions += pos
            }
        }
        world.recordOutput(
            "fillbiome",
            "data",
            targets = changedPositions.map { it.toString() },
            text = changedPositions.size.toString(),
            payload = JsonObject().also { payload ->
                payload.addProperty("biome", biome.toString())
                filter?.let { payload.addProperty("filter", it.toString()) }
                payload.addProperty("volume", volume)
                payload.addProperty("changed", changedPositions.size)
                payload.add("from", blockPosOutput(from))
                payload.add("to", blockPosOutput(to))
                payload.add("positions", JsonArray().also { positions ->
                    changedPositions.forEach { pos -> positions.add(pos.toString()) }
                })
            },
        )
    }

    private fun executeDamage(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 3, "damage <target> <amount> [damageType] ...", location)
        val amount = tokens[2].text.toDoubleOrNull() ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid damage amount '${tokens[2].text}'", location)
        val damageContext = damageCommandContext(tokens, context, location)
        val sourceEntity = damageContext.directEntity ?: damageContext.causingEntity
        val targets = EntitySelectors.select(world, tokens[1].text, context, location)
        val damaged = JsonArray()
        targets.forEach { entity ->
            val beforeHealth = if (entity is SandboxPlayer) entity.health else entity.fullNbt(profile, location).get("Health")?.asDouble ?: 20.0
            val afterHealth = (beforeHealth - amount).coerceAtLeast(0.0)
            damaged.add(
                JsonObject().also { entry ->
                    entry.addProperty("target", entity.scoreHolder)
                    entry.addProperty("uuid", entity.uuid)
                    entry.addProperty("type", entity.type.toString())
                    entry.addProperty("beforeHealth", beforeHealth)
                    entry.addProperty("afterHealth", afterHealth)
                    entry.addProperty("dead", beforeHealth > 0.0 && afterHealth <= 0.0)
                },
            )
            if (entity is SandboxPlayer) {
                entity.health = afterHealth
                advancements.handle(
                    PlayerEvent(
                        entity.name,
                        "damage",
                        entity = sourceEntity,
                        damageAmount = amount,
                        damageSource = damageContext.damageSource,
                    ),
                )
                if (beforeHealth > 0.0 && afterHealth <= 0.0) {
                    advancements.handle(
                        PlayerEvent(
                            entity.name,
                            "death",
                            entity = sourceEntity,
                            damageAmount = amount,
                            damageSource = damageContext.damageSource,
                        ),
                    )
                    if (sourceEntity != null) {
                        advancements.handle(
                            PlayerEvent(
                                entity.name,
                                "entity_killed_player",
                                entity = sourceEntity,
                                damageAmount = amount,
                                damageSource = damageContext.damageSource,
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
                            damageSource = damageContext.damageSource,
                        ),
                    )
                    if (beforeHealth > 0.0 && afterHealth <= 0.0) {
                        advancements.handle(
                            PlayerEvent(
                                sourceEntity.name,
                                "killed_entity",
                                entity = entity,
                                damageAmount = amount,
                                damageSource = damageContext.damageSource,
                            ),
                        )
                    }
                }
            }
        }
        world.recordOutput(
            "damage",
            "data",
            targets = targets.map { it.scoreHolder },
            text = targets.size.toString(),
            payload = JsonObject().also { payload ->
                payload.addProperty("amount", amount)
                payload.addProperty("damageSource", damageContext.damageSource.toString())
                damageTypePayload(damageContext.damageSource, location)?.let { payload.add("damageType", it) }
                damageContext.position?.let { payload.add("position", positionOutput(it)) }
                sourceEntity?.let { source ->
                    payload.addProperty("source", source.scoreHolder)
                    payload.addProperty("sourceUuid", source.uuid)
                    payload.addProperty("sourceType", source.type.toString())
                }
                damageContext.directEntity?.let { source ->
                    payload.addProperty("directSource", source.scoreHolder)
                    payload.addProperty("directSourceUuid", source.uuid)
                    payload.addProperty("directSourceType", source.type.toString())
                }
                damageContext.causingEntity?.let { source ->
                    payload.addProperty("causingSource", source.scoreHolder)
                    payload.addProperty("causingSourceUuid", source.uuid)
                    payload.addProperty("causingSourceType", source.type.toString())
                }
                payload.addProperty("count", targets.size)
                payload.add("targets", damaged)
            },
        )
        world.entities.removeIf { entity ->
            when (entity) {
                is SandboxPlayer -> entity.health <= 0.0
                else -> entity.fullNbt(profile, location).get("Health")?.asDouble == 0.0
            }
        }
    }

    private fun damageTypePayload(id: ResourceLocation, location: SourceLocation?): JsonObject? {
        val resource = datapack.rawResources["damage_type"]?.get(id) ?: return null
        if (!resource.root.isJsonObject) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Damage type resource '$id' must be a JSON object", location)
        }
        val root = resource.root.asJsonObject
        return JsonObject().also { payload ->
            payload.addProperty("id", id.toString())
            payload.addProperty("resource", resource.file)
            payload.addProperty("version", resource.version ?: profile.id)
            root.copyPrimitive("message_id", payload, "messageId", location)
            root.copyPrimitive("scaling", payload, "scaling", location)
            root.copyPrimitive("exhaustion", payload, "exhaustion", location)
            root.copyPrimitive("effects", payload, "effects", location)
            root.copyPrimitive("death_message_type", payload, "deathMessageType", location)
        }
    }

    private fun JsonObject.copyPrimitive(source: String, target: JsonObject, targetName: String, location: SourceLocation?) {
        val value = get(source) ?: return
        if (!value.isJsonPrimitive) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Damage type field '$source' must be a primitive", location)
        }
        target.add(targetName, value.deepCopy())
    }

    private data class DamageCommandContext(
        val damageSource: ResourceLocation,
        val position: Position?,
        val directEntity: SandboxEntity?,
        val causingEntity: SandboxEntity?,
    )

    private fun damageCommandContext(tokens: List<CommandToken>, context: ExecutionContext, location: SourceLocation?): DamageCommandContext {
        var index = 3
        val damageSource = tokens.getOrNull(index)
            ?.text
            ?.takeUnless { it in damageCommandMarkers }
            ?.let {
                index += 1
                ResourceLocation.parse(it)
            }
            ?: ResourceLocation("minecraft", "generic")
        var position: Position? = null
        var directEntity: SandboxEntity? = null
        var causingEntity: SandboxEntity? = null

        while (index < tokens.size) {
            when (tokens[index].text) {
                "at" -> {
                    position = parsePosition(tokens, index + 1, context, location)
                    index += 4
                }
                "by" -> {
                    directEntity = damageSourceEntity(tokens, index, context, location)
                    index += 2
                }
                "from" -> {
                    causingEntity = damageSourceEntity(tokens, index, context, location)
                    index += 2
                }
                else -> throw SandboxException(
                    DiagnosticCode.INPUT_FORMAT,
                    "Unsupported damage context '${tokens[index].text}'; expected at, by, or from",
                    location,
                )
            }
        }

        return DamageCommandContext(damageSource, position, directEntity, causingEntity)
    }

    private fun damageSourceEntity(tokens: List<CommandToken>, markerIndex: Int, context: ExecutionContext, location: SourceLocation?): SandboxEntity {
        val selector = tokens.getOrNull(markerIndex + 1)
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "damage ${tokens[markerIndex].text} requires an entity selector", location)
        return EntitySelectors.select(world, selector.text, context, location).firstOrNull()
            ?: throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Damage source '${selector.text}' did not match an entity", location)
    }

    private val damageCommandMarkers = setOf("at", "by", "from")

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
                recordRideMountOutput(riders, vehicle)
            }
            "dismount" -> {
                val previousVehicles = riders.map { rider -> rider to rider.vehicle }
                riders.forEach { rider ->
                    world.entities.firstOrNull { it.uuid == rider.vehicle }?.passengers?.remove(rider.uuid)
                    rider.vehicle = null
                }
                recordRideDismountOutput(previousVehicles)
            }
            else -> unsupportedFeature("Unsupported ride action '${tokens[2].text}'", profile.id, location)
        }
    }

    private fun recordRideMountOutput(riders: List<SandboxEntity>, vehicle: SandboxEntity) {
        world.recordOutput(
            "ride mount",
            "data",
            targets = riders.map { it.scoreHolder },
            text = riders.size.toString(),
            payload = JsonObject().also { payload ->
                payload.addProperty("action", "mount")
                payload.addProperty("count", riders.size)
                payload.addProperty("vehicle", vehicle.scoreHolder)
                payload.addProperty("vehicleUuid", vehicle.uuid)
                payload.addProperty("vehicleType", vehicle.type.toString())
                payload.add("riders", JsonArray().also { array ->
                    riders.forEach { rider ->
                        array.add(
                            JsonObject().also { entry ->
                                entry.addProperty("target", rider.scoreHolder)
                                entry.addProperty("uuid", rider.uuid)
                                entry.addProperty("type", rider.type.toString())
                                entry.addProperty("vehicleUuid", rider.vehicle)
                            },
                        )
                    }
                })
            },
        )
    }

    private fun recordRideDismountOutput(previousVehicles: List<Pair<SandboxEntity, String?>>) {
        world.recordOutput(
            "ride dismount",
            "data",
            targets = previousVehicles.map { it.first.scoreHolder },
            text = previousVehicles.size.toString(),
            payload = JsonObject().also { payload ->
                payload.addProperty("action", "dismount")
                payload.addProperty("count", previousVehicles.size)
                payload.add("riders", JsonArray().also { array ->
                    previousVehicles.forEach { (rider, previousVehicle) ->
                        array.add(
                            JsonObject().also { entry ->
                                entry.addProperty("target", rider.scoreHolder)
                                entry.addProperty("uuid", rider.uuid)
                                entry.addProperty("type", rider.type.toString())
                                previousVehicle?.let { entry.addProperty("previousVehicleUuid", it) }
                            },
                        )
                    }
                })
            },
        )
    }

    private fun executeRotate(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 4, "rotate <targets> <yaw> <pitch>", location)
        val yaw = tokens[2].text.toDoubleOrNull() ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid yaw '${tokens[2].text}'", location)
        val pitch = tokens[3].text.toDoubleOrNull() ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid pitch '${tokens[3].text}'", location)
        val rotated = EntitySelectors.select(world, tokens[1].text, context, location).map { entity ->
            val before = Rotation(entity.yaw, entity.pitch)
            entity.yaw = yaw
            entity.pitch = pitch
            entity to before
        }
        world.recordOutput(
            "rotate",
            "data",
            targets = rotated.map { it.first.scoreHolder },
            text = rotated.size.toString(),
            payload = JsonObject().also { payload ->
                payload.addProperty("count", rotated.size)
                payload.addProperty("yaw", yaw)
                payload.addProperty("pitch", pitch)
                payload.add(
                    "targets",
                    JsonArray().also { targets ->
                        rotated.forEach { (entity, before) ->
                            targets.add(
                                JsonObject().also { entry ->
                                    entry.addProperty("target", entity.scoreHolder)
                                    entry.addProperty("uuid", entity.uuid)
                                    entry.addProperty("type", entity.type.toString())
                                    entry.addProperty("beforeYaw", before.yaw)
                                    entry.addProperty("beforePitch", before.pitch)
                                    entry.addProperty("afterYaw", entity.yaw)
                                    entry.addProperty("afterPitch", entity.pitch)
                                },
                            )
                        }
                    },
                )
            },
        )
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
                val modifierId = ResourceLocation.parse(tokens[5].text)
                val modifier = datapack.itemModifier(modifierId)
                val modified = mutableListOf<Pair<String, ItemStack>>()
                EntitySelectors.select(world, tokens[3].text, context, location).forEach { entity ->
                    val access = entityItemAccess(entity, tokens[4].text, location)
                    val item = access.get() ?: return@forEach
                    if (item.id == ResourceLocation("minecraft", "air") || item.count <= 0) return@forEach
                    val updated = applyItemModifier(item, modifier.root, { stack -> itemModifierPredicateContext(entity, stack) }, location)
                    access.set(updated)
                    modified += entity.scoreHolder to updated.copyStack()
                    if (entity is SandboxPlayer) {
                        advancements.handle(PlayerEvent(entity.name, "inventory_changed", item = updated))
                    }
                }
                recordItemModifyOutput("entity", tokens[4].text, modifierId, modified)
            }
            "block" -> {
                requireSize(tokens, 8, "item modify block <pos> <slot> <modifier>", location)
                val pos = parseBlockPos(tokens, 3, context, location)
                val slot = inventorySlot(tokens[6].text)
                val modifierId = ResourceLocation.parse(tokens[7].text)
                val modifier = datapack.itemModifier(modifierId)
                val item = blockItem(pos, slot, location)
                val modified = if (item != null && item.id != ResourceLocation("minecraft", "air") && item.count > 0) {
                    val updated = applyItemModifier(item, modifier.root, { stack -> itemModifierPredicateContext(pos, stack) }, location)
                    replaceBlockItem(pos, slot, updated, location)
                    listOf(pos.toString() to updated.copyStack())
                } else {
                    emptyList()
                }
                recordItemModifyOutput("block", tokens[6].text, modifierId, modified)
            }
            else -> unsupportedFeature("Unsupported item modify target '${tokens[2].text}'", profile.id, location)
        }
    }

    private fun recordItemModifyOutput(targetKind: String, slot: String, modifier: ResourceLocation, modified: List<Pair<String, ItemStack>>) {
        world.recordOutput(
            "item modify",
            "data",
            targets = modified.map { it.first },
            text = modified.size.toString(),
            payload = JsonObject().also { payload ->
                payload.addProperty("targetKind", targetKind)
                payload.addProperty("slot", slot)
                payload.addProperty("modifier", modifier.toString())
                payload.addProperty("modified", modified.size)
                payload.add("items", JsonArray().also { items ->
                    modified.forEach { (target, item) ->
                        items.add(JsonObject().also { entry ->
                            entry.addProperty("target", target)
                            entry.add("item", item.toJson())
                        })
                    }
                })
            },
        )
    }

    private fun executeItemReplace(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 3, "item replace <entity|block> ...", location)
        when (tokens[2].text) {
            "entity" -> {
                requireSize(tokens, 6, "item replace entity <targets> <slot> <with|from> ...", location)
                val item = when (tokens[5].text) {
                    "with" -> {
                        requireSize(tokens, 7, "item replace entity <targets> <slot> with <item> [count]", location)
                        val count = tokens.getOrNull(7)?.text?.let { parseInt(it, "item count", location) } ?: 1
                        parseItemStackArgument(tokens[6].text, count, location)
                    }
                    "from" -> readItemSource(tokens, 6, context, location)
                    else -> unsupportedFeature("Expected 'with' or 'from' in item replace entity", profile.id, location)
                }
                val entities = EntitySelectors.select(world, tokens[3].text, context, location)
                entities.forEach { entity ->
                    entityItemAccess(entity, tokens[4].text, location).set(item)
                }
                recordItemReplaceOutput("entity", entities.map { it.scoreHolder }, tokens[4].text, item)
            }
            "block" -> {
                requireSize(tokens, 8, "item replace block <pos> <slot> <with|from> ...", location)
                val pos = parseBlockPos(tokens, 3, context, location)
                val slot = inventorySlot(tokens[6].text)
                val item = when (tokens[7].text) {
                    "with" -> {
                        requireSize(tokens, 9, "item replace block <pos> <slot> with <item> [count]", location)
                        val count = tokens.getOrNull(9)?.text?.let { parseInt(it, "item count", location) } ?: 1
                        parseItemStackArgument(tokens[8].text, count, location)
                    }
                    "from" -> readItemSource(tokens, 8, context, location)
                    else -> unsupportedFeature("Expected 'with' or 'from' in item replace block", profile.id, location)
                }
                replaceBlockItem(pos, slot, item, location)
                recordItemReplaceOutput("block", listOf(pos.toString()), tokens[6].text, item)
            }
            else -> unsupportedFeature("Unsupported item replace target '${tokens[2].text}'", profile.id, location)
        }
    }

    private fun recordItemReplaceOutput(targetKind: String, targets: List<String>, slot: String, item: ItemStack?) {
        world.recordOutput(
            "item replace",
            "data",
            targets = targets,
            text = targets.size.toString(),
            payload = JsonObject().also { payload ->
                payload.addProperty("targetKind", targetKind)
                payload.add("targets", JsonArray().also { array -> targets.forEach { array.add(it) } })
                payload.addProperty("slot", slot)
                item?.let { payload.add("item", it.toJson()) }
            },
        )
    }

    private enum class PlayerItemContainer {
        INVENTORY,
        ENDER_ITEMS,
    }

    private data class PlayerItemSlot(
        val container: PlayerItemContainer,
        val index: Int,
    )

    private fun replacePlayerItem(player: SandboxPlayer, slot: PlayerItemSlot, item: ItemStack?) {
        val items = playerItems(player, slot.container)
        while (items.size <= slot.index) items += ItemStack(ResourceLocation("minecraft", "air"), 0)
        items[slot.index] = item?.copyStack() ?: ItemStack(ResourceLocation("minecraft", "air"), 0)
    }

    private fun playerItem(player: SandboxPlayer, slot: PlayerItemSlot): ItemStack? =
        playerItems(player, slot.container).getOrNull(slot.index)?.copyStack()

    private fun playerItems(player: SandboxPlayer, container: PlayerItemContainer): MutableList<ItemStack> =
        when (container) {
            PlayerItemContainer.INVENTORY -> player.inventory
            PlayerItemContainer.ENDER_ITEMS -> player.enderItems
        }

    private fun playerItemSlot(player: SandboxPlayer, rawSlot: String): PlayerItemSlot =
        if (isEnderItemSlot(rawSlot)) {
            PlayerItemSlot(
                PlayerItemContainer.ENDER_ITEMS,
                rawSlot.substringAfter('.').toIntOrNull()?.coerceAtLeast(0) ?: 0,
            )
        } else {
            PlayerItemSlot(PlayerItemContainer.INVENTORY, playerInventorySlot(player, rawSlot))
        }

    private fun playerInventorySlot(player: SandboxPlayer, rawSlot: String): Int =
        when (rawSlot) {
            "weapon.mainhand", "hotbar.selected" -> player.selectedSlot.coerceIn(0, 8)
            else -> inventorySlot(rawSlot)
        }

    private fun isEnderItemSlot(rawSlot: String): Boolean =
        rawSlot.startsWith("enderchest.") ||
            rawSlot.startsWith("ender_chest.") ||
            rawSlot.startsWith("enderChest.") ||
            rawSlot.startsWith("enderItems.") ||
            rawSlot.startsWith("ender_items.") ||
            rawSlot.startsWith("ender.")

    private data class EntityItemAccess(
        val get: () -> ItemStack?,
        val set: (ItemStack?) -> Unit,
    )

    private fun entityItemAccess(entity: SandboxEntity, rawSlot: String, location: SourceLocation?): EntityItemAccess {
        if (entity is SandboxPlayer) {
            val slot = playerItemSlot(entity, rawSlot)
            return EntityItemAccess(
                get = { playerItem(entity, slot) },
                set = { item -> replacePlayerItem(entity, slot, item) },
            )
        }

        val slot = EquipmentSlots.canonical(rawSlot)
            ?: unsupportedFeature("Entity slot '$rawSlot' is only supported for players or equipment slots", profile.id, location)
        return EntityItemAccess(
            get = { entity.equipment[slot]?.copyStack() },
            set = { item -> replaceEntityEquipmentItem(entity, slot, item) },
        )
    }

    private fun replaceEntityEquipmentItem(entity: SandboxEntity, slot: String, item: ItemStack?) {
        if (item != null && item.count > 0 && item.id != ResourceLocation("minecraft", "air")) {
            entity.equipment[slot] = item.copyStack()
        } else {
            entity.equipment.remove(slot)
        }
    }

    private fun readItemSource(tokens: List<CommandToken>, index: Int, context: ExecutionContext, location: SourceLocation?): ItemStack? {
        requireIndex(tokens, index, "item source <entity|block>", location)
        return when (tokens[index].text) {
            "entity" -> {
                requireSizeFrom(tokens, index, 3, "item source entity <source> <slot>", location)
                val source = EntitySelectors.select(world, tokens[index + 1].text, context, location).firstOrNull()
                    ?: throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Item source entity '${tokens[index + 1].text}' did not match an entity", location)
                entityItemAccess(source, tokens[index + 2].text, location).get()
            }
            "block" -> {
                requireSizeFrom(tokens, index, 5, "item source block <pos> <slot>", location)
                blockItem(parseBlockPos(tokens, index + 1, context, location), inventorySlot(tokens[index + 4].text), location)?.copyStack()
            }
            else -> unsupportedFeature("Unsupported item source '${tokens[index].text}'", profile.id, location)
        }
    }

    private fun parseItemStackArgument(raw: String, count: Int, location: SourceLocation?): ItemStack {
        val firstPayload = raw.indexOfFirst { it == '[' || it == '{' }
        val idText = if (firstPayload >= 0) raw.substring(0, firstPayload) else raw
        if (idText.isBlank()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Item id is missing in '$raw'", location)
        }

        val components = JsonObject()
        val nbt = JsonObject()
        var index = firstPayload.takeIf { it >= 0 } ?: raw.length
        while (index < raw.length) {
            when (raw[index]) {
                '[' -> {
                    val end = matchingItemPayloadEnd(raw, index, '[', ']', location)
                    parseItemComponents(raw.substring(index + 1, end), components, nbt, location)
                    index = end + 1
                }
                '{' -> {
                    val end = matchingItemPayloadEnd(raw, index, '{', '}', location)
                    val parsed = JsonValues.parse(raw.substring(index, end + 1), location)
                    if (!parsed.isJsonObject) {
                        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Item NBT must be an object", location)
                    }
                    JsonPaths.merge(nbt, null, parsed.asJsonObject)
                    index = end + 1
                }
                else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Malformed item argument '$raw'", location)
            }
        }
        return ItemStack(ResourceLocation.parse(idText), count, components, nbt)
    }

    private fun parseItemComponents(raw: String, components: JsonObject, nbt: JsonObject, location: SourceLocation?) {
        splitItemPayload(raw, location)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("!") }
            .forEach { entry ->
                val equalsIndex = topLevelEquals(entry, location)
                if (equalsIndex <= 0) {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Malformed item component '$entry'", location)
                }
                val key = canonicalItemComponent(entry.substring(0, equalsIndex).trim())
                val value = JsonValues.parse(entry.substring(equalsIndex + 1).trim(), location)
                components.add(key, value.deepCopy())
                if (key == "minecraft:custom_data") {
                    if (!value.isJsonObject) {
                        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Item custom_data component must be an object", location)
                    }
                    JsonPaths.merge(nbt, null, value)
                }
            }
    }

    private fun canonicalItemComponent(raw: String): String {
        if (raw.isBlank()) return raw
        return if (':' in raw) raw else "minecraft:$raw"
    }

    private fun splitItemPayload(raw: String, location: SourceLocation?): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        var objectDepth = 0
        var arrayDepth = 0
        var quote: Char? = null
        var escaped = false
        raw.forEachIndexed { index, char ->
            if (quote != null) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == quote -> quote = null
                }
                return@forEachIndexed
            }
            when (char) {
                '"', '\'' -> quote = char
                '{' -> objectDepth++
                '}' -> objectDepth--
                '[' -> arrayDepth++
                ']' -> arrayDepth--
                ',' -> if (objectDepth == 0 && arrayDepth == 0) {
                    parts += raw.substring(start, index)
                    start = index + 1
                }
            }
            if (objectDepth < 0 || arrayDepth < 0) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Malformed item component payload '$raw'", location)
            }
        }
        if (quote != null || objectDepth != 0 || arrayDepth != 0) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Malformed item component payload '$raw'", location)
        }
        parts += raw.substring(start)
        return parts
    }

    private fun topLevelEquals(raw: String, location: SourceLocation?): Int {
        var objectDepth = 0
        var arrayDepth = 0
        var quote: Char? = null
        var escaped = false
        raw.forEachIndexed { index, char ->
            if (quote != null) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == quote -> quote = null
                }
                return@forEachIndexed
            }
            when (char) {
                '"', '\'' -> quote = char
                '{' -> objectDepth++
                '}' -> objectDepth--
                '[' -> arrayDepth++
                ']' -> arrayDepth--
                '=' -> if (objectDepth == 0 && arrayDepth == 0) return index
            }
            if (objectDepth < 0 || arrayDepth < 0) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Malformed item component '$raw'", location)
            }
        }
        if (quote != null || objectDepth != 0 || arrayDepth != 0) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Malformed item component '$raw'", location)
        }
        return -1
    }

    private fun matchingItemPayloadEnd(raw: String, start: Int, open: Char, close: Char, location: SourceLocation?): Int {
        var depth = 0
        var quote: Char? = null
        var escaped = false
        for (index in start until raw.length) {
            val char = raw[index]
            if (quote != null) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == quote -> quote = null
                }
                continue
            }
            when (char) {
                '"', '\'' -> quote = char
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Malformed item argument '$raw'", location)
    }

    private fun ItemStack.matchesClearItem(expected: ItemStack): Boolean =
        id == expected.id &&
            jsonContainsAll(components, expected.components) &&
            jsonContainsAll(nbt, expected.nbt)

    private fun jsonContainsAll(actual: JsonObject, expected: JsonObject): Boolean =
        expected.entrySet().all { (key, expectedValue) ->
            val actualValue = actual.get(key) ?: return@all false
            when {
                expectedValue.isJsonObject && actualValue.isJsonObject -> jsonContainsAll(actualValue.asJsonObject, expectedValue.asJsonObject)
                expectedValue.isJsonArray && actualValue.isJsonArray -> actualValue == expectedValue
                else -> actualValue == expectedValue
            }
        }

    private fun ItemStack.copyStack(): ItemStack =
        copy(components = components.deepCopy(), nbt = nbt.deepCopy())

    private fun blockItem(pos: BlockPos, slot: Int, location: SourceLocation?): ItemStack? {
        val block = world.requireBlock(pos)
        val items = block.fullNbt(pos, profile, location).getAsJsonArray("Items") ?: return null
        val itemJson = items.firstOrNull {
            it.isJsonObject && it.asJsonObject.get("Slot")?.asInt == slot
        }?.asJsonObject ?: return null
        return itemStackFromJson(itemJson)
    }

    private fun itemStackFromJson(json: JsonObject): ItemStack? {
        return itemStackFromNbtJson(json)
    }

    private fun applyItemModifier(
        item: ItemStack,
        root: JsonElement,
        predicateContext: (ItemStack) -> PredicateContext,
        location: SourceLocation?,
    ): ItemStack =
        applyItemModifier(item, root, predicateContext, location, mutableSetOf())

    private fun applyItemModifier(
        item: ItemStack,
        root: JsonElement,
        predicateContext: (ItemStack) -> PredicateContext,
        location: SourceLocation?,
        activeReferences: MutableSet<ResourceLocation>,
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
                "limit_count" -> stack = stack.copy(count = itemModifierLimit(stack.count, function.get("limit")).coerceAtLeast(0))
                "set_item" -> stack = stack.copy(id = itemModifierResource(function, "item", type, location))
                "discard" -> stack = ItemStack(ResourceLocation("minecraft", "air"), 0)
                "set_damage" -> stack.components.addProperty("minecraft:damage", itemModifierNumber(function.get("damage"), 0.0))
                "set_name" -> stack.components.add("minecraft:custom_name", itemModifierText(function, "name", type, location))
                "set_lore" -> stack.components.add("minecraft:lore", itemModifierLore(function, location))
                "copy_nbt" -> copyItemModifierNbt(stack, function, predicateContext(stack), location)
                "copy_components" -> copyItemModifierComponents(stack, function, predicateContext(stack), location)
                "reference" -> {
                    val id = itemModifierResource(function, "name", type, location)
                    stack = applyReferencedItemModifier(stack, id, predicateContext, location, activeReferences)
                }
                "filtered" -> {
                    val itemFilter = itemModifierObject(function, "item_filter", type, location)
                    val branchName = if (predicates.testItemPredicate(stack, itemFilter)) "on_pass" else "on_fail"
                    function.get(branchName)?.takeUnless { it.isJsonNull }?.let { branch ->
                        stack = applyItemModifierBranch(stack, branch, predicateContext, location, activeReferences, branchName)
                    }
                }
                "sequence" -> {
                    val nested = function.get("functions")
                        ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Item modifier 'sequence' requires 'functions'", location)
                    stack = applyItemModifier(stack, nested, predicateContext, location, activeReferences)
                }
                else -> unsupportedFeature("Item modifier function '$type' is not implemented", profile.id, location)
            }
        }
        return stack
    }

    private fun applyItemModifierBranch(
        item: ItemStack,
        branch: JsonElement,
        predicateContext: (ItemStack) -> PredicateContext,
        location: SourceLocation?,
        activeReferences: MutableSet<ResourceLocation>,
        branchName: String,
    ): ItemStack =
        when {
            branch.isJsonPrimitive && branch.asJsonPrimitive.isString ->
                applyReferencedItemModifier(item, ResourceLocation.parse(branch.asString), predicateContext, location, activeReferences)
            branch.isJsonObject || branch.isJsonArray ->
                applyItemModifier(item, branch, predicateContext, location, activeReferences)
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Item modifier branch '$branchName' must be an id, object, or array", location)
        }

    private fun applyReferencedItemModifier(
        item: ItemStack,
        id: ResourceLocation,
        predicateContext: (ItemStack) -> PredicateContext,
        location: SourceLocation?,
        activeReferences: MutableSet<ResourceLocation>,
    ): ItemStack {
        if (!activeReferences.add(id)) {
            throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Recursive item modifier reference: $id", location)
        }
        try {
            return applyItemModifier(item, datapack.itemModifier(id).root, predicateContext, location, activeReferences)
        } finally {
            activeReferences.remove(id)
        }
    }

    private fun itemModifierPredicateContext(pos: BlockPos, stack: ItemStack): PredicateContext =
        PredicateContext(
            world = world,
            origin = Position(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble()),
            dimension = ResourceLocation("minecraft", "overworld"),
            tool = stack,
            block = world.block(pos)?.id,
            blockState = world.block(pos),
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

    private fun itemModifierPredicateContext(entity: SandboxEntity, stack: ItemStack): PredicateContext =
        if (entity is SandboxPlayer) {
            itemModifierPredicateContext(entity, stack)
        } else {
            PredicateContext(
                world = world,
                thisEntity = entity,
                origin = entity.position,
                dimension = entity.dimension,
                tool = stack,
                weather = currentWeatherState(),
            )
        }

    private fun itemModifierType(function: JsonObject, location: SourceLocation?): String {
        val raw = function.get("function")?.takeIf { it.isJsonPrimitive }?.asString
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Item modifier function is missing", location)
        return ResourceLocation.parse(raw).path
    }

    private fun itemModifierResource(function: JsonObject, key: String, type: String, location: SourceLocation?): ResourceLocation {
        val raw = function.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Item modifier '$type' requires string '$key'", location)
        return ResourceLocation.parse(raw)
    }

    private fun itemModifierObject(function: JsonObject, key: String, type: String, location: SourceLocation?): JsonObject =
        function.get(key)?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Item modifier '$type' requires object '$key'", location)

    private fun itemModifierCount(element: JsonElement?, fallback: Int): Int =
        when {
            element == null || element.isJsonNull -> fallback
            element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> element.asInt
            element.isJsonObject -> element.asJsonObject.get("min")?.asInt ?: element.asJsonObject.get("base")?.asInt ?: fallback
            else -> fallback
        }

    private fun itemModifierNumber(element: JsonElement?, fallback: Double): Double =
        when {
            element == null || element.isJsonNull -> fallback
            element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> element.asDouble
            element.isJsonObject -> element.asJsonObject.get("min")?.asDouble ?: element.asJsonObject.get("base")?.asDouble ?: fallback
            else -> fallback
        }

    private fun itemModifierLimit(value: Int, element: JsonElement?): Int {
        if (element == null || !element.isJsonObject) return value
        val root = element.asJsonObject
        val min = root.get("min")?.asInt ?: Int.MIN_VALUE
        val max = root.get("max")?.asInt ?: Int.MAX_VALUE
        return value.coerceIn(min, max)
    }

    private fun itemModifierText(function: JsonObject, key: String, type: String, location: SourceLocation?): JsonElement =
        function.get(key)?.deepCopy()
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Item modifier '$type' requires '$key'", location)

    private fun itemModifierLore(function: JsonObject, location: SourceLocation?): JsonArray {
        val lore = function.get("lore") ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Item modifier 'set_lore' requires 'lore'", location)
        if (!lore.isJsonArray) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Item modifier 'set_lore' lore must be an array", location)
        }
        return lore.deepCopy().asJsonArray
    }

    private fun copyItemModifierNbt(
        stack: ItemStack,
        function: JsonObject,
        context: PredicateContext,
        location: SourceLocation?,
    ) {
        val source = function.get("source")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: "tool"
        val sourceNbt = itemModifierNbtSource(source, stack, context, location)
        val ops = function.getAsJsonArray("ops")
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Item modifier 'copy_nbt' requires 'ops'", location)
        ops.forEachIndexed { index, element ->
            if (!element.isJsonObject) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Item modifier copy_nbt ops entries must be objects at index $index", location)
            }
            val op = element.asJsonObject
            val value = JsonPaths.get(sourceNbt, op.requiredItemModifierString("source", "copy_nbt", location)) ?: return@forEachIndexed
            JsonPaths.set(stack.nbt, op.requiredItemModifierString("target", "copy_nbt", location), value)
        }
    }

    private fun itemModifierNbtSource(
        source: String,
        stack: ItemStack,
        context: PredicateContext,
        location: SourceLocation?,
    ): JsonObject {
        val nbt = when (source) {
            "tool" -> context.tool?.nbt ?: stack.nbt
            "this" -> context.thisEntity?.nbt ?: context.player?.nbt ?: stack.nbt
            "attacker" -> context.attacker?.nbt
            "direct_attacker" -> context.directEntity?.nbt
            "killer" -> context.killer?.nbt
            else -> null
        }
        return nbt ?: throw SandboxException(DiagnosticCode.MISSING_CONTEXT, "copy_nbt requires source context '$source'", location)
    }

    private fun copyItemModifierComponents(
        stack: ItemStack,
        function: JsonObject,
        context: PredicateContext,
        location: SourceLocation?,
    ) {
        val source = function.get("source")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: "tool"
        val sourceComponents = itemModifierComponentSource(source, stack, context, location)
        val include = itemModifierComponentList(function.get("include"), "include", location)
        val exclude = itemModifierComponentList(function.get("exclude"), "exclude", location).orEmpty().toSet()
        val names = (include ?: sourceComponents.entrySet().map { it.key }.sorted())
            .distinct()
            .filterNot { it in exclude }
        names.forEach { name ->
            sourceComponents.get(name)?.let { stack.components.add(name, it.deepCopy()) }
        }
    }

    private fun itemModifierComponentSource(
        source: String,
        stack: ItemStack,
        context: PredicateContext,
        location: SourceLocation?,
    ): JsonObject {
        val item = when (source) {
            "tool" -> context.tool ?: stack
            "this" -> (context.thisEntity as? SandboxPlayer)?.selectedItem ?: context.player?.selectedItem ?: stack
            "attacker" -> (context.attacker as? SandboxPlayer)?.selectedItem
            "direct_attacker" -> (context.directEntity as? SandboxPlayer)?.selectedItem
            "killer" -> (context.killer as? SandboxPlayer)?.selectedItem
            else -> null
        }
        return item?.components
            ?: throw SandboxException(DiagnosticCode.MISSING_CONTEXT, "copy_components requires item component source '$source'", location)
    }

    private fun itemModifierComponentList(element: JsonElement?, key: String, location: SourceLocation?): List<String>? {
        if (element == null || element.isJsonNull) return null
        return when {
            element.isJsonPrimitive && element.asJsonPrimitive.isString ->
                listOf(ResourceLocation.parse(element.asString).toString())
            element.isJsonArray -> element.asJsonArray.mapIndexed { index, value ->
                if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Item modifier copy_components '$key' entries must be strings at index $index", location)
                }
                ResourceLocation.parse(value.asString).toString()
            }
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Item modifier copy_components '$key' must be a string or array of strings", location)
        }
    }

    private fun JsonObject.requiredItemModifierString(name: String, type: String, location: SourceLocation?): String =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Item modifier '$type' requires string '$name'", location)

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
            position = parsePosition(tokens, 2, context, location)
            nbtStartIndex = 5
        }
        val nbt = if (tokens.size > nbtStartIndex) parseSummonNbt(CommandTokenizer.tailFrom(command, tokens[nbtStartIndex]), location) else JsonObject()
        val tags = extractTags(nbt).toMutableSet()
        val entity = SandboxEntity(type = type, position = position, tags = tags, dimension = context.dimension)
        val fullNbt = entity.fullNbt(profile, location)
        nbt.entrySet().forEach { (key, value) -> fullNbt.add(key, value.deepCopy()) }
        entity.writeFullNbt(profile, fullNbt, location)
        world.entities += entity
        world.recordOutput(
            "summon",
            "data",
            targets = listOf(entity.scoreHolder),
            text = "1",
            payload = JsonObject().also { payload ->
                payload.addProperty("count", 1)
                payload.addProperty("target", entity.scoreHolder)
                payload.addProperty("uuid", entity.uuid)
                payload.addProperty("type", entity.type.toString())
                payload.addProperty("dimension", entity.dimension.toString())
                payload.addDimensionMetadata("dimensionResource", entity.dimension, location)
                payload.add("position", positionOutput(entity.position))
                payload.add("tags", JsonArray().also { tagsArray -> entity.tags.forEach { tagsArray.add(it) } })
                payload.add("nbt", nbt.deepCopy())
                entityVariantPayloads(entity.type, fullNbt, location)?.let { payload.add("variantResources", it) }
            },
        )
    }

    private fun entityVariantPayloads(entityType: ResourceLocation, nbt: JsonObject, location: SourceLocation?): JsonArray? {
        val variants = JsonArray()
        entityVariantResourceFields(entityType).forEach { (field, kind) ->
            val value = nbt.get(field)?.takeIf { it.isJsonPrimitive } ?: return@forEach
            val id = runCatching { ResourceLocation.parse(value.asString) }.getOrNull() ?: return@forEach
            val resource = datapack.rawResources[kind]?.get(id) ?: return@forEach
            if (!resource.root.isJsonObject) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Entity variant resource '$id' of type '$kind' must be a JSON object", location)
            }
            variants.add(
                JsonObject().also { payload ->
                    payload.addProperty("type", kind)
                    payload.addProperty("field", field)
                    payload.addProperty("id", id.toString())
                    payload.addProperty("resource", resource.file)
                    payload.addProperty("version", resource.version ?: profile.id)
                    payload.add("definition", resource.root.deepCopy())
                },
            )
        }
        return variants.takeIf { it.size() > 0 }
    }

    private fun entityVariantResourceFields(entityType: ResourceLocation): List<Pair<String, String>> =
        when (entityType.toString()) {
            "minecraft:cat" -> listOf("variant" to "cat_variant", "Variant" to "cat_variant")
            "minecraft:chicken" -> listOf("variant" to "chicken_variant", "Variant" to "chicken_variant")
            "minecraft:cow" -> listOf("variant" to "cow_variant", "Variant" to "cow_variant")
            "minecraft:frog" -> listOf("variant" to "frog_variant", "Variant" to "frog_variant")
            "minecraft:painting" -> listOf("variant" to "painting_variant", "Variant" to "painting_variant")
            "minecraft:pig" -> listOf("variant" to "pig_variant", "Variant" to "pig_variant")
            "minecraft:wolf" -> listOf(
                "variant" to "wolf_variant",
                "Variant" to "wolf_variant",
                "sound_variant" to "wolf_sound_variant",
                "SoundVariant" to "wolf_sound_variant",
            )
            else -> emptyList()
        }

    private fun executeKill(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        val targetToken = tokens.getOrNull(1)?.text ?: "@s"
        val selected = EntitySelectors.select(world, targetToken, context, location).distinctBy { it.uuid }
        (context.entity as? SandboxPlayer)?.let { player ->
            selected.filterNot { it is SandboxPlayer }.forEach { target ->
                advancements.handle(PlayerEvent(player.name, "killed_entity", entity = target))
            }
        }
        world.recordOutput(
            "kill",
            "data",
            targets = selected.map { it.scoreHolder },
            text = selected.size.toString(),
            payload = JsonObject().also { payload ->
                payload.addProperty("count", selected.size)
                payload.add(
                    "targets",
                    JsonArray().also { targets ->
                        selected.forEach { entity ->
                            targets.add(
                                JsonObject().also { entry ->
                                    entry.addProperty("target", entity.scoreHolder)
                                    entry.addProperty("uuid", entity.uuid)
                                    entry.addProperty("type", entity.type.toString())
                                    entry.addProperty("dimension", entity.dimension.toString())
                                    entry.addDimensionMetadata("dimensionResource", entity.dimension, location)
                                    entry.addProperty("player", entity is SandboxPlayer)
                                },
                            )
                        }
                    },
                )
            },
        )
        val selectedUuids = selected.map { it.uuid }.toSet()
        world.entities.removeIf { it.uuid in selectedUuids }
    }

    private fun executeTeleport(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 2, "${tokens[0].text} <targets> <location>|<destination>", location)
        when {
            tokens.size >= 4 && isCoordinateTriple(tokens, 1) -> {
                val entity = context.entity ?: throw SandboxException(DiagnosticCode.COMMAND_ERROR, "${tokens[0].text} <location> requires an execution entity", location)
                val position = parsePosition(
                    tokens,
                    1,
                    entity.position,
                    location,
                    context.yaw,
                    context.pitch,
                    anchoredPosition(entity.position, entity, context.anchor),
                )
                val rotation = parseOptionalTeleportRotation(tokens, 4, position, context, location)
                val moved = moveEntities(listOf(entity), position, rotation, context.dimension)
                recordTeleportOutput(tokens[0].text, moved)
            }
            tokens.size >= 5 && isCoordinateTriple(tokens, 2) -> {
                val targets = EntitySelectors.select(world, tokens[1].text, context, location)
                val position = parsePosition(tokens, 2, context, location)
                val rotation = parseOptionalTeleportRotation(tokens, 5, position, context, location)
                val moved = moveEntities(targets, position, rotation, context.dimension)
                recordTeleportOutput(tokens[0].text, moved)
            }
            tokens.size >= 3 -> {
                val targets = EntitySelectors.select(world, tokens[1].text, context, location)
                val destination = EntitySelectors.select(world, tokens[2].text, context, location).firstOrNull()
                    ?: throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Teleport destination '${tokens[2].text}' did not match an entity", location)
                val moved = moveEntities(
                    targets,
                    destination.position,
                    Rotation(destination.yaw, destination.pitch),
                    destination.dimension,
                )
                recordTeleportOutput(tokens[0].text, moved)
            }
            else -> unsupportedFeature("Unsupported ${tokens[0].text} form", profile.id, location)
        }
    }

    private data class Rotation(val yaw: Double, val pitch: Double)

    private data class EntityMove(
        val entity: SandboxEntity,
        val beforePosition: Position,
        val beforeDimension: ResourceLocation,
        val beforeYaw: Double,
        val beforePitch: Double,
    )

    private fun moveEntities(
        entities: List<SandboxEntity>,
        position: Position,
        rotation: Rotation? = null,
        dimension: ResourceLocation? = null,
    ): List<EntityMove> =
        entities.map { entity ->
            val move = EntityMove(
                entity = entity,
                beforePosition = entity.position,
                beforeDimension = entity.dimension,
                beforeYaw = entity.yaw,
                beforePitch = entity.pitch,
            )
            entity.position = position
            dimension?.let { entity.dimension = it }
            rotation?.let {
                entity.yaw = it.yaw
                entity.pitch = it.pitch
            }
            if (entity is SandboxPlayer) {
                advancements.handle(PlayerEvent(entity.name, "moved"))
            }
            move
        }

    private fun recordTeleportOutput(command: String, moved: List<EntityMove>) {
        world.recordOutput(
            command,
            "data",
            targets = moved.map { it.entity.scoreHolder },
            text = moved.size.toString(),
            payload = JsonObject().also { payload ->
                payload.addProperty("count", moved.size)
                payload.add(
                    "targets",
                    JsonArray().also { targets ->
                        moved.forEach { move ->
                            targets.add(
                                JsonObject().also { entry ->
                                    entry.addProperty("target", move.entity.scoreHolder)
                                    entry.addProperty("uuid", move.entity.uuid)
                                    entry.addProperty("type", move.entity.type.toString())
                                    entry.add("from", positionOutput(move.beforePosition))
                                    entry.add("to", positionOutput(move.entity.position))
                                    entry.addProperty("fromDimension", move.beforeDimension.toString())
                                    entry.addProperty("toDimension", move.entity.dimension.toString())
                                    entry.addDimensionMetadata("fromDimensionResource", move.beforeDimension, null)
                                    entry.addDimensionMetadata("toDimensionResource", move.entity.dimension, null)
                                    entry.addProperty("beforeYaw", move.beforeYaw)
                                    entry.addProperty("beforePitch", move.beforePitch)
                                    entry.addProperty("afterYaw", move.entity.yaw)
                                    entry.addProperty("afterPitch", move.entity.pitch)
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    private fun positionOutput(position: Position): JsonObject =
        JsonObject().also { pos ->
            pos.addProperty("x", position.x)
            pos.addProperty("y", position.y)
            pos.addProperty("z", position.z)
        }

    private fun JsonObject.addDimensionMetadata(name: String, dimension: ResourceLocation, location: SourceLocation?) {
        dimensionPayload(dimension, location)?.let { add(name, it) }
    }

    private fun spawnPointOutput(spawn: SpawnPoint, location: SourceLocation?): JsonObject =
        spawn.toJson().also { payload ->
            payload.addDimensionMetadata("dimensionResource", spawn.dimension, location)
        }

    private fun dimensionPayload(id: ResourceLocation, location: SourceLocation?): JsonObject? {
        val resource = datapack.rawResources["dimension"]?.get(id) ?: return null
        if (!resource.root.isJsonObject) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Dimension resource '$id' must be a JSON object", location)
        }
        val root = resource.root.asJsonObject
        return JsonObject().also { payload ->
            payload.addProperty("id", id.toString())
            payload.addProperty("resource", resource.file)
            payload.addProperty("version", resource.version ?: profile.id)
            payload.add("definition", root.deepCopy())
            root.get("type")?.let { typeElement ->
                if (!typeElement.isJsonPrimitive) {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Dimension resource '$id' field 'type' must be a primitive id", location)
                }
                val type = ResourceLocation.parse(typeElement.asString)
                payload.addProperty("type", type.toString())
                dimensionTypePayload(type, location)?.let { payload.add("dimensionType", it) }
            }
        }
    }

    private fun dimensionTypePayload(id: ResourceLocation, location: SourceLocation?): JsonObject? {
        val resource = datapack.rawResources["dimension_type"]?.get(id) ?: return null
        if (!resource.root.isJsonObject) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Dimension type resource '$id' must be a JSON object", location)
        }
        val root = resource.root.asJsonObject
        return JsonObject().also { payload ->
            payload.addProperty("id", id.toString())
            payload.addProperty("resource", resource.file)
            payload.addProperty("version", resource.version ?: profile.id)
            payload.add("definition", root.deepCopy())
        }
    }

    private fun executeSetBlock(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 5, "setblock <pos> <block> [destroy|keep|replace]", location)
        val pos = parseBlockPos(tokens, 1, context, location)
        val block = parseBlockArgument(tokens[4].text, location)
        val before = world.block(pos)?.copyForClone()
        when (val mode = tokens.getOrNull(5)?.text ?: "replace") {
            "replace", "destroy", "strict" -> world.setBlock(pos, block.toBlock(pos, profile, location))
            "keep" -> if (world.block(pos) == null) world.setBlock(pos, block.toBlock(pos, profile, location))
            else -> unsupportedFeature("Unsupported setblock mode '$mode'", profile.id, location)
        }
        val after = world.block(pos)?.copyForClone()
        recordSetBlockOutput(pos, tokens.getOrNull(5)?.text ?: "replace", before, after)
    }

    private fun recordSetBlockOutput(pos: BlockPos, mode: String, before: SandboxBlock?, after: SandboxBlock?) {
        val changed = !sameBlock(before, after)
        world.recordOutput(
            "setblock",
            "data",
            targets = if (changed) listOf(pos.toString()) else emptyList(),
            text = if (changed) "1" else "0",
            payload = JsonObject().also { payload ->
                payload.addProperty("changed", changed)
                payload.addProperty("mode", mode)
                payload.add("pos", blockPosOutput(pos))
                before?.let { payload.add("before", it.toJson(pos)) }
                after?.let { payload.add("after", it.toJson(pos)) }
            },
        )
    }

    private fun blockPosOutput(pos: BlockPos): JsonObject =
        JsonObject().also { json ->
            json.addProperty("x", pos.x)
            json.addProperty("y", pos.y)
            json.addProperty("z", pos.z)
        }

    private fun blockPosArrayOutput(positions: List<BlockPos>): JsonArray =
        JsonArray().also { array -> positions.forEach { array.add(it.toString()) } }

    private fun executeClone(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 10, "clone <begin> <end> <destination> [replace|masked|filtered] [force|move|normal]", location)
        val from = parseBlockPos(tokens, 1, context, location)
        val to = parseBlockPos(tokens, 4, context, location)
        val dest = parseBlockPos(tokens, 7, context, location)
        val maskMode = tokens.getOrNull(10)?.text ?: "replace"
        val cloneMode = tokens.getOrNull(11)?.text ?: "normal"
        if (cloneMode !in setOf("normal", "force", "move")) {
            unsupportedFeature("Unsupported clone mode '$cloneMode'", profile.id, location)
        }
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
        val changed = mutableListOf<BlockPos>()
        val removedSources = mutableListOf<BlockPos>()
        copied.forEach { (pos, block) ->
            val before = world.block(pos)?.copyForClone()
            world.setBlock(pos, block)
            val after = world.block(pos)?.copyForClone()
            if (!sameBlock(before, after)) changed += pos
        }
        if (cloneMode == "move") {
            for (x in xs) for (y in ys) for (z in zs) {
                val sourcePos = BlockPos(x, y, z)
                val before = world.block(sourcePos)?.copyForClone()
                world.setBlock(sourcePos, null)
                val after = world.block(sourcePos)?.copyForClone()
                if (!sameBlock(before, after)) {
                    removedSources += sourcePos
                    changed += sourcePos
                }
            }
        }
        recordCloneOutput(from, to, dest, maskMode, cloneMode, copied.map { it.first }, removedSources, changed)
    }

    private fun recordCloneOutput(
        from: BlockPos,
        to: BlockPos,
        destination: BlockPos,
        maskMode: String,
        cloneMode: String,
        copiedPositions: List<BlockPos>,
        removedSourcePositions: List<BlockPos>,
        changedPositions: List<BlockPos>,
    ) {
        world.recordOutput(
            "clone",
            "data",
            targets = copiedPositions.map { it.toString() },
            text = copiedPositions.size.toString(),
            payload = JsonObject().also { payload ->
                payload.addProperty("maskMode", maskMode)
                payload.addProperty("cloneMode", cloneMode)
                payload.addProperty("copied", copiedPositions.size)
                payload.addProperty("removedSources", removedSourcePositions.size)
                payload.addProperty("changed", changedPositions.size)
                payload.add("from", blockPosOutput(from))
                payload.add("to", blockPosOutput(to))
                payload.add("destination", blockPosOutput(destination))
                payload.add("copiedPositions", blockPosArrayOutput(copiedPositions))
                payload.add("removedSourcePositions", blockPosArrayOutput(removedSourcePositions))
                payload.add("changedPositions", blockPosArrayOutput(changedPositions))
            },
        )
    }

    private fun executeFill(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 8, "fill <from> <to> <block> [replace|keep|destroy|hollow|outline]", location)
        val from = parseBlockPos(tokens, 1, context, location)
        val to = parseBlockPos(tokens, 4, context, location)
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
        val changed = mutableListOf<BlockPos>()
        for (x in xs) for (y in ys) for (z in zs) {
            val pos = BlockPos(x, y, z)
            val before = world.block(pos)?.copyForClone()
            val boundary = x == xs.first || x == xs.last || y == ys.first || y == ys.last || z == zs.first || z == zs.last
            when {
                mode == "keep" && world.block(pos) != null -> Unit
                mode == "outline" && !boundary -> Unit
                mode == "hollow" && !boundary -> world.setBlock(pos, null)
                else -> world.setBlock(pos, block.toBlock(pos, profile, location))
            }
            val after = world.block(pos)?.copyForClone()
            if (!sameBlock(before, after)) changed += pos
        }
        recordFillOutput(from, to, block, mode, volume, changed)
    }

    private fun recordFillOutput(
        from: BlockPos,
        to: BlockPos,
        block: BlockArgument,
        mode: String,
        volume: Int,
        changed: List<BlockPos>,
    ) {
        world.recordOutput(
            "fill",
            "data",
            targets = changed.map { it.toString() },
            text = changed.size.toString(),
            payload = JsonObject().also { payload ->
                payload.addProperty("mode", mode)
                payload.addProperty("volume", volume)
                payload.addProperty("changed", changed.size)
                payload.add("from", blockPosOutput(from))
                payload.add("to", blockPosOutput(to))
                payload.add("block", blockArgumentOutput(block))
                payload.add(
                    "positions",
                    JsonArray().also { positions ->
                        changed.forEach { pos -> positions.add(pos.toString()) }
                    },
                )
            },
        )
    }

    private fun blockArgumentOutput(block: BlockArgument): JsonObject =
        JsonObject().also { json ->
            json.addProperty("id", block.id.toString())
            json.add(
                "properties",
                JsonObject().also { properties ->
                    block.properties.toSortedMap().forEach { (key, value) -> properties.addProperty(key, value) }
                },
            )
            json.add("nbt", block.nbt.deepCopy())
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
        world.recordOutput("say", "chat", world.players.keys.toList(), "<$sender> $text", chatCommandPayload(ResourceLocation("minecraft", "say_command"), sender, text, location))
    }

    private fun executeMe(command: String, tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 2, "me <action>", location)
        val sender = outputSender(context)
        val text = CommandTokenizer.tailFrom(command, tokens[1])
        world.recordOutput("me", "chat", world.players.keys.toList(), "* $sender $text", chatCommandPayload(ResourceLocation("minecraft", "emote_command"), sender, text, location))
    }

    private fun executePrivateMessage(command: String, tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 3, "${tokens[0].text} <targets> <message>", location)
        val targets = resolvePlayers(tokens[1].text, location, context).map { it.name }
        val sender = outputSender(context)
        val text = CommandTokenizer.tailFrom(command, tokens[2])
        world.recordOutput(tokens[0].text, "chat", targets, "[$sender -> ${targets.joinToString()}] $text", chatCommandPayload(ResourceLocation("minecraft", "msg_command_incoming"), sender, text, location))
    }

    private fun executeTeamMessage(command: String, tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        requireSize(tokens, 2, "${tokens[0].text} <message>", location)
        val sender = outputSender(context)
        val text = CommandTokenizer.tailFrom(command, tokens[1])
        world.recordOutput(tokens[0].text, "chat", world.players.keys.toList(), "[team] <$sender> $text", chatCommandPayload(ResourceLocation("minecraft", "team_msg_command_incoming"), sender, text, location))
    }

    private fun chatCommandPayload(chatType: ResourceLocation, sender: String, message: String, location: SourceLocation?): JsonObject =
        JsonObject().also { payload ->
            payload.addProperty("sender", sender)
            payload.addProperty("message", message)
            payload.addProperty("chatType", chatType.toString())
            chatTypePayload(chatType, location)?.let { payload.add("chatTypeResource", it) }
        }

    private fun chatTypePayload(id: ResourceLocation, location: SourceLocation?): JsonObject? {
        val resource = datapack.rawResources["chat_type"]?.get(id) ?: return null
        if (!resource.root.isJsonObject) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Chat type resource '$id' must be a JSON object", location)
        }
        val root = resource.root.asJsonObject
        return JsonObject().also { payload ->
            payload.addProperty("id", id.toString())
            payload.addProperty("resource", resource.file)
            payload.addProperty("version", resource.version ?: profile.id)
            root.get("chat")?.let { payload.add("chat", it.deepCopy()) }
            root.get("narration")?.let { payload.add("narration", it.deepCopy()) }
        }
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

    private fun executeTransfer(command: String, tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        if (!profile.commands.hasRoot("transfer")) {
            handleUnknownCommand("transfer", command, location)
        }
        requireSize(tokens, 2, "transfer <host> [port] [players]", location)
        val targetFirst = tokens.size >= 3 && isTransferTargetFirst(tokens[1].text, tokens[2].text)
        val host = if (targetFirst) tokens[2].text else tokens[1].text
        val portIndex = if (targetFirst) 3 else 2
        val targetTokenIndex = if (!targetFirst && tokens.getOrNull(2)?.text?.toIntOrNull() != null) 3 else 2
        val port = tokens.getOrNull(portIndex)
            ?.takeIf { it.text.toIntOrNull() != null }
            ?.let { parseNetworkPort(it.text, "transfer port", location) }
            ?: 25565
        val targetToken = if (targetFirst) tokens.getOrNull(1)?.text else tokens.getOrNull(targetTokenIndex)?.text
        val targets = targetToken
            ?.let { resolvePlayers(it, location, context) }
            ?: listOfNotNull(context.entity as? SandboxPlayer).ifEmpty { world.players.values.toList() }
        val targetNames = targets.map { it.name }

        val payload = JsonObject()
        payload.addProperty("host", host)
        payload.addProperty("port", port)
        payload.addProperty("syntax", if (targetFirst) "target-first" else "host-first")
        payload.addProperty("noOp", true)
        payload.addProperty("reason", "Networking/server transfer is not simulated by the sandbox")
        payload.add(
            "targets",
            JsonArray().also { array ->
                targetNames.forEach { array.add(it) }
            },
        )
        world.recordOutput("transfer", "debug", targetNames, "$host:$port", payload)
    }

    private fun isTransferTargetFirst(firstArgument: String, secondArgument: String): Boolean {
        if (secondArgument.toIntOrNull() != null) return false
        if (firstArgument.contains(".") || firstArgument.contains(":")) return false
        return firstArgument == "@a" || EntitySelectors.isSelector(firstArgument) || firstArgument in world.players
    }

    private fun parseNetworkPort(raw: String, label: String, location: SourceLocation?): Int {
        val port = parseInt(raw, label, location)
        if (port !in 1..65535) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid $label: '$raw' (expected 1..65535)", location)
        }
        return port
    }

    private fun executePublish(tokens: List<CommandToken>, location: SourceLocation?) {
        requireSize(tokens, 1, "publish [allowCommands] [gamemode] [port]", location)
        val allowCommands = tokens.getOrNull(1)?.text?.let { parseBoolean(it, "publish allowCommands", location) }
        val gameMode = tokens.getOrNull(2)?.text?.let { normalizeGameMode(it, location) }
        val port = tokens.getOrNull(3)?.text?.let { parseNetworkPort(it, "publish port", location) }
        val payload = JsonObject()
        allowCommands?.let { payload.addProperty("allowCommands", it) }
        gameMode?.let { payload.addProperty("gamemode", it) }
        port?.let { payload.addProperty("port", it) }
        payload.addProperty("noOp", true)
        payload.addProperty("reason", "LAN/network publishing is not simulated by the sandbox")
        world.recordOutput("publish", "debug", text = port?.toString().orEmpty(), payload = payload)
    }

    private fun executeProfilingNoop(tokens: List<CommandToken>, location: SourceLocation?) {
        requireSize(tokens, 2, "${tokens[0].text} <action> [...]", location)
        val arguments = JsonArray()
        tokens.drop(1).forEach { arguments.add(it.text) }
        val payload = JsonObject()
        payload.addProperty("action", tokens[1].text)
        payload.add("arguments", arguments)
        payload.addProperty("noOp", true)
        payload.addProperty("reason", "Profiling and flight recording are not simulated by the sandbox")
        world.recordOutput(tokens[0].text, "debug", text = tokens.drop(1).joinToString(" ") { it.text }, payload = payload)
    }

    private fun executeServerAdminNoop(command: String, tokens: List<CommandToken>, location: SourceLocation?) {
        val root = tokens[0].text
        fun payload(action: String): JsonObject =
            JsonObject().also {
                it.addProperty("action", action)
                it.addProperty("noOp", true)
                it.addProperty("noOpReason", "Server administration state is not simulated by the sandbox")
            }

        when (root) {
            "ban", "ban-ip", "kick" -> {
                requireSize(tokens, 2, "$root <target> [reason]", location)
                val reason = tokens.getOrNull(2)?.let { CommandTokenizer.tailFrom(command, it) }
                val payload = payload(root)
                payload.addProperty("target", tokens[1].text)
                reason?.let { payload.addProperty("message", it) }
                world.recordOutput(root, "debug", text = tokens[1].text, payload = payload)
            }
            "pardon", "pardon-ip", "op", "deop" -> {
                requireSize(tokens, 2, "$root <target>", location)
                world.recordOutput(
                    root,
                    "debug",
                    text = tokens[1].text,
                    payload = payload(root).also { it.addProperty("target", tokens[1].text) },
                )
            }
            "banlist" -> {
                val filter = tokens.getOrNull(1)?.text ?: "players"
                if (filter !in setOf("ips", "players")) {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Expected: banlist [ips|players]", location)
                }
                world.recordOutput(
                    "banlist",
                    "debug",
                    text = filter,
                    payload = payload("list").also { it.addProperty("filter", filter) },
                )
            }
            "whitelist" -> executeWhitelistNoop(tokens, location, ::payload)
        }
    }

    private fun executeWhitelistNoop(
        tokens: List<CommandToken>,
        location: SourceLocation?,
        payloadFactory: (String) -> JsonObject,
    ) {
        requireSize(tokens, 2, "whitelist <add|remove|list|on|off|reload> [target]", location)
        val action = tokens[1].text
        if (action !in setOf("add", "remove", "list", "on", "off", "reload")) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Expected: whitelist <add|remove|list|on|off|reload> [target]", location)
        }
        val payload = payloadFactory(action)
        if (action in setOf("add", "remove")) {
            requireSize(tokens, 3, "whitelist $action <target>", location)
            payload.addProperty("target", tokens[2].text)
        }
        world.recordOutput("whitelist $action", "debug", text = tokens.getOrNull(2)?.text.orEmpty(), payload = payload)
    }

    private fun executeSaveLifecycleNoop(tokens: List<CommandToken>, location: SourceLocation?) {
        val command = tokens[0].text
        val payload = JsonObject()
        payload.addProperty("action", command.removePrefix("save-"))
        payload.addProperty("noOp", true)
        payload.addProperty("noOpReason", "World save lifecycle is controlled by the host process")
        if (command == "save-all") {
            val flush = tokens.getOrNull(1)?.text?.let {
                if (it != "flush") throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Expected: save-all [flush]", location)
                true
            } ?: false
            payload.addProperty("flush", flush)
        }
        world.recordOutput(command, "debug", text = command, payload = payload)
    }

    private fun executeSetIdleTimeout(tokens: List<CommandToken>, location: SourceLocation?) {
        requireSize(tokens, 2, "setidletimeout <minutes>", location)
        val minutes = parseInt(tokens[1].text, "idle timeout minutes", location)
        if (minutes < 0) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid idle timeout minutes: '${tokens[1].text}'", location)
        }
        world.recordOutput(
            "setidletimeout",
            "debug",
            text = minutes.toString(),
            payload = JsonObject().also {
                it.addProperty("minutes", minutes)
                it.addProperty("noOp", true)
                it.addProperty("noOpReason", "Player idle timeout enforcement is not simulated by the sandbox")
            },
        )
    }

    private fun executeStop(tokens: List<CommandToken>, location: SourceLocation?) {
        requireSize(tokens, 1, "stop", location)
        world.recordOutput(
            "stop",
            "debug",
            text = "stop requested",
            payload = JsonObject().also { payload ->
                payload.addProperty("noOp", true)
                payload.addProperty("reason", "Runtime lifecycle is controlled by the host process")
            },
        )
    }

    private fun executeSchedule(tokens: List<CommandToken>, location: SourceLocation?) {
        requireSize(tokens, 2, "schedule <function|clear> ...", location)
        if (tokens[1].text == "clear") {
            requireSize(tokens, 3, "schedule clear <id>", location)
            val id = ResourceLocation.parse(tokens[2].text)
            val removed = world.scheduledFunctions.count { it.id == id }
            world.scheduledFunctions.removeIf { it.id == id }
            world.recordOutput(
                "schedule clear",
                "data",
                text = removed.toString(),
                payload = JsonObject().also { payload ->
                    payload.addProperty("id", id.toString())
                    payload.addProperty("removed", removed)
                    payload.addProperty("remaining", world.scheduledFunctions.size)
                },
            )
            return
        }
        requireSize(tokens, 4, "schedule function <id> <time> [append|replace]", location)
        if (tokens[1].text != "function") {
            unsupportedFeature("Only 'schedule function' and 'schedule clear' are implemented", profile.id, location)
        }
        val id = ResourceLocation.parse(tokens[2].text)
        val delay = parseTime(tokens[3].text, location)
        val mode = tokens.getOrNull(4)?.text ?: "replace"
        var replaced = 0
        if (mode == "replace") {
            replaced = world.scheduledFunctions.count { it.id == id }
            world.scheduledFunctions.removeIf { it.id == id }
        } else if (mode != "append") {
            unsupportedFeature("Unsupported schedule mode '$mode'", profile.id, location)
        }
        val dueTick = world.gameTime + delay
        world.scheduledFunctions += ScheduledFunction(id, dueTick)
        world.recordOutput(
            "schedule function",
            "data",
            text = dueTick.toString(),
            payload = JsonObject().also { payload ->
                payload.addProperty("id", id.toString())
                payload.addProperty("delay", delay)
                payload.addProperty("dueTick", dueTick)
                payload.addProperty("mode", mode)
                payload.addProperty("replaced", replaced)
                payload.addProperty("scheduledCount", world.scheduledFunctions.size)
            },
        )
    }

    private fun executeSetWorldSpawn(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
        val position = if (tokens.size >= 4) parsePosition(tokens, 1, context, location) else context.position
        val angle = tokens.getOrNull(if (tokens.size >= 4) 4 else 1)?.text?.let { parseDouble(it, "spawn angle", location) }
        world.worldSpawn = SpawnPoint(position = position, dimension = ResourceLocation("minecraft", "overworld"), angle = angle)
        world.recordOutput(
            "setworldspawn",
            "data",
            text = "${position.x} ${position.y} ${position.z}",
            payload = JsonObject().also { payload ->
                payload.add("spawn", spawnPointOutput(world.worldSpawn, location))
            },
        )
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
        val position = if (tokens.size >= posIndex + 3) parsePosition(tokens, posIndex, context, location) else context.position
        val angle = tokens.getOrNull(posIndex + 3)?.text?.let { parseDouble(it, "spawn angle", location) }
        targets.forEach {
            it.spawnPoint = SpawnPoint(position = position, dimension = it.dimension, angle = angle)
        }
        world.recordOutput(
            "spawnpoint",
            "data",
            targets = targets.map { it.name },
            text = targets.size.toString(),
            payload = JsonObject().also { payload ->
                payload.addProperty("count", targets.size)
                payload.add("position", positionOutput(position))
                angle?.let { payload.addProperty("angle", it) }
                payload.add(
                    "players",
                    JsonArray().also { players ->
                        targets.forEach { player ->
                            players.add(
                                JsonObject().also { entry ->
                                    entry.addProperty("player", player.name)
                                    entry.addProperty("uuid", player.uuid)
                                    player.spawnPoint?.let { entry.add("spawn", spawnPointOutput(it, location)) }
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    private fun executeTick(tokens: List<CommandToken>, location: SourceLocation?) {
        requireSize(tokens, 2, "tick <query|rate|freeze|unfreeze|step|sprint|stop>", location)
        fun recordTickOutput(
            command: String,
            action: String,
            text: String,
            beforeRate: Double? = null,
            requestedRate: Double? = null,
            beforeFrozen: Boolean? = null,
            beforeGameTime: Long? = null,
            ticks: Int? = null,
        ) {
            world.recordOutput(
                command,
                "data",
                text = text,
                payload = JsonObject().also { payload ->
                    payload.addProperty("action", action)
                    payload.addProperty("rate", world.tickRate)
                    payload.addProperty("frozen", world.tickFrozen)
                    payload.addProperty("gameTime", world.gameTime)
                    beforeRate?.let { payload.addProperty("beforeRate", it) }
                    requestedRate?.let { payload.addProperty("requestedRate", it) }
                    beforeFrozen?.let { payload.addProperty("beforeFrozen", it) }
                    beforeGameTime?.let { before ->
                        payload.addProperty("beforeGameTime", before)
                        payload.addProperty("advancedTicks", world.gameTime - before)
                    }
                    ticks?.let { payload.addProperty("ticks", it) }
                },
            )
        }
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
                val beforeRate = world.tickRate
                val requestedRate = parseDouble(tokens[2].text, "tick rate", location)
                world.tickRate = requestedRate.coerceAtLeast(1.0)
                recordTickOutput(
                    command = "tick rate",
                    action = "rate",
                    text = world.tickRate.toString(),
                    beforeRate = beforeRate,
                    requestedRate = requestedRate,
                )
            }
            "freeze" -> {
                val beforeFrozen = world.tickFrozen
                world.tickFrozen = true
                recordTickOutput(
                    command = "tick freeze",
                    action = "freeze",
                    text = world.tickFrozen.toString(),
                    beforeFrozen = beforeFrozen,
                )
            }
            "unfreeze" -> {
                val beforeFrozen = world.tickFrozen
                world.tickFrozen = false
                recordTickOutput(
                    command = "tick unfreeze",
                    action = "unfreeze",
                    text = world.tickFrozen.toString(),
                    beforeFrozen = beforeFrozen,
                )
            }
            "step", "sprint" -> {
                val action = tokens[1].text
                val ticks = tokens.getOrNull(2)?.text?.let { parseTime(it, location).toInt() } ?: 1
                val beforeGameTime = world.gameTime
                runTicks(ticks)
                recordTickOutput(
                    command = "tick $action",
                    action = action,
                    text = (world.gameTime - beforeGameTime).toString(),
                    beforeGameTime = beforeGameTime,
                    ticks = ticks,
                )
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
        fun recordWorldBorderOutput(
            command: String,
            action: String,
            text: String,
            before: SandboxWorldBorder,
            extra: JsonObject.() -> Unit = {},
        ) {
            world.recordOutput(
                command,
                "data",
                text = text,
                payload = border.toJson().also { payload ->
                    payload.addProperty("action", action)
                    payload.add("before", before.toJson())
                    payload.extra()
                },
            )
        }
        when (tokens[1].text) {
            "get" -> world.recordOutput("worldborder get", "data", text = border.size.toString(), payload = border.toJson())
            "set" -> {
                requireSize(tokens, 3, "worldborder set <distance> [time]", location)
                val before = border.copy()
                val requestedSize = parseDouble(tokens[2].text, "worldborder size", location)
                border.targetSize = requestedSize.coerceAtLeast(1.0)
                border.size = border.targetSize
                border.lerpTimeSeconds = tokens.getOrNull(3)?.text?.let { parseLong(it, "worldborder time", location) } ?: 0
                recordWorldBorderOutput("worldborder set", "set", border.size.toString(), before) {
                    addProperty("requestedSize", requestedSize)
                }
            }
            "add" -> {
                requireSize(tokens, 3, "worldborder add <distance> [time]", location)
                val before = border.copy()
                val delta = parseDouble(tokens[2].text, "worldborder size delta", location)
                border.targetSize = (border.size + delta).coerceAtLeast(1.0)
                border.size = border.targetSize
                border.lerpTimeSeconds = tokens.getOrNull(3)?.text?.let { parseLong(it, "worldborder time", location) } ?: 0
                recordWorldBorderOutput("worldborder add", "add", border.size.toString(), before) {
                    addProperty("delta", delta)
                }
            }
            "center" -> {
                requireSize(tokens, 4, "worldborder center <x> <z>", location)
                val before = border.copy()
                border.centerX = parseCoordinate(tokens[2].text, border.centerX, location)
                border.centerZ = parseCoordinate(tokens[3].text, border.centerZ, location)
                recordWorldBorderOutput(
                    "worldborder center",
                    "center",
                    "${border.centerX} ${border.centerZ}",
                    before,
                )
            }
            "damage" -> {
                requireSize(tokens, 4, "worldborder damage <amount|buffer> <value>", location)
                val before = border.copy()
                val field = tokens[2].text
                val value = when (field) {
                    "amount", "buffer" -> parseDouble(tokens[3].text, "worldborder damage $field", location)
                    else -> unsupportedFeature("Unsupported worldborder damage field '${tokens[2].text}'", profile.id, location)
                }
                when (field) {
                    "amount" -> border.damageAmount = value
                    "buffer" -> border.damageBuffer = value
                }
                recordWorldBorderOutput("worldborder damage", "damage", value.toString(), before) {
                    addProperty("field", field)
                    addProperty("value", value)
                }
            }
            "warning" -> {
                requireSize(tokens, 4, "worldborder warning <distance|time> <value>", location)
                val before = border.copy()
                val field = tokens[2].text
                val value = when (field) {
                    "distance", "time" -> parseInt(tokens[3].text, "worldborder warning $field", location)
                    else -> unsupportedFeature("Unsupported worldborder warning field '${tokens[2].text}'", profile.id, location)
                }
                when (field) {
                    "distance" -> border.warningDistance = value
                    "time" -> border.warningTime = value
                }
                recordWorldBorderOutput("worldborder warning", "warning", value.toString(), before) {
                    addProperty("field", field)
                    addProperty("value", value)
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
        if (action == "test") {
            val id = ResourceLocation.parse(tokens[3].text)
            val criterion = tokens.getOrNull(4)?.text
            val results = JsonArray()
            var passed = 0
            targets.forEach { player ->
                val advancement = datapack.advancement(id)
                val progress = advancements.progress(player, id)
                val done = progress.isDone(advancement.requirements)
                val criterionDone = criterion?.let { progress.criteria[it] == true }
                val success = criterionDone ?: done
                if (success) passed += 1
                results.add(
                    JsonObject().also { result ->
                        result.addProperty("player", player.name)
                        result.addProperty("done", done)
                        criterion?.let {
                            result.addProperty("criterion", it)
                            result.addProperty("criterionDone", criterionDone)
                        }
                    },
                )
            }
            world.recordOutput(
                "advancement test",
                "data",
                text = passed.toString(),
                payload = JsonObject().also { payload ->
                    payload.addProperty("id", id.toString())
                    criterion?.let { payload.addProperty("criterion", it) }
                    payload.addProperty("passed", passed)
                    payload.add("results", results)
                },
            )
            return
        }
        val ids = advancementTargets(mode, tokens.getOrNull(4)?.text, location)
        val criterion = if (mode == "only") tokens.getOrNull(5)?.text else null
        val updates = mutableListOf<Pair<String, AdvancementUpdate>>()
        targets.forEach { player ->
            ids.forEach { advancement ->
                val changed = when (action) {
                    "grant" -> advancements.grant(player, advancement, criterion)
                    "revoke" -> advancements.revoke(player, advancement, criterion)
                    else -> unsupportedFeature("Unsupported advancement action '$action'", profile.id, location)
                }
                changed.forEach { updates += player.name to it }
            }
        }
        world.recordOutput(
            "advancement $action",
            "data",
            targets = targets.map { it.name },
            text = updates.size.toString(),
            payload = JsonObject().also { payload ->
                payload.addProperty("action", action)
                payload.addProperty("mode", mode)
                criterion?.let { payload.addProperty("criterion", it) }
                payload.add("targets", JsonArray().also { array -> targets.map { it.name }.sorted().forEach { array.add(it) } })
                payload.add("advancements", JsonArray().also { array -> ids.forEach { array.add(it.toString()) } })
                payload.addProperty("changed", updates.size)
                payload.add(
                    "updates",
                    JsonArray().also { array ->
                        updates.forEach { (player, update) ->
                            array.add(
                                JsonObject().also { item ->
                                    item.addProperty("player", player)
                                    item.addProperty("advancement", update.advancement.toString())
                                    item.addProperty("criterion", update.criterion)
                                    item.addProperty("done", update.completed)
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    private fun advancementTargets(mode: String, idText: String?, location: SourceLocation?): List<ResourceLocation> =
        when (mode) {
            "everything" -> datapack.advancements.keys.sorted()
            "only" -> listOf(advancementTargetId(idText, location))
            "from" -> {
                val id = advancementTargetId(idText, location)
                listOf(id) + advancementDescendants(id)
            }
            "through" -> {
                val id = advancementTargetId(idText, location)
                advancementAncestors(id, location).asReversed() + id + advancementDescendants(id)
            }
            "until" -> {
                val id = advancementTargetId(idText, location)
                advancementAncestors(id, location).asReversed() + id
            }
            else -> unsupportedFeature("Unsupported advancement mode '$mode'", profile.id, location)
        }.distinct()

    private fun advancementTargetId(idText: String?, location: SourceLocation?): ResourceLocation {
        val id = ResourceLocation.parse(idText ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Advancement id is required", location))
        datapack.advancement(id)
        return id
    }

    private fun advancementAncestors(id: ResourceLocation, location: SourceLocation?): List<ResourceLocation> {
        val ancestors = mutableListOf<ResourceLocation>()
        val seen = mutableSetOf(id)
        var parent = datapack.advancement(id).parent
        while (parent != null) {
            if (!seen.add(parent)) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Advancement parent cycle detected at '$parent'", location)
            }
            val definition = datapack.advancements[parent]
                ?: throw SandboxException(DiagnosticCode.RESOURCE_NOT_FOUND, "Advancement parent '$parent' was not found", location)
            ancestors += parent
            parent = definition.parent
        }
        return ancestors
    }

    private fun advancementDescendants(id: ResourceLocation): List<ResourceLocation> {
        val childrenByParent = datapack.advancements.values.groupBy { it.parent }
        val descendants = mutableListOf<ResourceLocation>()
        val seen = mutableSetOf(id)
        fun visit(parent: ResourceLocation) {
            childrenByParent[parent].orEmpty().sortedBy { it.id.toString() }.forEach { child ->
                if (seen.add(child.id)) {
                    descendants += child.id
                    visit(child.id)
                }
            }
        }
        visit(id)
        return descendants
    }

    private fun executeTeam(command: String, tokens: List<CommandToken>, location: SourceLocation?) {
        requireSize(tokens, 2, "team <add|remove|list|join|leave|empty|modify> ...", location)
        fun teamArray(teams: Collection<SandboxTeam>): JsonArray =
            JsonArray().also { array -> teams.sortedBy { it.name }.forEach { array.add(it.toJson()) } }

        fun memberArray(members: Collection<String>): JsonArray =
            JsonArray().also { array -> members.sorted().forEach { array.add(it) } }

        fun teamMap(teams: Collection<SandboxTeam>): JsonObject =
            JsonObject().also { json -> teams.sortedBy { it.name }.forEach { json.add(it.name, it.toJson()) } }

        fun recordTeamOutput(
            command: String,
            action: String,
            teamName: String,
            text: String,
            team: SandboxTeam? = null,
            before: JsonObject? = null,
            extra: JsonObject.() -> Unit = {},
        ) {
            world.recordOutput(
                command,
                "data",
                text = text,
                payload = (team?.toJson() ?: JsonObject().also { it.addProperty("name", teamName) }).also { payload ->
                    payload.addProperty("action", action)
                    before?.let { payload.add("before", it) }
                    payload.extra()
                },
            )
        }

        when (tokens[1].text) {
            "add" -> {
                requireSize(tokens, 3, "team add <team> [displayName]", location)
                val name = tokens[2].text
                val before = world.teams[name]?.toJson()
                val team = SandboxTeam(name, tokens.getOrNull(3)?.let { CommandTokenizer.tailFrom(command, it) } ?: name)
                world.teams[name] = team
                recordTeamOutput("team add", "add", name, name, team, before) {
                    addProperty("replaced", before != null)
                }
            }
            "remove" -> {
                requireSize(tokens, 3, "team remove <team>", location)
                val name = tokens[2].text
                val removed = world.teams.remove(name)
                recordTeamOutput("team remove", "remove", name, if (removed != null) "1" else "0", before = removed?.toJson()) {
                    addProperty("removed", removed != null)
                }
            }
            "list" -> {
                val team = tokens.getOrNull(2)?.text
                val text = if (team == null) world.teams.keys.sorted().joinToString() else world.teams[team]?.members?.joinToString().orEmpty()
                world.recordOutput(
                    "team list",
                    "data",
                    text = text,
                    payload = JsonObject().also { payload ->
                        payload.addProperty("action", "list")
                        if (team == null) {
                            payload.add("teams", teamArray(world.teams.values))
                            payload.addProperty("count", world.teams.size)
                        } else {
                            val existing = world.teams[team]
                            payload.addProperty("name", team)
                            payload.addProperty("exists", existing != null)
                            payload.add("members", memberArray(existing?.members.orEmpty()))
                            payload.addProperty("count", existing?.members?.size ?: 0)
                        }
                    },
                )
            }
            "join" -> {
                requireSize(tokens, 4, "team join <team> <members...>", location)
                val team = world.teams.getOrPut(tokens[2].text) { SandboxTeam(tokens[2].text) }
                val before = team.toJson()
                val members = tokens.drop(3).map { it.text }
                val added = members.count { it !in team.members }
                team.members += members
                recordTeamOutput("team join", "join", team.name, added.toString(), team, before) {
                    add("requestedMembers", memberArray(members))
                    addProperty("added", added)
                }
            }
            "leave" -> {
                requireSize(tokens, 3, "team leave <members...>", location)
                val beforeTeams = teamMap(world.teams.values)
                val members = tokens.drop(2).map { it.text }
                var removed = 0
                members.forEach { member ->
                    world.teams.values.forEach { team ->
                        if (team.members.remove(member)) removed += 1
                    }
                }
                world.recordOutput(
                    "team leave",
                    "data",
                    text = removed.toString(),
                    payload = JsonObject().also { payload ->
                        payload.addProperty("action", "leave")
                        payload.add("members", memberArray(members))
                        payload.addProperty("removed", removed)
                        payload.add("beforeTeams", beforeTeams)
                        payload.add("teams", teamArray(world.teams.values))
                    },
                )
            }
            "empty" -> {
                requireSize(tokens, 3, "team empty <team>", location)
                val name = tokens[2].text
                val team = world.teams[name]
                val before = team?.toJson()
                val removed = team?.members?.size ?: 0
                team?.members?.clear()
                recordTeamOutput("team empty", "empty", name, removed.toString(), team, before) {
                    addProperty("removed", removed)
                }
            }
            "modify" -> {
                requireSize(tokens, 5, "team modify <team> <option> <value>", location)
                val team = world.teams.getOrPut(tokens[2].text) { SandboxTeam(tokens[2].text) }
                val before = team.toJson()
                val option = tokens[3].text
                val value = if (option == "displayName") {
                    CommandTokenizer.tailFrom(command, tokens[4])
                } else {
                    tokens[4].text
                }
                if (option == "displayName") team.displayName = value else team.options[option] = value
                recordTeamOutput("team modify", "modify", team.name, value, team, before) {
                    addProperty("option", option)
                    addProperty("value", value)
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

    private fun parsePosition(tokens: List<CommandToken>, index: Int, context: ExecutionContext, location: SourceLocation?): Position =
        parsePosition(tokens, index, context.position, location, context.yaw, context.pitch, context.anchoredPosition())

    private fun parsePosition(
        tokens: List<CommandToken>,
        index: Int,
        base: Position,
        location: SourceLocation?,
        yaw: Double = 0.0,
        pitch: Double = 0.0,
        localBase: Position = base,
    ): Position {
        requireSizeFrom(tokens, index, 3, "<x> <y> <z>", location)
        val coordinates = listOf(tokens[index].text, tokens[index + 1].text, tokens[index + 2].text)
        if (coordinates.any { it.startsWith("^") }) {
            if (!coordinates.all { it.startsWith("^") }) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Cannot mix local coordinates '^' with world coordinates", location)
            }
            return localPosition(
                localBase,
                left = parseLocalCoordinate(coordinates[0], "left", location),
                up = parseLocalCoordinate(coordinates[1], "up", location),
                forward = parseLocalCoordinate(coordinates[2], "forward", location),
                yaw = yaw,
                pitch = pitch,
            )
        }
        return Position(
            parseCoordinate(tokens[index].text, base.x, location),
            parseCoordinate(tokens[index + 1].text, base.y, location),
            parseCoordinate(tokens[index + 2].text, base.z, location),
        )
    }

    private fun localPosition(base: Position, left: Double, up: Double, forward: Double, yaw: Double, pitch: Double): Position {
        val yawRadians = Math.toRadians(yaw)
        val pitchRadians = Math.toRadians(pitch)
        val forwardX = -sin(yawRadians) * cos(pitchRadians)
        val forwardY = -sin(pitchRadians)
        val forwardZ = cos(yawRadians) * cos(pitchRadians)
        val leftX = cos(yawRadians)
        val leftZ = sin(yawRadians)
        val upX = forwardY * leftZ
        val upY = forwardZ * leftX - forwardX * leftZ
        val upZ = -forwardY * leftX
        return Position(
            x = base.x + left * leftX + up * upX + forward * forwardX,
            y = base.y + up * upY + forward * forwardY,
            z = base.z + left * leftZ + up * upZ + forward * forwardZ,
        )
    }

    private fun parseLocalCoordinate(raw: String, label: String, location: SourceLocation?): Double =
        when {
            raw == "^" -> 0.0
            raw.startsWith("^") -> raw.drop(1).takeIf { it.isNotBlank() }?.toDoubleOrNull()
                ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid local $label coordinate '$raw'", location)
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid local $label coordinate '$raw'", location)
        }

    private fun parseBlockPos(tokens: List<CommandToken>, index: Int, context: ExecutionContext, location: SourceLocation?): BlockPos =
        parseBlockPos(tokens, index, context.position, location, context.yaw, context.pitch, context.anchoredPosition())

    private fun parseBlockPos(
        tokens: List<CommandToken>,
        index: Int,
        base: Position,
        location: SourceLocation?,
        yaw: Double = 0.0,
        pitch: Double = 0.0,
        localBase: Position = base,
    ): BlockPos {
        val pos = parsePosition(tokens, index, base, location, yaw, pitch, localBase)
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
            isCoordinateToken(it.text)
        }

    private fun isCoordinateToken(raw: String): Boolean =
        raw == "~" || raw.startsWith("~") || raw.startsWith("^") || raw.toDoubleOrNull() != null

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
            raw.startsWith("^") -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Local coordinates '^' require a full 3D coordinate triple", location)
            else -> raw.toDoubleOrNull()
                ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid coordinate '$raw'", location)
        }

    private fun parseOptionalRotation(
        tokens: List<CommandToken>,
        index: Int,
        context: ExecutionContext,
        location: SourceLocation?,
    ): Rotation? {
        if (tokens.size <= index) return null
        requireSizeFrom(tokens, index, 2, "rotation <yaw> <pitch>", location)
        return Rotation(
            yaw = parseRotation(tokens[index].text, context.yaw, "yaw", location),
            pitch = parseRotation(tokens[index + 1].text, context.pitch, "pitch", location),
        )
    }

    private fun parseOptionalTeleportRotation(
        tokens: List<CommandToken>,
        index: Int,
        source: Position,
        context: ExecutionContext,
        location: SourceLocation?,
    ): Rotation? {
        if (tokens.size <= index) return null
        if (tokens[index].text != "facing") {
            return parseOptionalRotation(tokens, index, context, location)
        }
        val target = when (tokens.getOrNull(index + 1)?.text) {
            "entity" -> {
                requireIndex(tokens, index + 2, "${tokens[0].text} ... facing entity <target> [eyes|feet]", location)
                val targetEntity = EntitySelectors.select(world, tokens[index + 2].text, context, location).firstOrNull()
                    ?: throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Teleport facing target '${tokens[index + 2].text}' did not match an entity", location)
                facingPosition(targetEntity, tokens.getOrNull(index + 3)?.text ?: "feet", location)
            }
            null -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "${tokens[0].text} facing requires a position or entity", location)
            else -> {
                requireSizeFrom(tokens, index, 4, "${tokens[0].text} ... facing <x> <y> <z>", location)
                parsePosition(tokens, index + 1, context, location)
            }
        }
        return rotationFacing(source, target, Rotation(context.yaw, context.pitch))
    }

    private fun parseRotation(raw: String, base: Double, label: String, location: SourceLocation?): Double =
        when {
            raw == "~" -> base
            raw.startsWith("~") -> base + (raw.drop(1).takeIf { it.isNotBlank() }?.toDoubleOrNull()
                ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid relative $label '$raw'", location))
            else -> raw.toDoubleOrNull()
                ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid $label '$raw'", location)
        }

    private fun ExecutionContext.facing(target: Position): ExecutionContext {
        val rotation = rotationFacing(position, target, Rotation(yaw, pitch))
        return copy(yaw = rotation.yaw, pitch = rotation.pitch)
    }

    private fun rotationFacing(source: Position, target: Position, fallback: Rotation): Rotation {
        val dx = target.x - source.x
        val dy = target.y - source.y
        val dz = target.z - source.z
        val horizontal = sqrt(dx * dx + dz * dz)
        if (horizontal == 0.0 && dy == 0.0) return fallback
        return Rotation(
            yaw = Math.toDegrees(atan2(-dx, dz)),
            pitch = Math.toDegrees(atan2(-dy, horizontal)),
        )
    }

    private fun ExecutionContext.anchoredPosition(): Position =
        anchoredPosition(position, entity, anchor)

    private fun anchoredPosition(position: Position, entity: SandboxEntity?, anchor: String): Position =
        when (anchor) {
            "eyes" -> position.copy(y = position.y + eyeHeight(entity))
            else -> position
        }

    private fun facingPosition(entity: SandboxEntity, anchor: String, location: SourceLocation?): Position =
        when (anchor) {
            "feet" -> entity.position
            "eyes" -> anchoredPosition(entity.position, entity, "eyes")
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "execute facing entity anchor must be eyes or feet", location)
        }

    private fun eyeHeight(entity: SandboxEntity?): Double =
        when (entity) {
            is SandboxPlayer -> 1.62
            null -> 0.0
            else -> 1.0
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
                val scaled = scaledStoreValue(valueToStore, tokens[index + 4].text, tokens[index + 5].text, location)
                JsonPaths.set(storage, tokens[index + 3].text, scaled)
            }
            "entity" -> {
                requireSizeFrom(tokens, index, 6, "execute store ${tokens[index].text} entity <target> <path> <type> <scale>", location)
                val scaled = scaledStoreValue(valueToStore, tokens[index + 4].text, tokens[index + 5].text, location)
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
                val pos = parseBlockPos(tokens, index + 2, context, location)
                val block = world.requireBlock(pos)
                val scaled = scaledStoreValue(valueToStore, tokens[index + 6].text, tokens[index + 7].text, location)
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

    private fun scaledStoreValue(value: Int, typeText: String, scaleText: String, location: SourceLocation?): JsonPrimitive {
        val scale = scaleText.toDoubleOrNull()
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid execute store scale '$scaleText'", location)
        if (!scale.isFinite()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid execute store scale '$scaleText'", location)
        }
        val scaled = value * scale
        return when (typeText) {
            "byte" -> JsonPrimitive(scaled.toInt().toByte().toInt())
            "short" -> JsonPrimitive(scaled.toInt().toShort().toInt())
            "int" -> JsonPrimitive(scaled.toInt())
            "long" -> JsonPrimitive(scaled.toLong())
            "float" -> JsonPrimitive(scaled.toFloat())
            "double" -> JsonPrimitive(scaled)
            else -> unsupportedFeature("Unsupported execute store numeric type '$typeText'", profile.id, location)
        }
    }

    private fun executeStoreValue(
        storeType: String,
        commandsRun: Int,
        outputsBefore: Int,
        returnValue: Int? = null,
        success: Boolean = commandsRun > 0,
    ): Int =
        when (storeType) {
            "success" -> if (success) 1 else 0
            else -> if (success) returnValue ?: latestNumericOutput(outputsBefore) ?: commandsRun else 0
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
 * @param limits Execution safety limits used to stop runaway tests.
 */
@JvmOverloads
fun createSandbox(
    version: String,
    packs: List<Path>,
    world: SandboxWorld = SandboxWorld(),
    defaultPlayerName: String? = "Steve",
    unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
    limits: SandboxLimits = SandboxLimits(),
): DatapackSandbox {
    val profile = VersionProfiles.get(version)
    val datapack = DatapackLoader.load(packs, profile)
    defaultPlayerName?.let { if (world.players.isEmpty()) world.createPlayer(it) }
    return DatapackSandbox(profile, datapack, world, unsupportedFeatureMode, limits)
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
 * @param limits Execution safety limits used to stop runaway tests.
 */
@JvmOverloads
fun createFunctionSandbox(
    version: String,
    functionSources: List<FunctionSource>,
    world: SandboxWorld = SandboxWorld(),
    defaultPlayerName: String? = "Steve",
    unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
    limits: SandboxLimits = SandboxLimits(),
): DatapackSandbox {
    val profile = VersionProfiles.get(version)
    val datapack = DatapackLoader.loadFunctionSources(functionSources, profile)
    defaultPlayerName?.let { if (world.players.isEmpty()) world.createPlayer(it) }
    return DatapackSandbox(profile, datapack, world, unsupportedFeatureMode, limits)
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
    limits: SandboxLimits = SandboxLimits(),
): DatapackSandbox {
    val profile = VersionProfiles.get(version)
    val datapack = DatapackLoader.loadFunctionSources(packs, functionSources, profile)
    defaultPlayerName?.let { if (world.players.isEmpty()) world.createPlayer(it) }
    return DatapackSandbox(profile, datapack, world, unsupportedFeatureMode, limits)
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
 * @param limits Execution safety limits used to stop runaway tests.
 */
@JvmOverloads
fun createFunctionSandbox(
    version: String,
    functionFile: Path,
    functionId: String = SingleFunctionDatapack.DEFAULT_ID,
    world: SandboxWorld = SandboxWorld(),
    defaultPlayerName: String? = "Steve",
    unsupportedFeatureMode: UnsupportedFeatureMode = UnsupportedFeatureMode.WARN,
    limits: SandboxLimits = SandboxLimits(),
): DatapackSandbox =
    createFunctionSandbox(
        version = version,
        functionSources = listOf(FunctionSource.file(functionId, functionFile)),
        world = world,
        defaultPlayerName = defaultPlayerName,
        unsupportedFeatureMode = unsupportedFeatureMode,
        limits = limits,
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
    limits: SandboxLimits = SandboxLimits(),
): DatapackSandbox =
    createFunctionSandbox(
        version = version,
        functionSources = listOf(FunctionSource.text(functionId, functionText, sourceName)),
        world = world,
        defaultPlayerName = defaultPlayerName,
        unsupportedFeatureMode = unsupportedFeatureMode,
        limits = limits,
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
    limits: SandboxLimits = SandboxLimits(),
): DatapackSandbox =
    createFunctionSandbox(
        version = version,
        packs = packs,
        functionSources = listOf(FunctionSource.text(functionId, functionText, sourceName)),
        world = world,
        defaultPlayerName = defaultPlayerName,
        unsupportedFeatureMode = unsupportedFeatureMode,
        limits = limits,
    )
