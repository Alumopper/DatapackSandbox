package moe.afox.dpsandbox.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.charset.StandardCharsets

internal data class NamedNbt(
    val name: String,
    val value: JsonElement,
)

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

    fun read(input: InputStream): NamedNbt {
        val data = DataInputStream(input)
        val type = data.readUnsignedByte()
        if (type == TAG_END) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "NBT root cannot be TAG_End")
        val name = readString(data)
        return NamedNbt(name, readPayload(data, type))
    }

    private fun readPayload(
        data: DataInputStream,
        type: Int,
    ): JsonElement =
        when (type) {
            TAG_BYTE -> JsonPrimitive(data.readByte().toInt())
            TAG_SHORT -> JsonPrimitive(data.readShort().toInt())
            TAG_INT -> JsonPrimitive(data.readInt())
            TAG_LONG -> JsonPrimitive(data.readLong())
            TAG_FLOAT -> JsonPrimitive(data.readFloat())
            TAG_DOUBLE -> JsonPrimitive(data.readDouble())
            TAG_BYTE_ARRAY -> JsonArray().also { array -> repeat(readLength(data, "byte array")) { array.add(data.readByte().toInt()) } }
            TAG_STRING -> JsonPrimitive(readString(data))
            TAG_LIST -> readList(data)
            TAG_COMPOUND -> readCompound(data)
            TAG_INT_ARRAY -> JsonArray().also { array -> repeat(readLength(data, "int array")) { array.add(data.readInt()) } }
            TAG_LONG_ARRAY -> JsonArray().also { array -> repeat(readLength(data, "long array")) { array.add(data.readLong()) } }
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Unknown NBT tag type $type")
        }

    private fun readList(data: DataInputStream): JsonArray {
        val childType = data.readUnsignedByte()
        val length = readLength(data, "list")
        val array = JsonArray()
        repeat(length) {
            if (childType == TAG_END) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "NBT list cannot contain TAG_End entries")
            }
            array.add(readPayload(data, childType))
        }
        return array
    }

    private fun readCompound(data: DataInputStream): JsonObject {
        val json = JsonObject()
        while (true) {
            val type =
                try {
                    data.readUnsignedByte()
                } catch (error: EOFException) {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Unexpected end of NBT compound", cause = error)
                }
            if (type == TAG_END) return json
            val name = readString(data)
            json.add(name, readPayload(data, type))
        }
    }

    private fun readLength(
        data: DataInputStream,
        label: String,
    ): Int {
        val length = data.readInt()
        if (length < 0) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "NBT $label length cannot be negative")
        return length
    }

    private fun readString(data: DataInputStream): String {
        val length = data.readUnsignedShort()
        val bytes = data.readNBytes(length)
        if (bytes.size != length) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Unexpected end of NBT string")
        return String(bytes, StandardCharsets.UTF_8)
    }
}
