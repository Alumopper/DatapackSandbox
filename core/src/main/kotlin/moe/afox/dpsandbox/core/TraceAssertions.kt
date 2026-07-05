package moe.afox.dpsandbox.core

/**
 * Structured predicate for matching sandbox command trace events.
 *
 * Null fields are wildcards. [count], when non-null, changes the assertion
 * from "at least one match" to "exactly this many matches".
 */
data class TraceExpectation(
    /** Exact command text to match. */
    val command: String? = null,
    /** Root command token, for example `scoreboard` or `give`. */
    val root: String? = null,
    /** Substring that must appear in the command text. */
    val contains: String? = null,
    /** Expected success flag, or null to ignore it. */
    val success: Boolean? = null,
    /** Source file substring to match. */
    val fileContains: String? = null,
    /** Function id that must appear in the source function stack. */
    val function: String? = null,
    /** Exact expected number of matching trace events, or null to require at least one. */
    val count: Int? = null,
) {
    /**
     * Returns whether one [event] satisfies this expectation.
     */
    fun matches(event: CommandTraceEvent): Boolean =
        (command == null || event.command == command) &&
            (root == null || event.root == root) &&
            (contains == null || contains in event.command) &&
            (success == null || event.success == success) &&
            (fileContains == null || event.source?.file?.contains(fileContains) == true) &&
            (function == null || event.source?.functionStack?.any { it.id.toString() == function } == true)

    /**
     * Returns every trace event that satisfies this expectation.
     */
    fun matching(traces: List<CommandTraceEvent>): List<CommandTraceEvent> =
        traces.filter(::matches)

    /**
     * Returns assertion failure messages for [traces].
     *
     * An empty list means the expectation passed.
     */
    fun failures(traces: List<CommandTraceEvent>, label: String = "trace"): List<String> {
        val matches = matching(traces)
        if (count != null && matches.size != count) {
            return listOf("$label expected $count match(es) but found ${matches.size}: ${describe()}")
        }
        if (count == null && matches.isEmpty()) {
            return listOf("$label expected at least one match: ${describe()}")
        }
        return emptyList()
    }

    /**
     * Renders this expectation as a compact human-readable description.
     */
    fun describe(): String =
        listOfNotNull(
            command?.let { "command=$it" },
            root?.let { "root=$it" },
            contains?.let { "contains=$it" },
            success?.let { "success=$it" },
            fileContains?.let { "fileContains=$it" },
            function?.let { "function=$it" },
        ).ifEmpty { listOf("<any trace>") }.joinToString(", ")
}

/**
 * Stateless helpers for matching command trace events.
 */
object TraceAssertions {
    /**
     * Returns every event in [traces] matching [expectation].
     */
    fun matching(traces: List<CommandTraceEvent>, expectation: TraceExpectation): List<CommandTraceEvent> =
        expectation.matching(traces)

    /**
     * Returns human-readable failures for [expectation].
     *
     * An empty list means the expectation passed.
     */
    fun failures(traces: List<CommandTraceEvent>, expectation: TraceExpectation, label: String = "trace"): List<String> =
        expectation.failures(traces, label)
}
