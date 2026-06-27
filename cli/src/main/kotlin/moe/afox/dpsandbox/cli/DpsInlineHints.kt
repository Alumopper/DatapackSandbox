package moe.afox.dpsandbox.cli

import org.jline.reader.LineReader
import org.jline.reader.Widget
import org.jline.widget.Widgets

class DpsInlineHints private constructor(
    reader: LineReader,
    private val completer: DpsCompleter,
) : Widgets(reader) {
    private var enabled = false

    init {
        addWidget("_dps-self-insert", updatingWidget(LineReader.SELF_INSERT))
        addWidget("_dps-backward-delete-char", updatingWidget(LineReader.BACKWARD_DELETE_CHAR))
        addWidget("_dps-delete-char", updatingWidget(LineReader.DELETE_CHAR))
        addWidget("_dps-backward-kill-word", updatingWidget(LineReader.BACKWARD_KILL_WORD))
        addWidget("_dps-kill-line", updatingWidget(LineReader.KILL_LINE))
        addWidget("_dps-kill-whole-line", updatingWidget(LineReader.KILL_WHOLE_LINE))
        addWidget("_dps-expand-or-complete", updatingWidget(LineReader.EXPAND_OR_COMPLETE))
        addWidget("_dps-redisplay", Widget {
            refresh(redraw = false)
            builtin(LineReader.REDISPLAY)
        })
        addWidget("_dps-accept-line", Widget {
            setTailTip("")
            clearDescription()
            builtin(LineReader.ACCEPT_LINE)
        })
    }

    fun enable() {
        if (enabled) return
        aliasWidget("_dps-accept-line", LineReader.ACCEPT_LINE)
        aliasWidget("_dps-backward-delete-char", LineReader.BACKWARD_DELETE_CHAR)
        aliasWidget("_dps-delete-char", LineReader.DELETE_CHAR)
        aliasWidget("_dps-expand-or-complete", LineReader.EXPAND_OR_COMPLETE)
        aliasWidget("_dps-self-insert", LineReader.SELF_INSERT)
        aliasWidget("_dps-redisplay", LineReader.REDISPLAY)
        aliasWidget("_dps-kill-line", LineReader.KILL_LINE)
        aliasWidget("_dps-kill-whole-line", LineReader.KILL_WHOLE_LINE)
        aliasWidget("_dps-backward-kill-word", LineReader.BACKWARD_KILL_WORD)
        setSuggestionType(LineReader.SuggestionType.TAIL_TIP)
        enabled = true
        refresh(redraw = false)
    }

    private fun updatingWidget(builtinName: String): Widget =
        Widget {
            if (builtinName == LineReader.EXPAND_OR_COMPLETE) {
                setTailTip("")
                builtin(LineReader.REDISPLAY)
            }
            val result = builtin(builtinName)
            refresh()
            result
        }

    private fun builtin(name: String): Boolean =
        reader.builtinWidgets[name]?.apply() ?: false

    private fun refresh(redraw: Boolean = true) {
        setTailTip(completer.inlineHint(buffer().toString()))
        val description = completer.multilineHints(buffer().toString())
        if (description.isEmpty()) {
            clearDescription()
        } else {
            setDescription(description)
        }
        if (redraw && reader.isReading) {
            builtin(LineReader.REDISPLAY)
        }
    }

    companion object {
        fun install(reader: LineReader, completer: DpsCompleter) {
            DpsInlineHints(reader, completer).enable()
        }
    }
}
