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
    /** Advancement id that must appear in the event's failed advancement criteria. */
    val failedAdvancement: ResourceLocation? = null,
    /** Criterion name that must appear in the event's failed advancement criteria. */
    val failedCriterion: String? = null,
    /** Substring that must appear in a failed advancement criterion reason. */
    val failureContains: String? = null,
    /** Exact expected number of matching event traces, or null to require at least one. */
    val count: Int? = null,
    /** Item id recorded on the event trace. */
    val item: ResourceLocation? = null,
    /** Entity type recorded on the event trace. */
    val entity: ResourceLocation? = null,
    /** Block id recorded on the event trace. */
    val block: ResourceLocation? = null,
    /** Exact block x coordinate recorded on a block event trace. */
    val blockX: Int? = null,
    /** Exact block y coordinate recorded on a block event trace. */
    val blockY: Int? = null,
    /** Exact block z coordinate recorded on a block event trace. */
    val blockZ: Int? = null,
    /** Recipe id recorded on the event trace. */
    val recipe: ResourceLocation? = null,
    /** Source dimension recorded on a dimension-change event trace. */
    val fromDimension: ResourceLocation? = null,
    /** Destination dimension recorded on a dimension-change event trace. */
    val toDimension: ResourceLocation? = null,
    /** Damage source id recorded on a damage/death event trace. */
    val damageSource: ResourceLocation? = null,
    /** Exact damage amount recorded on a damage/death event trace. */
    val damageAmount: Double? = null,
    /** Input device recorded on keyboard/mouse event traces. */
    val inputDevice: String? = null,
    /** Input key/button code recorded on keyboard/mouse event traces. */
    val inputCode: String? = null,
    /** Input action recorded on keyboard/mouse event traces. */
    val inputAction: String? = null,
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
            (criterion == null || event.advancements.any { it.criterion == criterion }) &&
            failureMatches(event) &&
            (item == null || event.item == item) &&
            (entity == null || event.entity == entity) &&
            (block == null || event.block == block) &&
            (blockX == null || event.blockPos?.x == blockX) &&
            (blockY == null || event.blockPos?.y == blockY) &&
            (blockZ == null || event.blockPos?.z == blockZ) &&
            (recipe == null || event.recipe == recipe) &&
            (fromDimension == null || event.fromDimension == fromDimension) &&
            (toDimension == null || event.toDimension == toDimension) &&
            (damageSource == null || event.damageSource == damageSource) &&
            (damageAmount == null || event.damageAmount == damageAmount) &&
            (inputDevice == null || event.input?.device == inputDevice) &&
            (inputCode == null || event.input?.code == inputCode) &&
            (inputAction == null || event.input?.action == inputAction)

    private fun failureMatches(event: PlayerEventTraceEvent): Boolean {
        if (failedAdvancement == null && failedCriterion == null && failureContains == null) return true
        return event.advancementFailures.any { failure ->
            (failedAdvancement == null || failure.advancement == failedAdvancement) &&
                (failedCriterion == null || failure.criterion == failedCriterion) &&
                (failureContains == null || failure.reason.contains(failureContains))
        }
    }

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
            failedAdvancement?.let { "failedAdvancement=$it" },
            failedCriterion?.let { "failedCriterion=$it" },
            failureContains?.let { "failureContains=$it" },
            item?.let { "item=$it" },
            entity?.let { "entity=$it" },
            block?.let { "block=$it" },
            blockX?.let { "blockX=$it" },
            blockY?.let { "blockY=$it" },
            blockZ?.let { "blockZ=$it" },
            recipe?.let { "recipe=$it" },
            fromDimension?.let { "from=$it" },
            toDimension?.let { "to=$it" },
            damageSource?.let { "damageSource=$it" },
            damageAmount?.let { "damageAmount=$it" },
            inputDevice?.let { "inputDevice=$it" },
            inputCode?.let { "inputCode=$it" },
            inputAction?.let { "inputAction=$it" },
        ).ifEmpty { listOf("<any event trace>") }.joinToString(", ")

    private fun actualEvents(events: List<PlayerEventTraceEvent>): String {
        if (events.isEmpty()) return "actual event traces: <none>"
        val rendered = events.take(5).mapIndexed { index, event ->
            val status = if (event.success) "OK" else "ERR"
            val advancements = event.advancements
                .take(3)
                .joinToString(prefix = "[", postfix = "]") { "${it.advancement}/${it.criterion}:completed=${it.completed}" }
            val failures = event.advancementFailures
                .take(3)
                .joinToString(prefix = "[", postfix = "]") { "${it.advancement}/${it.criterion}:${it.reason}" }
            "#${index + 1} [$status] player=${event.playerName} type=${event.type} advancements=$advancements failures=$failures"
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
