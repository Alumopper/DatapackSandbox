package moe.afox.dpsandbox.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.ceil
import kotlin.math.ln

/**
 * Options for importing selected chunks from a Java Edition save.
 *
 * The importer is fixture-oriented. It only reads the requested chunks and the
 * selected content groups; it does not load a complete world save.
 */
data class MinecraftSaveImportOptions(
    /** Root directory of the Minecraft save. */
    val path: Path,
    /** Dimension id to import from. */
    val dimension: ResourceLocation = ResourceLocation("minecraft", "overworld"),
    /** Chunk coordinates to import. */
    val chunks: List<ChunkPos>,
    /** Whether to import non-air block states from chunk section palettes. */
    val includeBlocks: Boolean = true,
    /** Whether to import block entity NBT and merge it into matching imported blocks. */
    val includeBlockEntities: Boolean = true,
    /** Whether to import non-player entities from entity region files. */
    val includeEntities: Boolean = true,
)

/**
 * Summary returned by [MinecraftSaveImporter.importInto].
 */
data class MinecraftSaveImportResult(
    /** Number of requested chunks that were found in at least one region file. */
    val chunksRead: Int,
    /** Number of non-air block states imported. */
    val blocksImported: Int,
    /** Number of block entity NBT records imported. */
    val blockEntitiesImported: Int,
    /** Number of entities imported. */
    val entitiesImported: Int,
)

/**
 * Imports selected Java Edition Anvil save data into an existing sandbox world.
 */
object MinecraftSaveImporter {
    /**
     * Imports data described by [options] into [world].
     *
     * Imported NBT is validated through the supplied [profile]. Missing region
     * files are skipped, but invalid paths, empty chunk lists, malformed NBT, or
     * schema-invalid entity/block-entity data throw [SandboxException].
     *
     * @return counts describing what was imported.
     */
    fun importInto(
        world: SandboxWorld,
        profile: VersionProfile,
        options: MinecraftSaveImportOptions,
    ): MinecraftSaveImportResult {
        val saveRoot = options.path.toAbsolutePath().normalize()
        if (!Files.isDirectory(saveRoot)) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Minecraft save path is not a directory: $saveRoot", version = profile.id)
        }
        val chunks = options.chunks.distinct().sorted()
        if (chunks.isEmpty()) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "Minecraft save import requires at least one chunk or a from/to block range",
                version = profile.id,
            )
        }

        val dimensionRoot = dimensionRoot(saveRoot, options.dimension)
        val regionCache = mutableMapOf<Path, AnvilRegionFile?>()
        var chunksRead = 0
        var blocks = 0
        var blockEntities = 0
        var entities = 0

        chunks.forEach { chunk ->
            var loadedChunk = false
            if (options.includeBlocks || options.includeBlockEntities) {
                val region = regionFile(dimensionRoot.resolve("region"), chunk, regionCache)
                val chunkNbt = region?.readChunk(chunk)
                if (chunkNbt != null) {
                    loadedChunk = true
                    if (options.includeBlocks) blocks += importBlocks(chunkNbt, chunk, world)
                    if (options.includeBlockEntities) blockEntities += importBlockEntities(chunkNbt, world, profile)
                }
            }
            if (options.includeEntities) {
                val entityRegion = regionFile(dimensionRoot.resolve("entities"), chunk, regionCache)
                val entityChunkNbt = entityRegion?.readChunk(chunk)
                if (entityChunkNbt != null) {
                    loadedChunk = true
                    entities += importEntities(entityChunkNbt, world, profile, options.dimension)
                }
            }
            if (loadedChunk) chunksRead += 1
        }

        return MinecraftSaveImportResult(chunksRead, blocks, blockEntities, entities)
    }

    private fun importBlocks(
        chunkNbt: JsonObject,
        requestedChunk: ChunkPos,
        world: SandboxWorld,
    ): Int {
        val sections = sections(chunkNbt)
        val chunkX = chunkNbt.int("xPos") ?: chunkNbt.obj("Level")?.int("xPos") ?: requestedChunk.x
        val chunkZ = chunkNbt.int("zPos") ?: chunkNbt.obj("Level")?.int("zPos") ?: requestedChunk.z
        var imported = 0

        sections.forEach { sectionElement ->
            if (!sectionElement.isJsonObject) return@forEach
            val section = sectionElement.asJsonObject
            val sectionY = section.int("Y") ?: section.int("y") ?: return@forEach
            val states = section.obj("block_states")
            val palette = states?.array("palette") ?: section.array("Palette") ?: return@forEach
            if (palette.size() == 0) return@forEach
            val packed = states?.array("data") ?: section.array("BlockStates")
            val bits = bitsPerBlock(palette.size())
            for (index in 0 until 4096) {
                val paletteIndex =
                    if (palette.size() == 1) {
                        0
                    } else {
                        packedIndex(
                            packed
                                ?: throw SandboxException(
                                    DiagnosticCode.INPUT_FORMAT,
                                    "Chunk section has palette size ${palette.size()} but no block state data",
                                ),
                            index,
                            bits,
                        )
                    }
                val state = palette.getOrNull(paletteIndex)?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val id = state.string("Name")?.let { ResourceLocation.parse(it) } ?: continue
                if (id.path == "air" || id.path == "cave_air" || id.path == "void_air") continue

                val localX = index and 15
                val localZ = (index shr 4) and 15
                val localY = (index shr 8) and 15
                val pos = BlockPos(chunkX * 16 + localX, sectionY * 16 + localY, chunkZ * 16 + localZ)
                val properties = linkedMapOf<String, String>()
                state.obj("Properties")?.entrySet()?.forEach { (key, value) ->
                    if (value.isJsonPrimitive) properties[key] = value.asString
                }
                world.setBlock(pos, SandboxBlock(id, properties))
                imported += 1
            }
        }
        return imported
    }

    private fun importBlockEntities(
        chunkNbt: JsonObject,
        world: SandboxWorld,
        profile: VersionProfile,
    ): Int {
        val blockEntities =
            chunkNbt.array("block_entities")
                ?: chunkNbt.obj("Level")?.array("TileEntities")
                ?: chunkNbt.array("TileEntities")
                ?: return 0
        var imported = 0
        blockEntities.forEach { element ->
            if (!element.isJsonObject) return@forEach
            val nbt = element.asJsonObject
            val pos = BlockPos(nbt.int("x") ?: return@forEach, nbt.int("y") ?: return@forEach, nbt.int("z") ?: return@forEach)
            val block =
                world.block(pos)
                    ?: throw SandboxException(
                        DiagnosticCode.INPUT_FORMAT,
                        "Block entity at $pos has no matching block; import blocks with block entities",
                    )
            val full = block.fullNbt(pos, profile)
            JsonPaths.merge(full, null, nbt)
            block.writeFullNbt(pos, profile, full)
            imported += 1
        }
        return imported
    }

    private fun importEntities(
        chunkNbt: JsonObject,
        world: SandboxWorld,
        profile: VersionProfile,
        dimension: ResourceLocation,
    ): Int {
        val entities =
            chunkNbt.array("Entities")
                ?: chunkNbt.array("entities")
                ?: chunkNbt.obj("Level")?.array("Entities")
                ?: return 0
        var imported = 0
        entities.forEach { element ->
            if (!element.isJsonObject) return@forEach
            val nbt = element.asJsonObject
            val id = nbt.string("id")?.let { ResourceLocation.parse(it) } ?: return@forEach
            val position = nbt.array("Pos")?.toPosition() ?: Position.zero
            val rotation = nbt.array("Rotation")
            val tags = nbt.array("Tags")?.mapNotNullTo(sortedSetOf()) { if (it.isJsonPrimitive) it.asString else null } ?: sortedSetOf()
            val uuid =
                nbt.get("UUID")?.let { JsonValues.render(it) } ?: java.util.UUID
                    .randomUUID()
                    .toString()
            val entity =
                SandboxEntity(
                    uuid = uuid,
                    type = id,
                    position = position,
                    tags = tags,
                    yaw = rotation?.getOrNull(0)?.asDouble ?: 0.0,
                    pitch = rotation?.getOrNull(1)?.asDouble ?: 0.0,
                    dimension = dimension,
                )
            entity.writeFullNbt(profile, nbt)
            world.entities += entity
            imported += 1
        }
        return imported
    }

    private fun regionFile(
        regionDir: Path,
        chunk: ChunkPos,
        cache: MutableMap<Path, AnvilRegionFile?>,
    ): AnvilRegionFile? {
        val regionX = Math.floorDiv(chunk.x, 32)
        val regionZ = Math.floorDiv(chunk.z, 32)
        val path = regionDir.resolve("r.$regionX.$regionZ.mca")
        return cache.getOrPut(path) {
            if (Files.exists(path)) AnvilRegionFile(path) else null
        }
    }

    private fun dimensionRoot(
        saveRoot: Path,
        dimension: ResourceLocation,
    ): Path =
        when (dimension.toString()) {
            "minecraft:overworld" -> saveRoot
            "minecraft:the_nether" -> saveRoot.resolve("DIM-1")
            "minecraft:the_end" -> saveRoot.resolve("DIM1")
            else ->
                saveRoot
                    .resolve(
                        "dimensions",
                    ).resolve(dimension.namespace)
                    .resolve(dimension.path.replace('/', java.io.File.separatorChar))
        }

    private fun sections(chunkNbt: JsonObject): JsonArray =
        chunkNbt.array("sections")
            ?: chunkNbt.obj("Level")?.array("Sections")
            ?: chunkNbt.array("Sections")
            ?: JsonArray()

    private fun bitsPerBlock(paletteSize: Int): Int = maxOf(4, ceil(ln(paletteSize.toDouble()) / ln(2.0)).toInt())

    private fun packedIndex(
        data: JsonArray,
        index: Int,
        bits: Int,
    ): Int {
        val longs = LongArray(data.size()) { data[it].asLong }
        val bitIndex = index * bits
        val startLong = bitIndex / 64
        val startOffset = bitIndex % 64
        val mask = (1L shl bits) - 1L
        var value = longs[startLong] ushr startOffset
        if (startOffset + bits > 64) {
            value = value or (longs[startLong + 1] shl (64 - startOffset))
        }
        return (value and mask).toInt()
    }
}

private fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.array(name: String): JsonArray? = get(name)?.takeIf { it.isJsonArray }?.asJsonArray

private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString

private fun JsonObject.int(name: String): Int? = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

private fun JsonArray.getOrNull(index: Int): JsonElement? = if (index in 0 until size()) get(index) else null

private fun JsonArray.toPosition(): Position =
    if (size() >= 3) Position(get(0).asDouble, get(1).asDouble, get(2).asDouble) else Position.zero
