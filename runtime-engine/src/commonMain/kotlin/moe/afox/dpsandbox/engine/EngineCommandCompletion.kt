package moe.afox.dpsandbox.engine

data class EngineCompletionEnvironment(
    val roots: List<String>,
    val blocks: List<String>,
    val items: List<String>,
    val entities: List<String>,
    val functions: List<String>,
    val functionTags: List<String>,
    val objectives: List<String>,
    val scoreHolders: List<String>,
    val storages: List<String>,
    val tags: List<String>,
    val gamerules: List<String>,
    val biomes: List<String> = emptyList(),
    val biomeTags: List<String> = emptyList(),
    val damageTypes: List<String> = emptyList(),
    val enchantments: List<String> = emptyList(),
    val effects: List<String> = emptyList(),
    val dimensions: List<String> = emptyList(),
    val attributes: List<String> = emptyList(),
    val particles: List<String> = emptyList(),
    val sounds: List<String> = emptyList(),
    val scoreboardCriteria: List<String> = emptyList(),
    val advancements: List<String> = emptyList(),
    val recipes: List<String> = emptyList(),
    val pointOfInterestTypes: List<String> = emptyList(),
    val pointOfInterestTypeTags: List<String> = emptyList(),
    val structures: List<String> = emptyList(),
    val structureTags: List<String> = emptyList(),
    val configuredFeatures: List<String> = emptyList(),
    val templatePools: List<String> = emptyList(),
    val testInstances: List<String> = emptyList(),
    val worldClocks: List<String> = emptyList(),
    val timelines: List<String> = emptyList(),
)

data class EngineCompletionCandidate(
    val value: String,
    val description: String,
    val group: String,
    val appendSpace: Boolean,
    val start: Int,
    val end: Int,
)

/**
 * A clean-room command tree shared by the offline browser runtime and JVM clients.
 *
 * The previous implementation selected branches from the total token count.
 * That made a completed literal such as `scoreboard players` look like the
 * still-being-typed `players` argument. This completer instead walks only the
 * committed tokens before the cursor, mirroring Brigadier's child-node model.
 */
object EngineCommandCompletion {
    fun complete(
        source: String,
        cursor: Int,
        environment: EngineCompletionEnvironment,
    ): List<EngineCompletionCandidate> {
        val input = CompletionInput.parse(source, cursor)
        val options = commandCandidates(input.committed, environment)
        StructuredCommandCompletion.complete(input.prefix, input.start, input.end, options, environment)?.let { return it }
        val rootSlash = input.committed.isEmpty() && input.prefix.startsWith('/')
        val prefix = if (rootSlash) input.prefix.removePrefix("/") else input.prefix
        return options
            .asSequence()
            .filter { prefix.isBlank() || it.value.startsWith(prefix, ignoreCase = true) }
            .distinctBy { it.value }
            .sortedBy { it.value }
            .take(MAX_SUGGESTIONS)
            .map { option ->
                EngineCompletionCandidate(
                    value = if (rootSlash) "/${option.value}" else option.value,
                    description = option.description,
                    group = option.group,
                    appendSpace = option.appendSpace,
                    start = input.start,
                    end = input.end,
                )
            }.toList()
    }

    private fun commandCandidates(
        rawTokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> {
        if (rawTokens.isEmpty()) return environment.roots.options("Minecraft commands", "command")
        val tokens = rawTokens.mapIndexed { index, token -> if (index == 0) token.removePrefix("/").lowercase() else token }
        if (tokens.first() !in environment.roots) return emptyList()
        return when (tokens.first()) {
            "execute" -> executeCandidates(tokens, environment)
            "scoreboard" -> scoreboardCandidates(tokens, environment)
            "data" -> dataCandidates(tokens, environment)
            "setblock" -> setBlockCandidates(tokens, environment)
            "fill" -> fillCandidates(tokens, environment)
            "summon" -> summonCandidates(tokens, environment)
            "give" -> giveCandidates(tokens, environment)
            "clear" -> clearCandidates(tokens, environment)
            "function" -> if (tokens.size == 1) functionIds(environment).options("functions") else emptyList()
            "kill" -> if (tokens.size == 1) targetOptions(environment, terminal = true) else emptyList()
            "tag" -> tagCandidates(tokens, environment)
            "tellraw" -> tellrawCandidates(tokens, environment)
            "title" -> titleCandidates(tokens, environment)
            "particle" -> particleCandidates(tokens, environment)
            "time" -> timeCandidates(tokens, environment)
            "weather" -> if (tokens.size == 1) listOf("clear", "rain", "thunder").options("weather states") else emptyList()
            "gamerule" -> gameruleCandidates(tokens, environment)
            "tp", "teleport" -> teleportCandidates(tokens, environment)
            "return" -> returnCandidates(tokens, environment)
            "tick" -> tickCandidates(tokens)
            "advancement" -> advancementCandidates(tokens, environment)
            "attribute" -> attributeCandidates(tokens, environment)
            "bossbar" -> bossbarCandidates(tokens, environment)
            "clone" -> cloneCandidates(tokens, environment)
            "damage" -> damageCandidates(tokens, environment)
            "effect" -> effectCandidates(tokens, environment)
            "enchant" -> enchantCandidates(tokens, environment)
            "experience", "xp" -> experienceCandidates(tokens, environment)
            "fillbiome" -> fillBiomeCandidates(tokens, environment)
            "forceload" -> forceLoadCandidates(tokens)
            "gamemode" -> gameModeCandidates(tokens, environment)
            "item" -> itemCandidates(tokens, environment)
            "playsound" -> playSoundCandidates(tokens, environment)
            "recipe" -> recipeCandidates(tokens, environment)
            "ride" -> rideCandidates(tokens, environment)
            "rotate" -> rotateCandidates(tokens, environment)
            "schedule" -> scheduleCandidates(tokens, environment)
            "spawnpoint" -> spawnpointCandidates(tokens, environment)
            "spectate" -> spectateCandidates(tokens, environment)
            "spreadplayers" -> spreadPlayersCandidates(tokens, environment)
            "team" -> teamCandidates(tokens, environment)
            "trigger" -> triggerCandidates(tokens, environment)
            "worldborder" -> worldBorderCandidates(tokens)
            else -> simpleCandidates(tokens, environment)
        }
    }

    private fun scoreboardCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> {
        if (tokens.size == 1) return listOf("objectives", "players").options("scoreboard groups")
        return when (tokens[1]) {
            "objectives" -> objectiveCandidates(tokens, environment)
            "players" -> scorePlayerCandidates(tokens, environment)
            else -> emptyList()
        }
    }

    private fun objectiveCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> {
        if (tokens.size == 2) {
            return listOf("add", "list", "modify", "remove", "setdisplay").options("objective actions")
        }
        return when (tokens[2]) {
            "add" ->
                when (tokens.size) {
                    3 -> listOf("objective").options("objective names")
                    4 -> (environment.scoreboardCriteria + listOf("dummy", "trigger")).distinct().options("objective criteria")
                    5 -> TEXT_COMPONENTS.options("display names", terminal = true, syntax = CompletionSyntax.TEXT_COMPONENT)
                    else -> emptyList()
                }
            "remove" -> if (tokens.size == 3) environment.objectives.options("objectives") else emptyList()
            "modify" ->
                when (tokens.size) {
                    3 -> environment.objectives.options("objectives")
                    4 -> listOf("displayautoupdate", "displayname", "numberformat", "rendertype").options("objective fields")
                    5 ->
                        when (tokens[4]) {
                            "displayautoupdate" -> BOOLEANS.options("booleans")
                            "displayname" -> TEXT_COMPONENTS.options("display names", terminal = true, syntax = CompletionSyntax.TEXT_COMPONENT)
                            "numberformat" -> listOf("blank", "fixed", "styled").options("number formats")
                            "rendertype" -> listOf("hearts", "integer").options("render types")
                            else -> emptyList()
                        }
                    else -> emptyList()
                }
            "setdisplay" ->
                when (tokens.size) {
                    3 -> SCOREBOARD_SLOTS.options("display slots")
                    4 -> environment.objectives.options("objectives", terminal = true)
                    else -> emptyList()
                }
            else -> emptyList()
        }
    }

    private fun scorePlayerCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> {
        if (tokens.size == 2) return SCORE_ACTIONS.options("player score actions")
        val action = tokens[2]
        if (tokens.size == 3) {
            return if (action == "display") listOf("name", "numberformat").options("display fields") else scoreTargets(environment)
        }
        return when (action) {
            "list" -> emptyList()
            "get", "enable", "reset", "set", "add", "remove" ->
                when (tokens.size) {
                    4 -> environment.objectives.options("objectives")
                    5 -> if (action in setOf("set", "add", "remove")) INTEGER_VALUES.options("score values", terminal = true) else emptyList()
                    else -> emptyList()
                }
            "operation" ->
                when (tokens.size) {
                    4 -> environment.objectives.options("objectives")
                    5 -> SCORE_OPERATIONS.options("score operations")
                    6 -> scoreTargets(environment)
                    7 -> environment.objectives.options("objectives", terminal = true)
                    else -> emptyList()
                }
            "display" ->
                when (tokens.size) {
                    4 -> scoreTargets(environment)
                    5 -> if (tokens[3] == "name") TEXT_COMPONENTS.options("display names", terminal = true, syntax = CompletionSyntax.TEXT_COMPONENT) else listOf("blank", "fixed", "styled").options("number formats", terminal = true)
                    else -> emptyList()
                }
            else -> emptyList()
        }
    }

    private fun executeCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> {
        var index = 1
        while (true) {
            if (index >= tokens.size) return EXECUTE_SUBCOMMANDS.options("execute subcommands")
            when (tokens[index]) {
                "run" -> return commandCandidates(tokens.drop(index + 1), environment)
                "as", "at" -> {
                    if (index + 1 >= tokens.size) return targetOptions(environment)
                    index += 2
                }
                "align" -> {
                    if (index + 1 >= tokens.size) return listOf("x", "xy", "xyz", "xz", "y", "yz", "z").options("axes")
                    index += 2
                }
                "anchored" -> {
                    if (index + 1 >= tokens.size) return listOf("eyes", "feet").options("anchors")
                    index += 2
                }
                "in" -> {
                    if (index + 1 >= tokens.size) return environment.dimensions.withFallback(DIMENSIONS).options("dimensions")
                    index += 2
                }
                "on" -> {
                    if (index + 1 >= tokens.size) return EXECUTE_RELATIONS.options("entity relations")
                    index += 2
                }
                "summon" -> {
                    if (index + 1 >= tokens.size) return environment.entities.options("entity types")
                    index += 2
                }
                "positioned" -> {
                    val parsed = parsePositioned(tokens, index, environment)
                    parsed.options?.let { return it }
                    index = parsed.next
                }
                "rotated" -> {
                    val parsed = parseRotated(tokens, index, environment)
                    parsed.options?.let { return it }
                    index = parsed.next
                }
                "facing" -> {
                    val parsed = parseFacing(tokens, index, environment)
                    parsed.options?.let { return it }
                    index = parsed.next
                }
                "if", "unless" -> {
                    val parsed = parseCondition(tokens, index, environment)
                    parsed.options?.let { return it }
                    index = parsed.next
                }
                "store" -> {
                    val parsed = parseStore(tokens, index, environment)
                    parsed.options?.let { return it }
                    index = parsed.next
                }
                else -> return EXECUTE_SUBCOMMANDS.options("execute subcommands")
            }
        }
    }

    private fun parsePositioned(
        tokens: List<String>,
        index: Int,
        environment: EngineCompletionEnvironment,
    ): ParseStep {
        if (index + 1 >= tokens.size) return ParseStep(options = (listOf("as", "over") + COORDINATES).options("position"))
        return when (tokens[index + 1]) {
            "as" -> required(tokens, index + 2, targetOptions(environment), index + 3)
            "over" -> required(tokens, index + 2, HEIGHTMAPS.options("heightmaps"), index + 3)
            else -> coordinates(tokens, index + 1, 3, index + 4)
        }
    }

    private fun parseRotated(
        tokens: List<String>,
        index: Int,
        environment: EngineCompletionEnvironment,
    ): ParseStep {
        if (index + 1 >= tokens.size) return ParseStep(options = (listOf("as") + ROTATIONS).options("rotation"))
        return if (tokens[index + 1] == "as") {
            required(tokens, index + 2, targetOptions(environment), index + 3)
        } else {
            coordinates(tokens, index + 1, 2, index + 3, ROTATIONS)
        }
    }

    private fun parseFacing(
        tokens: List<String>,
        index: Int,
        environment: EngineCompletionEnvironment,
    ): ParseStep {
        if (index + 1 >= tokens.size) return ParseStep(options = (listOf("entity") + COORDINATES).options("facing target"))
        if (tokens[index + 1] != "entity") return coordinates(tokens, index + 1, 3, index + 4)
        if (index + 2 >= tokens.size) return ParseStep(options = targetOptions(environment))
        if (index + 3 >= tokens.size) return ParseStep(options = listOf("eyes", "feet").options("anchors"))
        return ParseStep(index + 4)
    }

    private fun parseCondition(
        tokens: List<String>,
        index: Int,
        environment: EngineCompletionEnvironment,
    ): ParseStep {
        if (index + 1 >= tokens.size) return ParseStep(options = EXECUTE_CONDITIONS.options("execute conditions"))
        val condition = tokens[index + 1]
        return when (condition) {
            "entity" -> required(tokens, index + 2, targetOptions(environment), index + 3)
            "predicate" -> required(tokens, index + 2, listOf("minecraft:entity_properties").options("predicates"), index + 3)
            "function" -> required(tokens, index + 2, functionIds(environment).options("functions/tags"), index + 3)
            "dimension" -> required(tokens, index + 2, environment.dimensions.withFallback(DIMENSIONS).options("dimensions"), index + 3)
            "loaded" -> coordinates(tokens, index + 2, 3, index + 5)
            "biome" -> coordinatesThen(tokens, index + 2, 3, environment.biomes.withFallback(BIOMES).options("biomes"), index + 6)
            "block" -> coordinatesThen(tokens, index + 2, 3, environment.blocks.options("blocks"), index + 6)
            "blocks" -> {
                val positions = coordinates(tokens, index + 2, 9, index + 11)
                if (positions.options != null) positions else required(tokens, index + 11, listOf("all", "masked").options("scan modes"), index + 12)
            }
            "score" -> parseScoreCondition(tokens, index + 2, environment)
            "data" -> parseDataCondition(tokens, index + 2, environment)
            else -> ParseStep(index + 2)
        }
    }

    private fun parseScoreCondition(
        tokens: List<String>,
        index: Int,
        environment: EngineCompletionEnvironment,
    ): ParseStep {
        if (index >= tokens.size) return ParseStep(options = scoreTargets(environment))
        if (index + 1 >= tokens.size) return ParseStep(options = environment.objectives.options("objectives"))
        if (index + 2 >= tokens.size) return ParseStep(options = SCORE_COMPARISONS.options("score comparisons"))
        if (tokens[index + 2] == "matches") {
            return required(tokens, index + 3, listOf("0", "0..", "..0", "0..10").options("score ranges"), index + 4)
        }
        if (index + 3 >= tokens.size) return ParseStep(options = scoreTargets(environment))
        return required(tokens, index + 4, environment.objectives.options("objectives"), index + 5)
    }

    private fun parseDataCondition(
        tokens: List<String>,
        index: Int,
        environment: EngineCompletionEnvironment,
    ): ParseStep {
        if (index >= tokens.size) return ParseStep(options = DATA_TARGETS.options("data targets"))
        return when (tokens[index]) {
            "entity" -> targetAndPath(tokens, index + 1, targetOptions(environment), index + 3)
            "storage" -> targetAndPath(tokens, index + 1, environment.storages.options("storages"), index + 3)
            "block" -> {
                val position = coordinates(tokens, index + 1, 3, index + 4)
                if (position.options != null) position else required(tokens, index + 4, NBT_PATHS.options("NBT paths"), index + 5)
            }
            else -> ParseStep(index + 1)
        }
    }

    private fun targetAndPath(
        tokens: List<String>,
        targetIndex: Int,
        options: List<Option>,
        next: Int,
    ): ParseStep {
        if (targetIndex >= tokens.size) return ParseStep(options = options)
        return required(tokens, targetIndex + 1, NBT_PATHS.options("NBT paths"), next)
    }

    private fun parseStore(
        tokens: List<String>,
        index: Int,
        environment: EngineCompletionEnvironment,
    ): ParseStep {
        if (index + 1 >= tokens.size) return ParseStep(options = listOf("result", "success").options("store modes"))
        if (index + 2 >= tokens.size) return ParseStep(options = STORE_TARGETS.options("store targets"))
        val targetIndex = index + 2
        return when (tokens[targetIndex]) {
            "score" -> {
                if (targetIndex + 1 >= tokens.size) ParseStep(options = scoreTargets(environment))
                else required(tokens, targetIndex + 2, environment.objectives.options("objectives"), targetIndex + 3)
            }
            "bossbar" -> {
                if (targetIndex + 1 >= tokens.size) ParseStep(options = listOf("minecraft:bossbar").options("bossbars"))
                else required(tokens, targetIndex + 2, listOf("max", "value").options("bossbar fields"), targetIndex + 3)
            }
            "storage" -> parseNumericStore(tokens, targetIndex + 1, environment.storages.options("storages"), targetIndex + 5)
            "entity" -> parseNumericStore(tokens, targetIndex + 1, targetOptions(environment), targetIndex + 5)
            "block" -> {
                val position = coordinates(tokens, targetIndex + 1, 3, targetIndex + 4)
                if (position.options != null) position else parseNumericStore(tokens, targetIndex + 4, emptyList(), targetIndex + 7)
            }
            else -> ParseStep(targetIndex + 1)
        }
    }

    private fun parseNumericStore(
        tokens: List<String>,
        targetIndex: Int,
        targetOptions: List<Option>,
        next: Int,
    ): ParseStep {
        if (targetOptions.isNotEmpty() && targetIndex >= tokens.size) return ParseStep(options = targetOptions)
        val pathIndex = if (targetOptions.isEmpty()) targetIndex else targetIndex + 1
        if (pathIndex >= tokens.size) return ParseStep(options = NBT_PATHS.options("NBT paths"))
        if (pathIndex + 1 >= tokens.size) return ParseStep(options = NBT_NUMBER_TYPES.options("numeric types"))
        if (pathIndex + 2 >= tokens.size) return ParseStep(options = listOf("1", "0.01").options("scales"))
        return ParseStep(next)
    }

    private fun dataCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> {
        if (tokens.size == 1) return listOf("get", "merge", "modify", "remove").options("data actions")
        if (tokens.size == 2) return DATA_TARGETS.options("data targets")
        val targetEnd =
            when (tokens[2]) {
                "entity", "storage" -> 4
                "block" -> 6
                else -> return emptyList()
            }
        if (tokens.size == 3) {
            return when (tokens[2]) {
                "entity" -> targetOptions(environment)
                "storage" -> environment.storages.options("storages")
                else -> COORDINATES.options("block position")
            }
        }
        if (tokens[2] == "block" && tokens.size in 4..5) return COORDINATES.options("block position")
        if (tokens.size == targetEnd) {
            return when (tokens[1]) {
                "get", "modify", "remove" -> NBT_PATHS.options("NBT paths")
                "merge" -> NBT_COMPOUNDS.options("NBT compounds", terminal = true, syntax = CompletionSyntax.NBT)
                else -> emptyList()
            }
        }
        if (tokens[1] == "get" && tokens.size == targetEnd + 1) return listOf("1", "0.01").options("scales", terminal = true)
        if (tokens[1] != "modify") return emptyList()
        if (tokens.size == targetEnd + 1) return listOf("append", "insert", "merge", "prepend", "set").options("data operations")
        val operation = tokens[targetEnd + 1]
        val sourceIndex = targetEnd + if (operation == "insert") 3 else 2
        if (tokens.size == sourceIndex) return listOf("from", "string", "value").options("data sources")
        if (tokens.size == sourceIndex + 1 && tokens[sourceIndex] == "value") {
            return NBT_VALUES.options("NBT values", terminal = true, syntax = CompletionSyntax.NBT)
        }
        if (tokens.size == sourceIndex + 1 && tokens[sourceIndex] in setOf("from", "string")) return DATA_TARGETS.options("source targets")
        return emptyList()
    }

    private fun setBlockCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> =
        when (tokens.size) {
            in 1..3 -> COORDINATES.options("block position")
            4 -> environment.blocks.options("blocks")
            5 -> listOf("destroy", "keep", "replace", "strict").options("setblock modes", terminal = true)
            else -> emptyList()
        }

    private fun fillCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> =
        when (tokens.size) {
            in 1..6 -> COORDINATES.options("block positions")
            7 -> environment.blocks.options("blocks")
            8 -> listOf("destroy", "hollow", "keep", "outline", "replace", "strict").options("fill modes")
            9 -> if (tokens[8] == "replace") environment.blocks.options("filter blocks", terminal = true) else emptyList()
            else -> emptyList()
        }

    private fun summonCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> =
        when (tokens.size) {
            1 -> environment.entities.options("entity types")
            in 2..4 -> COORDINATES.options("summon position")
            5 -> NBT_COMPOUNDS.options("entity NBT", terminal = true, syntax = CompletionSyntax.NBT)
            else -> emptyList()
        }

    private fun giveCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> =
        when (tokens.size) {
            1 -> playerTargets(environment)
            2 -> environment.items.options("items")
            3 -> INTEGER_VALUES.options("item counts", terminal = true)
            else -> emptyList()
        }

    private fun clearCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> =
        when (tokens.size) {
            1 -> playerTargets(environment)
            2 -> environment.items.options("items")
            3 -> INTEGER_VALUES.options("maximum counts", terminal = true)
            else -> emptyList()
        }

    private fun tagCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> =
        when (tokens.size) {
            1 -> targetOptions(environment)
            2 -> listOf("add", "list", "remove").options("tag actions")
            3 -> if (tokens[2] == "list") emptyList() else environment.tags.options("tags", terminal = true)
            else -> emptyList()
        }

    private fun tellrawCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> =
        when (tokens.size) {
            1 -> playerTargets(environment)
            2 -> TEXT_COMPONENTS.options("text components", terminal = true, syntax = CompletionSyntax.TEXT_COMPONENT)
            else -> emptyList()
        }

    private fun titleCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> =
        when (tokens.size) {
            1 -> playerTargets(environment)
            2 -> listOf("actionbar", "clear", "reset", "subtitle", "times", "title").options("title actions")
            3 ->
                when (tokens[2]) {
                    "times" -> TICK_VALUES.options("fade-in ticks")
                    "clear", "reset" -> emptyList()
                    else -> TEXT_COMPONENTS.options("text components", terminal = true, syntax = CompletionSyntax.TEXT_COMPONENT)
                }
            4, 5 -> if (tokens[2] == "times") TICK_VALUES.options("title times", terminal = tokens.size == 5) else emptyList()
            else -> emptyList()
        }

    private fun particleCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> =
        when (tokens.size) {
            1 -> environment.particles.withFallback(PARTICLES).options("particles")
            in 2..4 -> COORDINATES.options("position")
            in 5..7 -> listOf("0", "0.25", "0.5", "1").options("spread")
            8 -> listOf("0", "0.02", "0.1", "1").options("speed")
            9 -> listOf("1", "8", "16", "32", "64").options("particle counts")
            10 -> listOf("force", "normal").options("visibility")
            11 -> playerTargets(environment, terminal = true)
            else -> emptyList()
        }

    private fun timeCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> =
        when (tokens.size) {
            1 -> listOf("add", "of", "pause", "query", "rate", "resume", "set").options("time actions")
            2 ->
                when (tokens[1]) {
                    "of" -> environment.worldClocks.options("world clocks")
                    "query" -> (listOf("gametime", "time") + environment.timelines).options("time queries", terminal = true)
                    "rate" -> listOf("1", "20").options("clock rates", terminal = true)
                    "set" -> listOf("day", "midnight", "night", "noon").options("time values", terminal = true)
                    else -> TICK_VALUES.options("time values", terminal = true)
                }
            3 ->
                if (tokens[1] == "of") {
                    listOf("add", "pause", "query", "rate", "resume", "set").options("world clock actions")
                } else {
                    emptyList()
                }
            4 ->
                if (tokens[1] == "of") {
                    when (tokens[3]) {
                        "query" -> (listOf("time") + environment.timelines).options("clock queries", terminal = true)
                        "rate" -> listOf("1", "20").options("clock rates", terminal = true)
                        "set" -> listOf("day", "midnight", "night", "noon").options("time values", terminal = true)
                        "add" -> TICK_VALUES.options("time values", terminal = true)
                        else -> emptyList()
                    }
                } else {
                    emptyList()
                }
            else -> emptyList()
        }

    private fun gameruleCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> =
        when (tokens.size) {
            1 -> (environment.gamerules + COMMON_GAMERULES).distinct().options("gamerules")
            2 -> (BOOLEANS + INTEGER_VALUES).options("gamerule values", terminal = true)
            else -> emptyList()
        }

    private fun teleportCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> {
        if (tokens.size == 1) return targetOrCoordinates(environment, "targets/positions")
        val destinationIndex = if (tokens[1].startsWith("@") || tokens[1] in environment.scoreHolders) 2 else 1
        if (tokens.size == destinationIndex) return targetOrCoordinates(environment, "destination")
        if (tokens[destinationIndex].startsWith("@") || tokens[destinationIndex] in environment.scoreHolders) return emptyList()
        if (tokens.size in destinationIndex + 1..destinationIndex + 2) return COORDINATES.options("destination")
        if (tokens.size == destinationIndex + 3) return (listOf("facing") + ROTATIONS).options("rotation/facing")
        if (tokens.getOrNull(destinationIndex + 3) == "facing") {
            val facingIndex = destinationIndex + 4
            if (tokens.size == facingIndex) return (listOf("entity") + COORDINATES).options("facing target")
            if (tokens[facingIndex] == "entity") {
                if (tokens.size == facingIndex + 1) return targetOptions(environment)
                if (tokens.size == facingIndex + 2) return listOf("eyes", "feet").options("anchors", terminal = true)
            } else if (tokens.size in facingIndex + 1..facingIndex + 2) {
                return COORDINATES.options("facing position", terminal = tokens.size == facingIndex + 2)
            }
        } else if (tokens.size == destinationIndex + 4) {
            return ROTATIONS.options("pitch", terminal = true)
        }
        return emptyList()
    }

    private fun returnCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> {
        if (tokens.size == 1) return listOf("fail", "run", "value").options("return actions")
        return when (tokens[1]) {
            "run" -> commandCandidates(tokens.drop(2), environment)
            "value" -> if (tokens.size == 2) INTEGER_VALUES.options("return values", terminal = true) else emptyList()
            else -> emptyList()
        }
    }

    private fun tickCandidates(tokens: List<String>): List<Option> =
        when (tokens.size) {
            1 -> listOf("freeze", "rate", "sprint", "step", "unfreeze").options("tick actions")
            2 -> if (tokens[1] in setOf("rate", "sprint", "step")) TICK_VALUES.options("tick values", terminal = true) else emptyList()
            else -> emptyList()
        }

    private fun advancementCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> =
        when (tokens.size) {
            1 -> listOf("grant", "revoke", "test").options("advancement actions")
            2 -> playerTargets(environment)
            3 ->
                if (tokens[1] == "test") environment.advancements.options("advancements", terminal = true)
                else listOf("everything", "from", "only", "through", "until").options("advancement modes")
            4 -> environment.advancements.options("advancements", terminal = true)
            else -> emptyList()
        }

    private fun attributeCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> =
        when (tokens.size) {
            1 -> targetOptions(environment)
            2 -> environment.attributes.withFallback(ATTRIBUTES).options("attributes")
            3 -> listOf("base", "get", "modifier").options("attribute actions")
            4 ->
                when (tokens[3]) {
                    "base" -> listOf("get", "reset", "set").options("base actions")
                    "modifier" -> listOf("add", "remove", "value").options("modifier actions")
                    else -> emptyList()
                }
            5 ->
                when {
                    tokens[3] == "base" && tokens[4] in setOf("get", "set") -> listOf("1", "20").options("attribute values", terminal = true)
                    tokens[3] == "modifier" && tokens[4] == "value" -> listOf("get").options("modifier value actions")
                    tokens[3] == "modifier" && tokens[4] in setOf("remove", "value") -> listOf("minecraft:modifier").options("modifier ids")
                    else -> emptyList()
                }
            else -> emptyList()
        }

    private fun bossbarCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> {
        if (tokens.size == 1) return listOf("add", "get", "list", "remove", "set").options("bossbar actions")
        val ids = listOf("minecraft:bossbar").options("bossbars")
        if (tokens.size == 2 && tokens[1] != "list") return ids
        return when {
            tokens.size == 3 && tokens[1] == "add" -> TEXT_COMPONENTS.options("bossbar names", terminal = true, syntax = CompletionSyntax.TEXT_COMPONENT)
            tokens.size == 3 && tokens[1] == "get" -> listOf("max", "players", "value", "visible").options("bossbar fields", terminal = true)
            tokens.size == 3 && tokens[1] == "set" -> listOf("color", "max", "name", "players", "style", "value", "visible").options("bossbar fields")
            tokens.size == 4 && tokens[1] == "set" ->
                when (tokens[3]) {
                    "color" -> BOSSBAR_COLORS.options("bossbar colors", terminal = true)
                    "name" -> TEXT_COMPONENTS.options("bossbar names", terminal = true, syntax = CompletionSyntax.TEXT_COMPONENT)
                    "players" -> playerTargets(environment, terminal = true)
                    "style" -> BOSSBAR_STYLES.options("bossbar styles", terminal = true)
                    "visible" -> BOOLEANS.options("booleans", terminal = true)
                    else -> INTEGER_VALUES.options("bossbar values", terminal = true)
                }
            else -> emptyList()
        }
    }

    private fun cloneCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> =
        when (tokens.size) {
            in 1..9 -> COORDINATES.options("clone positions")
            10 -> listOf("filtered", "masked", "replace").options("clone masks")
            11 -> if (tokens[10] == "filtered") environment.blocks.options("filter blocks") else listOf("force", "move", "normal", "strict").options("clone modes", terminal = true)
            12 -> listOf("force", "move", "normal", "strict").options("clone modes", terminal = true)
            else -> emptyList()
        }

    private fun damageCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> =
        when (tokens.size) {
            1 -> targetOptions(environment)
            2 -> INTEGER_VALUES.options("damage amounts")
            3 -> environment.damageTypes.withFallback(DAMAGE_TYPES).options("damage types")
            4 -> listOf("at", "by").options("damage sources")
            5 -> if (tokens[4] == "by") targetOptions(environment) else COORDINATES.options("damage position")
            6 -> if (tokens[4] == "at") COORDINATES.options("damage position") else listOf("from").options("damage cause")
            7 -> if (tokens[4] == "at") COORDINATES.options("damage position", terminal = true) else if (tokens[6] == "from") targetOptions(environment) else emptyList()
            else -> emptyList()
        }

    private fun effectCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> =
        when (tokens.size) {
            1 -> listOf("clear", "give").options("effect actions")
            2 -> targetOptions(environment)
            3 -> environment.effects.withFallback(EFFECTS).options("effects")
            4, 5 -> INTEGER_VALUES.options(if (tokens.size == 4) "durations" else "amplifiers")
            6 -> BOOLEANS.options("hide particles", terminal = true)
            else -> emptyList()
        }

    private fun enchantCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> =
        when (tokens.size) {
            1 -> playerTargets(environment)
            2 -> environment.enchantments.withFallback(ENCHANTMENTS).options("enchantments")
            3 -> INTEGER_VALUES.options("enchantment levels", terminal = true)
            else -> emptyList()
        }

    private fun experienceCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> =
        when (tokens.size) {
            1 -> listOf("add", "query", "set").options("experience actions")
            2 -> playerTargets(environment)
            3 -> if (tokens[1] == "query") listOf("levels", "points").options("experience units", terminal = true) else INTEGER_VALUES.options("experience amounts")
            4 -> listOf("levels", "points").options("experience units", terminal = true)
            else -> emptyList()
        }

    private fun fillBiomeCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> =
        when (tokens.size) {
            in 1..6 -> COORDINATES.options("biome positions")
            7 -> environment.biomes.withFallback(BIOMES).options("biomes")
            8 -> listOf("replace").options("biome filters")
            9 -> environment.biomes.withFallback(BIOMES).options("filter biomes", terminal = true)
            else -> emptyList()
        }

    private fun forceLoadCandidates(tokens: List<String>): List<Option> =
        when (tokens.size) {
            1 -> listOf("add", "query", "remove").options("forceload actions")
            2 -> if (tokens[1] == "remove") (listOf("all") + COORDINATES).options("chunk positions") else COORDINATES.options("chunk positions")
            3, 4, 5 -> COORDINATES.options("chunk positions", terminal = tokens.size == 5)
            else -> emptyList()
        }

    private fun gameModeCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> =
        when (tokens.size) {
            1 -> GAME_MODES.options("game modes")
            2 -> playerTargets(environment, terminal = true)
            else -> emptyList()
        }

    private fun itemCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> {
        if (tokens.size == 1) return listOf("modify", "replace").options("item actions")
        if (tokens.size == 2) return listOf("block", "entity").options("item targets")
        val entity = tokens[2] == "entity"
        if (tokens.size == 3) return if (entity) targetOptions(environment) else COORDINATES.options("block position")
        if (!entity && tokens.size in 4..5) return COORDINATES.options("block position")
        val slotIndex = if (entity) 4 else 6
        if (tokens.size == slotIndex) return INVENTORY_SLOTS.options("item slots")
        if (tokens.size == slotIndex + 1) {
            return if (tokens[1] == "replace") listOf("from", "with").options("item sources") else listOf("minecraft:modifier").options("item modifiers", terminal = true)
        }
        if (tokens.size == slotIndex + 2 && tokens[1] == "replace" && tokens[slotIndex + 1] == "with") return environment.items.options("items")
        if (tokens.size == slotIndex + 3 && tokens[1] == "replace" && tokens[slotIndex + 1] == "with") return INTEGER_VALUES.options("item counts", terminal = true)
        if (tokens[1] == "replace" && tokens.getOrNull(slotIndex + 1) == "from") {
            val sourceKindIndex = slotIndex + 2
            if (tokens.size == sourceKindIndex) return listOf("block", "entity").options("source targets")
            val sourceIsEntity = tokens.getOrNull(sourceKindIndex) == "entity"
            val sourceTargetIndex = sourceKindIndex + 1
            if (tokens.size == sourceTargetIndex) return if (sourceIsEntity) targetOptions(environment) else COORDINATES.options("source block position")
            if (!sourceIsEntity && tokens.size in sourceTargetIndex + 1..sourceTargetIndex + 2) return COORDINATES.options("source block position")
            val sourceSlotIndex = if (sourceIsEntity) sourceTargetIndex + 1 else sourceTargetIndex + 3
            if (tokens.size == sourceSlotIndex) return INVENTORY_SLOTS.options("source item slots")
            if (tokens.size == sourceSlotIndex + 1) return listOf("minecraft:modifier").options("item modifiers", terminal = true)
        }
        return emptyList()
    }

    private fun playSoundCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> =
        when (tokens.size) {
            1 -> environment.sounds.withFallback(SOUNDS).options("sounds")
            2 -> SOUND_SOURCES.options("sound sources")
            3 -> playerTargets(environment)
            in 4..6 -> COORDINATES.options("sound position")
            in 7..9 -> listOf("0", "0.5", "1").options("volume/pitch/minimum volume", terminal = tokens.size == 9)
            else -> emptyList()
        }

    private fun recipeCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> =
        when (tokens.size) {
            1 -> listOf("give", "take").options("recipe actions")
            2 -> playerTargets(environment)
            3 -> (listOf("*") + environment.recipes).options("recipes", terminal = true)
            else -> emptyList()
        }

    private fun rideCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> =
        when (tokens.size) {
            1 -> targetOptions(environment)
            2 -> listOf("dismount", "mount").options("ride actions", terminal = tokens.getOrNull(1) == "dismount")
            3 -> if (tokens[2] == "mount") targetOptions(environment) else emptyList()
            else -> emptyList()
        }

    private fun rotateCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> =
        when (tokens.size) {
            1 -> targetOptions(environment)
            2 -> (listOf("facing") + ROTATIONS).options("rotation/facing")
            3 -> if (tokens[2] == "facing") targetOrCoordinates(environment, "facing target") else ROTATIONS.options("pitch", terminal = true)
            else -> emptyList()
        }

    private fun scheduleCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> =
        when (tokens.size) {
            1 -> listOf("clear", "function").options("schedule actions")
            2 -> functionIds(environment).options("functions")
            3 -> if (tokens[1] == "function") TICK_VALUES.options("delays") else emptyList()
            4 -> listOf("append", "replace").options("schedule modes", terminal = true)
            else -> emptyList()
        }

    private fun spawnpointCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> =
        when (tokens.size) {
            1 -> playerTargets(environment)
            in 2..4 -> COORDINATES.options("spawn position")
            5 -> ROTATIONS.options("spawn angle", terminal = true)
            else -> emptyList()
        }

    private fun spectateCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> =
        when (tokens.size) {
            1 -> targetOptions(environment)
            2 -> playerTargets(environment, terminal = true)
            else -> emptyList()
        }

    private fun spreadPlayersCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> =
        when (tokens.size) {
            in 1..2 -> COORDINATES.options("center")
            3, 4 -> INTEGER_VALUES.options(if (tokens.size == 3) "spread distance" else "maximum range")
            5 -> BOOLEANS.options("respect teams")
            6 -> targetOptions(environment)
            else -> emptyList()
        }

    private fun teamCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> {
        if (tokens.size == 1) return listOf("add", "empty", "join", "leave", "list", "modify", "remove").options("team actions")
        if (tokens.size == 2) {
            return if (tokens[1] == "leave") playerTargets(environment, terminal = true) else listOf("team").options("teams")
        }
        return when (tokens[1]) {
            "add" -> if (tokens.size == 3) TEXT_COMPONENTS.options("team display names", terminal = true, syntax = CompletionSyntax.TEXT_COMPONENT) else emptyList()
            "join" -> if (tokens.size == 3) playerTargets(environment, terminal = true) else emptyList()
            "modify" ->
                when (tokens.size) {
                    3 -> TEAM_OPTIONS.options("team options")
                    4 ->
                        if (tokens[3] in setOf("displayName", "prefix", "suffix")) {
                            TEXT_COMPONENTS.options("team text components", terminal = true, syntax = CompletionSyntax.TEXT_COMPONENT)
                        } else {
                            (BOOLEANS + GAME_MODES + BOSSBAR_COLORS).options("team option values", terminal = true)
                        }
                    else -> emptyList()
                }
            else -> emptyList()
        }
    }

    private fun triggerCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> =
        when (tokens.size) {
            1 -> environment.objectives.options("objectives")
            2 -> listOf("add", "set").options("trigger actions")
            3 -> INTEGER_VALUES.options("trigger values", terminal = true)
            else -> emptyList()
        }

    private fun worldBorderCandidates(tokens: List<String>): List<Option> =
        when (tokens.size) {
            1 -> listOf("add", "center", "damage", "get", "set", "warning").options("worldborder actions")
            2 ->
                when (tokens[1]) {
                    "damage" -> listOf("amount", "buffer").options("damage fields")
                    "warning" -> listOf("distance", "time").options("warning fields")
                    "center" -> COORDINATES.options("center")
                    "get" -> emptyList()
                    else -> INTEGER_VALUES.options("worldborder values")
                }
            3 -> if (tokens[1] == "center") COORDINATES.options("center", terminal = true) else INTEGER_VALUES.options("worldborder values", terminal = true)
            else -> emptyList()
        }

    private fun simpleCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> {
        if (tokens.size == 1) return simpleRootCandidates(tokens.first(), environment)
        return when (tokens.first()) {
            "datapack" ->
                when (tokens.size) {
                    2 -> if (tokens[1] in setOf("disable", "enable")) listOf("vanilla").options("datapacks") else emptyList()
                    3 -> if (tokens[1] == "enable") listOf("after", "before", "first", "last").options("pack order") else emptyList()
                    else -> emptyList()
                }
            "debug" -> if (tokens.size == 2 && tokens[1] == "function") functionIds(environment).options("functions/tags", terminal = true) else emptyList()
            "help" -> if (tokens.size == 2) environment.roots.options("commands", terminal = true) else emptyList()
            "locate" ->
                if (tokens.size == 2) {
                    when (tokens[1]) {
                        "biome" -> (environment.biomes + environment.biomeTags).withFallback(BIOMES).options("biomes", terminal = true)
                        "poi" -> (environment.pointOfInterestTypes + environment.pointOfInterestTypeTags).options("points of interest", terminal = true)
                        else -> (environment.structures + environment.structureTags).options("structures", terminal = true)
                    }
                } else {
                    emptyList()
                }
            "loot" -> lootCandidates(tokens, environment)
            "place" ->
                if (tokens.size == 2) {
                    when (tokens[1]) {
                        "feature" -> environment.configuredFeatures.options("configured features")
                        "jigsaw" -> environment.templatePools.options("template pools")
                        "structure" -> environment.structures.options("structures")
                        else -> environment.structures.options("structures/templates")
                    }
                } else if (tokens.size in 3..5) {
                    COORDINATES.options("placement position", terminal = tokens.size >= 5)
                } else {
                    emptyList()
                }
            "random" ->
                when (tokens.size) {
                    2 -> if (tokens[1] == "reset") listOf("*").options("random sequences", terminal = true) else listOf("0..10").options("integer ranges")
                    3 -> listOf("minecraft:random").options("random sequences", terminal = true)
                    else -> emptyList()
                }
            "setworldspawn" -> if (tokens.size in 2..3) COORDINATES.options("spawn position") else if (tokens.size == 4) ROTATIONS.options("spawn angle", terminal = true) else emptyList()
            "test" ->
                if (tokens.size == 2 && tokens[1] in setOf("locate", "run", "runmultiple", "verify")) {
                    environment.testInstances.options("test instances", terminal = true)
                } else {
                    emptyList()
                }
            "stopsound" ->
                when (tokens.size) {
                    2 -> SOUND_SOURCES.options("sound sources")
                    3 -> environment.sounds.withFallback(SOUNDS).options("sounds", terminal = true)
                    else -> emptyList()
                }
            "transfer" ->
                when (tokens.size) {
                    2 -> listOf("25565").options("ports")
                    3 -> playerTargets(environment, terminal = true)
                    else -> emptyList()
                }
            "whitelist" -> if (tokens.size == 2 && tokens[1] in setOf("add", "remove")) playerTargets(environment, terminal = true) else emptyList()
            else -> emptyList()
        }
    }

    private fun simpleRootCandidates(
        root: String,
        environment: EngineCompletionEnvironment,
    ): List<Option> {
        SIMPLE_ROOT_CHILDREN[root]?.let { return it.options("subcommands") }
        return when (root) {
            "ban", "deop", "kick", "op", "pardon" -> playerTargets(environment)
            "ban-ip", "pardon-ip" -> listOf("127.0.0.1").options("IP addresses", terminal = true)
            "help" -> environment.roots.options("commands", terminal = true)
            "list" -> listOf("uuids").options("list options", terminal = true)
            "me", "say", "teammsg", "tm" -> listOf("message").options("message", terminal = true)
            "msg", "tell", "w" -> playerTargets(environment)
            "save-all" -> listOf("flush").options("save options", terminal = true)
            "setidletimeout" -> INTEGER_VALUES.options("idle timeout", terminal = true)
            "setworldspawn" -> COORDINATES.options("spawn position")
            "stopsound" -> playerTargets(environment)
            "transfer" -> listOf("localhost").options("server addresses")
            else -> emptyList()
        }
    }

    private fun lootCandidates(
        tokens: List<String>,
        environment: EngineCompletionEnvironment,
    ): List<Option> =
        when (tokens.size) {
            2 ->
                when (tokens[1]) {
                    "give" -> playerTargets(environment)
                    "insert", "spawn" -> COORDINATES.options("loot position")
                    "replace" -> listOf("block", "entity").options("loot targets")
                    else -> emptyList()
                }
            3, 4 -> if (tokens[1] in setOf("insert", "spawn")) COORDINATES.options("loot position") else emptyList()
            else -> emptyList()
        }

    private fun required(
        tokens: List<String>,
        index: Int,
        options: List<Option>,
        next: Int,
    ): ParseStep = if (index >= tokens.size) ParseStep(options = options) else ParseStep(next)

    private fun coordinates(
        tokens: List<String>,
        start: Int,
        count: Int,
        next: Int,
        values: List<String> = COORDINATES,
    ): ParseStep {
        for (index in start until start + count) {
            if (index >= tokens.size) return ParseStep(options = values.options("coordinates"))
        }
        return ParseStep(next)
    }

    private fun coordinatesThen(
        tokens: List<String>,
        start: Int,
        count: Int,
        options: List<Option>,
        next: Int,
    ): ParseStep {
        val position = coordinates(tokens, start, count, start + count)
        return if (position.options != null) position else required(tokens, start + count, options, next)
    }

    private fun functionIds(environment: EngineCompletionEnvironment): List<String> =
        (environment.functions + environment.functionTags.map { "#$it" }).distinct().sorted()

    private fun playerTargets(
        environment: EngineCompletionEnvironment,
        terminal: Boolean = false,
    ): List<Option> =
        (environment.scoreHolders + listOf("@a", "@n", "@p", "@r", "@s", "Steve"))
            .distinct()
            .options("players/selectors", terminal = terminal, syntax = CompletionSyntax.TARGET)

    private fun targetValues(environment: EngineCompletionEnvironment): List<String> =
        (environment.scoreHolders + listOf("@a", "@e", "@n", "@p", "@r", "@s", "Steve") + environment.entities).distinct()

    private fun targetOptions(
        environment: EngineCompletionEnvironment,
        terminal: Boolean = false,
    ): List<Option> = targetValues(environment).options("entities/selectors", terminal = terminal, syntax = CompletionSyntax.TARGET)

    private fun targetOrCoordinates(
        environment: EngineCompletionEnvironment,
        description: String,
    ): List<Option> = targetValues(environment).options(description, syntax = CompletionSyntax.TARGET) + COORDINATES.options(description)

    private fun scoreTargets(environment: EngineCompletionEnvironment): List<Option> =
        (environment.scoreHolders + listOf("*", "#value", "@a", "@e", "@n", "@p", "@r", "@s", "Steve"))
            .distinct()
            .options("score holders", syntax = CompletionSyntax.TARGET)

    private fun Iterable<String>.options(
        description: String,
        group: String = "value",
        terminal: Boolean = false,
        syntax: CompletionSyntax = CompletionSyntax.PLAIN,
    ): List<Option> = map { Option(it, description, group, appendSpace = !terminal, syntax = syntax) }

    private fun List<String>.withFallback(fallback: List<String>): List<String> = ifEmpty { fallback }

    internal data class Option(
        val value: String,
        val description: String,
        val group: String,
        val appendSpace: Boolean,
        val syntax: CompletionSyntax,
    )

    private data class ParseStep(
        val next: Int = 0,
        val options: List<Option>? = null,
    )

    private data class CompletionInput(
        val committed: List<String>,
        val prefix: String,
        val start: Int,
        val end: Int,
    ) {
        companion object {
            fun parse(
                source: String,
                cursor: Int,
            ): CompletionInput {
                val end = cursor.coerceIn(0, source.length)
                val tokens = mutableListOf<Token>()
                var tokenStart = -1
                var quote: Char? = null
                var escaped = false
                var depth = 0
                for (index in 0 until end) {
                    val char = source[index]
                    if (tokenStart < 0 && !char.isWhitespace()) tokenStart = index
                    when {
                        escaped -> escaped = false
                        char == '\\' && quote != null -> escaped = true
                        quote != null -> if (char == quote) quote = null
                        char == '"' || char == '\'' -> quote = char
                        char == '{' || char == '[' || char == '(' -> depth += 1
                        char == '}' || char == ']' || char == ')' -> depth = (depth - 1).coerceAtLeast(0)
                        char.isWhitespace() && depth == 0 && tokenStart >= 0 -> {
                            tokens += Token(source.substring(tokenStart, index), tokenStart)
                            tokenStart = -1
                        }
                    }
                }
                if (tokenStart >= 0) tokens += Token(source.substring(tokenStart, end), tokenStart)
                val hasPrefix = tokenStart >= 0
                return CompletionInput(
                    committed = if (hasPrefix) tokens.dropLast(1).map(Token::value) else tokens.map(Token::value),
                    prefix = if (hasPrefix) tokens.last().value else "",
                    start = if (hasPrefix) tokens.last().start else end,
                    end = end,
                )
            }
        }
    }

    private data class Token(
        val value: String,
        val start: Int,
    )

    private const val MAX_SUGGESTIONS = 100
    private val BOOLEANS = listOf("false", "true")
    private val COORDINATES = listOf("0", "^", "~")
    private val ROTATIONS = listOf("0", "~")
    private val INTEGER_VALUES = listOf("-1", "0", "1", "10")
    private val TICK_VALUES = listOf("1", "10", "20", "100")
    private val NBT_PATHS = listOf("path", "Items", "Pos[0]")
    private val NBT_COMPOUNDS = listOf("{}")
    private val NBT_VALUES = listOf("{}", "[]", "\"\"", "0", "false", "true")
    private val DATA_TARGETS = listOf("block", "entity", "storage")
    private val NBT_NUMBER_TYPES = listOf("byte", "double", "float", "int", "long", "short")
    private val DIMENSIONS = listOf("minecraft:overworld", "minecraft:the_end", "minecraft:the_nether")
    private val BIOMES = listOf("minecraft:desert", "minecraft:forest", "minecraft:plains")
    private val HEIGHTMAPS = listOf("motion_blocking", "motion_blocking_no_leaves", "ocean_floor", "world_surface")
    private val EXECUTE_RELATIONS = listOf("attacker", "controller", "leasher", "origin", "owner", "passengers", "target", "vehicle")
    private val EXECUTE_SUBCOMMANDS =
        listOf("align", "anchored", "as", "at", "facing", "if", "in", "on", "positioned", "rotated", "run", "store", "summon", "unless")
    private val EXECUTE_CONDITIONS = listOf("biome", "block", "blocks", "data", "dimension", "entity", "function", "loaded", "predicate", "score")
    private val STORE_TARGETS = listOf("block", "bossbar", "entity", "score", "storage")
    private val SCORE_ACTIONS = listOf("add", "display", "enable", "get", "list", "operation", "remove", "reset", "set")
    private val SCORE_OPERATIONS = listOf("%=", "*=", "+=", "-=", "/=", "<", "=", ">", "><")
    private val SCORE_COMPARISONS = listOf("<", "<=", "=", ">", ">=", "matches")
    private val SCOREBOARD_SLOTS = listOf("below_name", "list", "sidebar", "sidebar.team.blue", "sidebar.team.red")
    private val TEXT_COMPONENTS = listOf("{\"text\":\"\"}", "{\"text\":\"Ready\",\"color\":\"green\"}")
    private val PARTICLES = listOf("minecraft:block", "minecraft:cloud", "minecraft:crit", "minecraft:dust", "minecraft:end_rod", "minecraft:flame", "minecraft:heart", "minecraft:item", "minecraft:portal", "minecraft:smoke")
    private val ATTRIBUTES =
        listOf(
            "minecraft:armor",
            "minecraft:attack_damage",
            "minecraft:generic.armor",
            "minecraft:generic.attack_damage",
            "minecraft:generic.max_health",
            "minecraft:generic.movement_speed",
            "minecraft:max_health",
            "minecraft:movement_speed",
            "minecraft:scale",
        )
    private val BOSSBAR_COLORS = listOf("blue", "green", "pink", "purple", "red", "white", "yellow")
    private val BOSSBAR_STYLES = listOf("notched_6", "notched_10", "notched_12", "notched_20", "progress")
    private val DAMAGE_TYPES = listOf("minecraft:fall", "minecraft:generic", "minecraft:in_fire", "minecraft:magic", "minecraft:mob_attack", "minecraft:player_attack")
    private val EFFECTS = listOf("minecraft:haste", "minecraft:regeneration", "minecraft:resistance", "minecraft:speed", "minecraft:strength")
    private val ENCHANTMENTS = listOf("minecraft:efficiency", "minecraft:protection", "minecraft:sharpness", "minecraft:unbreaking")
    private val GAME_MODES = listOf("adventure", "creative", "spectator", "survival")
    private val INVENTORY_SLOTS = listOf("armor.chest", "armor.feet", "armor.head", "armor.legs", "container.0", "hotbar.0", "hotbar.1", "hotbar.2", "weapon.mainhand", "weapon.offhand")
    private val SOUNDS = listOf("minecraft:block.note_block.harp", "minecraft:entity.experience_orb.pickup", "minecraft:entity.player.levelup")
    private val SOUND_SOURCES = listOf("ambient", "block", "hostile", "master", "music", "neutral", "player", "record", "voice", "weather")
    private val TEAM_OPTIONS = listOf("collisionRule", "color", "deathMessageVisibility", "displayName", "friendlyFire", "nametagVisibility", "prefix", "seeFriendlyInvisibles", "suffix")
    private val COMMON_GAMERULES = listOf("doDaylightCycle", "doMobSpawning", "doWeatherCycle", "keepInventory", "randomTickSpeed", "sendCommandFeedback")
    private val SIMPLE_ROOT_CHILDREN =
        mapOf(
            "banlist" to listOf("ips", "players"),
            "datapack" to listOf("disable", "enable", "list"),
            "debug" to listOf("function", "start", "stop"),
            "defaultgamemode" to listOf("adventure", "creative", "spectator", "survival"),
            "difficulty" to listOf("easy", "hard", "normal", "peaceful"),
            "jfr" to listOf("start", "stop"),
            "locate" to listOf("biome", "poi", "structure"),
            "loot" to listOf("give", "insert", "replace", "spawn"),
            "perf" to listOf("start", "stop"),
            "place" to listOf("feature", "jigsaw", "structure", "template"),
            "publish" to listOf("false", "true"),
            "random" to listOf("reset", "roll", "value"),
            "test" to
                listOf(
                    "clearall",
                    "clearthat",
                    "clearthese",
                    "create",
                    "locate",
                    "pos",
                    "resetclosest",
                    "resetthat",
                    "resetthese",
                    "run",
                    "runclosest",
                    "runfailed",
                    "runmultiple",
                    "runthat",
                    "runthese",
                    "stop",
                    "verify",
                ),
            "whitelist" to listOf("add", "list", "off", "on", "reload", "remove"),
        )
}
