package moe.afox.dpsandbox.cli

internal object ReplPresentation {
    private const val WIDTH = 64

    fun dashboard(
        version: String,
        packs: Int,
        watch: Boolean,
        trace: Boolean,
        gameTime: Long,
        players: Int,
        entities: Int,
    ): String =
        buildString {
            appendLine(topBorder(" Datapack Sandbox "))
            appendLine(row("profile", ConsoleStyle.brightCyan(version), "packs", packs.toString()))
            appendLine(row("watch", state(watch), "trace", state(trace)))
            appendLine(row("game time", gameTime.toString(), "players / entities", "$players / $entities"))
            appendLine(divider())
            appendLine(line("Type ${ConsoleStyle.bold("help")} for commands | ${ConsoleStyle.bold("status")} to redraw this card"))
            append(bottomBorder())
        }

    fun prompt(
        version: String,
        watch: Boolean,
        trace: Boolean,
    ): String {
        val modes =
            buildList {
                if (watch) add("watch")
                if (trace) add("trace")
            }.joinToString(",")
        val suffix = if (modes.isEmpty()) "" else ConsoleStyle.gray(" [$modes]")
        return "${ConsoleStyle.brightCyan("dps")}${ConsoleStyle.gray("@$version")}$suffix ${ConsoleStyle.cyan(">")} "
    }

    fun help(): String =
        buildString {
            appendLine(section("Run"))
            appendLine(command("load", "run #minecraft:load"))
            appendLine(command("tick [n]", "advance the world"))
            appendLine(command("function <id>", "run a datapack function"))
            appendLine(command("<minecraft command>", "execute it directly"))
            appendLine()
            appendLine(section("Sandbox"))
            appendLine(command("player <name>", "create a sandbox player"))
            appendLine(command("event player ...", "inject a player behavior event"))
            appendLine(command("load fixture <file>", "apply a world fixture"))
            appendLine(command("reset world", "start from a fresh sparse world"))
            appendLine()
            appendLine(section("Inspect"))
            appendLine(command("status", "show the session dashboard"))
            appendLine(command("inspect <kind>", "world, scores, entities, resources, outputs, ..."))
            appendLine(command("snapshot [file]", "print or save the complete state"))
            appendLine(command("diff last", "show changes from the previous command"))
            appendLine()
            appendLine(section("Workflow"))
            appendLine(command("reload", "reload packs and keep world state"))
            appendLine(command("trace on|off|status", "control live command traces"))
            appendLine(command("rerun last", "repeat the previous world-changing command"))
            appendLine(command("help <command>", "show focused help"))
            append(command("exit", "leave the REPL"))
        }

    fun detailHelp(
        command: String,
        text: String,
    ): String = "${section(command)}\n${text.prependIndent("  ")}"

    fun success(
        label: String,
        detail: String,
    ): String = "${ConsoleStyle.success("OK")} ${ConsoleStyle.bold(label)} ${ConsoleStyle.gray("($detail)")}"

    fun warning(text: String): String = "${ConsoleStyle.yellow("!")} $text"

    private fun section(name: String): String = ConsoleStyle.bold(ConsoleStyle.brightCyan(name.uppercase()))

    private fun command(
        command: String,
        description: String,
    ): String = "  ${ConsoleStyle.cyan(command.padEnd(25))} ${ConsoleStyle.dim(description)}"

    private fun state(enabled: Boolean): String = if (enabled) ConsoleStyle.green("on") else ConsoleStyle.gray("off")

    private fun topBorder(title: String): String {
        val remaining = (WIDTH - title.length - 2).coerceAtLeast(1)
        return ConsoleStyle.gray("+-") + ConsoleStyle.bold(title) + ConsoleStyle.gray("${"-".repeat(remaining)}+")
    }

    private fun divider(): String = ConsoleStyle.gray("+${"-".repeat(WIDTH)}+")

    private fun bottomBorder(): String = ConsoleStyle.gray("+${"-".repeat(WIDTH)}+")

    private fun row(
        leftLabel: String,
        leftValue: String,
        rightLabel: String,
        rightValue: String,
    ): String {
        val left = "${leftLabel.padEnd(10)} $leftValue"
        val right = "${rightLabel.padEnd(18)} $rightValue"
        return line(left.padEnd(29) + right)
    }

    private fun line(content: String): String =
        ConsoleStyle.gray("|") + " " + content + " ".repeat((WIDTH - content.visibleLength() - 1).coerceAtLeast(1)) + ConsoleStyle.gray("|")

    private fun String.visibleLength(): Int = replace(Regex("\u001B\\[[;\\d]*m"), "").length
}
