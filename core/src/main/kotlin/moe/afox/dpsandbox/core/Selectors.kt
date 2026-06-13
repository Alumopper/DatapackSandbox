package moe.afox.dpsandbox.core

private data class SelectorOptions(
    val tags: List<Pair<String, Boolean>> = emptyList(),
    val type: Pair<ResourceLocation, Boolean>? = null,
    val limit: Int? = null,
)

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
        options.type?.let { (type, positive) ->
            result = result.filter { (it.type == type) == positive }
        }
        options.tags.forEach { (tag, positive) ->
            result = result.filter { (tag in it.tags) == positive }
        }
        val sorted = when (selector) {
            "@p", "@n" -> result.sortedWith(compareBy<SandboxEntity> { it.position.distanceSquaredTo(context.position) }.thenBy { it.uuid })
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
        var limit: Int? = null
        optionsText.split(',').filter { it.isNotBlank() }.forEach { rawPart ->
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
                "limit" -> limit = value.toIntOrNull()
                    ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid selector limit: $value", location = location)
                else -> unsupportedFeature("Unsupported selector option '$key'", location = location, command = token)
            }
        }
        return SelectorOptions(tags = tags, type = type, limit = limit)
    }
}

private fun Position.distanceSquaredTo(other: Position): Double {
    val dx = x - other.x
    val dy = y - other.y
    val dz = z - other.z
    return dx * dx + dy * dy + dz * dz
}
