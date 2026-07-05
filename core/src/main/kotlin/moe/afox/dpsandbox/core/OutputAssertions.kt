package moe.afox.dpsandbox.core

import com.google.gson.JsonElement

/**
 * Predicate for matching one resolved text segment inside an [OutputEvent].
 *
 * Null properties are wildcards. Text matching can be exact through [text] or
 * substring-based through [contains].
 */
data class OutputSegmentExpectation(
    /** Exact segment text to match. */
    val text: String? = null,
    /** Substring that must appear in the segment text. */
    val contains: String? = null,
    /** Exact segment text after whitespace is collapsed and trimmed. */
    val normalizedText: String? = null,
    /** Substring that must appear after both values have whitespace collapsed and trimmed. */
    val normalizedContains: String? = null,
    /** Minecraft text color name, for example `yellow` or `dark_green`. */
    val color: String? = null,
    /** Expected bold flag, or `null` to ignore it. */
    val bold: Boolean? = null,
    /** Expected italic flag, or `null` to ignore it. */
    val italic: Boolean? = null,
    /** Expected underline flag, or `null` to ignore it. */
    val underlined: Boolean? = null,
    /** Expected strikethrough flag, or `null` to ignore it. */
    val strikethrough: Boolean? = null,
    /** Expected obfuscated flag, or `null` to ignore it. */
    val obfuscated: Boolean? = null,
) {
    /**
     * Returns whether [segment] satisfies all non-null expectation fields.
     */
    fun matches(segment: OutputTextSegment): Boolean =
        (text == null || segment.text == text) &&
            (contains == null || contains in segment.text) &&
            (normalizedText == null || normalizeOutputText(segment.text) == normalizeOutputText(normalizedText)) &&
            (normalizedContains == null || normalizeOutputText(normalizedContains) in normalizeOutputText(segment.text)) &&
            (color == null || segment.color == color) &&
            (bold == null || segment.bold == bold) &&
            (italic == null || segment.italic == italic) &&
            (underlined == null || segment.underlined == underlined) &&
            (strikethrough == null || segment.strikethrough == strikethrough) &&
            (obfuscated == null || segment.obfuscated == obfuscated)
}

/**
 * Structured predicate for matching sandbox output events.
 *
 * Output events are produced by observable commands (`tellraw`, `title`,
 * `say`, `playsound`, `particle`) and by sandbox warnings. Null fields are
 * wildcards. [count], when non-null, changes the assertion from "at least one
 * match" to "exactly this many matches".
 */
data class OutputExpectation(
    /** Command name that produced the event, for example `tellraw`. */
    val command: String? = null,
    /** Logical output channel such as `chat`, `title`, `sound`, `visual`, or `warning`. */
    val channel: String? = null,
    /** Single target that must be present in the event target set. */
    val target: String? = null,
    /** Every target in this set must be present in the event target set. */
    val targets: Set<String> = emptySet(),
    /** Exact plain text to match. */
    val text: String? = null,
    /** Substring that must appear in the event plain text. */
    val contains: String? = null,
    /** Exact plain text after whitespace is collapsed and trimmed. */
    val normalizedText: String? = null,
    /** Substring that must appear after both values have whitespace collapsed and trimmed. */
    val normalizedContains: String? = null,
    /** Optional JSON path inside the event payload. */
    val payloadPath: String? = null,
    /** Expected JSON value at [payloadPath], or any value when null and [payloadPath] is set. */
    val payloadEquals: JsonElement? = null,
    /** Optional resolved text segment expectation. */
    val segment: OutputSegmentExpectation? = null,
    /** Exact expected number of matching events, or null to require at least one. */
    val count: Int? = null,
    /** One-based global output event position to match, or null to search all outputs. */
    val order: Int? = null,
) {
    /**
     * Returns whether one [event] satisfies this expectation.
     */
    fun matches(event: OutputEvent): Boolean =
        (command == null || event.command == command) &&
            (channel == null || event.channel == channel) &&
            (target == null || target in event.targets) &&
            targets.all { it in event.targets } &&
            (text == null || event.text == text) &&
            (contains == null || contains in event.text) &&
            (normalizedText == null || normalizeOutputText(event.text) == normalizeOutputText(normalizedText)) &&
            (normalizedContains == null || normalizeOutputText(normalizedContains) in normalizeOutputText(event.text)) &&
            payloadMatches(event) &&
            (segment == null || event.segments.any(segment::matches))

    /**
     * Returns every output event that satisfies this expectation.
     */
    fun matching(outputs: List<OutputEvent>): List<OutputEvent> =
        order?.let { ordinal ->
            outputs.getOrNull(ordinal - 1)?.takeIf(::matches)?.let(::listOf) ?: emptyList()
        } ?: outputs.filter(::matches)

    /**
     * Returns assertion failure messages for [outputs].
     *
     * An empty list means the expectation passed.
     */
    fun failures(outputs: List<OutputEvent>, label: String = "output"): List<String> {
        val matches = matching(outputs)
        if (count != null && matches.size != count) {
            return listOf("$label expected $count match(es) but found ${matches.size}: ${describe()}; ${actualOutputs(outputs)}")
        }
        if (count == null && matches.isEmpty()) {
            return listOf("$label expected at least one match: ${describe()}; ${actualOutputs(outputs)}")
        }
        return emptyList()
    }

    /**
     * Renders this expectation as a compact human-readable description.
     */
    fun describe(): String =
        listOfNotNull(
            command?.let { "command=$it" },
            channel?.let { "channel=$it" },
            target?.let { "target=$it" },
            targets.takeIf { it.isNotEmpty() }?.let { "targets=${it.sorted()}" },
            text?.let { "text=${quote(it)}" },
            contains?.let { "contains=${quote(it)}" },
            normalizedText?.let { "normalizedText=${quote(it)}" },
            normalizedContains?.let { "normalizedContains=${quote(it)}" },
            payloadPath?.let { "payload.$it=${payloadEquals?.let(JsonValues::render) ?: "<exists>"}" },
            segment?.let { "segment=$it" },
            order?.let { "order=$it" },
        ).ifEmpty { listOf("<any output>") }.joinToString(", ")

    private fun payloadMatches(event: OutputEvent): Boolean {
        if (payloadPath == null && payloadEquals == null) return true
        val payload = event.payload
        if (payload == null || !payload.isJsonObject) return false
        val actual = JsonPaths.get(payload.asJsonObject, payloadPath)
        return payloadEquals == null || actual == payloadEquals
    }

    private fun quote(value: String): String = "'$value'"

    private fun actualOutputs(outputs: List<OutputEvent>): String {
        if (outputs.isEmpty()) return "actual outputs: <none>"
        val rendered = outputs.take(5).mapIndexed { index, output ->
            "#${index + 1} command=${output.command} channel=${output.channel} targets=${output.targets.sorted()} text=${quote(output.text.truncateForAssertion())}"
        }
        val suffix = if (outputs.size > rendered.size) "; ... +${outputs.size - rendered.size} more" else ""
        return "actual outputs: ${rendered.joinToString("; ")}$suffix"
    }

    private fun String.truncateForAssertion(): String =
        replace("\r", "\\r")
            .replace("\n", "\\n")
            .let { if (it.length <= 160) it else it.take(157) + "..." }
}

private val OutputWhitespace = Regex("\\s+")

internal fun normalizeOutputText(value: String): String =
    value.trim().replace(OutputWhitespace, " ")

/**
 * Stateless helpers for matching output events.
 */
object OutputAssertions {
    /**
     * Returns every event in [outputs] matching [expectation].
     */
    fun matching(outputs: List<OutputEvent>, expectation: OutputExpectation): List<OutputEvent> =
        expectation.matching(outputs)

    /**
     * Returns human-readable failures for [expectation].
     *
     * An empty list means the expectation passed.
     */
    fun failures(outputs: List<OutputEvent>, expectation: OutputExpectation, label: String = "output"): List<String> =
        expectation.failures(outputs, label)
}
