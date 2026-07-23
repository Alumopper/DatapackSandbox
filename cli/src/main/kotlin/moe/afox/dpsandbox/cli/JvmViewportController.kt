package moe.afox.dpsandbox.cli

import moe.afox.dpsandbox.core.DatapackSandbox
import moe.afox.dpsandbox.core.OutputEvent
import moe.afox.dpsandbox.core.PlayerEvents
import moe.afox.dpsandbox.core.Position
import moe.afox.dpsandbox.render.CompiledRealtimeGpuScene
import moe.afox.dpsandbox.render.RealtimeRenderCamera
import moe.afox.dpsandbox.render.RenderAssets
import moe.afox.dpsandbox.render.RenderCamera
import moe.afox.dpsandbox.render.RenderRequest
import moe.afox.dpsandbox.render.SandboxRealtimeRenderer
import moe.afox.dpsandbox.render.SandboxRenderer
import moe.afox.dpsandbox.render.WorldView
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

internal class JvmViewportController(
    private val sandboxFactory: () -> DatapackSandbox,
    private val initializeSandbox: (DatapackSandbox) -> Unit,
    private val realtimeRenderer: SandboxRealtimeRenderer,
    private val assets: RenderAssets,
    private val options: JvmViewportOptions,
    private val sceneSink: (CompiledRealtimeGpuScene, Boolean) -> Unit,
    private val outputSink: (String, Boolean) -> Unit,
    private val playStateSink: (Boolean) -> Unit,
    private val visualSink: (OutputEvent) -> Unit,
    private val environmentSink: (JvmViewportEnvironment) -> Unit,
) {
    private val worker: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "dps-jvm-viewport-world").apply { isDaemon = true }
        }
    private val staticRenderer = SandboxRenderer(assets)
    private lateinit var sandbox: DatapackSandbox

    @Volatile
    private var playing = false

    @Volatile
    private var closed = false

    @Volatile
    private var camera: RealtimeRenderCamera? = null

    @Volatile
    private var automaticCamera = true
    private var nextTickNanos = 0L
    private var forwardedOutputCount = 0
    private var lastCompiledView: WorldView? = null

    fun start() {
        worker.execute {
            replaceSandbox(forceResetCamera = true)
            if (options.autoplay) setPlayingOnWorker(true)
        }
        worker.scheduleAtFixedRate(::pumpTicks, 10, 10, TimeUnit.MILLISECONDS)
    }

    fun execute(command: String) {
        if (command.isBlank()) return
        worker.execute {
            emit("> ${command.trim()}")
            runWorldAction("Command") {
                val result = sandbox.executeCommand(command)
                val trace = sandbox.world.traces.lastOrNull()
                val summary =
                    "${if (result.success) "OK" else "NO RESULT"}: ${result.commandsExecuted} command(s), " +
                        "${trace?.snapshotDiffs?.size ?: 0} state change(s)"
                emit(summary, error = !result.success)
                rebuildScene(forceResetCamera = false, onlyIfChanged = true)
            }
        }
    }

    fun inspectCommand(
        command: String,
        revision: Long,
        sink: (ViewportCommandInspection) -> Unit,
    ) {
        worker.execute {
            if (!::sandbox.isInitialized) return@execute
            val completion = DpsCompletionEngine { sandbox }
            val context = CompletionContext.parse(command)
            val exactRoot = DpsCommandCatalog.rootCommands(sandbox.profile).any { it.value == context.first }
            val suggestions = completion.suggestions(command).take(MAX_VIEWPORT_SUGGESTIONS)
            val check = if (command.isNotBlank() && exactRoot) sandbox.checkCommand(command) else null
            sink(
                ViewportCommandInspection(
                    revision = revision,
                    suggestions = suggestions,
                    hint = completion.inlineHint(command),
                    check = check,
                ),
            )
        }
    }

    fun play() {
        worker.execute { setPlayingOnWorker(true) }
    }

    fun pause() {
        worker.execute { setPlayingOnWorker(false) }
    }

    fun step() {
        worker.execute {
            setPlayingOnWorker(false)
            runWorldAction("Step") {
                val result = sandbox.runTicks(1)
                emit("Advanced to tick ${sandbox.world.gameTime}; ${result.commandsExecuted} command(s) executed")
                rebuildScene(forceResetCamera = false, onlyIfChanged = true)
            }
        }
    }

    fun reset() {
        worker.execute {
            setPlayingOnWorker(false)
            replaceSandbox(forceResetCamera = false)
        }
    }

    fun saveCheckpoint() {
        worker.execute {
            runWorldAction("Save point") {
                sandbox.saveCheckpoint(VIEWPORT_CHECKPOINT)
                emit("Saved JVM viewport checkpoint")
            }
        }
    }

    fun restoreCheckpoint() {
        worker.execute {
            setPlayingOnWorker(false)
            runWorldAction("Return") {
                sandbox.restoreCheckpoint(VIEWPORT_CHECKPOINT)
                emit("Restored JVM viewport checkpoint")
                rebuildScene(forceResetCamera = false)
            }
        }
    }

    fun exportPng(path: Path) {
        worker.execute {
            runWorldAction("PNG export") {
                val current = camera
                val renderCamera =
                    current?.let {
                        RenderCamera.Fixed(it.position, it.yaw, it.pitch, it.dimension)
                    } ?: RenderCamera.Auto
                val frame =
                    staticRenderer.render(
                        sandbox,
                        RenderRequest(
                            width = options.width,
                            height = options.height,
                            camera = renderCamera,
                            fieldOfViewDegrees = options.fieldOfView,
                            showHud = true,
                        ),
                    )
                frame.writePng(path)
                emit("Exported high-quality JVM PNG: ${path.toAbsolutePath().normalize()}")
            }
        }
    }

    fun dispatchInput(
        device: String,
        code: String,
        action: String,
        x: Double?,
        y: Double?,
    ) {
        worker.execute {
            if (options.inputPlayer !in sandbox.world.players) sandbox.createPlayer(options.inputPlayer)
            val event =
                if (device == "keyboard") {
                    PlayerEvents.keyInput(options.inputPlayer, code, action)
                } else {
                    PlayerEvents.mouseInput(options.inputPlayer, code, action, x, y)
                }
            runWorldAction("Input") { sandbox.handlePlayerEvent(event) }
        }
    }

    fun updateCamera(
        next: RealtimeRenderCamera,
        automatic: Boolean,
    ) {
        camera = next
        automaticCamera = automatic
    }

    fun close() {
        closed = true
        playing = false
        worker.shutdownNow()
    }

    private fun replaceSandbox(forceResetCamera: Boolean) {
        runWorldAction("Sandbox reset") {
            sandbox = sandboxFactory()
            forwardedOutputCount = 0
            lastCompiledView = null
            initializeSandbox(sandbox)
            if (options.inputPlayer !in sandbox.world.players) sandbox.createPlayer(options.inputPlayer)
            emit("JVM sandbox ready | Minecraft ${sandbox.profile.id}")
            rebuildScene(forceResetCamera)
        }
    }

    private fun rebuildScene(
        forceResetCamera: Boolean,
        onlyIfChanged: Boolean = false,
    ) {
        environmentSink(JvmViewportEnvironment(sandbox.world.dayTime, sandbox.world.weather))
        val capturedView = WorldView.capture(sandbox).withoutEnvironment()
        if (onlyIfChanged && capturedView == lastCompiledView) return
        val currentCamera = camera
        val requestedCamera =
            if (automaticCamera || currentCamera == null) {
                RenderCamera.Auto
            } else {
                RenderCamera.Fixed(
                    position = Position(currentCamera.position.x, currentCamera.position.y, currentCamera.position.z),
                    yaw = currentCamera.yaw,
                    pitch = currentCamera.pitch,
                    dimension = currentCamera.dimension,
                )
            }
        val scene =
            realtimeRenderer.compileGpu(
                sandbox,
                RenderRequest(
                    width = options.width,
                    height = options.height,
                    camera = requestedCamera,
                    fieldOfViewDegrees = options.fieldOfView,
                    renderDistance = 1_024.0,
                ),
            )
        lastCompiledView = capturedView
        sceneSink(scene, forceResetCamera)
    }

    private fun setPlayingOnWorker(next: Boolean) {
        if (playing == next) return
        playing = next
        nextTickNanos = System.nanoTime() + tickNanos()
        playStateSink(next)
        emit(if (next) "Simulation playing at ${options.tickRate} TPS" else "Simulation paused")
    }

    private fun pumpTicks() {
        if (!playing || closed || !::sandbox.isInitialized) return
        val now = System.nanoTime()
        if (now < nextTickNanos) return
        val tickNanos = tickNanos()
        val due = ((now - nextTickNanos) / tickNanos + 1L).toInt()
        val runCount = due.coerceAtMost(MAX_CATCH_UP_TICKS)
        runWorldAction("Simulation tick") {
            sandbox.runTicks(runCount)
            nextTickNanos += runCount * tickNanos
            val dropped = due - runCount
            if (dropped > 0) {
                nextTickNanos = now + tickNanos
                emit("Dropped $dropped delayed tick(s) to keep the JVM viewport responsive", error = true)
            }
            rebuildScene(forceResetCamera = false, onlyIfChanged = true)
        }
    }

    private fun WorldView.withoutEnvironment(): WorldView = copy(gameTime = 0, dayTime = 0, weather = "")

    private fun tickNanos(): Long = 1_000_000_000L / options.tickRate

    private fun runWorldAction(
        label: String,
        action: () -> Unit,
    ) {
        try {
            action()
        } catch (error: Exception) {
            if (label == "Simulation tick") setPlayingOnWorker(false)
            emit("$label failed: ${error.message ?: error::class.simpleName}", error = true)
        } finally {
            if (::sandbox.isInitialized) forwardNewOutputs()
        }
    }

    private fun forwardNewOutputs() {
        val outputs = sandbox.world.outputs
        if (forwardedOutputCount > outputs.size) forwardedOutputCount = outputs.size
        outputs.subList(forwardedOutputCount, outputs.size).forEach { output ->
            visualSink(output)
            if (output.channel == "visual") return@forEach
            val targets =
                output.targets
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(prefix = " [", postfix = "]")
                    .orEmpty()
            emit("${output.channel.uppercase()}$targets: ${output.text.ifBlank { output.rawText }}")
        }
        forwardedOutputCount = outputs.size
    }

    private fun emit(
        message: String,
        error: Boolean = false,
    ) {
        outputSink(message, error)
    }

    companion object {
        private const val VIEWPORT_CHECKPOINT = "jvm-viewport"
        private const val MAX_CATCH_UP_TICKS = 5
        private const val MAX_VIEWPORT_SUGGESTIONS = 6
    }
}
