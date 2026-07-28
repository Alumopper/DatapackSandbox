package moe.afox.dpsandbox.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

internal data class NamedNbt(
    val name: String,
    val value: JsonElement,
)

internal data class BinaryNbtLimits(
    val maximumBytes: Long = 64L * 1024L * 1024L,
    val maximumElements: Int = 2_000_000,
    val maximumDepth: Int = 512,
) {
    init {
        require(maximumBytes > 0) { "NBT byte limit must be positive" }
        require(maximumElements > 0) { "NBT element limit must be positive" }
        require(maximumDepth > 0) { "NBT depth limit must be positive" }
    }
}

internal object BinaryNbt {
    private const val TAG_END = 0
    private const val TAG_BYTE = 1
    private const val TAG_SHORT = 2
    private const val TAG_INT = 3
    private const val TAG_LONG = 4
    private const val TAG_FLOAT = 5
    private const val TAG_DOUBLE = 6
    private const val TAG_BYTE_ARRAY = 7
    private const val TAG_STRING = 8
    private const val TAG_LIST = 9
    private const val TAG_COMPOUND = 10
    private const val TAG_INT_ARRAY = 11
    private const val TAG_LONG_ARRAY = 12

    fun read(
        input: InputStream,
        limits: BinaryNbtLimits = BinaryNbtLimits(),
    ): NamedNbt =
        try {
            Reader(input, limits).read()
        } catch (error: SandboxException) {
            throw error
        } catch (error: EOFException) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Unexpected end of NBT input", cause = error)
        } catch (error: IOException) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Unable to read NBT input: ${error.message}", cause = error)
        }

    private class Reader(
        input: InputStream,
        private val limits: BinaryNbtLimits,
    ) {
        private val data = DataInputStream(LimitedInputStream(input, limits.maximumBytes))
        private var remainingElements = limits.maximumElements

        fun read(): NamedNbt {
            val type = data.readUnsignedByte()
            if (type == TAG_END) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "NBT root cannot be TAG_End")
            consumeElements(1)
            val name = readString()
            return NamedNbt(name, readPayload(type, 0))
        }

        private fun readPayload(
            type: Int,
            depth: Int,
        ): JsonElement =
            when (type) {
                TAG_BYTE -> JsonPrimitive(data.readByte().toInt())
                TAG_SHORT -> JsonPrimitive(data.readShort().toInt())
                TAG_INT -> JsonPrimitive(data.readInt())
                TAG_LONG -> JsonPrimitive(data.readLong())
                TAG_FLOAT -> JsonPrimitive(data.readFloat())
                TAG_DOUBLE -> JsonPrimitive(data.readDouble())
                TAG_BYTE_ARRAY ->
                    JsonArray().also { array ->
                        repeat(readLength("byte array")) { array.add(data.readByte().toInt()) }
                    }
                TAG_STRING -> JsonPrimitive(readString())
                TAG_LIST -> readList(depth)
                TAG_COMPOUND -> readCompound(depth)
                TAG_INT_ARRAY ->
                    JsonArray().also { array ->
                        repeat(readLength("int array")) { array.add(data.readInt()) }
                    }
                TAG_LONG_ARRAY ->
                    JsonArray().also { array ->
                        repeat(readLength("long array")) { array.add(data.readLong()) }
                    }
                else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Unknown NBT tag type $type")
            }

        private fun readList(depth: Int): JsonArray {
            requireDepth(depth)
            val childType = data.readUnsignedByte()
            val length = readLength("list")
            if (childType == TAG_END && length > 0) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "NBT list cannot contain TAG_End entries")
            }
            return JsonArray().also { array ->
                repeat(length) { array.add(readPayload(childType, depth + 1)) }
            }
        }

        private fun readCompound(depth: Int): JsonObject {
            requireDepth(depth)
            val json = JsonObject()
            while (true) {
                val type = data.readUnsignedByte()
                if (type == TAG_END) return json
                consumeElements(1)
                val name = readString()
                json.add(name, readPayload(type, depth + 1))
            }
        }

        private fun readLength(label: String): Int {
            val length = data.readInt()
            if (length < 0) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "NBT $label length cannot be negative")
            consumeElements(length)
            return length
        }

        private fun readString(): String {
            val length = data.readUnsignedShort()
            val bytes = data.readNBytes(length)
            if (bytes.size != length) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Unexpected end of NBT string")
            return String(bytes, StandardCharsets.UTF_8)
        }

        private fun consumeElements(count: Int) {
            if (count > remainingElements) {
                throw SandboxException(
                    DiagnosticCode.INPUT_FORMAT,
                    "NBT element count exceeds limit ${limits.maximumElements}",
                )
            }
            remainingElements -= count
        }

        private fun requireDepth(depth: Int) {
            if (depth >= limits.maximumDepth) {
                throw SandboxException(
                    DiagnosticCode.INPUT_FORMAT,
                    "NBT nesting depth exceeds limit ${limits.maximumDepth}",
                )
            }
        }
    }

    private class LimitedInputStream(
        private val delegate: InputStream,
        private val maximumBytes: Long,
    ) : InputStream() {
        private var consumed = 0L

        override fun read(): Int {
            val value = delegate.read()
            if (value < 0) return value
            recordBytes(1)
            return value
        }

        override fun read(
            target: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            val read = delegate.read(target, offset, length)
            if (read > 0) recordBytes(read)
            return read
        }

        private fun recordBytes(count: Int) {
            consumed += count
            if (consumed > maximumBytes) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "NBT input exceeds byte limit $maximumBytes")
            }
        }
    }
}
