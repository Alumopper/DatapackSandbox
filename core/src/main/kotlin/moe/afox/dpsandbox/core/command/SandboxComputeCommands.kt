package moe.afox.dpsandbox.core.command

import com.google.gson.JsonPrimitive
import moe.afox.dpsandbox.core.CommandToken
import moe.afox.dpsandbox.core.ContextNumberProviders
import moe.afox.dpsandbox.core.DatapackSandbox
import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.EntitySelectors
import moe.afox.dpsandbox.core.ExecutionContext
import moe.afox.dpsandbox.core.JsonValues
import moe.afox.dpsandbox.core.PredicateContext
import moe.afox.dpsandbox.core.SandboxException
import moe.afox.dpsandbox.core.SandboxPlayer
import moe.afox.dpsandbox.core.SourceLocation
import kotlin.math.floor

internal data class ComputedValue(
    val value: JsonPrimitive,
    val nextIndex: Int,
)

internal fun DatapackSandbox.computeValue(tokens: List<CommandToken>, start: Int, context: ExecutionContext, location: SourceLocation?): ComputedValue {
    require(profile.id == "26.3") { "Compute requires Minecraft 26.3" }
    requireSizeFrom(tokens, start, 3, "compute <default|entity target|block pos> <integer|float> <provider>", location)
    var index = start + 1
    var predicate = PredicateContext(world, context.position, context.dimension, context.entity, player = context.entity as? SandboxPlayer)
    when (tokens[start].text) {
        "default" -> Unit
        "entity" -> {
            val entity =
                EntitySelectors.select(world, tokens[index].text, context, location).singleOrNull()
                    ?: throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Compute entity requires exactly one target", location)
            predicate = predicate.copy(targetEntity = entity)
            index++
        }
        "block" -> {
            val pos = parseBlockPos(tokens, index, context, location)
            val block = world.block(pos)
            predicate = predicate.copy(block = block?.id, blockState = block)
            index += 3
        }
        else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Unknown compute context '${tokens[start].text}'", location)
    }
    requireIndex(tokens, index + 1, "compute <context> <integer|float> <provider>", location)
    val engine = ContextNumberProviders(datapack, profile)
    val provider = JsonValues.parse(tokens[index + 1].text, location)
    val value =
        when (tokens[index].text) {
            "integer" -> JsonPrimitive(engine.integer(provider, predicate))
            "float" -> JsonPrimitive(engine.floating(provider, predicate))
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Compute expects integer or float", location)
        }
    return ComputedValue(value, index + 2)
}

internal fun DatapackSandbox.executeCompute(tokens: List<CommandToken>, location: SourceLocation?, context: ExecutionContext) {
    val computed = computeValue(tokens, 1, context, location)
    val floating = computed.value.asNumber is Float
    val scale = tokens.getOrNull(computed.nextIndex)?.text?.let { parseDouble(it, "compute scale", location) } ?: 1.0
    if (tokens.size > computed.nextIndex + if (floating) 1 else 0) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Unexpected compute arguments", location)
    val result = if (floating) floor(computed.value.asDouble * scale) else computed.value.asDouble
    if (!result.isFinite() || result < Int.MIN_VALUE || result > Int.MAX_VALUE) throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Compute result is outside the integer range", location)
    world.recordOutput("compute", "data", text = result.toInt().toString())
}
