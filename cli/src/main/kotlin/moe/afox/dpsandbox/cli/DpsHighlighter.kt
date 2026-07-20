package moe.afox.dpsandbox.cli

import moe.afox.dpsandbox.core.VersionProfile
import moe.afox.dpsandbox.core.VersionProfiles
import org.jline.reader.Highlighter
import org.jline.reader.LineReader
import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder
import org.jline.utils.AttributedStyle

class DpsHighlighter(
    private val profile: () -> VersionProfile = { VersionProfiles.default },
) : Highlighter {
    override fun highlight(
        reader: LineReader,
        buffer: String,
    ): AttributedString = highlightLine(buffer)

    fun highlightLine(buffer: String): AttributedString {
        val builder = AttributedStringBuilder(buffer.length)
        val matches = tokenPattern.findAll(buffer).toList()
        var nonBlankIndex = -1
        matches.forEach { match ->
            val token = match.value
            val tokenIndex =
                if (token.isBlank()) {
                    -1
                } else {
                    nonBlankIndex += 1
                    nonBlankIndex
                }
            val style = styleFor(token, tokenIndex)
            builder.styled(style, token)
        }
        return builder.toAttributedString()
    }

    private fun styleFor(
        token: String,
        index: Int,
    ): AttributedStyle =
        when {
            token.isBlank() -> normal
            index == 0 -> commandStyle(token)
            token.startsWith("@") -> selectorStyle
            token.isQuoted() -> stringStyle
            token.isNumberLike() -> numberStyle
            token.isResourceLocationLike() -> resourceStyle
            token in syntaxKeywords -> keywordStyle
            token.startsWith("{") || token.startsWith("[") || token.endsWith("}") || token.endsWith("]") -> nbtStyle
            else -> normal
        }

    private fun commandStyle(token: String): AttributedStyle {
        val command = token.removePrefix("/")
        val rootCommands = DpsCommandCatalog.rootNames(profile())
        return if (command in rootCommands || rootCommands.any { it.startsWith(command) }) {
            rootCommandStyle
        } else {
            errorStyle
        }
    }

    private fun String.isQuoted(): Boolean = length >= 1 && (startsWith("\"") || startsWith("'"))

    private fun String.isNumberLike(): Boolean = matches(numberPattern) || this == "~" || startsWith("~") && drop(1).matches(numberPattern)

    private fun String.isResourceLocationLike(): Boolean = trimEnd(',', ']', '}', ')').matches(resourceLocationPattern)

    companion object {
        private val tokenPattern = Regex("""\s+|"(?:\\.|[^"\\])*"?|'(?:\\.|[^'\\])*'?|\S+""")
        private val numberPattern = Regex("""-?\d+(?:\.\d+)?[bBsSlLfFdD]?""")
        private val resourceLocationPattern = Regex("""[a-z0-9_.-]+:[a-z0-9_./-]+""")

        private val syntaxKeywords =
            setOf(
                "add",
                "append",
                "as",
                "at",
                "bossbar",
                "block",
                "clear",
                "destroy",
                "entity",
                "everything",
                "from",
                "get",
                "if",
                "insert",
                "keep",
                "list",
                "merge",
                "modify",
                "mount",
                "move",
                "masked",
                "normal",
                "objectives",
                "only",
                "players",
                "positioned",
                "prepend",
                "query",
                "remove",
                "replace",
                "result",
                "revoke",
                "run",
                "score",
                "set",
                "success",
                "storage",
                "strict",
                "through",
                "until",
                "unless",
                "value",
                "with",
            )

        private val normal = AttributedStyle.DEFAULT
        private val rootCommandStyle = AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN).bold()
        private val selectorStyle = AttributedStyle.DEFAULT.foreground(AttributedStyle.MAGENTA).bold()
        private val resourceStyle = AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN)
        private val numberStyle = AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW)
        private val stringStyle = AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN)
        private val keywordStyle = AttributedStyle.DEFAULT.foreground(AttributedStyle.BLUE)
        private val nbtStyle = AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW)
        private val errorStyle = AttributedStyle.DEFAULT.foreground(AttributedStyle.RED).bold()
    }
}
