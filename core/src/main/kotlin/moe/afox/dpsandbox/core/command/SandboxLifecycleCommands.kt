package moe.afox.dpsandbox.core.command
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import moe.afox.dpsandbox.core.AdvancementUpdate
import moe.afox.dpsandbox.core.CommandToken
import moe.afox.dpsandbox.core.CommandTokenizer
import moe.afox.dpsandbox.core.DatapackSandbox
import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.ExecutionContext
import moe.afox.dpsandbox.core.ResourceLocation
import moe.afox.dpsandbox.core.SandboxException
import moe.afox.dpsandbox.core.SandboxPlayer
import moe.afox.dpsandbox.core.SandboxTeam
import moe.afox.dpsandbox.core.SandboxWorldBorder
import moe.afox.dpsandbox.core.ScheduledFunction
import moe.afox.dpsandbox.core.SourceLocation
import moe.afox.dpsandbox.core.SpawnPoint
import moe.afox.dpsandbox.core.toJson
import moe.afox.dpsandbox.core.unsupportedFeature

internal fun DatapackSandbox.executeSchedule(
    tokens: List<CommandToken>,
    location: SourceLocation?,
) {
    requireSize(tokens, 2, "schedule <function|clear> ...", location)
    if (tokens[1].text == "clear") {
        requireSize(tokens, 3, "schedule clear <id>", location)
        val id = ResourceLocation.parse(tokens[2].text)
        val removed = world.scheduledFunctions.count { it.id == id }
        world.scheduledFunctions.removeIf { it.id == id }
        world.recordOutput(
            "schedule clear",
            "data",
            text = removed.toString(),
            payload =
                JsonObject().also { payload ->
                    payload.addProperty("id", id.toString())
                    payload.addProperty("removed", removed)
                    payload.addProperty("remaining", world.scheduledFunctions.size)
                },
        )
        return
    }
    requireSize(tokens, 4, "schedule function <id> <time> [append|replace]", location)
    if (tokens[1].text != "function") {
        unsupportedFeature("Only 'schedule function' and 'schedule clear' are implemented", profile.id, location)
    }
    val id = ResourceLocation.parse(tokens[2].text)
    val delay = parseTime(tokens[3].text, location)
    val mode = tokens.getOrNull(4)?.text ?: "replace"
    var replaced = 0
    if (mode == "replace") {
        replaced = world.scheduledFunctions.count { it.id == id }
        world.scheduledFunctions.removeIf { it.id == id }
    } else if (mode != "append") {
        unsupportedFeature("Unsupported schedule mode '$mode'", profile.id, location)
    }
    val dueTick = world.gameTime + delay
    world.scheduledFunctions += ScheduledFunction(id, dueTick)
    world.recordOutput(
        "schedule function",
        "data",
        text = dueTick.toString(),
        payload =
            JsonObject().also { payload ->
                payload.addProperty("id", id.toString())
                payload.addProperty("delay", delay)
                payload.addProperty("dueTick", dueTick)
                payload.addProperty("mode", mode)
                payload.addProperty("replaced", replaced)
                payload.addProperty("scheduledCount", world.scheduledFunctions.size)
            },
    )
}

internal fun DatapackSandbox.executeSetWorldSpawn(
    tokens: List<CommandToken>,
    location: SourceLocation?,
    context: ExecutionContext,
) {
    val position = if (tokens.size >= 4) parsePosition(tokens, 1, context, location) else context.position
    val angle = tokens.getOrNull(if (tokens.size >= 4) 4 else 1)?.text?.let { parseDouble(it, "spawn angle", location) }
    world.worldSpawn = SpawnPoint(position = position, dimension = ResourceLocation("minecraft", "overworld"), angle = angle)
    world.recordOutput(
        "setworldspawn",
        "data",
        text = "${position.x} ${position.y} ${position.z}",
        payload =
            JsonObject().also { payload ->
                payload.add("spawn", spawnPointOutput(world.worldSpawn, location))
            },
    )
}

internal fun DatapackSandbox.executeSpawnPoint(
    tokens: List<CommandToken>,
    location: SourceLocation?,
    context: ExecutionContext,
) {
    val targetToken = tokens.getOrNull(1)?.text
    val targets =
        if (targetToken != null && !isCoordinateToken(targetToken)) {
            resolvePlayers(targetToken, location, context)
        } else {
            listOf(
                context.entity as? SandboxPlayer ?: world.players.values.firstOrNull()
                    ?: throw SandboxException(DiagnosticCode.COMMAND_ERROR, "spawnpoint requires a target player", location),
            )
        }
    val posIndex = if (targetToken != null && !isCoordinateToken(targetToken)) 2 else 1
    val position = if (tokens.size >= posIndex + 3) parsePosition(tokens, posIndex, context, location) else context.position
    val angle = tokens.getOrNull(posIndex + 3)?.text?.let { parseDouble(it, "spawn angle", location) }
    targets.forEach {
        it.spawnPoint = SpawnPoint(position = position, dimension = it.dimension, angle = angle)
    }
    world.recordOutput(
        "spawnpoint",
        "data",
        targets = targets.map { it.name },
        text = targets.size.toString(),
        payload =
            JsonObject().also { payload ->
                payload.addProperty("count", targets.size)
                payload.add("position", positionOutput(position))
                angle?.let { payload.addProperty("angle", it) }
                payload.add(
                    "players",
                    JsonArray().also { players ->
                        targets.forEach { player ->
                            players.add(
                                JsonObject().also { entry ->
                                    entry.addProperty("player", player.name)
                                    entry.addProperty("uuid", player.uuid)
                                    player.spawnPoint?.let { entry.add("spawn", spawnPointOutput(it, location)) }
                                },
                            )
                        }
                    },
                )
            },
    )
}

internal fun DatapackSandbox.executeTick(
    tokens: List<CommandToken>,
    location: SourceLocation?,
) {
    requireSize(tokens, 2, "tick <query|rate|freeze|unfreeze|step|sprint|stop>", location)

    fun recordTickOutput(
        command: String,
        action: String,
        text: String,
        beforeRate: Double? = null,
        requestedRate: Double? = null,
        beforeFrozen: Boolean? = null,
        beforeGameTime: Long? = null,
        ticks: Int? = null,
    ) {
        world.recordOutput(
            command,
            "data",
            text = text,
            payload =
                JsonObject().also { payload ->
                    payload.addProperty("action", action)
                    payload.addProperty("rate", world.tickRate)
                    payload.addProperty("frozen", world.tickFrozen)
                    payload.addProperty("gameTime", world.gameTime)
                    beforeRate?.let { payload.addProperty("beforeRate", it) }
                    requestedRate?.let { payload.addProperty("requestedRate", it) }
                    beforeFrozen?.let { payload.addProperty("beforeFrozen", it) }
                    beforeGameTime?.let { before ->
                        payload.addProperty("beforeGameTime", before)
                        payload.addProperty("advancedTicks", world.gameTime - before)
                    }
                    ticks?.let { payload.addProperty("ticks", it) }
                },
        )
    }
    when (tokens[1].text) {
        "query" -> {
            val payload = JsonObject()
            payload.addProperty("rate", world.tickRate)
            payload.addProperty("frozen", world.tickFrozen)
            payload.addProperty("gameTime", world.gameTime)
            world.recordOutput(
                "tick query",
                "data",
                text = "rate=${world.tickRate}, frozen=${world.tickFrozen}, gameTime=${world.gameTime}",
                payload = payload,
            )
        }
        "rate" -> {
            requireSize(tokens, 3, "tick rate <rate>", location)
            val beforeRate = world.tickRate
            val requestedRate = parseDouble(tokens[2].text, "tick rate", location)
            world.tickRate = requestedRate.coerceAtLeast(1.0)
            recordTickOutput(
                command = "tick rate",
                action = "rate",
                text = world.tickRate.toString(),
                beforeRate = beforeRate,
                requestedRate = requestedRate,
            )
        }
        "freeze" -> {
            val beforeFrozen = world.tickFrozen
            world.tickFrozen = true
            recordTickOutput(
                command = "tick freeze",
                action = "freeze",
                text = world.tickFrozen.toString(),
                beforeFrozen = beforeFrozen,
            )
        }
        "unfreeze" -> {
            val beforeFrozen = world.tickFrozen
            world.tickFrozen = false
            recordTickOutput(
                command = "tick unfreeze",
                action = "unfreeze",
                text = world.tickFrozen.toString(),
                beforeFrozen = beforeFrozen,
            )
        }
        "step", "sprint" -> {
            val action = tokens[1].text
            val ticks = tokens.getOrNull(2)?.text?.let { parseTime(it, location).toInt() } ?: 1
            val beforeGameTime = world.gameTime
            runTicks(ticks)
            recordTickOutput(
                command = "tick $action",
                action = action,
                text = (world.gameTime - beforeGameTime).toString(),
                beforeGameTime = beforeGameTime,
                ticks = ticks,
            )
        }
        "stop" -> world.recordOutput("tick stop", "data", text = "No active tick sprint")
        else -> unsupportedFeature("Unsupported tick action '${tokens[1].text}'", profile.id, location)
    }
}

internal fun DatapackSandbox.executeTrigger(
    tokens: List<CommandToken>,
    location: SourceLocation?,
    context: ExecutionContext,
) {
    requireSize(tokens, 2, "trigger <objective> [add|set] [value]", location)
    val player =
        context.entity as? SandboxPlayer ?: world.players.values.firstOrNull()
            ?: throw SandboxException(DiagnosticCode.COMMAND_ERROR, "trigger requires a player", location)
    val objective = tokens[1].text
    world.ensureObjective(objective)
    val action = tokens.getOrNull(2)?.text ?: "add"
    val value = tokens.getOrNull(3)?.text?.let { parseInt(it, "trigger value", location) } ?: 1
    when (action) {
        "add" -> world.addScore(player.name, objective, value)
        "set" -> world.setScore(player.name, objective, value)
        else -> unsupportedFeature("Unsupported trigger action '$action'", profile.id, location)
    }
}

internal fun DatapackSandbox.executeWorldBorder(
    tokens: List<CommandToken>,
    location: SourceLocation?,
) {
    requireSize(tokens, 2, "worldborder <add|center|damage|get|set|warning>", location)
    val border = world.worldBorder

    fun recordWorldBorderOutput(
        command: String,
        action: String,
        text: String,
        before: SandboxWorldBorder,
        extra: JsonObject.() -> Unit = {},
    ) {
        world.recordOutput(
            command,
            "data",
            text = text,
            payload =
                border.toJson().also { payload ->
                    payload.addProperty("action", action)
                    payload.add("before", before.toJson())
                    payload.extra()
                },
        )
    }
    when (tokens[1].text) {
        "get" -> world.recordOutput("worldborder get", "data", text = border.size.toString(), payload = border.toJson())
        "set" -> {
            requireSize(tokens, 3, "worldborder set <distance> [time]", location)
            val before = border.copy()
            val requestedSize = parseDouble(tokens[2].text, "worldborder size", location)
            border.targetSize = requestedSize.coerceAtLeast(1.0)
            border.size = border.targetSize
            border.lerpTimeSeconds = tokens.getOrNull(3)?.text?.let { parseLong(it, "worldborder time", location) } ?: 0
            recordWorldBorderOutput("worldborder set", "set", border.size.toString(), before) {
                addProperty("requestedSize", requestedSize)
            }
        }
        "add" -> {
            requireSize(tokens, 3, "worldborder add <distance> [time]", location)
            val before = border.copy()
            val delta = parseDouble(tokens[2].text, "worldborder size delta", location)
            border.targetSize = (border.size + delta).coerceAtLeast(1.0)
            border.size = border.targetSize
            border.lerpTimeSeconds = tokens.getOrNull(3)?.text?.let { parseLong(it, "worldborder time", location) } ?: 0
            recordWorldBorderOutput("worldborder add", "add", border.size.toString(), before) {
                addProperty("delta", delta)
            }
        }
        "center" -> {
            requireSize(tokens, 4, "worldborder center <x> <z>", location)
            val before = border.copy()
            border.centerX = parseCoordinate(tokens[2].text, border.centerX, location)
            border.centerZ = parseCoordinate(tokens[3].text, border.centerZ, location)
            recordWorldBorderOutput(
                "worldborder center",
                "center",
                "${border.centerX} ${border.centerZ}",
                before,
            )
        }
        "damage" -> {
            requireSize(tokens, 4, "worldborder damage <amount|buffer> <value>", location)
            val before = border.copy()
            val field = tokens[2].text
            val value =
                when (field) {
                    "amount", "buffer" -> parseDouble(tokens[3].text, "worldborder damage $field", location)
                    else -> unsupportedFeature("Unsupported worldborder damage field '${tokens[2].text}'", profile.id, location)
                }
            when (field) {
                "amount" -> border.damageAmount = value
                "buffer" -> border.damageBuffer = value
            }
            recordWorldBorderOutput("worldborder damage", "damage", value.toString(), before) {
                addProperty("field", field)
                addProperty("value", value)
            }
        }
        "warning" -> {
            requireSize(tokens, 4, "worldborder warning <distance|time> <value>", location)
            val before = border.copy()
            val field = tokens[2].text
            val value =
                when (field) {
                    "distance", "time" -> parseInt(tokens[3].text, "worldborder warning $field", location)
                    else -> unsupportedFeature("Unsupported worldborder warning field '${tokens[2].text}'", profile.id, location)
                }
            when (field) {
                "distance" -> border.warningDistance = value
                "time" -> border.warningTime = value
            }
            recordWorldBorderOutput("worldborder warning", "warning", value.toString(), before) {
                addProperty("field", field)
                addProperty("value", value)
            }
        }
        else -> unsupportedFeature("Unsupported worldborder action '${tokens[1].text}'", profile.id, location)
    }
}

internal fun DatapackSandbox.executeAdvancement(
    tokens: List<CommandToken>,
    location: SourceLocation?,
) {
    requireSize(tokens, 4, "advancement <grant|revoke|test> <targets> <mode> ...", location)
    val action = tokens[1].text
    val targets = resolvePlayers(tokens[2].text, location)
    val mode = tokens[3].text
    if (action == "test") {
        val id = ResourceLocation.parse(tokens[3].text)
        val criterion = tokens.getOrNull(4)?.text
        val results = JsonArray()
        var passed = 0
        targets.forEach { player ->
            val advancement = datapack.advancement(id)
            val progress = advancements.progress(player, id)
            val done = progress.isDone(advancement.requirements)
            val criterionDone = criterion?.let { progress.criteria[it] == true }
            val success = criterionDone ?: done
            if (success) passed += 1
            results.add(
                JsonObject().also { result ->
                    result.addProperty("player", player.name)
                    result.addProperty("done", done)
                    criterion?.let {
                        result.addProperty("criterion", it)
                        result.addProperty("criterionDone", criterionDone)
                    }
                },
            )
        }
        world.recordOutput(
            "advancement test",
            "data",
            text = passed.toString(),
            payload =
                JsonObject().also { payload ->
                    payload.addProperty("id", id.toString())
                    criterion?.let { payload.addProperty("criterion", it) }
                    payload.addProperty("passed", passed)
                    payload.add("results", results)
                },
        )
        return
    }
    val ids = advancementTargets(mode, tokens.getOrNull(4)?.text, location)
    val criterion = if (mode == "only") tokens.getOrNull(5)?.text else null
    val updates = mutableListOf<Pair<String, AdvancementUpdate>>()
    targets.forEach { player ->
        ids.forEach { advancement ->
            val changed =
                when (action) {
                    "grant" -> advancements.grant(player, advancement, criterion)
                    "revoke" -> advancements.revoke(player, advancement, criterion)
                    else -> unsupportedFeature("Unsupported advancement action '$action'", profile.id, location)
                }
            changed.forEach { updates += player.name to it }
        }
    }
    world.recordOutput(
        "advancement $action",
        "data",
        targets = targets.map { it.name },
        text = updates.size.toString(),
        payload =
            JsonObject().also { payload ->
                payload.addProperty("action", action)
                payload.addProperty("mode", mode)
                criterion?.let { payload.addProperty("criterion", it) }
                payload.add("targets", JsonArray().also { array -> targets.map { it.name }.sorted().forEach { array.add(it) } })
                payload.add("advancements", JsonArray().also { array -> ids.forEach { array.add(it.toString()) } })
                payload.addProperty("changed", updates.size)
                payload.add(
                    "updates",
                    JsonArray().also { array ->
                        updates.forEach { (player, update) ->
                            array.add(
                                JsonObject().also { item ->
                                    item.addProperty("player", player)
                                    item.addProperty("advancement", update.advancement.toString())
                                    item.addProperty("criterion", update.criterion)
                                    item.addProperty("done", update.completed)
                                },
                            )
                        }
                    },
                )
            },
    )
}

internal fun DatapackSandbox.advancementTargets(
    mode: String,
    idText: String?,
    location: SourceLocation?,
): List<ResourceLocation> =
    when (mode) {
        "everything" -> datapack.advancements.keys.sorted()
        "only" -> listOf(advancementTargetId(idText, location))
        "from" -> {
            val id = advancementTargetId(idText, location)
            listOf(id) + advancementDescendants(id)
        }
        "through" -> {
            val id = advancementTargetId(idText, location)
            advancementAncestors(id, location).asReversed() + id + advancementDescendants(id)
        }
        "until" -> {
            val id = advancementTargetId(idText, location)
            advancementAncestors(id, location).asReversed() + id
        }
        else -> unsupportedFeature("Unsupported advancement mode '$mode'", profile.id, location)
    }.distinct()

internal fun DatapackSandbox.advancementTargetId(
    idText: String?,
    location: SourceLocation?,
): ResourceLocation {
    val id =
        ResourceLocation.parse(
            idText ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Advancement id is required", location),
        )
    datapack.advancement(id)
    return id
}

internal fun DatapackSandbox.advancementAncestors(
    id: ResourceLocation,
    location: SourceLocation?,
): List<ResourceLocation> {
    val ancestors = mutableListOf<ResourceLocation>()
    val seen = mutableSetOf(id)
    var parent = datapack.advancement(id).parent
    while (parent != null) {
        if (!seen.add(parent)) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Advancement parent cycle detected at '$parent'", location)
        }
        val definition =
            datapack.advancements[parent]
                ?: throw SandboxException(DiagnosticCode.RESOURCE_NOT_FOUND, "Advancement parent '$parent' was not found", location)
        ancestors += parent
        parent = definition.parent
    }
    return ancestors
}

internal fun DatapackSandbox.advancementDescendants(id: ResourceLocation): List<ResourceLocation> {
    val childrenByParent = datapack.advancements.values.groupBy { it.parent }
    val descendants = mutableListOf<ResourceLocation>()
    val seen = mutableSetOf(id)

    fun visit(parent: ResourceLocation) {
        childrenByParent[parent].orEmpty().sortedBy { it.id.toString() }.forEach { child ->
            if (seen.add(child.id)) {
                descendants += child.id
                visit(child.id)
            }
        }
    }
    visit(id)
    return descendants
}

internal fun DatapackSandbox.executeTeam(
    command: String,
    tokens: List<CommandToken>,
    location: SourceLocation?,
) {
    requireSize(tokens, 2, "team <add|remove|list|join|leave|empty|modify> ...", location)

    fun teamArray(teams: Collection<SandboxTeam>): JsonArray =
        JsonArray().also { array -> teams.sortedBy { it.name }.forEach { array.add(it.toJson()) } }

    fun memberArray(members: Collection<String>): JsonArray = JsonArray().also { array -> members.sorted().forEach { array.add(it) } }

    fun teamMap(teams: Collection<SandboxTeam>): JsonObject =
        JsonObject().also { json -> teams.sortedBy { it.name }.forEach { json.add(it.name, it.toJson()) } }

    fun recordTeamOutput(
        command: String,
        action: String,
        teamName: String,
        text: String,
        team: SandboxTeam? = null,
        before: JsonObject? = null,
        extra: JsonObject.() -> Unit = {},
    ) {
        world.recordOutput(
            command,
            "data",
            text = text,
            payload =
                (team?.toJson() ?: JsonObject().also { it.addProperty("name", teamName) }).also { payload ->
                    payload.addProperty("action", action)
                    before?.let { payload.add("before", it) }
                    payload.extra()
                },
        )
    }

    when (tokens[1].text) {
        "add" -> {
            requireSize(tokens, 3, "team add <team> [displayName]", location)
            val name = tokens[2].text
            val before = world.teams[name]?.toJson()
            val team = SandboxTeam(name, tokens.getOrNull(3)?.let { CommandTokenizer.tailFrom(command, it) } ?: name)
            world.teams[name] = team
            recordTeamOutput("team add", "add", name, name, team, before) {
                addProperty("replaced", before != null)
            }
        }
        "remove" -> {
            requireSize(tokens, 3, "team remove <team>", location)
            val name = tokens[2].text
            val removed = world.teams.remove(name)
            recordTeamOutput("team remove", "remove", name, if (removed != null) "1" else "0", before = removed?.toJson()) {
                addProperty("removed", removed != null)
            }
        }
        "list" -> {
            val team = tokens.getOrNull(2)?.text
            val text =
                if (team ==
                    null
                ) {
                    world.teams.keys
                        .sorted()
                        .joinToString()
                } else {
                    world.teams[team]
                        ?.members
                        ?.joinToString()
                        .orEmpty()
                }
            world.recordOutput(
                "team list",
                "data",
                text = text,
                payload =
                    JsonObject().also { payload ->
                        payload.addProperty("action", "list")
                        if (team == null) {
                            payload.add("teams", teamArray(world.teams.values))
                            payload.addProperty("count", world.teams.size)
                        } else {
                            val existing = world.teams[team]
                            payload.addProperty("name", team)
                            payload.addProperty("exists", existing != null)
                            payload.add("members", memberArray(existing?.members.orEmpty()))
                            payload.addProperty("count", existing?.members?.size ?: 0)
                        }
                    },
            )
        }
        "join" -> {
            requireSize(tokens, 4, "team join <team> <members...>", location)
            val team = world.teams.getOrPut(tokens[2].text) { SandboxTeam(tokens[2].text) }
            val before = team.toJson()
            val members = tokens.drop(3).map { it.text }
            val added = members.count { it !in team.members }
            team.members += members
            recordTeamOutput("team join", "join", team.name, added.toString(), team, before) {
                add("requestedMembers", memberArray(members))
                addProperty("added", added)
            }
        }
        "leave" -> {
            requireSize(tokens, 3, "team leave <members...>", location)
            val beforeTeams = teamMap(world.teams.values)
            val members = tokens.drop(2).map { it.text }
            var removed = 0
            members.forEach { member ->
                world.teams.values.forEach { team ->
                    if (team.members.remove(member)) removed += 1
                }
            }
            world.recordOutput(
                "team leave",
                "data",
                text = removed.toString(),
                payload =
                    JsonObject().also { payload ->
                        payload.addProperty("action", "leave")
                        payload.add("members", memberArray(members))
                        payload.addProperty("removed", removed)
                        payload.add("beforeTeams", beforeTeams)
                        payload.add("teams", teamArray(world.teams.values))
                    },
            )
        }
        "empty" -> {
            requireSize(tokens, 3, "team empty <team>", location)
            val name = tokens[2].text
            val team = world.teams[name]
            val before = team?.toJson()
            val removed = team?.members?.size ?: 0
            team?.members?.clear()
            recordTeamOutput("team empty", "empty", name, removed.toString(), team, before) {
                addProperty("removed", removed)
            }
        }
        "modify" -> {
            requireSize(tokens, 5, "team modify <team> <option> <value>", location)
            val team = world.teams.getOrPut(tokens[2].text) { SandboxTeam(tokens[2].text) }
            val before = team.toJson()
            val option = tokens[3].text
            val value =
                if (option == "displayName") {
                    CommandTokenizer.tailFrom(command, tokens[4])
                } else {
                    tokens[4].text
                }
            if (option == "displayName") team.displayName = value else team.options[option] = value
            recordTeamOutput("team modify", "modify", team.name, value, team, before) {
                addProperty("option", option)
                addProperty("value", value)
            }
        }
        else -> unsupportedFeature("Unsupported team action '${tokens[1].text}'", profile.id, location)
    }
}
