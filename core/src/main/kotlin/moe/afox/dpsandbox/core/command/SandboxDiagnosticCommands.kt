package moe.afox.dpsandbox.core.command

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import moe.afox.dpsandbox.core.CommandToken
import moe.afox.dpsandbox.core.DatapackSandbox
import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.SandboxException
import moe.afox.dpsandbox.core.SourceLocation

internal fun DatapackSandbox.executeHelp(
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

private fun DatapackSandbox.commandHelp(root: String): String =
    when (root) {
        "function" -> "function <namespace:path>"
        "scoreboard" -> "scoreboard objectives|players ..."
        "execute" -> "execute ... run <command>"
        "data" -> "data get|merge|modify|remove <target> ..."
        "gamemode" -> "gamemode <mode> [targets]"
        "worldborder" -> "worldborder add|center|damage|get|set|warning ..."
        else -> if (profile.commands.hasRoot(root)) "vanilla root command; sandbox support may be partial" else "unknown"
    }

internal fun DatapackSandbox.executeList(
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
