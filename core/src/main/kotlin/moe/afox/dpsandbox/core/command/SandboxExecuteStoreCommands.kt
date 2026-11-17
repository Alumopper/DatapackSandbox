package moe.afox.dpsandbox.core.command

import com.google.gson.JsonPrimitive
import moe.afox.dpsandbox.core.CommandToken
import moe.afox.dpsandbox.core.DatapackSandbox
import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.EntitySelectors
import moe.afox.dpsandbox.core.ExecutionContext
import moe.afox.dpsandbox.core.JsonPaths
import moe.afox.dpsandbox.core.ResourceLocation
import moe.afox.dpsandbox.core.SandboxException
import moe.afox.dpsandbox.core.SandboxPlayer
import moe.afox.dpsandbox.core.SourceLocation
import moe.afox.dpsandbox.core.unsupportedFeature

internal fun DatapackSandbox.bindExecuteStore(
    tokens: List<CommandToken>,
    index: Int,
    location: SourceLocation?,
    context: ExecutionContext,
): (Boolean, Int) -> Unit {
    requireSizeFrom(tokens, index, 4, "execute store <result|success> <score|storage|entity|block|bossbar> ...", location)
    val storeSuccess =
        when (tokens[index].text) {
            "success" -> true
            "result" -> false
            else -> unsupportedFeature("Unsupported execute store type '${tokens[index].text}'", profile.id, location)
        }
    val write: (Int) -> Unit =
        when (tokens[index + 1].text) {
            "score" -> {
                requireSizeFrom(tokens, index, 4, "execute store ${tokens[index].text} score <target> <objective>", location)
                val targets = scoreTargets(tokens[index + 2].text, context, location)

                fun(value: Int) {
                    targets.forEach { world.setScore(it, tokens[index + 3].text, value) }
                }
            }
            "storage" -> {
                requireSizeFrom(tokens, index, 6, "execute store ${tokens[index].text} storage <id> <path> <type> <scale>", location)
                val id = ResourceLocation.parse(tokens[index + 2].text)

                fun(value: Int) {
                    val scaled = scaledStoreValue(value, tokens[index + 4].text, tokens[index + 5].text, location)
                    JsonPaths.set(world.storage(id), tokens[index + 3].sourceText, scaled)
                }
            }
            "entity" -> {
                requireSizeFrom(tokens, index, 6, "execute store ${tokens[index].text} entity <target> <path> <type> <scale>", location)
                val target =
                    EntitySelectors.select(world, tokens[index + 2].text, context, location).singleOrNull()
                        ?: throw SandboxException(
                            DiagnosticCode.COMMAND_ERROR,
                            "execute store entity requires exactly one target",
                            location,
                        )
                if (target is SandboxPlayer) {
                    throw SandboxException(
                        DiagnosticCode.COMMAND_ERROR,
                        "Player NBT is read-only in this sandbox; use player events or movement commands",
                        location,
                    )
                }

                fun(value: Int) {
                    val scaled = scaledStoreValue(value, tokens[index + 4].text, tokens[index + 5].text, location)
                    val updated = target.fullNbt(profile, location)
                    JsonPaths.set(updated, tokens[index + 3].sourceText, scaled)
                    target.writeFullNbt(profile, updated, location)
                }
            }
            "block" -> {
                requireSizeFrom(tokens, index, 8, "execute store ${tokens[index].text} block <pos> <path> <type> <scale>", location)
                val pos = parseBlockPos(tokens, index + 2, context, location)
                val block = world.requireBlock(pos)

                fun(value: Int) {
                    val scaled = scaledStoreValue(value, tokens[index + 6].text, tokens[index + 7].text, location)
                    val updated = block.fullNbt(pos, profile, location)
                    JsonPaths.set(updated, tokens[index + 5].sourceText, scaled)
                    block.writeFullNbt(pos, profile, updated, location)
                }
            }
            "bossbar" -> {
                requireSizeFrom(tokens, index, 4, "execute store ${tokens[index].text} bossbar <id> <value|max>", location)
                val bar =
                    world.bossbars[ResourceLocation.parse(tokens[index + 2].text)]
                        ?: throw SandboxException(
                            DiagnosticCode.COMMAND_ERROR,
                            "Bossbar '${tokens[index + 2].text}' does not exist",
                            location,
                        )
                when (tokens[index + 3].text) {
                    "value" -> { value -> bar.value = value }
                    "max" -> { value -> bar.max = value.coerceAtLeast(1) }
                    else -> unsupportedFeature("Unsupported execute store bossbar field '${tokens[index + 3].text}'", profile.id, location)
                }
            }
            else -> unsupportedFeature("Unsupported execute store target '${tokens[index + 1].text}'", profile.id, location)
        }
    return { success, value ->
        write(
            if (!success) {
                0
            } else if (storeSuccess) {
                1
            } else {
                value
            },
        )
    }
}

private fun DatapackSandbox.scaledStoreValue(
    value: Int,
    typeText: String,
    scaleText: String,
    location: SourceLocation?,
): JsonPrimitive {
    val scale =
        scaleText.toDoubleOrNull()
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid execute store scale '$scaleText'", location)
    if (!scale.isFinite()) {
        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid execute store scale '$scaleText'", location)
    }
    val scaled = value * scale
    return when (typeText) {
        "byte" -> JsonPrimitive(scaled.toInt().toByte().toInt())
        "short" -> JsonPrimitive(scaled.toInt().toShort().toInt())
        "int" -> JsonPrimitive(scaled.toInt())
        "long" -> JsonPrimitive(scaled.toLong())
        "float" -> JsonPrimitive(scaled.toFloat())
        "double" -> JsonPrimitive(scaled)
        else -> unsupportedFeature("Unsupported execute store numeric type '$typeText'", profile.id, location)
    }
}
