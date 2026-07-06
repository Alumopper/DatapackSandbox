package moe.afox.dpsandbox.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.UUID

/**
 * Integer block position in the sandbox world.
 */
data class BlockPos(val x: Int, val y: Int, val z: Int) : Comparable<BlockPos> {
    override fun compareTo(other: BlockPos): Int =
        compareValuesBy(this, other, BlockPos::y, BlockPos::z, BlockPos::x)

    override fun toString(): String = "$x $y $z"
}

/**
 * Sparse block state and optional block entity NBT stored by the sandbox.
 */
data class SandboxBlock(
    var id: ResourceLocation,
    val properties: MutableMap<String, String> = linkedMapOf(),
    val nbt: JsonObject = JsonObject(),
) {
    /**
     * Returns the block entity NBT view using the default version profile.
     */
    fun fullNbt(pos: BlockPos, location: SourceLocation? = null): JsonObject =
        NbtSchemas.blockEntityNbt(this, pos, location)

    /**
     * Returns the block entity NBT view for [profile].
     */
    fun fullNbt(pos: BlockPos, profile: VersionProfile, location: SourceLocation? = null): JsonObject =
        NbtSchemas.blockEntityNbt(this, pos, profile, location)

    /**
     * Validates and writes a full block entity NBT view using the default profile.
     */
    fun writeFullNbt(pos: BlockPos, updated: JsonObject, location: SourceLocation? = null) =
        NbtSchemas.writeBlockEntityNbt(this, pos, updated, location)

    /**
     * Validates and writes a full block entity NBT view for [profile].
     */
    fun writeFullNbt(pos: BlockPos, profile: VersionProfile, updated: JsonObject, location: SourceLocation? = null) =
        NbtSchemas.writeBlockEntityNbt(this, pos, profile, updated, location)

    /**
     * Serializes this block into deterministic snapshot JSON.
     */
    fun toJson(pos: BlockPos): JsonObject {
        val json = JsonObject()
        json.addProperty("id", id.toString())
        json.addProperty("x", pos.x)
        json.addProperty("y", pos.y)
        json.addProperty("z", pos.z)
        val propertiesJson = JsonObject()
        properties.toSortedMap().forEach { (key, value) -> propertiesJson.addProperty(key, value) }
        json.add("properties", propertiesJson)
        json.add("nbt", nbt.deepCopy())
        return json
    }
}

/**
 * Internal scoreboard key exposed in snapshots and direct world access.
 */
data class ScoreKey(val target: String, val objective: String) : Comparable<ScoreKey> {
    override fun compareTo(other: ScoreKey): Int =
        compareValuesBy(this, other, ScoreKey::objective, ScoreKey::target)
}

/**
 * Floating-point entity or command position.
 */
data class Position(val x: Double, val y: Double, val z: Double) {
    companion object {
        val zero = Position(0.0, 0.0, 0.0)
    }
}

/**
 * Stored world or player spawn point.
 */
data class SpawnPoint(
    var position: Position = Position.zero,
    var dimension: ResourceLocation = ResourceLocation("minecraft", "overworld"),
    var angle: Double? = null,
    var forced: Boolean = false,
) {
    /**
     * Serializes this spawn point into snapshot JSON.
     */
    fun toJson(): JsonObject =
        JsonObject().also { json ->
            json.addProperty("x", position.x)
            json.addProperty("y", position.y)
            json.addProperty("z", position.z)
            json.addProperty("dimension", dimension.toString())
            angle?.let { json.addProperty("angle", it) }
            json.addProperty("forced", forced)
        }
}

/**
 * Item stack used in inventories, loot output, and item predicates.
 */
data class ItemStack(
    val id: ResourceLocation,
    var count: Int = 1,
    val components: JsonObject = JsonObject(),
    val nbt: JsonObject = JsonObject(),
) {
    /**
     * Serializes this item stack into deterministic snapshot JSON.
     */
    fun toJson(): JsonObject {
        val json = JsonObject()
        json.addProperty("id", id.toString())
        json.addProperty("count", count)
        json.add("components", components.deepCopy())
        json.add("nbt", nbt.deepCopy())
        return json
    }

    /**
     * Serializes this item stack into the item compound shape used by command NBT.
     */
    fun toNbtJson(): JsonObject {
        val json = toJson()
        json.remove("nbt")
        if (nbt.entrySet().isNotEmpty()) {
            json.getAsJsonObject("components").add("minecraft:custom_data", nbt.deepCopy())
        }
        return json
    }
}

internal fun itemStackFromNbtJson(json: JsonObject): ItemStack? {
    val id = json.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
    val count = (json.get("count") ?: json.get("Count"))?.takeIf { it.isJsonPrimitive }?.asInt ?: 1
    val components = json.getAsJsonObject("components")?.deepCopy() ?: JsonObject()
    val nbt = (json.getAsJsonObject("nbt")
        ?: json.getAsJsonObject("tag")
        ?: components.getAsJsonObject("minecraft:custom_data"))?.deepCopy() ?: JsonObject()
    return ItemStack(ResourceLocation.parse(id), count, components, nbt)
}

/**
 * Player status effect state tracked by the sandbox.
 */
data class PlayerEffect(
    val id: ResourceLocation,
    var durationTicks: Int = -1,
    var amplifier: Int = 0,
    var hideParticles: Boolean = false,
) {
    /**
     * Serializes this effect into deterministic snapshot JSON.
     */
    fun toJson(): JsonObject =
        JsonObject().also {
            it.addProperty("id", id.toString())
            it.addProperty("duration", durationTicks)
            it.addProperty("amplifier", amplifier)
            it.addProperty("hideParticles", hideParticles)
        }
}

/**
 * Per-player advancement criterion progress.
 */
data class AdvancementProgress(
    val criteria: MutableMap<String, Boolean> = linkedMapOf(),
) {
    /**
     * Returns whether this progress satisfies an advancement requirement matrix.
     */
    fun isDone(requirements: List<List<String>>): Boolean =
        requirements.isNotEmpty() && requirements.all { group -> group.any { criteria[it] == true } }
}

/**
 * Mutable non-player or player entity state modeled by the sandbox.
 */
open class SandboxEntity(
    val uuid: String = UUID.randomUUID().toString(),
    val type: ResourceLocation,
    var position: Position = Position.zero,
    val tags: MutableSet<String> = sortedSetOf(),
    val nbt: JsonObject = JsonObject(),
    var yaw: Double = 0.0,
    var pitch: Double = 0.0,
    var vehicle: String? = null,
    val passengers: MutableSet<String> = sortedSetOf(),
) {
    open val scoreHolder: String get() = uuid
    val attributes: MutableMap<ResourceLocation, Double> = linkedMapOf()
    val equipment: MutableMap<String, ItemStack> = linkedMapOf()

    /**
     * Returns the entity NBT view using the default version profile.
     */
    open fun fullNbt(location: SourceLocation? = null): JsonObject =
        NbtSchemas.entityNbt(this, location)

    /**
     * Returns the entity NBT view for [profile].
     */
    open fun fullNbt(profile: VersionProfile, location: SourceLocation? = null): JsonObject =
        NbtSchemas.entityNbt(this, profile, location)

    /**
     * Validates and writes a full entity NBT view using the default profile.
     */
    open fun writeFullNbt(updated: JsonObject, location: SourceLocation? = null) =
        NbtSchemas.writeEntityNbt(this, updated, location)

    /**
     * Validates and writes a full entity NBT view for [profile].
     */
    open fun writeFullNbt(profile: VersionProfile, updated: JsonObject, location: SourceLocation? = null) =
        NbtSchemas.writeEntityNbt(this, profile, updated, location)
}

/**
 * Mutable sandbox player state.
 */
class SandboxPlayer(
    val name: String,
    uuid: String = UUID.randomUUID().toString(),
    position: Position = Position.zero,
    var dimension: ResourceLocation = ResourceLocation("minecraft", "overworld"),
    var gameMode: String = "survival",
    var xp: Int = 0,
    var health: Double = 20.0,
    var food: Int = 20,
) : SandboxEntity(uuid, ResourceLocation("minecraft", "player"), position) {
    override val scoreHolder: String get() = name

    val inventory: MutableList<ItemStack> = mutableListOf()
    var selectedSlot: Int = 0
    val effects: MutableSet<ResourceLocation> = sortedSetOf()
    val effectDetails: MutableMap<ResourceLocation, PlayerEffect> = linkedMapOf()
    val recipes: MutableSet<ResourceLocation> = sortedSetOf()
    val advancementProgress: MutableMap<ResourceLocation, AdvancementProgress> = linkedMapOf()
    val stats: MutableMap<ResourceLocation, Int> = linkedMapOf()
    val inputEvents: MutableList<PlayerInput> = mutableListOf()
    var lastInput: PlayerInput? = null
    var spawnPoint: SpawnPoint? = null

    val selectedItem: ItemStack?
        get() = inventory.getOrNull(selectedSlot)

    /**
     * Returns the player NBT view using the default version profile.
     */
    override fun fullNbt(location: SourceLocation?): JsonObject {
        return fullNbt(VersionProfiles.default, location)
    }

    /**
     * Returns the player NBT view for [profile].
     *
     * Player NBT is readable for commands and predicates, but direct NBT writes
     * are blocked by [writeFullNbt].
     */
    override fun fullNbt(profile: VersionProfile, location: SourceLocation?): JsonObject {
        val json = super.fullNbt(profile, location)
        json.addProperty("Name", name)
        json.addProperty("Dimension", dimension.toString())
        json.addProperty("playerGameType", gameMode)
        json.addProperty("previousPlayerGameType", gameMode)
        json.addProperty("Health", health)
        json.addProperty("foodLevel", food)
        json.addProperty("foodTickTimer", 0)
        json.addProperty("foodSaturationLevel", 5.0)
        json.addProperty("foodExhaustionLevel", 0.0)
        json.addProperty("XpLevel", 0)
        json.addProperty("XpP", 0.0)
        json.addProperty("XpTotal", xp)
        json.addProperty("XpSeed", 0)
        json.addProperty("SelectedItemSlot", selectedSlot)
        spawnPoint?.let { spawn ->
            json.addProperty("SpawnX", spawn.position.x.toInt())
            json.addProperty("SpawnY", spawn.position.y.toInt())
            json.addProperty("SpawnZ", spawn.position.z.toInt())
            json.addProperty("SpawnDimension", spawn.dimension.toString())
            json.addProperty("SpawnForced", spawn.forced)
            spawn.angle?.let { json.addProperty("SpawnAngle", it) }
        }

        val inventoryJson = JsonArray()
        inventory.forEachIndexed { index, item ->
            val itemJson = item.toJson()
            itemJson.addProperty("Slot", index)
            inventoryJson.add(itemJson)
        }
        json.add("Inventory", inventoryJson)

        val effectsJson = JsonArray()
        effects.sorted().forEach { effect ->
            val detail = effectDetails[effect]
            val effectJson = JsonObject()
            effectJson.addProperty("id", effect.toString())
            effectJson.addProperty("amplifier", detail?.amplifier ?: 0)
            effectJson.addProperty("duration", detail?.durationTicks ?: -1)
            effectJson.addProperty("show_particles", detail?.hideParticles != true)
            effectsJson.add(effectJson)
        }
        json.add("ActiveEffects", effectsJson)

        json.add("EnderItems", JsonArray())
        json.add("abilities", JsonObject().also {
            it.addProperty("invulnerable", false)
            it.addProperty("flying", false)
            it.addProperty("mayfly", gameMode == "creative" || gameMode == "spectator")
            it.addProperty("instabuild", gameMode == "creative")
            it.addProperty("mayBuild", gameMode != "spectator")
            it.addProperty("flySpeed", 0.05)
            it.addProperty("walkSpeed", 0.1)
        })
        json.add("recipeBook", JsonObject().also {
            it.add("recipes", JsonArray().also { array -> recipes.sorted().forEach { recipe -> array.add(recipe.toString()) } })
            it.add("toBeDisplayed", JsonArray())
        })
        json.addProperty("seenCredits", false)
        return json
    }

    /**
     * Always fails because player NBT is read-only in this sandbox.
     */
    override fun writeFullNbt(updated: JsonObject, location: SourceLocation?) {
        throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Player NBT is read-only in this sandbox; use player events or movement commands")
    }

    /**
     * Always fails because player NBT is read-only in this sandbox.
     */
    override fun writeFullNbt(profile: VersionProfile, updated: JsonObject, location: SourceLocation?) {
        throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Player NBT is read-only in this sandbox; use player events or movement commands")
    }

    /**
     * Records keyboard or mouse input metadata on this player.
     */
    fun recordInput(input: PlayerInput) {
        lastInput = input
        inputEvents += input
    }
}

/**
 * Scheduled function entry due at [dueTick].
 */
data class ScheduledFunction(
    val id: ResourceLocation,
    val dueTick: Long,
)

/**
 * Bossbar state stored by the sandbox.
 */
data class SandboxBossbar(
    val id: ResourceLocation,
    var name: String,
    var value: Int = 0,
    var max: Int = 100,
    var color: String = "white",
    var style: String = "progress",
    var visible: Boolean = true,
    val players: MutableSet<String> = sortedSetOf(),
) {
    /**
     * Serializes this bossbar into deterministic snapshot JSON.
     */
    fun toJson(): JsonObject =
        JsonObject().also { json ->
            json.addProperty("id", id.toString())
            json.addProperty("name", name)
            json.addProperty("value", value)
            json.addProperty("max", max)
            json.addProperty("color", color)
            json.addProperty("style", style)
            json.addProperty("visible", visible)
            json.add("players", JsonArray().also { array -> players.forEach { array.add(it) } })
        }
}

/**
 * Team state stored by the sandbox.
 */
data class SandboxTeam(
    val name: String,
    var displayName: String = name,
    val members: MutableSet<String> = sortedSetOf(),
    val options: MutableMap<String, String> = linkedMapOf(),
) {
    /**
     * Serializes this team into deterministic snapshot JSON.
     */
    fun toJson(): JsonObject =
        JsonObject().also { json ->
            json.addProperty("name", name)
            json.addProperty("displayName", displayName)
            json.add("members", JsonArray().also { array -> members.forEach { array.add(it) } })
            val optionsJson = JsonObject()
            options.toSortedMap().forEach { (key, value) -> optionsJson.addProperty(key, value) }
            json.add("options", optionsJson)
        }
}

/**
 * World border state stored by the sandbox.
 */
data class SandboxWorldBorder(
    var centerX: Double = 0.0,
    var centerZ: Double = 0.0,
    var size: Double = 59999968.0,
    var targetSize: Double = size,
    var lerpTimeSeconds: Long = 0,
    var damageBuffer: Double = 5.0,
    var damageAmount: Double = 0.2,
    var warningDistance: Int = 5,
    var warningTime: Int = 15,
) {
    /**
     * Serializes this world border into deterministic snapshot JSON.
     */
    fun toJson(): JsonObject =
        JsonObject().also { json ->
            json.addProperty("centerX", centerX)
            json.addProperty("centerZ", centerZ)
            json.addProperty("size", size)
            json.addProperty("targetSize", targetSize)
            json.addProperty("lerpTimeSeconds", lerpTimeSeconds)
            json.addProperty("damageBuffer", damageBuffer)
            json.addProperty("damageAmount", damageAmount)
            json.addProperty("warningDistance", warningDistance)
            json.addProperty("warningTime", warningTime)
        }
}

/**
 * Resolved text segment from a JSON text component.
 */
data class OutputTextSegment(
    val text: String,
    val color: String? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underlined: Boolean = false,
    val strikethrough: Boolean = false,
    val obfuscated: Boolean = false,
) {
    /**
     * Serializes this resolved text segment into snapshot JSON.
     */
    fun toJson(): JsonObject {
        val json = JsonObject()
        json.addProperty("text", text)
        color?.let { json.addProperty("color", it) }
        if (bold) json.addProperty("bold", true)
        if (italic) json.addProperty("italic", true)
        if (underlined) json.addProperty("underlined", true)
        if (strikethrough) json.addProperty("strikethrough", true)
        if (obfuscated) json.addProperty("obfuscated", true)
        return json
    }
}

/**
 * One frame in the datapack function stack for a command or output event.
 */
data class FunctionTraceFrame(
    val id: ResourceLocation,
    val file: String? = null,
) {
    /**
     * Serializes this frame into deterministic snapshot JSON.
     */
    fun toJson(): JsonObject =
        JsonObject().also { json ->
            json.addProperty("id", id.toString())
            file?.let { json.addProperty("file", it) }
        }
}

/**
 * Source metadata attached to commands, trace events, and output events.
 */
data class CommandSource(
    val file: String? = null,
    val line: Int? = null,
    val command: String? = null,
    val functionStack: List<FunctionTraceFrame> = emptyList(),
) {
    /**
     * Serializes this source into deterministic snapshot JSON.
     */
    fun toJson(): JsonObject =
        JsonObject().also { json ->
            file?.let { json.addProperty("file", it) }
            line?.let { json.addProperty("line", it) }
            command?.let { json.addProperty("command", it) }
            if (functionStack.isNotEmpty()) {
                json.add("functionStack", JsonArray().also { array ->
                    functionStack.forEach { array.add(it.toJson()) }
                })
            }
        }
}

/**
 * Structured trace event for one command execution.
 */
data class CommandTraceEvent(
    val tick: Long,
    val command: String,
    val root: String,
    val source: CommandSource? = null,
    val executor: String? = null,
    val position: Position = Position.zero,
    val success: Boolean,
    val commandsExecuted: Int,
    val outputs: Int,
    val snapshotDiffs: List<SnapshotDiffEntry> = emptyList(),
    val errorCode: DiagnosticCode? = null,
    val errorMessage: String? = null,
) {
    /**
     * Serializes this trace event into deterministic snapshot JSON.
     */
    fun toJson(): JsonObject =
        JsonObject().also { json ->
            json.addProperty("tick", tick)
            json.addProperty("command", command)
            json.addProperty("root", root)
            json.addProperty("success", success)
            json.addProperty("commandsExecuted", commandsExecuted)
            json.addProperty("outputs", outputs)
            json.add("snapshotDiffs", SnapshotDiff.toJson(snapshotDiffs))
            executor?.let { json.addProperty("executor", it) }
            json.add("position", JsonObject().also { pos ->
                pos.addProperty("x", position.x)
                pos.addProperty("y", position.y)
                pos.addProperty("z", position.z)
            })
            source?.let { json.add("source", it.toJson()) }
            errorCode?.let { json.addProperty("errorCode", it.name) }
            errorMessage?.let { json.addProperty("errorMessage", it) }
        }
}

/**
 * Structured trace event for one high-level player event dispatch.
 */
data class PlayerEventTraceEvent(
    val tick: Long,
    val playerName: String,
    val type: String,
    val item: ResourceLocation? = null,
    val entity: ResourceLocation? = null,
    val block: ResourceLocation? = null,
    val recipe: ResourceLocation? = null,
    val fromDimension: ResourceLocation? = null,
    val toDimension: ResourceLocation? = null,
    val damageSource: ResourceLocation? = null,
    val damageAmount: Double? = null,
    val input: PlayerInput? = null,
    val advancements: List<AdvancementUpdate> = emptyList(),
    val success: Boolean = true,
    val errorCode: DiagnosticCode? = null,
    val errorMessage: String? = null,
) {
    /**
     * Serializes this player event trace into deterministic snapshot JSON.
     */
    fun toJson(): JsonObject =
        JsonObject().also { json ->
            json.addProperty("tick", tick)
            json.addProperty("player", playerName)
            json.addProperty("type", type)
            json.addProperty("success", success)
            item?.let { json.addProperty("item", it.toString()) }
            entity?.let { json.addProperty("entity", it.toString()) }
            block?.let { json.addProperty("block", it.toString()) }
            recipe?.let { json.addProperty("recipe", it.toString()) }
            fromDimension?.let { json.addProperty("from", it.toString()) }
            toDimension?.let { json.addProperty("to", it.toString()) }
            damageSource?.let { json.addProperty("damageSource", it.toString()) }
            damageAmount?.let { json.addProperty("damageAmount", it) }
            input?.let { json.add("input", it.toJson()) }
            json.add(
                "advancements",
                JsonArray().also { array ->
                    advancements
                        .sortedWith(compareBy<AdvancementUpdate> { it.advancement.toString() }.thenBy { it.criterion })
                        .forEach { update ->
                            array.add(
                                JsonObject().also { advancement ->
                                    advancement.addProperty("id", update.advancement.toString())
                                    advancement.addProperty("criterion", update.criterion)
                                    advancement.addProperty("completed", update.completed)
                                },
                            )
                        }
                },
            )
            errorCode?.let { json.addProperty("errorCode", it.name) }
            errorMessage?.let { json.addProperty("errorMessage", it) }
        }

    companion object {
        fun from(
            tick: Long,
            event: PlayerEvent,
            updates: List<AdvancementUpdate>,
            success: Boolean = true,
            errorCode: DiagnosticCode? = null,
            errorMessage: String? = null,
        ): PlayerEventTraceEvent =
            PlayerEventTraceEvent(
                tick = tick,
                playerName = event.playerName,
                type = event.type,
                item = event.item?.id,
                entity = event.entity?.type,
                block = event.block,
                recipe = event.recipe,
                fromDimension = event.fromDimension,
                toDimension = event.toDimension,
                damageSource = event.damageSource,
                damageAmount = event.damageAmount,
                input = event.input,
                advancements = updates,
                success = success,
                errorCode = errorCode,
                errorMessage = errorMessage,
            )
    }
}

/**
 * Observable output event recorded by commands and warnings.
 */
data class OutputEvent(
    val tick: Long,
    val command: String,
    val channel: String,
    val targets: List<String> = emptyList(),
    val text: String = "",
    val payload: JsonElement? = null,
    val segments: List<OutputTextSegment> = emptyList(),
    val source: CommandSource? = null,
) {
    /**
     * Serializes this output event into deterministic snapshot JSON.
     */
    fun toJson(): JsonObject {
        val json = JsonObject()
        json.addProperty("tick", tick)
        json.addProperty("command", command)
        json.addProperty("channel", channel)
        json.addProperty("text", text)

        val targetJson = JsonArray()
        targets.sorted().forEach { targetJson.add(it) }
        json.add("targets", targetJson)

        payload?.let { json.add("payload", it.deepCopy()) }
        source?.let { json.add("source", it.toJson()) }

        if (segments.isNotEmpty()) {
            val segmentJson = JsonArray()
            segments.forEach { segmentJson.add(it.toJson()) }
            json.add("segments", segmentJson)
        }
        return json
    }
}

/**
 * Mutable in-memory world state used by [DatapackSandbox].
 *
 * The world starts as sparse void. Only explicitly placed/imported blocks,
 * entities, players, scores, storage, and output events exist.
 */
class SandboxWorld {
    var gameTime: Long = 0
        private set
    var dayTime: Long = 0
        private set
    var weather: String = "clear"
    var weatherDuration: Int = 0
    var difficulty: String = "normal"
    var defaultGameMode: String = "survival"
    var seed: Long = 0
    var worldSpawn: SpawnPoint = SpawnPoint()
    var tickRate: Double = 20.0
    var tickFrozen: Boolean = false

    val objectives: MutableMap<String, String> = linkedMapOf()
    val scores: MutableMap<ScoreKey, Int> = linkedMapOf()
    val storages: MutableMap<ResourceLocation, JsonObject> = linkedMapOf()
    val entities: MutableList<SandboxEntity> = mutableListOf()
    val players: MutableMap<String, SandboxPlayer> = linkedMapOf()
    val blocks: MutableMap<BlockPos, SandboxBlock> = linkedMapOf()
    val scheduledFunctions: MutableList<ScheduledFunction> = mutableListOf()
    val outputs: MutableList<OutputEvent> = mutableListOf()
    val traces: MutableList<CommandTraceEvent> = mutableListOf()
    val playerEventTraces: MutableList<PlayerEventTraceEvent> = mutableListOf()
    val bossbars: MutableMap<ResourceLocation, SandboxBossbar> = linkedMapOf()
    val gamerules: MutableMap<String, String> = linkedMapOf()
    val teams: MutableMap<String, SandboxTeam> = linkedMapOf()
    val randomSequences: MutableMap<String, Long> = linkedMapOf()
    val forcedChunks: MutableSet<ChunkPos> = sortedSetOf()
    val biomes: MutableMap<BlockPos, ResourceLocation> = linkedMapOf()
    val worldBorder: SandboxWorldBorder = SandboxWorldBorder()
    var currentCommandSource: CommandSource? = null

    /**
     * Advances world time by one tick and decreases weather duration when active.
     */
    fun advanceTick() {
        gameTime += 1
        dayTime = (dayTime + 1).floorMod(24000)
        if (weatherDuration > 0) weatherDuration -= 1
    }

    /**
     * Sets absolute game time, clamped to zero or greater.
     */
    fun setGameTime(value: Long) {
        gameTime = value.coerceAtLeast(0)
    }

    /**
     * Sets daytime modulo 24000.
     */
    fun setDayTime(value: Long) {
        dayTime = value.floorMod(24000)
    }

    /**
     * Adds [delta] to daytime modulo 24000.
     */
    fun addDayTime(delta: Long) {
        dayTime = (dayTime + delta).floorMod(24000)
    }

    /**
     * Adds or replaces a scoreboard objective.
     */
    fun addObjective(name: String, criteria: String) {
        objectives[name] = criteria
    }

    /**
     * Removes a scoreboard objective and all scores stored under it.
     */
    fun removeObjective(name: String) {
        objectives.remove(name)
        scores.keys.filter { it.objective == name }.forEach { scores.remove(it) }
    }

    /**
     * Returns the current score, or zero when no score has been set.
     */
    fun getScore(target: String, objective: String): Int =
        scores[ScoreKey(target, objective)] ?: 0

    /**
     * Sets a score after verifying the objective exists.
     */
    fun setScore(target: String, objective: String, value: Int) {
        ensureObjective(objective)
        scores[ScoreKey(target, objective)] = value
    }

    /**
     * Adds [delta] to the current score after verifying the objective exists.
     */
    fun addScore(target: String, objective: String, delta: Int) {
        setScore(target, objective, getScore(target, objective) + delta)
    }

    /**
     * Throws when [objective] is not defined.
     */
    fun ensureObjective(objective: String) {
        if (!objectives.containsKey(objective)) {
            throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Unknown scoreboard objective '$objective'")
        }
    }

    /**
     * Returns a storage object, creating an empty one when missing.
     */
    fun storage(id: ResourceLocation): JsonObject =
        storages.getOrPut(id) { JsonObject() }

    /**
     * Creates or reuses a sandbox player and ensures it is present in [entities].
     */
    fun createPlayer(name: String): SandboxPlayer =
        players.getOrPut(name) {
            SandboxPlayer(name).also { entities += it }
        }

    /**
     * Returns a player by name or throws a structured input error.
     */
    fun requirePlayer(name: String): SandboxPlayer =
        players[name] ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Player '$name' does not exist")

    /**
     * Returns the block at [pos], or null for void/air.
     */
    fun block(pos: BlockPos): SandboxBlock? = blocks[pos]

    /**
     * Returns the block at [pos] or throws when no explicit block exists.
     */
    fun requireBlock(pos: BlockPos): SandboxBlock =
        block(pos) ?: throw SandboxException(DiagnosticCode.COMMAND_ERROR, "No block exists at $pos; the initial sandbox world is void")

    /**
     * Places or removes a block in the sparse world.
     *
     * Passing null or `minecraft:air` removes the explicit block entry.
     */
    fun setBlock(pos: BlockPos, block: SandboxBlock?) {
        if (block == null || block.id == ResourceLocation("minecraft", "air")) {
            blocks.remove(pos)
        } else {
            blocks[pos] = block
        }
    }

    /**
     * Records an observable command output event.
     *
     * These events are used by REPL rendering, snapshots, manifest assertions,
     * and the quick-test API.
     */
    fun recordOutput(
        command: String,
        channel: String,
        targets: List<String> = emptyList(),
        text: String = "",
        payload: JsonElement? = null,
        segments: List<OutputTextSegment> = emptyList(),
        source: CommandSource? = currentCommandSource,
    ) {
        outputs += OutputEvent(
            tick = gameTime,
            command = command,
            channel = channel,
            targets = targets,
            text = text,
            payload = payload,
            segments = segments,
            source = source,
        )
    }

    /**
     * Returns a deterministic JSON snapshot of the complete modeled world state.
     */
    fun snapshot(profile: VersionProfile = VersionProfiles.default): JsonObject {
        val root = JsonObject()
        root.addProperty("gameTime", gameTime)
        root.addProperty("dayTime", dayTime)
        root.addProperty("weather", weather)
        root.addProperty("weatherDuration", weatherDuration)
        root.addProperty("difficulty", difficulty)
        root.addProperty("defaultGameMode", defaultGameMode)
        root.addProperty("seed", seed)
        root.add("worldSpawn", worldSpawn.toJson())
        root.addProperty("tickRate", tickRate)
        root.addProperty("tickFrozen", tickFrozen)

        val objectivesJson = JsonObject()
        objectives.toSortedMap().forEach { (name, criteria) ->
            objectivesJson.addProperty(name, criteria)
        }
        root.add("objectives", objectivesJson)

        val scoresJson = JsonObject()
        scores.toSortedMap().forEach { (key, value) ->
            val objectiveJson = scoresJson.getAsJsonObject(key.objective) ?: JsonObject().also {
                scoresJson.add(key.objective, it)
            }
            objectiveJson.addProperty(key.target, value)
        }
        root.add("scores", scoresJson)

        val storageJson = JsonObject()
        storages.toSortedMap().forEach { (id, value) ->
            storageJson.add(id.toString(), value.deepCopy())
        }
        root.add("storage", storageJson)

        val entitiesJson = JsonArray()
        entities.sortedWith(compareBy<SandboxEntity> { it.type.toString() }.thenBy { it.uuid }).forEach { entity ->
            entitiesJson.add(entity.toJson(profile))
        }
        root.add("entities", entitiesJson)

        val blocksJson = JsonArray()
        blocks.toSortedMap().forEach { (pos, block) -> blocksJson.add(block.toJson(pos)) }
        root.add("blocks", blocksJson)

        val playersJson = JsonObject()
        players.toSortedMap().forEach { (name, player) ->
            playersJson.add(name, player.toPlayerJson(profile))
        }
        root.add("players", playersJson)

        val scheduledJson = JsonArray()
        scheduledFunctions.sortedWith(compareBy<ScheduledFunction> { it.dueTick }.thenBy { it.id.toString() }).forEach {
            val entry = JsonObject()
            entry.addProperty("function", it.id.toString())
            entry.addProperty("dueTick", it.dueTick)
            scheduledJson.add(entry)
        }
        root.add("scheduled", scheduledJson)

        val bossbarJson = JsonObject()
        bossbars.toSortedMap().forEach { (id, value) -> bossbarJson.add(id.toString(), value.toJson()) }
        root.add("bossbars", bossbarJson)

        val gameruleJson = JsonObject()
        gamerules.toSortedMap().forEach { (key, value) -> gameruleJson.addProperty(key, value) }
        root.add("gamerules", gameruleJson)

        val teamJson = JsonObject()
        teams.toSortedMap().forEach { (name, team) -> teamJson.add(name, team.toJson()) }
        root.add("teams", teamJson)

        val forcedChunkJson = JsonArray()
        forcedChunks.sorted().forEach { chunk ->
            forcedChunkJson.add(JsonObject().also {
                it.addProperty("x", chunk.x)
                it.addProperty("z", chunk.z)
            })
        }
        root.add("forcedChunks", forcedChunkJson)

        val biomeJson = JsonArray()
        biomes.toSortedMap().forEach { (pos, biome) ->
            biomeJson.add(JsonObject().also {
                it.addProperty("x", pos.x)
                it.addProperty("y", pos.y)
                it.addProperty("z", pos.z)
                it.addProperty("biome", biome.toString())
            })
        }
        root.add("biomes", biomeJson)
        root.add("worldBorder", worldBorder.toJson())

        val outputJson = JsonArray()
        outputs.forEach { outputJson.add(it.toJson()) }
        root.add("outputs", outputJson)

        val traceJson = JsonArray()
        traces.forEach { traceJson.add(it.toJson()) }
        root.add("traces", traceJson)

        val playerEventTraceJson = JsonArray()
        playerEventTraces.forEach { playerEventTraceJson.add(it.toJson()) }
        root.add("playerEventTraces", playerEventTraceJson)
        return root
    }
}

/**
 * Serializes an entity into deterministic snapshot JSON.
 */
fun SandboxEntity.toJson(profile: VersionProfile = VersionProfiles.default): JsonElement {
    val json = JsonObject()
    json.addProperty("uuid", uuid)
    json.addProperty("type", type.toString())
    json.addProperty("x", position.x)
    json.addProperty("y", position.y)
    json.addProperty("z", position.z)
    json.addProperty("yaw", yaw)
    json.addProperty("pitch", pitch)
    vehicle?.let { json.addProperty("vehicle", it) }
    json.add("passengers", JsonArray().also { array -> passengers.forEach { array.add(it) } })

    val tagsJson = JsonArray()
    tags.sorted().forEach { tagsJson.add(it) }
    json.add("tags", tagsJson)
    json.add("nbt", fullNbt(profile))
    val equipmentJson = JsonObject()
    equipment.toSortedMap().forEach { (slot, item) -> equipmentJson.add(slot, item.toJson()) }
    json.add("equipment", equipmentJson)
    val attributesJson = JsonObject()
    attributes.toSortedMap().forEach { (id, value) -> attributesJson.addProperty(id.toString(), value) }
    json.add("attributes", attributesJson)
    return json
}

/**
 * Serializes a player into deterministic snapshot JSON, including player-only state.
 */
fun SandboxPlayer.toPlayerJson(profile: VersionProfile = VersionProfiles.default): JsonElement {
    val json = toJson(profile).asJsonObject
    json.addProperty("name", name)
    json.addProperty("dimension", dimension.toString())
    json.addProperty("gameMode", gameMode)
    json.addProperty("xp", xp)
    json.addProperty("health", health)
    json.addProperty("food", food)
    json.addProperty("selectedSlot", selectedSlot)
    spawnPoint?.let { json.add("spawnPoint", it.toJson()) }

    val inventoryJson = JsonArray()
    inventory.forEach { inventoryJson.add(it.toJson()) }
    json.add("inventory", inventoryJson)

    val effectsJson = JsonArray()
    effects.sorted().forEach { effect ->
        effectsJson.add(effectDetails[effect]?.toJson() ?: JsonObject().also { it.addProperty("id", effect.toString()) })
    }
    json.add("effects", effectsJson)

    val recipesJson = JsonArray()
    recipes.sorted().forEach { recipesJson.add(it.toString()) }
    json.add("recipes", recipesJson)

    val advancementsJson = JsonObject()
    advancementProgress.toSortedMap().forEach { (id, progress) ->
        val progressJson = JsonObject()
        progress.criteria.toSortedMap().forEach { (criterion, done) -> progressJson.addProperty(criterion, done) }
        advancementsJson.add(id.toString(), progressJson)
    }
    json.add("advancements", advancementsJson)

    lastInput?.let { json.add("lastInput", it.toJson()) }
    val inputEventsJson = JsonArray()
    inputEvents.forEach { inputEventsJson.add(it.toJson()) }
    json.add("inputEvents", inputEventsJson)
    return json
}

private fun Long.floorMod(modulus: Long): Long =
    Math.floorMod(this, modulus)
