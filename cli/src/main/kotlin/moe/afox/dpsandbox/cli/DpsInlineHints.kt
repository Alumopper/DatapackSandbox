package moe.afox.dpsandbox.cli

import org.jline.reader.LineReader
import org.jline.reader.Widget
import org.jline.utils.AttributedString
import org.jline.widget.Widgets

class DpsInlineHints private constructor(
    reader: LineReader,
    private val completer: DpsCompleter,
    private val multilineDescriptions: Boolean,
) : Widgets(reader) {
    private var enabled = false
    private val tailTipCache = DpsHintCache<String>()
    private val descriptionCache = DpsHintCache<List<AttributedString>>()

    init {
        addWidget("_dps-self-insert", updatingWidget(LineReader.SELF_INSERT))
        addWidget("_dps-backward-delete-char", updatingWidget(LineReader.BACKWARD_DELETE_CHAR))
        addWidget("_dps-delete-char", updatingWidget(LineReader.DELETE_CHAR))
        addWidget("_dps-backward-kill-word", updatingWidget(LineReader.BACKWARD_KILL_WORD))
        addWidget("_dps-kill-line", updatingWidget(LineReader.KILL_LINE))
        addWidget("_dps-kill-whole-line", updatingWidget(LineReader.KILL_WHOLE_LINE))
        addWidget("_dps-expand-or-complete", updatingWidget(LineReader.EXPAND_OR_COMPLETE))
        addWidget(
            "_dps-redisplay",
            Widget {
                refresh()
                builtin(LineReader.REDISPLAY)
            },
        )
        addWidget(
            "_dps-accept-line",
            Widget {
                clearHints()
                builtin(LineReader.ACCEPT_LINE)
            },
        )
    }

    fun enable() {
        if (enabled) return
        if (!multilineDescriptions) destroyDescription()
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
        refresh()
    }

    private fun updatingWidget(builtinName: String): Widget =
        Widget {
            if (builtinName == LineReader.EXPAND_OR_COMPLETE) {
                updateTailTip("")
            }
            val result = builtin(builtinName)
            refresh()
            result
        }

    private fun builtin(name: String): Boolean = reader.builtinWidgets[name]?.apply() ?: false

    private fun refresh() {
        updateTailTip(completer.inlineHint(buffer().toString()))
        val description = completer.multilineHints(buffer().toString())
        updateDescription(description)
    }

    private fun updateTailTip(tailTip: String) {
        if (!tailTipCache.update(tailTip)) return
        setTailTip(tailTip)
    }

    private fun updateDescription(description: List<AttributedString>) {
        if (!multilineDescriptions) return
        if (!descriptionCache.update(description)) return
        if (description.isEmpty()) clearDescription() else setDescription(description)
    }

    private fun clearHints() {
        updateTailTip("")
        updateDescription(emptyList())
    }

    companion object {
        fun install(
            reader: LineReader,
            completer: DpsCompleter,
        ) {
            DpsInlineHints(
                reader = reader,
                completer = completer,
                multilineDescriptions =
                    DpsInlineHintPolicy.multilineDescriptionsEnabled(
                        osName = System.getProperty("os.name").orEmpty(),
                        override = System.getenv("DPS_MULTILINE_HINTS"),
                    ),
            ).enable()
        }
    }
}

internal object DpsInlineHintPolicy {
    fun multilineDescriptionsEnabled(
        osName: String,
        override: String?,
    ): Boolean =
        when (override?.trim()?.lowercase()) {
            "1", "true", "on", "yes" -> true
            "0", "false", "off", "no" -> false
            else -> !osName.startsWith("Windows", ignoreCase = true)
        }
}

internal class DpsHintCache<T> {
    private var initialized = false
    private var value: T? = null

    fun update(next: T): Boolean {
        if (initialized && value == next) return false
        initialized = true
        value = next
        return true
    }
}
