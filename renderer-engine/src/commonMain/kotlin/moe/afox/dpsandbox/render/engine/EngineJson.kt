package moe.afox.dpsandbox.render.engine

internal sealed interface EngineJsonValue

internal data class EngineJsonObject(
    val values: Map<String, EngineJsonValue>,
) : EngineJsonValue {
    fun objectValue(name: String): EngineJsonObject? = values[name] as? EngineJsonObject

    fun arrayValue(name: String): List<EngineJsonValue>? = (values[name] as? EngineJsonArray)?.values

    fun string(name: String): String? = (values[name] as? EngineJsonString)?.value

    fun number(name: String): Double? = (values[name] as? EngineJsonNumber)?.value

    fun int(name: String): Int? = number(name)?.toInt()

    fun boolean(name: String): Boolean? = (values[name] as? EngineJsonBoolean)?.value

    fun vector(name: String): EngineVec3? {
        val array = arrayValue(name) ?: return null
        if (array.size != 3) return null
        val numbers = array.map { (it as? EngineJsonNumber)?.value ?: return null }
        return EngineVec3(numbers[0], numbers[1], numbers[2])
    }

    fun numberArray(
        name: String,
        size: Int,
    ): List<Double>? {
        val array = arrayValue(name) ?: return null
        if (array.size != size) return null
        return array.map { (it as? EngineJsonNumber)?.value ?: return null }
    }
}

internal data class EngineJsonArray(
    val values: List<EngineJsonValue>,
) : EngineJsonValue

internal data class EngineJsonString(
    val value: String,
) : EngineJsonValue

internal data class EngineJsonNumber(
    val value: Double,
) : EngineJsonValue

internal data class EngineJsonBoolean(
    val value: Boolean,
) : EngineJsonValue

internal data object EngineJsonNull : EngineJsonValue

internal class EngineJsonParser(
    private val source: String,
) {
    private var index = 0

    fun parseObject(): EngineJsonObject? = runCatching { parseValue() as? EngineJsonObject }.getOrNull()

    private fun parseValue(): EngineJsonValue {
        whitespace()
        return when (peek()) {
            '{' -> parseObjectValue()
            '[' -> parseArray()
            '"' -> EngineJsonString(parseString())
            't' -> literal("true", EngineJsonBoolean(true))
            'f' -> literal("false", EngineJsonBoolean(false))
            'n' -> literal("null", EngineJsonNull)
            else -> parseNumber()
        }
    }

    private fun parseObjectValue(): EngineJsonObject {
        expect('{')
        whitespace()
        val values = linkedMapOf<String, EngineJsonValue>()
        if (consume('}')) return EngineJsonObject(values)
        while (true) {
            whitespace()
            val key = parseString()
            whitespace()
            expect(':')
            values[key] = parseValue()
            whitespace()
            if (consume('}')) return EngineJsonObject(values)
            expect(',')
        }
    }

    private fun parseArray(): EngineJsonArray {
        expect('[')
        whitespace()
        val values = mutableListOf<EngineJsonValue>()
        if (consume(']')) return EngineJsonArray(values)
        while (true) {
            values += parseValue()
            whitespace()
            if (consume(']')) return EngineJsonArray(values)
            expect(',')
        }
    }

    private fun parseString(): String {
        expect('"')
        val result = StringBuilder()
        while (index < source.length) {
            val character = source[index++]
            when {
                character == '"' -> return result.toString()
                character != '\\' -> result.append(character)
                index >= source.length -> error("Unterminated JSON escape")
                else -> {
                    when (val escaped = source[index++]) {
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000c')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            require(index + 4 <= source.length) { "Incomplete JSON unicode escape" }
                            result.append(source.substring(index, index + 4).toInt(16).toChar())
                            index += 4
                        }
                        else -> error("Invalid JSON escape $escaped")
                    }
                }
            }
        }
        error("Unterminated JSON string")
    }

    private fun parseNumber(): EngineJsonNumber {
        val start = index
        if (peek() == '-') index += 1
        digits()
        if (peekOrNull() == '.') {
            index += 1
            digits()
        }
        if (peekOrNull() == 'e' || peekOrNull() == 'E') {
            index += 1
            if (peekOrNull() == '+' || peekOrNull() == '-') index += 1
            digits()
        }
        return EngineJsonNumber(source.substring(start, index).toDouble())
    }

    private fun digits() {
        val start = index
        while (peekOrNull()?.isDigit() == true) index += 1
        require(index > start) { "Expected JSON number" }
    }

    private fun <T : EngineJsonValue> literal(
        text: String,
        value: T,
    ): T {
        require(source.startsWith(text, index)) { "Invalid JSON literal" }
        index += text.length
        return value
    }

    private fun whitespace() {
        while (peekOrNull()?.isWhitespace() == true) index += 1
    }

    private fun expect(character: Char) {
        require(consume(character)) { "Expected '$character' at JSON offset $index" }
    }

    private fun consume(character: Char): Boolean {
        if (peekOrNull() != character) return false
        index += 1
        return true
    }

    private fun peek(): Char = peekOrNull() ?: error("Unexpected end of JSON")

    private fun peekOrNull(): Char? = source.getOrNull(index)
}
