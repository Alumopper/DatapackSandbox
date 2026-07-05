package moe.afox.dpsandbox.core

/**
 * Structured predicate for matching high-level player event trace records.
 *
 * Null properties are wildcards. [count], when non-null, changes the assertion
 * from "at least one match" to "exactly this many matches".
 */
data class PlayerEventTraceExpectation(
    /** Player name that received the simulated input/event. */
    val player: String? = null,
    /** Event type, accepting either snake_case or kebab-case input. */
    val type: String? = null,
    /** Expected event dispatch success flag, or null to ignore it. */
    val success: Boolean? = null,
    /** Advancement id that must appear in the event's matched criteria. */
    val advancement: ResourceLocation? = null,
    /** Criterion name that must appear in the event's matched criteria. */
    val criterion: String? = null,
    /** Exact expected number of matching event traces, or null to require at least one. */
    val count: Int? = null,
) {
    private val normalizedType: String? = type?.replace('-', '_')

    /**
     * Returns whether one [event] satisfies this expectation.
     */
    fun matches(event: PlayerEventTraceEvent): Boolean =
        (player == null || event.playerName == player) &&
            (normalizedType == null || event.type == normalizedType) &&
            (success == null || event.success == success) &&
            (advancement == null || event.advancements.any { it.advancement == advancement }) &&
            (criterion == null || event.advancements.any { it.criterion == criterion })

    /**
     * Returns every player event trace that satisfies this expectation.
     */
    fun matching(events: List<PlayerEventTraceEvent>): List<PlayerEventTraceEvent> =
        events.filter(::matches)

    /**
     * Returns assertion failure messages for [events].
     *
     * An empty list means the expectation passed.
     */
    fun failures(events: List<PlayerEventTraceEvent>, label: String = "eventTrace"): List<String> {
        val matches = matching(events)
        if (count != null && matches.size != count) {
            return listOf("$label expected $count match(es) but found ${matches.size}: ${describe()}; ${actualEvents(events)}")
        }
        if (count == null && matches.isEmpty()) {
            return listOf("$label expected at least one match: ${describe()}; ${actualEvents(events)}")
        }
        return emptyList()
    }

    /**
     * Renders this expectation as a compact human-readable description.
     */
    fun describe(): String =
        listOfNotNull(
            player?.let { "player=$it" },
            type?.let { "type=$it" },
            success?.let { "success=$it" },
            advancement?.let { "advancement=$it" },
            criterion?.let { "criterion=$it" },
        ).ifEmpty { listOf("<any event trace>") }.joinToString(", ")

    private fun actualEvents(events: List<PlayerEventTraceEvent>): String {
        if (events.isEmpty()) return "actual event traces: <none>"
        val rendered = events.take(5).mapIndexed { index, event ->
            val status = if (event.success) "OK" else "ERR"
            val advancements = event.advancements
                .take(3)
                .joinToString(prefix = "[", postfix = "]") { "${it.advancement}/${it.criterion}:completed=${it.completed}" }
            "#${index + 1} [$status] player=${event.playerName} type=${event.type} advancements=$advancements"
        }
        val suffix = if (events.size > rendered.size) "; ... +${events.size - rendered.size} more" else ""
        return "actual event traces: ${rendered.joinToString("; ")}$suffix"
    }
}

/**
 * Stateless helpers for matching player event trace records.
 */
object PlayerEventTraceAssertions {
    /**
     * Returns every event trace in [events] matching [expectation].
     */
    fun matching(events: List<PlayerEventTraceEvent>, expectation: PlayerEventTraceExpectation): List<PlayerEventTraceEvent> =
        expectation.matching(events)

    /**
     * Returns human-readable failures for [expectation].
     *
     * An empty list means the expectation passed.
     */
    fun failures(events: List<PlayerEventTraceEvent>, expectation: PlayerEventTraceExpectation, label: String = "eventTrace"): List<String> =
        expectation.failures(events, label)
}
