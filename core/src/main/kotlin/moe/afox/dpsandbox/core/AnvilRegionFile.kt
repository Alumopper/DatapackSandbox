package moe.afox.dpsandbox.core

import com.google.gson.JsonObject
import net.jpountz.lz4.LZ4BlockInputStream
import net.jpountz.lz4.LZ4Factory
import net.jpountz.xxhash.XXHashFactory
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

internal class AnvilRegionFile(
    path: Path,
) : Closeable {
    private val channel = FileChannel.open(path, StandardOpenOption.READ)
    private val fileSize = channel.size()

    init {
        if (fileSize < ANVIL_HEADER_BYTES) {
            close()
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Region file is too small to contain an Anvil header")
        }
    }

    fun readChunk(chunk: ChunkPos): JsonObject? {
        val location = locationEntry(chunk)
        if (location == 0) return null

        val sectorOffset = location ushr 8
        val sectorCount = location and 0xFF
        val chunkOffset = sectorOffset.toLong() * ANVIL_SECTOR_BYTES
        if (sectorOffset <= 1 || sectorCount <= 0 || chunkOffset > fileSize - CHUNK_HEADER_BYTES) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid region location entry for chunk ${chunk.x},${chunk.z}")
        }

        val length = readInt(chunkOffset)
        val maximumLength = sectorCount * ANVIL_SECTOR_BYTES - Int.SIZE_BYTES
        if (length <= 1 || length > maximumLength || length.toLong() > fileSize - chunkOffset - Int.SIZE_BYTES) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid chunk length for chunk ${chunk.x},${chunk.z}")
        }

        val chunkData = readBytes(chunkOffset + Int.SIZE_BYTES, length)
        val compression = chunkData[0].toInt() and 0xFF
        val payload = chunkData.copyOfRange(1, chunkData.size)
        val root = decompressed(payload, compression).use { BinaryNbt.read(it).value }
        if (!root.isJsonObject) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Chunk root NBT must be a compound")
        }
        return root.asJsonObject
    }

    private fun locationEntry(chunk: ChunkPos): Int {
        val localX = Math.floorMod(chunk.x, ANVIL_REGION_CHUNKS)
        val localZ = Math.floorMod(chunk.z, ANVIL_REGION_CHUNKS)
        return readInt((localX + localZ * ANVIL_REGION_CHUNKS).toLong() * Int.SIZE_BYTES)
    }

    private fun decompressed(
        payload: ByteArray,
        compression: Int,
    ) = when (compression) {
        1 -> GZIPInputStream(ByteArrayInputStream(payload))
        2 -> InflaterInputStream(ByteArrayInputStream(payload))
        3 -> ByteArrayInputStream(payload)
        4 ->
            LZ4BlockInputStream(
                ByteArrayInputStream(payload),
                LZ4Factory.safeInstance().fastDecompressor(),
                XXHashFactory.safeInstance().newStreamingHash32(LZ4_CHECKSUM_SEED).asChecksum(),
            )
        else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Unknown Anvil chunk compression type $compression")
    }

    private fun readInt(offset: Long): Int = ByteBuffer.wrap(readBytes(offset, Int.SIZE_BYTES)).order(ByteOrder.BIG_ENDIAN).int

    private fun readBytes(
        offset: Long,
        length: Int,
    ): ByteArray {
        val target = ByteBuffer.allocate(length)
        var position = offset
        while (target.hasRemaining()) {
            val read = channel.read(target, position)
            if (read < 0) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Unexpected end of Anvil region file")
            }
            if (read == 0) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Unable to make progress while reading Anvil region file")
            }
            position += read
        }
        return target.array()
    }

    override fun close() {
        channel.close()
    }

    private companion object {
        const val ANVIL_HEADER_BYTES = 8192L
        const val ANVIL_SECTOR_BYTES = 4096
        const val CHUNK_HEADER_BYTES = 5L
        const val ANVIL_REGION_CHUNKS = 32
        const val LZ4_CHECKSUM_SEED: Int = -0x68b84d74
    }
}
