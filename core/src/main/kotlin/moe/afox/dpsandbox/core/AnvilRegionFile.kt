package moe.afox.dpsandbox.core

import com.google.gson.JsonObject
import net.jpountz.lz4.LZ4BlockInputStream
import net.jpountz.lz4.LZ4Factory
import net.jpountz.xxhash.XXHashFactory
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

internal class AnvilRegionFile(
    path: Path,
) {
    private val bytes = Files.readAllBytes(path)

    fun readChunk(chunk: ChunkPos): JsonObject? {
        if (bytes.size < ANVIL_HEADER_BYTES) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Region file is too small to contain an Anvil header")
        }

        val location = locationEntry(chunk)
        if (location == 0) return null

        val sectorOffset = location ushr 8
        val sectorCount = location and 0xFF
        val chunkOffset = sectorOffset * ANVIL_SECTOR_BYTES
        if (sectorOffset <= 1 || sectorCount <= 0 || chunkOffset + 5 > bytes.size) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid region location entry for chunk ${chunk.x},${chunk.z}")
        }

        val length = readInt(chunkOffset)
        if (length <= 1 || chunkOffset + 4 + length > bytes.size) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid chunk length for chunk ${chunk.x},${chunk.z}")
        }

        val compression = bytes[chunkOffset + 4].toInt() and 0xFF
        val payload = bytes.copyOfRange(chunkOffset + 5, chunkOffset + 4 + length)
        val root = decompressed(payload, compression).use { BinaryNbt.read(it).value }
        if (!root.isJsonObject) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Chunk root NBT must be a compound")
        }
        return root.asJsonObject
    }

    private fun locationEntry(chunk: ChunkPos): Int {
        val localX = Math.floorMod(chunk.x, ANVIL_REGION_CHUNKS)
        val localZ = Math.floorMod(chunk.z, ANVIL_REGION_CHUNKS)
        return readInt((localX + localZ * ANVIL_REGION_CHUNKS) * 4)
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

    private fun readInt(offset: Int): Int = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int

    private companion object {
        const val ANVIL_HEADER_BYTES = 8192
        const val ANVIL_SECTOR_BYTES = 4096
        const val ANVIL_REGION_CHUNKS = 32
        const val LZ4_CHECKSUM_SEED: Int = -0x68b84d74
    }
}
