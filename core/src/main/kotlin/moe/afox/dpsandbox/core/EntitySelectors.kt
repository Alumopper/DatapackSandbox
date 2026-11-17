package moe.afox.dpsandbox.core

import com.google.gson.JsonObject
import kotlin.random.Random

private data class SelectorOptions(
    val tags: List<Pair<String, Boolean>> = emptyList(),
    val types: List<Pair<String, Boolean>> = emptyList(),
    val names: List<Pair<String, Boolean>> = emptyList(),
    val gamemodes: List<Pair<String, Boolean>> = emptyList(),
    val teams: List<Pair<String, Boolean>> = emptyList(),
    val nbt: List<Pair<JsonObject, Boolean>> = emptyList(),
    val predicates: List<Pair<ResourceLocation, Boolean>> = emptyList(),
    val scores: Map<String, SelectorScoreRange> = emptyMap(),
    val advancements: Map<ResourceLocation, SelectorAdvancementExpectation> = emptyMap(),
    val level: SelectorScoreRange? = null,
    val xRotation: ClosedFloatingPointRange<Double>? = null,
    val yRotation: ClosedFloatingPointRange<Double>? = null,
    val limit: Int? = null,
    val sort: String? = null,
    val distance: ClosedFloatingPointRange<Double>? = null,
    val x: Double? = null,
    val y: Double? = null,
    val z: Double? = null,
    val dx: Double? = null,
    val dy: Double? = null,
    val dz: Double? = null,
)

private data class SelectorScoreRange(
    val min: Int? = null,
    val max: Int? = null,
) {
    fun contains(value: Int): Boolean = (min == null || value >= min) && (max == null || value <= max)
}

private data class SelectorAdvancementExpectation(
    val done: Boolean? = null,
    val criteria: Map<String, Boolean> = emptyMap(),
)

object EntitySelectors {
    fun isSelector(value: String): Boolean = value.startsWith("@")

    fun select(
        world: SandboxWorld,
        token: String,
        context: ExecutionContext,
        location: SourceLocation? = null,
    ): List<SandboxEntity> {
        if (!token.startsWith("@")) {
            val normalizedUuid = normalizeUuid(token)
            return world.players[token]?.let { listOf(it) }
                ?: world.entities.filter { entity ->
                    entity.uuid == token || (normalizedUuid != null && normalizeUuid(entity.uuid) == normalizedUuid)
                }
        }

        val selector = token.substringBefore("[")
        val options = parseOptions(token, location)
        val initial =
            when (selector) {
                "@s" -> listOfNotNull(context.entity)
                "@e" -> world.entities.toList()
                "@a", "@p", "@r" -> world.players.values.toList()
                "@n" -> world.entities.toList()
                else -> unsupportedFeature("Unsupported selector '$selector'", location = location, command = token)
            }

        var result = initial.asSequence()
        val origin =
            Position(
                x = options.x ?: context.position.x,
                y = options.y ?: context.position.y,
                z = options.z ?: context.position.z,
            )
        if (options.x != null ||
            options.y != null ||
            options.z != null ||
            options.distance != null ||
            options.dx != null ||
            options.dy != null ||
            options.dz != null
        ) {
            result = result.filter { it.dimension == context.dimension }
        }
        options.types.forEach { (type, positive) ->
            val tag = type.startsWith('#')
            val id = ResourceLocation.parse(type.removePrefix("#"))
            result =
                result.filter {
                    val matches = if (tag) context.predicateEngine?.entityTypeInTag(it.type, id) == true else it.type == id
                    matches == positive
                }
        }
        options.names.forEach { (name, positive) ->
            result = result.filter { (it.selectorName() == name) == positive }
        }
        options.gamemodes.forEach { (mode, positive) ->
            result =
                result.filter { entity ->
                    entity is SandboxPlayer && ((entity.gameMode == mode) == positive)
                }
        }
        options.teams.forEach { (team, positive) ->
            result =
                result.filter { entity ->
                    val actual = world.teamFor(entity)
                    val expected = team.takeIf { it.isNotBlank() }
                    val matched = actual == expected
                    matched == positive
                }
        }
        options.nbt.forEach { (expected, positive) ->
            result =
                result.filter { entity ->
                    matchesNbt(entity.fullNbt(context.predicateEngine?.profile ?: VersionProfiles.default, location), expected) == positive
                }
        }
        options.predicates.forEach { (predicate, positive) ->
            val engine =
                context.predicateEngine
                    ?: throw SandboxException(
                        DiagnosticCode.MISSING_CONTEXT,
                        "Selector predicate requires predicate runtime context",
                        location = location,
                    )
            result =
                result.filter { entity ->
                    engine.test(predicate, selectorPredicateContext(world, entity)) == positive
                }
        }
        options.tags.forEach { (tag, positive) ->
            result = result.filter { (if (tag.isEmpty()) it.tags.isEmpty() else tag in it.tags) == positive }
        }
        if (options.scores.isNotEmpty()) {
            result =
                result.filter { entity ->
                    options.scores.all { (objective, range) ->
                        world.scores[ScoreKey(entity.scoreHolder, objective)]?.let(range::contains) == true
                    }
                }
        }
        if (options.advancements.isNotEmpty()) {
            result =
                result.filter { entity ->
                    entity is SandboxPlayer &&
                        options.advancements.all { (advancement, expectation) ->
                            entity.matchesAdvancement(advancement, expectation)
                        }
                }
        }
        options.level?.let { level ->
            result =
                result.filter { entity ->
                    entity is SandboxPlayer && level.contains(entity.xpLevels)
                }
        }
        options.xRotation?.let { range ->
            result = result.filter { matchesRotation(it.pitch, range) }
        }
        options.yRotation?.let { range ->
            result = result.filter { matchesRotation(it.yaw, range) }
        }
        options.distance?.let { distance ->
            val minSquared = distance.start * distance.start
            val maxSquared = distance.endInclusive * distance.endInclusive
            result = result.filter { it.position.distanceSquaredTo(origin) in minSquared..maxSquared }
        }
        if (options.dx != null || options.dy != null || options.dz != null) {
            val max =
                Position(
                    x = origin.x + (options.dx ?: 0.0),
                    y = origin.y + (options.dy ?: 0.0),
                    z = origin.z + (options.dz ?: 0.0),
                )
            val minX = minOf(origin.x, max.x)
            val maxX = maxOf(origin.x, max.x) + 1.0
            val minY = minOf(origin.y, max.y)
            val maxY = maxOf(origin.y, max.y) + 1.0
            val minZ = minOf(origin.z, max.z)
            val maxZ = maxOf(origin.z, max.z) + 1.0
            result =
                result.filter { entity ->
                    val pos = entity.position
                    val size = SpecialEntitySupport.selectionSize(entity)
                    if (size == null) {
                        // Entities with no modeled dimensions use their feet as the reference point.
                        pos.x >= minX && pos.x < maxX && pos.y >= minY && pos.y < maxY && pos.z >= minZ && pos.z < maxZ
                    } else {
                        val radius = size.first / 2
                        pos.x + radius > minX &&
                            pos.x - radius < maxX &&
                            pos.y + size.second > minY &&
                            pos.y < maxY &&
                            pos.z + radius > minZ &&
                            pos.z - radius < maxZ
                    }
                }
        }
        val sort =
            options.sort ?: when (selector) {
                "@r" -> "random"
                "@p", "@n" -> "nearest"
                else -> "arbitrary"
            }
        val sorted =
            when (sort) {
                "nearest" -> result.sortedWith(compareBy<SandboxEntity> { it.position.distanceSquaredTo(origin) }.thenBy { it.uuid })
                "furthest" ->
                    result.sortedWith(
                        compareByDescending<SandboxEntity> { it.position.distanceSquaredTo(origin) }.thenBy { it.uuid },
                    )
                "random" ->
                    result
                        .toList()
                        .let { candidates ->
                            if (candidates.size < 2) candidates else candidates.shuffled(Random(world.seed + world.selectorDraw++))
                        }.asSequence()
                "arbitrary" -> result
                else -> result
            }.toList()
        val defaultLimit =
            when (selector) {
                "@p", "@n", "@r" -> 1
                else -> null
            }
        return (options.limit ?: defaultLimit)?.let { sorted.take(it) } ?: sorted
    }

    private fun parseOptions(
        token: String,
        location: SourceLocation?,
    ): SelectorOptions {
        val optionsText =
            token
                .substringAfter("[", missingDelimiterValue = "")
                .substringBeforeLast("]", missingDelimiterValue = "")
        if ("[" !in token) return SelectorOptions()
        if (!token.endsWith("]")) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Malformed selector options: $token", location = location)
        }

        if (optionsText.isBlank()) return SelectorOptions()
        val seen = mutableSetOf<String>()
        val tags = mutableListOf<Pair<String, Boolean>>()
        val types = mutableListOf<Pair<String, Boolean>>()
        val names = mutableListOf<Pair<String, Boolean>>()
        val gamemodes = mutableListOf<Pair<String, Boolean>>()
        val teams = mutableListOf<Pair<String, Boolean>>()
        val nbt = mutableListOf<Pair<JsonObject, Boolean>>()
        val predicates = mutableListOf<Pair<ResourceLocation, Boolean>>()
        var scores: Map<String, SelectorScoreRange> = emptyMap()
        var advancements: Map<ResourceLocation, SelectorAdvancementExpectation> = emptyMap()
        var level: SelectorScoreRange? = null
        var xRotation: ClosedFloatingPointRange<Double>? = null
        var yRotation: ClosedFloatingPointRange<Double>? = null
        var limit: Int? = null
        var sort: String? = null
        var distance: ClosedFloatingPointRange<Double>? = null
        var x: Double? = null
        var y: Double? = null
        var z: Double? = null
        var dx: Double? = null
        var dy: Double? = null
        var dz: Double? = null
        splitSelectorOptions(optionsText, location).filter { it.isNotBlank() }.forEach { rawPart ->
            val part = rawPart.trim()
            if ('=' !in part) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Expected selector option key=value", location)
            val key = part.substringBefore("=").trim()
            val value = part.substringAfter("=").trim()
            if (key !in setOf("tag", "nbt", "predicate", "type", "gamemode", "name", "team") && !seen.add(key)) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Duplicate selector option '$key'", location)
            }
            when (key) {
                "tag" -> {
                    val positive = !value.startsWith("!")
                    tags += value.removePrefix("!") to positive
                }
                "type" -> {
                    val positive = !value.startsWith("!")
                    types.addFilter(value.removePrefix("!"), positive, key, location)
                }
                "name" -> {
                    val positive = !value.startsWith("!")
                    val raw = value.removePrefix("!")
                    val text = if (raw.startsWith('"') || raw.startsWith("\u0027")) JsonValues.parse(raw, location).asString else raw
                    names.addFilter(text, positive, key, location)
                }
                "gamemode" -> {
                    val positive = !value.startsWith("!")
                    gamemodes.addFilter(normalizeSelectorGameMode(value.removePrefix("!"), location), positive, key, location)
                }
                "team" -> {
                    val positive = !value.startsWith("!")
                    teams.addFilter(value.removePrefix("!"), positive, key, location)
                }
                "nbt" -> {
                    val positive = !value.startsWith("!")
                    nbt += parseSelectorNbt(value.removePrefix("!"), location) to positive
                }
                "predicate" -> {
                    val positive = !value.startsWith("!")
                    predicates += ResourceLocation.parse(value.removePrefix("!")) to positive
                }
                "scores" -> scores = parseSelectorScores(value, location)
                "advancements" -> advancements = parseSelectorAdvancements(value, location)
                "level" -> level = parseSelectorIntRange(value, "level", location)
                "x_rotation" -> xRotation = parseSignedSelectorRange(value, "x_rotation", location)
                "y_rotation" -> yRotation = parseSignedSelectorRange(value, "y_rotation", location)
                "limit" ->
                    limit = value.toIntOrNull()?.takeIf { it >= 1 }
                        ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid selector limit: $value", location = location)
                "sort" -> {
                    if (value !in setOf("nearest", "furthest", "random", "arbitrary")) {
                        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid selector sort: $value", location = location)
                    }
                    sort = value
                }
                "distance" -> distance = parseSelectorRange(value, "distance", location)
                "x" -> x = parseSelectorDouble(value, "x", location)
                "y" -> y = parseSelectorDouble(value, "y", location)
                "z" -> z = parseSelectorDouble(value, "z", location)
                "dx" -> dx = parseSelectorDouble(value, "dx", location)
                "dy" -> dy = parseSelectorDouble(value, "dy", location)
                "dz" -> dz = parseSelectorDouble(value, "dz", location)
                else -> unsupportedFeature("Unsupported selector option '$key'", location = location, command = token)
            }
        }
        return SelectorOptions(
            tags = tags,
            types = types,
            names = names,
            gamemodes = gamemodes,
            teams = teams,
            nbt = nbt,
            predicates = predicates,
            scores = scores,
            advancements = advancements,
            level = level,
            xRotation = xRotation,
            yRotation = yRotation,
            limit = limit,
            sort = sort,
            distance = distance,
            x = x,
            y = y,
            z = z,
            dx = dx,
            dy = dy,
            dz = dz,
        )
    }

    private fun splitSelectorOptions(
        text: String,
        location: SourceLocation?,
    ): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var quote: Char? = null
        var escaped = false
        var start = 0
        text.forEachIndexed { index, char ->
            val activeQuote = quote
            if (activeQuote != null) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == activeQuote -> quote = null
                }
                return@forEachIndexed
            }
            when (char) {
                '"', '\'' -> quote = char
                '{', '[', '(' -> depth += 1
                '}', ']', ')' -> {
                    depth -= 1
                    if (depth < 0) {
                        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Malformed selector options: $text", location = location)
                    }
                }
                ',' ->
                    if (depth == 0) {
                        parts += text.substring(start, index)
                        start = index + 1
                    }
            }
        }
        if (quote != null || depth != 0) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Malformed selector options: $text", location = location)
        }
        parts += text.substring(start)
        return parts
    }

    private fun normalizeSelectorGameMode(
        value: String,
        location: SourceLocation?,
    ): String =
        when (value) {
            "survival" -> "survival"
            "creative" -> "creative"
            "adventure" -> "adventure"
            "spectator" -> "spectator"
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid selector gamemode: $value", location = location)
        }

    private fun parseSelectorScores(
        value: String,
        location: SourceLocation?,
    ): Map<String, SelectorScoreRange> {
        if (!value.startsWith("{") || !value.endsWith("}")) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid selector scores: $value", location = location)
        }
        val body = value.removePrefix("{").removeSuffix("}")
        if (body.isBlank()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid selector scores: $value", location = location)
        }
        return splitSelectorOptions(body, location).associate { rawPart ->
            val part = rawPart.trim()
            val objective = part.substringBefore("=")
            val range = part.substringAfter("=", missingDelimiterValue = "")
            if (objective.isBlank() || range.isBlank()) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid selector score entry: $part", location = location)
            }
            objective to parseSelectorIntRange(range, "score $objective", location)
        }
    }

    private fun parseSelectorNbt(
        value: String,
        location: SourceLocation?,
    ): JsonObject {
        val parsed = JsonValues.parse(value, location)
        if (!parsed.isJsonObject) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid selector nbt: $value", location = location)
        }
        return parsed.asJsonObject
    }

    private fun parseSelectorAdvancements(
        value: String,
        location: SourceLocation?,
    ): Map<ResourceLocation, SelectorAdvancementExpectation> {
        if (!value.startsWith("{") || !value.endsWith("}")) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid selector advancements: $value", location = location)
        }
        val body = value.removePrefix("{").removeSuffix("}")
        if (body.isBlank()) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid selector advancements: $value", location = location)
        }
        return splitSelectorOptions(body, location).associate { rawPart ->
            val part = rawPart.trim()
            val id = part.substringBefore("=")
            val rawExpectation = part.substringAfter("=", missingDelimiterValue = "")
            if (id.isBlank() || rawExpectation.isBlank()) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid selector advancement entry: $part", location = location)
            }
            ResourceLocation.parse(id) to parseSelectorAdvancementExpectation(rawExpectation, location)
        }
    }

    private fun parseSelectorAdvancementExpectation(
        value: String,
        location: SourceLocation?,
    ): SelectorAdvancementExpectation =
        when {
            value == "true" || value == "false" -> SelectorAdvancementExpectation(done = value.toBooleanStrict())
            value.startsWith("{") && value.endsWith("}") -> {
                val body = value.removePrefix("{").removeSuffix("}")
                if (body.isBlank()) {
                    throw SandboxException(
                        DiagnosticCode.INPUT_FORMAT,
                        "Invalid selector advancement criteria: $value",
                        location = location,
                    )
                }
                val criteria =
                    splitSelectorOptions(body, location).associate { rawPart ->
                        val part = rawPart.trim()
                        val criterion = part.substringBefore("=")
                        val rawExpected = part.substringAfter("=", missingDelimiterValue = "")
                        if (criterion.isBlank() || rawExpected !in setOf("true", "false")) {
                            throw SandboxException(
                                DiagnosticCode.INPUT_FORMAT,
                                "Invalid selector advancement criterion: $part",
                                location = location,
                            )
                        }
                        criterion to rawExpected.toBooleanStrict()
                    }
                SelectorAdvancementExpectation(criteria = criteria)
            }
            else -> throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "Invalid selector advancement expectation: $value",
                location = location,
            )
        }

    private fun parseSelectorIntRange(
        value: String,
        label: String,
        location: SourceLocation?,
    ): SelectorScoreRange {
        if (!value.contains("..")) {
            val exact =
                value.toIntOrNull()
                    ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid selector $label: $value", location = location)
            return SelectorScoreRange(exact, exact)
        }
        val startText = value.substringBefore("..")
        val endText = value.substringAfter("..")
        val start = startText.takeIf { it.isNotBlank() }?.toIntOrNull()
        val end = endText.takeIf { it.isNotBlank() }?.toIntOrNull()
        if ((startText.isNotBlank() && start == null) ||
            (endText.isNotBlank() && end == null) ||
            (start != null && end != null && start > end)
        ) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid selector $label range: $value", location = location)
        }
        return SelectorScoreRange(start, end)
    }

    private fun parseSelectorDouble(
        value: String,
        label: String,
        location: SourceLocation?,
    ): Double =
        value.toDoubleOrNull()?.takeIf { it.isFinite() }
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid selector $label: $value", location = location)

    private fun matchesRotation(value: Double, range: ClosedFloatingPointRange<Double>): Boolean {
        fun wrapped(angle: Double): Double {
            val normalized = angle % 360.0
            return when {
                normalized >= 180.0 -> normalized - 360.0
                normalized < -180.0 -> normalized + 360.0
                else -> normalized
            }
        }
        val angle = wrapped(value)
        val min = if (range.start.isFinite()) wrapped(range.start) else 0.0
        val max = if (range.endInclusive.isFinite()) wrapped(range.endInclusive) else wrapped(359.0)
        return if (min <= max) angle in min..max else angle >= min || angle <= max
    }

    private fun parseSignedSelectorRange(
        value: String,
        label: String,
        location: SourceLocation?,
    ): ClosedFloatingPointRange<Double> {
        if (!value.contains("..")) {
            val exact = parseSelectorDouble(value, label, location)
            return exact..exact
        }
        val startText = value.substringBefore("..")
        val endText = value.substringAfter("..")
        val start = if (startText.isBlank()) Double.NEGATIVE_INFINITY else parseSelectorDouble(startText, "$label start", location)
        val end = if (endText.isBlank()) Double.POSITIVE_INFINITY else parseSelectorDouble(endText, "$label end", location)
        if (startText.isEmpty() && endText.isEmpty()) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Empty selector $label range", location)
        return start..end
    }

    private fun parseSelectorRange(
        value: String,
        label: String,
        location: SourceLocation?,
    ): ClosedFloatingPointRange<Double> {
        if (!value.contains("..")) {
            val exact = parseSelectorDouble(value, label, location)
            if (exact < 0.0) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid selector $label range: $value", location = location)
            }
            return exact..exact
        }
        val startText = value.substringBefore("..")
        val endText = value.substringAfter("..")
        val start = if (startText.isBlank()) 0.0 else parseSelectorDouble(startText, "$label start", location)
        val end = if (endText.isBlank()) Double.POSITIVE_INFINITY else parseSelectorDouble(endText, "$label end", location)
        if (start < 0.0 || end < 0.0 || start > end) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid selector $label range: $value", location = location)
        }
        return start..end
    }

    private fun <T> MutableList<Pair<T, Boolean>>.addFilter(value: T, positive: Boolean, key: String, location: SourceLocation?) {
        if (isNotEmpty() && (positive || any { it.second })) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Incompatible repeated selector option '$key'", location)
        }
        add(value to positive)
    }

    private fun selectorPredicateContext(
        world: SandboxWorld,
        entity: SandboxEntity,
    ): PredicateContext {
        val player = entity as? SandboxPlayer
        return PredicateContext(
            world = world,
            origin = entity.position,
            dimension = entity.dimension,
            thisEntity = entity,
            player = player,
            tool = player?.selectedItem,
            weather =
                WeatherState(
                    raining = world.weather == "rain" || world.weather == "thunder",
                    thundering = world.weather == "thunder",
                ),
        )
    }
}

private fun Position.distanceSquaredTo(other: Position): Double {
    val dx = x - other.x
    val dy = y - other.y
    val dz = z - other.z
    return dx * dx + dy * dy + dz * dz
}

private fun SandboxEntity.selectorName(): String =
    when (this) {
        is SandboxPlayer -> name
        else -> nbt.get("CustomName")?.takeIf { it.isJsonPrimitive }?.asString ?: scoreHolder
    }

private fun SandboxWorld.teamFor(entity: SandboxEntity): String? =
    teams.entries.firstOrNull { (_, team) -> entity.scoreHolder in team.members }?.key

private fun SandboxPlayer.matchesAdvancement(
    advancement: ResourceLocation,
    expectation: SelectorAdvancementExpectation,
): Boolean {
    val progress = advancementProgress[advancement]
    expectation.done?.let { expected ->
        val actualDone = progress?.criteria?.isNotEmpty() == true && progress.criteria.values.all { it }
        if (actualDone != expected) return false
    }
    return expectation.criteria.all { (criterion, expected) ->
        (progress?.criteria?.get(criterion) == true) == expected
    }
}
