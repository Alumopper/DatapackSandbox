package moe.afox.dpsandbox.engine

internal enum class CompletionSyntax {
    PLAIN,
    TARGET,
    NBT,
    TEXT_COMPONENT,
}

/** Context completion for arguments that remain a single command token while they are edited. */
internal object StructuredCommandCompletion {
    fun complete(
        prefix: String,
        start: Int,
        end: Int,
        options: List<EngineCommandCompletion.Option>,
        environment: EngineCompletionEnvironment,
    ): List<EngineCompletionCandidate>? {
        val syntax =
            when {
                prefix.startsWith('@') && '[' in prefix -> CompletionSyntax.TARGET
                prefix.startsWith('{') || prefix.startsWith('[') ->
                    options.firstOrNull { it.syntax == CompletionSyntax.TEXT_COMPONENT || it.syntax == CompletionSyntax.NBT }?.syntax
                else -> null
            } ?: return null
        val option = options.firstOrNull { it.syntax == syntax } ?: return null
        return when (syntax) {
            CompletionSyntax.TARGET -> selector(prefix, start, end, option, environment)
            CompletionSyntax.NBT -> structuredValue(prefix, start, end, CompletionSyntax.NBT, environment)
            CompletionSyntax.TEXT_COMPONENT -> structuredValue(prefix, start, end, CompletionSyntax.TEXT_COMPONENT, environment)
            CompletionSyntax.PLAIN -> null
        }
    }

    private fun selector(
        prefix: String,
        start: Int,
        end: Int,
        option: EngineCommandCompletion.Option,
        environment: EngineCompletionEnvironment,
    ): List<EngineCompletionCandidate> {
        val contentStart = prefix.indexOf('[') + 1
        val segmentStart = lastTopLevelDelimiter(prefix, contentStart, ',') + 1
        val rawSegment = prefix.substring(segmentStart)
        val leading = rawSegment.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) rawSegment.length else it }
        val memberStart = segmentStart + leading
        val segment = prefix.substring(memberStart)
        val equals = topLevelDelimiter(segment, '=')
        if (equals < 0) {
            val values = buildList {
                addAll(SELECTOR_KEYS)
                if (segment.isEmpty()) add("]")
            }
            return candidates(
                values,
                segment,
                start + memberStart,
                end,
                description = "target selector arguments",
                group = "selector",
                appendSpace = { it == "]" && option.appendSpace },
            )
        }

        val key = segment.substring(0, equals).trim().lowercase()
        val rawValue = segment.substring(equals + 1)
        val valueLeading = rawValue.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) rawValue.length else it }
        val valueStart = memberStart + equals + 1 + valueLeading
        val valuePrefix = prefix.substring(valueStart)
        if (key == "scores" && valuePrefix.startsWith('{')) {
            return scoreMap(valuePrefix, start + valueStart, end, environment)
        }
        if (key == "nbt" && valuePrefix.startsWith('{')) {
            return structuredValue(valuePrefix, start + valueStart, end, CompletionSyntax.NBT, environment)
        }
        return candidates(
            selectorValues(key, environment),
            valuePrefix,
            start + valueStart,
            end,
            description = "target selector $key values",
            group = "selector",
        )
    }

    private fun selectorValues(
        key: String,
        environment: EngineCompletionEnvironment,
    ): List<String> =
        when (key) {
            "type" -> withNegated(environment.entities)
            "tag" -> withNegated(environment.tags)
            "name" -> withNegated(environment.scoreHolders + "Steve")
            "gamemode" -> withNegated(GAME_MODES)
            "sort" -> listOf("arbitrary", "furthest", "nearest", "random")
            "limit" -> listOf("1", "5", "10")
            "distance", "level", "x_rotation", "y_rotation" -> listOf("0", "0..", "..0", "0..10")
            "x", "y", "z", "dx", "dy", "dz" -> listOf("0", "1", "-1", "~")
            "predicate" -> withNegated(listOf("minecraft:entity_properties"))
            "scores", "advancements", "nbt" -> listOf("{}")
            "team" -> listOf("!", "")
            else -> emptyList()
        }

    private fun scoreMap(
        prefix: String,
        start: Int,
        end: Int,
        environment: EngineCompletionEnvironment,
    ): List<EngineCompletionCandidate> {
        val segmentStart = lastTopLevelDelimiter(prefix, 1, ',') + 1
        val rawSegment = prefix.substring(segmentStart)
        val leading = rawSegment.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) rawSegment.length else it }
        val memberStart = segmentStart + leading
        val segment = prefix.substring(memberStart)
        val equals = topLevelDelimiter(segment, '=')
        return if (equals < 0) {
            candidates(
                buildList {
                    addAll(environment.objectives.map { "$it=" })
                    if (segment.isEmpty()) add("}")
                },
                segment,
                start + memberStart,
                end,
                description = "score selector objectives",
                group = "selector",
            )
        } else {
            val rawValue = segment.substring(equals + 1)
            val leadingValue = rawValue.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) rawValue.length else it }
            val valueStart = memberStart + equals + 1 + leadingValue
            candidates(
                listOf("0", "0..", "..0", "0..10"),
                prefix.substring(valueStart),
                start + valueStart,
                end,
                description = "score selector ranges",
                group = "selector",
            )
        }
    }

    private fun structuredValue(
        prefix: String,
        start: Int,
        end: Int,
        syntax: CompletionSyntax,
        environment: EngineCompletionEnvironment,
    ): List<EngineCompletionCandidate> {
        val container = openContainers(prefix).lastOrNull() ?: return emptyList()
        val parentKey = keyBefore(prefix, container.start)
        val segmentStart = lastTopLevelDelimiter(prefix, container.start + 1, ',') + 1
        val rawSegment = prefix.substring(segmentStart)
        val leading = rawSegment.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) rawSegment.length else it }
        val memberStart = segmentStart + leading
        val segment = prefix.substring(memberStart)
        if (container.kind == '[') {
            return arrayValues(syntax, parentKey, segment, start + memberStart, end, environment)
        }

        val colon = topLevelDelimiter(segment, ':')
        if (colon < 0) {
            val values = buildList {
                addAll(objectKeys(syntax, parentKey))
                if (segment.isEmpty()) add("}")
            }
            return candidates(
                values,
                segment,
                start + memberStart,
                end,
                description = if (syntax == CompletionSyntax.TEXT_COMPONENT) "text component fields" else "NBT fields",
                group = if (syntax == CompletionSyntax.TEXT_COMPONENT) "text component" else "nbt",
            )
        }

        val key = segment.substring(0, colon).trim().trim('"', '\'').lowercase()
        val rawValue = segment.substring(colon + 1)
        val valueLeading = rawValue.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) rawValue.length else it }
        val valueStart = memberStart + colon + 1 + valueLeading
        return candidates(
            fieldValues(syntax, key, environment),
            prefix.substring(valueStart),
            start + valueStart,
            end,
            description = if (syntax == CompletionSyntax.TEXT_COMPONENT) "text component $key values" else "NBT $key values",
            group = if (syntax == CompletionSyntax.TEXT_COMPONENT) "text component" else "nbt",
        )
    }

    private fun objectKeys(
        syntax: CompletionSyntax,
        parentKey: String?,
    ): List<String> =
        if (syntax == CompletionSyntax.TEXT_COMPONENT) {
            when (parentKey?.lowercase()) {
                "score" -> jsonKeys("name", "objective")
                "clickevent", "click_event" -> jsonKeys("action", "command", "url", "value")
                "hoverevent", "hover_event" -> jsonKeys("action", "contents", "value")
                else -> TEXT_COMPONENT_KEYS
            }
        } else {
            when (parentKey?.lowercase()) {
                "transformation" -> nbtKeys("translation", "left_rotation", "scale", "right_rotation")
                "brightness" -> nbtKeys("sky", "block")
                "block_state" -> nbtKeys("Name", "Properties")
                "item" -> nbtKeys("id", "count", "components")
                "properties" -> nbtKeys("axis", "facing", "waterlogged")
                "components" -> listOf("\"minecraft:item_model\":", "\"minecraft:custom_name\":", "\"minecraft:custom_data\":")
                else -> NBT_KEYS
            }
        }

    private fun fieldValues(
        syntax: CompletionSyntax,
        key: String,
        environment: EngineCompletionEnvironment,
    ): List<String> =
        if (syntax == CompletionSyntax.TEXT_COMPONENT) {
            when (key) {
                "bold", "italic", "underlined", "strikethrough", "obfuscated" -> listOf("false", "true")
                "color" -> TEXT_COLORS.map { "\"$it\"" }
                "selector" -> listOf("@a", "@e", "@n", "@p", "@r", "@s").map { "\"$it\"" }
                "score" -> listOf("{\"name\":\"@s\",\"objective\":\"\"}")
                "extra", "with" -> listOf("[]")
                "click_event", "clickevent" -> listOf("{\"action\":\"run_command\",\"command\":\"\"}")
                "hover_event", "hoverevent" -> listOf("{\"action\":\"show_text\",\"contents\":{\"text\":\"\"}}")
                else -> listOf("\"\"")
            }
        } else {
            when (key) {
                "nogravity", "invulnerable", "silent", "glowing", "persistencerequired", "shadow", "see_through", "default_background", "waterlogged" ->
                    listOf("false", "true", "0b", "1b")
                "tags" -> listOf("[]")
                "pos", "motion", "translation" -> listOf("[0.0d,0.0d,0.0d]", "[0f,0f,0f]")
                "rotation" -> listOf("[0f,0f]")
                "scale" -> listOf("[1f,1f,1f]")
                "left_rotation", "right_rotation" -> listOf("[0f,0f,0f,1f]")
                "brightness" -> listOf("{sky:15,block:15}")
                "transformation" -> listOf("{translation:[0f,0f,0f],scale:[1f,1f,1f]}")
                "block_state" -> listOf("{Name:\"minecraft:stone\"}")
                "item" -> listOf("{id:\"minecraft:apple\",count:1}")
                "name" -> environment.blocks.map { "\"$it\"" }
                "id" -> environment.items.map { "\"$it\"" }
                "type" -> environment.entities.map { "\"$it\"" }
                "customname", "text" -> listOf("'{\"text\":\"\"}'", "\"\"")
                "billboard" -> listOf("center", "fixed", "horizontal", "vertical").map { "\"$it\"" }
                "alignment" -> listOf("center", "left", "right").map { "\"$it\"" }
                "item_display" -> listOf("fixed", "ground", "gui", "head", "none", "thirdperson_lefthand", "thirdperson_righthand").map { "\"$it\"" }
                else -> listOf("\"\"", "0", "0f", "false", "true", "[]", "{}")
            }
        }

    private fun arrayValues(
        syntax: CompletionSyntax,
        parentKey: String?,
        prefix: String,
        start: Int,
        end: Int,
        environment: EngineCompletionEnvironment,
    ): List<EngineCompletionCandidate> {
        val values =
            when {
                syntax == CompletionSyntax.TEXT_COMPONENT -> listOf("{\"text\":\"\"}", "\"\"")
                parentKey.equals("Tags", ignoreCase = true) -> environment.tags.map { "\"$it\"" } + "\"\""
                else -> listOf("0", "0b", "0f", "\"\"", "{}")
            }.toMutableList()
        if (prefix.isEmpty()) values += "]"
        return candidates(
            values,
            prefix,
            start,
            end,
            description = if (syntax == CompletionSyntax.TEXT_COMPONENT) "text component elements" else "NBT list elements",
            group = if (syntax == CompletionSyntax.TEXT_COMPONENT) "text component" else "nbt",
        )
    }

    private fun candidates(
        values: Iterable<String>,
        prefix: String,
        start: Int,
        end: Int,
        description: String,
        group: String,
        appendSpace: (String) -> Boolean = { false },
    ): List<EngineCompletionCandidate> =
        values
            .asSequence()
            .filter { prefix.isBlank() || it.startsWith(prefix, ignoreCase = true) }
            .distinct()
            .sorted()
            .take(MAX_SUGGESTIONS)
            .map { value -> EngineCompletionCandidate(value, description, group, appendSpace(value), start, end) }
            .toList()

    private fun withNegated(values: Iterable<String>): List<String> =
        values.flatMap { listOf(it, "!$it") }.distinct().sorted()

    private fun openContainers(source: String): List<Container> {
        val stack = mutableListOf<Container>()
        var quote: Char? = null
        var escaped = false
        source.forEachIndexed { index, char ->
            when {
                escaped -> escaped = false
                char == '\\' && quote != null -> escaped = true
                quote != null -> if (char == quote) quote = null
                char == '"' || char == '\'' -> quote = char
                char == '{' || char == '[' -> stack += Container(char, index)
                char == '}' -> stack.indexOfLast { it.kind == '{' }.takeIf { it >= 0 }?.let { stack.removeAt(it) }
                char == ']' -> stack.indexOfLast { it.kind == '[' }.takeIf { it >= 0 }?.let { stack.removeAt(it) }
            }
        }
        return stack
    }

    private fun lastTopLevelDelimiter(
        source: String,
        from: Int,
        delimiter: Char,
    ): Int {
        var result = from - 1
        scanTopLevel(source, from) { index, char -> if (char == delimiter) result = index }
        return result
    }

    private fun topLevelDelimiter(
        source: String,
        delimiter: Char,
    ): Int {
        var result = -1
        scanTopLevel(source, 0) { index, char -> if (result < 0 && char == delimiter) result = index }
        return result
    }

    private inline fun scanTopLevel(
        source: String,
        from: Int,
        action: (Int, Char) -> Unit,
    ) {
        var quote: Char? = null
        var escaped = false
        var depth = 0
        for (index in from until source.length) {
            val char = source[index]
            when {
                escaped -> escaped = false
                char == '\\' && quote != null -> escaped = true
                quote != null -> if (char == quote) quote = null
                char == '"' || char == '\'' -> quote = char
                char == '{' || char == '[' || char == '(' -> depth += 1
                char == '}' || char == ']' || char == ')' -> depth = (depth - 1).coerceAtLeast(0)
                depth == 0 -> action(index, char)
            }
        }
    }

    private fun keyBefore(
        source: String,
        containerStart: Int,
    ): String? =
        KEY_BEFORE_CONTAINER.find(source.substring(0, containerStart))?.let { match ->
            match.groupValues[1].ifEmpty { match.groupValues[2] }
        }

    private fun jsonKeys(vararg values: String): List<String> = values.map { "\"$it\":" }

    private fun nbtKeys(vararg values: String): List<String> = values.map { "$it:" }

    private data class Container(
        val kind: Char,
        val start: Int,
    )

    private val KEY_BEFORE_CONTAINER = Regex("""(?:"([^"]+)"|([A-Za-z0-9_.:-]+))\s*:\s*$""")
    private val GAME_MODES = listOf("adventure", "creative", "spectator", "survival")
    private val SELECTOR_KEYS =
        listOf(
            "advancements=",
            "distance=",
            "dx=",
            "dy=",
            "dz=",
            "gamemode=",
            "level=",
            "limit=",
            "name=",
            "nbt=",
            "predicate=",
            "scores=",
            "sort=",
            "tag=",
            "team=",
            "type=",
            "x=",
            "x_rotation=",
            "y=",
            "y_rotation=",
            "z=",
        )
    private val TEXT_COMPONENT_KEYS =
        jsonKeys(
            "text",
            "translate",
            "fallback",
            "with",
            "selector",
            "score",
            "keybind",
            "nbt",
            "extra",
            "color",
            "font",
            "bold",
            "italic",
            "underlined",
            "strikethrough",
            "obfuscated",
            "insertion",
            "click_event",
            "hover_event",
        )
    private val NBT_KEYS =
        nbtKeys(
            "Tags",
            "CustomName",
            "NoGravity",
            "Invulnerable",
            "Silent",
            "Glowing",
            "PersistenceRequired",
            "Health",
            "Pos",
            "Motion",
            "Rotation",
            "transformation",
            "billboard",
            "brightness",
            "teleport_duration",
            "interpolation_duration",
            "start_interpolation",
            "view_range",
            "shadow_radius",
            "shadow_strength",
            "glow_color_override",
            "block_state",
            "item",
            "item_display",
            "text",
            "line_width",
            "background",
            "text_opacity",
            "shadow",
            "see_through",
            "alignment",
            "width",
            "height",
        )
    private val TEXT_COLORS =
        listOf(
            "aqua",
            "black",
            "blue",
            "dark_aqua",
            "dark_blue",
            "dark_gray",
            "dark_green",
            "dark_purple",
            "dark_red",
            "gold",
            "gray",
            "green",
            "light_purple",
            "red",
            "white",
            "yellow",
        )
    private const val MAX_SUGGESTIONS = 100
}
