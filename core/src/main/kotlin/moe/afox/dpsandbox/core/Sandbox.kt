package moe.afox.dpsandbox.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import moe.afox.dpsandbox.core.command.SandboxItemCommands
import moe.afox.dpsandbox.core.command.SandboxPlacementCommands
import moe.afox.dpsandbox.core.command.blockPosOutput
import moe.afox.dpsandbox.core.command.currentWeatherState
import moe.afox.dpsandbox.core.command.executeAdvancement
import moe.afox.dpsandbox.core.command.executeAttribute
import moe.afox.dpsandbox.core.command.executeBossbar
import moe.afox.dpsandbox.core.command.executeClear
import moe.afox.dpsandbox.core.command.executeClone
import moe.afox.dpsandbox.core.command.executeData
import moe.afox.dpsandbox.core.command.executeDefaultGameMode
import moe.afox.dpsandbox.core.command.executeDifficulty
import moe.afox.dpsandbox.core.command.executeEffect
import moe.afox.dpsandbox.core.command.executeEnchant
import moe.afox.dpsandbox.core.command.executeExecute
import moe.afox.dpsandbox.core.command.executeExperience
import moe.afox.dpsandbox.core.command.executeFill
import moe.afox.dpsandbox.core.command.executeGameMode
import moe.afox.dpsandbox.core.command.executeGamerule
import moe.afox.dpsandbox.core.command.executeGive
import moe.afox.dpsandbox.core.command.executeKill
import moe.afox.dpsandbox.core.command.executeMe
import moe.afox.dpsandbox.core.command.executeParticle
import moe.afox.dpsandbox.core.command.executePlaySound
import moe.afox.dpsandbox.core.command.executePrivateMessage
import moe.afox.dpsandbox.core.command.executeProfilingNoop
import moe.afox.dpsandbox.core.command.executePublish
import moe.afox.dpsandbox.core.command.executeRandom
import moe.afox.dpsandbox.core.command.executeRecipe
import moe.afox.dpsandbox.core.command.executeSaveLifecycleNoop
import moe.afox.dpsandbox.core.command.executeSay
import moe.afox.dpsandbox.core.command.executeSchedule
import moe.afox.dpsandbox.core.command.executeScoreboard
import moe.afox.dpsandbox.core.command.executeSeed
import moe.afox.dpsandbox.core.command.executeServerAdminNoop
import moe.afox.dpsandbox.core.command.executeSetBlock
import moe.afox.dpsandbox.core.command.executeSetIdleTimeout
import moe.afox.dpsandbox.core.command.executeSetWorldSpawn
import moe.afox.dpsandbox.core.command.executeSpawnPoint
import moe.afox.dpsandbox.core.command.executeStop
import moe.afox.dpsandbox.core.command.executeStopSound
import moe.afox.dpsandbox.core.command.executeSummon
import moe.afox.dpsandbox.core.command.executeTag
import moe.afox.dpsandbox.core.command.executeTeam
import moe.afox.dpsandbox.core.command.executeTeamMessage
import moe.afox.dpsandbox.core.command.executeTeleport
import moe.afox.dpsandbox.core.command.executeTellraw
import moe.afox.dpsandbox.core.command.executeTick
import moe.afox.dpsandbox.core.command.executeTime
import moe.afox.dpsandbox.core.command.executeTitle
import moe.afox.dpsandbox.core.command.executeTransfer
import moe.afox.dpsandbox.core.command.executeTrigger
import moe.afox.dpsandbox.core.command.executeWeather
import moe.afox.dpsandbox.core.command.executeWorldBorder
import moe.afox.dpsandbox.core.command.scoreTargets
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

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

/** Non-mutating result of checking one command against a copy of the current sandbox state. */
data class CommandCheckResult(
    /** True when the command can execute successfully in the copied state. */
    val valid: Boolean,
    /** Number of command lines the check would execute. */
    val commandsExecuted: Int = 0,
    /** Number of observable output events the check would produce. */
    val outputs: Int = 0,
    /** Number of snapshot changes the check would produce. */
    val stateChanges: Int = 0,
    /** Structured diagnostic code when [valid] is false. */
    val errorCode: DiagnosticCode? = null,
    /** Human-readable validation message. */
    val message: String = "",
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

private class ReturnSignal(
    val value: Int? = null,
) : RuntimeException() {
    override fun fillInStackTrace(): Throwable = this
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

    internal var commandsExecuted = 0

    @Volatile
    private var executionCancellationRequested = false
    private var functionDepth = 0
    internal var lastFunctionReturnValue: Int? = null
    private val functionStack = mutableListOf<FunctionTraceFrame>()
    private val placementCommands = SandboxPlacementCommands(this)
    private val itemCommands = SandboxItemCommands(this)
    private val checkpoints = linkedMapOf<String, SandboxWorld>()
    private val checkpointNamePattern = Regex("[A-Za-z0-9._-]{1,64}")

    init {
        recordDatapackLoadWarnings()
    }

    /** Requests cooperative cancellation at the next command or tick boundary. */
    fun requestExecutionCancellation() {
        executionCancellationRequested = true
    }

    /** Clears a previous cooperative cancellation request. */
    fun clearExecutionCancellation() {
        executionCancellationRequested = false
    }

    private fun checkExecutionCancellation(location: SourceLocation? = null) {
        if (executionCancellationRequested) {
            throw SandboxException(
                DiagnosticCode.EXECUTION_INTERRUPTED,
                "Sandbox execution interrupted at a command boundary",
                location,
                version = profile.id,
            )
        }
    }

    private fun recordDatapackLoadWarnings() {
        datapack.warnings.forEach { warning ->
            val payload =
                JsonObject().also { json ->
                    warning.code?.let { json.addProperty("code", it.name) }
                    warning.file?.let { json.addProperty("file", it) }
                    warning.version?.let { json.addProperty("version", it) }
                }
            world.recordOutput(
                command = "datapack load",
                channel = "warning",
                text = warning.message,
                payload = payload,
                source = null,
            )
        }
    }

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
            checkExecutionCancellation()
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
    fun runFunction(
        id: ResourceLocation,
        context: ExecutionContext = ExecutionContext(),
    ): ExecutionResult {
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
            functionStack +=
                FunctionTraceFrame(
                    id,
                    function.lines
                        .firstOrNull()
                        ?.location
                        ?.file,
                )
            for (line in function.lines) {
                checkExecutionCancellation(line.location)
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
    fun executeCommand(
        command: String,
        location: SourceLocation? = null,
        context: ExecutionContext = ExecutionContext(),
    ): ExecutionResult {
        checkExecutionCancellation(location)
        val normalized = command.trim().removePrefix("/")
        if (normalized.isBlank()) return ExecutionResult(0)
        val runtimeContext = context.copy(predicateEngine = predicates)
        val before = commandsExecuted
        val outputsBefore = world.outputs.size
        val beforeSnapshot = buildSnapshotJson()
        checkSnapshotSize(beforeSnapshot)
        val source =
            CommandSource(
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
            world.traces +=
                CommandTraceEvent(
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
     * Checks [command] by executing it against an isolated copy of the current world.
     *
     * This catches parser, resource, selector, and state-dependent command errors without
     * changing this sandbox, its traces, outputs, checkpoints, or execution budget.
     */
    fun checkCommand(
        command: String,
        context: ExecutionContext = ExecutionContext(),
    ): CommandCheckResult {
        val normalized = command.trim().removePrefix("/")
        if (normalized.isBlank()) {
            return CommandCheckResult(
                valid = false,
                errorCode = DiagnosticCode.INPUT_FORMAT,
                message = "Command must not be blank",
            )
        }
        val preview =
            DatapackSandbox(
                profile = profile,
                datapack = datapack,
                world = world.checkpointCopy(),
                unsupportedFeatureMode = unsupportedFeatureMode,
                limits = limits,
            )
        return try {
            val result = preview.executeCommand(normalized, context = context)
            val trace = preview.world.traces.lastOrNull()
            CommandCheckResult(
                valid = true,
                commandsExecuted = result.commandsExecuted,
                outputs = trace?.outputs ?: 0,
                stateChanges = trace?.snapshotDiffs?.size ?: 0,
                message = if (result.success) "Command check passed" else "Command is valid; its result would be unsuccessful",
            )
        } catch (error: SandboxException) {
            CommandCheckResult(
                valid = false,
                errorCode = error.code,
                message = error.message,
            )
        } catch (error: RuntimeException) {
            CommandCheckResult(
                valid = false,
                errorCode = DiagnosticCode.COMMAND_ERROR,
                message = error.message ?: error::class.simpleName.orEmpty(),
            )
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
     * Saves or replaces a reusable named checkpoint of all modeled world state.
     * Datapack resources and monotonic execution safety budgets are not part of the checkpoint.
     *
     * @return the deterministic snapshot text stored for the checkpoint.
     */
    @JvmOverloads
    fun saveCheckpoint(name: String = "default"): String {
        validateCheckpointName(name)
        requireCheckpointBoundary()
        if (name !in checkpoints && checkpoints.size >= 32) {
            throw SandboxException(
                DiagnosticCode.COMMAND_ERROR,
                "Sandbox already has 32 checkpoints",
                version = profile.id,
            )
        }
        val snapshot = snapshotString()
        checkpoints[name] = world.checkpointCopy()
        return snapshot
    }

    /**
     * Restores a named checkpoint without consuming it. Repeated restores are supported.
     *
     * @return the deterministic snapshot text after restoration.
     */
    @JvmOverloads
    fun restoreCheckpoint(name: String = "default"): String {
        validateCheckpointName(name)
        requireCheckpointBoundary()
        val checkpoint =
            checkpoints[name]
                ?: throw SandboxException(
                    DiagnosticCode.INPUT_FORMAT,
                    "Unknown checkpoint '$name'",
                    version = profile.id,
                )
        world.restoreCheckpointState(checkpoint)
        executionCancellationRequested = false
        lastFunctionReturnValue = null
        functionStack.clear()
        return snapshotString()
    }

    /** Deletes a checkpoint and reports whether it existed. */
    fun deleteCheckpoint(name: String): Boolean {
        validateCheckpointName(name)
        requireCheckpointBoundary()
        return checkpoints.remove(name) != null
    }

    /** Returns checkpoint names in stable lexical order. */
    fun checkpointNames(): List<String> = checkpoints.keys.sorted()

    private fun validateCheckpointName(name: String) {
        if (!checkpointNamePattern.matches(name)) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "Checkpoint name must be 1-64 ASCII letters, digits, '.', '_' or '-'",
                version = profile.id,
            )
        }
    }

    private fun requireCheckpointBoundary() {
        if (functionDepth != 0 || world.currentCommandSource != null) {
            throw SandboxException(
                DiagnosticCode.COMMAND_ERROR,
                "Checkpoints can only be changed at a command boundary",
                version = profile.id,
            )
        }
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
        var normalized = event.normalized()
        val player = world.requirePlayer(normalized.playerName)
        var updates: List<AdvancementUpdate> = emptyList()
        var advancementFailures: List<AdvancementCriterionFailure> = emptyList()
        var success = false
        var errorCode: DiagnosticCode? = null
        var errorMessage: String? = null
        try {
            normalized = resolvePlayerEventTarget(normalized, player)
            val playerAction =
                SpecialEntitySupport.isInteractionEvent(normalized.type) || SpecialEntitySupport.isAttackEvent(normalized.type)
            if (playerAction && normalized.target != null) {
                val target =
                    normalized.entity
                        ?: throw SandboxException(
                            DiagnosticCode.COMMAND_ERROR,
                            "Player event target '${normalized.target}' did not resolve",
                            version = profile.id,
                        )
                val attack = SpecialEntitySupport.isAttackEvent(normalized.type)
                SpecialEntitySupport.validatePlayerAction(target, attack)
                normalized =
                    normalized.copy(
                        interactionResponse = SpecialEntitySupport.recordInteractionAction(target, player, world.gameTime, attack),
                    )
            }
            normalized.input?.let(player::recordInput)
            applyPlayerEventState(normalized, player)
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
            world.playerEventTraces +=
                PlayerEventTraceEvent.from(
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

    private fun resolvePlayerEventTarget(
        event: PlayerEvent,
        player: SandboxPlayer,
    ): PlayerEvent {
        val target = event.target ?: return event
        val matches =
            EntitySelectors.select(
                world,
                target,
                ExecutionContext(entity = player, predicateEngine = predicates),
            )
        if (matches.size != 1) {
            throw SandboxException(
                DiagnosticCode.COMMAND_ERROR,
                "Player event target '$target' must resolve to exactly one entity, got ${matches.size}",
                version = profile.id,
            )
        }
        return event.copy(entity = matches.single())
    }

    private fun applyPlayerEventState(
        event: PlayerEvent,
        player: SandboxPlayer,
    ) {
        when (event.type) {
            "item_consumed", "consume_item" -> applyConsumedItemEvent(player, event.item)
            "inventory_changed", "item_picked_up", "item_added" -> event.item?.let { player.inventory += it.copyForInventory() }
            "changed_dimension" -> event.toDimension?.let { player.dimension = it }
            "damage" ->
                event.damageAmount?.takeIf { it > 0.0 }?.let { amount ->
                    player.health = (player.health - amount).coerceAtLeast(0.0)
                }
            "death" -> player.health = 0.0
            "recipe_unlocked" -> event.recipe?.let { player.recipes += it }
            "placed_block", "block_placed" ->
                if (event.block != null && event.blockPos != null) {
                    world.setBlock(event.blockPos, SandboxBlock(event.block))
                }
            "block_broken", "broken_block", "broke_block" -> event.blockPos?.let { world.setBlock(it, null) }
        }
    }

    private fun applyConsumedItemEvent(
        player: SandboxPlayer,
        requested: ItemStack?,
    ) {
        val consumed = consumeInventoryItem(player, requested) ?: requested ?: return
        foodRestoredBy(consumed.id)?.let { player.food = (player.food + it).coerceAtMost(20) }
    }

    private fun consumeInventoryItem(
        player: SandboxPlayer,
        requested: ItemStack?,
    ): ItemStack? {
        val index =
            if (requested == null) {
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

    private fun ItemStack.copyForInventory(): ItemStack = copy(components = components.deepCopy(), nbt = nbt.deepCopy())

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
    fun generateLoot(
        table: ResourceLocation,
        contextType: ResourceLocation,
        player: SandboxPlayer? = null,
        seed: Long = world.gameTime,
    ): LootResult =
        loot.generate(
            table,
            LootContext(
                type = contextType,
                predicateContext =
                    PredicateContext(
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

    internal fun executeOne(
        command: String,
        location: SourceLocation?,
        context: ExecutionContext,
    ): Boolean {
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
                "particle" -> executeParticle(command, tokens, location, context)
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

    internal fun handleUnknownCommand(
        root: String,
        command: String,
        location: SourceLocation?,
    ) {
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

    private fun recordUnsupportedWarning(
        command: String,
        error: SandboxException,
        location: SourceLocation?,
    ) {
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

    private fun executeFunction(
        tokens: List<CommandToken>,
        location: SourceLocation?,
        context: ExecutionContext,
    ): ExecutionResult {
        requireSize(tokens, 2, "function <id>", location)
        val result = runFunction(ResourceLocation.parse(tokens[1].text), context)
        lastFunctionReturnValue = result.returnValue
        return result
    }

    private fun executeReturn(
        command: String,
        tokens: List<CommandToken>,
        location: SourceLocation?,
        context: ExecutionContext,
    ): Nothing {
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

    private fun executeHelp(
        tokens: List<CommandToken>,
        location: SourceLocation?,
    ) {
        requireSize(tokens, 1, "help [command]", location)
        val topic = tokens.getOrNull(1)?.text
        val text =
            if (topic == null) {
                profile.commands.roots
                    .sorted()
                    .joinToString(", ")
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

    private fun executeList(
        tokens: List<CommandToken>,
        location: SourceLocation?,
    ) {
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

    private fun executeLoot(
        tokens: List<CommandToken>,
        location: SourceLocation?,
        context: ExecutionContext,
    ) {
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
            payload =
                extraPayload.also { payload ->
                    payload.addProperty("targetKind", targetKind)
                    payload.add("targets", JsonArray().also { array -> targets.forEach { array.add(it) } })
                    payload.add("items", itemStacksJson(items))
                    payload.addProperty("stacks", items.size)
                    payload.addProperty("totalCount", items.sumOf { it.count })
                },
        )
    }

    private fun itemStacksJson(items: List<ItemStack>): JsonArray =
        JsonArray().also { array -> items.forEach { array.add(itemStackOutput(it)) } }

    internal fun itemStackOutput(item: ItemStack): JsonObject =
        item.toJson().also { json ->
            itemTrimResourcePayloads(item)?.let { json.add("trimResources", it) }
            itemComponentResourcePayloads(item)?.let { json.add("componentResources", it) }
        }

    private fun itemComponentResourcePayloads(item: ItemStack): JsonArray? {
        val resources = JsonArray()
        item.components
            .get("minecraft:equippable")
            ?.componentResourceId("asset_id")
            ?.let { itemComponentResourcePayload("minecraft:equippable", "asset_id", "equipment_asset", it) }
            ?.let(resources::add)
        item.components
            .get("minecraft:instrument")
            ?.componentResourceId("value")
            ?.let { itemComponentResourcePayload("minecraft:instrument", "value", "instrument", it) }
            ?.let(resources::add)
        item.components
            .get("minecraft:jukebox_playable")
            ?.componentResourceId("song")
            ?.let { itemComponentResourcePayload("minecraft:jukebox_playable", "song", "jukebox_song", it) }
            ?.let(resources::add)
        item.components.get("minecraft:banner_patterns")?.let { patterns ->
            val entries =
                when {
                    patterns.isJsonArray -> patterns.asJsonArray.toList()
                    patterns.isJsonObject ->
                        patterns.asJsonObject
                            .getAsJsonArray("patterns")
                            ?.toList()
                            .orEmpty()
                    else -> emptyList()
                }
            entries.forEachIndexed { index, entry ->
                entry.componentResourceId("pattern")?.let { id ->
                    itemComponentResourcePayload("minecraft:banner_patterns", "pattern", "banner_pattern", id)?.let { payload ->
                        payload.addProperty("index", index)
                        resources.add(payload)
                    }
                }
            }
        }
        return resources.takeIf { it.size() > 0 }
    }

    private fun JsonElement.componentResourceId(field: String): ResourceLocation? =
        when {
            isJsonPrimitive -> runCatching { ResourceLocation.parse(asString) }.getOrNull()
            isJsonObject ->
                asJsonObject
                    .get(field)
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asString
                    ?.let { runCatching { ResourceLocation.parse(it) }.getOrNull() }
            else -> null
        }

    private fun itemComponentResourcePayload(
        component: String,
        field: String,
        kind: String,
        id: ResourceLocation,
    ): JsonObject? {
        val resource = datapack.rawResources[kind]?.get(id) ?: return null
        if (!resource.root.isJsonObject) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Item component resource '$id' of type '$kind' must be a JSON object")
        }
        return JsonObject().also { payload ->
            payload.addProperty("type", kind)
            payload.addProperty("component", component)
            payload.addProperty("field", field)
            payload.addProperty("id", id.toString())
            payload.addProperty("resource", resource.file)
            payload.addProperty("version", resource.version ?: profile.id)
            payload.add("definition", resource.root.deepCopy())
        }
    }

    private fun itemTrimResourcePayloads(item: ItemStack): JsonArray? {
        val trim =
            item.components
                .get("minecraft:trim")
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject ?: return null
        val resources = JsonArray()
        trimResourcePayload(trim, "material", "trim_material")?.let(resources::add)
        trimResourcePayload(trim, "pattern", "trim_pattern")?.let(resources::add)
        return resources.takeIf { it.size() > 0 }
    }

    private fun trimResourcePayload(
        trim: JsonObject,
        field: String,
        kind: String,
    ): JsonObject? {
        val id =
            trim
                .get(field)
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
                ?.let { runCatching { ResourceLocation.parse(it) }.getOrNull() }
                ?: return null
        val resource = datapack.rawResources[kind]?.get(id) ?: return null
        if (!resource.root.isJsonObject) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Trim resource '$id' of type '$kind' must be a JSON object")
        }
        return JsonObject().also { payload ->
            payload.addProperty("type", kind)
            payload.addProperty("field", field)
            payload.addProperty("id", id.toString())
            payload.addProperty("resource", resource.file)
            payload.addProperty("version", resource.version ?: profile.id)
            payload.add("definition", resource.root.deepCopy())
        }
    }

    private fun executeLocate(
        tokens: List<CommandToken>,
        location: SourceLocation?,
    ) {
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

    private fun executePlace(
        tokens: List<CommandToken>,
        location: SourceLocation?,
        context: ExecutionContext,
    ) = placementCommands.execute(tokens, location, context)

    private fun executeSpectate(
        tokens: List<CommandToken>,
        location: SourceLocation?,
        context: ExecutionContext,
    ) {
        val spectator =
            tokens.getOrNull(2)?.text?.let { resolvePlayers(it, location, context).firstOrNull() }
                ?: context.entity as? SandboxPlayer
        val target =
            tokens
                .getOrNull(
                    1,
                )?.text
                ?.takeIf { it != "stop" }
                ?.let { EntitySelectors.select(world, it, context, location).firstOrNull() }
        val payload = JsonObject()
        spectator?.let {
            it.gameMode = "spectator"
            payload.addProperty("spectator", it.name)
        }
        target?.let { payload.addProperty("target", it.uuid) }
        world.recordOutput(
            "spectate",
            "data",
            text =
                if (target ==
                    null
                ) {
                    "spectate cleared"
                } else {
                    "spectating ${target.uuid}"
                },
            payload = payload,
        )
    }

    private fun executeSpreadPlayers(
        tokens: List<CommandToken>,
        location: SourceLocation?,
        context: ExecutionContext,
    ) {
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

    private fun executeLootReplace(
        tokens: List<CommandToken>,
        location: SourceLocation?,
        context: ExecutionContext,
    ) {
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

    private fun parseLootSource(
        tokens: List<CommandToken>,
        index: Int,
        context: ExecutionContext,
        location: SourceLocation?,
    ): List<ItemStack> {
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
                generateLootItems(
                    ResourceLocation.parse(tokens[index + 1].text),
                    ResourceLocation("minecraft", "fishing"),
                    context,
                    origin = origin,
                    tool = tool,
                )
            }
            "mine" -> {
                requireSizeFrom(tokens, index, 4, "loot source mine <pos> [tool]", location)
                val pos = parseBlockPos(tokens, index + 1, context, location)
                val block = world.block(pos) ?: return emptyList()
                val tool = tokens.getOrNull(index + 4)?.text?.let { lootTool(it, location) }
                val table =
                    block.nbt
                        .get("LootTable")
                        ?.takeIf { it.isJsonPrimitive }
                        ?.asString
                        ?.let(ResourceLocation::parse)
                if (table == null) {
                    listOf(ItemStack(block.id))
                } else {
                    generateLootItems(
                        table,
                        ResourceLocation("minecraft", "block"),
                        context,
                        origin = Position(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble()),
                        tool = tool,
                        blockState = block,
                    )
                }
            }
            "kill" -> {
                requireIndex(tokens, index + 1, "loot source kill <target>", location)
                EntitySelectors.select(world, tokens[index + 1].text, context, location).flatMap { entity ->
                    val table =
                        entity.nbt
                            .get("DeathLootTable")
                            ?.takeIf { it.isJsonPrimitive }
                            ?.asString
                            ?.let(ResourceLocation::parse)
                    table
                        ?.let {
                            generateLootItems(
                                it,
                                ResourceLocation("minecraft", "entity"),
                                context,
                                origin = entity.position,
                                thisEntity = entity,
                            )
                        }.orEmpty()
                }
            }
            "entity" -> {
                requireSizeFrom(tokens, index, 3, "loot source entity <table> <target>", location)
                val table = ResourceLocation.parse(tokens[index + 1].text)
                EntitySelectors.select(world, tokens[index + 2].text, context, location).flatMap { entity ->
                    generateLootItems(
                        table,
                        ResourceLocation("minecraft", "entity"),
                        context,
                        origin = entity.position,
                        thisEntity = entity,
                    )
                }
            }
            "block" -> {
                requireSizeFrom(tokens, index, 5, "loot source block <table> <pos> [tool]", location)
                val table = ResourceLocation.parse(tokens[index + 1].text)
                val pos = parseBlockPos(tokens, index + 2, context, location)
                val block = world.block(pos) ?: return emptyList()
                val tool = tokens.getOrNull(index + 5)?.text?.let { lootTool(it, location) }
                generateLootItems(
                    table,
                    ResourceLocation("minecraft", "block"),
                    context,
                    origin = Position(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble()),
                    tool = tool,
                    blockState = block,
                )
            }
            "equipment" -> {
                requireSizeFrom(tokens, index, 4, "loot source equipment <table> <target> <slot>", location)
                val table = ResourceLocation.parse(tokens[index + 1].text)
                EntitySelectors.select(world, tokens[index + 2].text, context, location).flatMap { entity ->
                    val tool = entityItemAccess(entity, tokens[index + 3].text, location).get()
                    generateLootItems(
                        table,
                        ResourceLocation("minecraft", "equipment"),
                        context,
                        origin = entity.position,
                        thisEntity = entity,
                        tool = tool,
                    )
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
        return loot
            .generate(
                table,
                LootContext(
                    type = contextType,
                    predicateContext =
                        PredicateContext(
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

    private fun lootTool(
        raw: String,
        location: SourceLocation?,
    ): ItemStack = parseItemStackArgument(raw, 1, location)

    private fun insertLootIntoBlock(
        pos: BlockPos,
        items: List<ItemStack>,
        location: SourceLocation?,
    ) {
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

    internal fun replaceBlockItem(
        pos: BlockPos,
        slot: Int,
        item: ItemStack?,
        location: SourceLocation?,
    ) {
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

    private fun blockItemJson(item: ItemStack): JsonObject = item.toNbtJson()

    private fun spawnLootItems(
        position: Position,
        items: List<ItemStack>,
        dimension: ResourceLocation,
    ) {
        items.forEach { item ->
            world.entities +=
                SandboxEntity(
                    type = ResourceLocation("minecraft", "item"),
                    position = position,
                    dimension = dimension,
                    nbt = JsonObject().also { it.add("Item", blockItemJson(item)) },
                )
        }
    }

    private fun executeDatapack(
        tokens: List<CommandToken>,
        location: SourceLocation?,
    ) {
        requireSize(tokens, 2, "datapack <list|enable|disable> ...", location)
        when (tokens[1].text) {
            "list" -> {
                if (tokens.size > 3) {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Expected: datapack list [available|enabled]", location)
                }
                val filter = tokens.getOrNull(2)?.text ?: "all"
                if (filter != "all" && filter != "available" && filter != "enabled") {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Expected: datapack list [available|enabled]", location)
                }
                val summary = datapack.resourceSummary()
                val packs =
                    datapack.resourceIndex
                        .map { it.pack }
                        .distinct()
                        .sorted()
                val payload = JsonObject()
                payload.addProperty("filter", filter)
                payload.addProperty("packCount", packs.size)
                payload.add(
                    "packs",
                    JsonArray().also { packArray -> packs.forEach { packArray.add(it) } },
                )
                payload.addProperty("functions", summary.functions)
                payload.addProperty("lootTables", summary.lootTables)
                payload.addProperty("predicates", summary.predicates)
                payload.addProperty("advancements", summary.advancements)
                payload.addProperty("recipes", summary.recipes)
                payload.addProperty("itemModifiers", summary.itemModifiers)
                payload.addProperty("tags", summary.tags)
                payload.addProperty("rawResourceKinds", summary.rawResourceKinds)
                payload.addProperty("rawResources", summary.rawResources)
                payload.addProperty("resourceIndex", summary.resourceIndex)
                payload.addProperty("activeResources", summary.activeResources)
                payload.addProperty("overriddenResources", summary.overriddenResources)
                payload.add(
                    "resourceOverrides",
                    JsonArray().also { overrides -> summary.overlays.forEach { overrides.add(it.toResourceIndexPayload()) } },
                )
                payload.add(
                    "missingReferences",
                    JsonArray().also { references -> summary.missingReferences.forEach { references.add(it.toMissingReferencePayload()) } },
                )
                world.recordOutput("datapack list", "data", text = "Loaded datapack resources", payload = payload)
            }
            "enable", "disable" -> {
                requireSize(tokens, 3, "datapack ${tokens[1].text} <name>", location)
                val action = tokens[1].text
                val name = tokens[2].text
                world.recordOutput(
                    "datapack $action",
                    "warning",
                    text = "Datapack order is fixed at sandbox creation; '$action' is accepted as a no-op",
                    payload =
                        JsonObject().also { payload ->
                            payload.addProperty("action", action)
                            payload.addProperty("name", name)
                            payload.addProperty("noOp", true)
                            payload.addProperty("noOpReason", "Datapack order is fixed at sandbox creation")
                            payload.add(
                                "arguments",
                                JsonArray().also { arguments ->
                                    tokens.drop(3).forEach { arguments.add(it.text) }
                                },
                            )
                        },
                )
            }
            else -> unsupportedFeature("Unsupported datapack action '${tokens[1].text}'", profile.id, location)
        }
    }

    private fun DatapackMissingResourceReference.toMissingReferencePayload(): JsonObject =
        JsonObject().also { payload ->
            payload.addProperty("source", source)
            payload.addProperty("type", type)
            payload.addProperty("id", id.toString())
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

    private fun executeReload(
        tokens: List<CommandToken>,
        location: SourceLocation?,
    ) {
        requireSize(tokens, 1, "reload", location)
        world.recordOutput(
            "reload",
            "data",
            text = "Reload is a no-op inside this immutable sandbox instance",
            payload =
                JsonObject().also { payload ->
                    payload.addProperty("action", "reload")
                    payload.addProperty("noOp", true)
                    payload.addProperty(
                        "noOpReason",
                        "Datapack resources are immutable for this sandbox instance; use REPL reload or recreate the sandbox",
                    )
                },
        )
    }

    private fun executeForceload(
        tokens: List<CommandToken>,
        location: SourceLocation?,
        context: ExecutionContext,
    ) {
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
                    val didChange =
                        if (tokens[1].text == "add") {
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
                        payload =
                            JsonObject().also { payload ->
                                payload.add(
                                    "position",
                                    JsonObject().also {
                                        it.addProperty("x", pos.x)
                                        it.addProperty("z", pos.z)
                                    },
                                )
                                payload.add(
                                    "chunk",
                                    JsonObject().also {
                                        it.addProperty("x", chunk.x)
                                        it.addProperty("z", chunk.z)
                                    },
                                )
                                payload.addProperty("forced", forced)
                                payload.addProperty("forcedCount", world.forcedChunks.size)
                            },
                    )
                } else {
                    val payload = JsonArray()
                    world.forcedChunks.sorted().forEach { chunk ->
                        payload.add(
                            JsonObject().also {
                                it.addProperty("x", chunk.x)
                                it.addProperty("z", chunk.z)
                            },
                        )
                    }
                    world.recordOutput(
                        "forceload query",
                        "data",
                        text = world.forcedChunks.sorted().joinToString { "${it.x},${it.z}" },
                        payload = payload,
                    )
                }
            }
            else -> unsupportedFeature("Unsupported forceload action '${tokens[1].text}'", profile.id, location)
        }
    }

    private fun recordForceloadMutationOutput(
        action: String,
        chunks: List<ChunkPos>,
        changed: Int,
    ) {
        world.recordOutput(
            "forceload $action",
            "data",
            targets = chunks.map { "${it.x},${it.z}" },
            text = changed.toString(),
            payload =
                JsonObject().also { payload ->
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

    private fun executeFillBiome(
        tokens: List<CommandToken>,
        location: SourceLocation?,
        context: ExecutionContext,
    ) {
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
        for (x in xs) {
            for (y in ys) {
                for (z in zs) {
                    val pos = BlockPos(x, y, z)
                    if (filter == null || world.biomes[pos] == filter) {
                        world.biomes[pos] = biome
                        changedPositions += pos
                    }
                }
            }
        }
        world.recordOutput(
            "fillbiome",
            "data",
            targets = changedPositions.map { it.toString() },
            text = changedPositions.size.toString(),
            payload =
                JsonObject().also { payload ->
                    payload.addProperty("biome", biome.toString())
                    filter?.let { payload.addProperty("filter", it.toString()) }
                    payload.addProperty("volume", volume)
                    payload.addProperty("changed", changedPositions.size)
                    payload.add("from", blockPosOutput(from))
                    payload.add("to", blockPosOutput(to))
                    payload.add(
                        "positions",
                        JsonArray().also { positions ->
                            changedPositions.forEach { pos -> positions.add(pos.toString()) }
                        },
                    )
                },
        )
    }

    private fun executeDamage(
        tokens: List<CommandToken>,
        location: SourceLocation?,
        context: ExecutionContext,
    ) {
        requireSize(tokens, 3, "damage <target> <amount> [damageType] ...", location)
        val amount =
            tokens[2].text.toDoubleOrNull()
                ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid damage amount '${tokens[2].text}'", location)
        val damageContext = damageCommandContext(tokens, context, location)
        val sourceEntity = damageContext.directEntity ?: damageContext.causingEntity
        val targets =
            EntitySelectors
                .select(world, tokens[1].text, context, location)
                .filterNot(SpecialEntitySupport::isDamageImmune)
        val damaged = JsonArray()
        targets.forEach { entity ->
            val beforeHealth =
                if (entity is SandboxPlayer) {
                    entity.health
                } else {
                    entity.fullNbt(profile, location).get("Health")?.asDouble
                        ?: 20.0
                }
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
            payload =
                JsonObject().also { payload ->
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

    private fun damageTypePayload(
        id: ResourceLocation,
        location: SourceLocation?,
    ): JsonObject? {
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

    private fun JsonObject.copyPrimitive(
        source: String,
        target: JsonObject,
        targetName: String,
        location: SourceLocation?,
    ) {
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

    private fun damageCommandContext(
        tokens: List<CommandToken>,
        context: ExecutionContext,
        location: SourceLocation?,
    ): DamageCommandContext {
        var index = 3
        val damageSource =
            tokens
                .getOrNull(index)
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

    private fun damageSourceEntity(
        tokens: List<CommandToken>,
        markerIndex: Int,
        context: ExecutionContext,
        location: SourceLocation?,
    ): SandboxEntity {
        val selector =
            tokens.getOrNull(markerIndex + 1)
                ?: throw SandboxException(
                    DiagnosticCode.INPUT_FORMAT,
                    "damage ${tokens[markerIndex].text} requires an entity selector",
                    location,
                )
        return EntitySelectors.select(world, selector.text, context, location).firstOrNull()
            ?: throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Damage source '${selector.text}' did not match an entity", location)
    }

    private val damageCommandMarkers = setOf("at", "by", "from")

    private fun executeRide(
        tokens: List<CommandToken>,
        location: SourceLocation?,
        context: ExecutionContext,
    ) {
        requireSize(tokens, 3, "ride <target> <mount|dismount> ...", location)
        val riders = EntitySelectors.select(world, tokens[1].text, context, location)
        when (tokens[2].text) {
            "mount" -> {
                requireSize(tokens, 4, "ride <target> mount <vehicle>", location)
                val vehicle =
                    EntitySelectors.select(world, tokens[3].text, context, location).firstOrNull()
                        ?: throw SandboxException(
                            DiagnosticCode.COMMAND_ERROR,
                            "Ride vehicle '${tokens[3].text}' did not match an entity",
                            location,
                        )
                riders.forEach { rider ->
                    rider.vehicle = vehicle.uuid
                    vehicle.passengers += rider.uuid
                }
                recordRideMountOutput(riders, vehicle)
            }
            "dismount" -> {
                val previousVehicles = riders.map { rider -> rider to rider.vehicle }
                riders.forEach { rider ->
                    world.entities
                        .firstOrNull { it.uuid == rider.vehicle }
                        ?.passengers
                        ?.remove(rider.uuid)
                    rider.vehicle = null
                }
                recordRideDismountOutput(previousVehicles)
            }
            else -> unsupportedFeature("Unsupported ride action '${tokens[2].text}'", profile.id, location)
        }
    }

    private fun recordRideMountOutput(
        riders: List<SandboxEntity>,
        vehicle: SandboxEntity,
    ) {
        world.recordOutput(
            "ride mount",
            "data",
            targets = riders.map { it.scoreHolder },
            text = riders.size.toString(),
            payload =
                JsonObject().also { payload ->
                    payload.addProperty("action", "mount")
                    payload.addProperty("count", riders.size)
                    payload.addProperty("vehicle", vehicle.scoreHolder)
                    payload.addProperty("vehicleUuid", vehicle.uuid)
                    payload.addProperty("vehicleType", vehicle.type.toString())
                    payload.add(
                        "riders",
                        JsonArray().also { array ->
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
                        },
                    )
                },
        )
    }

    private fun recordRideDismountOutput(previousVehicles: List<Pair<SandboxEntity, String?>>) {
        world.recordOutput(
            "ride dismount",
            "data",
            targets = previousVehicles.map { it.first.scoreHolder },
            text = previousVehicles.size.toString(),
            payload =
                JsonObject().also { payload ->
                    payload.addProperty("action", "dismount")
                    payload.addProperty("count", previousVehicles.size)
                    payload.add(
                        "riders",
                        JsonArray().also { array ->
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
                        },
                    )
                },
        )
    }

    private fun executeRotate(
        tokens: List<CommandToken>,
        location: SourceLocation?,
        context: ExecutionContext,
    ) {
        requireSize(tokens, 4, "rotate <targets> <yaw> <pitch>", location)
        val yaw =
            tokens[2].text.toDoubleOrNull()
                ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid yaw '${tokens[2].text}'", location)
        val pitch =
            tokens[3].text.toDoubleOrNull()
                ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid pitch '${tokens[3].text}'", location)
        val rotated =
            EntitySelectors.select(world, tokens[1].text, context, location).map { entity ->
                val before = Rotation(entity.yaw, entity.pitch)
                entity.yaw = yaw
                entity.pitch = pitch
                SpecialEntitySupport.scheduleDisplayTeleport(entity, entity.position, before.yaw, before.pitch)
                entity to before
            }
        world.recordOutput(
            "rotate",
            "data",
            targets = rotated.map { it.first.scoreHolder },
            text = rotated.size.toString(),
            payload =
                JsonObject().also { payload ->
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

    private fun executeItem(
        tokens: List<CommandToken>,
        location: SourceLocation?,
        context: ExecutionContext,
    ) = itemCommands.execute(tokens, location, context)

    internal fun parseItemStackArgument(raw: String, count: Int, location: SourceLocation?): ItemStack =
        itemCommands.parseItemStackArgument(raw, count, location)

    internal fun ItemStack.matchesClearItem(expected: ItemStack): Boolean = itemCommands.matchesClearItem(this, expected)

    internal fun ItemStack.copyStack(): ItemStack = itemCommands.copyStackValue(this)

    internal fun entityItemAccess(
        entity: SandboxEntity,
        rawSlot: String,
        location: SourceLocation?,
    ): SandboxItemCommands.EntityItemAccess = itemCommands.entityItemAccess(entity, rawSlot, location)

    internal data class Rotation(
        val yaw: Double,
        val pitch: Double,
    )

    internal data class EntityMove(
        val entity: SandboxEntity,
        val beforePosition: Position,
        val beforeDimension: ResourceLocation,
        val beforeYaw: Double,
        val beforePitch: Double,
    )

    internal fun moveEntities(
        entities: List<SandboxEntity>,
        position: Position,
        rotation: Rotation? = null,
        dimension: ResourceLocation? = null,
    ): List<EntityMove> =
        entities.map { entity ->
            val move =
                EntityMove(
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
            SpecialEntitySupport.scheduleDisplayTeleport(entity, move.beforePosition, move.beforeYaw, move.beforePitch)
            if (entity is SandboxPlayer) {
                advancements.handle(PlayerEvent(entity.name, "moved"))
            }
            move
        }

    internal fun recordTeleportOutput(
        command: String,
        moved: List<EntityMove>,
    ) {
        world.recordOutput(
            command,
            "data",
            targets = moved.map { it.entity.scoreHolder },
            text = moved.size.toString(),
            payload =
                JsonObject().also { payload ->
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

    internal fun positionOutput(position: Position): JsonObject =
        JsonObject().also { pos ->
            pos.addProperty("x", position.x)
            pos.addProperty("y", position.y)
            pos.addProperty("z", position.z)
        }

    internal fun JsonObject.addDimensionMetadata(
        name: String,
        dimension: ResourceLocation,
        location: SourceLocation?,
    ) {
        dimensionPayload(dimension, location)?.let { add(name, it) }
    }

    internal fun spawnPointOutput(
        spawn: SpawnPoint,
        location: SourceLocation?,
    ): JsonObject =
        spawn.toJson().also { payload ->
            payload.addDimensionMetadata("dimensionResource", spawn.dimension, location)
        }

    private fun dimensionPayload(
        id: ResourceLocation,
        location: SourceLocation?,
    ): JsonObject? {
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
                    throw SandboxException(
                        DiagnosticCode.INPUT_FORMAT,
                        "Dimension resource '$id' field 'type' must be a primitive id",
                        location,
                    )
                }
                val type = ResourceLocation.parse(typeElement.asString)
                payload.addProperty("type", type.toString())
                dimensionTypePayload(type, location)?.let { payload.add("dimensionType", it) }
            }
        }
    }

    private fun dimensionTypePayload(
        id: ResourceLocation,
        location: SourceLocation?,
    ): JsonObject? {
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

    internal fun resolvePlayers(
        target: String,
        location: SourceLocation?,
        context: ExecutionContext = ExecutionContext(),
    ): List<SandboxPlayer> =
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

    internal fun scoreMatches(
        value: Int,
        range: String,
        location: SourceLocation?,
    ): Boolean {
        if (!range.contains("..")) return value == parseInt(range, "score range", location)
        val startText = range.substringBefore("..")
        val endText = range.substringAfter("..")
        val start = if (startText.isBlank()) Int.MIN_VALUE else parseInt(startText, "score range start", location)
        val end = if (endText.isBlank()) Int.MAX_VALUE else parseInt(endText, "score range end", location)
        return value in start..end
    }

    internal fun parseSummonNbt(
        raw: String,
        location: SourceLocation?,
    ): JsonObject {
        val json = JsonValues.parse(raw, location)
        if (!json.isJsonObject) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Summon NBT must be an object", location)
        }
        return json.asJsonObject
    }

    internal fun extractTags(nbt: JsonObject): Set<String> {
        val tags = nbt.get("Tags") ?: nbt.get("tags") ?: return emptySet()
        if (!tags.isJsonArray) return emptySet()
        return tags.asJsonArray.mapNotNull { if (it.isJsonPrimitive) it.asString else null }.toSet()
    }

    internal fun parsePosition(
        tokens: List<CommandToken>,
        index: Int,
        context: ExecutionContext,
        location: SourceLocation?,
    ): Position = parsePosition(tokens, index, context.position, location, context.yaw, context.pitch, context.anchoredPosition())

    internal fun parsePosition(
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

    private fun localPosition(
        base: Position,
        left: Double,
        up: Double,
        forward: Double,
        yaw: Double,
        pitch: Double,
    ): Position {
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

    private fun parseLocalCoordinate(
        raw: String,
        label: String,
        location: SourceLocation?,
    ): Double =
        when {
            raw == "^" -> 0.0
            raw.startsWith("^") ->
                raw.drop(1).takeIf { it.isNotBlank() }?.toDoubleOrNull()
                    ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid local $label coordinate '$raw'", location)
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid local $label coordinate '$raw'", location)
        }

    internal fun parseBlockPos(
        tokens: List<CommandToken>,
        index: Int,
        context: ExecutionContext,
        location: SourceLocation?,
    ): BlockPos = parseBlockPos(tokens, index, context.position, location, context.yaw, context.pitch, context.anchoredPosition())

    internal fun parseBlockPos(
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

    private fun parseColumnPos(
        tokens: List<CommandToken>,
        index: Int,
        base: Position,
        location: SourceLocation?,
    ): BlockPos {
        requireSizeFrom(tokens, index, 2, "<x> <z>", location)
        return BlockPos(
            floor(parseCoordinate(tokens[index].text, base.x, location)).toInt(),
            0,
            floor(parseCoordinate(tokens[index + 1].text, base.z, location)).toInt(),
        )
    }

    internal fun isCoordinateTriple(
        tokens: List<CommandToken>,
        index: Int,
    ): Boolean =
        index + 2 < tokens.size &&
            listOf(tokens[index], tokens[index + 1], tokens[index + 2]).all {
                isCoordinateToken(it.text)
            }

    internal fun isCoordinateToken(raw: String): Boolean =
        raw == "~" || raw.startsWith("~") || raw.startsWith("^") || raw.toDoubleOrNull() != null

    internal fun matchesBlock(
        pos: BlockPos,
        rawBlock: String,
        location: SourceLocation?,
    ): Boolean {
        val expected = parseBlockArgument(rawBlock, location)
        val actual = world.block(pos) ?: return expected.id == ResourceLocation("minecraft", "air")
        if (actual.id != expected.id) return false
        return expected.properties.all { (key, value) -> actual.properties[key] == value }
    }

    internal fun parseBlockArgument(
        raw: String,
        location: SourceLocation?,
    ): BlockArgument {
        val nbtStart = raw.indexOf('{')
        val blockStateText = if (nbtStart >= 0) raw.substring(0, nbtStart) else raw
        val nbt =
            if (nbtStart >= 0) {
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
            blockStateText
                .substring(stateStart + 1, blockStateText.length - 1)
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

    internal fun parseCoordinate(
        raw: String,
        base: Double,
    ): Double = parseCoordinate(raw, base, null)

    internal fun parseCoordinate(
        raw: String,
        base: Double,
        location: SourceLocation?,
    ): Double =
        when {
            raw == "~" -> base
            raw.startsWith("~") ->
                base + (
                    raw.drop(1).takeIf { it.isNotBlank() }?.toDoubleOrNull()
                        ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid relative coordinate '$raw'", location)
                )
            raw.startsWith(
                "^",
            ) -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Local coordinates '^' require a full 3D coordinate triple", location)
            else ->
                raw.toDoubleOrNull()
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

    internal fun parseOptionalTeleportRotation(
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
        val target =
            when (tokens.getOrNull(index + 1)?.text) {
                "entity" -> {
                    requireIndex(tokens, index + 2, "${tokens[0].text} ... facing entity <target> [eyes|feet]", location)
                    val targetEntity =
                        EntitySelectors.select(world, tokens[index + 2].text, context, location).firstOrNull()
                            ?: throw SandboxException(
                                DiagnosticCode.COMMAND_ERROR,
                                "Teleport facing target '${tokens[index + 2].text}' did not match an entity",
                                location,
                            )
                    facingPosition(targetEntity, tokens.getOrNull(index + 3)?.text ?: "feet", location)
                }
                null -> throw SandboxException(
                    DiagnosticCode.INPUT_FORMAT,
                    "${tokens[0].text} facing requires a position or entity",
                    location,
                )
                else -> {
                    requireSizeFrom(tokens, index, 4, "${tokens[0].text} ... facing <x> <y> <z>", location)
                    parsePosition(tokens, index + 1, context, location)
                }
            }
        return rotationFacing(source, target, Rotation(context.yaw, context.pitch))
    }

    internal fun parseRotation(
        raw: String,
        base: Double,
        label: String,
        location: SourceLocation?,
    ): Double =
        when {
            raw == "~" -> base
            raw.startsWith("~") ->
                base + (
                    raw.drop(1).takeIf { it.isNotBlank() }?.toDoubleOrNull()
                        ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid relative $label '$raw'", location)
                )
            else ->
                raw.toDoubleOrNull()
                    ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid $label '$raw'", location)
        }

    internal fun ExecutionContext.facing(target: Position): ExecutionContext {
        val rotation = rotationFacing(position, target, Rotation(yaw, pitch))
        return copy(yaw = rotation.yaw, pitch = rotation.pitch)
    }

    private fun rotationFacing(
        source: Position,
        target: Position,
        fallback: Rotation,
    ): Rotation {
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

    private fun ExecutionContext.anchoredPosition(): Position = anchoredPosition(position, entity, anchor)

    internal fun anchoredPosition(
        position: Position,
        entity: SandboxEntity?,
        anchor: String,
    ): Position =
        when (anchor) {
            "eyes" -> position.copy(y = position.y + eyeHeight(entity))
            else -> position
        }

    internal fun facingPosition(
        entity: SandboxEntity,
        anchor: String,
        location: SourceLocation?,
    ): Position =
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

    internal fun parseTime(
        raw: String,
        location: SourceLocation?,
    ): Long {
        val suffix = raw.last()
        val amountText = if (suffix.isLetter()) raw.dropLast(1) else raw
        val amount =
            amountText.toLongOrNull()
                ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid schedule time '$raw'", location)
        val ticks =
            when (suffix) {
                't' -> amount
                's' -> amount * 20
                'd' -> amount * 24000
                else ->
                    if (suffix.isLetter()) {
                        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Unsupported schedule time unit '$suffix'", location)
                    } else {
                        amount
                    }
            }
        return max(1, ticks)
    }

    internal fun storeExecuteValue(
        tokens: List<CommandToken>,
        index: Int,
        value: Int,
        location: SourceLocation?,
        context: ExecutionContext,
    ) {
        requireSizeFrom(tokens, index, 4, "execute store <result|success> <score|storage|entity|block|bossbar> ...", location)
        val valueToStore =
            if (tokens[index].text == "success") {
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
                val target =
                    EntitySelectors.select(world, tokens[index + 2].text, context, location).singleOrNull()
                        ?: throw SandboxException(
                            DiagnosticCode.COMMAND_ERROR,
                            "execute store entity requires exactly one target",
                            location,
                        )
                if (target is SandboxPlayer) {
                    throw SandboxException(
                        DiagnosticCode.COMMAND_ERROR,
                        "Player NBT is read-only in this sandbox; use player events or movement commands",
                        location,
                    )
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
                val bar =
                    world.bossbars[ResourceLocation.parse(tokens[index + 2].text)]
                        ?: throw SandboxException(
                            DiagnosticCode.COMMAND_ERROR,
                            "Bossbar '${tokens[index + 2].text}' does not exist",
                            location,
                        )
                when (tokens[index + 3].text) {
                    "value" -> bar.value = valueToStore
                    "max" -> bar.max = valueToStore.coerceAtLeast(1)
                    else -> unsupportedFeature("Unsupported execute store bossbar field '${tokens[index + 3].text}'", profile.id, location)
                }
            }
            else -> unsupportedFeature("Unsupported execute store target '${tokens[index + 1].text}'", profile.id, location)
        }
    }

    private fun scaledStoreValue(
        value: Int,
        typeText: String,
        scaleText: String,
        location: SourceLocation?,
    ): JsonPrimitive {
        val scale =
            scaleText.toDoubleOrNull()
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

    internal fun executeStoreValue(
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
        world.outputs
            .drop(outputsBefore)
            .asReversed()
            .firstNotNullOfOrNull { output ->
                output.text
                    .trim()
                    .toDoubleOrNull()
                    ?.toInt()
            }

    internal fun outputSender(context: ExecutionContext): String =
        when (val entity = context.entity) {
            is SandboxPlayer -> entity.name
            null -> "Server"
            else -> entity.uuid
        }

    internal fun parseInt(
        raw: String,
        label: String,
        location: SourceLocation?,
    ): Int =
        raw.toIntOrNull()
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid $label: '$raw'", location)

    internal fun parseLong(
        raw: String,
        label: String,
        location: SourceLocation?,
    ): Long =
        raw.toLongOrNull()
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid $label: '$raw'", location)

    internal fun parseDouble(
        raw: String,
        label: String,
        location: SourceLocation?,
    ): Double =
        raw.toDoubleOrNull()
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid $label: '$raw'", location)

    internal fun parseBoolean(
        raw: String,
        label: String,
        location: SourceLocation?,
    ): Boolean =
        when (raw.lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid $label: '$raw'", location)
        }

    internal fun parseTimeOfDay(
        raw: String,
        location: SourceLocation?,
    ): Long =
        when (raw) {
            "day" -> 1000
            "noon" -> 6000
            "night" -> 13000
            "midnight" -> 18000
            else -> parseLong(raw, "time value", location)
        }

    internal fun normalizeGameMode(
        raw: String,
        location: SourceLocation?,
    ): String =
        when (raw.lowercase()) {
            "survival", "s", "0" -> "survival"
            "creative", "c", "1" -> "creative"
            "adventure", "a", "2" -> "adventure"
            "spectator", "sp", "3" -> "spectator"
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid game mode '$raw'", location)
        }

    internal fun normalizeDifficulty(
        raw: String,
        location: SourceLocation?,
    ): String =
        when (raw.lowercase()) {
            "peaceful", "p", "0" -> "peaceful"
            "easy", "e", "1" -> "easy"
            "normal", "n", "2" -> "normal"
            "hard", "h", "3" -> "hard"
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid difficulty '$raw'", location)
        }

    internal fun defaultAttribute(attribute: ResourceLocation): Double =
        when (attribute.toString()) {
            "minecraft:max_health" -> 20.0
            "minecraft:movement_speed" -> 0.1
            "minecraft:attack_damage" -> 2.0
            "minecraft:armor", "minecraft:armor_toughness", "minecraft:knockback_resistance" -> 0.0
            else -> 0.0
        }

    internal fun parseIntRange(
        raw: String,
        location: SourceLocation?,
    ): Pair<Int, Int> {
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

    internal fun inventorySlot(raw: String): Int =
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

    internal fun requireSize(
        tokens: List<CommandToken>,
        size: Int,
        usage: String,
        location: SourceLocation?,
    ) {
        if (tokens.size < size) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Expected: $usage", location)
        }
    }

    internal fun requireSizeFrom(
        tokens: List<CommandToken>,
        index: Int,
        size: Int,
        usage: String,
        location: SourceLocation?,
    ) {
        if (tokens.size < index + size) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Expected: $usage", location)
        }
    }

    internal fun requireIndex(
        tokens: List<CommandToken>,
        index: Int,
        usage: String,
        location: SourceLocation?,
    ) {
        if (tokens.size <= index) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Expected: $usage", location)
        }
    }
}
