package moe.afox.dpsandbox.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.UUID
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Runtime and validation support for datapack-oriented special entities. */
internal object SpecialEntitySupport {
    private val blockDisplay = ResourceLocation("minecraft", "block_display")
    private val itemDisplay = ResourceLocation("minecraft", "item_display")
    private val textDisplay = ResourceLocation("minecraft", "text_display")
    private val armorStand = ResourceLocation("minecraft", "armor_stand")
    private val marker = ResourceLocation("minecraft", "marker")
    private val interaction = ResourceLocation("minecraft", "interaction")

    private val displayTypes = setOf(blockDisplay, itemDisplay, textDisplay)
    private val displayKeys =
        setOf(
            "transformation",
            "interpolation_duration",
            "start_interpolation",
            "teleport_duration",
            "billboard",
            "brightness",
            "view_range",
            "shadow_radius",
            "shadow_strength",
            "width",
            "height",
            "glow_color_override",
        )
    private val armorStandKeys =
        setOf(
            "Invisible",
            "Marker",
            "Small",
            "ShowArms",
            "NoBasePlate",
            "DisabledSlots",
            "Pose",
        )
    private val interactionKeys = setOf("width", "height", "response", "attack", "interaction")
    private val poseParts = setOf("Head", "Body", "LeftArm", "RightArm", "LeftLeg", "RightLeg")
    private val billboardValues = setOf("fixed", "vertical", "horizontal", "center")
    private val itemDisplayValues =
        setOf(
            "none",
            "thirdperson_lefthand",
            "thirdperson_righthand",
            "firstperson_lefthand",
            "firstperson_righthand",
            "head",
            "gui",
            "ground",
            "fixed",
        )

    fun isDisplay(type: ResourceLocation): Boolean = type in displayTypes

    fun isItemDisplay(type: ResourceLocation): Boolean = type == itemDisplay

    fun isInteraction(type: ResourceLocation): Boolean = type == interaction

    fun isNonLivingSpecial(type: ResourceLocation): Boolean = type in displayTypes || type == marker || type == interaction

    fun allowedKeys(type: ResourceLocation): Set<String> =
        when (type) {
            blockDisplay -> displayKeys + "block_state"
            itemDisplay -> displayKeys + setOf("item", "item_display")
            textDisplay ->
                displayKeys +
                    setOf(
                        "text",
                        "line_width",
                        "background",
                        "text_opacity",
                        "shadow",
                        "see_through",
                        "default_background",
                        "alignment",
                    )
            armorStand -> armorStandKeys
            marker -> setOf("data")
            interaction -> interactionKeys
            else -> emptySet()
        }

    fun addDefaults(
        entity: SandboxEntity,
        json: JsonObject,
    ) {
        when (entity.type) {
            in displayTypes -> {
                json.addIfMissing("transformation", DisplayTransformation.identity().toJson())
                json.addPropertyIfMissing("interpolation_duration", 0)
                json.addPropertyIfMissing("start_interpolation", 0)
                json.addPropertyIfMissing("teleport_duration", 0)
                json.addPropertyIfMissing("billboard", "fixed")
                json.addPropertyIfMissing("view_range", 1.0)
                json.addPropertyIfMissing("shadow_radius", 0.0)
                json.addPropertyIfMissing("shadow_strength", 1.0)
                json.addPropertyIfMissing("width", 0.0)
                json.addPropertyIfMissing("height", 0.0)
                json.addPropertyIfMissing("glow_color_override", 0)
                when (entity.type) {
                    blockDisplay ->
                        json.addIfMissing(
                            "block_state",
                            JsonObject().also { it.addProperty("Name", "minecraft:air") },
                        )
                    itemDisplay -> {
                        json.addIfMissing("item", JsonObject())
                        json.addPropertyIfMissing("item_display", "none")
                    }
                    textDisplay -> {
                        json.addPropertyIfMissing("text", "")
                        json.addPropertyIfMissing("line_width", 200)
                        json.addPropertyIfMissing("background", 0x40000000)
                        json.addPropertyIfMissing("text_opacity", 255)
                        json.addPropertyIfMissing("shadow", false)
                        json.addPropertyIfMissing("see_through", false)
                        json.addPropertyIfMissing("default_background", false)
                        json.addPropertyIfMissing("alignment", "center")
                    }
                }
            }
            armorStand -> {
                armorStandKeys.filterNot { it == "Pose" }.forEach { key ->
                    if (key == "DisabledSlots") json.addPropertyIfMissing(key, 0) else json.addPropertyIfMissing(key, false)
                }
                json.addIfMissing(
                    "Pose",
                    JsonObject().also { pose ->
                        pose.add("Head", vectorJson(0.0, 0.0, 0.0))
                        pose.add("Body", vectorJson(0.0, 0.0, 0.0))
                        pose.add("LeftArm", vectorJson(-10.0, 0.0, -10.0))
                        pose.add("RightArm", vectorJson(-15.0, 0.0, 10.0))
                        pose.add("LeftLeg", vectorJson(-1.0, 0.0, -1.0))
                        pose.add("RightLeg", vectorJson(1.0, 0.0, 1.0))
                    },
                )
            }
            marker -> json.addIfMissing("data", JsonObject())
            interaction -> {
                json.addPropertyIfMissing("width", 1.0)
                json.addPropertyIfMissing("height", 1.0)
                json.addPropertyIfMissing("response", false)
            }
        }
    }

    fun validate(
        entity: SandboxEntity,
        nbt: JsonObject,
        location: SourceLocation?,
    ) {
        when (entity.type) {
            in displayTypes -> validateDisplay(entity.type, nbt, location)
            armorStand -> validateArmorStand(nbt, location)
            marker -> nbt.get("data")?.requireObject("marker data", location)
            interaction -> validateInteraction(nbt, location)
        }
    }

    fun syncAfterNbtWrite(
        entity: SandboxEntity,
        updated: JsonObject,
        beforePosition: Position,
        beforeYaw: Double,
        beforePitch: Double,
    ) {
        if (!isDisplay(entity.type)) return
        val next = DisplayInterpolatedValues.fromNbt(updated, entity.type, null)
        val runtime = entity.displayRuntime
        if (runtime == null) {
            entity.displayRuntime = DisplayRuntimeState(next, next, entity.ageTicks, 0)
        } else if (next != runtime.target) {
            val previous = runtime.rendered(entity.ageTicks)
            val duration = updated.intValue("interpolation_duration", 0).coerceAtLeast(0)
            val delay = updated.intValue("start_interpolation", 0).takeIf { it > 0 } ?: 0
            runtime.previous = previous
            runtime.target = next
            runtime.startTick = entity.ageTicks + delay
            runtime.duration = duration
        }
        if (entity.position != beforePosition || entity.yaw != beforeYaw || entity.pitch != beforePitch) {
            scheduleDisplayTeleport(entity, beforePosition, beforeYaw, beforePitch)
        }
    }

    fun scheduleDisplayTeleport(
        entity: SandboxEntity,
        beforePosition: Position,
        beforeYaw: Double,
        beforePitch: Double,
    ) {
        if (!isDisplay(entity.type)) return
        val runtime = entity.displayRuntime ?: return
        val duration = entity.nbt.intValue("teleport_duration", 0).coerceIn(0, 59)
        if (duration == 0) {
            runtime.teleport = null
            return
        }
        val fallback = DisplayPose(beforePosition, beforeYaw, beforePitch)
        val previous = runtime.renderedPose(entity.ageTicks, fallback)
        runtime.teleport =
            DisplayTeleportState(
                previous = previous,
                target = DisplayPose(entity.position, entity.yaw, entity.pitch),
                startTick = entity.ageTicks,
                duration = duration,
            )
    }

    fun snapshot(entity: SandboxEntity): JsonObject? =
        when (entity.type) {
            in displayTypes -> displaySnapshot(entity)
            armorStand -> armorStandSnapshot(entity)
            marker -> hitboxSnapshot(entity, 0.0, 0.0, interactable = false, attackable = false)
            interaction -> interactionSnapshot(entity)
            else -> null
        }

    fun isDamageImmune(entity: SandboxEntity): Boolean =
        entity.nbt.booleanValue("Invulnerable", false) ||
            entity.type in displayTypes ||
            entity.type == marker ||
            entity.type == interaction ||
            (entity.type == armorStand && entity.nbt.booleanValue("Marker", false))

    fun validatePlayerAction(
        entity: SandboxEntity,
        attack: Boolean,
        location: SourceLocation? = null,
    ) {
        val action = if (attack) "attack" else "interaction"
        when {
            entity.type in displayTypes || entity.type == marker ->
                throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Entity ${entity.type} has no $action hitbox", location)
            entity.type == armorStand && entity.nbt.booleanValue("Marker", false) ->
                throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Marker armor stand has no $action hitbox", location)
            entity.type == interaction -> {
                val width = entity.nbt.doubleValue("width", 1.0)
                val height = entity.nbt.doubleValue("height", 1.0)
                if (width <= 0.0 || height <= 0.0) {
                    throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Interaction entity has a zero-sized $action hitbox", location)
                }
            }
        }
    }

    fun recordInteractionAction(
        entity: SandboxEntity,
        player: SandboxPlayer,
        tick: Long,
        attack: Boolean,
    ): Boolean? {
        if (entity.type != interaction) return null
        entity.nbt.add(
            if (attack) "attack" else "interaction",
            JsonObject().also { action ->
                action.add("player", uuidToIntArray(player.uuid))
                action.addProperty("timestamp", tick)
            },
        )
        return entity.nbt.booleanValue("response", false)
    }

    fun relation(
        entity: SandboxEntity?,
        relation: String,
        world: SandboxWorld,
    ): SandboxEntity? {
        if (entity?.type != interaction) return null
        val key =
            when (relation) {
                "attacker" -> "attack"
                "target" -> "interaction"
                else -> return null
            }
        val playerValue = entity.nbt.getAsJsonObject(key)?.get("player") ?: return null
        val uuid = uuidFromValue(playerValue) ?: return null
        return world.entities.firstOrNull { it.uuid == uuid }
    }

    fun isInteractionEvent(type: String): Boolean = type in setOf("entity_interacted", "player_interacted_with_entity")

    fun isAttackEvent(type: String): Boolean =
        type in setOf("entity_attacked", "player_attacked_entity", "attack_entity", "player_hurt_entity")

    fun itemDisplayItem(entity: SandboxEntity): ItemStack? = entity.nbt.getAsJsonObject("item")?.let(::itemStackFromNbtJson)

    fun setItemDisplayItem(
        entity: SandboxEntity,
        item: ItemStack?,
    ) {
        if (item == null || item.count <= 0 || item.id == ResourceLocation("minecraft", "air")) {
            entity.nbt.add("item", JsonObject())
        } else {
            entity.nbt.add("item", item.toNbtJson())
        }
    }

    private fun validateDisplay(
        type: ResourceLocation,
        nbt: JsonObject,
        location: SourceLocation?,
    ) {
        nbt.get("transformation")?.let { DisplayTransformation.parse(it, location) }
        nbt.validateNonNegativeInt("interpolation_duration", location)
        nbt.validateInt("start_interpolation", location)
        nbt.get("teleport_duration")?.let {
            val value = it.requireInt("display teleport_duration", location)
            if (value !in 0..59) inputError("display teleport_duration must be between 0 and 59", location)
        }
        nbt.validateEnum("billboard", billboardValues, location)
        nbt.get("brightness")?.let { brightness ->
            val root = brightness.requireObject("display brightness", location)
            val unknown = root.entrySet().map { it.key }.filterNot { it in setOf("sky", "block") }
            if (unknown.isNotEmpty()) inputError("Unknown display brightness field(s): ${unknown.joinToString()}", location)
            listOf("sky", "block").forEach { key ->
                root.get(key)?.let {
                    val value = it.requireInt("display brightness.$key", location)
                    if (value !in 0..15) inputError("display brightness.$key must be between 0 and 15", location)
                }
            }
        }
        listOf("view_range", "shadow_radius", "shadow_strength", "width", "height").forEach {
            nbt.validateNonNegativeNumber(it, location)
        }
        nbt.validateInt("glow_color_override", location)

        when (type) {
            blockDisplay ->
                nbt.get("block_state")?.let { blockState ->
                    val root = blockState.requireObject("block display block_state", location)
                    val name =
                        root.get("Name")
                            ?: inputError("block display block_state requires Name", location)
                    ResourceLocation.parse(name.requireString("block display block_state.Name", location))
                    root.get("Properties")?.requireObject("block display block_state.Properties", location)?.entrySet()?.forEach { (key, value) ->
                        if (!value.isJsonPrimitive) inputError("block display property '$key' must be a primitive", location)
                    }
                }
            itemDisplay -> nbt.validateEnum("item_display", itemDisplayValues, location)
            textDisplay -> {
                nbt.validateInt("line_width", location)
                nbt.validateInt("background", location)
                nbt.get("text_opacity")?.let {
                    val value = it.requireInt("text display text_opacity", location)
                    if (value !in -128..255) inputError("text display text_opacity must be between -128 and 255", location)
                }
                listOf("shadow", "see_through", "default_background").forEach { nbt.validateBoolean(it, location) }
                nbt.validateEnum("alignment", setOf("center", "left", "right"), location)
            }
        }
    }

    private fun validateArmorStand(
        nbt: JsonObject,
        location: SourceLocation?,
    ) {
        listOf("Invisible", "Marker", "Small", "ShowArms", "NoBasePlate").forEach { nbt.validateBoolean(it, location) }
        nbt.validateInt("DisabledSlots", location)
        nbt.get("Pose")?.let { poseValue ->
            val pose = poseValue.requireObject("armor stand Pose", location)
            val unknown =
                pose
                    .entrySet()
                    .map { it.key }
                    .filterNot { it in poseParts }
                    .sorted()
            if (unknown.isNotEmpty()) inputError("Unknown armor stand pose part(s): ${unknown.joinToString()}", location)
            pose.entrySet().forEach { (part, value) -> value.requireNumberArray("armor stand Pose.$part", 3, location) }
        }
    }

    private fun validateInteraction(
        nbt: JsonObject,
        location: SourceLocation?,
    ) {
        listOf("width", "height").forEach { nbt.validateNonNegativeNumber(it, location) }
        nbt.validateBoolean("response", location)
        listOf("attack", "interaction").forEach { key ->
            nbt.get(key)?.let { actionValue ->
                val action = actionValue.requireObject("interaction entity $key", location)
                val unknown =
                    action
                        .entrySet()
                        .map { it.key }
                        .filterNot { it in setOf("player", "timestamp") }
                        .sorted()
                if (unknown.isNotEmpty()) inputError("Unknown interaction entity $key field(s): ${unknown.joinToString()}", location)
                val player = action.get("player") ?: inputError("interaction entity $key requires player", location)
                if (uuidFromValue(player) ==
                    null
                ) {
                    inputError("interaction entity $key.player must be a UUID string or four-integer array", location)
                }
                action.get("timestamp")?.requireLong("interaction entity $key.timestamp", location)
                    ?: inputError("interaction entity $key requires timestamp", location)
            }
        }
    }

    private fun displaySnapshot(entity: SandboxEntity): JsonObject {
        val runtime =
            entity.displayRuntime
                ?: DisplayRuntimeState(
                    target = DisplayInterpolatedValues.fromNbt(entity.fullNbt(), entity.type, null),
                    previous = DisplayInterpolatedValues.fromNbt(entity.fullNbt(), entity.type, null),
                    startTick = entity.ageTicks,
                    duration = 0,
                ).also { entity.displayRuntime = it }
        val rendered = runtime.rendered(entity.ageTicks)
        val pose = runtime.renderedPose(entity.ageTicks, DisplayPose(entity.position, entity.yaw, entity.pitch))
        val progress = runtime.progress(entity.ageTicks)
        val root = JsonObject()
        root.addProperty("kind", entity.type.path.removeSuffix("_display"))
        root.add("renderTransformation", rendered.transformation.toJson())
        root.addProperty("shadowRadius", rendered.shadowRadius)
        root.addProperty("shadowStrength", rendered.shadowStrength)
        if (entity.type == textDisplay) {
            root.addProperty("textOpacity", rendered.textOpacity)
            root.addProperty("background", rendered.background)
        }
        root.addProperty("interpolationProgress", progress)
        root.addProperty("interpolationStartTick", runtime.startTick)
        root.addProperty("interpolationDuration", runtime.duration)
        root.add(
            "renderPosition",
            JsonObject().also {
                it.addProperty("x", pose.position.x)
                it.addProperty("y", pose.position.y)
                it.addProperty("z", pose.position.z)
                it.addProperty("yaw", pose.yaw)
                it.addProperty("pitch", pose.pitch)
            },
        )
        root.addProperty("teleportProgress", runtime.teleport?.progress(entity.ageTicks) ?: 1.0)
        val width = entity.nbt.doubleValue("width", 0.0)
        val height = entity.nbt.doubleValue("height", 0.0)
        root.add("cullingBox", cullingBoxSnapshot(entity, width, height))
        root.add("hitbox", hitboxSnapshot(entity, 0.0, 0.0, interactable = false, attackable = false))
        root.add(
            "content",
            JsonObject().also { content ->
                when (entity.type) {
                    blockDisplay -> entity.nbt.get("block_state")?.let { content.add("blockState", it.deepCopy()) }
                    itemDisplay -> {
                        entity.nbt.get("item")?.let { content.add("item", it.deepCopy()) }
                        entity.nbt.get("item_display")?.let { content.add("itemDisplay", it.deepCopy()) }
                    }
                    textDisplay -> entity.nbt.get("text")?.let { content.add("text", it.deepCopy()) }
                }
            },
        )
        return root
    }

    private fun armorStandSnapshot(entity: SandboxEntity): JsonObject {
        val markerMode = entity.nbt.booleanValue("Marker", false)
        val small = entity.nbt.booleanValue("Small", false)
        val width =
            when {
                markerMode -> 0.0
                small -> 0.25
                else -> 0.5
            }
        val height =
            when {
                markerMode -> 0.0
                small -> 0.9875
                else -> 1.975
            }
        return JsonObject().also { root ->
            root.add("pose", entity.nbt.getAsJsonObject("Pose")?.deepCopy() ?: JsonObject())
            root.addProperty("marker", markerMode)
            root.addProperty("small", small)
            root.addProperty("showArms", entity.nbt.booleanValue("ShowArms", false))
            root.addProperty("showBasePlate", !entity.nbt.booleanValue("NoBasePlate", false))
            root.addProperty("disabledSlots", entity.nbt.intValue("DisabledSlots", 0))
            root.add("hitbox", hitboxSnapshot(entity, width, height, interactable = !markerMode, attackable = !markerMode))
        }
    }

    private fun interactionSnapshot(entity: SandboxEntity): JsonObject {
        val width = entity.nbt.doubleValue("width", 1.0)
        val height = entity.nbt.doubleValue("height", 1.0)
        return JsonObject().also { root ->
            root.addProperty("response", entity.nbt.booleanValue("response", false))
            entity.nbt.get("attack")?.let { root.add("lastAttack", it.deepCopy()) }
            entity.nbt.get("interaction")?.let { root.add("lastInteraction", it.deepCopy()) }
            root.add(
                "hitbox",
                hitboxSnapshot(
                    entity,
                    width,
                    height,
                    interactable = width > 0.0 && height > 0.0,
                    attackable =
                        width > 0.0 && height > 0.0,
                ),
            )
        }
    }

    private fun hitboxSnapshot(
        entity: SandboxEntity,
        width: Double,
        height: Double,
        interactable: Boolean,
        attackable: Boolean,
    ): JsonObject =
        JsonObject().also { hitbox ->
            hitbox.addProperty("width", width)
            hitbox.addProperty("height", height)
            hitbox.addProperty("minX", entity.position.x - width / 2.0)
            hitbox.addProperty("maxX", entity.position.x + width / 2.0)
            hitbox.addProperty("minY", entity.position.y)
            hitbox.addProperty("maxY", entity.position.y + height)
            hitbox.addProperty("minZ", entity.position.z - width / 2.0)
            hitbox.addProperty("maxZ", entity.position.z + width / 2.0)
            hitbox.addProperty("interactable", interactable)
            hitbox.addProperty("attackable", attackable)
            hitbox.addProperty("collidable", false)
        }

    private fun cullingBoxSnapshot(
        entity: SandboxEntity,
        width: Double,
        height: Double,
    ): JsonObject =
        JsonObject().also { box ->
            box.addProperty("width", width)
            box.addProperty("height", height)
            box.addProperty("enabled", width > 0.0 && height > 0.0)
            box.addProperty("minX", entity.position.x - width / 2.0)
            box.addProperty("maxX", entity.position.x + width / 2.0)
            box.addProperty("minY", entity.position.y)
            box.addProperty("maxY", entity.position.y + height)
            box.addProperty("minZ", entity.position.z - width / 2.0)
            box.addProperty("maxZ", entity.position.z + width / 2.0)
        }

    private fun uuidToIntArray(raw: String): JsonArray {
        val uuid = UUID.fromString(raw)
        return JsonArray().also { array ->
            array.add((uuid.mostSignificantBits shr 32).toInt())
            array.add(uuid.mostSignificantBits.toInt())
            array.add((uuid.leastSignificantBits shr 32).toInt())
            array.add(uuid.leastSignificantBits.toInt())
        }
    }

    private fun uuidFromValue(value: JsonElement): String? =
        when {
            value.isJsonPrimitive && value.asJsonPrimitive.isString ->
                runCatching { UUID.fromString(value.asString).toString() }.getOrNull()
            value.isJsonArray &&
                value.asJsonArray.size() == 4 &&
                value.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isNumber } -> {
                val parts = value.asJsonArray.map { it.asInt }
                val most = (parts[0].toLong() shl 32) or (parts[1].toLong() and 0xffffffffL)
                val least = (parts[2].toLong() shl 32) or (parts[3].toLong() and 0xffffffffL)
                UUID(most, least).toString()
            }
            else -> null
        }

    private fun inputError(
        message: String,
        location: SourceLocation?,
    ): Nothing = throw SandboxException(DiagnosticCode.INPUT_FORMAT, message, location)

    private fun JsonObject.validateBoolean(
        key: String,
        location: SourceLocation?,
    ) {
        get(key)?.let { if (!it.isJsonPrimitive || !it.asJsonPrimitive.isBoolean) inputError("$key must be a boolean", location) }
    }

    private fun JsonObject.validateInt(
        key: String,
        location: SourceLocation?,
    ) {
        get(key)?.requireInt(key, location)
    }

    private fun JsonObject.validateNonNegativeInt(
        key: String,
        location: SourceLocation?,
    ) {
        get(key)?.let { if (it.requireInt(key, location) < 0) inputError("$key must be non-negative", location) }
    }

    private fun JsonObject.validateNonNegativeNumber(
        key: String,
        location: SourceLocation?,
    ) {
        get(key)?.let { if (it.requireDouble(key, location) < 0.0) inputError("$key must be non-negative", location) }
    }

    private fun JsonObject.validateEnum(
        key: String,
        allowed: Set<String>,
        location: SourceLocation?,
    ) {
        get(key)?.let {
            val value = it.requireString(key, location)
            if (value !in allowed) inputError("$key must be one of ${allowed.sorted().joinToString()}", location)
        }
    }

    private fun JsonElement.requireObject(
        label: String,
        location: SourceLocation?,
    ): JsonObject = takeIf { it.isJsonObject }?.asJsonObject ?: inputError("$label must be an object", location)

    private fun JsonElement.requireString(
        label: String,
        location: SourceLocation?,
    ): String = takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: inputError("$label must be a string", location)

    private fun JsonElement.requireDouble(
        label: String,
        location: SourceLocation?,
    ): Double = takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble ?: inputError("$label must be numeric", location)

    private fun JsonElement.requireInt(
        label: String,
        location: SourceLocation?,
    ): Int {
        val number =
            takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asBigDecimal
                ?: inputError("$label must be an integer", location)
        return runCatching { number.intValueExact() }.getOrElse { inputError("$label must be an integer", location) }
    }

    private fun JsonElement.requireLong(
        label: String,
        location: SourceLocation?,
    ): Long {
        val number =
            takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asBigDecimal
                ?: inputError("$label must be an integer", location)
        return runCatching { number.longValueExact() }.getOrElse { inputError("$label must be an integer", location) }
    }

    private fun JsonElement.requireNumberArray(
        label: String,
        size: Int,
        location: SourceLocation?,
    ): List<Double> {
        if (!isJsonArray || asJsonArray.size() != size) inputError("$label must contain exactly $size numbers", location)
        return asJsonArray.mapIndexed { index, value -> value.requireDouble("$label[$index]", location) }
    }
}

internal data class DisplayRuntimeState(
    var target: DisplayInterpolatedValues,
    var previous: DisplayInterpolatedValues,
    var startTick: Long,
    var duration: Int,
    var teleport: DisplayTeleportState? = null,
) {
    fun progress(tick: Long): Double =
        when {
            duration <= 0 -> 1.0
            tick <= startTick -> 0.0
            else -> ((tick - startTick).toDouble() / duration.toDouble()).coerceIn(0.0, 1.0)
        }

    fun rendered(tick: Long): DisplayInterpolatedValues = previous.interpolate(target, progress(tick))

    fun renderedPose(
        tick: Long,
        fallback: DisplayPose,
    ): DisplayPose = teleport?.rendered(tick) ?: fallback
}

internal data class DisplayTeleportState(
    val previous: DisplayPose,
    val target: DisplayPose,
    val startTick: Long,
    val duration: Int,
) {
    fun progress(tick: Long): Double = if (duration <= 0) 1.0 else ((tick - startTick).toDouble() / duration.toDouble()).coerceIn(0.0, 1.0)

    fun rendered(tick: Long): DisplayPose = previous.interpolate(target, progress(tick))
}

internal data class DisplayPose(
    val position: Position,
    val yaw: Double,
    val pitch: Double,
) {
    fun interpolate(
        other: DisplayPose,
        progress: Double,
    ): DisplayPose =
        DisplayPose(
            Position(
                lerp(position.x, other.position.x, progress),
                lerp(position.y, other.position.y, progress),
                lerp(position.z, other.position.z, progress),
            ),
            lerp(yaw, other.yaw, progress),
            lerp(pitch, other.pitch, progress),
        )
}

internal data class DisplayInterpolatedValues(
    val transformation: DisplayTransformation,
    val shadowRadius: Double,
    val shadowStrength: Double,
    val textOpacity: Int,
    val background: Int,
) {
    fun interpolate(
        other: DisplayInterpolatedValues,
        progress: Double,
    ): DisplayInterpolatedValues =
        DisplayInterpolatedValues(
            transformation.interpolate(other.transformation, progress),
            lerp(shadowRadius, other.shadowRadius, progress),
            lerp(shadowStrength, other.shadowStrength, progress),
            lerp(textOpacity.toDouble(), other.textOpacity.toDouble(), progress).roundToInt(),
            interpolateArgb(background, other.background, progress),
        )

    companion object {
        fun fromNbt(
            nbt: JsonObject,
            type: ResourceLocation,
            location: SourceLocation?,
        ): DisplayInterpolatedValues =
            DisplayInterpolatedValues(
                DisplayTransformation.parse(nbt.get("transformation") ?: DisplayTransformation.identity().toJson(), location),
                nbt.doubleValue("shadow_radius", 0.0),
                nbt.doubleValue("shadow_strength", 1.0),
                if (type.path == "text_display") nbt.intValue("text_opacity", 255) else 255,
                if (type.path == "text_display") nbt.intValue("background", 0x40000000) else 0,
            )
    }
}

internal sealed interface DisplayTransformation {
    fun interpolate(
        other: DisplayTransformation,
        progress: Double,
    ): DisplayTransformation

    fun toJson(): JsonElement

    fun toMatrix(): List<Double>

    data class Decomposed(
        val translation: Vec3,
        val leftRotation: Quaternion,
        val scale: Vec3,
        val rightRotation: Quaternion,
    ) : DisplayTransformation {
        override fun interpolate(
            other: DisplayTransformation,
            progress: Double,
        ): DisplayTransformation =
            if (other is Decomposed) {
                Decomposed(
                    translation.interpolate(other.translation, progress),
                    leftRotation.interpolate(other.leftRotation, progress),
                    scale.interpolate(other.scale, progress),
                    rightRotation.interpolate(other.rightRotation, progress),
                )
            } else {
                Matrix(interpolateMatrix(toMatrix(), other.toMatrix(), progress))
            }

        override fun toJson(): JsonObject =
            JsonObject().also { root ->
                root.add("translation", translation.toJson())
                root.add("left_rotation", leftRotation.toJson())
                root.add("scale", scale.toJson())
                root.add("right_rotation", rightRotation.toJson())
            }

        override fun toMatrix(): List<Double> =
            multiplyMatrices(
                multiplyMatrices(
                    multiplyMatrices(translationMatrix(translation), rotationMatrix(leftRotation)),
                    scaleMatrix(scale),
                ),
                rotationMatrix(rightRotation),
            )
    }

    data class Matrix(
        val values: List<Double>,
    ) : DisplayTransformation {
        override fun interpolate(
            other: DisplayTransformation,
            progress: Double,
        ): DisplayTransformation = Matrix(interpolateMatrix(values, other.toMatrix(), progress))

        override fun toJson(): JsonArray = JsonArray().also { array -> values.forEach(array::add) }

        override fun toMatrix(): List<Double> = values
    }

    companion object {
        fun identity(): DisplayTransformation = Decomposed(Vec3.zero, Quaternion.identity, Vec3.one, Quaternion.identity)

        fun parse(
            value: JsonElement,
            location: SourceLocation?,
        ): DisplayTransformation =
            when {
                value.isJsonArray -> {
                    val array = value.asJsonArray
                    if (array.size() != 16 || array.any { !it.isJsonPrimitive || !it.asJsonPrimitive.isNumber }) {
                        throw SandboxException(
                            DiagnosticCode.INPUT_FORMAT,
                            "display transformation matrix must contain exactly 16 numbers",
                            location,
                        )
                    }
                    Matrix(array.map { it.asDouble })
                }
                value.isJsonObject -> {
                    val root = value.asJsonObject
                    val unknown =
                        root
                            .entrySet()
                            .map { it.key }
                            .filterNot { it in setOf("translation", "left_rotation", "scale", "right_rotation") }
                    if (unknown.isNotEmpty()) {
                        throw SandboxException(
                            DiagnosticCode.INPUT_FORMAT,
                            "Unknown display transformation field(s): ${unknown.joinToString()}",
                            location,
                        )
                    }
                    Decomposed(
                        root.get("translation")?.let { Vec3.parse(it, "display transformation.translation", location) } ?: Vec3.zero,
                        root.get("left_rotation")?.let { Quaternion.parse(it, "display transformation.left_rotation", location) }
                            ?: Quaternion.identity,
                        root.get("scale")?.let { Vec3.parse(it, "display transformation.scale", location) } ?: Vec3.one,
                        root.get("right_rotation")?.let { Quaternion.parse(it, "display transformation.right_rotation", location) }
                            ?: Quaternion.identity,
                    )
                }
                else -> throw SandboxException(
                    DiagnosticCode.INPUT_FORMAT,
                    "display transformation must be a 16-number matrix or object",
                    location,
                )
            }
    }
}

internal data class Vec3(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    fun interpolate(
        other: Vec3,
        progress: Double,
    ): Vec3 = Vec3(lerp(x, other.x, progress), lerp(y, other.y, progress), lerp(z, other.z, progress))

    fun toJson(): JsonArray = vectorJson(x, y, z)

    companion object {
        val zero = Vec3(0.0, 0.0, 0.0)
        val one = Vec3(1.0, 1.0, 1.0)

        fun parse(
            value: JsonElement,
            label: String,
            location: SourceLocation?,
        ): Vec3 {
            if (!value.isJsonArray ||
                value.asJsonArray.size() != 3 ||
                value.asJsonArray.any { !it.isJsonPrimitive || !it.asJsonPrimitive.isNumber }
            ) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must contain exactly 3 numbers", location)
            }
            return Vec3(value.asJsonArray[0].asDouble, value.asJsonArray[1].asDouble, value.asJsonArray[2].asDouble)
        }
    }
}

internal data class Quaternion(
    val x: Double,
    val y: Double,
    val z: Double,
    val w: Double,
) {
    fun interpolate(
        other: Quaternion,
        progress: Double,
    ): Quaternion {
        var target = other
        var dot = x * other.x + y * other.y + z * other.z + w * other.w
        if (dot < 0.0) {
            target = Quaternion(-other.x, -other.y, -other.z, -other.w)
            dot = -dot
        }
        if (dot > 0.9995) {
            return Quaternion(
                lerp(x, target.x, progress),
                lerp(y, target.y, progress),
                lerp(z, target.z, progress),
                lerp(w, target.w, progress),
            ).normalized()
        }
        val theta = acos(dot.coerceIn(-1.0, 1.0))
        val denominator = sin(theta)
        if (denominator == 0.0) return this
        val fromWeight = sin((1.0 - progress) * theta) / denominator
        val toWeight = sin(progress * theta) / denominator
        return Quaternion(
            x * fromWeight + target.x * toWeight,
            y * fromWeight + target.y * toWeight,
            z * fromWeight + target.z * toWeight,
            w * fromWeight + target.w * toWeight,
        ).normalized()
    }

    fun normalized(): Quaternion {
        val length = sqrt(x * x + y * y + z * z + w * w)
        return if (length == 0.0) identity else Quaternion(x / length, y / length, z / length, w / length)
    }

    fun toJson(): JsonArray =
        JsonArray().also { array ->
            array.add(x)
            array.add(y)
            array.add(z)
            array.add(w)
        }

    companion object {
        val identity = Quaternion(0.0, 0.0, 0.0, 1.0)

        fun parse(
            value: JsonElement,
            label: String,
            location: SourceLocation?,
        ): Quaternion =
            when {
                value.isJsonArray -> {
                    val array = value.asJsonArray
                    if (array.size() != 4 || array.any { !it.isJsonPrimitive || !it.asJsonPrimitive.isNumber }) {
                        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must contain exactly 4 numbers", location)
                    }
                    Quaternion(array[0].asDouble, array[1].asDouble, array[2].asDouble, array[3].asDouble).normalized()
                }
                value.isJsonObject -> {
                    val root = value.asJsonObject
                    val axis =
                        root.get("axis")?.let { Vec3.parse(it, "$label.axis", location) }
                            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label requires axis", location)
                    val angleValue = root.get("angle")
                    if (angleValue == null || !angleValue.isJsonPrimitive || !angleValue.asJsonPrimitive.isNumber) {
                        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label requires numeric angle", location)
                    }
                    val half = angleValue.asDouble / 2.0
                    val axisLength = sqrt(axis.x * axis.x + axis.y * axis.y + axis.z * axis.z)
                    if (axisLength == 0.0) {
                        identity
                    } else {
                        Quaternion(
                            axis.x / axisLength * sin(half),
                            axis.y / axisLength * sin(half),
                            axis.z / axisLength * sin(half),
                            cos(half),
                        ).normalized()
                    }
                }
                else -> throw SandboxException(
                    DiagnosticCode.INPUT_FORMAT,
                    "$label must be a quaternion array or axis-angle object",
                    location,
                )
            }
    }
}

private fun JsonObject.addIfMissing(
    key: String,
    value: JsonElement,
) {
    if (!has(key)) add(key, value)
}

private fun JsonObject.addPropertyIfMissing(
    key: String,
    value: String,
) {
    if (!has(key)) addProperty(key, value)
}

private fun JsonObject.addPropertyIfMissing(
    key: String,
    value: Number,
) {
    if (!has(key)) addProperty(key, value)
}

private fun JsonObject.addPropertyIfMissing(
    key: String,
    value: Boolean,
) {
    if (!has(key)) addProperty(key, value)
}

private fun JsonObject.intValue(
    key: String,
    fallback: Int,
): Int = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: fallback

private fun JsonObject.doubleValue(
    key: String,
    fallback: Double,
): Double = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble ?: fallback

private fun JsonObject.booleanValue(
    key: String,
    fallback: Boolean,
): Boolean = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: fallback

private fun vectorJson(
    x: Double,
    y: Double,
    z: Double,
): JsonArray =
    JsonArray().also { array ->
        array.add(x)
        array.add(y)
        array.add(z)
    }

private fun lerp(
    start: Double,
    end: Double,
    progress: Double,
): Double = start + (end - start) * progress

private fun interpolateArgb(
    start: Int,
    end: Int,
    progress: Double,
): Int {
    var result = 0
    for (shift in listOf(24, 16, 8, 0)) {
        val from = (start ushr shift) and 0xff
        val to = (end ushr shift) and 0xff
        result = result or (lerp(from.toDouble(), to.toDouble(), progress).roundToInt().coerceIn(0, 255) shl shift)
    }
    return result
}

private fun interpolateMatrix(
    start: List<Double>,
    end: List<Double>,
    progress: Double,
): List<Double> = start.indices.map { index -> lerp(start[index], end[index], progress) }

private fun translationMatrix(value: Vec3): List<Double> =
    listOf(
        1.0,
        0.0,
        0.0,
        value.x,
        0.0,
        1.0,
        0.0,
        value.y,
        0.0,
        0.0,
        1.0,
        value.z,
        0.0,
        0.0,
        0.0,
        1.0,
    )

private fun scaleMatrix(value: Vec3): List<Double> =
    listOf(
        value.x,
        0.0,
        0.0,
        0.0,
        0.0,
        value.y,
        0.0,
        0.0,
        0.0,
        0.0,
        value.z,
        0.0,
        0.0,
        0.0,
        0.0,
        1.0,
    )

private fun rotationMatrix(raw: Quaternion): List<Double> {
    val q = raw.normalized()
    val xx = q.x * q.x
    val yy = q.y * q.y
    val zz = q.z * q.z
    val xy = q.x * q.y
    val xz = q.x * q.z
    val yz = q.y * q.z
    val wx = q.w * q.x
    val wy = q.w * q.y
    val wz = q.w * q.z
    return listOf(
        1.0 - 2.0 * (yy + zz),
        2.0 * (xy - wz),
        2.0 * (xz + wy),
        0.0,
        2.0 * (xy + wz),
        1.0 - 2.0 * (xx + zz),
        2.0 * (yz - wx),
        0.0,
        2.0 * (xz - wy),
        2.0 * (yz + wx),
        1.0 - 2.0 * (xx + yy),
        0.0,
        0.0,
        0.0,
        0.0,
        1.0,
    )
}

private fun multiplyMatrices(
    left: List<Double>,
    right: List<Double>,
): List<Double> =
    List(16) { index ->
        val row = index / 4
        val column = index % 4
        (0..3).sumOf { offset -> left[row * 4 + offset] * right[offset * 4 + column] }
    }
