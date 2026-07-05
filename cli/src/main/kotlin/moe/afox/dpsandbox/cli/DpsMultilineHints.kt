package moe.afox.dpsandbox.cli

import moe.afox.dpsandbox.core.VersionProfile
import moe.afox.dpsandbox.core.VersionProfiles
import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder
import org.jline.utils.AttributedStyle

object DpsMultilineHints {
    fun describe(buffer: String, cursor: Int = buffer.length, profile: VersionProfile = VersionProfiles.default): List<AttributedString> {
        val context = CompletionContext.parse(buffer, cursor)
        val commands = DpsCommandCatalog.rootCommands(profile)
        val command = commandFor(context, commands) ?: return emptyList()
        val usage = command + DpsCommandCatalog.usageSuffix(command)
        val detail = details[command]
            ?: commands.firstOrNull { it.value == command }?.description
            ?: return emptyList()
        return listOf(title(usage), body(detail))
    }

    private fun commandFor(context: CompletionContext, commands: List<CompletionSuggestion>): String? {
        val first = context.first
        val names = commands.mapTo(sortedSetOf()) { it.value }
        if (first in names) return first
        if (context.wordIndex == 0) {
            val prefix = context.prefix.removePrefix("/")
            return names.singleOrNull { it.startsWith(prefix) }
        }
        return null
    }

    private fun title(text: String): AttributedString =
        AttributedStringBuilder()
            .styled(AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN).bold(), text)
            .toAttributedString()

    private fun body(text: String): AttributedString =
        AttributedStringBuilder()
            .styled(AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE), text)
            .toAttributedString()

    private val details = mapOf(
        "load" to "run #minecraft:load in the current sandbox",
        "reload" to "reload datapack files from disk while keeping world state",
        "tick" to "advance scheduled functions, tick functions, world time, and player tick triggers",
        "function" to "run one loaded function",
        "player" to "create or reuse a sandbox player with readable vanilla-style NBT",
        "event" to "inject player actions for advancement triggers, predicates, and rewards",
        "trace" to "toggle automatic printing of command trace events after each REPL command",
        "diff" to "print the snapshot diff captured for the last world-changing REPL command",
        "rerun" to "execute the last world-changing REPL command again",
        "reset" to "replace the in-memory world with a fresh sparse world",
        "inspect" to "print sandbox state without mutating it",
        "snapshot" to "print or write deterministic world JSON",
        "scoreboard" to "edit objectives and score holders",
        "execute" to "run a command as/at entities, under conditions, or storing result values",
        "data" to "read or mutate storage/entity/block NBT with schema validation",
        "summon" to "create an entity with validated top-level NBT; AI is not ticked",
        "setblock" to "place one block in the sparse void world without block updates",
        "fill" to "fill a sparse block region without updates, physics, or drops",
        "fillbiome" to "store biome overrides for explicit sparse block positions",
        "clone" to "copy sparse block state and block entity NBT",
        "bossbar" to "edit stored bossbar state; no real client UI is simulated",
        "give" to "add item stacks to sandbox player inventories",
        "effect" to "give or clear stored player effects",
        "item" to "replace sandbox entity item slots",
        "advancement" to "inspect, grant, or revoke per-player advancement progress",
        "attribute" to "read or edit stored sandbox entity attributes",
        "schedule" to "schedule or clear function execution by game tick",
        "defaultgamemode" to "edit the world's stored default game mode",
        "difficulty" to "edit or report the world's stored difficulty",
        "forceload" to "edit stored forced chunk coordinates",
        "gamemode" to "edit sandbox player game mode",
        "seed" to "report the deterministic sandbox seed",
        "setworldspawn" to "edit the stored world spawn point",
        "spawnpoint" to "edit stored player spawn points",
        "spectate" to "record spectator target state without client camera simulation",
        "spreadplayers" to "deterministically distribute selected entities around a center",
        "trigger" to "edit trigger objective scores for the current/default player",
        "weather" to "edit stored weather state only",
        "time" to "edit or query sandbox time state",
        "worldborder" to "edit stored world border state without client UI effects",
        "help" to "show detailed help for a command",
        "exit" to "leave the REPL",
        "quit" to "leave the REPL",
    )
}
