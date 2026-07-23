package moe.afox.dpsandbox.engine

data class EngineBlock(
    val x: Int,
    val y: Int,
    val z: Int,
    val id: String,
    val properties: Map<String, String> = emptyMap(),
)

data class EngineEntity(
    val uuid: String,
    val type: String,
    var x: Double,
    var y: Double,
    var z: Double,
    var yaw: Double = 0.0,
    var pitch: Double = 0.0,
    val tags: MutableSet<String> = linkedSetOf(),
    val data: EngineDataObject = EngineDataObject(),
    var display: EngineDisplayState? = null,
    var ageTicks: Long = 0,
    var displayAnimation: EngineDisplayAnimation? = null,
) {
    fun renderedDisplay(): EngineDisplayState? = displayAnimation?.rendered(ageTicks) ?: display

    fun renderedPose(): EngineDisplayPose =
        displayAnimation?.teleport?.rendered(ageTicks) ?: EngineDisplayPose(x, y, z, yaw, pitch)

    fun updateDisplay(next: EngineDisplayState?) {
        val current = renderedDisplay()
        display = next
        if (next == null) {
            displayAnimation = null
            return
        }
        val duration = data.intValue("interpolation_duration").coerceAtLeast(0)
        val delay = data.intValue("start_interpolation").takeIf { it > 0 } ?: 0
        val teleport = displayAnimation?.teleport
        displayAnimation = EngineDisplayAnimation(current ?: next, next, ageTicks + delay, duration, teleport)
    }

    fun scheduleTeleport(previous: EngineDisplayPose) {
        val animation = displayAnimation ?: return
        val duration = data.intValue("teleport_duration").coerceIn(0, 59)
        animation.teleport =
            if (duration == 0) {
                null
            } else {
                val renderedPrevious = animation.teleport?.rendered(ageTicks) ?: previous
                EngineDisplayTeleport(renderedPrevious, EngineDisplayPose(x, y, z, yaw, pitch), ageTicks, duration)
            }
    }
}

data class EngineDisplayAnimation(
    var previous: EngineDisplayState,
    var target: EngineDisplayState,
    var startTick: Long,
    var duration: Int,
    var teleport: EngineDisplayTeleport? = null,
) {
    fun progress(tick: Long): Double =
        when {
            duration <= 0 -> 1.0
            tick <= startTick -> 0.0
            else -> ((tick - startTick).toDouble() / duration).coerceIn(0.0, 1.0)
        }

    fun rendered(tick: Long): EngineDisplayState = previous.interpolate(target, progress(tick))
}

data class EngineDisplayTeleport(
    val previous: EngineDisplayPose,
    val target: EngineDisplayPose,
    val startTick: Long,
    val duration: Int,
) {
    fun rendered(tick: Long): EngineDisplayPose {
        val progress = if (duration <= 0) 1.0 else ((tick - startTick).toDouble() / duration).coerceIn(0.0, 1.0)
        return previous.interpolate(target, progress)
    }
}

data class EngineDisplayPose(
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Double,
    val pitch: Double,
) {
    fun interpolate(
        target: EngineDisplayPose,
        progress: Double,
    ): EngineDisplayPose =
        EngineDisplayPose(
            lerp(x, target.x, progress),
            lerp(y, target.y, progress),
            lerp(z, target.z, progress),
            lerp(yaw, target.yaw, progress),
            lerp(pitch, target.pitch, progress),
        )
}

data class EngineOutput(
    val command: String,
    val channel: String,
    val text: String,
    val targets: List<String> = emptyList(),
    val payloadJson: String? = null,
)

/** Low-level browser input mirrored by the JVM sandbox's PlayerInput model. */
data class EnginePlayerInput(
    val player: String,
    val device: String,
    val code: String,
    val action: String,
    val x: Double? = null,
    val y: Double? = null,
    val tick: Long = 0,
) {
    fun json(): String =
        JsonText.obj(
            "player" to JsonText.quote(player),
            "device" to JsonText.quote(device),
            "code" to JsonText.quote(code),
            "action" to JsonText.quote(action),
            "x" to (x?.toString() ?: "null"),
            "y" to (y?.toString() ?: "null"),
            "tick" to tick.toString(),
        )
}

data class EngineDiagnostic(
    val line: Int,
    val from: Int,
    val to: Int,
    val severity: String,
    val code: String,
    val message: String,
    val command: String,
) {
    fun json(): String =
        JsonText.obj(
            "line" to line.toString(),
            "from" to from.toString(),
            "to" to to.toString(),
            "severity" to JsonText.quote(severity),
            "code" to JsonText.quote(code),
            "message" to JsonText.quote(message),
            "command" to JsonText.quote(command),
        )
}

class EngineFailure(
    val code: String,
    override val message: String,
    val line: Int = 1,
    val command: String = "",
) : RuntimeException(message) {
    fun diagnostic(): EngineDiagnostic = EngineDiagnostic(line, 0, command.length, "error", code, message, command)
}

data class EngineWorld(
    var gameTime: Long = 0,
    var dayTime: Long = 0,
    var weather: String = "clear",
    var seed: Long = 0,
    val blocks: MutableMap<String, EngineBlock> = linkedMapOf(),
    val objectives: MutableMap<String, String> = linkedMapOf(),
    val scores: MutableMap<String, MutableMap<String, Int>> = linkedMapOf(),
    val storages: MutableMap<String, String> = linkedMapOf(),
    val entities: MutableList<EngineEntity> = mutableListOf(),
    val inventories: MutableMap<String, MutableMap<String, Int>> = linkedMapOf(),
    val gamerules: MutableMap<String, String> = linkedMapOf(),
    val playerInputs: MutableList<EnginePlayerInput> = mutableListOf(),
) {
    fun copyWorld(): EngineWorld =
        EngineWorld(
            gameTime = gameTime,
            dayTime = dayTime,
            weather = weather,
            seed = seed,
            blocks = blocks.toMutableMap(),
            objectives = objectives.toMutableMap(),
            scores = scores.mapValuesTo(linkedMapOf()) { (_, values) -> values.toMutableMap() },
            storages = storages.toMutableMap(),
            entities =
                entities.mapTo(mutableListOf()) {
                    it.copy(
                        tags = it.tags.toMutableSet(),
                        data = it.data.deepCopy(),
                        display = it.display?.copy(
                            transformation = it.display!!.transformation.toList(),
                            blockProperties = it.display!!.blockProperties.toMap(),
                        ),
                        displayAnimation = it.displayAnimation?.deepCopy(),
                    )
                },
            inventories = inventories.mapValuesTo(linkedMapOf()) { (_, values) -> values.toMutableMap() },
            gamerules = gamerules.toMutableMap(),
            playerInputs = playerInputs.toMutableList(),
        )

    fun sections(): Map<String, String> =
        linkedMapOf(
            "time" to JsonText.obj("gameTime" to gameTime.toString(), "dayTime" to dayTime.toString()),
            "weather" to JsonText.quote(weather),
            "blocks" to JsonText.array(blocks.entries.sortedBy { it.key }.map { blockJson(it.value) }),
            "scores" to scoreJson(),
            "storage" to JsonText.obj(*storages.entries.sortedBy { it.key }.map { (key, value) -> key to JsonText.quote(value) }.toTypedArray()),
            "entities" to JsonText.array(entities.sortedBy { it.uuid }.map(::entityJson)),
            "players" to inventoryJson(),
            "gamerules" to JsonText.obj(*gamerules.entries.sortedBy { it.key }.map { (key, value) -> key to JsonText.quote(value) }.toTypedArray()),
            "playerInputs" to JsonText.array(playerInputs.map(EnginePlayerInput::json)),
        )

    fun snapshotJson(): String {
        val sections = sections()
        return JsonText.obj(
            "gameTime" to gameTime.toString(),
            "dayTime" to dayTime.toString(),
            "weather" to JsonText.quote(weather),
            "seed" to seed.toString(),
            "objectives" to JsonText.obj(*objectives.entries.sortedBy { it.key }.map { (key, value) -> key to JsonText.quote(value) }.toTypedArray()),
            "scores" to sections.getValue("scores"),
            "storage" to sections.getValue("storage"),
            "entities" to sections.getValue("entities"),
            "blocks" to sections.getValue("blocks"),
            "players" to sections.getValue("players"),
            "gamerules" to sections.getValue("gamerules"),
            "playerInputs" to sections.getValue("playerInputs"),
        )
    }

    fun recordInput(input: EnginePlayerInput) {
        playerInputs += input
        if (playerInputs.size > MAX_RECORDED_INPUTS) playerInputs.removeAt(0)
    }

    fun advanceTicks(count: Int) {
        require(count >= 0) { "Tick count must not be negative" }
        gameTime += count
        dayTime = (dayTime + count) % 24_000
        entities.forEach { it.ageTicks += count }
    }

    private fun scoreJson(): String =
        JsonText.obj(
            *scores.entries.sortedBy { it.key }.map { (objective, values) ->
                objective to JsonText.obj(*values.entries.sortedBy { it.key }.map { (target, value) -> target to value.toString() }.toTypedArray())
            }.toTypedArray(),
        )

    private fun inventoryJson(): String =
        JsonText.obj(
            *inventories.entries.sortedBy { it.key }.map { (player, values) ->
                player to JsonText.obj(*values.entries.sortedBy { it.key }.map { (item, count) -> item to count.toString() }.toTypedArray())
            }.toTypedArray(),
        )

    private fun blockJson(value: EngineBlock): String =
        JsonText.obj(
            "x" to value.x.toString(),
            "y" to value.y.toString(),
            "z" to value.z.toString(),
            "id" to JsonText.quote(value.id),
        )

    private fun entityJson(value: EngineEntity): String {
        val pose = value.renderedPose()
        val display = value.renderedDisplay()
        return JsonText.obj(
            "uuid" to JsonText.quote(value.uuid),
            "type" to JsonText.quote(value.type),
            "x" to pose.x.toString(),
            "y" to pose.y.toString(),
            "z" to pose.z.toString(),
            "yaw" to pose.yaw.toString(),
            "pitch" to pose.pitch.toString(),
            "tags" to JsonText.array(value.tags.sorted().map(JsonText::quote)),
            "special" to (display?.specialJson(pose.x, pose.y, pose.z, pose.yaw, pose.pitch, value.type) ?: "null"),
        )
    }

    companion object {
        private const val MAX_RECORDED_INPUTS = 1_024
    }
}

private fun EngineDisplayAnimation.deepCopy(): EngineDisplayAnimation =
    EngineDisplayAnimation(
        previous = previous.copy(transformation = previous.transformation.toList(), blockProperties = previous.blockProperties.toMap()),
        target = target.copy(transformation = target.transformation.toList(), blockProperties = target.blockProperties.toMap()),
        startTick = startTick,
        duration = duration,
        teleport = teleport?.let { it.copy(previous = it.previous.copy(), target = it.target.copy()) },
    )

private fun EngineDataObject.intValue(name: String): Int = (values[name] as? EngineDataNumber)?.value?.toInt() ?: 0

private fun lerp(
    from: Double,
    to: Double,
    progress: Double,
): Double = from + (to - from) * progress.coerceIn(0.0, 1.0)
