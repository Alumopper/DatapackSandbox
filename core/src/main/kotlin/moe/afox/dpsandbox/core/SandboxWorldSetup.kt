package moe.afox.dpsandbox.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.nio.file.Path

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
    ): SandboxWorldSetup =
        entity(
            type = ResourceLocation.parse(type),
            position = Position(x, y, z),
            tags = tags,
            nbt = nbt?.let { JsonValues.parse(it).asJsonObject },
            yaw = yaw,
            pitch = pitch,
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
    ): SandboxWorldSetup = apply {
        operations += { world, profile ->
            val entity = SandboxEntity(type = type, position = position, tags = tags.toMutableSet(), yaw = yaw, pitch = pitch)
            if (nbt != null && nbt.entrySet().isNotEmpty()) {
                val full = entity.fullNbt(profile)
                JsonPaths.merge(full, null, nbt)
                entity.writeFullNbt(profile, full)
            } else {
                entity.fullNbt(profile)
            }
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
        health: Double = 20.0,
        food: Int = 20,
        selectedSlot: Int = 0,
        inventory: Iterable<ItemStack> = emptyList(),
    ): SandboxWorldSetup = apply {
        operations += { world, _ ->
            val player = world.createPlayer(name)
            player.position = Position(x, y, z)
            player.dimension = ResourceLocation.parse(dimension)
            player.gameMode = gameMode
            player.xp = xp
            player.health = health
            player.food = food
            player.selectedSlot = selectedSlot.coerceAtLeast(0)
            player.inventory.clear()
            player.inventory += inventory.map { it.copy(components = it.components.deepCopy(), nbt = it.nbt.deepCopy()) }
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
