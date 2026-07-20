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
        suggestions(line.line(), line.cursor()).forEachIndexed { index, suggestion ->
            candidates +=
                Candidate(
                    suggestion.value,
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
