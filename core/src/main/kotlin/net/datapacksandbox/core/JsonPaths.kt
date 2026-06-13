package net.datapacksandbox.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

object JsonValues {
    val prettyGson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun parse(raw: String, location: SourceLocation? = null): JsonElement =
        try {
            SnbtParser(raw).parse()
        } catch (error: Exception) {
            try {
                JsonParser.parseString(raw)
            } catch (_: Exception) {
                if (raw.matches(Regex("[A-Za-z0-9_./:-]+"))) {
                    JsonPrimitive(raw)
                } else {
                    throw SandboxException(
                        code = DiagnosticCode.INPUT_FORMAT,
                        message = "Invalid JSON/SNBT-lite value: $raw",
                        location = location,
                        cause = error,
                    )
                }
            }
        }

    fun render(element: JsonElement): String = prettyGson.toJson(element)
}

object JsonPaths {
    fun get(root: JsonObject, path: String?): JsonElement? {
        if (path.isNullOrBlank()) return root
        return NbtPath.parse(path).get(root)
    }

    fun set(root: JsonObject, path: String, value: JsonElement) {
        NbtPath.parse(path).set(root, value)
    }

    fun merge(root: JsonObject, path: String?, value: JsonElement) {
        val current = get(root, path)
        if (path.isNullOrBlank()) {
            mergeInto(root, value)
        } else if (current != null && current.isJsonObject) {
            mergeInto(current.asJsonObject, value)
        } else {
            set(root, path, value)
        }
    }

    fun append(root: JsonObject, path: String, value: JsonElement) {
        val array = requireArray(root, path)
        array.add(value)
    }

    fun prepend(root: JsonObject, path: String, value: JsonElement) {
        val array = requireArray(root, path)
        val replacement = JsonArray()
        replacement.add(value.deepCopy())
        array.forEach { replacement.add(it.deepCopy()) }
        set(root, path, replacement)
    }

    fun insert(root: JsonObject, path: String, index: Int, value: JsonElement) {
        val array = requireArray(root, path)
        val targetIndex = index.coerceIn(0, array.size())
        val replacement = JsonArray()
        array.forEachIndexed { currentIndex, element ->
            if (currentIndex == targetIndex) replacement.add(value.deepCopy())
            replacement.add(element.deepCopy())
        }
        if (targetIndex == array.size()) replacement.add(value.deepCopy())
        set(root, path, replacement)
    }

    fun remove(root: JsonObject, path: String): Boolean {
        return NbtPath.parse(path).remove(root)
    }

    fun exists(root: JsonObject, path: String): Boolean =
        get(root, path) != null && get(root, path) !is JsonNull

    private fun requireArray(root: JsonObject, path: String): JsonArray {
        val value = get(root, path)
        if (value != null && value.isJsonArray) return value.asJsonArray
        val array = JsonArray()
        set(root, path, array)
        return get(root, path)?.asJsonArray
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Failed to create array at path '$path'")
    }

    private fun mergeInto(target: JsonObject, value: JsonElement) {
        if (!value.isJsonObject) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Merged value must be an object")
        }
        value.asJsonObject.entrySet().forEach { (key, incoming) ->
            val existing = target.get(key)
            if (existing != null && existing.isJsonObject && incoming.isJsonObject) {
                mergeInto(existing.asJsonObject, incoming)
            } else {
                target.add(key, incoming.deepCopy())
            }
        }
    }
}

private sealed interface PathPart {
    data class Field(val name: String) : PathPart
    data class Index(val index: Int) : PathPart
}

private data class NbtPath(val parts: List<PathPart>) {
    fun get(root: JsonElement): JsonElement? {
        var current: JsonElement = root
        parts.forEach { part ->
            current = when (part) {
                is PathPart.Field -> if (current.isJsonObject) current.asJsonObject.get(part.name) ?: return null else return null
                is PathPart.Index -> if (current.isJsonArray && part.index in 0 until current.asJsonArray.size()) {
                    current.asJsonArray[part.index]
                } else {
                    return null
                }
            }
        }
        return current
    }

    fun set(root: JsonObject, value: JsonElement) {
        if (parts.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Path cannot be empty for set")
        }
        var current: JsonElement = root
        parts.dropLast(1).forEachIndexed { index, part ->
            val next = parts[index + 1]
            current = when (part) {
                is PathPart.Field -> {
                    if (!current.isJsonObject) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Path segment '${part.name}' is not an object")
                    val objectCurrent = current.asJsonObject
                    val existing = objectCurrent.get(part.name)
                    if (existing == null || existing is JsonNull) {
                        val created = if (next is PathPart.Index) JsonArray() else JsonObject()
                        objectCurrent.add(part.name, created)
                        created
                    } else {
                        existing
                    }
                }
                is PathPart.Index -> {
                    if (!current.isJsonArray) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Path segment [${part.index}] is not an array")
                    val array = current.asJsonArray
                    while (array.size() <= part.index) array.add(JsonObject())
                    array[part.index]
                }
            }
        }

        when (val last = parts.last()) {
            is PathPart.Field -> {
                if (!current.isJsonObject) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Path target '${last.name}' is not an object")
                current.asJsonObject.add(last.name, value.deepCopy())
            }
            is PathPart.Index -> {
                if (!current.isJsonArray) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Path target [${last.index}] is not an array")
                val array = current.asJsonArray
                while (array.size() <= last.index) array.add(JsonNull.INSTANCE)
                array.set(last.index, value.deepCopy())
            }
        }
    }

    fun remove(root: JsonObject): Boolean {
        if (parts.isEmpty()) return false
        var current: JsonElement = root
        parts.dropLast(1).forEach { part ->
            current = when (part) {
                is PathPart.Field -> if (current.isJsonObject) current.asJsonObject.get(part.name) ?: return false else return false
                is PathPart.Index -> if (current.isJsonArray && part.index in 0 until current.asJsonArray.size()) {
                    current.asJsonArray[part.index]
                } else {
                    return false
                }
            }
        }
        return when (val last = parts.last()) {
            is PathPart.Field -> current.isJsonObject && current.asJsonObject.remove(last.name) != null
            is PathPart.Index -> current.isJsonArray && last.index in 0 until current.asJsonArray.size() && current.asJsonArray.remove(current.asJsonArray[last.index])
        }
    }

    companion object {
        fun parse(path: String): NbtPath {
            if (path.isBlank()) return NbtPath(emptyList())
            val parts = mutableListOf<PathPart>()
            var index = 0
            val name = StringBuilder()
            fun flushName() {
                if (name.isNotEmpty()) {
                    parts += PathPart.Field(name.toString())
                    name.clear()
                }
            }
            while (index < path.length) {
                when (val char = path[index]) {
                    '.' -> {
                        flushName()
                        index++
                    }
                    '[' -> {
                        flushName()
                        val end = path.indexOf(']', index)
                        if (end < 0) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Unclosed path index in '$path'")
                        val rawIndex = path.substring(index + 1, end)
                        val parsed = rawIndex.toIntOrNull()
                            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Only numeric path indexes are supported in '$path'")
                        parts += PathPart.Index(parsed)
                        index = end + 1
                    }
                    else -> {
                        name.append(char)
                        index++
                    }
                }
            }
            flushName()
            return NbtPath(parts)
        }
    }
}

private class SnbtParser(private val input: String) {
    private var index = 0

    fun parse(): JsonElement {
        skipWhitespace()
        val value = parseValue()
        skipWhitespace()
        if (index != input.length) error("Unexpected trailing content")
        return value
    }

    private fun parseValue(): JsonElement {
        skipWhitespace()
        if (index >= input.length) error("Unexpected end of SNBT")
        return when (input[index]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"', '\'' -> JsonPrimitive(parseQuoted())
            else -> parseBare()
        }
    }

    private fun parseObject(): JsonObject {
        expect('{')
        val objectValue = JsonObject()
        skipWhitespace()
        if (peek('}')) {
            index++
            return objectValue
        }
        while (true) {
            skipWhitespace()
            val key = if (peek('"') || peek('\'')) parseQuoted() else parseBareToken()
            skipWhitespace()
            expect(':')
            objectValue.add(key, parseValue())
            skipWhitespace()
            when {
                peek(',') -> index++
                peek('}') -> {
                    index++
                    return objectValue
                }
                else -> error("Expected ',' or '}'")
            }
        }
    }

    private fun parseArray(): JsonArray {
        expect('[')
        if (index + 1 < input.length && input[index + 1] == ';' && input[index].uppercaseChar() in setOf('B', 'I', 'L')) {
            index += 2
        }
        val array = JsonArray()
        skipWhitespace()
        if (peek(']')) {
            index++
            return array
        }
        while (true) {
            array.add(parseValue())
            skipWhitespace()
            when {
                peek(',') -> index++
                peek(']') -> {
                    index++
                    return array
                }
                else -> error("Expected ',' or ']'")
            }
        }
    }

    private fun parseBare(): JsonElement {
        val token = parseBareToken()
        return when {
            token.equals("true", ignoreCase = true) -> JsonPrimitive(true)
            token.equals("false", ignoreCase = true) -> JsonPrimitive(false)
            token.matches(Regex("[-+]?\\d+[bBsSlL]")) -> JsonPrimitive(token.dropLast(1).toLong())
            token.matches(Regex("[-+]?\\d+")) -> JsonPrimitive(token.toLong())
            token.matches(Regex("[-+]?(\\d+\\.\\d*|\\d*\\.\\d+)([fFdD])?")) -> JsonPrimitive(token.removeSuffix("f").removeSuffix("F").removeSuffix("d").removeSuffix("D").toDouble())
            token.matches(Regex("[-+]?\\d+[fFdD]")) -> JsonPrimitive(token.dropLast(1).toDouble())
            else -> JsonPrimitive(token)
        }
    }

    private fun parseBareToken(): String {
        skipWhitespace()
        val start = index
        while (index < input.length && !input[index].isWhitespace() && input[index] !in charArrayOf(',', ':', '}', ']', '[')) {
            index++
        }
        if (start == index) error("Expected token")
        return input.substring(start, index)
    }

    private fun parseQuoted(): String {
        val quote = input[index++]
        val builder = StringBuilder()
        var escaped = false
        while (index < input.length) {
            val char = input[index++]
            when {
                escaped -> {
                    builder.append(char)
                    escaped = false
                }
                char == '\\' -> escaped = true
                char == quote -> return builder.toString()
                else -> builder.append(char)
            }
        }
        error("Unterminated string")
    }

    private fun skipWhitespace() {
        while (index < input.length && input[index].isWhitespace()) index++
    }

    private fun expect(char: Char) {
        skipWhitespace()
        if (!peek(char)) error("Expected '$char'")
        index++
    }

    private fun peek(char: Char): Boolean = index < input.length && input[index] == char

    private fun error(message: String): Nothing {
        throw IllegalArgumentException("$message at offset $index in '$input'")
    }
}
