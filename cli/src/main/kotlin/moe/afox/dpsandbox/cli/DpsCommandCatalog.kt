package moe.afox.dpsandbox.cli

import moe.afox.dpsandbox.core.VersionProfile
import moe.afox.dpsandbox.core.VersionProfiles

object DpsCommandCatalog {
    private val baseRootCommands: List<CompletionSuggestion> = listOf(
        command("load", "run #minecraft:load"),
        command("reload", "reload datapack files"),
        command("tick", "advance sandbox ticks"),
        command("function", "run a loaded function"),
        command("player", "create or reuse a player"),
        command("event", "inject a player event"),
        command("trace", "toggle command trace printing"),
        command("diff", "inspect the last snapshot diff"),
        command("rerun", "rerun the last tracked command"),
        command("reset", "reset sandbox state"),
        command("inspect", "inspect sandbox state"),
        command("snapshot", "print or write a snapshot"),
        command("help", "show help"),
        command("exit", "leave the REPL"),
        command("quit", "leave the REPL"),
        command("advancement", "grant, revoke, or test advancement progress"),
        command("attribute", "read or edit stored entity attributes"),
        command("bossbar", "edit stored bossbar state"),
        command("clear", "remove items from player inventories"),
        command("clone", "copy sparse sandbox blocks"),
        command("damage", "apply sandbox health damage"),
        command("data", "read or mutate storage/entity/block NBT"),
        command("defaultgamemode", "edit world default game mode"),
        command("difficulty", "edit world difficulty"),
        command("effect", "give or clear player effects"),
        command("enchant", "write enchantment components"),
        command("execute", "run a command in a modified context"),
        command("experience", "edit player XP"),
        command("xp", "edit player XP"),
        command("fill", "fill sparse sandbox blocks"),
        command("fillbiome", "store sparse biome overrides"),
        command("forceload", "edit forced chunk state"),
        command("gamemode", "edit player game mode"),
        command("gamerule", "edit stored gamerule values"),
        command("give", "add items to players"),
        command("item", "replace entity item slots"),
        command("kill", "remove entities"),
        command("loot", "generate or place loot"),
        command("random", "generate deterministic random values"),
        command("recipe", "give or take player recipes"),
        command("ride", "edit riding relationships"),
        command("rotate", "edit entity yaw and pitch"),
        command("schedule", "schedule or clear functions"),
        command("scoreboard", "edit objectives and player scores"),
        command("seed", "report sandbox seed"),
        command("setblock", "place one sparse sandbox block"),
        command("setworldspawn", "edit world spawn point"),
        command("spawnpoint", "edit player spawn points"),
        command("spectate", "record spectator target"),
        command("spreadplayers", "deterministically spread entities"),
        command("summon", "create an entity"),
        command("tag", "edit entity tags"),
        command("team", "edit team state"),
        command("teleport", "move entities"),
        command("tp", "move entities"),
        command("time", "edit world time state"),
        command("weather", "edit weather state"),
        command("worldborder", "edit stored world border state"),
        command("tellraw", "record a raw JSON chat output"),
        command("title", "record title output"),
        command("trigger", "edit trigger objective scores"),
        command("say", "record chat output"),
        command("me", "record chat output"),
        command("msg", "record private chat output"),
        command("tell", "record private chat output"),
        command("w", "record private chat output"),
        command("teammsg", "record team chat output"),
        command("tm", "record team chat output"),
        command("playsound", "record a sound output"),
        command("stopsound", "record a stop-sound output"),
        command("particle", "record a visual output"),
        unsupported("datapack"),
        unsupported("debug"),
        unsupported("place"),
    ).distinctBy { it.value }.sortedBy { it.value }

    fun rootCommands(profile: VersionProfile = VersionProfiles.default): List<CompletionSuggestion> {
        val scoped = baseRootCommands.filter { suggestion ->
            suggestion.value in sandboxOnlyRoots || profile.commands.hasRoot(suggestion.value)
        }
        val known = scoped.mapTo(mutableSetOf()) { it.value }
        val inferredUnsupported = profile.commands.roots
            .filterNot { it in known }
            .map(::unsupported)
        return (scoped + inferredUnsupported).distinctBy { it.value }.sortedBy { it.value }
    }

    fun rootNames(profile: VersionProfile = VersionProfiles.default): Set<String> =
        rootCommands(profile).mapTo(sortedSetOf()) { it.value }

    fun usageSuffix(command: String): String =
        when (command) {
            "function" -> " <namespace:path>"
            "load" -> " [fixture <file>]"
            "tick" -> " [count]"
            "trace" -> " <on|off|status>"
            "diff" -> " last"
            "rerun" -> " last"
            "reset" -> " world"
            "inspect" -> " <score|storage|entities|blocks|player|loot|predicate|advancement|recipe|item_modifier|raw|tags|resources|registry|outputs>"
            "event" -> " player <name> <type> [id] [action]"
            "attribute" -> " <target> <attribute> <get|base|modifier> ..."
            "scoreboard" -> " objectives|players ..."
            "execute" -> " as|at|if|unless|store ... run <command>"
            "data" -> " <get|modify|merge|remove> <storage|entity|block> ..."
            "bossbar" -> " <add|remove|list|get|set> ..."
            "give" -> " <players> <item> [count]"
            "effect" -> " <give|clear> <players> ..."
            "defaultgamemode" -> " <mode>"
            "difficulty" -> " [peaceful|easy|normal|hard]"
            "fillbiome" -> " <from> <to> <biome> [replace <filter>]"
            "forceload" -> " <add|remove|query> ..."
            "gamemode" -> " <mode> [targets]"
            "advancement" -> " <grant|revoke|test> <players> ..."
            "schedule" -> " <function|clear> ..."
            "seed" -> ""
            "setblock" -> " <x> <y> <z> <block>"
            "setworldspawn" -> " [pos] [angle]"
            "spawnpoint" -> " [targets] [pos] [angle]"
            "spectate" -> " [target] [player]"
            "spreadplayers" -> " <center> <spreadDistance> <maxRange> <respectTeams> <targets>"
            "fill" -> " <from> <to> <block>"
            "trigger" -> " <objective> [add|set] [value]"
            "weather" -> " <clear|rain|thunder> [duration]"
            "time" -> " <set|add|query> ..."
            "worldborder" -> " <get|set|add|center|damage|warning> ..."
            else -> baseRootCommands.firstOrNull { it.value == command }
                ?.description
                ?.takeIf { it.isNotBlank() }
                ?.let { " - $it" }
                .orEmpty()
        }

    private fun command(value: String, description: String): CompletionSuggestion =
        CompletionSuggestion(value, description, "commands", appendSpace = true)

    private fun unsupported(value: String): CompletionSuggestion =
        CompletionSuggestion(value, "vanilla command: warning unless --unsupported error is set", "vanilla warnings", appendSpace = true)

    private val sandboxOnlyRoots = setOf(
        "load",
        "player",
        "event",
        "trace",
        "diff",
        "rerun",
        "reset",
        "inspect",
        "snapshot",
        "exit",
        "quit",
    )
}
