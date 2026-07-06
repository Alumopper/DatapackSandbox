package moe.afox.dpsandbox.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.nio.file.Path
import java.util.UUID

/**
 * Chunk coordinate in Java Edition Anvil region space.
 *
 * `x` and `z` are chunk coordinates, not block coordinates.
 */
data class ChunkPos(val x: Int, val z: Int) : Comparable<ChunkPos> {
    override fun compareTo(other: ChunkPos): Int =
        compareValuesBy(this, other, ChunkPos::z, ChunkPos::x)
}

/**
 * Builder-style collection of world fixture operations.
 *
 * A setup object can be reused across quick tests or manifest parsing. The
 * operations are validated when [applyTo] is called because validation depends
 * on the active [VersionProfile].
 */
class SandboxWorldSetup {
    private val operations = mutableListOf<(SandboxWorld, VersionProfile) -> Unit>()

    /**
     * Applies all queued fixture operations to [world].
     *
     * @param profile Version profile used for NBT schema validation.
     */
    fun applyTo(world: SandboxWorld, profile: VersionProfile) {
        operations.forEach { it(world, profile) }
        normalizeEntityRelationships(world)
    }

    private fun normalizeEntityRelationships(world: SandboxWorld) {
        val entitiesByUuid = (world.entities + world.players.values).associateBy { it.uuid }
        entitiesByUuid.values.forEach { entity ->
            entity.vehicle?.let { vehicleId -> entitiesByUuid[vehicleId]?.passengers?.add(entity.uuid) }
            entity.passengers.toList().forEach { passengerId -> entitiesByUuid[passengerId]?.vehicle = entity.uuid }
        }
    }

    /**
     * Sets the world's absolute game time.
     *
     * @return this setup for fluent chaining.
     */
    fun gameTime(value: Long): SandboxWorldSetup = apply {
        operations += { world, _ -> world.setGameTime(value) }
    }

    /**
     * Sets the world's daytime value.
     *
     * @return this setup for fluent chaining.
     */
    fun dayTime(value: Long): SandboxWorldSetup = apply {
        operations += { world, _ -> world.setDayTime(value) }
    }

    /**
     * Sets the world's deterministic seed.
     *
     * @return this setup for fluent chaining.
     */
    fun seed(value: Long): SandboxWorldSetup = apply {
        operations += { world, _ -> world.seed = value }
    }

    /**
     * Sets one deterministic random sequence state.
     *
     * @return this setup for fluent chaining.
     */
    fun randomSequence(name: String, state: Long): SandboxWorldSetup = apply {
        operations += { world, _ -> world.randomSequences[name] = state }
    }

    /**
     * Sets the world's stored difficulty.
     *
     * @return this setup for fluent chaining.
     */
    fun difficulty(value: String): SandboxWorldSetup = apply {
        operations += { world, _ -> world.difficulty = value.lowercase() }
    }

    /**
     * Sets the world's default game mode.
     *
     * @return this setup for fluent chaining.
     */
    fun defaultGameMode(value: String): SandboxWorldSetup = apply {
        operations += { world, _ -> world.defaultGameMode = value.lowercase() }
    }

    /**
     * Sets the world spawn point.
     *
     * @return this setup for fluent chaining.
     */
    @JvmOverloads
    fun worldSpawn(
        x: Double,
        y: Double,
        z: Double,
        dimension: String = "minecraft:overworld",
        angle: Double? = null,
        forced: Boolean = false,
    ): SandboxWorldSetup = apply {
        operations += { world, _ ->
            world.worldSpawn = SpawnPoint(
                position = Position(x, y, z),
                dimension = ResourceLocation.parse(dimension),
                angle = angle,
                forced = forced,
            )
        }
    }

    /**
     * Sets selected world border fields.
     *
     * @return this setup for fluent chaining.
     */
    @JvmOverloads
    fun worldBorder(
        centerX: Double? = null,
        centerZ: Double? = null,
        size: Double? = null,
        targetSize: Double? = null,
        lerpTimeSeconds: Long? = null,
        damageBuffer: Double? = null,
        damageAmount: Double? = null,
        warningDistance: Int? = null,
        warningTime: Int? = null,
    ): SandboxWorldSetup = apply {
        operations += { world, _ ->
            val border = world.worldBorder
            centerX?.let { border.centerX = it }
            centerZ?.let { border.centerZ = it }
            size?.let {
                border.size = it
                if (targetSize == null) border.targetSize = it
            }
            targetSize?.let { border.targetSize = it }
            lerpTimeSeconds?.let { border.lerpTimeSeconds = it }
            damageBuffer?.let { border.damageBuffer = it }
            damageAmount?.let { border.damageAmount = it }
            warningDistance?.let { border.warningDistance = it }
            warningTime?.let { border.warningTime = it }
        }
    }

    /**
     * Sets the stored weather state.
     *
     * @param kind One of `clear`, `rain`, or `thunder`.
     * @param duration Optional duration in ticks. Negative values are coerced to zero.
     * @throws SandboxException when [kind] is not a supported weather value.
     * @return this setup for fluent chaining.
     */
    @JvmOverloads
    fun weather(kind: String, duration: Int = 0): SandboxWorldSetup = apply {
        operations += { world, _ ->
            val normalized = kind.lowercase()
            if (normalized !in setOf("clear", "rain", "thunder")) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Weather must be clear, rain, or thunder")
            }
            world.weather = normalized
            world.weatherDuration = duration.coerceAtLeast(0)
        }
    }

    /**
     * Marks a chunk as force-loaded.
     *
     * @return this setup for fluent chaining.
     */
    fun forcedChunk(x: Int, z: Int): SandboxWorldSetup = apply {
        operations += { world, _ -> world.forcedChunks += ChunkPos(x, z) }
    }

    /**
     * Stores an explicit biome override at a block position.
     *
     * @return this setup for fluent chaining.
     */
    fun biome(x: Int, y: Int, z: Int, id: String): SandboxWorldSetup = apply {
        operations += { world, _ -> world.biomes[BlockPos(x, y, z)] = ResourceLocation.parse(id) }
    }

    /**
     * Places a block fixture using primitive coordinates and optional SNBT/JSON NBT text.
     *
     * @param id Block id such as `minecraft:chest`.
     * @param properties Block state properties.
     * @param nbt Optional block entity NBT object. Unknown top-level fields are
     * rejected by the active version profile when the setup is applied.
     * @return this setup for fluent chaining.
     */
    @JvmOverloads
    fun block(
        x: Int,
        y: Int,
        z: Int,
        id: String,
        properties: Map<String, String> = emptyMap(),
        nbt: String? = null,
    ): SandboxWorldSetup =
        block(BlockPos(x, y, z), ResourceLocation.parse(id), properties, nbt?.let { JsonValues.parse(it).asJsonObject })

    /**
     * Places a block fixture using typed ids and JSON NBT.
     *
     * @return this setup for fluent chaining.
     */
    @JvmOverloads
    fun block(
        pos: BlockPos,
        id: ResourceLocation,
        properties: Map<String, String> = emptyMap(),
        nbt: JsonObject? = null,
    ): SandboxWorldSetup = apply {
        operations += { world, profile ->
            val sandboxBlock = SandboxBlock(id, properties.toMutableMap())
            if (nbt != null && nbt.entrySet().isNotEmpty()) {
                val full = sandboxBlock.fullNbt(pos, profile)
                JsonPaths.merge(full, null, nbt)
                sandboxBlock.writeFullNbt(pos, profile, full)
            }
            world.setBlock(pos, sandboxBlock)
        }
    }

    /**
     * Fills an inclusive cuboid region with one block fixture.
     *
     * This is intended for deterministic sparse-world fixtures. The volume is
     * capped at 32768 blocks to match command-side region safety limits.
     *
     * @return this setup for fluent chaining.
     */
    @JvmOverloads
    fun region(
        from: BlockPos,
        to: BlockPos,
        id: ResourceLocation,
        properties: Map<String, String> = emptyMap(),
        nbt: JsonObject? = null,
    ): SandboxWorldSetup = apply {
        val xs = minOf(from.x, to.x)..maxOf(from.x, to.x)
        val ys = minOf(from.y, to.y)..maxOf(from.y, to.y)
        val zs = minOf(from.z, to.z)..maxOf(from.z, to.z)
        val volume = xs.count() * ys.count() * zs.count()
        if (volume > 32768) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "World region fixture volume $volume exceeds sandbox limit 32768")
        }
        val nbtCopy = nbt?.deepCopy()
        operations += { world, profile ->
            for (x in xs) for (y in ys) for (z in zs) {
                val pos = BlockPos(x, y, z)
                val sandboxBlock = SandboxBlock(id, properties.toMutableMap())
                if (nbtCopy != null && nbtCopy.entrySet().isNotEmpty()) {
                    val full = sandboxBlock.fullNbt(pos, profile)
                    JsonPaths.merge(full, null, nbtCopy.deepCopy())
                    sandboxBlock.writeFullNbt(pos, profile, full)
                }
                world.setBlock(pos, sandboxBlock)
            }
        }
    }

    /**
     * Fills an inclusive cuboid region with one block fixture.
     *
     * @return this setup for fluent chaining.
     */
    @JvmOverloads
    fun region(
        fromX: Int,
        fromY: Int,
        fromZ: Int,
        toX: Int,
        toY: Int,
        toZ: Int,
        id: String,
        properties: Map<String, String> = emptyMap(),
        nbt: String? = null,
    ): SandboxWorldSetup =
        region(
            from = BlockPos(fromX, fromY, fromZ),
            to = BlockPos(toX, toY, toZ),
            id = ResourceLocation.parse(id),
            properties = properties,
            nbt = nbt?.let { JsonValues.parse(it).asJsonObject },
        )

    /**
     * Adds a non-player entity fixture using primitive coordinates and optional SNBT/JSON NBT text.
     *
     * Entity NBT is validated against the active version profile when applied.
     *
     * @return this setup for fluent chaining.
     */
    @JvmOverloads
    fun entity(
        type: String,
        x: Double = 0.0,
        y: Double = 0.0,
        z: Double = 0.0,
        tags: Iterable<String> = emptyList(),
        nbt: String? = null,
        yaw: Double = 0.0,
        pitch: Double = 0.0,
        equipment: Map<String, ItemStack> = emptyMap(),
        effects: Iterable<PlayerEffect> = emptyList(),
        attributes: Map<String, Double> = emptyMap(),
        dimension: String = "minecraft:overworld",
        health: Double? = null,
        uuid: String? = null,
        vehicle: String? = null,
        passengers: Iterable<String> = emptyList(),
    ): SandboxWorldSetup =
        entity(
            type = ResourceLocation.parse(type),
            position = Position(x, y, z),
            tags = tags,
            nbt = nbt?.let { JsonValues.parse(it).asJsonObject },
            yaw = yaw,
            pitch = pitch,
            equipment = equipment,
            effects = effects,
            attributes = attributes,
            dimension = ResourceLocation.parse(dimension),
            health = health,
            uuid = uuid,
            vehicle = vehicle,
            passengers = passengers,
        )

    /**
     * Adds a non-player entity fixture using typed ids and JSON NBT.
     *
     * @return this setup for fluent chaining.
     */
    @JvmOverloads
    fun entity(
        type: ResourceLocation,
        position: Position = Position.zero,
        tags: Iterable<String> = emptyList(),
        nbt: JsonObject? = null,
        yaw: Double = 0.0,
        pitch: Double = 0.0,
        equipment: Map<String, ItemStack> = emptyMap(),
        effects: Iterable<PlayerEffect> = emptyList(),
        attributes: Map<String, Double> = emptyMap(),
        dimension: ResourceLocation = ResourceLocation("minecraft", "overworld"),
        health: Double? = null,
        uuid: String? = null,
        vehicle: String? = null,
        passengers: Iterable<String> = emptyList(),
    ): SandboxWorldSetup = apply {
        val equipmentCopies = equipment.map { (slot, item) -> slot to item.copyForSetup() }
        val effectCopies = effects.map { it.copy() }
        val attributeCopies = attributes.map { (id, value) -> ResourceLocation.parse(id) to value }
        val passengerCopies = passengers.toMutableSet()
        operations += { world, profile ->
            val entity = SandboxEntity(
                uuid = uuid ?: UUID.randomUUID().toString(),
                type = type,
                position = position,
                tags = tags.toMutableSet(),
                yaw = yaw,
                pitch = pitch,
                dimension = dimension,
                vehicle = vehicle,
                passengers = passengerCopies.toMutableSet(),
            )
            val hasNbt = nbt != null && nbt.entrySet().isNotEmpty()
            if (hasNbt || health != null) {
                val full = entity.fullNbt(profile)
                if (nbt != null) JsonPaths.merge(full, null, nbt)
                health?.let { full.addProperty("Health", it) }
                entity.writeFullNbt(profile, full)
            } else {
                entity.fullNbt(profile)
            }
            equipmentCopies.forEach { (rawSlot, item) ->
                val slot = EquipmentSlots.canonical(rawSlot)
                    ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Entity equipment slot '$rawSlot' is not supported")
                entity.equipment[slot] = item.copyForSetup()
            }
            effectCopies.forEach { effect -> entity.activeEffects[effect.id] = effect.copy() }
            attributeCopies.forEach { (id, value) -> entity.attributes[id] = value }
            world.entities += entity
        }
    }

    /**
     * Creates or replaces a player fixture.
     *
     * Player NBT is readable at runtime but not directly writable through the
     * `data` command. Use this fixture or player events to establish player
     * state before behavior runs.
     *
     * @return this setup for fluent chaining.
     */
    @JvmOverloads
    fun player(
        name: String,
        x: Double = 0.0,
        y: Double = 0.0,
        z: Double = 0.0,
        dimension: String = "minecraft:overworld",
        gameMode: String = "survival",
        xp: Int = 0,
        xpLevels: Int = 0,
        health: Double = 20.0,
        food: Int = 20,
        selectedSlot: Int = 0,
        inventory: Iterable<ItemStack> = emptyList(),
        enderItems: Iterable<ItemStack> = emptyList(),
    ): SandboxWorldSetup = apply {
        operations += { world, _ ->
            val player = world.createPlayer(name)
            player.position = Position(x, y, z)
            player.dimension = ResourceLocation.parse(dimension)
            player.gameMode = gameMode
            player.xp = xp
            player.xpLevels = xpLevels
            player.health = health
            player.food = food
            player.selectedSlot = selectedSlot.coerceAtLeast(0)
            player.inventory.clear()
            player.inventory += inventory.map { it.copy(components = it.components.deepCopy(), nbt = it.nbt.deepCopy()) }
            player.enderItems.clear()
            player.enderItems += enderItems.map { it.copy(components = it.components.deepCopy(), nbt = it.nbt.deepCopy()) }
        }
    }

    /**
     * Adds a recipe to an existing or newly-created player fixture.
     *
     * @return this setup for fluent chaining.
     */
    fun playerRecipe(name: String, recipe: String): SandboxWorldSetup = apply {
        operations += { world, _ -> world.createPlayer(name).recipes += ResourceLocation.parse(recipe) }
    }

    /**
     * Adds a stat value to an existing or newly-created player fixture.
     *
     * @return this setup for fluent chaining.
     */
    fun playerStat(name: String, stat: String, value: Int): SandboxWorldSetup = apply {
        operations += { world, _ -> world.createPlayer(name).stats[ResourceLocation.parse(stat)] = value }
    }

    /**
     * Sets one advancement criterion state on an existing or newly-created player fixture.
     *
     * @return this setup for fluent chaining.
     */
    @JvmOverloads
    fun playerAdvancementCriterion(
        name: String,
        advancement: String,
        criterion: String,
        done: Boolean = true,
    ): SandboxWorldSetup = apply {
        operations += { world, _ ->
            val id = ResourceLocation.parse(advancement)
            val progress = world.createPlayer(name).advancementProgress.getOrPut(id) { AdvancementProgress() }
            progress.criteria[criterion] = done
        }
    }

    /**
     * Sets multiple advancement criterion states on an existing or newly-created player fixture.
     *
     * @return this setup for fluent chaining.
     */
    fun playerAdvancement(name: String, advancement: String, criteria: Map<String, Boolean>): SandboxWorldSetup = apply {
        operations += { world, _ ->
            val id = ResourceLocation.parse(advancement)
            val progress = world.createPlayer(name).advancementProgress.getOrPut(id) { AdvancementProgress() }
            criteria.forEach { (criterion, done) -> progress.criteria[criterion] = done }
        }
    }

    /**
     * Adds an active effect to an existing or newly-created player fixture.
     *
     * @return this setup for fluent chaining.
     */
    @JvmOverloads
    fun playerEffect(name: String, effect: String, durationTicks: Int = -1, amplifier: Int = 0, hideParticles: Boolean = false): SandboxWorldSetup = apply {
        operations += { world, _ ->
            val id = ResourceLocation.parse(effect)
            val player = world.createPlayer(name)
            player.effects += id
            player.effectDetails[id] = PlayerEffect(id, durationTicks, amplifier, hideParticles)
        }
    }

    /**
     * Sets a player's spawn point.
     *
     * @return this setup for fluent chaining.
     */
    @JvmOverloads
    fun playerSpawn(
        name: String,
        x: Double,
        y: Double,
        z: Double,
        dimension: String = "minecraft:overworld",
        angle: Double? = null,
        forced: Boolean = false,
    ): SandboxWorldSetup = apply {
        operations += { world, _ ->
            world.createPlayer(name).spawnPoint = SpawnPoint(
                position = Position(x, y, z),
                dimension = ResourceLocation.parse(dimension),
                angle = angle,
                forced = forced,
            )
        }
    }

    /**
     * Creates an [ItemStack] helper for player inventories and assertions.
     *
     * The returned stack owns deep copies of [components] and [nbt].
     */
    @JvmOverloads
    fun item(id: String, count: Int = 1, components: JsonObject = JsonObject(), nbt: JsonObject = JsonObject()): ItemStack =
        ItemStack(ResourceLocation.parse(id), count, components.deepCopy(), nbt.deepCopy())

    /**
     * Creates an active effect helper for non-player entity fixtures.
     */
    @JvmOverloads
    fun effect(id: String, durationTicks: Int = -1, amplifier: Int = 0, hideParticles: Boolean = false): PlayerEffect =
        PlayerEffect(ResourceLocation.parse(id), durationTicks, amplifier, hideParticles)

    /**
     * Creates an objective if necessary and sets a scoreboard value.
     *
     * @return this setup for fluent chaining.
     */
    @JvmOverloads
    fun score(target: String, objective: String, value: Int, criteria: String = "dummy"): SandboxWorldSetup = apply {
        operations += { world, _ ->
            if (!world.objectives.containsKey(objective)) world.addObjective(objective, criteria)
            world.setScore(target, objective, value)
        }
    }

    /**
     * Sets a storage object from SNBT/JSON text.
     *
     * @param id Storage id such as `demo:state`.
     * @param value Object value to store.
     * @return this setup for fluent chaining.
     */
    fun storage(id: String, value: String): SandboxWorldSetup =
        storage(ResourceLocation.parse(id), JsonValues.parse(value))

    /**
     * Sets a storage object from a parsed JSON element.
     *
     * @throws SandboxException when [value] is not an object.
     * @return this setup for fluent chaining.
     */
    fun storage(id: ResourceLocation, value: JsonElement): SandboxWorldSetup = apply {
        operations += { world, _ ->
            if (!value.isJsonObject) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Storage setup value for $id must be an object")
            }
            world.storages[id] = value.asJsonObject.deepCopy()
        }
    }

    /**
     * Stores a gamerule value as a string.
     *
     * The sandbox tracks gamerule state for tests but does not execute most
     * vanilla gameplay side effects of individual gamerules.
     *
     * @return this setup for fluent chaining.
     */
    fun gamerule(name: String, value: String): SandboxWorldSetup = apply {
        operations += { world, _ -> world.gamerules[name] = value }
    }

    /**
     * Creates or replaces a team fixture.
     *
     * @return this setup for fluent chaining.
     */
    @JvmOverloads
    fun team(
        name: String,
        displayName: String = name,
        members: Iterable<String> = emptyList(),
        options: Map<String, String> = emptyMap(),
    ): SandboxWorldSetup = apply {
        operations += { world, _ ->
            world.teams[name] = SandboxTeam(
                name = name,
                displayName = displayName,
                members = members.toMutableSet().toSortedSet(),
                options = options.toMutableMap(),
            )
        }
    }

    /**
     * Creates or replaces a bossbar fixture.
     *
     * @return this setup for fluent chaining.
     */
    @JvmOverloads
    fun bossbar(
        id: String,
        name: String,
        value: Int = 0,
        max: Int = 100,
        color: String = "white",
        style: String = "progress",
        visible: Boolean = true,
        players: Iterable<String> = emptyList(),
    ): SandboxWorldSetup = apply {
        operations += { world, _ ->
            world.bossbars[ResourceLocation.parse(id)] = SandboxBossbar(
                id = ResourceLocation.parse(id),
                name = name,
                value = value,
                max = max,
                color = color,
                style = style,
                visible = visible,
                players = players.toMutableSet().toSortedSet(),
            )
        }
    }

    /**
     * Imports selected chunks from an existing Java Edition save.
     *
     * Importing is scoped and deterministic: only [chunks] are read, and the
     * include flags decide whether block states, block entities, and entities
     * are imported. Playerdata, lighting, POI, and scheduled block ticks are not
     * imported.
     *
     * @return this setup for fluent chaining.
     */
    @JvmOverloads
    fun importSave(
        path: Path,
        chunks: Iterable<ChunkPos>,
        dimension: String = "minecraft:overworld",
        includeBlocks: Boolean = true,
        includeBlockEntities: Boolean = true,
        includeEntities: Boolean = true,
    ): SandboxWorldSetup = apply {
        val chunkList = chunks.toList()
        operations += { world, profile ->
            MinecraftSaveImporter.importInto(
                world = world,
                profile = profile,
                options = MinecraftSaveImportOptions(
                    path = path,
                    dimension = ResourceLocation.parse(dimension),
                    chunks = chunkList,
                    includeBlocks = includeBlocks,
                    includeBlockEntities = includeBlockEntities,
                    includeEntities = includeEntities,
                ),
            )
        }
    }

    /**
     * Imports every chunk touched by the inclusive block range [from]..[to].
     *
     * This is a convenience overload over [importSave] when fixture boundaries
     * are easier to express in block coordinates.
     *
     * @return this setup for fluent chaining.
     */
    @JvmOverloads
    fun importSave(
        path: Path,
        from: BlockPos,
        to: BlockPos,
        dimension: String = "minecraft:overworld",
        includeBlocks: Boolean = true,
        includeBlockEntities: Boolean = true,
        includeEntities: Boolean = true,
    ): SandboxWorldSetup =
        importSave(path, chunksInBlockRange(from, to), dimension, includeBlocks, includeBlockEntities, includeEntities)
}

private fun ItemStack.copyForSetup(): ItemStack =
    copy(components = components.deepCopy(), nbt = nbt.deepCopy())

/**
 * Applies a [SandboxWorldSetup] to this world with [profile] validation.
 */
fun SandboxWorld.applySetup(setup: SandboxWorldSetup, profile: VersionProfile) {
    setup.applyTo(this, profile)
}

/**
 * Returns all chunk coordinates intersecting the inclusive block range.
 */
fun chunksInBlockRange(from: BlockPos, to: BlockPos): List<ChunkPos> {
    val minX = minOf(from.x, to.x).floorDiv16()
    val maxX = maxOf(from.x, to.x).floorDiv16()
    val minZ = minOf(from.z, to.z).floorDiv16()
    val maxZ = maxOf(from.z, to.z).floorDiv16()
    val chunks = mutableListOf<ChunkPos>()
    for (z in minZ..maxZ) for (x in minX..maxX) chunks += ChunkPos(x, z)
    return chunks
}

private fun Int.floorDiv16(): Int = Math.floorDiv(this, 16)
