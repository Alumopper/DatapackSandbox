package moe.afox.dpsandbox.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

object JsonValues {
    /** Kept for JVM API compatibility; runtime rendering uses the tree writer below. */
    val prettyGson
        get() = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun parse(
        raw: String,
        location: SourceLocation? = null,
    ): JsonElement =
        try {
            SnbtParser(raw).parse()
        } catch (error: Exception) {
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

    fun render(element: JsonElement): String = buildString { appendJson(element, 0, pretty = true) }

    internal fun renderCompact(element: JsonElement): String = buildString { appendJson(element, 0, pretty = false) }

    private fun StringBuilder.appendJson(
        element: JsonElement,
        depth: Int,
        pretty: Boolean,
    ) {
        when {
            element.isJsonObject -> {
                val entries = element.asJsonObject.entrySet().toList()
                append('{')
                if (entries.isNotEmpty()) {
                    entries.forEachIndexed { index, (key, value) ->
                        if (pretty) {
                            append('\n')
                            append("  ".repeat(depth + 1))
                        }
                        appendQuoted(key)
                        append(if (pretty) ": " else ":")
                        appendJson(value, depth + 1, pretty)
                        if (index < entries.lastIndex) append(',')
                    }
                    if (pretty) {
                        append('\n')
                        append("  ".repeat(depth))
                    }
                }
                append('}')
            }
            element.isJsonArray -> {
                val values = element.asJsonArray.toList()
                append('[')
                if (values.isNotEmpty()) {
                    values.forEachIndexed { index, value ->
                        if (pretty) {
                            append('\n')
                            append("  ".repeat(depth + 1))
                        }
                        appendJson(value, depth + 1, pretty)
                        if (index < values.lastIndex) append(',')
                    }
                    if (pretty) {
                        append('\n')
                        append("  ".repeat(depth))
                    }
                }
                append(']')
            }
            element.isJsonNull -> append("null")
            element.asJsonPrimitive.isString -> appendQuoted(element.asString)
            else -> append(element.asString)
        }
    }

    private fun StringBuilder.appendQuoted(value: String) {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '<', '>', '&', '=', '\'', '/' -> append(char)
                else ->
                    if (char.code < 0x20) {
                        append("\\u")
                        append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        append(char)
                    }
            }
        }
        append('"')
    }
}

object JsonPaths {
    fun get(
        root: JsonObject,
        path: String?,
    ): JsonElement? {
        if (path.isNullOrBlank() || path == "{}") return root
        return NbtPath.parse(path).get(root)
    }

    internal fun getAll(
        root: JsonObject,
        path: String?,
    ): List<JsonElement> {
        if (path.isNullOrBlank() || path == "{}") return listOf(root)
        return NbtPath.parse(path).getAll(root)
    }

    fun set(
        root: JsonObject,
        path: String,
        value: JsonElement,
    ) {
        NbtPath.parse(path).set(root, value)
    }

    fun merge(
        root: JsonObject,
        path: String?,
        value: JsonElement,
    ) {
        val current = get(root, path)
        if (path.isNullOrBlank()) {
            mergeInto(root, value)
        } else if (current != null && current.isJsonObject) {
            mergeInto(current.asJsonObject, value)
        } else {
            set(root, path, value)
        }
    }

    fun append(
        root: JsonObject,
        path: String,
        value: JsonElement,
        location: SourceLocation? = null,
    ) {
        val array = requireArray(root, path, location)
        array.add(value)
    }

    fun prepend(
        root: JsonObject,
        path: String,
        value: JsonElement,
        location: SourceLocation? = null,
    ) {
        val array = requireArray(root, path, location)
        val replacement = JsonArray()
        replacement.add(value.deepCopy())
        array.forEach { replacement.add(it.deepCopy()) }
        set(root, path, replacement)
    }

    fun insert(
        root: JsonObject,
        path: String,
        index: Int,
        value: JsonElement,
        location: SourceLocation? = null,
    ) {
        val array = requireArray(root, path, location)
        val targetIndex = index.coerceIn(0, array.size())
        val replacement = JsonArray()
        array.forEachIndexed { currentIndex, element ->
            if (currentIndex == targetIndex) replacement.add(value.deepCopy())
            replacement.add(element.deepCopy())
        }
        if (targetIndex == array.size()) replacement.add(value.deepCopy())
        set(root, path, replacement)
    }

    fun remove(
        root: JsonObject,
        path: String,
    ): Boolean = NbtPath.parse(path).remove(root)

    fun exists(
        root: JsonObject,
        path: String,
    ): Boolean = get(root, path) != null && get(root, path) !is JsonNull

    private fun requireArray(
        root: JsonObject,
        path: String,
        location: SourceLocation?,
    ): JsonArray {
        val value = get(root, path)
        if (value != null && value.isJsonArray) return value.asJsonArray
        if (value != null && value !is JsonNull) {
            throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Data path '$path' is not a list", location)
        }
        val array = JsonArray()
        set(root, path, array)
        return get(root, path)?.asJsonArray
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Failed to create array at path '$path'", location)
    }

    private fun mergeInto(
        target: JsonObject,
        value: JsonElement,
    ) {
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
    data class Field(
        val name: String,
    ) : PathPart

    data class Index(
        val index: Int,
    ) : PathPart

    data class Match(
        val expected: JsonObject,
    ) : PathPart

    data object All : PathPart
}

private data class NbtPath(
    val parts: List<PathPart>,
) {
    fun get(root: JsonElement): JsonElement? = getAll(root).firstOrNull()

    fun getAll(root: JsonElement): List<JsonElement> =
        parts.fold(listOf(root)) { current, part ->
            current.flatMap { element ->
                when (part) {
                    is PathPart.Field ->
                        if (element.isJsonObject) listOfNotNull(element.asJsonObject.get(part.name)) else emptyList()
                    is PathPart.Index -> {
                        if (!element.isJsonArray) {
                            emptyList()
                        } else {
                            val array = element.asJsonArray
                            existingIndex(part.index, array.size())?.let { listOf(array[it]) } ?: emptyList()
                        }
                    }
                    is PathPart.Match ->
                        if (element.isJsonArray) {
                            element.asJsonArray.filter { it.isJsonObject && objectMatches(it.asJsonObject, part.expected) }
                        } else {
                            emptyList()
                        }
                    PathPart.All -> if (element.isJsonArray) element.asJsonArray.toList() else emptyList()
                }
            }
        }

    fun set(
        root: JsonObject,
        value: JsonElement,
    ) {
        if (parts.isEmpty()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Path cannot be empty for set")
        }
        setAt(root, 0, value)
    }

    private fun setAt(
        current: JsonElement,
        partIndex: Int,
        value: JsonElement,
    ) {
        val part = parts[partIndex]
        val last = partIndex == parts.lastIndex
        if (last) {
            when (part) {
                is PathPart.Field -> {
                    if (!current.isJsonObject) {
                        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Path target '${part.name}' is not an object")
                    }
                    current.asJsonObject.add(part.name, value.deepCopy())
                }
                is PathPart.Index -> {
                    if (!current.isJsonArray) {
                        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Path target [${part.index}] is not an array")
                    }
                    val array = current.asJsonArray
                    val targetIndex =
                        if (part.index < 0) {
                            existingIndex(part.index, array.size())
                                ?: throw SandboxException(
                                    DiagnosticCode.COMMAND_ERROR,
                                    "Path index [${part.index}] did not match any array entry",
                                )
                        } else {
                            while (array.size() <= part.index) array.add(JsonNull.INSTANCE)
                            part.index
                        }
                    array.set(targetIndex, value.deepCopy())
                }
                is PathPart.Match -> {
                    if (!current.isJsonArray) {
                        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Path object matcher target is not an array")
                    }
                    val array = current.asJsonArray
                    val matches =
                        (0 until array.size()).filter { index ->
                            array[index].isJsonObject && objectMatches(array[index].asJsonObject, part.expected)
                        }
                    if (matches.isEmpty()) {
                        throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Path object matcher did not match any array entry")
                    }
                    matches.forEach { array.set(it, value.deepCopy()) }
                }
                PathPart.All -> {
                    if (!current.isJsonArray) {
                        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Path list matcher target is not an array")
                    }
                    val array = current.asJsonArray
                    (0 until array.size()).forEach { array.set(it, value.deepCopy()) }
                }
            }
            return
        }

        val next = parts[partIndex + 1]
        when (part) {
            is PathPart.Field -> {
                if (!current.isJsonObject) {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Path segment '${part.name}' is not an object")
                }
                val objectCurrent = current.asJsonObject
                val child =
                    objectCurrent.get(part.name)?.takeUnless { it is JsonNull }
                        ?: pathContainer(next).also { objectCurrent.add(part.name, it) }
                setAt(child, partIndex + 1, value)
            }
            is PathPart.Index -> {
                if (!current.isJsonArray) {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Path segment [${part.index}] is not an array")
                }
                val array = current.asJsonArray
                val targetIndex =
                    if (part.index < 0) {
                        existingIndex(part.index, array.size())
                            ?: throw SandboxException(
                                DiagnosticCode.COMMAND_ERROR,
                                "Path index [${part.index}] did not match any array entry",
                            )
                    } else {
                        while (array.size() <= part.index) array.add(pathContainer(next))
                        part.index
                    }
                setAt(array[targetIndex], partIndex + 1, value)
            }
            is PathPart.Match -> {
                if (!current.isJsonArray) {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Path object matcher is not applied to an array")
                }
                val matches = current.asJsonArray.filter { it.isJsonObject && objectMatches(it.asJsonObject, part.expected) }
                if (matches.isEmpty()) {
                    throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Path object matcher did not match any array entry")
                }
                matches.forEach { setAt(it, partIndex + 1, value) }
            }
            PathPart.All -> {
                if (!current.isJsonArray) {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Path list matcher is not applied to an array")
                }
                current.asJsonArray.forEach { setAt(it, partIndex + 1, value) }
            }
        }
    }

    private fun pathContainer(next: PathPart): JsonElement =
        if (next is PathPart.Index || next is PathPart.Match || next is PathPart.All) JsonArray() else JsonObject()

    fun remove(root: JsonObject): Boolean {
        if (parts.isEmpty()) return false
        return removeAt(root, 0)
    }

    private fun removeAt(
        current: JsonElement,
        partIndex: Int,
    ): Boolean {
        val part = parts[partIndex]
        if (partIndex == parts.lastIndex) {
            return when (part) {
                is PathPart.Field -> current.isJsonObject && current.asJsonObject.remove(part.name) != null
                is PathPart.Index -> {
                    if (!current.isJsonArray) return false
                    val array = current.asJsonArray
                    val targetIndex = existingIndex(part.index, array.size()) ?: return false
                    array.remove(array[targetIndex])
                }
                is PathPart.Match -> {
                    if (!current.isJsonArray) return false
                    val array = current.asJsonArray
                    val matches = array.filter { it.isJsonObject && objectMatches(it.asJsonObject, part.expected) }
                    matches.forEach { array.remove(it) }
                    matches.isNotEmpty()
                }
                PathPart.All -> {
                    if (!current.isJsonArray) return false
                    val array = current.asJsonArray
                    val changed = array.size() > 0
                    while (array.size() > 0) array.remove(0)
                    changed
                }
            }
        }
        val children =
            when (part) {
                is PathPart.Field ->
                    if (current.isJsonObject) listOfNotNull(current.asJsonObject.get(part.name)) else emptyList()
                is PathPart.Index -> {
                    if (!current.isJsonArray) {
                        emptyList()
                    } else {
                        val array = current.asJsonArray
                        existingIndex(part.index, array.size())?.let { listOf(array[it]) } ?: emptyList()
                    }
                }
                is PathPart.Match ->
                    if (current.isJsonArray) {
                        current.asJsonArray.filter { it.isJsonObject && objectMatches(it.asJsonObject, part.expected) }
                    } else {
                        emptyList()
                    }
                PathPart.All -> if (current.isJsonArray) current.asJsonArray.toList() else emptyList()
            }
        return children.fold(false) { changed, child -> removeAt(child, partIndex + 1) || changed }
    }

    private fun objectMatches(
        actual: JsonObject,
        expected: JsonObject,
    ): Boolean =
        expected.entrySet().all { (key, expectedValue) ->
            val actualValue = actual.get(key) ?: return@all false
            elementMatches(actualValue, expectedValue)
        }

    private fun elementMatches(
        actual: JsonElement,
        expected: JsonElement,
    ): Boolean =
        when {
            expected.isJsonObject -> actual.isJsonObject && objectMatches(actual.asJsonObject, expected.asJsonObject)
            expected.isJsonArray ->
                actual.isJsonArray &&
                    actual.asJsonArray.size() == expected.asJsonArray.size() &&
                    actual.asJsonArray.zip(expected.asJsonArray).all { (actualElement, expectedElement) ->
                        elementMatches(actualElement, expectedElement)
                    }
            expected.isJsonPrimitive && actual.isJsonPrimitive -> primitiveMatches(actual.asJsonPrimitive, expected.asJsonPrimitive)
            else -> actual == expected
        }

    private fun primitiveMatches(
        actual: JsonPrimitive,
        expected: JsonPrimitive,
    ): Boolean =
        when {
            actual.isNumber && expected.isNumber -> actual.asDouble == expected.asDouble
            actual.isBoolean && expected.isBoolean -> actual.asBoolean == expected.asBoolean
            else -> actual.asString == expected.asString
        }

    private fun existingIndex(
        index: Int,
        size: Int,
    ): Int? {
        val normalized = if (index < 0) size + index else index
        return normalized.takeIf { it in 0 until size }
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
                        val end = findClosingBracket(path, index)
                        if (end < 0) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Unclosed path index in '$path'")
                        val rawIndex = path.substring(index + 1, end)
                        parts +=
                            if (rawIndex.isBlank()) {
                                PathPart.All
                            } else if (rawIndex.trimStart().startsWith("{")) {
                                val matcher = JsonValues.parse(rawIndex)
                                if (!matcher.isJsonObject) {
                                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Path object matcher must be an object in '$path'")
                                }
                                PathPart.Match(matcher.asJsonObject)
                            } else {
                                val parsed =
                                    rawIndex.toIntOrNull()
                                        ?: throw SandboxException(
                                            DiagnosticCode.INPUT_FORMAT,
                                            "Only numeric path indexes and object matchers are supported in '$path'",
                                        )
                                PathPart.Index(parsed)
                            }
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

        private fun findClosingBracket(
            path: String,
            start: Int,
        ): Int {
            var index = start + 1
            var objectDepth = 0
            var arrayDepth = 0
            var quote: Char? = null
            var escaped = false
            while (index < path.length) {
                val char = path[index]
                if (quote != null) {
                    when {
                        escaped -> escaped = false
                        char == '\\' -> escaped = true
                        char == quote -> quote = null
                    }
                    index++
                    continue
                }
                when (char) {
                    '"', '\'' -> quote = char
                    '{' -> objectDepth++
                    '}' -> objectDepth--
                    '[' -> arrayDepth++
                    ']' -> {
                        if (objectDepth == 0 && arrayDepth == 0) return index
                        arrayDepth--
                    }
                }
                index++
            }
            return -1
        }
    }
}

private class SnbtParser(
    private val input: String,
) {
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
                peek(',') -> {
                    index++
                    skipWhitespace()
                    if (peek('}')) {
                        index++
                        return objectValue
                    }
                }
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
                peek(',') -> {
                    index++
                    skipWhitespace()
                    if (peek(']')) {
                        index++
                        return array
                    }
                }
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
            token.matches(
                Regex("[-+]?(\\d+\\.\\d*|\\d*\\.\\d+)([fFdD])?"),
            ) ->
                JsonPrimitive(
                    token
                        .removeSuffix("f")
                        .removeSuffix("F")
                        .removeSuffix("d")
                        .removeSuffix("D")
                        .toDouble(),
                )
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

    private fun error(message: String): Nothing = throw IllegalArgumentException("$message at offset $index in '$input'")
}
