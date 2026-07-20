package moe.afox.dpsandbox.playground

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets

internal data class PlaygroundRequest(
    val id: JsonElement,
    val type: String,
    val body: JsonObject,
) {
    companion object {
        fun parse(
            text: String,
            maximumBytes: Int,
        ): PlaygroundRequest {
            if (text.toByteArray(StandardCharsets.UTF_8).size > maximumBytes) {
                throw ProtocolException("REQUEST_TOO_LARGE", "Request exceeds the $maximumBytes byte frame limit")
            }
            val root =
                try {
                    JsonParser.parseString(text)
                } catch (error: Exception) {
                    throw ProtocolException("INVALID_REQUEST", "Request must be valid JSON", cause = error)
                }
            if (!root.isJsonObject) throw ProtocolException("INVALID_REQUEST", "Request must be a JSON object")
            val body = root.asJsonObject
            val id = body.get("id") ?: throw ProtocolException("INVALID_REQUEST", "Request is missing id")
            if (!id.isJsonPrimitive || (!id.asJsonPrimitive.isString && !id.asJsonPrimitive.isNumber)) {
                throw ProtocolException("INVALID_REQUEST", "Request id must be a string or number")
            }
            val type = body.string("type") ?: throw ProtocolException("INVALID_REQUEST", "Request is missing type")
            return PlaygroundRequest(id.deepCopy(), type, body)
        }
    }
}

internal class ProtocolException(
    val code: String,
    override val message: String,
    val recoverable: Boolean = true,
    val details: JsonObject? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal fun event(
    type: String,
    requestId: JsonElement? = null,
    block: JsonObject.() -> Unit = {},
): JsonObject =
    JsonObject().also { json ->
        json.addProperty("type", type)
        requestId?.let { json.add("requestId", it.deepCopy()) }
        json.block()
    }

internal fun errorEvent(
    requestId: JsonElement?,
    cellId: String? = null,
    code: String,
    message: String,
    recoverable: Boolean,
    details: JsonObject? = null,
): JsonObject =
    event("cell.error", requestId) {
        cellId?.let { addProperty("cellId", it) }
        add(
            "error",
            JsonObject().also { error ->
                error.addProperty("code", code)
                error.addProperty("message", message)
                error.addProperty("recoverable", recoverable)
                details?.let { error.add("details", it.deepCopy()) }
            },
        )
    }

internal fun JsonObject.string(name: String): String? =
    get(name)
        ?.takeUnless(JsonElement::isJsonNull)
        ?.takeIf(JsonElement::isJsonPrimitive)
        ?.asJsonPrimitive
        ?.takeIf { it.isString }
        ?.asString

internal fun JsonObject.int(name: String): Int? =
    get(name)
        ?.takeUnless(JsonElement::isJsonNull)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
        ?.runCatching { asInt }
        ?.getOrNull()

internal fun JsonObject.boolean(name: String): Boolean? =
    get(name)
        ?.takeUnless(JsonElement::isJsonNull)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
        ?.runCatching { asBoolean }
        ?.getOrNull()

internal fun JsonObject.objectValue(name: String): JsonObject? {
    val value = get(name) ?: return null
    if (value is JsonNull) return null
    if (!value.isJsonObject) throw ProtocolException("INVALID_REQUEST", "$name must be an object")
    return value.asJsonObject
}
