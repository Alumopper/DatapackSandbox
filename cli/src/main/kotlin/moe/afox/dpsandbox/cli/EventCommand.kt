package moe.afox.dpsandbox.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import moe.afox.dpsandbox.core.SandboxException
import moe.afox.dpsandbox.core.VersionProfiles
import moe.afox.dpsandbox.core.createSandbox
import kotlin.system.exitProcess

class EventCommand : CliktCommand(name = "event") {
    private val version by option("--version", "-v").default(VersionProfiles.default.id)
    private val packs by option("--pack", "-p").path(mustExist = true).multiple(required = true)
    private val args by argument("args").multiple(required = true)
    private val unsupported by option("--unsupported").default("warn")

    override fun run() {
        try {
            val sandbox = createSandbox(version, packs, unsupportedFeatureMode = unsupportedFeatureMode(unsupported))
            val event = parsePlayerEventArgs(args, "event")
            val player = sandbox.createPlayer(event.playerName)
            val updates = sandbox.handlePlayerEvent(event)
            val inputText = event.input?.let { ", input=${it.device}:${it.code}/${it.action}" }.orEmpty()
            val blockPosText = event.blockPos?.let { ", blockPos=${it.x},${it.y},${it.z}" }.orEmpty()
            println(
                ConsoleStyle.green("OK event player ${player.name} ${event.type}") +
                    ConsoleStyle.dim(" (updates=${updates.size}$inputText$blockPosText)"),
            )
            if (updates.isEmpty()) {
                println(
                    ConsoleStyle.yellow(
                        "No advancement criteria changed. Check the event type/id against the advancement trigger conditions.",
                    ),
                )
            } else {
                updates.forEach { println(it) }
            }
            println(sandbox.snapshotString())
        } catch (error: SandboxException) {
            println(ConsoleStyle.diagnostic(error.render()))
            exitProcess(ExitCodes.forException(error))
        }
    }
}
