package moe.afox.dpsandbox.core

private data class SelectorOptions(
    val tags: List<Pair<String, Boolean>> = emptyList(),
    val type: Pair<ResourceLocation, Boolean>? = null,
    val name: Pair<String, Boolean>? = null,
    val gamemode: Pair<String, Boolean>? = null,
    val team: Pair<String, Boolean>? = null,
    val scores: Map<String, SelectorScoreRange> = emptyMap(),
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

private data class SelectorScoreRange(val min: Int? = null, val max: Int? = null) {
    fun contains(value: Int): Boolean =
        (min == null || value >= min) && (max == null || value <= max)
}

object EntitySelectors {
    fun isSelector(value: String): Boolean = value.startsWith("@")

    fun select(world: SandboxWorld, token: String, context: ExecutionContext, location: SourceLocation? = null): List<SandboxEntity> {
        if (!token.startsWith("@")) {
            return world.players[token]?.let { listOf(it) }
                ?: world.entities.filter { it.uuid == token }
        }

        val selector = token.substringBefore("[")
        val options = parseOptions(token, location)
        val initial = when (selector) {
            "@s" -> listOfNotNull(context.entity)
            "@e" -> world.entities.toList()
            "@a" -> world.players.values.toList()
            "@p" -> world.players.values.toList()
            "@n" -> world.entities.toList()
            else -> unsupportedFeature("Unsupported selector '$selector'", location = location, command = token)
        }

        var result = initial.asSequence()
        val origin = Position(
            x = options.x ?: context.position.x,
            y = options.y ?: context.position.y,
            z = options.z ?: context.position.z,
        )
        options.type?.let { (type, positive) ->
            result = result.filter { (it.type == type) == positive }
        }
        options.name?.let { (name, positive) ->
            result = result.filter { (it.selectorName() == name) == positive }
        }
        options.gamemode?.let { (mode, positive) ->
            result = result.filter { entity ->
                entity is SandboxPlayer && ((entity.gameMode == mode) == positive)
            }
        }
        options.team?.let { (team, positive) ->
            result = result.filter { entity ->
                val actual = world.teamFor(entity)
                val expected = team.takeIf { it.isNotBlank() }
                val matched = actual == expected
                matched == positive
            }
        }
        options.tags.forEach { (tag, positive) ->
            result = result.filter { (tag in it.tags) == positive }
        }
        if (options.scores.isNotEmpty()) {
            options.scores.keys.forEach(world::ensureObjective)
            result = result.filter { entity ->
                options.scores.all { (objective, range) ->
                    range.contains(world.getScore(entity.scoreHolder, objective))
                }
            }
        }
        options.level?.let { level ->
            result = result.filter { entity ->
                entity is SandboxPlayer && level.contains(entity.xpLevels)
            }
        }
        options.xRotation?.let { range ->
            result = result.filter { it.pitch in range }
        }
        options.yRotation?.let { range ->
            result = result.filter { it.yaw in range }
        }
        options.distance?.let { distance ->
            val minSquared = distance.start * distance.start
            val maxSquared = distance.endInclusive * distance.endInclusive
            result = result.filter { it.position.distanceSquaredTo(origin) in minSquared..maxSquared }
        }
        if (options.dx != null || options.dy != null || options.dz != null) {
            val max = Position(
                x = origin.x + (options.dx ?: 0.0),
                y = origin.y + (options.dy ?: 0.0),
                z = origin.z + (options.dz ?: 0.0),
            )
            val minX = minOf(origin.x, max.x)
            val maxX = maxOf(origin.x, max.x)
            val minY = minOf(origin.y, max.y)
            val maxY = maxOf(origin.y, max.y)
            val minZ = minOf(origin.z, max.z)
            val maxZ = maxOf(origin.z, max.z)
            result = result.filter { entity ->
                entity.position.x in minX..maxX &&
                    entity.position.y in minY..maxY &&
                    entity.position.z in minZ..maxZ
            }
        }
        val sort = options.sort ?: when (selector) {
            "@p", "@n" -> "nearest"
            else -> "arbitrary"
        }
        val sorted = when (sort) {
            "nearest" -> result.sortedWith(compareBy<SandboxEntity> { it.position.distanceSquaredTo(origin) }.thenBy { it.uuid })
            "furthest" -> result.sortedWith(compareByDescending<SandboxEntity> { it.position.distanceSquaredTo(origin) }.thenBy { it.uuid })
            "random" -> result.sortedWith(compareBy<SandboxEntity> { deterministicSelectorHash(it.uuid, origin) }.thenBy { it.uuid })
            "arbitrary" -> result
            else -> result
        }.toList()
        val defaultLimit = when (selector) {
            "@p", "@n" -> 1
            else -> null
        }
        return (options.limit ?: defaultLimit)?.let { sorted.take(it) } ?: sorted
    }

    private fun parseOptions(token: String, location: SourceLocation?): SelectorOptions {
        val optionsText = token.substringAfter("[", missingDelimiterValue = "")
            .substringBeforeLast("]", missingDelimiterValue = "")
        if (optionsText.isBlank()) return SelectorOptions()
        if (!token.endsWith("]")) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Malformed selector options: $token", location = location)
        }

        val tags = mutableListOf<Pair<String, Boolean>>()
        var type: Pair<ResourceLocation, Boolean>? = null
        var name: Pair<String, Boolean>? = null
        var gamemode: Pair<String, Boolean>? = null
        var team: Pair<String, Boolean>? = null
        var scores: Map<String, SelectorScoreRange> = emptyMap()
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
            val key = part.substringBefore("=")
            val value = part.substringAfter("=", missingDelimiterValue = "")
            when (key) {
                "tag" -> {
                    val positive = !value.startsWith("!")
                    tags += value.removePrefix("!") to positive
                }
                "type" -> {
                    val positive = !value.startsWith("!")
                    type = ResourceLocation.parse(value.removePrefix("!")) to positive
                }
                "name" -> {
                    val positive = !value.startsWith("!")
                    name = value.removePrefix("!") to positive
                }
                "gamemode" -> {
                    val positive = !value.startsWith("!")
                    gamemode = normalizeSelectorGameMode(value.removePrefix("!"), location) to positive
                }
                "team" -> {
                    val positive = !value.startsWith("!")
                    team = value.removePrefix("!") to positive
                }
                "scores" -> scores = parseSelectorScores(value, location)
                "level" -> level = parseSelectorIntRange(value, "level", location)
                "x_rotation" -> xRotation = parseSignedSelectorRange(value, "x_rotation", location)
                "y_rotation" -> yRotation = parseSignedSelectorRange(value, "y_rotation", location)
                "limit" -> limit = value.toIntOrNull()
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
            type = type,
            name = name,
            gamemode = gamemode,
            team = team,
            scores = scores,
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

    private fun splitSelectorOptions(text: String, location: SourceLocation?): List<String> {
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
                ',' -> if (depth == 0) {
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

    private fun normalizeSelectorGameMode(value: String, location: SourceLocation?): String =
        when (value.lowercase()) {
            "survival", "s", "0" -> "survival"
            "creative", "c", "1" -> "creative"
            "adventure", "a", "2" -> "adventure"
            "spectator", "sp", "3" -> "spectator"
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid selector gamemode: $value", location = location)
        }

    private fun parseSelectorScores(value: String, location: SourceLocation?): Map<String, SelectorScoreRange> {
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

    private fun parseSelectorIntRange(value: String, label: String, location: SourceLocation?): SelectorScoreRange {
        if (!value.contains("..")) {
            val exact = value.toIntOrNull()
                ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid selector $label: $value", location = location)
            return SelectorScoreRange(exact, exact)
        }
        val startText = value.substringBefore("..")
        val endText = value.substringAfter("..")
        val start = startText.takeIf { it.isNotBlank() }?.toIntOrNull()
        val end = endText.takeIf { it.isNotBlank() }?.toIntOrNull()
        if ((startText.isNotBlank() && start == null) || (endText.isNotBlank() && end == null) || (start != null && end != null && start > end)) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid selector $label range: $value", location = location)
        }
        return SelectorScoreRange(start, end)
    }

    private fun parseSelectorDouble(value: String, label: String, location: SourceLocation?): Double =
        value.toDoubleOrNull()
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid selector $label: $value", location = location)

    private fun parseSignedSelectorRange(value: String, label: String, location: SourceLocation?): ClosedFloatingPointRange<Double> {
        if (!value.contains("..")) {
            val exact = parseSelectorDouble(value, label, location)
            return exact..exact
        }
        val startText = value.substringBefore("..")
        val endText = value.substringAfter("..")
        val start = if (startText.isBlank()) Double.NEGATIVE_INFINITY else parseSelectorDouble(startText, "$label start", location)
        val end = if (endText.isBlank()) Double.POSITIVE_INFINITY else parseSelectorDouble(endText, "$label end", location)
        if (start > end) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid selector $label range: $value", location = location)
        }
        return start..end
    }

    private fun parseSelectorRange(value: String, label: String, location: SourceLocation?): ClosedFloatingPointRange<Double> {
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

    private fun deterministicSelectorHash(uuid: String, origin: Position): Int =
        "$uuid@${origin.x},${origin.y},${origin.z}".hashCode()
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
