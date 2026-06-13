package moe.afox.dpsandbox.cli

data class CompletionContext(
    val words: List<String>,
    val wordIndex: Int,
    val prefix: String,
    val endsWithWhitespace: Boolean,
) {
    val first: String = words.firstOrNull()?.removePrefix("/")?.lowercase().orEmpty()

    fun filter(options: List<CompletionSuggestion>): List<CompletionSuggestion> {
        val rawPrefix = if (wordIndex == 0) prefix.removePrefix("/") else prefix
        val slashRoot = wordIndex == 0 && prefix.startsWith("/")
        return options.asSequence()
            .filter { rawPrefix.isBlank() || it.value.startsWith(rawPrefix) }
            .distinctBy { it.value }
            .sortedWith(compareBy<CompletionSuggestion> { it.value != rawPrefix }.thenBy { it.value })
            .map { if (slashRoot) it.copy(value = "/${it.value}") else it }
            .toList()
    }

    companion object {
        fun parse(buffer: String, cursor: Int = buffer.length): CompletionContext {
            val beforeCursor = buffer.take(cursor.coerceIn(0, buffer.length))
            if (beforeCursor.isBlank()) {
                return CompletionContext(emptyList(), 0, "", beforeCursor.lastOrNull()?.isWhitespace() == true)
            }
            val tokens = tokenPattern.findAll(beforeCursor).map { it.value }.toList()
            val trailingWhitespace = beforeCursor.lastOrNull()?.isWhitespace() == true
            val wordIndex = if (trailingWhitespace) tokens.size else (tokens.size - 1).coerceAtLeast(0)
            val prefix = if (trailingWhitespace) "" else tokens.lastOrNull().orEmpty()
            return CompletionContext(
                words = tokens.mapIndexed { index, token -> if (index == 0) token.removePrefix("/") else token },
                wordIndex = wordIndex,
                prefix = prefix,
                endsWithWhitespace = trailingWhitespace,
            )
        }

        private val tokenPattern = Regex("""(?:"(?:\\.|[^"\\])*"?|'(?:\\.|[^'\\])*'?|\S+)""")
    }
}
