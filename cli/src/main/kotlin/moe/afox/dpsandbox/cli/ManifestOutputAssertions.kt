package moe.afox.dpsandbox.cli

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import moe.afox.dpsandbox.core.DatapackSandbox
import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.OutputAssertions
import moe.afox.dpsandbox.core.OutputExpectation
import moe.afox.dpsandbox.core.OutputSegmentExpectation
import moe.afox.dpsandbox.core.SandboxException

object ManifestOutputAssertions {
    fun evaluate(output: JsonObject, sandbox: DatapackSandbox): List<String> =
        OutputAssertions.failures(sandbox.world.outputs, parse(output), label = "output")

    fun parse(output: JsonObject): OutputExpectation {
        val payload = output.getAsJsonObject("payload")
        return OutputExpectation(
            command = output.string("command"),
            channel = output.string("channel"),
            target = output.string("target"),
            targets = output.stringArray("targets").toSet(),
            text = output.string("text") ?: output.string("equals"),
            contains = output.string("contains"),
            normalizedText = output.string("normalizedText") ?: output.string("normalizedEquals"),
            normalizedContains = output.string("normalizedContains"),
            payloadPath = output.string("payloadPath") ?: payload?.string("path"),
            payloadEquals = output.get("payloadEquals") ?: payload?.get("equals"),
            segment = output.getAsJsonObject("segment")?.let(::parseSegment),
            count = output.get("count")?.asInt,
            order = output.get("order")?.asInt,
        )
    }

    private fun parseSegment(segment: JsonObject): OutputSegmentExpectation =
        OutputSegmentExpectation(
            text = segment.string("text") ?: segment.string("equals"),
            contains = segment.string("contains"),
            normalizedText = segment.string("normalizedText") ?: segment.string("normalizedEquals"),
            normalizedContains = segment.string("normalizedContains"),
            color = segment.string("color"),
            bold = segment.boolean("bold"),
            italic = segment.boolean("italic"),
            underlined = segment.boolean("underlined"),
            strikethrough = segment.boolean("strikethrough"),
            obfuscated = segment.boolean("obfuscated"),
        )

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.boolean(name: String): Boolean? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asBoolean

    private fun JsonObject.stringArray(name: String): List<String> {
        val value: JsonElement = get(name) ?: return emptyList()
        if (!value.isJsonArray) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Output assertion '$name' must be an array")
        }
        return value.asJsonArray.map { element ->
            if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Output assertion '$name' entries must be strings")
            }
            element.asString
        }
    }
}
