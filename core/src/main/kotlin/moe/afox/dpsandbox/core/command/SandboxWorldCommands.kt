package moe.afox.dpsandbox.core.command
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import moe.afox.dpsandbox.core.AttributeModifier
import moe.afox.dpsandbox.core.CommandToken
import moe.afox.dpsandbox.core.CommandTokenizer
import moe.afox.dpsandbox.core.DatapackSandbox
import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.EntitySelectors
import moe.afox.dpsandbox.core.ExecutionContext
import moe.afox.dpsandbox.core.JsonValues
import moe.afox.dpsandbox.core.ResourceLocation
import moe.afox.dpsandbox.core.SandboxBossbar
import moe.afox.dpsandbox.core.SandboxEntity
import moe.afox.dpsandbox.core.SandboxException
import moe.afox.dpsandbox.core.SandboxPlayer
import moe.afox.dpsandbox.core.ScoreboardObjectiveMetadata
import moe.afox.dpsandbox.core.SourceLocation
import moe.afox.dpsandbox.core.toJson
import moe.afox.dpsandbox.core.unsupportedFeature
import kotlin.math.max
import kotlin.random.Random

internal fun DatapackSandbox.executeSeed(
    tokens: List<CommandToken>,
    location: SourceLocation?,
) {
    requireSize(tokens, 1, "seed", location)
    world.recordOutput("seed", "data", text = world.seed.toString())
}

internal fun DatapackSandbox.executeDifficulty(
    tokens: List<CommandToken>,
    location: SourceLocation?,
) {
    requireSize(tokens, 1, "difficulty [peaceful|easy|normal|hard]", location)
    val before = world.difficulty
    tokens.getOrNull(1)?.let {
        val difficulty = normalizeDifficulty(it.text, location)
        world.difficulty = difficulty
    }
    world.recordOutput(
        "difficulty",
        "data",
        text = world.difficulty,
        payload =
            JsonObject().also { payload ->
                payload.addProperty("before", before)
                payload.addProperty("after", world.difficulty)
                payload.addProperty("changed", before != world.difficulty)
            },
    )
}

internal fun DatapackSandbox.executeDefaultGameMode(
    tokens: List<CommandToken>,
    location: SourceLocation?,
) {
    requireSize(tokens, 2, "defaultgamemode <mode>", location)
    val before = world.defaultGameMode
    world.defaultGameMode = normalizeGameMode(tokens[1].text, location)
    world.recordOutput(
        "defaultgamemode",
        "data",
        text = world.defaultGameMode,
        payload =
            JsonObject().also { payload ->
                payload.addProperty("before", before)
                payload.addProperty("after", world.defaultGameMode)
                payload.addProperty("changed", before != world.defaultGameMode)
            },
    )
}

internal fun DatapackSandbox.executeGameMode(
    tokens: List<CommandToken>,
    location: SourceLocation?,
    context: ExecutionContext,
) {
    requireSize(tokens, 2, "gamemode <mode> [targets]", location)
    val mode = normalizeGameMode(tokens[1].text, location)
    val targets =
        tokens.getOrNull(2)?.text?.let { resolvePlayers(it, location, context) }
            ?: listOf(
                context.entity as? SandboxPlayer
                    ?: throw SandboxException(
                        DiagnosticCode.COMMAND_ERROR,
                        "gamemode without targets requires a player execution context",
                        location,
                    ),
            )
    val changes = targets.map { player -> player to player.gameMode }
    targets.forEach { it.gameMode = mode }
    world.recordOutput(
        "gamemode",
        "data",
        targets = targets.map { it.name },
        text = targets.size.toString(),
        payload =
            JsonObject().also { payload ->
                payload.addProperty("mode", mode)
                payload.addProperty("count", targets.size)
                payload.add(
                    "players",
                    JsonArray().also { players ->
                        changes.forEach { (player, before) ->
                            players.add(
                                JsonObject().also { entry ->
                                    entry.addProperty("player", player.name)
                                    entry.addProperty("uuid", player.uuid)
                                    entry.addProperty("before", before)
                                    entry.addProperty("after", player.gameMode)
                                    entry.addProperty("changed", before != player.gameMode)
                                },
                            )
                        }
                    },
                )
            },
    )
}

internal fun DatapackSandbox.executeAttribute(
    tokens: List<CommandToken>,
    location: SourceLocation?,
    context: ExecutionContext,
) {
    requireSize(tokens, 4, "attribute <target> <attribute> <get|base|modifier> ...", location)
    val entity =
        EntitySelectors.select(world, tokens[1].text, context, location).singleOrNull()
            ?: throw SandboxException(DiagnosticCode.COMMAND_ERROR, "attribute requires exactly one target", location)
    val attribute = ResourceLocation.parse(tokens[2].text)
    when (tokens[3].text) {
        "get" -> {
            val scale = tokens.getOrNull(4)?.text?.let { parseDouble(it, "attribute scale", location) } ?: 1.0
            recordAttributeOutput("attribute get", entity, attribute, "total", scale)
        }
        "base" -> {
            requireSize(tokens, 5, "attribute <target> <attribute> base <get|set|reset>", location)
            when (tokens[4].text) {
                "get" -> {
                    val scale = tokens.getOrNull(5)?.text?.let { parseDouble(it, "attribute scale", location) } ?: 1.0
                    recordAttributeOutput("attribute base get", entity, attribute, "base", scale)
                }
                "set" -> {
                    requireSize(tokens, 6, "attribute <target> <attribute> base set <value>", location)
                    entity.attributes[attribute] = parseDouble(tokens[5].text, "attribute base", location)
                }
                "reset" -> entity.attributes.remove(attribute)
                else -> unsupportedFeature("Unsupported attribute base action '${tokens[4].text}'", profile.id, location)
            }
        }
        "modifier" -> {
            requireSize(tokens, 5, "attribute <target> <attribute> modifier <add|remove|value>", location)
            when (tokens[4].text) {
                "add" -> {
                    requireSize(tokens, 8, "attribute <target> <attribute> modifier add <id> <value> <operation>", location)
                    val modifier =
                        AttributeModifier(
                            id = ResourceLocation.parse(tokens[5].text),
                            amount = parseDouble(tokens[6].text, "attribute modifier value", location),
                            operation = normalizeAttributeModifierOperation(tokens[7].text, location),
                        )
                    entity.attributeModifiers.getOrPut(attribute) { linkedMapOf() }[modifier.id] = modifier
                    recordAttributeModifierOutput(
                        "attribute modifier add",
                        entity,
                        attribute,
                        modifier,
                        attributeTotal(entity, attribute),
                    )
                }
                "remove" -> {
                    requireSize(tokens, 6, "attribute <target> <attribute> modifier remove <id>", location)
                    val id = ResourceLocation.parse(tokens[5].text)
                    val removed = entity.attributeModifiers[attribute]?.remove(id)
                    if (entity.attributeModifiers[attribute]?.isEmpty() == true) {
                        entity.attributeModifiers.remove(attribute)
                    }
                    recordAttributeModifierOutput(
                        "attribute modifier remove",
                        entity,
                        attribute,
                        removed,
                        attributeTotal(entity, attribute),
                        id,
                    )
                }
                "value" -> {
                    requireSize(tokens, 7, "attribute <target> <attribute> modifier value get <id> [scale]", location)
                    if (tokens[5].text != "get") {
                        unsupportedFeature("Unsupported attribute modifier value action '${tokens[5].text}'", profile.id, location)
                    }
                    val id = ResourceLocation.parse(tokens[6].text)
                    val modifier =
                        entity.attributeModifiers[attribute]?.get(id)
                            ?: throw SandboxException(
                                DiagnosticCode.COMMAND_ERROR,
                                "Attribute modifier '$id' was not found for '$attribute'",
                                location,
                            )
                    val scale = tokens.getOrNull(7)?.text?.let { parseDouble(it, "attribute modifier scale", location) } ?: 1.0
                    recordAttributeModifierValueOutput(entity, attribute, modifier, scale)
                }
                else -> unsupportedFeature("Unsupported attribute modifier action '${tokens[4].text}'", profile.id, location)
            }
        }
        else -> unsupportedFeature("Unsupported attribute action '${tokens[3].text}'", profile.id, location)
    }
}

internal fun DatapackSandbox.recordAttributeOutput(
    command: String,
    entity: SandboxEntity,
    attribute: ResourceLocation,
    field: String,
    scale: Double,
) {
    val rawValue =
        if (field ==
            "total"
        ) {
            attributeTotal(entity, attribute)
        } else {
            entity.attributes[attribute] ?: defaultAttribute(attribute)
        }
    val value = rawValue * scale
    world.recordOutput(
        command,
        "data",
        text = value.toString(),
        payload =
            JsonObject().also { payload ->
                payload.addProperty("target", entity.scoreHolder)
                payload.addProperty("attribute", attribute.toString())
                payload.addProperty("field", field)
                payload.addProperty("scale", scale)
                payload.addProperty("rawValue", rawValue)
                payload.addProperty("value", value)
            },
    )
}

internal fun DatapackSandbox.recordAttributeModifierOutput(
    command: String,
    entity: SandboxEntity,
    attribute: ResourceLocation,
    modifier: AttributeModifier?,
    total: Double,
    id: ResourceLocation? = modifier?.id,
) {
    world.recordOutput(
        command,
        "data",
        targets = listOf(entity.scoreHolder),
        text = if (modifier == null) "0" else "1",
        payload =
            JsonObject().also { payload ->
                payload.addProperty("target", entity.scoreHolder)
                payload.addProperty("attribute", attribute.toString())
                id?.let { payload.addProperty("modifier", it.toString()) }
                payload.addProperty("changed", modifier != null)
                payload.addProperty("total", total)
                modifier?.let {
                    payload.addProperty("amount", it.amount)
                    payload.addProperty("operation", it.operation)
                }
            },
    )
}

internal fun DatapackSandbox.recordAttributeModifierValueOutput(
    entity: SandboxEntity,
    attribute: ResourceLocation,
    modifier: AttributeModifier,
    scale: Double,
) {
    val value = modifier.amount * scale
    world.recordOutput(
        "attribute modifier value get",
        "data",
        targets = listOf(entity.scoreHolder),
        text = value.toString(),
        payload =
            JsonObject().also { payload ->
                payload.addProperty("target", entity.scoreHolder)
                payload.addProperty("attribute", attribute.toString())
                payload.addProperty("modifier", modifier.id.toString())
                payload.addProperty("operation", modifier.operation)
                payload.addProperty("scale", scale)
                payload.addProperty("rawValue", modifier.amount)
                payload.addProperty("value", value)
            },
    )
}

internal fun DatapackSandbox.attributeTotal(
    entity: SandboxEntity,
    attribute: ResourceLocation,
): Double {
    val base = entity.attributes[attribute] ?: defaultAttribute(attribute)
    val modifiers = entity.attributeModifiers[attribute]?.values.orEmpty()
    val withBaseModifiers =
        base +
            modifiers.filter { it.operation == "add_value" }.sumOf { it.amount } +
            modifiers.filter { it.operation == "add_multiplied_base" }.sumOf { base * it.amount }
    return modifiers
        .filter { it.operation == "add_multiplied_total" }
        .fold(withBaseModifiers) { total, modifier -> total * (1.0 + modifier.amount) }
}

internal fun DatapackSandbox.normalizeAttributeModifierOperation(
    raw: String,
    location: SourceLocation?,
): String =
    when (raw) {
        "add_value", "addition" -> "add_value"
        "add_multiplied_base", "multiply_base" -> "add_multiplied_base"
        "add_multiplied_total", "multiply_total" -> "add_multiplied_total"
        else -> throw SandboxException(
            DiagnosticCode.INPUT_FORMAT,
            "Unsupported attribute modifier operation '$raw'; expected add_value, add_multiplied_base, or add_multiplied_total",
            location,
        )
    }

internal fun DatapackSandbox.executeScoreboard(
    command: String,
    tokens: List<CommandToken>,
    location: SourceLocation?,
) {
    requireSize(tokens, 3, "scoreboard <objectives|players> ...", location)
    when (tokens[1].text) {
        "objectives" -> executeObjectives(command, tokens, location)
        "players" -> executePlayers(tokens, location)
        else -> unsupportedFeature("Unsupported scoreboard subcommand '${tokens[1].text}'", profile.id, location)
    }
}

internal fun DatapackSandbox.executeObjectives(
    command: String,
    tokens: List<CommandToken>,
    location: SourceLocation?,
) {
    when (tokens[2].text) {
        "add" -> {
            requireSize(tokens, 5, "scoreboard objectives add <objective> <criteria>", location)
            world.addObjective(tokens[3].text, tokens[4].text)
        }
        "remove" -> {
            requireSize(tokens, 4, "scoreboard objectives remove <objective>", location)
            world.removeObjective(tokens[3].text)
        }
        "setdisplay" -> {
            requireSize(tokens, 4, "scoreboard objectives setdisplay <slot> [objective]", location)
            val slot = tokens[3].text
            val before = world.scoreboardDisplays[slot]
            val objective = tokens.getOrNull(4)?.text
            if (objective == null) {
                world.scoreboardDisplays.remove(slot)
            } else {
                world.ensureObjective(objective)
                world.scoreboardDisplays[slot] = objective
            }
            recordScoreboardDisplay(slot, objective, before)
        }
        "modify" -> executeObjectiveModify(command, tokens, location)
        "list" -> recordObjectiveList()
        else -> unsupportedFeature("Unsupported scoreboard objectives action '${tokens[2].text}'", profile.id, location)
    }
}

internal fun DatapackSandbox.executeObjectiveModify(
    command: String,
    tokens: List<CommandToken>,
    location: SourceLocation?,
) {
    requireSize(tokens, 6, "scoreboard objectives modify <objective> <displayname|rendertype|displayautoupdate> <value>", location)
    val objective = tokens[3].text
    world.ensureObjective(objective)
    val metadata = world.scoreboardObjectiveMetadata.getOrPut(objective) { ScoreboardObjectiveMetadata() }
    val field =
        when (tokens[4].text.lowercase()) {
            "displayname" -> "displayName"
            "rendertype" -> "renderType"
            "displayautoupdate" -> "displayAutoUpdate"
            else -> unsupportedFeature("Unsupported scoreboard objective field '${tokens[4].text}'", profile.id, location)
        }
    val before =
        when (field) {
            "displayName" -> JsonPrimitive(metadata.displayName ?: objective)
            "renderType" -> JsonPrimitive(metadata.renderType)
            "displayAutoUpdate" -> JsonPrimitive(metadata.displayAutoUpdate)
            else -> JsonPrimitive("")
        }
    val value =
        when (field) {
            "displayName" -> JsonPrimitive(CommandTokenizer.tailFrom(command, tokens[5]))
            "renderType" -> {
                val renderType = tokens[5].text
                if (renderType !in setOf("integer", "hearts")) {
                    throw SandboxException(
                        DiagnosticCode.INPUT_FORMAT,
                        "scoreboard objective render type must be integer or hearts",
                        location,
                    )
                }
                JsonPrimitive(renderType)
            }
            "displayAutoUpdate" -> JsonPrimitive(parseBoolean(tokens[5].text, "scoreboard objective displayAutoUpdate", location))
            else -> JsonPrimitive("")
        }
    when (field) {
        "displayName" -> metadata.displayName = value.asString
        "renderType" -> metadata.renderType = value.asString
        "displayAutoUpdate" -> metadata.displayAutoUpdate = value.asBoolean
    }
    recordScoreboardObjectiveModify(objective, field, value, before)
}

internal fun DatapackSandbox.recordScoreboardObjectiveModify(
    objective: String,
    field: String,
    value: JsonElement,
    before: JsonElement,
) {
    world.recordOutput(
        "scoreboard objectives modify",
        "data",
        text = "$objective $field=${JsonValues.render(value).trim()}",
        payload =
            JsonObject().also { payload ->
                payload.addProperty("objective", objective)
                payload.addProperty("field", field)
                payload.add("value", value)
                payload.add("previous", before)
            },
    )
}

internal fun DatapackSandbox.recordScoreboardDisplay(
    slot: String,
    objective: String?,
    before: String?,
) {
    world.recordOutput(
        "scoreboard objectives setdisplay",
        "data",
        text = objective?.let { "$slot=$it" } ?: "cleared $slot",
        payload =
            JsonObject().also { payload ->
                payload.addProperty("slot", slot)
                objective?.let { payload.addProperty("objective", it) }
                before?.let { payload.addProperty("previous", it) }
                payload.addProperty("cleared", objective == null)
            },
    )
}

internal fun DatapackSandbox.recordObjectiveList() {
    val payload = JsonObject()
    val entries = mutableListOf<String>()
    world.objectives.toSortedMap().forEach { (name, criteria) ->
        payload.addProperty(name, criteria)
        entries += "$name ($criteria)"
    }
    val text =
        if (entries.isEmpty()) {
            "No scoreboard objectives"
        } else {
            entries.joinToString()
        }
    world.recordOutput("scoreboard objectives list", "data", text = text, payload = payload)
}

internal fun DatapackSandbox.executePlayers(
    tokens: List<CommandToken>,
    location: SourceLocation?,
) {
    requireSize(tokens, 3, "scoreboard players <set|add|remove|get|operation> ...", location)
    when (tokens[2].text) {
        "set", "add", "remove" -> {
            requireSize(tokens, 6, "scoreboard players ${tokens[2].text} <target> <objective> <value>", location)
            val value = parseInt(tokens[5].text, "score value", location)
            scoreTargets(tokens[3].text, ExecutionContext(), location).forEach { target ->
                when (tokens[2].text) {
                    "set" -> world.setScore(target, tokens[4].text, value)
                    "add" -> world.addScore(target, tokens[4].text, value)
                    "remove" -> world.addScore(target, tokens[4].text, -value)
                }
            }
        }
        "get" -> {
            requireSize(tokens, 5, "scoreboard players get <target> <objective>", location)
            val objective = tokens[4].text
            scoreTargets(tokens[3].text, ExecutionContext(), location).forEach { target ->
                val value = world.getScore(target, objective)
                world.recordOutput(
                    "scoreboard players get",
                    "data",
                    text = value.toString(),
                    payload =
                        JsonObject().also { payload ->
                            payload.addProperty("target", target)
                            payload.addProperty("objective", objective)
                            payload.addProperty("value", value)
                        },
                )
            }
        }
        "reset" -> {
            requireSize(tokens, 4, "scoreboard players reset <target> [objective]", location)
            val targets = scoreTargets(tokens[3].text, ExecutionContext(), location).toSet()
            val objective = tokens.getOrNull(4)?.text
            world.scores.keys
                .filter { it.target in targets && (objective == null || it.objective == objective) }
                .forEach { world.scores.remove(it) }
        }
        "list" -> {
            val target = tokens.getOrNull(3)?.text
            val entries = world.scores.toSortedMap().filter { (key, _) -> target == null || key.target == target }
            val payload = JsonObject()
            entries.forEach { (key, value) -> payload.addProperty("${key.target} ${key.objective}", value) }
            world.recordOutput(
                "scoreboard players list",
                "data",
                text =
                    entries.entries.joinToString {
                        "${it.key.target} ${it.key.objective}=${it.value}"
                    },
                payload = payload,
            )
        }
        "enable" -> Unit
        "operation" -> executeScoreOperation(tokens, location)
        else -> unsupportedFeature("Unsupported scoreboard players action '${tokens[2].text}'", profile.id, location)
    }
}

internal fun DatapackSandbox.executeBossbar(
    command: String,
    tokens: List<CommandToken>,
    location: SourceLocation?,
    context: ExecutionContext,
) {
    requireSize(tokens, 2, "bossbar <add|remove|list|get|set> ...", location)

    fun recordBossbarMutation(
        command: String,
        action: String,
        id: ResourceLocation,
        text: String,
        bar: SandboxBossbar? = null,
        before: JsonObject? = null,
        extra: JsonObject.() -> Unit = {},
    ) {
        world.recordOutput(
            command,
            "data",
            text = text,
            payload =
                (bar?.toJson() ?: JsonObject().also { it.addProperty("id", id.toString()) }).also { payload ->
                    payload.addProperty("action", action)
                    before?.let { payload.add("before", it) }
                    payload.extra()
                },
        )
    }
    when (tokens[1].text) {
        "add" -> {
            requireSize(tokens, 4, "bossbar add <id> <name>", location)
            val id = ResourceLocation.parse(tokens[2].text)
            val before = world.bossbars[id]?.toJson()
            val bar = SandboxBossbar(id, CommandTokenizer.tailFrom(command, tokens[3]))
            world.bossbars[id] = bar
            recordBossbarMutation("bossbar add", "add", id, id.toString(), bar, before) {
                addProperty("replaced", before != null)
            }
        }
        "remove" -> {
            requireSize(tokens, 3, "bossbar remove <id>", location)
            val id = ResourceLocation.parse(tokens[2].text)
            val removed = world.bossbars.remove(id)
            recordBossbarMutation("bossbar remove", "remove", id, (removed != null).toString(), before = removed?.toJson()) {
                addProperty("removed", removed != null)
            }
        }
        "list" ->
            world.recordOutput(
                "bossbar list",
                "data",
                text =
                    world.bossbars.keys
                        .sorted()
                        .joinToString(),
            )
        "get" -> {
            requireSize(tokens, 4, "bossbar get <id> <field>", location)
            val id = ResourceLocation.parse(tokens[2].text)
            val bar =
                world.bossbars[id]
                    ?: throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Bossbar '${tokens[2].text}' does not exist", location)
            val field = tokens[3].text
            val value =
                when (field) {
                    "value" -> JsonPrimitive(bar.value)
                    "max" -> JsonPrimitive(bar.max)
                    "visible" -> JsonPrimitive(bar.visible)
                    "players" -> JsonArray().also { array -> bar.players.forEach { array.add(it) } }
                    else -> bar.toJson().get(field)?.deepCopy() ?: JsonPrimitive("")
                }
            world.recordOutput(
                "bossbar get",
                "data",
                text = if (value.isJsonPrimitive) value.asJsonPrimitive.asString else JsonValues.render(value),
                payload =
                    JsonObject().also { payload ->
                        payload.addProperty("id", id.toString())
                        payload.addProperty("field", field)
                        payload.add("value", value)
                    },
            )
        }
        "set" -> {
            requireSize(tokens, 5, "bossbar set <id> <field> <value>", location)
            val id = ResourceLocation.parse(tokens[2].text)
            val bar =
                world.bossbars[id]
                    ?: throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Bossbar '${tokens[2].text}' does not exist", location)
            val before = bar.toJson()
            val field = tokens[3].text
            val value =
                when (field) {
                    "name" -> JsonPrimitive(CommandTokenizer.tailFrom(command, tokens[4]))
                    "value" -> JsonPrimitive(parseInt(tokens[4].text, "bossbar value", location))
                    "max" -> JsonPrimitive(parseInt(tokens[4].text, "bossbar max", location).coerceAtLeast(1))
                    "color" -> JsonPrimitive(tokens[4].text)
                    "style" -> JsonPrimitive(tokens[4].text)
                    "visible" -> JsonPrimitive(parseBoolean(tokens[4].text, "bossbar visible", location))
                    "players" -> {
                        JsonArray().also { array ->
                            if (tokens.size >
                                4
                            ) {
                                resolvePlayers(tokens[4].text, location, context).map { it.name }.forEach { array.add(it) }
                            }
                        }
                    }
                    else -> unsupportedFeature("Unsupported bossbar field '${tokens[3].text}'", profile.id, location)
                }
            when (field) {
                "name" -> bar.name = value.asString
                "value" -> bar.value = value.asInt
                "max" -> bar.max = value.asInt
                "color" -> bar.color = value.asString
                "style" -> bar.style = value.asString
                "visible" -> bar.visible = value.asBoolean
                "players" -> {
                    bar.players.clear()
                    value.asJsonArray.forEach { bar.players.add(it.asString) }
                }
            }
            recordBossbarMutation(
                "bossbar set",
                "set",
                id,
                if (value.isJsonPrimitive) value.asJsonPrimitive.asString else JsonValues.render(value),
                bar,
                before,
            ) {
                addProperty("field", field)
                add("fieldValue", value)
            }
        }
        else -> unsupportedFeature("Unsupported bossbar action '${tokens[1].text}'", profile.id, location)
    }
}

internal fun DatapackSandbox.executeGamerule(
    tokens: List<CommandToken>,
    location: SourceLocation?,
) {
    requireSize(tokens, 2, "gamerule <rule> [value]", location)
    val rule = tokens[1].text
    if (tokens.size >= 3) {
        val before = world.gamerules[rule]
        val value = tokens[2].text
        world.gamerules[rule] = value
        world.recordOutput(
            "gamerule set",
            "data",
            text = value,
            payload =
                JsonObject().also { payload ->
                    payload.addProperty("action", "set")
                    payload.addProperty("rule", rule)
                    payload.addProperty("value", value)
                    payload.addProperty("beforeExists", before != null)
                    before?.let { payload.addProperty("beforeValue", it) }
                },
        )
    } else {
        val value = world.gamerules[rule]
        world.recordOutput(
            "gamerule",
            "data",
            text = value ?: "<unset>",
            payload =
                JsonObject().also { payload ->
                    payload.addProperty("rule", rule)
                    payload.addProperty("exists", value != null)
                    value?.let { payload.addProperty("value", it) }
                },
        )
    }
}

internal fun DatapackSandbox.executeTime(
    tokens: List<CommandToken>,
    location: SourceLocation?,
) {
    requireSize(tokens, 2, "time <set|add|query> ...", location)
    when (tokens[1].text) {
        "set" -> {
            requireSize(tokens, 3, "time set <value|day|noon|night|midnight>", location)
            val before = world.dayTime
            val value = parseTimeOfDay(tokens[2].text, location)
            world.setDayTime(value)
            recordTimeMutationOutput("time set", tokens[2].text, before)
        }
        "add" -> {
            requireSize(tokens, 3, "time add <time>", location)
            val before = world.dayTime
            world.addDayTime(parseLong(tokens[2].text, "time delta", location))
            recordTimeMutationOutput("time add", tokens[2].text, before)
        }
        "query" -> {
            requireSize(tokens, 3, "time query <daytime|gametime|day>", location)
            val query = tokens[2].text
            val value =
                when (query) {
                    "daytime" -> world.dayTime
                    "gametime" -> world.gameTime
                    "day" -> world.gameTime / 24000
                    else -> unsupportedFeature("Unsupported time query '$query'", profile.id, location)
                }
            world.recordOutput(
                "time query",
                "data",
                text = value.toString(),
                payload =
                    JsonObject().also { payload ->
                        payload.addProperty("query", query)
                        payload.addProperty("value", value)
                    },
            )
        }
        else -> unsupportedFeature("Unsupported time action '${tokens[1].text}'", profile.id, location)
    }
}

internal fun DatapackSandbox.recordTimeMutationOutput(
    command: String,
    argument: String,
    beforeDayTime: Long,
) {
    world.recordOutput(
        command,
        "data",
        text = world.dayTime.toString(),
        payload =
            JsonObject().also { payload ->
                payload.addProperty("argument", argument)
                payload.addProperty("beforeDayTime", beforeDayTime)
                payload.addProperty("afterDayTime", world.dayTime)
                payload.addProperty("gameTime", world.gameTime)
            },
    )
}

internal fun DatapackSandbox.executeWeather(
    tokens: List<CommandToken>,
    location: SourceLocation?,
) {
    requireSize(tokens, 2, "weather <clear|rain|thunder> [duration]", location)
    val weather = tokens[1].text
    if (weather !in setOf("clear", "rain", "thunder")) {
        unsupportedFeature("Unsupported weather '${tokens[1].text}'", profile.id, location)
    }
    world.weather = weather
    world.weatherDuration = tokens.getOrNull(2)?.text?.let { parseInt(it, "weather duration", location) } ?: 0
    world.recordOutput(
        "weather",
        "data",
        text = weather,
        payload =
            JsonObject().also { payload ->
                payload.addProperty("weather", world.weather)
                payload.addProperty("duration", world.weatherDuration)
                payload.addProperty("raining", world.weather == "rain" || world.weather == "thunder")
                payload.addProperty("thundering", world.weather == "thunder")
            },
    )
}

internal fun DatapackSandbox.executeRandom(
    tokens: List<CommandToken>,
    location: SourceLocation?,
) {
    requireSize(tokens, 2, "random <value|roll|reset> ...", location)
    when (tokens[1].text) {
        "value", "roll" -> {
            requireSize(tokens, 3, "random ${tokens[1].text} <range> [sequence]", location)
            val (min, max) = parseIntRange(tokens[2].text, location)
            val sequence = tokens.getOrNull(3)?.text ?: "default"
            val stateBefore = world.randomSequences.getOrPut(sequence) { randomSequenceSeed(sequence) }
            val random = Random(stateBefore)
            val value = if (max <= min) min else random.nextInt(min, max + 1)
            val stateAfter = random.nextLong()
            world.randomSequences[sequence] = stateAfter
            world.recordOutput(
                "random ${tokens[1].text}",
                "data",
                targets = listOf(sequence),
                text = value.toString(),
                payload =
                    JsonObject().also { payload ->
                        payload.addProperty("action", tokens[1].text)
                        payload.addProperty("range", tokens[2].text)
                        payload.addProperty("min", min)
                        payload.addProperty("max", max)
                        payload.addProperty("sequence", sequence)
                        payload.addProperty("worldSeed", world.seed)
                        payload.addProperty("gameTime", world.gameTime)
                        payload.addProperty("sequenceStateBefore", stateBefore)
                        payload.addProperty("sequenceStateAfter", stateAfter)
                        payload.addProperty("value", value)
                    },
            )
        }
        "reset" -> {
            tokens.getOrNull(2)?.text?.let { sequence ->
                val before = world.randomSequences[sequence]
                val explicitSeed = tokens.getOrNull(3)?.let { parseLong(it.text, "random seed", location) }
                val seed = explicitSeed ?: randomSequenceSeed(sequence)
                world.randomSequences[sequence] = seed
                world.recordOutput(
                    "random reset",
                    "data",
                    targets = listOf(sequence),
                    text = seed.toString(),
                    payload =
                        JsonObject().also { payload ->
                            payload.addProperty("action", "reset")
                            payload.addProperty("sequence", sequence)
                            payload.addProperty("seed", seed)
                            payload.addProperty("explicitSeed", explicitSeed != null)
                            payload.addProperty("hadPrevious", before != null)
                            before?.let { payload.addProperty("previousState", it) }
                            payload.addProperty("worldSeed", world.seed)
                            payload.addProperty("gameTime", world.gameTime)
                        },
                )
            } ?: run {
                val sequences = world.randomSequences.keys.sorted()
                world.randomSequences.clear()
                world.recordOutput(
                    "random reset",
                    "data",
                    text = sequences.size.toString(),
                    payload =
                        JsonObject().also { payload ->
                            payload.addProperty("action", "reset")
                            payload.addProperty("cleared", sequences.size)
                            payload.add("sequences", JsonArray().also { array -> sequences.forEach { array.add(it) } })
                        },
                )
            }
        }
        else -> unsupportedFeature("Unsupported random action '${tokens[1].text}'", profile.id, location)
    }
}

internal fun DatapackSandbox.randomSequenceSeed(sequence: String): Long = world.seed xor world.gameTime xor sequence.hashCode().toLong()

internal fun DatapackSandbox.executeScoreOperation(
    tokens: List<CommandToken>,
    location: SourceLocation?,
) {
    requireSize(tokens, 8, "scoreboard players operation <target> <objective> <op> <source> <sourceObjective>", location)
    val operation = tokens[5].text
    val sourceValue = world.getScore(tokens[6].text, tokens[7].text)
    scoreTargets(tokens[3].text, ExecutionContext(), location).forEach { target ->
        val current = world.getScore(target, tokens[4].text)
        val result =
            when (operation) {
                "=" -> sourceValue
                "+=" -> current + sourceValue
                "-=" -> current - sourceValue
                "*=" -> current * sourceValue
                "/=" -> if (sourceValue == 0) current else current / sourceValue
                "%=" -> if (sourceValue == 0) current else current % sourceValue
                "<" -> minOf(current, sourceValue)
                ">" -> maxOf(current, sourceValue)
                else -> unsupportedFeature("Unsupported scoreboard operation '$operation'", profile.id, location)
            }
        world.setScore(target, tokens[4].text, result)
    }
}

internal fun DatapackSandbox.scoreTargets(
    token: String,
    context: ExecutionContext,
    location: SourceLocation?,
): List<String> =
    if (EntitySelectors.isSelector(token)) {
        EntitySelectors.select(world, token, context, location).map { it.scoreHolder }
    } else {
        listOf(token)
    }
