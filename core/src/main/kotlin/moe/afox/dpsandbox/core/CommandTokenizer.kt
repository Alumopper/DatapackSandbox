package moe.afox.dpsandbox.core

data class CommandToken(
    val text: String,
    val start: Int,
    val end: Int,
    val sourceText: String = text,
)

object CommandTokenizer {
    fun tokenize(
        command: String,
        location: SourceLocation? = null,
    ): List<CommandToken> {
        val tokens = mutableListOf<CommandToken>()
        var index = 0
        while (index < command.length) {
            while (index < command.length && command[index].isWhitespace()) index++
            if (index >= command.length) break

            val start = index
            val builder = StringBuilder()
            val quote = if (command[index] == '"' || command[index] == '\'') command[index++] else null
            var escaped = false
            var quotedEnd = -1

            if (quote == null) {
                var nestedQuote: Char? = null
                var objectDepth = 0
                var arrayDepth = 0
                while (index < command.length) {
                    val char = command[index]
                    if (nestedQuote != null) {
                        builder.append(char)
                        index++
                        when {
                            escaped -> escaped = false
                            char == '\\' -> escaped = true
                            char == nestedQuote -> nestedQuote = null
                        }
                        continue
                    }
                    if (char.isWhitespace() && objectDepth == 0 && arrayDepth == 0) break
                    when (char) {
                        '"', '\'' -> nestedQuote = char
                        '{' -> objectDepth++
                        '}' -> objectDepth--
                        '[' -> arrayDepth++
                        ']' -> arrayDepth--
                    }
                    if (objectDepth < 0 || arrayDepth < 0) {
                        throw SandboxException(
                            code = DiagnosticCode.INPUT_FORMAT,
                            message = "Malformed bracketed command token",
                            location = location,
                            command = command,
                        )
                    }
                    builder.append(char)
                    index++
                }
                if (nestedQuote != null) {
                    throw SandboxException(
                        code = DiagnosticCode.INPUT_FORMAT,
                        message = "Unterminated quoted string",
                        location = location,
                        command = command,
                    )
                }
                if (objectDepth != 0 || arrayDepth != 0) {
                    throw SandboxException(
                        code = DiagnosticCode.INPUT_FORMAT,
                        message = "Unterminated bracketed command token",
                        location = location,
                        command = command,
                    )
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
                        char == quote -> {
                            quotedEnd = index
                            break
                        }
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
                // An NBT path may start with a quoted field and continue with .field or [index].
                // Keep that complete syntax together; standalone quoted arguments stay decoded.
                if (quotedEnd >= 0 && index < command.length && !command[index].isWhitespace()) {
                    val suffix = tokenize(command.substring(index), location).first()
                    index += suffix.end
                    builder.clear().append(command.substring(start, index))
                }
            }

            tokens += CommandToken(builder.toString(), start, index, command.substring(start, index))
        }
        return tokens
    }

    fun tailFrom(
        command: String,
        token: CommandToken,
    ): String = command.substring(token.start).trim()

    fun tailAfter(
        command: String,
        token: CommandToken,
    ): String = command.substring(token.end).trimStart()
}
