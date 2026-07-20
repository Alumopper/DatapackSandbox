package moe.afox.dpsandbox.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.JsonValues
import moe.afox.dpsandbox.core.ResourceLocation
import moe.afox.dpsandbox.core.SandboxException
import moe.afox.dpsandbox.core.VersionProfiles
import moe.afox.dpsandbox.core.createSandbox
import moe.afox.dpsandbox.core.toPlayerJson
import kotlin.system.exitProcess

class AdvancementCommand : CliktCommand(name = "advancement") {
    private val version by option("--version", "-v").default(VersionProfiles.default.id)
    private val packs by option("--pack", "-p").path(mustExist = true).multiple(required = true)
    private val args by argument("args").multiple(required = true)
    private val unsupported by option("--unsupported").default("warn")

    override fun run() {
        try {
            val sandbox = createSandbox(version, packs, unsupportedFeatureMode = unsupportedFeatureMode(unsupported))
            val action = args.getOrNull(0) ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "advancement action is required")
            val player = sandbox.createPlayer(args.getOrNull(1) ?: "Steve")
            val id = args.getOrNull(2)?.let(ResourceLocation::parse)
            when (action) {
                "list" ->
                    sandbox.datapack.advancements.keys
                        .forEach { println(it) }
                "progress" -> {
                    val progress =
                        sandbox.advancements.progress(
                            player,
                            id ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "advancement id is required"),
                        )
                    println(JsonValues.render(player.toPlayerJson(sandbox.profile)))
                    println(progress.criteria)
                }
                "grant" ->
                    sandbox.advancements
                        .grant(
                            player,
                            id ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "advancement id is required"),
                            args.getOrNull(3),
                        ).forEach { println(it) }
                "revoke" ->
                    sandbox.advancements
                        .revoke(
                            player,
                            id ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "advancement id is required"),
                            args.getOrNull(3),
                        ).forEach { println(it) }
                else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Unknown advancement action '$action'")
            }
        } catch (error: SandboxException) {
            println(ConsoleStyle.diagnostic(error.render()))
            exitProcess(ExitCodes.forException(error))
        }
    }
}
