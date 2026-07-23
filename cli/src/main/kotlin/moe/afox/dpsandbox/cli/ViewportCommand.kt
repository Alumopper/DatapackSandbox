package moe.afox.dpsandbox.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import moe.afox.dpsandbox.core.SandboxException
import moe.afox.dpsandbox.core.VersionProfiles
import moe.afox.dpsandbox.core.createFunctionSandboxFromString
import moe.afox.dpsandbox.core.createSandbox
import moe.afox.dpsandbox.render.RenderAssets
import java.nio.file.Path

/** Opens the keyboard-and-mouse JVM realtime sandbox window. */
class ViewportCommand : CliktCommand(name = "viewport") {
    private val version by option("--version", "-v").default(VersionProfiles.default.id)
    private val packs by option("--pack", "-p").path(mustExist = true).multiple()
    private val minecraftAssets by option("--minecraft-assets").path(mustExist = true)
    private val resourcePacks by option("--resource-pack").path(mustExist = true).multiple()
    private val commands by option("--command", "-c").multiple()
    private val function by option("--function", "-f")
    private val width by option("--width").int().default(1_200)
    private val height by option("--height").int().default(720)
    private val targetFps by option("--target-fps").int().default(60)
    private val tickRate by option("--tick-rate").int().default(20)
    private val autoplay by option("--autoplay").flag(default = false)
    private val inputPlayer by option("--input-player").default("Steve")
    private val fieldOfView by option("--field-of-view").double().default(70.0)
    private val moveSpeed by option("--move-speed").double().default(6.0)
    private val mouseSensitivity by option("--mouse-sensitivity").double().default(0.12)
    private val uiScale by option("--ui-scale").double().default(1.4)
    private val exportDirectory by option("--export-directory").path()

    override fun run() {
        try {
            val options =
                JvmViewportOptions(
                    version = version,
                    width = width,
                    height = height,
                    targetFps = targetFps,
                    tickRate = tickRate,
                    autoplay = autoplay,
                    inputPlayer = inputPlayer,
                    fieldOfView = fieldOfView,
                    moveSpeed = moveSpeed,
                    mouseSensitivity = mouseSensitivity,
                    uiScale = uiScale,
                )
            val assets = RenderAssets(minecraftAssets, resourcePacks)
            JvmViewportWindow(
                sandboxFactory = { createViewportSandbox(version, packs, inputPlayer) },
                initializeSandbox = { sandbox ->
                    sandbox.runLoad()
                    commands.forEach(sandbox::executeCommand)
                    function?.let(sandbox::runFunction)
                },
                assets = assets,
                options = options,
                exportDirectory = exportDirectory ?: Path.of("build").toAbsolutePath().normalize(),
            ).showAndWait()
        } catch (error: IllegalArgumentException) {
            throw UsageError(error.message ?: "Invalid JVM viewport options")
        } catch (error: SandboxException) {
            throw UsageError(error.render())
        }
    }
}

internal fun createViewportSandbox(
    version: String,
    packs: List<Path>,
    inputPlayer: String,
) = if (packs.isEmpty()) {
    createFunctionSandboxFromString(version, "", defaultPlayerName = inputPlayer)
} else {
    createSandbox(version, packs, defaultPlayerName = inputPlayer)
}
