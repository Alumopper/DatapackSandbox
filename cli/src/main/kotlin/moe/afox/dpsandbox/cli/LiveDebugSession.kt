package moe.afox.dpsandbox.cli

import com.google.gson.Gson
import com.google.gson.JsonObject
import moe.afox.dpsandbox.core.CommandTraceMode
import moe.afox.dpsandbox.core.DatapackSandbox
import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.ExecutionContext
import moe.afox.dpsandbox.core.Position
import moe.afox.dpsandbox.core.ResourceLocation
import moe.afox.dpsandbox.core.SandboxException
import moe.afox.dpsandbox.core.SandboxExecutionObserver
import moe.afox.dpsandbox.core.ScoreKey
import moe.afox.dpsandbox.core.SourceLocation
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** One owner executes the world; the protocol reader only changes control state or reads frozen data. */
internal class LiveDebugSession(
    private val sandbox: DatapackSandbox,
    private val emit: (String, JsonObject) -> Unit,
) : SandboxExecutionObserver {
    private data class Frame(
        val id: String,
        val activation: Long,
        var file: String?,
        var line: Int?,
        var executor: String,
        var dimension: String,
        var position: Position,
        @Transient var execution: ExecutionContext,
    )

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val gson = Gson()
    private val frames = mutableListOf<Frame>()
    private var breakpoints = emptySet<Pair<String, Int>>()
    private var sequence = 0L
    private var mode = "continue"
    private var stepDepth = 0
    private var pauseRequested = false
    private var stopRequested = false
    private var worker: Thread? = null
    private var frozen = JsonObject()
    private var inspection: DatapackSandbox? = null

    @Volatile var active = false
        private set

    @Volatile private var paused = false

    fun dispatch(method: String, params: JsonObject): JsonObject =
        when (method) {
            "debug.setBreakpoints" ->
                lock.withLock {
                    breakpoints =
                        params
                            .getAsJsonArray("breakpoints")
                            ?.map {
                                it.asJsonObject.let { point -> point.get("function").asString to point.get("line").asInt }
                            }?.toSet()
                            .orEmpty()
                    JsonObject().apply { addProperty("count", breakpoints.size) }
                }
            "debug.start" -> start(params)
            "debug.continue", "debug.step" ->
                lock.withLock {
                    require(active && paused) { "The debugger is not paused" }
                    mode = if (method == "debug.continue") "continue" else params.get("mode")?.asString ?: "into"
                    require(mode in setOf("continue", "into", "over", "out")) { "Invalid debug step mode" }
                    stepDepth = frames.size
                    paused = false
                    frozen = JsonObject()
                    inspection = null
                    changed.signalAll()
                    JsonObject().apply { addProperty("running", true) }
                }
            "debug.pause" ->
                lock.withLock {
                    require(active) { "The debugger is not running" }
                    pauseRequested = true
                    JsonObject().apply { addProperty("requested", true) }
                }
            "debug.state" ->
                lock.withLock {
                    frozen.deepCopy().apply {
                        addProperty("active", active)
                        addProperty("paused", paused)
                    }
                }
            "debug.inspect" -> inspect(params)
            "debug.stop" -> {
                stop()
                JsonObject().apply { addProperty("requested", true) }
            }
            else -> throw IllegalArgumentException("Unknown debug method '$method'")
        }

    private fun start(params: JsonObject): JsonObject =
        lock.withLock {
            require(!active) { "A debug execution is already active" }
            active = true
            paused = false
            stopRequested = false
            pauseRequested = params.get("stopOnEntry")?.asBoolean == true
            mode = "continue"
            frames.clear()
            frozen = JsonObject()
            inspection = null
            sandbox.clearExecutionCancellation()
            sandbox.executionObserver = this
            val previousTraceMode = sandbox.world.commandTraceMode
            sandbox.world.commandTraceMode = CommandTraceMode.OFF
            val functions = params.getAsJsonArray("functions")?.map { it.asString }.orEmpty()
            worker =
                Thread({
                    val completion = JsonObject()
                    try {
                        if (params.get("load")?.asBoolean != false) sandbox.runLoad()
                        functions.forEach { sandbox.runFunction(it) }
                        completion.add("snapshot", sandbox.snapshotJson())
                    } catch (error: Exception) {
                        if (!stopRequested) {
                            emit(
                                "debug.error",
                                JsonObject().apply {
                                    addProperty("message", error.message)
                                    if (error is SandboxException) {
                                        addProperty("code", error.code.name)
                                        add("location", gson.toJsonTree(error.location))
                                    }
                                    add("snapshot", sandbox.snapshotJson())
                                },
                            )
                        }
                        completion.addProperty("cancelled", stopRequested)
                    } finally {
                        lock.withLock {
                            sandbox.executionObserver = null
                            sandbox.world.commandTraceMode = previousTraceMode
                            sandbox.clearExecutionCancellation()
                            active = false
                            paused = false
                            inspection = null
                            changed.signalAll()
                        }
                    }
                    emit("debug.terminated", completion)
                }, "dps-live-debug").also {
                    it.isDaemon = true
                    it.start()
                }
            JsonObject().apply { addProperty("started", true) }
        }

    override fun enterFunction(id: ResourceLocation, location: SourceLocation?, context: ExecutionContext) =
        lock.withLock {
            frames.add(
                Frame(
                    id.toString(),
                    ++sequence,
                    location?.file,
                    location?.line,
                    context.entity?.scoreHolder ?: "Server",
                    context.dimension.toString(),
                    context.position,
                    context,
                ),
            )
            Unit
        }

    override fun exitFunction() =
        lock.withLock {
            if (frames.isNotEmpty()) frames.removeLast()
            Unit
        }

    override fun beforeCommand(command: String, location: SourceLocation?, context: ExecutionContext) =
        lock.withLock {
            frames.lastOrNull()?.apply {
                file = location?.file
                line = location?.line
                executor = context.entity?.scoreHolder ?: "Server"
                dimension = context.dimension.toString()
                position = context.position
                execution = context
            }
            if (stopRequested) throw SandboxException(DiagnosticCode.EXECUTION_INTERRUPTED, "Debug session stopped")
            val breakpoint = frames.lastOrNull()?.let { it.id to (location?.line ?: -1) } in breakpoints
            val stepping = mode == "into" || mode == "over" && frames.size <= stepDepth || mode == "out" && frames.size < stepDepth
            if (!pauseRequested && !breakpoint && !stepping) return@withLock
            val reason =
                if (breakpoint) {
                    "breakpoint"
                } else if (pauseRequested) {
                    "pause"
                } else {
                    "step"
                }
            pauseRequested = false
            paused = true
            mode = "continue"
            inspection = sandbox.inspectionCopy()
            frozen =
                JsonObject().apply {
                    addProperty("reason", reason)
                    addProperty("command", command)
                    add("frames", gson.toJsonTree(frames))
                    add("snapshot", sandbox.snapshotJson())
                }
            emit("debug.stopped", frozen.deepCopy())
            while (paused && !stopRequested) changed.await()
            if (stopRequested) throw SandboxException(DiagnosticCode.EXECUTION_INTERRUPTED, "Debug session stopped")
        }

    private fun inspect(params: JsonObject): JsonObject =
        lock.withLock {
            require(active && paused) { "The debugger is not paused" }
            val command = params.get("command")?.asString ?: error("Inspection requires a read command")
            val score = command.startsWith("scoreboard players get ")
            require(score || command.startsWith("data get ")) { "Inspection accepts only data get and scoreboard players get" }
            val frame = frames.singleOrNull { it.activation == params.get("frame")?.asLong } ?: error("Unknown inspection frame")
            val copy = requireNotNull(inspection).inspectionCopy()
            val executor =
                frame.execution.entity?.uuid?.let { uuid ->
                    copy.world.entities.firstOrNull { it.uuid == uuid }
                        ?: copy.world.players.values
                            .firstOrNull { it.uuid == uuid }
                }
            val context = frame.execution.copy(entity = executor, predicateEngine = copy.predicates)
            val cursor = copy.world.outputs.size
            copy.executeCommand(command, context = context)
            val values =
                copy.world.outputs
                    .drop(cursor)
                    .filter { it.channel == "data" }
            require(values.size == 1) { "The address is uninitialized or does not identify one value" }
            val payload = requireNotNull(values.single().payload) { "No readable value at this address" }
            if (score) {
                require(
                    requireNotNull(inspection).world.scores.containsKey(
                        ScoreKey(
                            payload.asJsonObject.get("target").asString,
                            payload.asJsonObject.get("objective").asString,
                        ),
                    ),
                ) { "Score is not initialized at this position" }
            }
            JsonObject().apply { add("value", (if (score) payload.asJsonObject.get("value") else payload).deepCopy()) }
        }

    fun stop() =
        lock.withLock {
            stopRequested = true
            sandbox.requestExecutionCancellation()
            paused = false
            changed.signalAll()
        }

    fun close() {
        stop()
        worker?.join(5000)
    }
}
