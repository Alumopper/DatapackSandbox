package moe.afox.dpsandbox.core

import net.jpountz.lz4.LZ4BlockOutputStream
import net.jpountz.lz4.LZ4Factory
import net.jpountz.xxhash.XXHashFactory
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.DeflaterOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MinecraftSaveImporterTest {
    @Test
    fun `imports blocks and entities from Java Anvil save chunks`() {
        val save = Files.createTempDirectory("dps-save-import")
        writeRegion(save.resolve("region"), ChunkPos(0, 0), blockChunkNbt())
        writeRegion(save.resolve("entities"), ChunkPos(0, 0), entityChunkNbt())

        val world = SandboxWorld()
        val result =
            MinecraftSaveImporter.importInto(
                world,
                VersionProfiles.get("26.2"),
                MinecraftSaveImportOptions(
                    path = save,
                    chunks = listOf(ChunkPos(0, 0)),
                ),
            )

        assertEquals(1, result.chunksRead)
        assertEquals(4096, result.blocksImported)
        assertEquals(1, result.entitiesImported)
        assertEquals(ResourceLocation.parse("minecraft:stone"), world.block(BlockPos(0, 64, 0))?.id)
        assertTrue(world.entities.any { it.type == ResourceLocation.parse("minecraft:pig") && "imported" in it.tags })
    }

    @Test
    fun `imports lz4 compressed Anvil chunks`() {
        val save = Files.createTempDirectory("dps-save-import-lz4")
        writeRegion(save.resolve("region"), ChunkPos(0, 0), blockChunkNbt(), compression = 4)

        val world = SandboxWorld()
        MinecraftSaveImporter.importInto(
            world,
            VersionProfiles.get("26.2"),
            MinecraftSaveImportOptions(
                path = save,
                chunks = listOf(ChunkPos(0, 0)),
                includeEntities = false,
            ),
        )

        assertEquals(ResourceLocation.parse("minecraft:stone"), world.block(BlockPos(0, 64, 0))?.id)
    }

    private fun blockChunkNbt(): ByteArray =
        nbtRoot {
            tagInt("xPos", 0)
            tagInt("zPos", 0)
            tagListCompound("sections") {
                compound {
                    tagByte("Y", 4)
                    tagCompound("block_states") {
                        tagListCompound("palette") {
                            compound {
                                tagString("Name", "minecraft:stone")
                            }
                        }
                    }
                }
            }
        }

    private fun entityChunkNbt(): ByteArray =
        nbtRoot {
            tagListCompound("Entities") {
                compound {
                    tagString("id", "minecraft:pig")
                    tagListDouble("Pos", 2.0, 64.0, 2.0)
                    tagListString("Tags", "imported")
                }
            }
        }

    private fun writeRegion(
        regionDir: Path,
        chunk: ChunkPos,
        nbt: ByteArray,
        compression: Int = 2,
    ) {
        Files.createDirectories(regionDir)
        val payload =
            when (compression) {
                2 -> zlib(nbt)
                4 -> lz4(nbt)
                else -> error("Unsupported test compression $compression")
            }
        val chunkRecord = ByteArray(5 + payload.size)
        ByteBuffer.wrap(chunkRecord).order(ByteOrder.BIG_ENDIAN).putInt(payload.size + 1)
        chunkRecord[4] = compression.toByte()
        payload.copyInto(chunkRecord, destinationOffset = 5)

        val sectors = (chunkRecord.size + 4095) / 4096
        val bytes = ByteArray((2 + sectors) * 4096)
        val localX = Math.floorMod(chunk.x, 32)
        val localZ = Math.floorMod(chunk.z, 32)
        val locationIndex = (localX + localZ * 32) * 4
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putInt(locationIndex, (2 shl 8) or sectors)
        chunkRecord.copyInto(bytes, destinationOffset = 2 * 4096)

        val regionX = Math.floorDiv(chunk.x, 32)
        val regionZ = Math.floorDiv(chunk.z, 32)
        Files.write(regionDir.resolve("r.$regionX.$regionZ.mca"), bytes)
    }

    private fun zlib(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun lz4(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        LZ4BlockOutputStream(
            out,
            1 shl 16,
            LZ4Factory.safeInstance().fastCompressor(),
            XXHashFactory.safeInstance().newStreamingHash32(LZ4_CHECKSUM_SEED).asChecksum(),
            true,
        ).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun nbtRoot(block: NbtWriter.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.writeByte(TAG_COMPOUND)
        data.writeNbtString("")
        NbtWriter(data).compoundPayload(block)
        return out.toByteArray()
    }

    private class NbtWriter(
        private val data: DataOutputStream,
    ) {
        fun compoundPayload(block: NbtWriter.() -> Unit) {
            block()
            data.writeByte(TAG_END)
        }

        fun compound(block: NbtWriter.() -> Unit) {
            compoundPayload(block)
        }

        fun tagByte(
            name: String,
            value: Int,
        ) {
            tag(TAG_BYTE, name)
            data.writeByte(value)
        }

        fun tagInt(
            name: String,
            value: Int,
        ) {
            tag(TAG_INT, name)
            data.writeInt(value)
        }

        fun tagString(
            name: String,
            value: String,
        ) {
            tag(TAG_STRING, name)
            data.writeNbtString(value)
        }

        fun tagCompound(
            name: String,
            block: NbtWriter.() -> Unit,
        ) {
            tag(TAG_COMPOUND, name)
            compoundPayload(block)
        }

        fun tagListCompound(
            name: String,
            block: ListBuilder.() -> Unit,
        ) {
            val entries = ListBuilder().apply(block).entries
            tag(TAG_LIST, name)
            data.writeByte(TAG_COMPOUND)
            data.writeInt(entries.size)
            entries.forEach { compoundPayload(it) }
        }

        fun tagListDouble(
            name: String,
            vararg values: Double,
        ) {
            tag(TAG_LIST, name)
            data.writeByte(TAG_DOUBLE)
            data.writeInt(values.size)
            values.forEach(data::writeDouble)
        }

        fun tagListString(
            name: String,
            vararg values: String,
        ) {
            tag(TAG_LIST, name)
            data.writeByte(TAG_STRING)
            data.writeInt(values.size)
            values.forEach(data::writeNbtString)
        }

        private fun tag(
            type: Int,
            name: String,
        ) {
            data.writeByte(type)
            data.writeNbtString(name)
        }
    }

    private class ListBuilder {
        val entries = mutableListOf<NbtWriter.() -> Unit>()

        fun compound(block: NbtWriter.() -> Unit) {
            entries += block
        }
    }

    private companion object {
        const val TAG_END = 0
        const val TAG_BYTE = 1
        const val TAG_INT = 3
        const val TAG_DOUBLE = 6
        const val TAG_STRING = 8
        const val TAG_LIST = 9
        const val TAG_COMPOUND = 10
        const val LZ4_CHECKSUM_SEED: Int = -0x68b84d74
    }
}

private fun DataOutputStream.writeNbtString(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeShort(bytes.size)
    write(bytes)
}
