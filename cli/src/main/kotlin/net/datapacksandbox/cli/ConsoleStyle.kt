package net.datapacksandbox.cli

import net.datapacksandbox.core.OutputTextSegment

object ConsoleStyle {
    private val enabled: Boolean =
        System.getenv("NO_COLOR") == null &&
            (System.console() != null || System.getenv("CLICOLOR_FORCE") == "1")

    fun green(text: String): String = color("32", text)
    fun red(text: String): String = color("31", text)
    fun yellow(text: String): String = color("33", text)
    fun blue(text: String): String = color("34", text)
    fun magenta(text: String): String = color("35", text)
    fun cyan(text: String): String = color("36", text)
    fun dim(text: String): String = color("2", text)
    fun bold(text: String): String = color("1", text)

    fun diagnostic(text: String): String = red(text)

    fun minecraft(segments: List<OutputTextSegment>): String =
        if (segments.isEmpty()) "" else segments.joinToString(separator = "") { minecraft(it) }

    private fun color(code: String, text: String): String =
        if (enabled) "\u001B[${code}m$text\u001B[0m" else text

    private fun minecraft(segment: OutputTextSegment): String {
        if (!enabled) return segment.text
        val codes = buildList {
            segment.color?.let { add(minecraftColorCode(it)) }
            if (segment.bold) add("1")
            if (segment.italic) add("3")
            if (segment.underlined) add("4")
            if (segment.strikethrough) add("9")
            if (segment.obfuscated) add("2")
        }.filterNotNull()
        return if (codes.isEmpty()) segment.text else "\u001B[${codes.joinToString(";")}m${segment.text}\u001B[0m"
    }

    private fun minecraftColorCode(color: String): String? =
        when (color) {
            "black" -> "30"
            "dark_blue" -> "34"
            "dark_green" -> "32"
            "dark_aqua" -> "36"
            "dark_red" -> "31"
            "dark_purple" -> "35"
            "gold" -> "33"
            "gray" -> "37"
            "dark_gray" -> "90"
            "blue" -> "94"
            "green" -> "92"
            "aqua" -> "96"
            "red" -> "91"
            "light_purple" -> "95"
            "yellow" -> "93"
            "white" -> "97"
            else -> null
        }
}
