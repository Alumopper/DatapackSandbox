package moe.afox.dpsandbox.engine

class EngineSession(
    val version: String,
    val limits: BrowserLimits = BrowserLimits(),
    private var world: EngineWorld = EngineWorld(),
) {
    private val functions = linkedMapOf<String, String>()
    private val functionTags = linkedMapOf<String, List<String>>()
    private var commandRoots: Set<String> = emptySet()
    private var blockRegistry: List<String> = emptyList()
    private var itemRegistry: List<String> = emptyList()
    private var entityRegistry: List<String> = emptyList()
    private var beforeSections: Map<String, String>? = null
    private val operationOutputs = mutableListOf<EngineOutput>()
    private val operationTrace = mutableListOf<String>()
    private var operationCommands = 0
    private var operationInterrupted = false
    private var uuidSequence = 0L
    private val checkpoints = linkedMapOf<String, EngineCheckpoint>()

    fun configure(
        roots: Iterable<String>,
        blocks: Iterable<String>,
        items: Iterable<String>,
        entities: Iterable<String>,
    ) {
        commandRoots = roots.toSet()
        blockRegistry = blocks.distinct().sorted()
        itemRegistry = items.distinct().sorted()
        entityRegistry = entities.distinct().sorted()
    }

    fun upsertFunction(
        id: String,
        source: String,
    ) {
        require(RESOURCE_ID.matches(id)) { "Invalid function id '$id'" }
        require(source.encodeToByteArray().size <= limits.maximumCellBytes) { "Function source exceeds the cell-size limit" }
        functions[id] = source
    }

    fun clearFunctions() {
        functions.clear()
        functionTags.clear()
    }

    fun setFunctionTag(
        id: String,
        values: List<String>,
    ) {
        require(RESOURCE_ID.matches(id)) { "Invalid function tag id '$id'" }
        values.forEach { value ->
            val normalized = value.removePrefix("#")
            require(RESOURCE_ID.matches(normalized)) { "Invalid function tag value '$value'" }
        }
        functionTags[id] = values.toList()
    }

    /** Runs #minecraft:load after the Worker has rebuilt the effective pack stack. */
    fun runLoadJson(): String = runLifecycleOperation(listOf("#minecraft:load"), advanceTime = false)

    /** Advances time and executes #minecraft:tick plus an optional user function once per tick. */
    fun runSimulationTicksJson(
        count: Int,
        tickFunction: String?,
    ): String {
        require(count in 0..limits.maximumCommands) { "Tick count exceeds the browser mutation budget" }
        val functionsToRun = buildList {
            add("#minecraft:tick")
            tickFunction?.takeIf(String::isNotBlank)?.let { add(resource(it)) }
        }
        return runLifecycleOperation(functionsToRun, advanceTime = true, count = count)
    }

    /** Records input without applying vanilla movement, collision, AI, or physics. */
    fun dispatchInputJson(
        player: String,
        device: String,
        code: String,
        action: String,
        x: Double?,
        y: Double?,
    ): String {
        require(PLAYER_NAME.matches(player)) { "Invalid player name '$player'" }
        require(device in INPUT_DEVICES) { "Unknown input device '$device'" }
        require(action in INPUT_ACTIONS) { "Unknown input action '$action'" }
        require(code.isNotBlank() && code.length <= 64) { "Input code must contain 1-64 characters" }
        world.inventories.getOrPut(player) { linkedMapOf() }
        if (world.entities.none { it.uuid == "player:$player" }) {
            world.entities += EngineEntity("player:$player", "minecraft:player", 0.0, 0.0, 0.0)
        }
        val input = EnginePlayerInput(player, device, code, action, x, y, world.gameTime)
        world.recordInput(input)
        return JsonText.obj(
            "event" to input.json(),
            "trace" to JsonText.array(listOf(JsonText.quote("player.input $player $device $code $action"))),
            "snapshot" to world.snapshotJson(),
        )
    }

    fun beginExecution() {
        beforeSections = world.sections()
        operationOutputs.clear()
        operationTrace.clear()
        operationCommands = 0
        operationInterrupted = false
    }

    fun interrupt() {
        operationInterrupted = true
    }

    fun executeLine(
        rawLine: String,
        lineNumber: Int,
    ) {
        val command = rawLine.removePrefix("\uFEFF").trim().removePrefix("/")
        if (command.isBlank() || command.startsWith('#')) return
        try {
            executeCommand(command, lineNumber)
        } catch (failure: EngineFailure) {
            throw EngineFailure(failure.code, failure.message, lineNumber, command)
        } catch (error: IllegalArgumentException) {
            throw EngineFailure("COMMAND_ERROR", error.message ?: "Invalid command", lineNumber, command)
        }
    }

    fun executeLineSafeJson(
        rawLine: String,
        lineNumber: Int,
    ): String =
        try {
            executeLine(rawLine, lineNumber)
            "{\"ok\":true}"
        } catch (failure: EngineFailure) {
            JsonText.obj(
                "ok" to "false",
                "error" to
                    JsonText.obj(
                        "code" to JsonText.quote(failure.code),
                        "message" to JsonText.quote(failure.message),
                        "line" to failure.line.toString(),
                        "command" to JsonText.quote(failure.command),
                    ),
            )
        }

    fun finishExecutionJson(): String {
        val before = beforeSections ?: throw EngineFailure("INVALID_REQUEST", "Execution was not started")
        val after = world.sections()
        val diffs =
            (before.keys + after.keys).distinct().mapNotNull { key ->
                val old = before[key]
                val new = after[key]
                if (old == new) null else JsonText.obj("path" to JsonText.quote(key), "before" to (old ?: "null"), "after" to (new ?: "null"))
            }
        val outputs = operationOutputs.map { output ->
            JsonText.obj(
                "tick" to world.gameTime.toString(),
                "command" to JsonText.quote(output.command),
                "channel" to JsonText.quote(output.channel),
                "text" to JsonText.quote(output.text),
                "rawText" to JsonText.quote(output.text),
                "targets" to JsonText.array(output.targets.map(JsonText::quote)),
                "payload" to (output.payloadJson ?: "null"),
            )
        }
        val result =
            JsonText.obj(
                "commands" to operationCommands.toString(),
                "outputs" to JsonText.array(outputs),
                "snapshotDiffs" to JsonText.array(diffs),
                "trace" to JsonText.array(operationTrace.map(JsonText::quote)),
                "snapshot" to world.snapshotJson(),
            )
        beforeSections = null
        check(result.encodeToByteArray().size <= limits.maximumOutputBytes) { "Execution result exceeds the output-size limit" }
        return result
    }

    fun checkJson(source: String): String {
        validateSourceSize(source)
        val scratch = EngineSession(version, limits, world.copyWorld())
        scratch.functions.putAll(functions)
        scratch.configure(commandRoots, blockRegistry, itemRegistry, entityRegistry)
        val diagnostics = mutableListOf<EngineDiagnostic>()
        scratch.beginExecution()
        source.lineSequence().forEachIndexed { index, raw ->
            runCatching { scratch.executeLine(raw, index + 1) }
                .exceptionOrNull()
                ?.let { error ->
                    val failure = error as? EngineFailure ?: EngineFailure("COMMAND_ERROR", error.message ?: "Invalid command", index + 1, raw)
                    diagnostics += failure.diagnostic()
                }
        }
        return JsonText.array(diagnostics.map(EngineDiagnostic::json))
    }

    fun completionJson(
        source: String,
        cursor: Int,
    ): String {
        val bounded = cursor.coerceIn(0, source.length)
        val before = source.substring(0, bounded)
        val start = before.indexOfLast(Char::isWhitespace).let { if (it < 0) 0 else it + 1 }
        val prefix = before.substring(start)
        val words = tokenize(before)
        val wordIndex = if (before.lastOrNull()?.isWhitespace() == true) words.size else (words.size - 1).coerceAtLeast(0)
        val candidates =
            when {
                words.size <= 1 && !before.endsWith(' ') -> commandRoots.sorted()
                words.firstOrNull() == "setblock" && wordIndex == 4 -> blockRegistry
                words.firstOrNull() == "summon" && wordIndex == 1 -> entityRegistry
                words.firstOrNull() == "give" && wordIndex == 2 -> itemRegistry
                words.firstOrNull() == "function" && wordIndex == 1 -> functions.keys.sorted()
                words.firstOrNull() == "weather" -> listOf("clear", "rain", "thunder")
                words.firstOrNull() == "time" -> listOf("add", "query", "set")
                words.firstOrNull() == "scoreboard" && words.size == 2 -> listOf("objectives", "players")
                words.firstOrNull() == "particle" ->
                    when (wordIndex) {
                        1 -> PARTICLE_TYPES
                        in 2..4 -> listOf("~", "0")
                        in 5..7 -> listOf("0", "0.25", "0.5", "1")
                        8 -> listOf("0", "0.02", "0.1", "1")
                        9 -> listOf("1", "8", "16", "32", "64")
                        10 -> listOf("normal", "force")
                        11 -> listOf("@a", "@s", "Steve")
                        else -> emptyList()
                    }
                words.firstOrNull() == "tellraw" ->
                    when (wordIndex) {
                        1 -> listOf("@a", "@s", "Steve")
                        2 -> TEXT_COMPONENT_TEMPLATES
                        else -> emptyList()
                    }
                words.firstOrNull() == "title" ->
                    when (wordIndex) {
                        1 -> listOf("@a", "@s", "Steve")
                        2 -> listOf("clear", "reset", "title", "subtitle", "actionbar", "times")
                        3 -> if (words.getOrNull(2) == "times") listOf("10", "20", "60", "70") else TEXT_COMPONENT_TEMPLATES
                        4, 5 -> if (words.getOrNull(2) == "times") listOf("10", "20", "60", "70") else emptyList()
                        else -> emptyList()
                    }
                else -> emptyList()
            }
        val filtered = candidates.filter { it.startsWith(prefix, ignoreCase = true) }.take(100)
        return JsonText.array(
            filtered.map { value ->
                JsonText.obj(
                    "value" to JsonText.quote(value),
                    "description" to JsonText.quote(if (words.size <= 1) "Minecraft command" else "sandbox value"),
                    "group" to JsonText.quote(if (words.size <= 1) "command" else "value"),
                    "appendSpace" to "true",
                    "start" to start.toString(),
                    "end" to bounded.toString(),
                    "behavior" to JsonText.quote("modeled"),
                )
            },
        )
    }

    fun snapshotJson(): String = world.snapshotJson()

    /** Saves or replaces a named, in-memory copy of all modeled world state. */
    fun saveCheckpoint(name: String): String {
        validateCheckpointName(name)
        require(beforeSections == null) { "A checkpoint cannot be changed during execution" }
        if (name !in checkpoints && checkpoints.size >= limits.maximumCheckpoints) {
            throw EngineFailure("CHECKPOINT_LIMIT", "Session already has ${limits.maximumCheckpoints} checkpoints")
        }
        val snapshot = world.snapshotJson()
        val bytes = snapshot.encodeToByteArray().size
        if (bytes > limits.maximumCheckpointBytes) {
            throw EngineFailure(
                "CHECKPOINT_SIZE_LIMIT",
                "Checkpoint is $bytes bytes; maximum is ${limits.maximumCheckpointBytes}",
            )
        }
        checkpoints[name] = EngineCheckpoint(world.copyWorld(), uuidSequence)
        return snapshot
    }

    /** Restores a named checkpoint without consuming it, so it can be reused. */
    fun restoreCheckpoint(name: String): String {
        validateCheckpointName(name)
        require(beforeSections == null) { "A checkpoint cannot be restored during execution" }
        val checkpoint = checkpoints[name] ?: throw EngineFailure("CHECKPOINT_NOT_FOUND", "Unknown checkpoint '$name'")
        world = checkpoint.world.copyWorld()
        uuidSequence = checkpoint.uuidSequence
        clearOperation()
        return world.snapshotJson()
    }

    fun deleteCheckpoint(name: String): Boolean {
        validateCheckpointName(name)
        require(beforeSections == null) { "A checkpoint cannot be changed during execution" }
        return checkpoints.remove(name) != null
    }

    fun checkpointNamesJson(): String = JsonText.array(checkpoints.keys.sorted().map(JsonText::quote))

    fun saveCheckpointResultJson(name: String): String = checkpointResultJson { JsonText.quote(saveCheckpoint(name)) }

    fun restoreCheckpointResultJson(name: String): String = checkpointResultJson { JsonText.quote(restoreCheckpoint(name)) }

    fun deleteCheckpointResultJson(name: String): String = checkpointResultJson { deleteCheckpoint(name).toString() }

    fun renderBlocks(): List<EngineBlock> = world.blocks.values.toList()

    fun renderEntities(): List<EngineEntity> =
        world.entities.map { entity ->
            val pose = entity.renderedPose()
            entity.copy(
                x = pose.x,
                y = pose.y,
                z = pose.z,
                yaw = pose.yaw,
                pitch = pose.pitch,
                display = entity.renderedDisplay(),
            )
        }

    fun renderEntityCount(): Int = world.entities.size

    fun dayTime(): Long = world.dayTime

    fun weather(): String = world.weather

    fun reset() {
        world = EngineWorld()
        checkpoints.clear()
        uuidSequence = 0
        clearOperation()
    }

    private fun runLifecycleOperation(
        functionIds: List<String>,
        advanceTime: Boolean,
        count: Int = 1,
    ): String {
        beginExecution()
        try {
            repeat(count) {
                if (advanceTime) world.advanceTicks(1)
                functionIds.forEach { runFunctionReference(it, 1, linkedSetOf()) }
            }
            return finishExecutionJson()
        } catch (error: Throwable) {
            clearOperation()
            throw error
        }
    }

    fun validateSourceSize(source: String) {
        val bytes = source.encodeToByteArray().size
        if (bytes > limits.maximumCellBytes) throw EngineFailure("CELL_TOO_LARGE", "Cell is $bytes bytes; maximum is ${limits.maximumCellBytes}")
    }

    private fun clearOperation() {
        beforeSections = null
        operationOutputs.clear()
        operationTrace.clear()
        operationCommands = 0
        operationInterrupted = false
    }

    private fun validateCheckpointName(name: String) {
        if (!CHECKPOINT_NAME.matches(name)) {
            throw EngineFailure(
                "CHECKPOINT_NAME_INVALID",
                "Checkpoint name must be 1-64 ASCII letters, digits, '.', '_' or '-'",
            )
        }
    }

    private fun checkpointResultJson(action: () -> String): String =
        try {
            JsonText.obj("ok" to "true", "value" to action())
        } catch (failure: EngineFailure) {
            JsonText.obj(
                "ok" to "false",
                "error" to
                    JsonText.obj(
                        "code" to JsonText.quote(failure.code),
                        "message" to JsonText.quote(failure.message),
                    ),
            )
        } catch (failure: IllegalArgumentException) {
            JsonText.obj(
                "ok" to "false",
                "error" to
                    JsonText.obj(
                        "code" to JsonText.quote("CHECKPOINT_FAILED"),
                        "message" to JsonText.quote(failure.message ?: "Checkpoint operation failed"),
                    ),
            )
        }

    private fun executeCommand(
        command: String,
        line: Int,
    ) {
        if (operationInterrupted) throw EngineFailure("INTERRUPTED", "Cell execution was interrupted", line, command)
        operationCommands += 1
        if (operationCommands > limits.maximumCommands) {
            throw EngineFailure("COMMAND_LIMIT", "Execution exceeded the ${limits.maximumCommands} command limit", line, command)
        }
        val tokens = tokenize(command)
        val root = tokens.firstOrNull()?.lowercase() ?: return
        operationTrace += command
        when (root) {
            "setblock" -> setBlock(tokens)
            "fill" -> fill(tokens)
            "scoreboard" -> scoreboard(tokens)
            "say", "me" -> output(root, "chat", tokens.drop(1).joinToString(" "))
            "tellraw" -> tellraw(tokens)
            "title" -> title(tokens)
            "particle" -> particle(tokens)
            "summon" -> summon(tokens)
            "kill" -> kill(tokens)
            "tag" -> tag(tokens)
            "data" -> data(tokens)
            "time" -> time(tokens)
            "weather" -> weather(tokens)
            "gamerule" -> gamerule(tokens)
            "give" -> give(tokens)
            "clear" -> clear(tokens)
            "tp", "teleport" -> teleport(tokens)
            "function" -> runFunction(tokens, line)
            "execute" -> executeNested(tokens, line)
            "return" -> executeReturn(tokens, line)
            "tick" -> tick(tokens)
            "seed" -> output(command, "seed", world.seed.toString())
            else -> {
                if (root !in commandRoots) throw EngineFailure("COMMAND_ERROR", "Unknown command '$root'", line, command)
                output(command, "warning", "Command '$root' is recognized but only approximately modeled in the browser runtime")
            }
        }
    }

    private fun setBlock(tokens: List<String>) {
        require(tokens.size >= 5) { "Usage: setblock <x> <y> <z> <block>" }
        val x = coordinate(tokens[1])
        val y = coordinate(tokens[2])
        val z = coordinate(tokens[3])
        val id = resource(tokens[4].substringBefore('[').substringBefore('{'))
        val properties = blockProperties(tokens[4])
        val key = blockKey(x, y, z)
        if (id == "minecraft:air") world.blocks.remove(key) else world.blocks[key] = EngineBlock(x, y, z, id, properties)
    }

    private fun fill(tokens: List<String>) {
        require(tokens.size >= 8) { "Usage: fill <from> <to> <block>" }
        val x1 = coordinate(tokens[1])
        val y1 = coordinate(tokens[2])
        val z1 = coordinate(tokens[3])
        val x2 = coordinate(tokens[4])
        val y2 = coordinate(tokens[5])
        val z2 = coordinate(tokens[6])
        val id = resource(tokens[7].substringBefore('[').substringBefore('{'))
        val properties = blockProperties(tokens[7])
        val count = (kotlin.math.abs(x2 - x1) + 1L) * (kotlin.math.abs(y2 - y1) + 1L) * (kotlin.math.abs(z2 - z1) + 1L)
        require(count <= limits.maximumCommands.toLong()) { "Fill volume $count exceeds the browser mutation budget" }
        for (x in minOf(x1, x2)..maxOf(x1, x2)) {
            for (y in minOf(y1, y2)..maxOf(y1, y2)) {
                for (z in minOf(z1, z2)..maxOf(z1, z2)) {
                    val key = blockKey(x, y, z)
                    if (id == "minecraft:air") world.blocks.remove(key) else world.blocks[key] = EngineBlock(x, y, z, id, properties)
                }
            }
        }
    }

    private fun scoreboard(tokens: List<String>) {
        require(tokens.size >= 3) { "Usage: scoreboard <objectives|players> ..." }
        when (tokens[1]) {
            "objectives" -> {
                require(tokens.size >= 4) { "Scoreboard objective action is incomplete" }
                when (tokens[2]) {
                    "add" -> world.objectives[tokens[3]] = tokens.getOrElse(4) { "dummy" }
                    "remove" -> {
                        world.objectives.remove(tokens[3]) ?: throw EngineFailure("COMMAND_ERROR", "Unknown scoreboard objective '${tokens[3]}'")
                        world.scores.remove(tokens[3])
                    }
                    "list" -> output(tokens.joinToString(" "), "scoreboard", world.objectives.keys.sorted().joinToString())
                    else -> throw EngineFailure("COMMAND_ERROR", "Unsupported scoreboard objectives action '${tokens[2]}'")
                }
            }
            "players" -> scoreboardPlayers(tokens)
            else -> throw EngineFailure("COMMAND_ERROR", "Unknown scoreboard section '${tokens[1]}'")
        }
    }

    private fun scoreboardPlayers(tokens: List<String>) {
        require(tokens.size >= 5) { "Scoreboard player action is incomplete" }
        val action = tokens[2]
        val target = tokens[3]
        val objective = tokens[4]
        require(objective in world.objectives) { "Unknown scoreboard objective '$objective'" }
        val scores = world.scores.getOrPut(objective) { linkedMapOf() }
        when (action) {
            "set" -> scores[target] = tokens.getOrNull(5)?.toIntOrNull() ?: error("Score value must be an integer")
            "add" -> scores[target] = (scores[target] ?: 0) + (tokens.getOrNull(5)?.toIntOrNull() ?: error("Score value must be an integer"))
            "remove" -> scores[target] = (scores[target] ?: 0) - (tokens.getOrNull(5)?.toIntOrNull() ?: error("Score value must be an integer"))
            "reset" -> scores.remove(target)
            "get" -> output(tokens.joinToString(" "), "scoreboard", (scores[target] ?: 0).toString())
            else -> throw EngineFailure("COMMAND_ERROR", "Unsupported scoreboard players action '$action'")
        }
    }

    private fun summon(tokens: List<String>) {
        require(tokens.size >= 2) { "Usage: summon <entity> [x y z]" }
        val type = resource(tokens[1])
        var cursor = 2
        val hasPosition = tokens.getOrNull(cursor)?.startsWith('{') == false && tokens.size >= cursor + 3
        val x = if (hasPosition) coordinateDouble(tokens[cursor++]) else 0.0
        val y = if (hasPosition) coordinateDouble(tokens[cursor++]) else 0.0
        val z = if (hasPosition) coordinateDouble(tokens[cursor++]) else 0.0
        val data =
            tokens.getOrNull(cursor)?.let { raw ->
                require(raw.startsWith('{')) { "Summon entity data must be an SNBT object" }
                EngineSnbtParser(raw).parseObject()
            } ?: EngineDataObject()
        val rotation = (data.values["Rotation"] as? EngineDataArray)?.values.orEmpty()
        val yaw = rotation.getOrNull(0)?.numberValue() ?: 0.0
        val pitch = rotation.getOrNull(1)?.numberValue() ?: 0.0
        val tags =
            (data.values["Tags"] as? EngineDataArray)
                ?.values
                ?.mapTo(linkedSetOf()) { it.stringValue() }
                ?: linkedSetOf()
        uuidSequence += 1
        val display = EngineDisplayState.from(type, data)
        val entity =
            EngineEntity(
                uuid = "00000000-0000-0000-0000-${uuidSequence.toString().padStart(12, '0')}",
                type = type,
                x = x,
                y = y,
                z = z,
                yaw = yaw,
                pitch = pitch,
                tags = tags,
                data = data,
                display = display,
            )
        entity.updateDisplay(display)
        world.entities += entity
    }

    private fun kill(tokens: List<String>) {
        val target = tokens.getOrElse(1) { "@s" }
        if (target == "@e" || target.startsWith("@e[")) world.entities.clear() else world.entities.removeAll { it.uuid == target || it.type == resource(target) }
    }

    private fun tag(tokens: List<String>) {
        require(tokens.size >= 4) { "Usage: tag <targets> <add|remove> <name>" }
        val targets = if (tokens[1].startsWith("@e")) world.entities else world.entities.filter { it.uuid == tokens[1] }
        when (tokens[2]) {
            "add" -> targets.forEach { it.tags += tokens[3] }
            "remove" -> targets.forEach { it.tags -= tokens[3] }
            else -> throw EngineFailure("COMMAND_ERROR", "Unknown tag action '${tokens[2]}'")
        }
    }

    private fun data(tokens: List<String>) {
        require(tokens.size >= 4) { "Data operation is incomplete" }
        val action = tokens[1]
        if (tokens[2] == "entity") {
            dataEntity(action, tokens)
            return
        }
        require(tokens[2] == "storage") { "Browser runtime models data operations on storage and display entities" }
        val id = resource(tokens[3])
        when (action) {
            "merge", "modify" -> world.storages[id] = tokens.drop(4).joinToString(" ").ifBlank { "{}" }
            "remove" -> world.storages.remove(id)
            "get" -> output(tokens.joinToString(" "), "data", world.storages[id] ?: "{}")
            else -> throw EngineFailure("COMMAND_ERROR", "Unsupported data action '$action'")
        }
    }

    private fun dataEntity(
        action: String,
        tokens: List<String>,
    ) {
        val targets = selectEntities(tokens[3])
        require(targets.isNotEmpty()) { "Entity selector '${tokens[3]}' matched no entities" }
        when (action) {
            "merge" -> {
                val update = EngineSnbtParser(tokens.drop(4).joinToString(" ")).parseObject()
                targets.forEach { entity ->
                    val before = EngineDisplayPose(entity.x, entity.y, entity.z, entity.yaw, entity.pitch)
                    entity.data.merge(update)
                    (entity.data.values["Pos"] as? EngineDataArray)?.values?.takeIf { it.size == 3 }?.let { position ->
                        entity.x = position[0].numberValue()
                        entity.y = position[1].numberValue()
                        entity.z = position[2].numberValue()
                    }
                    (entity.data.values["Rotation"] as? EngineDataArray)?.values?.takeIf { it.size == 2 }?.let { rotation ->
                        entity.yaw = rotation[0].numberValue()
                        entity.pitch = rotation[1].numberValue()
                    }
                    entity.updateDisplay(EngineDisplayState.from(entity.type, entity.data))
                    if (before != EngineDisplayPose(entity.x, entity.y, entity.z, entity.yaw, entity.pitch)) {
                        entity.scheduleTeleport(before)
                    }
                }
            }
            "get" -> output(tokens.joinToString(" "), "data", targets.first().data.json())
            else -> throw EngineFailure("COMMAND_ERROR", "Unsupported browser entity data action '$action'")
        }
    }

    private fun time(tokens: List<String>) {
        require(tokens.size >= 3) { "Usage: time <set|add|query> <value>" }
        when (tokens[1]) {
            "set" -> world.dayTime = parseTime(tokens[2])
            "add" -> world.dayTime = (world.dayTime + parseTime(tokens[2])) % 24_000
            "query" -> output(tokens.joinToString(" "), "time", world.dayTime.toString())
            else -> throw EngineFailure("COMMAND_ERROR", "Unknown time action '${tokens[1]}'")
        }
    }

    private fun weather(tokens: List<String>) {
        val value = tokens.getOrNull(1) ?: error("Weather type is required")
        require(value in setOf("clear", "rain", "thunder")) { "Unknown weather '$value'" }
        world.weather = value
    }

    private fun gamerule(tokens: List<String>) {
        require(tokens.size >= 2) { "Gamerule name is required" }
        if (tokens.size == 2) output(tokens.joinToString(" "), "gamerule", world.gamerules[tokens[1]] ?: "false") else world.gamerules[tokens[1]] = tokens[2]
    }

    private fun give(tokens: List<String>) {
        require(tokens.size >= 3) { "Usage: give <player> <item> [count]" }
        val inventory = world.inventories.getOrPut(tokens[1]) { linkedMapOf() }
        val item = resource(tokens[2].substringBefore('[').substringBefore('{'))
        inventory[item] = (inventory[item] ?: 0) + (tokens.getOrNull(3)?.toIntOrNull() ?: 1)
    }

    private fun clear(tokens: List<String>) {
        val player = tokens.getOrElse(1) { "@s" }
        if (tokens.size < 3) world.inventories[player]?.clear() else world.inventories[player]?.remove(resource(tokens[2]))
    }

    private fun teleport(tokens: List<String>) {
        require(tokens.size >= 5) { "Usage: tp <entity> <x> <y> <z>" }
        selectEntities(tokens[1]).forEach { entity ->
            val before = EngineDisplayPose(entity.x, entity.y, entity.z, entity.yaw, entity.pitch)
            entity.x = coordinateDouble(tokens[2])
            entity.y = coordinateDouble(tokens[3])
            entity.z = coordinateDouble(tokens[4])
            entity.scheduleTeleport(before)
        }
    }

    private fun tick(tokens: List<String>) {
        val count =
            when (tokens.getOrNull(1)) {
                null, "step" -> tokens.getOrNull(2)?.toIntOrNull() ?: 1
                else -> tokens.getOrNull(1)?.toIntOrNull() ?: 1
            }
        require(count in 0..limits.maximumCommands) { "Tick count exceeds the browser mutation budget" }
        world.advanceTicks(count)
    }

    private fun selectEntities(selector: String): List<EngineEntity> {
        if (!selector.startsWith("@e")) return world.entities.filter { it.uuid == selector }
        val tag = Regex("(?:^|[,\\[])tag=([^,\\]]+)").find(selector)?.groupValues?.get(1)
        val type = Regex("(?:^|[,\\[])type=([^,\\]]+)").find(selector)?.groupValues?.get(1)?.let(::resource)
        val limit = Regex("(?:^|[,\\[])limit=(\\d+)").find(selector)?.groupValues?.get(1)?.toIntOrNull()
        return world.entities
            .asSequence()
            .filter { tag == null || tag in it.tags }
            .filter { type == null || it.type == type }
            .let { values -> if (limit == null) values else values.take(limit) }
            .toList()
    }

    private fun runFunction(
        tokens: List<String>,
        line: Int,
    ) {
        val reference = tokens.getOrNull(1) ?: error("Function id is required")
        runFunctionReference(if (reference.startsWith('#')) "#${resource(reference.drop(1))}" else resource(reference), line, linkedSetOf())
    }

    private fun runFunctionReference(
        reference: String,
        line: Int,
        stack: MutableSet<String>,
    ) {
        if (!stack.add(reference)) throw EngineFailure("COMMAND_ERROR", "Recursive function tag '$reference'", line, reference)
        try {
            if (reference.startsWith('#')) {
                functionTags[reference.drop(1)].orEmpty().forEach { child ->
                    val normalized = if (child.startsWith('#')) "#${resource(child.drop(1))}" else resource(child)
                    runFunctionReference(normalized, line, stack)
                }
                return
            }
            val source = functions[reference] ?: throw EngineFailure("MISSING_RESOURCE", "Unknown function '$reference'")
            source.lineSequence().forEach { raw ->
                val command = raw.trim().removePrefix("/")
                if (command.isNotBlank() && !command.startsWith('#')) executeCommand(command, line)
            }
        } finally {
            stack.remove(reference)
        }
    }

    private fun executeNested(
        tokens: List<String>,
        line: Int,
    ) {
        val run = tokens.indexOf("run")
        require(run >= 0 && run < tokens.lastIndex) { "execute requires a run subcommand" }
        executeCommand(tokens.drop(run + 1).joinToString(" "), line)
    }

    private fun executeReturn(
        tokens: List<String>,
        line: Int,
    ) {
        if (tokens.getOrNull(1) == "run") executeCommand(tokens.drop(2).joinToString(" "), line)
    }

    private fun output(
        command: String,
        channel: String,
        text: String,
        targets: List<String> = emptyList(),
        payloadJson: String? = null,
    ) {
        if (operationOutputs.size >= limits.maximumOutputEvents) throw EngineFailure("OUTPUT_LIMIT", "Execution exceeded the output-event limit")
        operationOutputs += EngineOutput(command, channel, text, targets, payloadJson)
    }

    private fun tellraw(tokens: List<String>) {
        require(tokens.size >= 3) { "tellraw requires targets and a JSON text component" }
        val component = tokens.drop(2).joinToString(" ")
        output("tellraw", "chat", plainTextComponent(component), targetNames(tokens[1]), component)
    }

    private fun title(tokens: List<String>) {
        require(tokens.size >= 3) { "title requires targets and an action" }
        val targets = targetNames(tokens[1])
        when (val action = tokens[2]) {
            "clear", "reset" -> output("title $action", "title", action, targets)
            "times" -> {
                require(tokens.size == 6) { "title times requires fadeIn, stay, and fadeOut ticks" }
                val fadeIn = tokens[3].toIntOrNull()?.takeIf { it >= 0 } ?: error("Invalid title fadeIn '${tokens[3]}'")
                val stay = tokens[4].toIntOrNull()?.takeIf { it >= 0 } ?: error("Invalid title stay '${tokens[4]}'")
                val fadeOut = tokens[5].toIntOrNull()?.takeIf { it >= 0 } ?: error("Invalid title fadeOut '${tokens[5]}'")
                val payload =
                    JsonText.obj(
                        "fadeIn" to fadeIn.toString(),
                        "stay" to stay.toString(),
                        "fadeOut" to fadeOut.toString(),
                    )
                output("title times", "title", "$fadeIn $stay $fadeOut", targets, payload)
            }
            "title", "subtitle", "actionbar" -> {
                require(tokens.size >= 4) { "title $action requires a JSON text component" }
                val component = tokens.drop(3).joinToString(" ")
                output("title $action", "title", plainTextComponent(component), targets, component)
            }
            else -> error("Unsupported title action '$action'")
        }
    }

    private fun particle(tokens: List<String>) {
        require(tokens.size >= 2) { "particle requires a particle name" }
        require(tokens.size <= 12) { "particle has too many arguments" }
        require(tokens.size !in 3..4 && tokens.size !in 6..7) {
            "Particle position and delta arguments must each contain three coordinates"
        }
        val name = resource(tokens[1])
        val x = tokens.getOrNull(2)?.let(::coordinateDouble) ?: 0.0
        val y = tokens.getOrNull(3)?.let(::coordinateDouble) ?: 0.0
        val z = tokens.getOrNull(4)?.let(::coordinateDouble) ?: 0.0
        val deltaX = tokens.getOrNull(5)?.toDoubleOrNull() ?: 0.0
        val deltaY = tokens.getOrNull(6)?.toDoubleOrNull() ?: 0.0
        val deltaZ = tokens.getOrNull(7)?.toDoubleOrNull() ?: 0.0
        val speed = tokens.getOrNull(8)?.toDoubleOrNull() ?: 0.0
        require(speed >= 0.0) { "Particle speed must not be negative" }
        val count = tokens.getOrNull(9)?.toIntOrNull() ?: 0
        require(count in 0..MAX_VIEWPORT_PARTICLES_PER_COMMAND) {
            "Particle count must be between 0 and $MAX_VIEWPORT_PARTICLES_PER_COMMAND"
        }
        val mode = tokens.getOrNull(10) ?: "normal"
        require(mode == "normal" || mode == "force") { "Particle mode must be 'normal' or 'force'" }
        val targets = tokens.getOrNull(11)?.let(::targetNames).orEmpty()
        val payload =
            JsonText.obj(
                "particle" to JsonText.quote(name),
                "x" to x.toString(),
                "y" to y.toString(),
                "z" to z.toString(),
                "deltaX" to deltaX.toString(),
                "deltaY" to deltaY.toString(),
                "deltaZ" to deltaZ.toString(),
                "speed" to speed.toString(),
                "count" to count.toString(),
                "renderCount" to (if (count == 0) 1 else count).toString(),
                "mode" to JsonText.quote(mode),
                "viewerSelector" to (tokens.getOrNull(11)?.let(JsonText::quote) ?: "null"),
                "viewers" to JsonText.array(targets.map(JsonText::quote)),
            )
        output("particle", "visual", name, targets, payload)
    }

    private fun targetNames(raw: String): List<String> =
        when (raw) {
            "@a", "@e" -> world.inventories.keys.sorted()
            "@p", "@n", "@s" -> world.inventories.keys.firstOrNull()?.let(::listOf).orEmpty()
            else -> listOf(raw)
        }

    private fun plainTextComponent(raw: String): String {
        val match = TEXT_COMPONENT_TEXT.find(raw) ?: return raw.trim().trim('"')
        return match.groupValues[1]
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\\", "\\")
    }

    private fun parseTime(value: String): Long =
        when (value) {
            "day" -> 1_000
            "noon" -> 6_000
            "night" -> 13_000
            "midnight" -> 18_000
            else -> value.removeSuffix("t").toLongOrNull() ?: error("Invalid time '$value'")
        }

    private fun coordinate(value: String): Int =
        when {
            value == "~" -> 0
            value.startsWith('~') -> value.drop(1).toDoubleOrNull()?.toInt() ?: error("Invalid coordinate '$value'")
            else -> value.toDoubleOrNull()?.toInt() ?: error("Invalid coordinate '$value'")
        }

    private fun coordinateDouble(value: String): Double =
        when {
            value == "~" -> 0.0
            value.startsWith('~') -> value.drop(1).toDoubleOrNull() ?: error("Invalid coordinate '$value'")
            else -> value.toDoubleOrNull() ?: error("Invalid coordinate '$value'")
        }

    private fun resource(value: String): String {
        val normalized = if (':' in value) value else "minecraft:$value"
        require(RESOURCE_ID.matches(normalized)) { "Invalid resource location '$value'" }
        return normalized
    }

    private fun blockKey(x: Int, y: Int, z: Int): String = "$x,$y,$z"

    private fun blockProperties(token: String): Map<String, String> {
        val start = token.indexOf('[')
        if (start < 0) return emptyMap()
        val end = token.indexOf(']', start + 1)
        if (end < 0) return emptyMap()
        return token.substring(start + 1, end).split(',').mapNotNull { raw ->
            val name = raw.substringBefore('=', "").trim()
            val value = raw.substringAfter('=', "").trim()
            if (name.isEmpty() || value.isEmpty()) null else name to value
        }.toMap()
    }

    companion object {
        private val RESOURCE_ID = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")
        private val CHECKPOINT_NAME = Regex("[A-Za-z0-9._-]{1,64}")
        private val PLAYER_NAME = Regex("[A-Za-z0-9_]{1,16}")
        private val INPUT_DEVICES = setOf("keyboard", "mouse", "touch")
        private val INPUT_ACTIONS = setOf("press", "release", "click", "move", "scroll")
        private val TEXT_COMPONENT_TEXT = Regex("\"text\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        private const val MAX_VIEWPORT_PARTICLES_PER_COMMAND = 4_096
        private val TEXT_COMPONENT_TEMPLATES = listOf("{\"text\":\"\"}", "{\"text\":\"Ready\",\"color\":\"green\"}")
        private val PARTICLE_TYPES =
            listOf(
                "minecraft:flame",
                "minecraft:small_flame",
                "minecraft:smoke",
                "minecraft:large_smoke",
                "minecraft:cloud",
                "minecraft:crit",
                "minecraft:end_rod",
                "minecraft:portal",
                "minecraft:happy_villager",
                "minecraft:heart",
                "minecraft:soul_fire_flame",
                "minecraft:dust",
                "minecraft:block",
                "minecraft:item",
            )

        fun tokenize(command: String): List<String> {
            val result = mutableListOf<String>()
            val current = StringBuilder()
            var quote: Char? = null
            var escaped = false
            var depth = 0
            command.forEach { char ->
                when {
                    escaped -> {
                        current.append(char)
                        escaped = false
                    }
                    char == '\\' && quote != null -> {
                        current.append(char)
                        escaped = true
                    }
                    quote != null -> {
                        current.append(char)
                        if (char == quote) quote = null
                    }
                    char == '"' || char == '\'' -> {
                        quote = char
                        current.append(char)
                    }
                    char == '{' || char == '[' || char == '(' -> {
                        depth += 1
                        current.append(char)
                    }
                    char == '}' || char == ']' || char == ')' -> {
                        depth = (depth - 1).coerceAtLeast(0)
                        current.append(char)
                    }
                    char.isWhitespace() && depth == 0 -> {
                        if (current.isNotEmpty()) {
                            result += current.toString()
                            current.clear()
                        }
                    }
                    else -> current.append(char)
                }
            }
            if (quote != null || depth != 0) throw EngineFailure("COMMAND_ERROR", "Unclosed quote or bracket")
            if (current.isNotEmpty()) result += current.toString()
            return result
        }
    }
}

private data class EngineCheckpoint(
    val world: EngineWorld,
    val uuidSequence: Long,
)
