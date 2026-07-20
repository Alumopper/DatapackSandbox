package moe.afox.dpsandbox.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import com.google.gson.JsonArray
import moe.afox.dpsandbox.core.JsonValues
import moe.afox.dpsandbox.core.ResourceLocation
import moe.afox.dpsandbox.core.SandboxException
import moe.afox.dpsandbox.core.VersionProfiles
import moe.afox.dpsandbox.core.createSandbox
import moe.afox.dpsandbox.core.toJson
import kotlin.system.exitProcess

class LootCommand : CliktCommand(name = "loot") {
    private val version by option("--version", "-v").default(VersionProfiles.default.id)
    private val packs by option("--pack", "-p").path(mustExist = true).multiple(required = true)
    private val table by option("--table").default("minecraft:empty")
    private val context by option("--context").default("minecraft:empty")
    private val player by option("--player")
    private val seed by option("--seed").long().default(0)

    override fun run() {
        try {
            val sandbox = createSandbox(version, packs)
            val sandboxPlayer = player?.let { sandbox.createPlayer(it) }
            val result = sandbox.generateLoot(ResourceLocation.parse(table), ResourceLocation.parse(context), sandboxPlayer, seed)
            println(
                JsonValues.render(
                    com.google.gson.JsonArray().also { array ->
                        result.items.forEach { array.add(it.toJson()) }
                    },
                ),
            )
        } catch (error: SandboxException) {
            println(ConsoleStyle.diagnostic(error.render()))
            exitProcess(ExitCodes.forException(error))
        }
    }
}
