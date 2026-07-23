package moe.afox.dpsandbox.core.command
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import moe.afox.dpsandbox.core.BlockArgument
import moe.afox.dpsandbox.core.BlockPos
import moe.afox.dpsandbox.core.CommandToken
import moe.afox.dpsandbox.core.CommandTokenizer
import moe.afox.dpsandbox.core.DatapackSandbox
import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.EntitySelectors
import moe.afox.dpsandbox.core.ExecutionContext
import moe.afox.dpsandbox.core.ResourceLocation
import moe.afox.dpsandbox.core.SandboxBlock
import moe.afox.dpsandbox.core.SandboxException
import moe.afox.dpsandbox.core.SandboxPlayer
import moe.afox.dpsandbox.core.SourceLocation
import moe.afox.dpsandbox.core.TextComponents
import moe.afox.dpsandbox.core.copyForClone
import moe.afox.dpsandbox.core.toJson
import moe.afox.dpsandbox.core.unsupportedFeature

internal fun DatapackSandbox.executeSetBlock(
    tokens: List<CommandToken>,
    location: SourceLocation?,
    context: ExecutionContext,
) {
    requireSize(tokens, 5, "setblock <pos> <block> [destroy|keep|replace]", location)
    val pos = parseBlockPos(tokens, 1, context, location)
    val block = parseBlockArgument(tokens[4].text, location)
    val before = world.block(pos)?.copyForClone()
    when (val mode = tokens.getOrNull(5)?.text ?: "replace") {
        "replace", "destroy", "strict" -> world.setBlock(pos, block.toBlock(pos, profile, location))
        "keep" -> if (world.block(pos) == null) world.setBlock(pos, block.toBlock(pos, profile, location))
        else -> unsupportedFeature("Unsupported setblock mode '$mode'", profile.id, location)
    }
    val after = world.block(pos)?.copyForClone()
    recordSetBlockOutput(pos, tokens.getOrNull(5)?.text ?: "replace", before, after)
}

internal fun DatapackSandbox.recordSetBlockOutput(
    pos: BlockPos,
    mode: String,
    before: SandboxBlock?,
    after: SandboxBlock?,
) {
    val changed = !sameBlock(before, after)
    world.recordOutput(
        "setblock",
        "data",
        targets = if (changed) listOf(pos.toString()) else emptyList(),
        text = if (changed) "1" else "0",
        payload =
            JsonObject().also { payload ->
                payload.addProperty("changed", changed)
                payload.addProperty("mode", mode)
                payload.add("pos", blockPosOutput(pos))
                before?.let { payload.add("before", it.toJson(pos)) }
                after?.let { payload.add("after", it.toJson(pos)) }
            },
    )
}

internal fun DatapackSandbox.blockPosOutput(pos: BlockPos): JsonObject =
    JsonObject().also { json ->
        json.addProperty("x", pos.x)
        json.addProperty("y", pos.y)
        json.addProperty("z", pos.z)
    }

internal fun DatapackSandbox.blockPosArrayOutput(positions: List<BlockPos>): JsonArray =
    JsonArray().also { array -> positions.forEach { array.add(it.toString()) } }

internal fun DatapackSandbox.executeClone(
    tokens: List<CommandToken>,
    location: SourceLocation?,
    context: ExecutionContext,
) {
    requireSize(tokens, 10, "clone <begin> <end> <destination> [replace|masked|filtered] [force|move|normal]", location)
    val from = parseBlockPos(tokens, 1, context, location)
    val to = parseBlockPos(tokens, 4, context, location)
    val dest = parseBlockPos(tokens, 7, context, location)
    val maskMode = tokens.getOrNull(10)?.text ?: "replace"
    val cloneMode = tokens.getOrNull(11)?.text ?: "normal"
    if (cloneMode !in setOf("normal", "force", "move")) {
        unsupportedFeature("Unsupported clone mode '$cloneMode'", profile.id, location)
    }
    val xs = minOf(from.x, to.x)..maxOf(from.x, to.x)
    val ys = minOf(from.y, to.y)..maxOf(from.y, to.y)
    val zs = minOf(from.z, to.z)..maxOf(from.z, to.z)
    val copied = mutableListOf<Pair<BlockPos, SandboxBlock>>()
    for ((dx, x) in xs.withIndex()) {
        for ((dy, y) in ys.withIndex()) {
            for ((dz, z) in zs.withIndex()) {
                val sourcePos = BlockPos(x, y, z)
                val source = world.block(sourcePos)
                if (source == null && maskMode == "masked") continue
                source?.let { copied += BlockPos(dest.x + dx, dest.y + dy, dest.z + dz) to it.copyForClone() }
            }
        }
    }
    val changed = mutableListOf<BlockPos>()
    val removedSources = mutableListOf<BlockPos>()
    copied.forEach { (pos, block) ->
        val before = world.block(pos)?.copyForClone()
        world.setBlock(pos, block)
        val after = world.block(pos)?.copyForClone()
        if (!sameBlock(before, after)) changed += pos
    }
    if (cloneMode == "move") {
        for (x in xs) {
            for (y in ys) {
                for (z in zs) {
                    val sourcePos = BlockPos(x, y, z)
                    val before = world.block(sourcePos)?.copyForClone()
                    world.setBlock(sourcePos, null)
                    val after = world.block(sourcePos)?.copyForClone()
                    if (!sameBlock(before, after)) {
                        removedSources += sourcePos
                        changed += sourcePos
                    }
                }
            }
        }
    }
    recordCloneOutput(from, to, dest, maskMode, cloneMode, copied.map { it.first }, removedSources, changed)
}

internal fun DatapackSandbox.recordCloneOutput(
    from: BlockPos,
    to: BlockPos,
    destination: BlockPos,
    maskMode: String,
    cloneMode: String,
    copiedPositions: List<BlockPos>,
    removedSourcePositions: List<BlockPos>,
    changedPositions: List<BlockPos>,
) {
    world.recordOutput(
        "clone",
        "data",
        targets = copiedPositions.map { it.toString() },
        text = copiedPositions.size.toString(),
        payload =
            JsonObject().also { payload ->
                payload.addProperty("maskMode", maskMode)
                payload.addProperty("cloneMode", cloneMode)
                payload.addProperty("copied", copiedPositions.size)
                payload.addProperty("removedSources", removedSourcePositions.size)
                payload.addProperty("changed", changedPositions.size)
                payload.add("from", blockPosOutput(from))
                payload.add("to", blockPosOutput(to))
                payload.add("destination", blockPosOutput(destination))
                payload.add("copiedPositions", blockPosArrayOutput(copiedPositions))
                payload.add("removedSourcePositions", blockPosArrayOutput(removedSourcePositions))
                payload.add("changedPositions", blockPosArrayOutput(changedPositions))
            },
    )
}

internal fun DatapackSandbox.executeFill(
    tokens: List<CommandToken>,
    location: SourceLocation?,
    context: ExecutionContext,
) {
    requireSize(tokens, 8, "fill <from> <to> <block> [replace|keep|destroy|hollow|outline]", location)
    val from = parseBlockPos(tokens, 1, context, location)
    val to = parseBlockPos(tokens, 4, context, location)
    val block = parseBlockArgument(tokens[7].text, location)
    val mode = tokens.getOrNull(8)?.text ?: "replace"
    if (mode !in setOf("replace", "keep", "destroy", "hollow", "outline")) {
        unsupportedFeature("Unsupported fill mode '$mode'", profile.id, location)
    }
    val xs = minOf(from.x, to.x)..maxOf(from.x, to.x)
    val ys = minOf(from.y, to.y)..maxOf(from.y, to.y)
    val zs = minOf(from.z, to.z)..maxOf(from.z, to.z)
    val volume = xs.count() * ys.count() * zs.count()
    if (volume > 32768) {
        throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Fill volume $volume exceeds sandbox limit 32768", location)
    }
    val changed = mutableListOf<BlockPos>()
    for (x in xs) {
        for (y in ys) {
            for (z in zs) {
                val pos = BlockPos(x, y, z)
                val before = world.block(pos)?.copyForClone()
                val boundary = x == xs.first || x == xs.last || y == ys.first || y == ys.last || z == zs.first || z == zs.last
                when {
                    mode == "keep" && world.block(pos) != null -> Unit
                    mode == "outline" && !boundary -> Unit
                    mode == "hollow" && !boundary -> world.setBlock(pos, null)
                    else -> world.setBlock(pos, block.toBlock(pos, profile, location))
                }
                val after = world.block(pos)?.copyForClone()
                if (!sameBlock(before, after)) changed += pos
            }
        }
    }
    recordFillOutput(from, to, block, mode, volume, changed)
}

internal fun DatapackSandbox.recordFillOutput(
    from: BlockPos,
    to: BlockPos,
    block: BlockArgument,
    mode: String,
    volume: Int,
    changed: List<BlockPos>,
) {
    world.recordOutput(
        "fill",
        "data",
        targets = changed.map { it.toString() },
        text = changed.size.toString(),
        payload =
            JsonObject().also { payload ->
                payload.addProperty("mode", mode)
                payload.addProperty("volume", volume)
                payload.addProperty("changed", changed.size)
                payload.add("from", blockPosOutput(from))
                payload.add("to", blockPosOutput(to))
                payload.add("block", blockArgumentOutput(block))
                payload.add(
                    "positions",
                    JsonArray().also { positions ->
                        changed.forEach { pos -> positions.add(pos.toString()) }
                    },
                )
            },
    )
}

internal fun DatapackSandbox.blockArgumentOutput(block: BlockArgument): JsonObject =
    JsonObject().also { json ->
        json.addProperty("id", block.id.toString())
        json.add(
            "properties",
            JsonObject().also { properties ->
                block.properties.toSortedMap().forEach { (key, value) -> properties.addProperty(key, value) }
            },
        )
        json.add("nbt", block.nbt.deepCopy())
    }

internal fun DatapackSandbox.executeTellraw(
    command: String,
    tokens: List<CommandToken>,
    location: SourceLocation?,
    context: ExecutionContext,
) {
    requireSize(tokens, 3, "tellraw <targets> <message>", location)
    val targets = resolvePlayers(tokens[1].text, location, context)
    val payload = TextComponents.parse(CommandTokenizer.tailFrom(command, tokens[2]), location)
    if (targets.isEmpty()) {
        val rendered = TextComponents.resolve(payload, world, context, null, location)
        world.recordOutput("tellraw", "chat", emptyList(), rendered.plain, payload, rendered.segments)
    } else {
        targets.forEach { target ->
            val rendered = TextComponents.resolve(payload, world, context, target, location)
            world.recordOutput("tellraw", "chat", listOf(target.name), rendered.plain, payload, rendered.segments)
        }
    }
}

internal fun DatapackSandbox.executeTitle(
    command: String,
    tokens: List<CommandToken>,
    location: SourceLocation?,
    context: ExecutionContext,
) {
    requireSize(tokens, 3, "title <targets> <clear|reset|title|subtitle|actionbar|times> ...", location)
    val targets = resolvePlayers(tokens[1].text, location, context).map { it.name }
    when (val action = tokens[2].text) {
        "clear", "reset" -> world.recordOutput("title $action", "title", targets, action)
        "times" -> {
            requireSize(tokens, 6, "title <targets> times <fadeIn> <stay> <fadeOut>", location)
            val payload = JsonObject()
            payload.addProperty("fadeIn", parseInt(tokens[3].text, "title fadeIn", location))
            payload.addProperty("stay", parseInt(tokens[4].text, "title stay", location))
            payload.addProperty("fadeOut", parseInt(tokens[5].text, "title fadeOut", location))
            world.recordOutput("title times", "title", targets, "${tokens[3].text} ${tokens[4].text} ${tokens[5].text}", payload)
        }
        "title", "subtitle", "actionbar" -> {
            requireSize(tokens, 4, "title <targets> $action <title>", location)
            val payload = TextComponents.parse(CommandTokenizer.tailFrom(command, tokens[3]), location)
            if (targets.isEmpty()) {
                val rendered = TextComponents.resolve(payload, world, context, null, location)
                world.recordOutput("title $action", "title", emptyList(), rendered.plain, payload, rendered.segments)
            } else {
                targets.forEach { target ->
                    val player = world.requirePlayer(target)
                    val rendered = TextComponents.resolve(payload, world, context, player, location)
                    world.recordOutput("title $action", "title", listOf(target), rendered.plain, payload, rendered.segments)
                }
            }
        }
        else -> unsupportedFeature("Unsupported title action '$action'", profile.id, location, command)
    }
}

internal fun DatapackSandbox.executeSay(
    command: String,
    tokens: List<CommandToken>,
    location: SourceLocation?,
    context: ExecutionContext,
) {
    requireSize(tokens, 2, "say <message>", location)
    val sender = outputSender(context)
    val text = CommandTokenizer.tailFrom(command, tokens[1])
    world.recordOutput(
        "say",
        "chat",
        world.players.keys.toList(),
        "<$sender> $text",
        chatCommandPayload(ResourceLocation("minecraft", "say_command"), sender, text, location),
        rawText = text,
    )
}

internal fun DatapackSandbox.executeMe(
    command: String,
    tokens: List<CommandToken>,
    location: SourceLocation?,
    context: ExecutionContext,
) {
    requireSize(tokens, 2, "me <action>", location)
    val sender = outputSender(context)
    val text = CommandTokenizer.tailFrom(command, tokens[1])
    world.recordOutput(
        "me",
        "chat",
        world.players.keys.toList(),
        "* $sender $text",
        chatCommandPayload(ResourceLocation("minecraft", "emote_command"), sender, text, location),
        rawText = text,
    )
}

internal fun DatapackSandbox.executePrivateMessage(
    command: String,
    tokens: List<CommandToken>,
    location: SourceLocation?,
    context: ExecutionContext,
) {
    requireSize(tokens, 3, "${tokens[0].text} <targets> <message>", location)
    val targets = resolvePlayers(tokens[1].text, location, context).map { it.name }
    val sender = outputSender(context)
    val text = CommandTokenizer.tailFrom(command, tokens[2])
    world.recordOutput(
        tokens[0].text,
        "chat",
        targets,
        "[$sender -> ${targets.joinToString()}] $text",
        chatCommandPayload(ResourceLocation("minecraft", "msg_command_incoming"), sender, text, location),
        rawText = text,
    )
}

internal fun DatapackSandbox.executeTeamMessage(
    command: String,
    tokens: List<CommandToken>,
    location: SourceLocation?,
    context: ExecutionContext,
) {
    requireSize(tokens, 2, "${tokens[0].text} <message>", location)
    val sender = outputSender(context)
    val text = CommandTokenizer.tailFrom(command, tokens[1])
    world.recordOutput(
        tokens[0].text,
        "chat",
        world.players.keys.toList(),
        "[team] <$sender> $text",
        chatCommandPayload(ResourceLocation("minecraft", "team_msg_command_incoming"), sender, text, location),
        rawText = text,
    )
}

internal fun DatapackSandbox.chatCommandPayload(
    chatType: ResourceLocation,
    sender: String,
    message: String,
    location: SourceLocation?,
): JsonObject =
    JsonObject().also { payload ->
        payload.addProperty("sender", sender)
        payload.addProperty("message", message)
        payload.addProperty("chatType", chatType.toString())
        chatTypePayload(chatType, location)?.let { payload.add("chatTypeResource", it) }
    }

internal fun DatapackSandbox.chatTypePayload(
    id: ResourceLocation,
    location: SourceLocation?,
): JsonObject? {
    val resource = datapack.rawResources["chat_type"]?.get(id) ?: return null
    if (!resource.root.isJsonObject) {
        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Chat type resource '$id' must be a JSON object", location)
    }
    val root = resource.root.asJsonObject
    return JsonObject().also { payload ->
        payload.addProperty("id", id.toString())
        payload.addProperty("resource", resource.file)
        payload.addProperty("version", resource.version ?: profile.id)
        root.get("chat")?.let { payload.add("chat", it.deepCopy()) }
        root.get("narration")?.let { payload.add("narration", it.deepCopy()) }
    }
}

internal fun DatapackSandbox.executePlaySound(
    tokens: List<CommandToken>,
    location: SourceLocation?,
    context: ExecutionContext,
) {
    requireSize(tokens, 4, "playsound <sound> <source> <targets> [pos] [volume] [pitch] [minVolume]", location)
    val targets = resolvePlayers(tokens[3].text, location, context).map { it.name }
    val payload = JsonObject()
    payload.addProperty("sound", ResourceLocation.parse(tokens[1].text).toString())
    payload.addProperty("source", tokens[2].text)
    world.recordOutput("playsound", "sound", targets, "${tokens[2].text}:${tokens[1].text}", payload)
}

internal fun DatapackSandbox.executeStopSound(
    tokens: List<CommandToken>,
    location: SourceLocation?,
    context: ExecutionContext,
) {
    requireSize(tokens, 2, "stopsound <targets> [source] [sound]", location)
    val targets = resolvePlayers(tokens[1].text, location, context).map { it.name }
    val payload = JsonObject()
    tokens.getOrNull(2)?.text?.let { payload.addProperty("source", it) }
    tokens.getOrNull(3)?.text?.let { payload.addProperty("sound", ResourceLocation.parse(it).toString()) }
    world.recordOutput("stopsound", "sound", targets, "stop sound", payload)
}

internal fun DatapackSandbox.executeParticle(
    command: String,
    tokens: List<CommandToken>,
    location: SourceLocation?,
    context: ExecutionContext,
) {
    requireSize(tokens, 2, "particle <name> [pos] [delta] [speed] [count] [force|normal] [viewers]", location)
    if (tokens.size > 12) {
        throw SandboxException(
            DiagnosticCode.INPUT_FORMAT,
            "Expected: particle <name> [pos] [delta] [speed] [count] [force|normal] [viewers]",
            location,
        )
    }
    if (tokens.size in 3..4 || tokens.size in 6..7) {
        throw SandboxException(
            DiagnosticCode.INPUT_FORMAT,
            "Particle position and delta arguments must each contain three coordinates",
            location,
        )
    }
    val particle = ResourceLocation.parse(tokens[1].text)
    val position = if (tokens.size >= 5) parsePosition(tokens, 2, context, location) else context.position
    val deltaX = tokens.getOrNull(5)?.text?.let { parseDouble(it, "particle delta x", location) } ?: 0.0
    val deltaY = tokens.getOrNull(6)?.text?.let { parseDouble(it, "particle delta y", location) } ?: 0.0
    val deltaZ = tokens.getOrNull(7)?.text?.let { parseDouble(it, "particle delta z", location) } ?: 0.0
    val speed = tokens.getOrNull(8)?.text?.let { parseDouble(it, "particle speed", location) } ?: 0.0
    if (speed < 0.0) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Particle speed must not be negative", location)
    val requestedCount = tokens.getOrNull(9)?.text?.let { parseInt(it, "particle count", location) } ?: 0
    if (requestedCount !in 0..MAX_VIEWPORT_PARTICLES_PER_COMMAND) {
        throw SandboxException(
            DiagnosticCode.INPUT_FORMAT,
            "Particle count must be between 0 and $MAX_VIEWPORT_PARTICLES_PER_COMMAND",
            location,
        )
    }
    val mode = tokens.getOrNull(10)?.text ?: "normal"
    if (mode !in setOf("normal", "force")) {
        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Particle mode must be 'normal' or 'force'", location)
    }
    val viewerSelector = tokens.getOrNull(11)?.text
    val viewers = viewerSelector?.let { resolvePlayers(it, location, context).map { player -> player.name } }.orEmpty()
    val payload =
        JsonObject().also { json ->
            json.addProperty("particle", particle.toString())
            json.addProperty("arguments", CommandTokenizer.tailAfter(command, tokens[1]))
            json.addProperty("x", position.x)
            json.addProperty("y", position.y)
            json.addProperty("z", position.z)
            json.addProperty("deltaX", deltaX)
            json.addProperty("deltaY", deltaY)
            json.addProperty("deltaZ", deltaZ)
            json.addProperty("speed", speed)
            json.addProperty("count", requestedCount)
            json.addProperty("renderCount", if (requestedCount == 0) 1 else requestedCount)
            json.addProperty("mode", mode)
            viewerSelector?.let { json.addProperty("viewerSelector", it) }
            json.add(
                "viewers",
                JsonArray().also { array -> viewers.forEach(array::add) },
            )
        }
    world.recordOutput("particle", "visual", viewers, particle.toString(), payload)
}

private const val MAX_VIEWPORT_PARTICLES_PER_COMMAND = 4_096

internal fun DatapackSandbox.executeTransfer(
    command: String,
    tokens: List<CommandToken>,
    location: SourceLocation?,
    context: ExecutionContext,
) {
    if (!profile.commands.hasRoot("transfer")) {
        handleUnknownCommand("transfer", command, location)
    }
    requireSize(tokens, 2, "transfer <host> [port] [players]", location)
    val targetFirst = tokens.size >= 3 && isTransferTargetFirst(tokens[1].text, tokens[2].text)
    val host = if (targetFirst) tokens[2].text else tokens[1].text
    val portIndex = if (targetFirst) 3 else 2
    val targetTokenIndex = if (!targetFirst && tokens.getOrNull(2)?.text?.toIntOrNull() != null) 3 else 2
    val port =
        tokens
            .getOrNull(portIndex)
            ?.takeIf { it.text.toIntOrNull() != null }
            ?.let { parseNetworkPort(it.text, "transfer port", location) }
            ?: 25565
    val targetToken = if (targetFirst) tokens.getOrNull(1)?.text else tokens.getOrNull(targetTokenIndex)?.text
    val targets =
        targetToken
            ?.let { resolvePlayers(it, location, context) }
            ?: listOfNotNull(context.entity as? SandboxPlayer).ifEmpty { world.players.values.toList() }
    val targetNames = targets.map { it.name }

    val payload = JsonObject()
    payload.addProperty("host", host)
    payload.addProperty("port", port)
    payload.addProperty("syntax", if (targetFirst) "target-first" else "host-first")
    payload.addProperty("noOp", true)
    payload.addProperty("reason", "Networking/server transfer is not simulated by the sandbox")
    payload.add(
        "targets",
        JsonArray().also { array ->
            targetNames.forEach { array.add(it) }
        },
    )
    world.recordOutput("transfer", "debug", targetNames, "$host:$port", payload)
}

internal fun DatapackSandbox.isTransferTargetFirst(
    firstArgument: String,
    secondArgument: String,
): Boolean {
    if (secondArgument.toIntOrNull() != null) return false
    if (firstArgument.contains(".") || firstArgument.contains(":")) return false
    return firstArgument == "@a" || EntitySelectors.isSelector(firstArgument) || firstArgument in world.players
}

internal fun DatapackSandbox.parseNetworkPort(
    raw: String,
    label: String,
    location: SourceLocation?,
): Int {
    val port = parseInt(raw, label, location)
    if (port !in 1..65535) {
        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid $label: '$raw' (expected 1..65535)", location)
    }
    return port
}

internal fun DatapackSandbox.executePublish(
    tokens: List<CommandToken>,
    location: SourceLocation?,
) {
    requireSize(tokens, 1, "publish [allowCommands] [gamemode] [port]", location)
    val allowCommands = tokens.getOrNull(1)?.text?.let { parseBoolean(it, "publish allowCommands", location) }
    val gameMode = tokens.getOrNull(2)?.text?.let { normalizeGameMode(it, location) }
    val port = tokens.getOrNull(3)?.text?.let { parseNetworkPort(it, "publish port", location) }
    val payload = JsonObject()
    allowCommands?.let { payload.addProperty("allowCommands", it) }
    gameMode?.let { payload.addProperty("gamemode", it) }
    port?.let { payload.addProperty("port", it) }
    payload.addProperty("noOp", true)
    payload.addProperty("reason", "LAN/network publishing is not simulated by the sandbox")
    world.recordOutput("publish", "debug", text = port?.toString().orEmpty(), payload = payload)
}

internal fun DatapackSandbox.executeProfilingNoop(
    tokens: List<CommandToken>,
    location: SourceLocation?,
) {
    requireSize(tokens, 2, "${tokens[0].text} <action> [...]", location)
    val arguments = JsonArray()
    tokens.drop(1).forEach { arguments.add(it.text) }
    val payload = JsonObject()
    payload.addProperty("action", tokens[1].text)
    payload.add("arguments", arguments)
    payload.addProperty("noOp", true)
    payload.addProperty("reason", "Profiling and flight recording are not simulated by the sandbox")
    world.recordOutput(tokens[0].text, "debug", text = tokens.drop(1).joinToString(" ") { it.text }, payload = payload)
}

internal fun DatapackSandbox.executeServerAdminNoop(
    command: String,
    tokens: List<CommandToken>,
    location: SourceLocation?,
) {
    val root = tokens[0].text

    fun payload(action: String): JsonObject =
        JsonObject().also {
            it.addProperty("action", action)
            it.addProperty("noOp", true)
            it.addProperty("noOpReason", "Server administration state is not simulated by the sandbox")
        }

    when (root) {
        "ban", "ban-ip", "kick" -> {
            requireSize(tokens, 2, "$root <target> [reason]", location)
            val reason = tokens.getOrNull(2)?.let { CommandTokenizer.tailFrom(command, it) }
            val payload = payload(root)
            payload.addProperty("target", tokens[1].text)
            reason?.let { payload.addProperty("message", it) }
            world.recordOutput(root, "debug", text = tokens[1].text, payload = payload)
        }
        "pardon", "pardon-ip", "op", "deop" -> {
            requireSize(tokens, 2, "$root <target>", location)
            world.recordOutput(
                root,
                "debug",
                text = tokens[1].text,
                payload = payload(root).also { it.addProperty("target", tokens[1].text) },
            )
        }
        "banlist" -> {
            val filter = tokens.getOrNull(1)?.text ?: "players"
            if (filter !in setOf("ips", "players")) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Expected: banlist [ips|players]", location)
            }
            world.recordOutput(
                "banlist",
                "debug",
                text = filter,
                payload = payload("list").also { it.addProperty("filter", filter) },
            )
        }
        "whitelist" -> executeWhitelistNoop(tokens, location, ::payload)
    }
}

internal fun DatapackSandbox.executeWhitelistNoop(
    tokens: List<CommandToken>,
    location: SourceLocation?,
    payloadFactory: (String) -> JsonObject,
) {
    requireSize(tokens, 2, "whitelist <add|remove|list|on|off|reload> [target]", location)
    val action = tokens[1].text
    if (action !in setOf("add", "remove", "list", "on", "off", "reload")) {
        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Expected: whitelist <add|remove|list|on|off|reload> [target]", location)
    }
    val payload = payloadFactory(action)
    if (action in setOf("add", "remove")) {
        requireSize(tokens, 3, "whitelist $action <target>", location)
        payload.addProperty("target", tokens[2].text)
    }
    world.recordOutput("whitelist $action", "debug", text = tokens.getOrNull(2)?.text.orEmpty(), payload = payload)
}

internal fun DatapackSandbox.executeSaveLifecycleNoop(
    tokens: List<CommandToken>,
    location: SourceLocation?,
) {
    val command = tokens[0].text
    val payload = JsonObject()
    payload.addProperty("action", command.removePrefix("save-"))
    payload.addProperty("noOp", true)
    payload.addProperty("noOpReason", "World save lifecycle is controlled by the host process")
    if (command == "save-all") {
        val flush =
            tokens.getOrNull(1)?.text?.let {
                if (it != "flush") throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Expected: save-all [flush]", location)
                true
            } ?: false
        payload.addProperty("flush", flush)
    }
    world.recordOutput(command, "debug", text = command, payload = payload)
}

internal fun DatapackSandbox.executeSetIdleTimeout(
    tokens: List<CommandToken>,
    location: SourceLocation?,
) {
    requireSize(tokens, 2, "setidletimeout <minutes>", location)
    val minutes = parseInt(tokens[1].text, "idle timeout minutes", location)
    if (minutes < 0) {
        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Invalid idle timeout minutes: '${tokens[1].text}'", location)
    }
    world.recordOutput(
        "setidletimeout",
        "debug",
        text = minutes.toString(),
        payload =
            JsonObject().also {
                it.addProperty("minutes", minutes)
                it.addProperty("noOp", true)
                it.addProperty("noOpReason", "Player idle timeout enforcement is not simulated by the sandbox")
            },
    )
}

internal fun DatapackSandbox.executeStop(
    tokens: List<CommandToken>,
    location: SourceLocation?,
) {
    requireSize(tokens, 1, "stop", location)
    world.recordOutput(
        "stop",
        "debug",
        text = "stop requested",
        payload =
            JsonObject().also { payload ->
                payload.addProperty("noOp", true)
                payload.addProperty("reason", "Runtime lifecycle is controlled by the host process")
            },
    )
}
