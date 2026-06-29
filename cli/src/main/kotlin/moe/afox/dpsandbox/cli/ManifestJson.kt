package moe.afox.dpsandbox.cli

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import moe.afox.dpsandbox.core.BlockPos
import moe.afox.dpsandbox.core.ChunkPos
import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.ItemStack
import moe.afox.dpsandbox.core.JsonValues
import moe.afox.dpsandbox.core.Position
import moe.afox.dpsandbox.core.ResourceLocation
import moe.afox.dpsandbox.core.SandboxException

internal fun JsonObject.manifestArray(name: String, label: String = "Manifest '$name'"): List<JsonElement> {
    val value = get(name) ?: return emptyList()
    if (!value.isJsonArray) {
        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must be an array")
    }
    return value.asJsonArray.toList()
}

internal fun JsonObject.manifestStringArray(name: String, label: String = "Manifest '$name'"): List<String> =
    manifestArray(name, label).map { element ->
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label entries must be strings")
        }
        element.asString
    }

internal fun JsonObject.manifestString(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive }?.asString

internal fun JsonObject.requiredManifestString(name: String): String =
    manifestString(name) ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Missing required string '$name'")

internal fun JsonObject.requiredManifestInt(name: String): Int =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
        ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Missing required number '$name'")

internal fun parseManifestBlockPos(array: JsonArray?, label: String = "Block position"): BlockPos {
    if (array == null || array.size() != 3) {
        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must contain three numbers")
    }
    return BlockPos(array[0].asInt, array[1].asInt, array[2].asInt)
}

internal fun parseManifestPosition(array: JsonArray?, label: String = "Position"): Position {
    if (array == null || array.size() != 3) {
        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must contain three numbers")
    }
    return Position(array[0].asDouble, array[1].asDouble, array[2].asDouble)
}

internal fun parseManifestChunk(array: JsonArray?, label: String = "Chunk position"): ChunkPos {
    if (array == null || array.size() != 2) {
        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must contain two numbers")
    }
    return ChunkPos(array[0].asInt, array[1].asInt)
}

internal fun parseManifestChunks(array: JsonArray?, label: String = "chunks"): List<ChunkPos> {
    if (array == null) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must be an array")
    return array.mapIndexed { index, element ->
        if (!element.isJsonArray) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label[$index] must be [x, z]")
        parseManifestChunk(element.asJsonArray, "$label[$index]")
    }
}

internal fun parseManifestNbtObject(value: JsonElement?, label: String = "NBT value"): JsonObject? =
    when {
        value == null -> null
        value.isJsonObject -> value.asJsonObject
        value.isJsonPrimitive && value.asJsonPrimitive.isString -> {
            val parsed = JsonValues.parse(value.asString)
            if (!parsed.isJsonObject) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must be an object")
            parsed.asJsonObject
        }
        else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must be an object or SNBT object string")
    }

internal fun parseManifestItem(json: JsonObject): ItemStack =
    ItemStack(
        id = ResourceLocation.parse(json.manifestString("id") ?: json.manifestString("item") ?: "minecraft:air"),
        count = json.get("count")?.asInt ?: 1,
        components = json.getAsJsonObject("components") ?: JsonObject(),
        nbt = parseManifestNbtObject(json.get("nbt"), "item nbt") ?: JsonObject(),
    )

internal fun manifestPrimitiveString(value: JsonElement): String =
    if (value.isJsonPrimitive) value.asString else JsonValues.render(value)
