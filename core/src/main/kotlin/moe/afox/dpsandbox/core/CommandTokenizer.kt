package moe.afox.dpsandbox.core

data class CommandToken(val text: String, val start: Int, val end: Int)

object CommandTokenizer {
    fun tokenize(command: String, location: SourceLocation? = null): List<CommandToken> {
        val tokens = mutableListOf<CommandToken>()
        var index = 0
        while (index < command.length) {
            while (index < command.length && command[index].isWhitespace()) index++
            if (index >= command.length) break

            val start = index
            val builder = StringBuilder()
            val quote = if (command[index] == '"' || command[index] == '\'') command[index++] else null
            var escaped = false

            if (quote == null) {
                while (index < command.length && !command[index].isWhitespace()) {
                    builder.append(command[index++])
                }
            } else {
                while (index < command.length) {
                    val char = command[index++]
                    when {
                        escaped -> {
                            builder.append(char)
                            escaped = false
                        }
                        char == '\\' -> escaped = true
                        char == quote -> break
                        else -> builder.append(char)
                    }
                }
                if (index >= command.length && (command.lastOrNull() != quote || escaped)) {
                    throw SandboxException(
                        code = DiagnosticCode.INPUT_FORMAT,
                        message = "Unterminated quoted string",
                        location = location,
                        command = command,
                    )
                }
            }

            tokens += CommandToken(builder.toString(), start, index)
        }
        return tokens
    }

    fun tailFrom(command: String, token: CommandToken): String =
        command.substring(token.start).trim()

    fun tailAfter(command: String, token: CommandToken): String =
        command.substring(token.end).trimStart()
}
