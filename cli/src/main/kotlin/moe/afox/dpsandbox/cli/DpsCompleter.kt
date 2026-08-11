package moe.afox.dpsandbox.cli

import moe.afox.dpsandbox.core.DatapackSandbox
import org.jline.reader.Candidate
import org.jline.reader.Completer
import org.jline.reader.LineReader
import org.jline.reader.ParsedLine

class DpsCompleter(
    sandbox: () -> DatapackSandbox,
) : Completer {
    private val engine = DpsCompletionEngine(sandbox)

    override fun complete(
        reader: LineReader,
        line: ParsedLine,
        candidates: MutableList<Candidate>,
    ) {
        candidates += completionCandidates(line.line(), line.cursor())
    }

    private fun completionCandidates(
        buffer: String,
        cursor: Int = buffer.length,
    ): List<Candidate> {
        val tokenStart = activeCommandTokenStart(buffer, cursor)
        return engine.rangedSuggestions(buffer, cursor).mapIndexed { index, completion ->
            val suggestion = completion.suggestion
            val insertion =
                if (completion.start in tokenStart..cursor) {
                    buffer.substring(tokenStart, completion.start) + suggestion.value
                } else {
                    suggestion.value
                }
            Candidate(
                insertion,
                suggestion.value,
                suggestion.group,
                suggestion.description.takeIf { it.isNotBlank() },
                if (suggestion.appendSpace) " " else null,
                null,
                true,
                index,
            )
        }
    }

    private fun activeCommandTokenStart(
        buffer: String,
        cursor: Int,
    ): Int {
        val end = cursor.coerceIn(0, buffer.length)
        var tokenStart = end
        var quote: Char? = null
        var escaped = false
        var depth = 0
        for (index in 0 until end) {
            val char = buffer[index]
            if (tokenStart == end && !char.isWhitespace()) tokenStart = index
            when {
                escaped -> escaped = false
                char == '\\' && quote != null -> escaped = true
                quote != null -> if (char == quote) quote = null
                char == '"' || char == '\'' -> quote = char
                char == '{' || char == '[' || char == '(' -> depth += 1
                char == '}' || char == ']' || char == ')' -> depth = (depth - 1).coerceAtLeast(0)
                char.isWhitespace() && depth == 0 -> tokenStart = end
            }
        }
        return tokenStart
    }

    fun suggestions(
        buffer: String,
        cursor: Int = buffer.length,
    ): List<CompletionSuggestion> = engine.suggestions(buffer, cursor)

    fun inlineHint(
        buffer: String,
        cursor: Int = buffer.length,
    ): String = engine.inlineHint(buffer, cursor)

    fun multilineHints(
        buffer: String,
        cursor: Int = buffer.length,
    ) = engine.multilineHints(buffer, cursor)
}
