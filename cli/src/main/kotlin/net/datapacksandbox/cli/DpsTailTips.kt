package net.datapacksandbox.cli

import org.jline.console.ArgDesc
import org.jline.console.CmdDesc
import org.jline.utils.AttributedString

object DpsTailTips {
    fun descriptions(): Map<String, CmdDesc> = linkedMapOf(
        "load" to desc("run #minecraft:load"),
        "reload" to desc("reload datapack files and keep current world state"),
        "tick" to desc("tick [count]", "advance scheduled functions, tick functions, and player tick triggers"),
        "function" to desc("function <namespace:path>", "run one loaded function"),
        "player" to desc("player <name>", "create or reuse a sandbox player with readable vanilla-style NBT"),
        "event" to desc("event player <name> <type> [id] [action]", "inject player actions, including key_input and mouse_input"),
        "inspect" to desc("inspect <score|storage|entities|blocks|player|loot|predicate|advancement|registry|outputs>"),
        "snapshot" to desc("snapshot [file]", "print or write deterministic world JSON"),
        "scoreboard" to desc("scoreboard objectives|players ..."),
        "execute" to desc("execute as|at|positioned|if|unless ... run <command>"),
        "data" to desc("data <get|modify|merge|remove> <storage|entity|block> ...", "entity and block writes reject unknown vanilla NBT fields"),
        "tag" to desc("tag <selector> <add|remove|list> [name]"),
        "summon" to desc("summon <entity> [x y z] [snbt]", "creates an entity with validated top-level NBT"),
        "kill" to desc("kill <selector>"),
        "tp" to desc("tp <targets> <x> <y> <z>|<destination>"),
        "teleport" to desc("teleport <targets> <x> <y> <z>|<destination>"),
        "setblock" to desc("setblock <x> <y> <z> <block[state]{nbt}> [replace|keep|destroy]"),
        "fill" to desc("fill <from> <to> <block[state]{nbt}> [replace|keep]"),
        "schedule" to desc("schedule function <id> <time> [append|replace]"),
        "advancement" to desc("advancement <grant|revoke|test> <targets> only <id> [criterion]"),
        "tellraw" to desc("tellraw <players> <raw-json-text>"),
        "title" to desc("title <players> <title|subtitle|actionbar|clear|reset|times> ..."),
        "say" to desc("say <message>"),
        "me" to desc("me <action>"),
        "msg" to desc("msg <players> <message>"),
        "tell" to desc("tell <players> <message>"),
        "w" to desc("w <players> <message>"),
        "teammsg" to desc("teammsg <message>"),
        "tm" to desc("tm <message>"),
        "playsound" to desc("playsound <sound> <source> <players> [pos] [volume] [pitch] [minVolume]"),
        "stopsound" to desc("stopsound <players> [source] [sound]"),
        "particle" to desc("particle <name> [arguments...]"),
        "help" to desc("help [command]"),
        "exit" to desc("exit"),
        "quit" to desc("quit"),
    )

    private fun desc(vararg lines: String): CmdDesc =
        CmdDesc(
            lines.map { AttributedString(it) },
            ArgDesc.doArgNames(emptyList()),
            emptyMap(),
        )
}
