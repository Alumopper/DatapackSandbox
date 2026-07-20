package moe.afox.dpsandbox.core.command
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import moe.afox.dpsandbox.core.BlockPos
import moe.afox.dpsandbox.core.ChunkPos
import moe.afox.dpsandbox.core.CommandToken
import moe.afox.dpsandbox.core.CommandTokenizer
import moe.afox.dpsandbox.core.DataTargetSpec
import moe.afox.dpsandbox.core.DatapackSandbox
import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.EntitySelectors
import moe.afox.dpsandbox.core.ExecutionContext
import moe.afox.dpsandbox.core.ExecutionResult
import moe.afox.dpsandbox.core.JsonPaths
import moe.afox.dpsandbox.core.JsonValues
import moe.afox.dpsandbox.core.PredicateContext
import moe.afox.dpsandbox.core.ResourceLocation
import moe.afox.dpsandbox.core.SandboxBlock
import moe.afox.dpsandbox.core.SandboxEntity
import moe.afox.dpsandbox.core.SandboxException
import moe.afox.dpsandbox.core.SandboxPlayer
import moe.afox.dpsandbox.core.SourceLocation
import moe.afox.dpsandbox.core.SpecialEntitySupport
import moe.afox.dpsandbox.core.TagKey
import moe.afox.dpsandbox.core.WeatherState
import moe.afox.dpsandbox.core.unsupportedFeature
import kotlin.math.floor

internal fun DatapackSandbox.executeExecute(
    command: String,
    tokens: List<CommandToken>,
    location: SourceLocation?,
    context: ExecutionContext,
): Boolean {
    var index = 1
    var contexts = listOf(context)

    while (index < tokens.size) {
        when (tokens[index].text) {
            "run" -> {
                val rest = CommandTokenizer.tailAfter(command, tokens[index])
                return contexts.map { executeOne(rest, location, it) }.any { it }
            }
            "as" -> {
                requireIndex(tokens, index + 1, "execute as <selector>", location)
                contexts =
                    contexts.flatMap { ctx ->
                        EntitySelectors.select(world, tokens[index + 1].text, ctx, location).map {
                            ctx.copy(entity = it)
                        }
                    }
                index += 2
            }
            "at" -> {
                requireIndex(tokens, index + 1, "execute at <selector>", location)
                contexts =
                    contexts.flatMap { ctx ->
                        EntitySelectors.select(world, tokens[index + 1].text, ctx, location).map {
                            ctx.copy(position = it.position, dimension = entityDimension(it), yaw = it.yaw, pitch = it.pitch)
                        }
                    }
                index += 2
            }
            "on" -> {
                requireIndex(tokens, index + 1, "execute on <target|attacker>", location)
                val relation = tokens[index + 1].text
                if (relation !in setOf("target", "attacker")) {
                    unsupportedFeature("Unsupported execute on relation '$relation'", profile.id, location, command)
                }
                contexts =
                    contexts.mapNotNull { ctx ->
                        SpecialEntitySupport.relation(ctx.entity, relation, world)?.let { related ->
                            ctx.copy(entity = related)
                        }
                    }
                index += 2
            }
            "positioned" -> {
                requireIndex(tokens, index + 1, "execute positioned <x> <y> <z>|as <selector>", location)
                if (tokens[index + 1].text == "as") {
                    requireIndex(tokens, index + 2, "execute positioned as <selector>", location)
                    contexts =
                        contexts.flatMap { ctx ->
                            EntitySelectors.select(world, tokens[index + 2].text, ctx, location).map {
                                ctx.copy(position = it.position)
                            }
                        }
                    index += 3
                } else {
                    requireSizeFrom(tokens, index, 4, "execute positioned <x> <y> <z>", location)
                    contexts =
                        contexts.map { ctx ->
                            ctx.copy(position = parsePosition(tokens, index + 1, ctx, location))
                        }
                    index += 4
                }
            }
            "align" -> {
                requireIndex(tokens, index + 1, "execute align <axes>", location)
                val axes = tokens[index + 1].text
                if (axes.isBlank() || axes.any { it !in setOf('x', 'y', 'z') } || axes.toSet().size != axes.length) {
                    throw SandboxException(
                        DiagnosticCode.INPUT_FORMAT,
                        "execute align axes must be a unique combination of x, y, and z",
                        location,
                    )
                }
                contexts =
                    contexts.map { ctx ->
                        var pos = ctx.position
                        if ('x' in axes) pos = pos.copy(x = floor(pos.x))
                        if ('y' in axes) pos = pos.copy(y = floor(pos.y))
                        if ('z' in axes) pos = pos.copy(z = floor(pos.z))
                        ctx.copy(position = pos)
                    }
                index += 2
            }
            "anchored" -> {
                requireIndex(tokens, index + 1, "execute anchored <eyes|feet>", location)
                val anchor = tokens[index + 1].text
                if (anchor !in setOf("eyes", "feet")) {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "execute anchored must be eyes or feet", location)
                }
                contexts = contexts.map { it.copy(anchor = anchor) }
                index += 2
            }
            "facing" -> {
                requireIndex(tokens, index + 1, "execute facing <pos|entity>", location)
                if (tokens[index + 1].text == "entity") {
                    requireSizeFrom(tokens, index, 4, "execute facing entity <target> <eyes|feet>", location)
                    val anchor = tokens[index + 3].text
                    contexts =
                        contexts.flatMap { ctx ->
                            EntitySelectors.select(world, tokens[index + 2].text, ctx, location).map { target ->
                                ctx.facing(facingPosition(target, anchor, location))
                            }
                        }
                } else {
                    requireSizeFrom(tokens, index, 4, "execute facing <x> <y> <z>", location)
                    contexts =
                        contexts.map { ctx ->
                            ctx.facing(parsePosition(tokens, index + 1, ctx, location))
                        }
                }
                index += 4
            }
            "in" -> {
                requireIndex(tokens, index + 1, "execute in <dimension>", location)
                contexts = contexts.map { ctx -> ctx.copy(dimension = ResourceLocation.parse(tokens[index + 1].text)) }
                index += 2
            }
            "rotated" -> {
                requireIndex(tokens, index + 1, "execute rotated <yaw pitch>|as <target>", location)
                if (tokens[index + 1].text == "as") {
                    requireIndex(tokens, index + 2, "execute rotated as <target>", location)
                    contexts =
                        contexts.flatMap { ctx ->
                            EntitySelectors.select(world, tokens[index + 2].text, ctx, location).map {
                                ctx.copy(yaw = it.yaw, pitch = it.pitch)
                            }
                        }
                } else {
                    requireSizeFrom(tokens, index, 3, "execute rotated <yaw> <pitch>", location)
                    contexts =
                        contexts.map { ctx ->
                            ctx.copy(
                                yaw = parseRotation(tokens[index + 1].text, ctx.yaw, "yaw", location),
                                pitch = parseRotation(tokens[index + 2].text, ctx.pitch, "pitch", location),
                            )
                        }
                }
                index += 3
            }
            "store" -> {
                val runIndex = tokens.indexOfFirst { it.text == "run" }
                if (runIndex < 0) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "execute store requires run", location)
                val commandsBefore = commandsExecuted
                val outputsBefore = world.outputs.size
                val rest = CommandTokenizer.tailAfter(command, tokens[runIndex])
                lastFunctionReturnValue = null
                val commandSuccess = contexts.map { executeOne(rest, location, it) }.any { it }
                val commandsRun = (commandsExecuted - commandsBefore).coerceAtLeast(0)
                val stored =
                    executeStoreValue(
                        tokens.getOrNull(index + 1)?.text ?: "result",
                        commandsRun,
                        outputsBefore,
                        lastFunctionReturnValue,
                        commandSuccess,
                    )
                storeExecuteValue(tokens, index + 1, stored, location, contexts.firstOrNull() ?: context)
                return commandSuccess
            }
            "if", "unless" -> {
                val positive = tokens[index].text == "if"
                val evaluated = contexts.map { it to evaluateCondition(tokens, index + 1, location, it) }
                contexts =
                    evaluated
                        .filter { (_, result) -> result.first == positive }
                        .map { (ctx, _) -> ctx }
                index = evaluated.firstOrNull()?.second?.second ?: nextConditionIndex(tokens, index + 1, location)
            }
            else -> unsupportedFeature("Unsupported execute subcommand '${tokens[index].text}'", profile.id, location, command)
        }
    }
    return contexts.isNotEmpty()
}

internal fun DatapackSandbox.evaluateCondition(
    tokens: List<CommandToken>,
    index: Int,
    location: SourceLocation?,
    context: ExecutionContext,
): Pair<Boolean, Int> {
    requireIndex(tokens, index, "execute if|unless <condition>", location)
    return when (tokens[index].text) {
        "entity" -> {
            requireIndex(tokens, index + 1, "execute if entity <selector>", location)
            EntitySelectors.select(world, tokens[index + 1].text, context, location).isNotEmpty() to index + 2
        }
        "score" -> evaluateScoreCondition(tokens, index, location) to nextScoreConditionIndex(tokens, index)
        "data" -> evaluateDataCondition(tokens, index, location, context)
        "block" -> {
            requireSizeFrom(tokens, index, 5, "execute if|unless block <pos> <block>", location)
            val pos = parseBlockPos(tokens, index + 1, context, location)
            (matchesBlock(pos, tokens[index + 4].text, location) to index + 5)
        }
        "blocks" -> evaluateBlocksCondition(tokens, index, location, context)
        "predicate" -> {
            requireIndex(tokens, index + 1, "execute if|unless predicate <id>", location)
            predicates.test(ResourceLocation.parse(tokens[index + 1].text), executePredicateContext(context)) to index + 2
        }
        "function" -> {
            requireIndex(tokens, index + 1, "execute if|unless function <id|#tag>", location)
            evaluateFunctionCondition(tokens[index + 1].text, context, location) to index + 2
        }
        "dimension" -> {
            requireIndex(tokens, index + 1, "execute if|unless dimension <id>", location)
            (context.dimension == ResourceLocation.parse(tokens[index + 1].text)) to index + 2
        }
        "biome" -> {
            requireSizeFrom(tokens, index, 5, "execute if|unless biome <pos> <biome>", location)
            val pos = parseBlockPos(tokens, index + 1, context, location)
            (world.biomes[pos] == ResourceLocation.parse(tokens[index + 4].text)) to index + 5
        }
        "loaded" -> {
            requireSizeFrom(tokens, index, 4, "execute if|unless loaded <pos>", location)
            val pos = parseBlockPos(tokens, index + 1, context, location)
            (ChunkPos(Math.floorDiv(pos.x, 16), Math.floorDiv(pos.z, 16)) in world.forcedChunks) to index + 4
        }
        else -> unsupportedFeature("Unsupported execute condition '${tokens[index].text}'", profile.id, location)
    }
}

internal fun DatapackSandbox.nextConditionIndex(
    tokens: List<CommandToken>,
    index: Int,
    location: SourceLocation?,
): Int {
    requireIndex(tokens, index, "execute if|unless <condition>", location)
    return when (tokens[index].text) {
        "entity", "predicate", "function", "dimension" -> {
            requireIndex(tokens, index + 1, "execute if|unless ${tokens[index].text} <argument>", location)
            index + 2
        }
        "score" -> nextScoreConditionIndex(tokens, index)
        "data" -> {
            val (_, pathIndex) = parseDataTarget(tokens, index + 1, ExecutionContext(), location)
            requireIndex(tokens, pathIndex, "execute if data <target> <path>", location)
            pathIndex + 1
        }
        "block", "biome" -> {
            requireSizeFrom(tokens, index, 5, "execute if|unless ${tokens[index].text} <pos> <id>", location)
            index + 5
        }
        "blocks" -> {
            requireSizeFrom(tokens, index, 11, "execute if|unless blocks <begin> <end> <destination> <all|masked>", location)
            index + 11
        }
        "loaded" -> {
            requireSizeFrom(tokens, index, 4, "execute if|unless loaded <pos>", location)
            index + 4
        }
        else -> unsupportedFeature("Unsupported execute condition '${tokens[index].text}'", profile.id, location)
    }
}

internal fun DatapackSandbox.evaluateFunctionCondition(
    raw: String,
    context: ExecutionContext,
    location: SourceLocation?,
): Boolean {
    val ids =
        if (raw.startsWith("#")) {
            resolveFunctionTag(ResourceLocation.parse(raw.removePrefix("#")), location)
        } else {
            listOf(ResourceLocation.parse(raw))
        }
    if (ids.isEmpty()) return false
    return ids.map { runFunction(it, context) }.any { functionConditionPassed(it) }
}

internal fun DatapackSandbox.functionConditionPassed(result: ExecutionResult): Boolean =
    result.returnValue?.let { it > 0 } ?: (result.commandsExecuted > 0)

internal fun DatapackSandbox.resolveFunctionTag(
    id: ResourceLocation,
    location: SourceLocation?,
    visited: MutableSet<ResourceLocation> = linkedSetOf(),
): List<ResourceLocation> {
    if (!visited.add(id)) {
        throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Function tag '#$id' contains a cycle", location)
    }
    val definition =
        datapack.tags[TagKey("function", id)]
            ?: datapack.tags[TagKey("functions", id)]
            ?: throw SandboxException(DiagnosticCode.RESOURCE_NOT_FOUND, "Function tag '#$id' was not found", location)
    val functions =
        definition.values.flatMap { value ->
            if (value.id.startsWith("#")) {
                try {
                    resolveFunctionTag(ResourceLocation.parse(value.id.removePrefix("#")), location, visited)
                } catch (error: SandboxException) {
                    if (error.code == DiagnosticCode.RESOURCE_NOT_FOUND && !value.required) emptyList() else throw error
                }
            } else {
                val functionId = ResourceLocation.parse(value.id)
                if (datapack.functions.containsKey(functionId)) {
                    listOf(functionId)
                } else if (!value.required) {
                    emptyList()
                } else {
                    throw SandboxException(
                        DiagnosticCode.RESOURCE_NOT_FOUND,
                        "Function '$functionId' in tag '#$id' was not found",
                        location,
                    )
                }
            }
        }
    visited.remove(id)
    return functions
}

internal fun DatapackSandbox.executePredicateContext(context: ExecutionContext): PredicateContext {
    val player = context.entity as? SandboxPlayer
    return PredicateContext(
        world = world,
        origin = context.position,
        dimension = context.dimension,
        thisEntity = context.entity,
        player = player,
        tool = player?.selectedItem,
        weather = currentWeatherState(),
    )
}

internal fun DatapackSandbox.currentWeatherState(): WeatherState =
    WeatherState(raining = world.weather == "rain" || world.weather == "thunder", thundering = world.weather == "thunder")

internal fun DatapackSandbox.entityDimension(entity: SandboxEntity): ResourceLocation = entity.dimension

internal fun DatapackSandbox.evaluateBlocksCondition(
    tokens: List<CommandToken>,
    index: Int,
    location: SourceLocation?,
    context: ExecutionContext,
): Pair<Boolean, Int> {
    requireSizeFrom(tokens, index, 11, "execute if|unless blocks <begin> <end> <destination> <all|masked>", location)
    val from = parseBlockPos(tokens, index + 1, context, location)
    val to = parseBlockPos(tokens, index + 4, context, location)
    val dest = parseBlockPos(tokens, index + 7, context, location)
    val mode = tokens[index + 10].text
    if (mode !in setOf("all", "masked")) {
        unsupportedFeature("Unsupported execute blocks mode '$mode'", profile.id, location)
    }

    val xs = minOf(from.x, to.x)..maxOf(from.x, to.x)
    val ys = minOf(from.y, to.y)..maxOf(from.y, to.y)
    val zs = minOf(from.z, to.z)..maxOf(from.z, to.z)
    val volume = xs.count() * ys.count() * zs.count()
    if (volume > 32768) {
        throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Execute blocks volume $volume exceeds sandbox limit 32768", location)
    }

    for ((dx, x) in xs.withIndex()) {
        for ((dy, y) in ys.withIndex()) {
            for ((dz, z) in zs.withIndex()) {
                val source = world.block(BlockPos(x, y, z))
                if (mode == "masked" && source == null) continue
                val target = world.block(BlockPos(dest.x + dx, dest.y + dy, dest.z + dz))
                if (!sameBlock(source, target)) return false to index + 11
            }
        }
    }
    return true to index + 11
}

internal fun DatapackSandbox.sameBlock(
    left: SandboxBlock?,
    right: SandboxBlock?,
): Boolean {
    if (left == null || left.id == ResourceLocation("minecraft", "air")) {
        return right == null || right.id == ResourceLocation("minecraft", "air")
    }
    if (right == null || right.id == ResourceLocation("minecraft", "air")) return false
    return left.id == right.id && left.properties == right.properties && left.nbt == right.nbt
}

internal fun DatapackSandbox.evaluateScoreCondition(
    tokens: List<CommandToken>,
    index: Int,
    location: SourceLocation?,
): Boolean {
    requireSizeFrom(tokens, index, 5, "execute if score <target> <objective> matches <range>", location)
    val value = world.getScore(tokens[index + 1].text, tokens[index + 2].text)
    return when (val operator = tokens[index + 3].text) {
        "matches" -> scoreMatches(value, tokens[index + 4].text, location)
        "=", "<", "<=", ">", ">=" -> {
            requireIndex(tokens, index + 5, "execute if score comparison requires source target and objective", location)
            val other = world.getScore(tokens[index + 4].text, tokens[index + 5].text)
            when (operator) {
                "=" -> value == other
                "<" -> value < other
                "<=" -> value <= other
                ">" -> value > other
                else -> value >= other
            }
        }
        else -> unsupportedFeature("Unsupported score condition operator '$operator'", profile.id, location)
    }
}

internal fun DatapackSandbox.nextScoreConditionIndex(
    tokens: List<CommandToken>,
    index: Int,
): Int = if (index + 3 < tokens.size && tokens[index + 3].text == "matches") index + 5 else index + 6

internal fun DatapackSandbox.evaluateDataCondition(
    tokens: List<CommandToken>,
    index: Int,
    location: SourceLocation?,
    context: ExecutionContext,
): Pair<Boolean, Int> {
    requireSizeFrom(tokens, index, 4, "execute if data <storage|entity> ...", location)
    val (target, pathIndex) = parseDataTarget(tokens, index + 1, context, location)
    requireIndex(tokens, pathIndex, "execute if data <target> <path>", location)
    return (dataTargetNbtValues(target, location).any { JsonPaths.exists(it, tokens[pathIndex].text) } to pathIndex + 1)
}

internal fun DatapackSandbox.executeData(
    command: String,
    tokens: List<CommandToken>,
    location: SourceLocation?,
    context: ExecutionContext,
) {
    requireSize(tokens, 3, "data <modify|get|remove> ...", location)
    when (tokens[1].text) {
        "modify" -> executeDataModify(command, tokens, location, context)
        "merge" -> executeDataMerge(command, tokens, location, context)
        "get" ->
            executeDataGet(tokens, location, context)?.let {
                world.recordOutput("get", "data", text = JsonValues.render(it), payload = it)
            }
        "remove" -> executeDataRemove(tokens, location, context)
        else -> unsupportedFeature("Unsupported data action '${tokens[1].text}'", profile.id, location)
    }
}

internal fun DatapackSandbox.executeDataModify(
    command: String,
    tokens: List<CommandToken>,
    location: SourceLocation?,
    context: ExecutionContext,
) {
    requireSize(tokens, 6, "data modify <storage|entity|block> <target> <path> <set|merge|append|prepend|insert> ...", location)
    val (target, pathIndex) = parseDataTarget(tokens, 2, context, location)
    requireIndex(tokens, pathIndex + 1, "data modify <target> <path> <operation>", location)
    val path = tokens[pathIndex].text
    val mutation = tokens[pathIndex + 1].text
    val insertIndex =
        if (mutation == "insert") {
            requireIndex(tokens, pathIndex + 3, "data modify ... insert <index> <value|from> ...", location)
            parseInt(tokens[pathIndex + 2].text, "insert index", location)
        } else {
            null
        }
    val sourceKindIndex =
        when (mutation) {
            "set", "merge", "append", "prepend" -> pathIndex + 2
            "insert" -> pathIndex + 3
            else -> unsupportedFeature("Unsupported data modify operation '$mutation'", profile.id, location, command)
        }
    requireIndex(tokens, sourceKindIndex, "data modify ... $mutation <value|from> ...", location)
    val value = readDataModifyValue(command, tokens, sourceKindIndex, context, location)
    val before = dataTargetNbtValues(target, location).map { it.deepCopy() }
    mutateDataTarget(target, location) { targetNbt ->
        when (mutation) {
            "set" -> JsonPaths.set(targetNbt, path, value)
            "merge" -> JsonPaths.merge(targetNbt, path, value)
            "append" -> JsonPaths.append(targetNbt, path, value, location)
            "prepend" -> JsonPaths.prepend(targetNbt, path, value, location)
            "insert" -> JsonPaths.insert(targetNbt, path, insertIndex ?: 0, value, location)
        }
    }
    val after = dataTargetNbtValues(target, location).map { it.deepCopy() }
    recordDataMutationOutput(
        "data modify",
        target,
        value,
        before,
        after,
        JsonObject().also { details ->
            details.addProperty("operation", mutation)
            details.addProperty("path", path)
            insertIndex?.let { details.addProperty("insertIndex", it) }
        },
    )
}

internal fun DatapackSandbox.readDataModifyValue(
    command: String,
    tokens: List<CommandToken>,
    sourceKindIndex: Int,
    context: ExecutionContext,
    location: SourceLocation?,
): JsonElement =
    when (tokens[sourceKindIndex].text) {
        "value" -> {
            requireIndex(tokens, sourceKindIndex + 1, "data modify ... value <value>", location)
            JsonValues.parse(CommandTokenizer.tailFrom(command, tokens[sourceKindIndex + 1]), location)
        }
        "from" -> {
            val (sourceTarget, sourcePathIndex) = parseDataTarget(tokens, sourceKindIndex + 1, context, location)
            val sourcePath = tokens.getOrNull(sourcePathIndex)?.text
            dataTargetNbtValues(sourceTarget, location).firstOrNull()?.let { JsonPaths.get(it, sourcePath)?.deepCopy() }
                ?: throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Data modify source did not resolve", location)
        }
        "string" -> readDataModifyString(tokens, sourceKindIndex, context, location)
        else -> unsupportedFeature("Unsupported data modify source '${tokens[sourceKindIndex].text}'", profile.id, location, command)
    }

internal fun DatapackSandbox.readDataModifyString(
    tokens: List<CommandToken>,
    sourceKindIndex: Int,
    context: ExecutionContext,
    location: SourceLocation?,
): JsonPrimitive {
    val (sourceTarget, sourcePathIndex) = parseDataTarget(tokens, sourceKindIndex + 1, context, location)
    requireIndex(tokens, sourcePathIndex, "data modify ... string <source> <path> [start] [end]", location)
    val source =
        dataTargetNbtValues(sourceTarget, location)
            .firstOrNull()
            ?.let { JsonPaths.get(it, tokens[sourcePathIndex].text) }
            ?: throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Data modify string source did not resolve", location)
    if (!source.isJsonPrimitive) {
        throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Data modify string source must be a primitive value", location)
    }
    val text = source.asJsonPrimitive.asString
    val start = tokens.getOrNull(sourcePathIndex + 1)?.text?.let { parseInt(it, "string start", location) } ?: 0
    val end = tokens.getOrNull(sourcePathIndex + 2)?.text?.let { parseInt(it, "string end", location) } ?: text.length
    return JsonPrimitive(sliceString(text, start, end, location))
}

internal fun DatapackSandbox.sliceString(
    text: String,
    rawStart: Int,
    rawEnd: Int,
    location: SourceLocation?,
): String {
    val start = normalizeStringIndex(rawStart, text.length)
    val end = normalizeStringIndex(rawEnd, text.length)
    if (start > end) {
        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "String slice start $rawStart is after end $rawEnd", location)
    }
    return text.substring(start, end)
}

internal fun DatapackSandbox.normalizeStringIndex(
    index: Int,
    length: Int,
): Int = (if (index < 0) length + index else index).coerceIn(0, length)

internal fun DatapackSandbox.executeDataMerge(
    command: String,
    tokens: List<CommandToken>,
    location: SourceLocation?,
    context: ExecutionContext,
) {
    requireSize(tokens, 4, "data merge <storage|entity|block> <target> <nbt>", location)
    val (target, valueIndex) = parseDataTarget(tokens, 2, context, location)
    requireIndex(tokens, valueIndex, "data merge <target> <nbt>", location)
    val value = JsonValues.parse(CommandTokenizer.tailFrom(command, tokens[valueIndex]), location)
    val before = dataTargetNbtValues(target, location).map { it.deepCopy() }
    mutateDataTarget(target, location) { JsonPaths.merge(it, null, value) }
    val after = dataTargetNbtValues(target, location).map { it.deepCopy() }
    recordDataMutationOutput("data merge", target, value, before, after)
}

internal fun DatapackSandbox.executeDataGet(
    tokens: List<CommandToken>,
    location: SourceLocation?,
    context: ExecutionContext,
): JsonElement? {
    requireSize(tokens, 3, "data get <storage|entity|block> <target> [path] [scale]", location)
    val (target, pathIndex) = parseDataTarget(tokens, 2, context, location)
    val path = tokens.getOrNull(pathIndex)?.text
    val value = dataTargetNbtValues(target, location).firstOrNull()?.let { JsonPaths.get(it, path) }
    val scale = if (path != null) tokens.getOrNull(pathIndex + 1)?.text?.let { parseDouble(it, "data get scale", location) } else null
    return if (scale == null) value else value?.let { scaleDataGetResult(it, scale, location) }
}

internal fun DatapackSandbox.scaleDataGetResult(
    value: JsonElement,
    scale: Double,
    location: SourceLocation?,
): JsonPrimitive {
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) {
        throw SandboxException(DiagnosticCode.COMMAND_ERROR, "data get scale requires a numeric value", location)
    }
    return JsonPrimitive(value.asDouble * scale)
}

internal fun DatapackSandbox.executeDataRemove(
    tokens: List<CommandToken>,
    location: SourceLocation?,
    context: ExecutionContext,
) {
    requireSize(tokens, 4, "data remove <storage|entity|block> <target> <path>", location)
    val (target, pathIndex) = parseDataTarget(tokens, 2, context, location)
    requireIndex(tokens, pathIndex, "data remove <target> <path>", location)
    val path = tokens[pathIndex].text
    val before = dataTargetNbtValues(target, location).map { it.deepCopy() }
    mutateDataTarget(target, location) { JsonPaths.remove(it, path) }
    val after = dataTargetNbtValues(target, location).map { it.deepCopy() }
    recordDataMutationOutput(
        "data remove",
        target,
        null,
        before,
        after,
        JsonObject().also { details ->
            details.addProperty("operation", "remove")
            details.addProperty("path", path)
        },
    )
}

internal fun DatapackSandbox.parseDataTarget(
    tokens: List<CommandToken>,
    index: Int,
    context: ExecutionContext,
    location: SourceLocation?,
): Pair<DataTargetSpec, Int> {
    requireIndex(tokens, index, "data target", location)
    return when (tokens[index].text) {
        "storage" -> {
            requireIndex(tokens, index + 1, "data storage <id>", location)
            DataTargetSpec.Storage(ResourceLocation.parse(tokens[index + 1].text)) to index + 2
        }
        "entity" -> {
            requireIndex(tokens, index + 1, "data entity <target>", location)
            val selected = EntitySelectors.select(world, tokens[index + 1].text, context, location)
            DataTargetSpec.Entities(selected) to index + 2
        }
        "block" -> {
            requireSizeFrom(tokens, index, 4, "data block <x> <y> <z>", location)
            DataTargetSpec.Block(parseBlockPos(tokens, index + 1, context, location)) to index + 4
        }
        else -> unsupportedFeature("Unsupported data target '${tokens[index].text}'", profile.id, location)
    }
}

internal fun DatapackSandbox.dataTargetNbtValues(
    target: DataTargetSpec,
    location: SourceLocation?,
): List<JsonObject> =
    when (target) {
        is DataTargetSpec.Storage -> listOf(world.storage(target.id))
        is DataTargetSpec.Entities -> target.entities.map { it.fullNbt(profile, location) }
        is DataTargetSpec.Block -> listOf(world.requireBlock(target.pos).fullNbt(target.pos, profile, location))
    }

internal fun DatapackSandbox.recordDataMutationOutput(
    command: String,
    target: DataTargetSpec,
    value: JsonElement?,
    before: List<JsonObject>,
    after: List<JsonObject>,
    details: JsonObject? = null,
) {
    val targetNames = dataTargetNames(target)
    val changed = before.zip(after).count { (beforeValue, afterValue) -> beforeValue != afterValue }
    world.recordOutput(
        command,
        "data",
        targets = targetNames,
        text = changed.toString(),
        payload =
            JsonObject().also { payload ->
                payload.addProperty("targetKind", dataTargetKind(target))
                payload.addProperty("count", after.size)
                payload.addProperty("changed", changed)
                payload.add("targets", JsonArray().also { array -> targetNames.forEach { array.add(it) } })
                value?.let { payload.add("value", it.deepCopy()) }
                details?.let { payload.add("details", it) }
                payload.add(
                    "results",
                    JsonArray().also { results ->
                        after.forEachIndexed { index, afterValue ->
                            val beforeValue = before.getOrNull(index)
                            results.add(
                                JsonObject().also { entry ->
                                    entry.addProperty("target", targetNames.getOrElse(index) { index.toString() })
                                    beforeValue?.let { entry.add("before", it.deepCopy()) }
                                    entry.add("after", afterValue.deepCopy())
                                    entry.addProperty("changed", beforeValue != afterValue)
                                },
                            )
                        }
                    },
                )
            },
    )
}

internal fun DatapackSandbox.dataTargetKind(target: DataTargetSpec): String =
    when (target) {
        is DataTargetSpec.Storage -> "storage"
        is DataTargetSpec.Entities -> "entity"
        is DataTargetSpec.Block -> "block"
    }

internal fun DatapackSandbox.dataTargetNames(target: DataTargetSpec): List<String> =
    when (target) {
        is DataTargetSpec.Storage -> listOf(target.id.toString())
        is DataTargetSpec.Entities -> target.entities.map { it.scoreHolder }
        is DataTargetSpec.Block -> listOf(target.pos.toString())
    }

internal fun DatapackSandbox.mutateDataTarget(
    target: DataTargetSpec,
    location: SourceLocation?,
    mutation: (JsonObject) -> Unit,
) {
    when (target) {
        is DataTargetSpec.Storage -> mutation(world.storage(target.id))
        is DataTargetSpec.Block -> {
            val block = world.requireBlock(target.pos)
            val updated = block.fullNbt(target.pos, profile, location)
            mutation(updated)
            block.writeFullNbt(target.pos, profile, updated, location)
        }
        is DataTargetSpec.Entities ->
            target.entities.forEach { entity ->
                if (entity is SandboxPlayer) {
                    throw SandboxException(
                        DiagnosticCode.COMMAND_ERROR,
                        "Player NBT is read-only in this sandbox; use player events or movement commands",
                        location,
                    )
                }
                val updated = entity.fullNbt(profile, location)
                mutation(updated)
                entity.writeFullNbt(profile, updated, location)
            }
    }
}
