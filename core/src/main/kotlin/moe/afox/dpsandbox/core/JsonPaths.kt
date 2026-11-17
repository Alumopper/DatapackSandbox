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
        mergeValues(root, path, listOf(value))
    }

    internal fun mergeValues(root: JsonObject, path: String?, values: List<JsonElement>): Int {
        val merged = JsonObject()
        values.forEach {
            if (!it.isJsonObject) throw CommandFailure("Data merge source must be a compound")
            mergeInto(merged, it)
        }
        val destinations = NbtPath.parse(path.orEmpty()).getOrCreate(root, ::JsonObject)
        if (destinations.any { !it.isJsonObject }) throw CommandFailure("Data merge target must be a compound")
        return destinations.count {
            val before = it.deepCopy()
            mergeInto(it.asJsonObject, merged)
            it != before
        }
    }

    internal fun setCount(root: JsonObject, path: String, value: JsonElement): Int = NbtPath.parse(path).set(root, value)

    fun append(root: JsonObject, path: String, value: JsonElement, location: SourceLocation? = null) {
        insertValues(root, path, -1, listOf(value), location)
    }

    fun prepend(root: JsonObject, path: String, value: JsonElement, location: SourceLocation? = null) {
        insertValues(root, path, 0, listOf(value), location)
    }

    fun insert(root: JsonObject, path: String, index: Int, value: JsonElement, location: SourceLocation? = null) {
        insertValues(root, path, index, listOf(value), location)
    }

    internal fun insertValues(root: JsonObject, path: String, index: Int, values: List<JsonElement>, location: SourceLocation?): Int {
        val lists =
            NbtPath.parse(path).getOrCreate(root, ::JsonArray).map {
                if (!it.isJsonArray) throw CommandFailure("Data path '$path' is not a list", location)
                it.asJsonArray
            }
        for (list in lists) {
            val offset = if (index < 0) list.size() + index + 1 else index
            if (offset !in 0..list.size()) throw CommandFailure("Invalid list insertion index $index", location)
            list.asList().addAll(offset, values.map { it.deepCopy() })
        }
        return lists.size
    }

    fun remove(
        root: JsonObject,
        path: String,
    ): Boolean = removeCount(root, path) > 0

    internal fun removeCount(root: JsonObject, path: String): Int = NbtPath.parse(path).remove(root)

    fun exists(
        root: JsonObject,
        path: String,
    ): Boolean = get(root, path) != null && get(root, path) !is JsonNull

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
        val expected: JsonObject? = null,
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
    val rootFilter: JsonObject? = null,
) {
    fun get(root: JsonElement): JsonElement? = getAll(root).firstOrNull()

    fun getAll(root: JsonElement): List<JsonElement> = walk(root, parts.size, null)

    fun getOrCreate(root: JsonElement, factory: () -> JsonElement): List<JsonElement> = walk(root, parts.size, factory)

    private fun walk(root: JsonElement, count: Int, factory: (() -> JsonElement)?): List<JsonElement> {
        if (rootFilter != null && !matchesNbt(root, rootFilter)) return emptyList()
        var current = listOf(root)
        for (index in 0 until count) {
            val part = parts[index]
            val create: (() -> JsonElement)? =
                if (factory == null) {
                    null
                } else if (index + 1 == count) {
                    factory
                } else {
                    ({ pathContainer(parts[index + 1]) })
                }
            current = current.flatMap { children(it, part, create) }
            if (create != null && current.isEmpty()) throw CommandFailure("No element matches path segment $part")
        }
        return current
    }

    private fun children(parent: JsonElement, part: PathPart, create: (() -> JsonElement)?): List<JsonElement> {
        if (part is PathPart.Field) {
            if (!parent.isJsonObject) return emptyList()
            val obj = parent.asJsonObject
            val value =
                obj.get(part.name) ?: if (create != null) {
                    (part.expected?.deepCopy() ?: create()).also { obj.add(part.name, it) }
                } else {
                    return emptyList()
                }
            return listOfNotNull(value.takeIf { part.matches(it) })
        }
        if (!parent.isJsonArray) return emptyList()
        val list = parent.asJsonArray
        return when (part) {
            is PathPart.Index -> listOfNotNull(existingIndex(part.index, list.size())?.let { list[it] })
            is PathPart.Match -> {
                val matches = list.filter { matchesNbt(it, part.expected) }
                if (matches.isEmpty() && create != null) listOf(part.expected.deepCopy().also(list::add)) else matches
            }
            PathPart.All -> if (list.isEmpty && create != null) listOf(create().also(list::add)) else list.toList()
            is PathPart.Field -> error("Field handled above")
        }
    }

    fun set(root: JsonObject, value: JsonElement): Int {
        val last = parts.lastOrNull() ?: return 0
        val parents = walk(root, parts.lastIndex) { pathContainer(last) }
        return parents.sumOf { parent ->
            if (last is PathPart.Field) {
                if (!parent.isJsonObject) return@sumOf 0
                val old = parent.asJsonObject.get(last.name)
                if (old == value || (old != null && !last.matches(old))) return@sumOf 0
                parent.asJsonObject.add(last.name, value.deepCopy())
                return@sumOf 1
            }
            if (!parent.isJsonArray) return@sumOf 0
            val list = parent.asJsonArray
            val indices =
                when (last) {
                    is PathPart.Index -> listOfNotNull(existingIndex(last.index, list.size()))
                    is PathPart.Match -> (0 until list.size()).filter { matchesNbt(list[it], last.expected) }
                    else -> (0 until list.size()).toList()
                }
            if (indices.isEmpty() && last !is PathPart.Index) {
                list.add(value.deepCopy())
                1
            } else {
                indices.count { i ->
                    (list[i] != value).also { changed -> if (changed) list.set(i, value.deepCopy()) }
                }
            }
        }
    }

    private fun pathContainer(next: PathPart): JsonElement =
        if (next is PathPart.Index || next is PathPart.Match || next is PathPart.All) JsonArray() else JsonObject()

    fun remove(root: JsonObject): Int {
        val last = parts.lastOrNull() ?: return 0
        return walk(root, parts.lastIndex, null).sumOf { parent ->
            when (last) {
                is PathPart.Field -> {
                    if (!parent.isJsonObject) return@sumOf 0
                    val value = parent.asJsonObject.get(last.name) ?: return@sumOf 0
                    if (!last.matches(value)) return@sumOf 0
                    parent.asJsonObject.remove(last.name)
                    1
                }
                is PathPart.Index -> {
                    if (!parent.isJsonArray) return@sumOf 0
                    val index = existingIndex(last.index, parent.asJsonArray.size()) ?: return@sumOf 0
                    parent.asJsonArray.remove(index)
                    1
                }
                is PathPart.Match, PathPart.All -> {
                    if (!parent.isJsonArray) return@sumOf 0
                    val list = parent.asJsonArray.asList()
                    val size = list.size
                    if (last is PathPart.Match) list.removeIf { matchesNbt(it, last.expected) } else list.clear()
                    size - list.size
                }
            }
        }
    }

    private fun PathPart.Field.matches(value: JsonElement): Boolean = expected == null || matchesNbt(value, expected)

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
            var rootFilter: JsonObject? = null
            if (path.startsWith("{")) {
                val end = findClosingCompound(path, 0)
                if (end < 0) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Unclosed root matcher in '$path'")
                rootFilter = JsonValues.parse(path.substring(0, end + 1)).asJsonObject
                index = end + 1
            }
            val name = StringBuilder()

            fun flushName() {
                if (name.isNotEmpty()) {
                    parts += PathPart.Field(name.toString())
                    name.clear()
                }
            }
            while (index < path.length) {
                when (val char = path[index]) {
                    '"', '\'' -> {
                        val quote = char
                        index++
                        var closed = false
                        while (index < path.length) {
                            val next = path[index++]
                            if (next == quote) {
                                closed = true
                                break
                            }
                            if (next == '\\' && index < path.length) {
                                name.append(path[index++])
                            } else {
                                name.append(next)
                            }
                        }
                        if (!closed) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Unclosed quoted path field in '$path'")
                    }
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
                    '{' -> {
                        flushName()
                        val field =
                            parts.lastOrNull() as? PathPart.Field
                                ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Expected a named field before compound matcher in '$path'")
                        val end = findClosingCompound(path, index)
                        if (end < 0) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Unclosed compound matcher in '$path'")
                        parts[parts.lastIndex] = field.copy(expected = JsonValues.parse(path.substring(index, end + 1)).asJsonObject)
                        index = end + 1
                    }
                    else -> {
                        name.append(char)
                        index++
                    }
                }
            }
            flushName()
            return NbtPath(parts, rootFilter)
        }

        private fun findClosingCompound(path: String, start: Int): Int {
            var depth = 0
            var quote: Char? = null
            var escaped = false
            for (index in start until path.length) {
                val char = path[index]
                if (quote != null) {
                    when {
                        escaped -> escaped = false
                        char == '\\' -> escaped = true
                        char == quote -> quote = null
                    }
                } else {
                    when (char) {
                        '"', '\'' -> quote = char
                        '{' -> depth++
                        '}' -> if (--depth == 0) return index
                    }
                }
            }
            return -1
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
                Regex("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?[fFdD]?"),
            ) ->
                JsonPrimitive(
                    token
                        .removeSuffix("f")
                        .removeSuffix("F")
                        .removeSuffix("d")
                        .removeSuffix("D")
                        .toDouble(),
                )
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
