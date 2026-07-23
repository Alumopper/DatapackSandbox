package moe.afox.dpsandbox.cli

import moe.afox.dpsandbox.core.DatapackSandbox
import moe.afox.dpsandbox.core.OutputEvent
import moe.afox.dpsandbox.render.CompiledRealtimeGpuScene
import moe.afox.dpsandbox.render.RenderAssets
import moe.afox.dpsandbox.render.SandboxRealtimeRenderer
import org.lwjgl.glfw.Callbacks.glfwFreeCallbacks
import org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR
import org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR
import org.lwjgl.glfw.GLFW.GLFW_CURSOR
import org.lwjgl.glfw.GLFW.GLFW_CURSOR_DISABLED
import org.lwjgl.glfw.GLFW.GLFW_CURSOR_NORMAL
import org.lwjgl.glfw.GLFW.GLFW_FALSE
import org.lwjgl.glfw.GLFW.GLFW_KEY_A
import org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE
import org.lwjgl.glfw.GLFW.GLFW_KEY_D
import org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN
import org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
import org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE
import org.lwjgl.glfw.GLFW.GLFW_KEY_F10
import org.lwjgl.glfw.GLFW.GLFW_KEY_F12
import org.lwjgl.glfw.GLFW.GLFW_KEY_F5
import org.lwjgl.glfw.GLFW.GLFW_KEY_F6
import org.lwjgl.glfw.GLFW.GLFW_KEY_F7
import org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER
import org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT
import org.lwjgl.glfw.GLFW.GLFW_KEY_O
import org.lwjgl.glfw.GLFW.GLFW_KEY_P
import org.lwjgl.glfw.GLFW.GLFW_KEY_R
import org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT
import org.lwjgl.glfw.GLFW.GLFW_KEY_S
import org.lwjgl.glfw.GLFW.GLFW_KEY_SLASH
import org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE
import org.lwjgl.glfw.GLFW.GLFW_KEY_T
import org.lwjgl.glfw.GLFW.GLFW_KEY_TAB
import org.lwjgl.glfw.GLFW.GLFW_KEY_UP
import org.lwjgl.glfw.GLFW.GLFW_KEY_V
import org.lwjgl.glfw.GLFW.GLFW_KEY_W
import org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL
import org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT
import org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_MIDDLE
import org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE
import org.lwjgl.glfw.GLFW.GLFW_PRESS
import org.lwjgl.glfw.GLFW.GLFW_RAW_MOUSE_MOTION
import org.lwjgl.glfw.GLFW.GLFW_RELEASE
import org.lwjgl.glfw.GLFW.GLFW_RESIZABLE
import org.lwjgl.glfw.GLFW.GLFW_TRUE
import org.lwjgl.glfw.GLFW.GLFW_VISIBLE
import org.lwjgl.glfw.GLFW.glfwCreateWindow
import org.lwjgl.glfw.GLFW.glfwDefaultWindowHints
import org.lwjgl.glfw.GLFW.glfwDestroyWindow
import org.lwjgl.glfw.GLFW.glfwGetClipboardString
import org.lwjgl.glfw.GLFW.glfwGetFramebufferSize
import org.lwjgl.glfw.GLFW.glfwGetPrimaryMonitor
import org.lwjgl.glfw.GLFW.glfwGetVideoMode
import org.lwjgl.glfw.GLFW.glfwInit
import org.lwjgl.glfw.GLFW.glfwMakeContextCurrent
import org.lwjgl.glfw.GLFW.glfwPollEvents
import org.lwjgl.glfw.GLFW.glfwRawMouseMotionSupported
import org.lwjgl.glfw.GLFW.glfwSetCharCallback
import org.lwjgl.glfw.GLFW.glfwSetCursorPosCallback
import org.lwjgl.glfw.GLFW.glfwSetFramebufferSizeCallback
import org.lwjgl.glfw.GLFW.glfwSetInputMode
import org.lwjgl.glfw.GLFW.glfwSetKeyCallback
import org.lwjgl.glfw.GLFW.glfwSetMouseButtonCallback
import org.lwjgl.glfw.GLFW.glfwSetScrollCallback
import org.lwjgl.glfw.GLFW.glfwSetWindowFocusCallback
import org.lwjgl.glfw.GLFW.glfwSetWindowPos
import org.lwjgl.glfw.GLFW.glfwShowWindow
import org.lwjgl.glfw.GLFW.glfwSwapBuffers
import org.lwjgl.glfw.GLFW.glfwSwapInterval
import org.lwjgl.glfw.GLFW.glfwTerminate
import org.lwjgl.glfw.GLFW.glfwWindowHint
import org.lwjgl.glfw.GLFW.glfwWindowShouldClose
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengl.GL
import org.lwjgl.system.MemoryStack
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

internal class JvmViewportWindow(
    private val sandboxFactory: () -> DatapackSandbox,
    private val initializeSandbox: (DatapackSandbox) -> Unit,
    private val assets: RenderAssets,
    private val options: JvmViewportOptions,
    private val exportDirectory: Path,
) {
    private val pendingScene = AtomicReference<SceneUpdate?>()
    private val pendingEnvironment = AtomicReference<JvmViewportEnvironment?>()
    private val messages = ConcurrentLinkedQueue<StatusMessage>()
    private val visualEvents = ConcurrentLinkedQueue<OutputEvent>()
    private val pendingInspection = AtomicReference<ViewportCommandInspection?>()
    private val pressedKeys = mutableSetOf<Int>()
    private val camera = JvmViewportCamera(options.moveSpeed)
    private val commandEditor = JvmViewportCommandEditor()
    private val consoleLines = ArrayDeque<StatusMessage>()
    private val chatLines = ArrayDeque<ChatOverlayLine>()
    private var titleOverlay = TitleOverlay()
    private var toolbarLayout = ToolbarLayout(emptyList(), 1f)
    private lateinit var controller: JvmViewportController
    private var currentScene: CompiledRealtimeGpuScene? = null
    private var playing = false
    private var commandMode = false
    private var settingsOpen = false
    private var suppressNextCharacter = false
    private var mouseCaptured = false
    private var cursorInitialized = false
    private var cursorX = 0.0
    private var cursorY = 0.0
    private var mouseX = 0.0
    private var mouseY = 0.0
    private var lookX = 0.0
    private var lookY = 0.0
    private var lastLookDispatchNanos = 0L
    private var lastCommandChangeNanos = 0L
    private var requestedCommandRevision = -1L
    private var framebufferWidth = options.width
    private var framebufferHeight = options.height
    private var status = "Starting JVM sandbox..."
    private var statusError = false
    private var mouseSensitivity = options.mouseSensitivity
    private var uiScale = options.uiScale
    private var fieldOfView = options.fieldOfView
    private var stats = "0 FPS | 0 tris"
    private var statsStartedNanos = System.nanoTime()
    private var statsFrames = 0

    fun showAndWait() {
        val errorCallback = GLFWErrorCallback.createPrint(System.err).set()
        check(glfwInit()) { "GLFW initialization failed" }
        var window = 0L
        try {
            window = createWindow()
            glfwMakeContextCurrent(window)
            glfwSwapInterval(1)
            GL.createCapabilities()
            val clientResources = MinecraftClientResources(assets)
            val sceneRenderer = GlSceneRenderer(options.fieldOfView)
            val particleRenderer = GlParticleRenderer(clientResources)
            val hudRenderer = GlHudRenderer(clientResources)
            try {
                installCallbacks(window)
                centerWindow(window)
                glfwShowWindow(window)
                createController()
                controller.start()
                runLoop(window, sceneRenderer, particleRenderer, hudRenderer)
            } finally {
                if (::controller.isInitialized) controller.close()
                hudRenderer.close()
                particleRenderer.close()
                sceneRenderer.close()
                glfwFreeCallbacks(window)
            }
        } finally {
            if (window != 0L) glfwDestroyWindow(window)
            glfwTerminate()
            errorCallback.free()
        }
    }

    private fun createWindow(): Long {
        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE)
        return glfwCreateWindow(
            options.width,
            options.height,
            "Datapack Sandbox - JVM OpenGL Viewport",
            0L,
            0L,
        ).also { check(it != 0L) { "Unable to create the GLFW/OpenGL window" } }
    }

    private fun createController() {
        val realtimeRenderer = SandboxRealtimeRenderer(assets)
        controller =
            JvmViewportController(
                sandboxFactory = sandboxFactory,
                initializeSandbox = initializeSandbox,
                realtimeRenderer = realtimeRenderer,
                assets = assets,
                options = options,
                sceneSink = { scene, forceReset -> pendingScene.set(SceneUpdate(scene, forceReset)) },
                outputSink = { text, error ->
                    messages += StatusMessage(text, error)
                    if (error) System.err.println(text)
                },
                playStateSink = { playing = it },
                visualSink = visualEvents::add,
                environmentSink = pendingEnvironment::set,
            )
    }

    private fun runLoop(
        window: Long,
        sceneRenderer: GlSceneRenderer,
        particleRenderer: GlParticleRenderer,
        hudRenderer: GlHudRenderer,
    ) {
        var previousFrameNanos = System.nanoTime()
        val targetFrameNanos = 1_000_000_000L / options.targetFps
        while (!glfwWindowShouldClose(window)) {
            val frameStarted = System.nanoTime()
            val deltaSeconds = ((frameStarted - previousFrameNanos) / 1_000_000_000.0).coerceIn(0.0, 0.1)
            previousFrameNanos = frameStarted
            applyUpdates(sceneRenderer, particleRenderer)
            requestCommandInspection(frameStarted)
            updateCamera(deltaSeconds)
            flushLookInput(frameStarted)
            sceneRenderer.fieldOfView = fieldOfView
            sceneRenderer.draw(camera.snapshot(), framebufferWidth, framebufferHeight)
            particleRenderer.draw(camera.snapshot(), fieldOfView, framebufferWidth, framebufferHeight, frameStarted)
            drawHud(hudRenderer, sceneRenderer)
            glfwSwapBuffers(window)
            glfwPollEvents()
            recordStats(frameStarted, sceneRenderer.triangleCount)
            val remaining = targetFrameNanos - (System.nanoTime() - frameStarted)
            if (remaining > 0L) LockSupport.parkNanos(remaining)
        }
    }

    private fun applyUpdates(
        sceneRenderer: GlSceneRenderer,
        particleRenderer: GlParticleRenderer,
    ) {
        while (true) {
            val message = messages.poll() ?: break
            status = message.text
            statusError = message.error
            consoleLines += message
            while (consoleLines.size > MAX_CONSOLE_LINES) consoleLines.removeFirst()
        }
        while (true) {
            val event = visualEvents.poll() ?: break
            applyVisualEvent(event, particleRenderer)
        }
        pendingInspection.getAndSet(null)?.let(commandEditor::applyInspection)
        pendingEnvironment.getAndSet(null)?.let(sceneRenderer::updateEnvironment)
        pendingScene.getAndSet(null)?.let { update ->
            try {
                currentScene = update.scene
                if (update.forceResetCamera) camera.reset(update.scene.suggestedCamera) else camera.applySuggested(update.scene.suggestedCamera)
                controller.updateCamera(camera.snapshot(), camera.automatic)
                sceneRenderer.updateScene(update.scene, options.tickRate)
                status =
                    "Minecraft ${options.version} | Local JVM/OpenGL | ${update.scene.visibleBlocks} blocks | " +
                    "${update.scene.visibleEntities} entities"
                statusError = false
            } catch (error: Exception) {
                status = "GPU scene upload failed: ${error.message}"
                statusError = true
                error.printStackTrace()
            }
        }
    }

    private fun drawHud(
        hud: GlHudRenderer,
        sceneRenderer: GlSceneRenderer,
    ) {
        val scale = uiScale.toFloat()
        hud.begin(framebufferWidth, framebufferHeight)
        toolbarLayout = toolbarButtons(hud, scale)
        toolbarLayout.buttons.forEach { button ->
            val hovered = !mouseCaptured && mouseX >= button.x && mouseX < button.x + button.width && mouseY in 10.0..54.0
            val active = button.action == ToolbarAction.PLAY && playing
            val color =
                when {
                    active -> 0xcc117a55.toInt()
                    hovered -> 0xcc304a40.toInt()
                    else -> 0xaa101916.toInt()
                }
            hud.rectangle(button.x, 10, button.width, 44, color)
            hud.text(button.label, button.x + 9f, 24f, scale = toolbarLayout.textScale)
        }
        val statsWidth = hud.textWidth(stats, scale).toInt()
        val statsX = (framebufferWidth - statsWidth - 12).coerceAtLeast(12)
        val statusChars = ((statsX - 28) / (8 * scale)).toInt().coerceAtLeast(8)
        hudPlateText(
            hud,
            sanitize(status).take(statusChars),
            12f,
            67f,
            if (statusError) 0xffff8a80.toInt() else 0xffd7e4df.toInt(),
            scale,
        )
        hudPlateText(hud, stats, statsX.toFloat(), 67f, 0xffd7e4df.toInt(), scale)
        if (mouseCaptured) {
            val centerX = framebufferWidth / 2
            val centerY = framebufferHeight / 2
            hud.rectangle(centerX - 7, centerY, 15, 1, 0xeeffffff.toInt())
            hud.rectangle(centerX, centerY - 7, 1, 15, 0xeeffffff.toInt())
        }
        drawVisualOverlays(hud, scale, System.nanoTime())
        drawConsole(hud, scale)
        if (settingsOpen) drawSettings(hud, scale)
        hud.end()
        if (sceneRenderer.triangleCount == 0 && currentScene != null) {
            status = "Scene is empty; press T and enter a setblock, fill, or summon command"
        }
    }

    private fun applyVisualEvent(
        event: OutputEvent,
        particleRenderer: GlParticleRenderer,
    ) {
        if (!event.isVisibleToInputPlayer()) return
        when (event.channel) {
            "visual" -> if (event.command == "particle") particleRenderer.spawn(event)
            "chat" -> {
                chatLines +=
                    ChatOverlayLine(
                        text = event.text.ifBlank { event.rawText },
                        color = textColor(event),
                        createdNanos = System.nanoTime(),
                    )
                while (chatLines.size > MAX_CHAT_LINES) chatLines.removeFirst()
            }
            "title" -> applyTitleEvent(event)
        }
    }

    private fun OutputEvent.isVisibleToInputPlayer(): Boolean {
        if (targets.isNotEmpty()) return options.inputPlayer in targets
        val payloadObject = payload?.takeIf { it.isJsonObject }?.asJsonObject
        return payloadObject?.has("viewerSelector") != true
    }

    private fun applyTitleEvent(event: OutputEvent) {
        val now = System.nanoTime()
        when (event.command.removePrefix("title ")) {
            "clear" -> titleOverlay = titleOverlay.copy(title = "", subtitle = "", actionbar = "")
            "reset" -> titleOverlay = TitleOverlay()
            "times" -> {
                val payload = event.payload?.takeIf { it.isJsonObject }?.asJsonObject ?: return
                titleOverlay =
                    titleOverlay.copy(
                        fadeInTicks = payload.get("fadeIn")?.asInt?.coerceAtLeast(0) ?: titleOverlay.fadeInTicks,
                        stayTicks = payload.get("stay")?.asInt?.coerceAtLeast(0) ?: titleOverlay.stayTicks,
                        fadeOutTicks = payload.get("fadeOut")?.asInt?.coerceAtLeast(0) ?: titleOverlay.fadeOutTicks,
                    )
            }
            "title" ->
                titleOverlay =
                    titleOverlay.copy(
                        title = event.text,
                        titleColor = textColor(event),
                        titleShownNanos = now,
                    )
            "subtitle" ->
                titleOverlay =
                    titleOverlay.copy(
                        subtitle = event.text,
                        subtitleColor = textColor(event),
                        titleShownNanos = now,
                    )
            "actionbar" ->
                titleOverlay =
                    titleOverlay.copy(
                        actionbar = event.text,
                        actionbarColor = textColor(event),
                        actionbarShownNanos = now,
                    )
        }
    }

    private fun drawVisualOverlays(
        hud: GlHudRenderer,
        scale: Float,
        now: Long,
    ) {
        val titleAlpha = titleOverlay.titleAlpha(now)
        if (titleAlpha > 0f) {
            if (titleOverlay.title.isNotBlank()) {
                centeredShadowText(
                    hud,
                    sanitize(titleOverlay.title).take(maxTextColumns(scale * 2.1f)),
                    (framebufferHeight * 0.39f),
                    withAlpha(titleOverlay.titleColor, titleAlpha),
                    scale * 2.1f,
                )
            }
            if (titleOverlay.subtitle.isNotBlank()) {
                centeredShadowText(
                    hud,
                    sanitize(titleOverlay.subtitle).take(maxTextColumns(scale * 1.25f)),
                    (framebufferHeight * 0.39f + 46f * scale),
                    withAlpha(titleOverlay.subtitleColor, titleAlpha),
                    scale * 1.25f,
                )
            }
        }
        val actionbarAlpha = titleOverlay.actionbarAlpha(now)
        if (actionbarAlpha > 0f && titleOverlay.actionbar.isNotBlank()) {
            centeredShadowText(
                hud,
                sanitize(titleOverlay.actionbar).take(maxTextColumns(scale)),
                framebufferHeight - (if (commandMode) COMMAND_CONSOLE_HEIGHT - 28f else 145f),
                withAlpha(titleOverlay.actionbarColor, actionbarAlpha),
                scale,
            )
        }
        while (chatLines.firstOrNull()?.let { now - it.createdNanos > CHAT_LIFETIME_NANOS } == true) chatLines.removeFirst()
        val visible = chatLines.takeLast(5)
        val lineHeight = (17 * scale).toInt().coerceAtLeast(19)
        val bottom = framebufferHeight - (if (commandMode) COMMAND_CONSOLE_HEIGHT - 24 else 150)
        visible.forEachIndexed { index, line ->
            val age = now - line.createdNanos
            val alpha =
                if (age <= CHAT_FADE_START_NANOS) {
                    1f
                } else {
                    (1.0 - (age - CHAT_FADE_START_NANOS).toDouble() / (CHAT_LIFETIME_NANOS - CHAT_FADE_START_NANOS))
                        .toFloat()
                        .coerceIn(0f, 1f)
                }
            val y = bottom - (visible.size - index) * lineHeight
            val text = sanitize(line.text).take(maxTextColumns(scale))
            hud.rectangle(
                7,
                y - 3,
                hud.textWidth(text, scale).toInt() + 10,
                hud.textHeight(scale).toInt() + 6,
                ((0x99 * alpha).toInt().coerceIn(0, 255) shl 24),
            )
            hud.text(text, 12f, y.toFloat(), withAlpha(line.color, alpha), scale)
        }
    }

    private fun centeredShadowText(
        hud: GlHudRenderer,
        text: String,
        y: Float,
        color: Int,
        scale: Float,
    ) {
        val width = hud.textWidth(text, scale).toInt()
        val x = (framebufferWidth - width) / 2
        hud.shadowedText(text, x.toFloat(), y, color, scale)
    }

    private fun textColor(event: OutputEvent): Int = minecraftColor(event.segments.firstOrNull()?.color)

    private fun minecraftColor(color: String?): Int =
        when (color?.lowercase()) {
            "black" -> 0xff000000.toInt()
            "dark_blue" -> 0xff0000aa.toInt()
            "dark_green" -> 0xff00aa00.toInt()
            "dark_aqua" -> 0xff00aaaa.toInt()
            "dark_red" -> 0xffaa0000.toInt()
            "dark_purple" -> 0xffaa00aa.toInt()
            "gold" -> 0xffffaa00.toInt()
            "gray" -> 0xffaaaaaa.toInt()
            "dark_gray" -> 0xff555555.toInt()
            "blue" -> 0xff5555ff.toInt()
            "green" -> 0xff55ff55.toInt()
            "aqua" -> 0xff55ffff.toInt()
            "red" -> 0xffff5555.toInt()
            "light_purple" -> 0xffff55ff.toInt()
            "yellow" -> 0xffffff55.toInt()
            "white", null -> 0xffffffff.toInt()
            else ->
                color
                    .takeIf { it.startsWith("#") && it.length == 7 }
                    ?.drop(1)
                    ?.toIntOrNull(16)
                    ?.let { 0xff000000.toInt() or it }
                    ?: 0xffffffff.toInt()
        }

    private fun withAlpha(
        color: Int,
        alpha: Float,
    ): Int = ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24) or (color and 0x00ffffff)

    private fun drawConsole(
        hud: GlHudRenderer,
        scale: Float,
    ) {
        val resultRows = if (commandMode) 3 else 5
        val visibleLines = consoleLines.takeLast(resultRows)
        val lineHeight = (15 * scale).toInt().coerceAtLeast(17)
        val consoleHeight =
            if (commandMode) {
                COMMAND_CONSOLE_HEIGHT
            } else {
                28 + (visibleLines.size + 1) * lineHeight
            }
        val top = framebufferHeight - consoleHeight
        hudPlateText(
            hud,
            "CONSOLE | T command | Tab complete | Up/Down select | F10 settings",
            12f,
            (top + 8).toFloat(),
            0xffb8c8c1.toInt(),
            scale,
        )
        visibleLines.forEachIndexed { index, line ->
            val color = if (line.error) 0xffff8a80.toInt() else 0xffd7e4df.toInt()
            hudPlateText(
                hud,
                sanitize(line.text).take(maxTextColumns(scale)),
                12f,
                (top + 14 + (index + 1) * lineHeight).toFloat(),
                color,
                scale,
            )
        }
        if (commandMode) {
            val commandTop = top + 20 + (visibleLines.size + 1) * lineHeight
            hud.rectangle(8, commandTop - 7, framebufferWidth - 16, lineHeight + 14, 0xb0000000.toInt())
            hud.text(
                "> ${sanitize(commandEditor.text)}_",
                12f,
                commandTop.toFloat(),
                0xfff2fbf7.toInt(),
                scale,
            )
            drawCommandInspection(hud, scale, commandTop + lineHeight + 12, lineHeight)
        }
    }

    private fun drawCommandInspection(
        hud: GlHudRenderer,
        scale: Float,
        y: Int,
        lineHeight: Int,
    ) {
        val inspection = commandEditor.inspection
        val check = inspection?.check
        val checkText =
            when {
                commandEditor.text.isBlank() -> "CHECK: type a Minecraft command"
                check == null && !inspection?.suggestions.isNullOrEmpty() -> "CHECK: choose a completion or keep typing"
                check == null -> "CHECK: waiting for command name"
                check.valid -> "CHECK OK: ${check.message}; ${check.stateChanges} state change(s)"
                else -> "CHECK ${check.errorCode}: ${check.message}"
            }
        hudPlateText(
            hud,
            sanitize(checkText).take(maxTextColumns(scale)),
            12f,
            y.toFloat(),
            if (check?.valid == false) 0xffff8a80.toInt() else 0xff89e5b8.toInt(),
            scale,
        )
        inspection?.suggestions?.take(4)?.forEachIndexed { index, suggestion ->
            val selected = index == commandEditor.selectedSuggestion
            val prefix = if (selected) "> " else "  "
            val detail =
                suggestion.description
                    .takeIf { it.isNotBlank() }
                    ?.let { " - $it" }
                    .orEmpty()
            hudPlateText(
                hud,
                sanitize(prefix + suggestion.value + detail).take(maxTextColumns(scale)),
                18f,
                (y + (index + 1) * lineHeight).toFloat(),
                if (selected) 0xffffd27a.toInt() else 0xffb7cbc3.toInt(),
                scale,
            )
        }
    }

    private fun toolbarButtons(
        hud: GlHudRenderer,
        desiredScale: Float,
    ): ToolbarLayout {
        val specs =
            listOf(
                (if (playing) "Pause" else "Play") to ToolbarAction.PLAY,
                "Step" to ToolbarAction.STEP,
                "Reset view" to ToolbarAction.RESET_VIEW,
                "Save point" to ToolbarAction.SAVE_POINT,
                "Return" to ToolbarAction.RETURN,
                "Reset sandbox" to ToolbarAction.RESET_SANDBOX,
                "Export PNG" to ToolbarAction.EXPORT_PNG,
                "Command" to ToolbarAction.COMMAND,
                "Settings" to ToolbarAction.SETTINGS,
            )
        val fixedWidth = TOOLBAR_MARGIN * 2 + TOOLBAR_GAP * (specs.size - 1) + TOOLBAR_TEXT_PADDING * specs.size
        val baseTextWidth = specs.sumOf { (label, _) -> hud.textWidth(label, 1f).toDouble() }.toFloat()
        val fitScale = ((framebufferWidth - fixedWidth) / baseTextWidth).coerceAtLeast(MINIMUM_TOOLBAR_SCALE)
        val textScale = desiredScale.coerceAtMost(fitScale)
        var x = 10
        val buttons =
            specs.map { (label, action) ->
                val width = hud.textWidth(label, textScale).toInt() + TOOLBAR_TEXT_PADDING
                ToolbarButton(label, x, width, action).also { x += width + TOOLBAR_GAP }
            }
        return ToolbarLayout(buttons, textScale)
    }

    private fun drawSettings(
        hud: GlHudRenderer,
        scale: Float,
    ) {
        val panel = settingsPanel()
        hud.rectangle(panel.x, panel.y, panel.width, panel.height, 0xd014231e.toInt())
        hud.rectangle(panel.x + 2, panel.y + 2, panel.width - 4, 3, 0xff3d8b6c.toInt())
        hud.text("VIEWPORT SETTINGS", (panel.x + 20).toFloat(), (panel.y + 22).toFloat(), 0xfff2fbf7.toInt(), scale)
        hud.text("Changes apply immediately", (panel.x + 20).toFloat(), (panel.y + 48).toFloat(), 0xff91aaa0.toInt(), scale)
        settingRows(panel).forEach { row ->
            hud.text(row.label, (panel.x + 24).toFloat(), row.y.toFloat(), 0xffd7e4df.toInt(), scale)
            hud.text(row.value, (panel.x + 255).toFloat(), row.y.toFloat(), 0xffffd27a.toInt(), scale)
        }
        settingsButtons().forEach { button ->
            val hovered = button.contains(mouseX, mouseY)
            hud.rectangle(button.x, button.y, button.width, button.height, if (hovered) 0xdd3b5b4e.toInt() else 0xbb101916.toInt())
            hud.text(button.label, (button.x + 13).toFloat(), (button.y + 10).toFloat(), scale = scale)
        }
        hud.text("Esc or F10 closes settings", (panel.x + 20).toFloat(), (panel.y + panel.height - 32).toFloat(), 0xff91aaa0.toInt(), scale)
    }

    private fun settingsPanel(): UiRect {
        val width = 560.coerceAtMost(framebufferWidth - 32)
        val height = 350.coerceAtMost(framebufferHeight - 32)
        return UiRect((framebufferWidth - width) / 2, (framebufferHeight - height) / 2, width, height)
    }

    private fun settingRows(panel: UiRect): List<SettingRow> =
        listOf(
            SettingRow("Mouse sensitivity", "%.2f".format(mouseSensitivity), panel.y + 92),
            SettingRow("Fly speed", "%.1f u/s".format(camera.speed), panel.y + 148),
            SettingRow("UI scale", "%.1fx".format(uiScale), panel.y + 204),
            SettingRow("Field of view", "%.0f deg".format(fieldOfView), panel.y + 260),
        )

    private fun settingsButtons(): List<SettingButton> {
        val panel = settingsPanel()
        val minusX = panel.x + panel.width - 132
        val plusX = panel.x + panel.width - 72
        val rows = settingRows(panel)
        return buildList {
            add(SettingButton(panel.x + panel.width - 68, panel.y + 14, 46, 34, "X", SettingAction.Close))
            rows.forEachIndexed { index, row ->
                val action = SettingKind.entries[index]
                add(SettingButton(minusX, row.y - 10, 46, 38, "-", SettingAction.Adjust(action, -1)))
                add(SettingButton(plusX, row.y - 10, 46, 38, "+", SettingAction.Adjust(action, 1)))
            }
        }
    }

    private fun adjustSetting(action: SettingAction) {
        when (action) {
            SettingAction.Close -> settingsOpen = false
            is SettingAction.Adjust -> {
                when (action.kind) {
                    SettingKind.SENSITIVITY -> mouseSensitivity = (mouseSensitivity + action.direction * 0.02).coerceIn(0.01, 1.0)
                    SettingKind.SPEED -> camera.setSpeed(camera.speed + action.direction)
                    SettingKind.UI_SCALE -> uiScale = (uiScale + action.direction * 0.1).coerceIn(0.8, 2.5)
                    SettingKind.FIELD_OF_VIEW -> fieldOfView = (fieldOfView + action.direction * 5.0).coerceIn(10.0, 150.0)
                }
                status =
                    "Settings: sensitivity %.2f | speed %.1f | UI %.1fx | FOV %.0f"
                        .format(mouseSensitivity, camera.speed, uiScale, fieldOfView)
                statusError = false
            }
        }
    }

    private fun openSettings(window: Long) {
        releaseMouse(window)
        commandMode = false
        settingsOpen = !settingsOpen
    }

    private fun commandChanged() {
        lastCommandChangeNanos = System.nanoTime()
        requestedCommandRevision = -1L
    }

    private fun requestCommandInspection(now: Long) {
        if (!commandMode || requestedCommandRevision == commandEditor.revision) return
        if (now - lastCommandChangeNanos < COMMAND_INSPECTION_DELAY_NANOS) return
        requestedCommandRevision = commandEditor.revision
        controller.inspectCommand(commandEditor.text, commandEditor.revision) { pendingInspection.set(it) }
    }

    private fun maxTextColumns(scale: Float): Int = (framebufferWidth / (8f * scale)).toInt().coerceAtLeast(16) - 4

    private fun hudPlateText(
        hud: GlHudRenderer,
        text: String,
        x: Float,
        y: Float,
        color: Int,
        scale: Float,
    ) {
        val paddingX = 5
        val paddingY = 3
        hud.rectangle(
            x.toInt() - paddingX,
            y.toInt() - paddingY,
            hud.textWidth(text, scale).toInt() + paddingX * 2,
            hud.textHeight(scale).toInt() + paddingY * 2,
            0x99000000.toInt(),
        )
        hud.text(text, x, y, color, scale)
    }

    private fun installCallbacks(window: Long) {
        glfwSetFramebufferSizeCallback(window) { _, width, height ->
            framebufferWidth = width.coerceAtLeast(1)
            framebufferHeight = height.coerceAtLeast(1)
        }
        glfwSetKeyCallback(window) { _, key, _, action, modifiers -> handleKey(window, key, action, modifiers) }
        glfwSetCharCallback(window) { _, codepoint ->
            if (suppressNextCharacter) {
                suppressNextCharacter = false
                return@glfwSetCharCallback
            }
            if (commandMode && Character.isValidCodePoint(codepoint) && !Character.isISOControl(codepoint)) {
                commandEditor.append(String(Character.toChars(codepoint)))
                commandChanged()
            }
        }
        glfwSetCursorPosCallback(window) { _, x, y -> handleCursor(window, x, y) }
        glfwSetMouseButtonCallback(window) { _, button, action, _ -> handleMouseButton(window, button, action) }
        glfwSetScrollCallback(window) { _, _, yOffset ->
            camera.adjustSpeed(-yOffset.toInt())
            controller.updateCamera(camera.snapshot(), camera.automatic)
        }
        glfwSetWindowFocusCallback(window) { _, focused -> if (!focused) releaseMouse(window) }
        MemoryStack.stackPush().use { stack ->
            val width = stack.mallocInt(1)
            val height = stack.mallocInt(1)
            glfwGetFramebufferSize(window, width, height)
            framebufferWidth = width[0].coerceAtLeast(1)
            framebufferHeight = height[0].coerceAtLeast(1)
        }
    }

    private fun handleKey(
        window: Long,
        key: Int,
        action: Int,
        modifiers: Int,
    ) {
        if (action == GLFW_RELEASE) {
            pressedKeys.remove(key)
            keyInputCode(key)?.let { controller.dispatchInput("keyboard", it, "release", null, null) }
            return
        }
        if (action != GLFW_PRESS) return
        if (commandMode) {
            when (key) {
                GLFW_KEY_ESCAPE -> {
                    commandMode = false
                    commandEditor.clear()
                }
                GLFW_KEY_BACKSPACE -> {
                    commandEditor.backspace()
                    commandChanged()
                }
                GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> executeCommand()
                GLFW_KEY_TAB -> if (commandEditor.completeSelected()) commandChanged()
                GLFW_KEY_UP -> commandEditor.moveSelection(-1)
                GLFW_KEY_DOWN -> commandEditor.moveSelection(1)
                GLFW_KEY_V ->
                    if (modifiers and GLFW_MOD_CONTROL != 0) {
                        glfwGetClipboardString(window)?.let { clipboard: String ->
                            commandEditor.append(clipboard)
                            commandChanged()
                        }
                    }
            }
            return
        }
        if (settingsOpen) {
            if (key == GLFW_KEY_ESCAPE || key == GLFW_KEY_F10) settingsOpen = false
            return
        }
        when (key) {
            GLFW_KEY_ESCAPE -> releaseMouse(window)
            GLFW_KEY_SLASH, GLFW_KEY_T -> {
                suppressNextCharacter = true
                enterCommandMode(window)
            }
            GLFW_KEY_P -> if (playing) controller.pause() else controller.play()
            GLFW_KEY_O -> controller.step()
            GLFW_KEY_R -> resetView()
            GLFW_KEY_F10 -> openSettings(window)
            GLFW_KEY_F5 -> controller.reset()
            GLFW_KEY_F6 -> controller.saveCheckpoint()
            GLFW_KEY_F7 -> controller.restoreCheckpoint()
            GLFW_KEY_F12 -> exportPng()
        }
        if (pressedKeys.add(key)) {
            keyInputCode(key)?.let { controller.dispatchInput("keyboard", it, "press", null, null) }
        }
    }

    private fun handleCursor(
        window: Long,
        x: Double,
        y: Double,
    ) {
        mouseX = x
        mouseY = y
        if (!mouseCaptured) return
        if (!cursorInitialized) {
            cursorX = x
            cursorY = y
            cursorInitialized = true
            return
        }
        val deltaX = x - cursorX
        val deltaY = y - cursorY
        cursorX = x
        cursorY = y
        camera.look(deltaX * mouseSensitivity, deltaY * mouseSensitivity)
        lookX += deltaX
        lookY += deltaY
        controller.updateCamera(camera.snapshot(), camera.automatic)
        if (!glfwWindowShouldClose(window)) statusError = false
    }

    private fun handleMouseButton(
        window: Long,
        button: Int,
        action: Int,
    ) {
        if (settingsOpen) {
            if (action == GLFW_PRESS) settingsButtons().firstOrNull { it.contains(mouseX, mouseY) }?.let { adjustSetting(it.action) }
            return
        }
        if (!mouseCaptured && action == GLFW_PRESS && mouseY <= TOOLBAR_HEIGHT) {
            toolbarLayout.buttons
                .firstOrNull { mouseX >= it.x && mouseX < it.x + it.width && mouseY in 10.0..54.0 }
                ?.let { runToolbarAction(window, it.action) }
            return
        }
        val code = mouseInputCode(button)
        if (action == GLFW_PRESS) {
            if (!mouseCaptured && !commandMode) captureMouse(window)
            controller.dispatchInput("mouse", code, "press", mouseX, mouseY)
        } else if (action == GLFW_RELEASE) {
            controller.dispatchInput("mouse", code, "release", mouseX, mouseY)
            controller.dispatchInput("mouse", code, "click", mouseX, mouseY)
        }
    }

    private fun runToolbarAction(
        window: Long,
        action: ToolbarAction,
    ) {
        when (action) {
            ToolbarAction.PLAY -> if (playing) controller.pause() else controller.play()
            ToolbarAction.STEP -> controller.step()
            ToolbarAction.RESET_VIEW -> resetView()
            ToolbarAction.SAVE_POINT -> controller.saveCheckpoint()
            ToolbarAction.RETURN -> controller.restoreCheckpoint()
            ToolbarAction.RESET_SANDBOX -> controller.reset()
            ToolbarAction.EXPORT_PNG -> exportPng()
            ToolbarAction.COMMAND -> enterCommandMode(window)
            ToolbarAction.SETTINGS -> openSettings(window)
        }
    }

    private fun captureMouse(window: Long) {
        mouseCaptured = true
        cursorInitialized = false
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED)
        if (glfwRawMouseMotionSupported()) glfwSetInputMode(window, GLFW_RAW_MOUSE_MOTION, GLFW_TRUE)
    }

    private fun releaseMouse(window: Long) {
        if (mouseCaptured) {
            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL)
            if (glfwRawMouseMotionSupported()) glfwSetInputMode(window, GLFW_RAW_MOUSE_MOTION, GLFW_FALSE)
        }
        mouseCaptured = false
        cursorInitialized = false
        releaseMovementInputs()
    }

    private fun releaseMovementInputs() {
        pressedKeys.toList().forEach { key ->
            keyInputCode(key)?.let { controller.dispatchInput("keyboard", it, "release", null, null) }
        }
        pressedKeys.clear()
    }

    private fun enterCommandMode(window: Long) {
        releaseMouse(window)
        settingsOpen = false
        commandMode = true
        commandEditor.clear()
        commandChanged()
    }

    private fun executeCommand() {
        val text = commandEditor.text.trim()
        val check = commandEditor.inspection?.check
        if (check?.valid == false) {
            messages += StatusMessage("Command check failed: ${check.message}", true)
            return
        }
        if (text.isNotEmpty()) controller.execute(text)
        commandMode = false
        commandEditor.clear()
    }

    private fun updateCamera(deltaSeconds: Double) {
        if (commandMode || settingsOpen) return
        val forward = axis(GLFW_KEY_W, GLFW_KEY_S)
        val right = axis(GLFW_KEY_D, GLFW_KEY_A)
        val up =
            (if (GLFW_KEY_SPACE in pressedKeys) 1.0 else 0.0) -
                (if (GLFW_KEY_LEFT_SHIFT in pressedKeys || GLFW_KEY_RIGHT_SHIFT in pressedKeys) 1.0 else 0.0)
        if (forward == 0.0 && right == 0.0 && up == 0.0) return
        camera.move(forward, right, up, deltaSeconds)
        controller.updateCamera(camera.snapshot(), camera.automatic)
    }

    private fun axis(
        positive: Int,
        negative: Int,
    ): Double = (if (positive in pressedKeys) 1.0 else 0.0) - (if (negative in pressedKeys) 1.0 else 0.0)

    private fun flushLookInput(now: Long) {
        if (lookX == 0.0 && lookY == 0.0) return
        if (now - lastLookDispatchNanos < 50_000_000L) return
        controller.dispatchInput("mouse", "look", "move", lookX, lookY)
        lookX = 0.0
        lookY = 0.0
        lastLookDispatchNanos = now
    }

    private fun resetView() {
        currentScene?.let { scene ->
            camera.reset(scene.suggestedCamera)
            controller.updateCamera(camera.snapshot(), camera.automatic)
        }
    }

    private fun exportPng() {
        val path = exportDirectory.resolve("jvm-viewport-${System.currentTimeMillis()}.png")
        controller.exportPng(path)
    }

    private fun recordStats(
        now: Long,
        triangles: Int,
    ) {
        statsFrames += 1
        val elapsed = (now - statsStartedNanos) / 1_000_000_000.0
        if (elapsed < 0.5) return
        stats = "%.0f FPS | %d tris | %.1f u/s".format(statsFrames / elapsed, triangles, camera.speed)
        statsFrames = 0
        statsStartedNanos = now
    }

    private fun centerWindow(window: Long) {
        val videoMode = glfwGetVideoMode(glfwGetPrimaryMonitor()) ?: return
        glfwSetWindowPos(window, (videoMode.width() - options.width) / 2, (videoMode.height() - options.height) / 2)
    }

    private fun sanitize(text: String): String = text.map { if (it in ' '..'~') it else '?' }.joinToString("").take(180)

    private fun keyInputCode(key: Int): String? =
        when (key) {
            GLFW_KEY_W -> "key.forward"
            GLFW_KEY_S -> "key.back"
            GLFW_KEY_A -> "key.left"
            GLFW_KEY_D -> "key.right"
            GLFW_KEY_SPACE -> "key.jump"
            GLFW_KEY_LEFT_SHIFT, GLFW_KEY_RIGHT_SHIFT -> "key.sneak"
            else -> null
        }

    private fun mouseInputCode(button: Int): String =
        when (button) {
            GLFW_MOUSE_BUTTON_LEFT -> "left"
            GLFW_MOUSE_BUTTON_MIDDLE -> "middle"
            GLFW_MOUSE_BUTTON_RIGHT -> "right"
            else -> "button$button"
        }

    private data class SceneUpdate(
        val scene: CompiledRealtimeGpuScene,
        val forceResetCamera: Boolean,
    )

    private data class StatusMessage(
        val text: String,
        val error: Boolean,
    )

    private data class ChatOverlayLine(
        val text: String,
        val color: Int,
        val createdNanos: Long,
    )

    private data class TitleOverlay(
        val title: String = "",
        val subtitle: String = "",
        val actionbar: String = "",
        val titleColor: Int = 0xffffffff.toInt(),
        val subtitleColor: Int = 0xffffffff.toInt(),
        val actionbarColor: Int = 0xffffffff.toInt(),
        val fadeInTicks: Int = 10,
        val stayTicks: Int = 70,
        val fadeOutTicks: Int = 20,
        val titleShownNanos: Long = 0L,
        val actionbarShownNanos: Long = 0L,
    ) {
        fun titleAlpha(now: Long): Float {
            if (titleShownNanos == 0L) return 0f
            val elapsed = (now - titleShownNanos).coerceAtLeast(0L)
            val fadeIn = fadeInTicks * MINECRAFT_TICK_NANOS
            val stayEnd = fadeIn + stayTicks * MINECRAFT_TICK_NANOS
            val end = stayEnd + fadeOutTicks * MINECRAFT_TICK_NANOS
            return when {
                elapsed < fadeIn && fadeIn > 0L -> elapsed.toFloat() / fadeIn
                elapsed < stayEnd -> 1f
                elapsed < end && end > stayEnd -> 1f - (elapsed - stayEnd).toFloat() / (end - stayEnd)
                else -> 0f
            }.coerceIn(0f, 1f)
        }

        fun actionbarAlpha(now: Long): Float {
            if (actionbarShownNanos == 0L) return 0f
            val elapsed = (now - actionbarShownNanos).coerceAtLeast(0L)
            return when {
                elapsed < ACTIONBAR_FADE_START_NANOS -> 1f
                elapsed < ACTIONBAR_LIFETIME_NANOS ->
                    1f - (elapsed - ACTIONBAR_FADE_START_NANOS).toFloat() /
                        (ACTIONBAR_LIFETIME_NANOS - ACTIONBAR_FADE_START_NANOS)
                else -> 0f
            }.coerceIn(0f, 1f)
        }
    }

    private data class ToolbarButton(
        val label: String,
        val x: Int,
        val width: Int,
        val action: ToolbarAction,
    )

    private data class ToolbarLayout(
        val buttons: List<ToolbarButton>,
        val textScale: Float,
    )

    private data class UiRect(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )

    private data class SettingRow(
        val label: String,
        val value: String,
        val y: Int,
    )

    private data class SettingButton(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val label: String,
        val action: SettingAction,
    ) {
        fun contains(
            px: Double,
            py: Double,
        ): Boolean = px >= x && px < x + width && py >= y && py < y + height
    }

    private enum class SettingKind {
        SENSITIVITY,
        SPEED,
        UI_SCALE,
        FIELD_OF_VIEW,
    }

    private sealed interface SettingAction {
        data object Close : SettingAction

        data class Adjust(
            val kind: SettingKind,
            val direction: Int,
        ) : SettingAction
    }

    private enum class ToolbarAction {
        PLAY,
        STEP,
        RESET_VIEW,
        SAVE_POINT,
        RETURN,
        RESET_SANDBOX,
        EXPORT_PNG,
        COMMAND,
        SETTINGS,
    }

    companion object {
        private const val TOOLBAR_HEIGHT = 92
        private const val COMMAND_CONSOLE_HEIGHT = 270
        private const val MAX_CONSOLE_LINES = 40
        private const val MAX_CHAT_LINES = 40
        private const val CHAT_FADE_START_NANOS = 8_000_000_000L
        private const val CHAT_LIFETIME_NANOS = 10_000_000_000L
        private const val ACTIONBAR_FADE_START_NANOS = 2_500_000_000L
        private const val ACTIONBAR_LIFETIME_NANOS = 3_000_000_000L
        private const val MINECRAFT_TICK_NANOS = 50_000_000L
        private const val COMMAND_INSPECTION_DELAY_NANOS = 150_000_000L
        private const val TOOLBAR_MARGIN = 10
        private const val TOOLBAR_GAP = 7
        private const val TOOLBAR_TEXT_PADDING = 18
        private const val MINIMUM_TOOLBAR_SCALE = 0.1f
    }
}
