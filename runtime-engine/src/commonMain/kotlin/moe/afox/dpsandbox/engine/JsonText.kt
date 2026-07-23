package moe.afox.dpsandbox.engine

internal object JsonText {
    fun quote(value: String): String =
        buildString(value.length + 2) {
            append('"')
            value.forEach { char ->
                when (char) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000c' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (char.code < 0x20) append("\\u${char.code.toString(16).padStart(4, '0')}") else append(char)
                }
            }
            append('"')
        }

    fun array(values: Iterable<String>): String = values.joinToString(prefix = "[", postfix = "]")

    fun obj(vararg values: Pair<String, String>): String =
        values.joinToString(prefix = "{", postfix = "}") { (name, value) -> "${quote(name)}:$value" }
}
