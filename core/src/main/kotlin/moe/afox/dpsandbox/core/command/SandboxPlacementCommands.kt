package moe.afox.dpsandbox.core.command
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import moe.afox.dpsandbox.core.BlockArgument
import moe.afox.dpsandbox.core.BlockPos
import moe.afox.dpsandbox.core.CommandToken
import moe.afox.dpsandbox.core.Datapack
import moe.afox.dpsandbox.core.DatapackSandbox
import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.ExecutionContext
import moe.afox.dpsandbox.core.JsonPaths
import moe.afox.dpsandbox.core.Position
import moe.afox.dpsandbox.core.ResourceLocation
import moe.afox.dpsandbox.core.SandboxBlock
import moe.afox.dpsandbox.core.SandboxEntity
import moe.afox.dpsandbox.core.SandboxException
import moe.afox.dpsandbox.core.SandboxWorld
import moe.afox.dpsandbox.core.SourceLocation
import moe.afox.dpsandbox.core.TagKey
import moe.afox.dpsandbox.core.VersionProfile
import moe.afox.dpsandbox.core.copyForClone
import moe.afox.dpsandbox.core.unsupportedFeature
import kotlin.math.abs
import kotlin.math.floor

private data class SandboxStructurePlacement(
    val rotation: String = "none",
    val mirror: String = "none",
    val integrity: Double = 1.0,
    val seed: Long? = null,
) {
    fun transform(offset: BlockPos): BlockPos {
        val mirrored = mirror(offset.x, offset.z)
        val rotated = rotate(mirrored.first, mirrored.second)
        return BlockPos(rotated.first, offset.y, rotated.second)
    }

    fun transform(offset: Position): Position {
        val mirrored = mirror(offset.x, offset.z)
        val rotated = rotate(mirrored.first, mirrored.second)
        return Position(rotated.first, offset.y, rotated.second)
    }

    fun shouldPlace(
        id: ResourceLocation,
        origin: BlockPos,
        index: Int,
    ): Boolean {
        if (integrity >= 1.0) return true
        if (integrity <= 0.0) return false
        val raw = "$id|$origin|$index|${seed ?: 0L}".hashCode().toLong()
        val normalized = (raw - Int.MIN_VALUE.toLong()).toDouble() / 4_294_967_296.0
        return normalized < integrity
    }

    private fun mirror(
        x: Int,
        z: Int,
    ): Pair<Int, Int> =
        when (mirror) {
            "front_back" -> x to -z
            "left_right" -> -x to z
            else -> x to z
        }

    private fun mirror(
        x: Double,
        z: Double,
    ): Pair<Double, Double> =
        when (mirror) {
            "front_back" -> x to -z
            "left_right" -> -x to z
            else -> x to z
        }

    private fun rotate(
        x: Int,
        z: Int,
    ): Pair<Int, Int> =
        when (rotation) {
            "clockwise_90" -> -z to x
            "clockwise_180" -> -x to -z
            "counterclockwise_90" -> z to -x
            else -> x to z
        }

    private fun rotate(
        x: Double,
        z: Double,
    ): Pair<Double, Double> =
        when (rotation) {
            "clockwise_90" -> -z to x
            "clockwise_180" -> -x to -z
            "counterclockwise_90" -> z to -x
            else -> x to z
        }

    companion object {
        val rotations = setOf("none", "clockwise_90", "clockwise_180", "counterclockwise_90")
        val mirrors = setOf("none", "front_back", "left_right")
    }
}

private data class SandboxFeaturePlacement(
    val kind: String,
    val root: JsonObject,
    val format: String,
)

private data class SandboxFeaturePlacementPlan(
    val type: String,
    val blocks: List<SandboxFeatureBlockPlacement>,
)

private data class SandboxFeatureBlockPlacement(
    val offset: BlockPos,
    val block: BlockArgument,
    val replaceBlocks: Set<ResourceLocation>? = null,
)

private data class SandboxStructureBlockPlan(
    val format: String,
    val blocks: List<SandboxStructureBlockPlacement>,
)

private data class SandboxStructureBlockPlacement(
    val offset: BlockPos,
    val block: BlockArgument,
)

private data class SandboxStructureProcessorList(
    val ids: List<ResourceLocation>,
    val processors: List<SandboxStructureProcessor>,
) {
    val unsupportedCount: Int = processors.count { !it.supported }

    fun plus(other: SandboxStructureProcessorList): SandboxStructureProcessorList =
        SandboxStructureProcessorList(ids = ids + other.ids, processors = processors + other.processors)

    companion object {
        val empty = SandboxStructureProcessorList(emptyList(), emptyList())
    }
}

private data class SandboxStructureProcessor(
    val type: String,
    val ignoredBlocks: Set<ResourceLocation> = emptySet(),
    val protectedBlocks: Set<ResourceLocation> = emptySet(),
    val rules: List<SandboxStructureProcessorRule> = emptyList(),
    val jigsawReplacement: Boolean = false,
    val cappedDelegate: SandboxStructureProcessor? = null,
    val cappedLimit: Int? = null,
    val supported: Boolean = true,
) {
    private var cappedProcessed: Int = 0

    fun apply(
        block: BlockArgument,
        destinationId: ResourceLocation?,
    ): SandboxProcessedStructureBlock {
        val delegate = cappedDelegate
        val limit = cappedLimit
        if (delegate != null && limit != null) {
            if (cappedProcessed >= limit) return SandboxProcessedStructureBlock(block)
            val result = delegate.apply(block, destinationId)
            if (result.processed) cappedProcessed++
            return result
        }
        if (destinationId != null && destinationId in protectedBlocks) {
            return SandboxProcessedStructureBlock(block = null, processed = true)
        }
        if (block.id in ignoredBlocks) return SandboxProcessedStructureBlock(block = null, processed = true)
        val rule = rules.firstOrNull { it.matches(block) } ?: return SandboxProcessedStructureBlock(block)
        return SandboxProcessedStructureBlock(block = rule.output, processed = rule.output != block)
    }
}

private data class SandboxStructureProcessorRule(
    val inputBlocks: Set<ResourceLocation>?,
    val output: BlockArgument,
) {
    fun matches(block: BlockArgument): Boolean = inputBlocks?.contains(block.id) ?: true
}

private data class SandboxProcessedStructureBlock(
    val block: BlockArgument?,
    val processed: Boolean = false,
)

private data class SandboxTemplatePoolElement(
    val type: String,
    val structure: ResourceLocation? = null,
    val feature: ResourceLocation? = null,
    val processors: SandboxStructureProcessorList = SandboxStructureProcessorList.empty,
)

private data class SandboxSelectedTemplatePoolElement(
    val pool: ResourceLocation,
    val element: SandboxTemplatePoolElement,
    val fallbackFrom: ResourceLocation? = null,
)

private data class SandboxJigsawConnector(
    val sourceStructure: ResourceLocation,
    val sourceOffset: BlockPos,
    val position: BlockPos,
    val direction: BlockPos,
    val pool: ResourceLocation,
    val name: ResourceLocation?,
    val target: ResourceLocation?,
)

private data class SandboxJigsawExpansionResult(
    val targets: List<String>,
    val connections: List<JsonObject>,
    val childChangedBlocks: Int,
    val pieces: Int,
)

internal class SandboxPlacementCommands(
    private val sandbox: DatapackSandbox,
) {
    private val world: SandboxWorld get() = sandbox.world
    private val datapack: Datapack get() = sandbox.datapack
    private val profile: VersionProfile get() = sandbox.profile

    private fun requireSize(tokens: List<CommandToken>, size: Int, usage: String, location: SourceLocation?) =
        sandbox.requireSize(tokens, size, usage, location)

    private fun parseInt(raw: String, label: String, location: SourceLocation?): Int = sandbox.parseInt(raw, label, location)

    private fun parseDouble(raw: String, label: String, location: SourceLocation?): Double = sandbox.parseDouble(raw, label, location)

    private fun parseLong(raw: String, label: String, location: SourceLocation?): Long = sandbox.parseLong(raw, label, location)

    private fun isCoordinateTriple(tokens: List<CommandToken>, index: Int): Boolean = sandbox.isCoordinateTriple(tokens, index)

    private fun parsePosition(
        tokens: List<CommandToken>,
        index: Int,
        context: ExecutionContext,
        location: SourceLocation?,
    ): Position = sandbox.parsePosition(tokens, index, context, location)

    private fun parseBlockPos(
        tokens: List<CommandToken>,
        index: Int,
        context: ExecutionContext,
        location: SourceLocation?,
    ): BlockPos = sandbox.parseBlockPos(tokens, index, context, location)

    private fun parseBlockArgument(raw: String, location: SourceLocation?): BlockArgument = sandbox.parseBlockArgument(raw, location)

    private fun positionOutput(position: Position): JsonObject = sandbox.positionOutput(position)

    private fun blockPosOutput(pos: BlockPos): JsonObject = sandbox.blockPosOutput(pos)

    private fun blockPosArrayOutput(positions: List<BlockPos>): JsonArray = sandbox.blockPosArrayOutput(positions)

    private fun blockArgumentOutput(block: BlockArgument): JsonObject = sandbox.blockArgumentOutput(block)

    private fun sameBlock(left: SandboxBlock?, right: SandboxBlock?): Boolean = sandbox.sameBlock(left, right)

    private fun extractTags(nbt: JsonObject): Set<String> = sandbox.extractTags(nbt)

    fun execute(
        tokens: List<CommandToken>,
        location: SourceLocation?,
        context: ExecutionContext,
    ) {
        requireSize(tokens, 3, "place <feature|jigsaw|structure|template> ...", location)
        val kind = tokens[1].text
        val payload = JsonObject()
        payload.addProperty("kind", kind)
        var placedTargets = emptyList<String>()

        var placeId: ResourceLocation? = null
        val positionIndex =
            when (kind) {
                "feature", "structure", "template" -> {
                    placeId = ResourceLocation.parse(tokens[2].text)
                    payload.addProperty("id", placeId.toString())
                    3
                }
                "jigsaw" -> {
                    requireSize(tokens, 5, "place jigsaw <pool> <target> <maxDepth> [pos]", location)
                    payload.addProperty("pool", ResourceLocation.parse(tokens[2].text).toString())
                    payload.addProperty("target", ResourceLocation.parse(tokens[3].text).toString())
                    payload.addProperty("maxDepth", parseInt(tokens[4].text, "jigsaw max depth", location))
                    5
                }
                else -> unsupportedFeature("Unsupported place kind '$kind'", profile.id, location)
            }

        val position: Position
        val extraStart =
            if (isCoordinateTriple(tokens, positionIndex)) {
                position = parsePosition(tokens, positionIndex, context, location)
                payload.add("position", positionOutput(position))
                positionIndex + 3
            } else {
                position = context.position
                payload.add("position", positionOutput(position))
                positionIndex
            }
        val extras = tokens.drop(extraStart).map { it.text }
        if (extras.isNotEmpty()) {
            payload.add(
                "extra",
                JsonArray().also { extra ->
                    extras.forEach { extra.add(it) }
                },
            )
        }
        when (kind) {
            "feature" -> {
                placedTargets = placeSandboxFeature(placeId ?: error("missing place id"), position, payload, location)
            }
            "jigsaw" -> {
                placedTargets =
                    placeSandboxJigsaw(
                        pool = ResourceLocation.parse(tokens[2].text),
                        target = ResourceLocation.parse(tokens[3].text),
                        maxDepth = parseInt(tokens[4].text, "jigsaw max depth", location),
                        position = position,
                        context = context,
                        payload = payload,
                        location = location,
                    )
            }
            "structure", "template" -> {
                val placement = if (kind == "template") parseTemplatePlacement(extras, payload, location) else SandboxStructurePlacement()
                placedTargets = placeSandboxStructure(placeId ?: error("missing place id"), position, context, payload, placement, location)
            }
            else -> {
                payload.addProperty("placed", false)
                payload.addProperty("reason", "Sandbox records place commands but does not simulate this worldgen kind")
            }
        }

        val idText = payload.get("id")?.asString ?: payload.get("pool")?.asString.orEmpty()
        world.recordOutput("place $kind", "worldgen", targets = placedTargets, text = "$kind:$idText", payload = payload)
    }

    private fun placeSandboxJigsaw(
        pool: ResourceLocation,
        target: ResourceLocation,
        maxDepth: Int,
        position: Position,
        context: ExecutionContext,
        payload: JsonObject,
        location: SourceLocation?,
    ): List<String> {
        val resource = datapack.rawResources["worldgen/template_pool"]?.get(pool)
        if (resource == null) {
            payload.addProperty("placed", false)
            payload.addProperty("reason", "Sandbox records place jigsaw but no template pool resource was loaded")
            return emptyList()
        }
        if (!resource.root.isJsonObject) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Template pool resource '$pool' must be a JSON object", location)
        }
        val selected = selectTemplatePoolElement(pool, resource.root.asJsonObject, "template pool $pool", location)
        if (selected == null) {
            payload.addProperty("placed", false)
            payload.addProperty("format", "raw-json-index-only")
            payload.addProperty("reason", "Loaded template pool has no supported single/legacy structure or feature element")
            return emptyList()
        }
        val element = selected.element
        payload.addProperty("selectedPool", selected.pool.toString())
        selected.fallbackFrom?.let {
            payload.addProperty("fallbackPool", selected.pool.toString())
            payload.addProperty("usedFallback", true)
            payload.addProperty("fallbackFrom", it.toString())
        } ?: payload.addProperty("usedFallback", false)
        payload.addProperty("elementType", element.type)
        element.structure?.let { payload.addProperty("structure", it.toString()) }
        element.feature?.let { payload.addProperty("feature", it.toString()) }
        val targets =
            when {
                element.structure != null ->
                    placeSandboxStructure(
                        id = element.structure,
                        position = position,
                        context = context,
                        payload = payload,
                        placement = SandboxStructurePlacement(),
                        location = location,
                        extraProcessors = element.processors,
                    )
                element.feature != null -> placeSandboxFeature(element.feature, position, payload, location)
                else -> emptyList()
            }
        if (element.structure != null && maxDepth > 1) {
            val origin = BlockPos(floor(position.x).toInt(), floor(position.y).toInt(), floor(position.z).toInt())
            val expansion =
                expandJigsawConnections(
                    structure = element.structure,
                    origin = origin,
                    commandTarget = target,
                    currentDepth = 1,
                    maxDepth = maxDepth.coerceAtMost(16),
                    context = context,
                    location = location,
                )
            if (expansion.connections.isNotEmpty()) {
                payload.add(
                    "jigsawConnections",
                    JsonArray().also { array -> expansion.connections.forEach(array::add) },
                )
            }
            payload.addProperty("jigsawPieces", expansion.pieces + 1)
            payload.addProperty("jigsawChildChangedBlocks", expansion.childChangedBlocks)
            payload.addProperty(
                "totalChangedBlocks",
                payload.get("changedBlocks")?.asInt?.plus(expansion.childChangedBlocks) ?: expansion.childChangedBlocks,
            )
            payload.addProperty("effectiveMaxDepth", maxDepth.coerceAtMost(16))
            payload.addProperty("requestedMaxDepth", maxDepth)
            payload.addProperty("connectedTargets", expansion.targets.size)
            payload.add(
                "connectionTargets",
                JsonArray().also { array -> expansion.targets.forEach(array::add) },
            )
            payload.addProperty("format", "sandbox-template-pool")
            return targets + expansion.targets
        }
        payload.addProperty("format", "sandbox-template-pool")
        return targets
    }

    private fun expandJigsawConnections(
        structure: ResourceLocation,
        origin: BlockPos,
        commandTarget: ResourceLocation,
        currentDepth: Int,
        maxDepth: Int,
        context: ExecutionContext,
        location: SourceLocation?,
    ): SandboxJigsawExpansionResult {
        if (currentDepth >= maxDepth) return SandboxJigsawExpansionResult(emptyList(), emptyList(), 0, 0)
        val connectors = structureJigsawConnectors(structure, origin, location)
        if (connectors.isEmpty()) return SandboxJigsawExpansionResult(emptyList(), emptyList(), 0, 0)
        val matching = connectors.filter { it.target == commandTarget || it.name == commandTarget }
        val selectedConnectors = matching.ifEmpty { connectors }

        val targets = mutableListOf<String>()
        val connections = mutableListOf<JsonObject>()
        var childChangedBlocks = 0
        var pieces = 0
        selectedConnectors.take(16).forEach { connector ->
            val resource = datapack.rawResources["worldgen/template_pool"]?.get(connector.pool) ?: return@forEach
            if (!resource.root.isJsonObject) {
                throw SandboxException(
                    DiagnosticCode.INPUT_FORMAT,
                    "Template pool resource '${connector.pool}' must be a JSON object",
                    location,
                )
            }
            val selected =
                selectTemplatePoolElement(connector.pool, resource.root.asJsonObject, "template pool ${connector.pool}", location)
                    ?: return@forEach
            val childPayload = JsonObject()
            val childOrigin = connector.position
            val childTargets =
                when {
                    selected.element.structure != null ->
                        placeSandboxStructure(
                            id = selected.element.structure,
                            position = Position(childOrigin.x.toDouble(), childOrigin.y.toDouble(), childOrigin.z.toDouble()),
                            context = context,
                            payload = childPayload,
                            placement = SandboxStructurePlacement(),
                            location = location,
                            extraProcessors = selected.element.processors,
                        )
                    selected.element.feature != null ->
                        placeSandboxFeature(
                            selected.element.feature,
                            Position(childOrigin.x.toDouble(), childOrigin.y.toDouble(), childOrigin.z.toDouble()),
                            childPayload,
                            location,
                        )
                    else -> emptyList()
                }
            val changed = childPayload.get("changedBlocks")?.asInt ?: 0
            childChangedBlocks += changed
            targets += childTargets
            pieces++

            val connection =
                JsonObject().also { root ->
                    root.addProperty("depth", currentDepth + 1)
                    root.addProperty("sourceStructure", connector.sourceStructure.toString())
                    root.add("sourceOffset", blockPosOutput(connector.sourceOffset))
                    root.add("position", blockPosOutput(connector.position))
                    root.add("direction", blockPosOutput(connector.direction))
                    root.addProperty("pool", connector.pool.toString())
                    connector.name?.let { root.addProperty("name", it.toString()) }
                    connector.target?.let { root.addProperty("target", it.toString()) }
                    root.addProperty("selectedPool", selected.pool.toString())
                    selected.fallbackFrom?.let {
                        root.addProperty("usedFallback", true)
                        root.addProperty("fallbackFrom", it.toString())
                        root.addProperty("fallbackPool", selected.pool.toString())
                    } ?: root.addProperty("usedFallback", false)
                    root.addProperty("elementType", selected.element.type)
                    selected.element.structure?.let { root.addProperty("structure", it.toString()) }
                    selected.element.feature?.let { root.addProperty("feature", it.toString()) }
                    childPayload.get("featureType")?.let { root.add("featureType", it.deepCopy()) }
                    root.addProperty("changedBlocks", changed)
                    root.addProperty("targets", childTargets.size)
                }
            connections += connection

            selected.element.structure?.let { childStructure ->
                val nested =
                    expandJigsawConnections(
                        structure = childStructure,
                        origin = childOrigin,
                        commandTarget = commandTarget,
                        currentDepth = currentDepth + 1,
                        maxDepth = maxDepth,
                        context = context,
                        location = location,
                    )
                targets += nested.targets
                connections += nested.connections
                childChangedBlocks += nested.childChangedBlocks
                pieces += nested.pieces
            }
        }
        return SandboxJigsawExpansionResult(targets, connections, childChangedBlocks, pieces)
    }

    private fun structureJigsawConnectors(
        structure: ResourceLocation,
        origin: BlockPos,
        location: SourceLocation?,
    ): List<SandboxJigsawConnector> {
        val resource = datapack.rawResources["worldgen/structure"]?.get(structure) ?: return emptyList()
        if (!resource.root.isJsonObject) return emptyList()
        val plan = resource.root.asJsonObject.parseStructureBlockPlan(location) ?: return emptyList()
        return plan.blocks.mapNotNull { placement ->
            val block = placement.block
            if (block.id != ResourceLocation("minecraft", "jigsaw")) return@mapNotNull null
            val pool = block.nbt.jigsawResource("pool", "Pool") ?: return@mapNotNull null
            if (pool == ResourceLocation("minecraft", "empty")) return@mapNotNull null
            val offset = placement.offset
            val direction = block.jigsawDirection()
            SandboxJigsawConnector(
                sourceStructure = structure,
                sourceOffset = offset,
                position =
                    BlockPos(
                        origin.x + offset.x + direction.x,
                        origin.y + offset.y + direction.y,
                        origin.z + offset.z + direction.z,
                    ),
                direction = direction,
                pool = pool,
                name = block.nbt.jigsawResource("name", "Name"),
                target = block.nbt.jigsawResource("target", "Target"),
            )
        }
    }

    private fun JsonObject.jigsawResource(vararg names: String): ResourceLocation? {
        names.forEach { name ->
            val value = get(name)
            if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                return ResourceLocation.parse(value.asString)
            }
        }
        return null
    }

    private fun BlockArgument.jigsawDirection(): BlockPos {
        val raw =
            properties["orientation"]
                ?: properties["facing"]
                ?: nbt.jigsawString("orientation", "facing")
                ?: "east"
        val direction = raw.removePrefix("minecraft:").substringBefore('_')
        return when (direction) {
            "north" -> BlockPos(0, 0, -1)
            "south" -> BlockPos(0, 0, 1)
            "east" -> BlockPos(1, 0, 0)
            "west" -> BlockPos(-1, 0, 0)
            "up" -> BlockPos(0, 1, 0)
            "down" -> BlockPos(0, -1, 0)
            else -> BlockPos(1, 0, 0)
        }
    }

    private fun JsonObject.jigsawString(vararg names: String): String? {
        names.forEach { name ->
            val value = get(name)
            if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) return value.asString
        }
        return null
    }

    private fun selectTemplatePoolElement(
        pool: ResourceLocation,
        root: JsonObject,
        label: String,
        location: SourceLocation?,
        visited: Set<ResourceLocation> = emptySet(),
    ): SandboxSelectedTemplatePoolElement? {
        val selected = root.selectTemplatePoolElementInCurrentPool(pool, label, location)
        if (selected != null) return selected
        val fallbackText = root.placeString("fallback", location)
        if (fallbackText.isNullOrBlank() || fallbackText == "minecraft:empty") return null
        val fallback = ResourceLocation.parse(fallbackText)
        if (fallback in visited + pool) {
            throw SandboxException(DiagnosticCode.COMMAND_ERROR, "Template pool fallback cycle at '$fallback'", location)
        }
        val resource = datapack.rawResources["worldgen/template_pool"]?.get(fallback) ?: return null
        if (!resource.root.isJsonObject) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Template pool fallback '$fallback' must be a JSON object", location)
        }
        return selectTemplatePoolElement(fallback, resource.root.asJsonObject, "template pool $fallback", location, visited + pool)
            ?.let { it.copy(fallbackFrom = it.fallbackFrom ?: pool) }
    }

    private fun JsonObject.selectTemplatePoolElementInCurrentPool(
        pool: ResourceLocation,
        label: String,
        location: SourceLocation?,
    ): SandboxSelectedTemplatePoolElement? {
        val elements =
            getAsJsonArrayOrNull("elements")
                ?: return parseTemplatePoolElement(this, label, location)?.let { SandboxSelectedTemplatePoolElement(pool, it) }
        elements.forEachIndexed { index, element ->
            parseTemplatePoolElement(element.asPlaceJsonObject("$label element $index", location), "$label element $index", location)
                ?.let { return SandboxSelectedTemplatePoolElement(pool, it) }
        }
        return null
    }

    private fun parseTemplatePoolElement(
        root: JsonObject,
        label: String,
        location: SourceLocation?,
    ): SandboxTemplatePoolElement? {
        val elementRoot =
            root
                .get("element")
                ?.asPlaceJsonObject("$label element", location)
                ?: root
        val type = (
            elementRoot.placeString("element_type", location) ?: elementRoot.placeString("type", location)
                ?: "minecraft:single_pool_element"
        )
        return when (type.removePrefix("minecraft:")) {
            "single_pool_element", "legacy_single_pool_element" -> {
                val structure =
                    elementRoot.placeString("location", location)
                        ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label location is required", location)
                SandboxTemplatePoolElement(
                    type = type,
                    structure = ResourceLocation.parse(structure),
                    processors = parseTemplatePoolProcessors(elementRoot, "$label processors", location),
                )
            }
            "list_pool_element" -> {
                val children = elementRoot.getAsJsonArrayOrNull("elements") ?: return null
                children.forEachIndexed { index, child ->
                    parseTemplatePoolElement(child.asPlaceJsonObject("$label child $index", location), "$label child $index", location)
                        ?.let { return it.copy(type = type) }
                }
                null
            }
            "feature_pool_element" -> {
                val feature = elementRoot.placeString("feature", location)
                feature?.let {
                    SandboxTemplatePoolElement(
                        type = type,
                        feature = ResourceLocation.parse(it),
                        processors = parseTemplatePoolProcessors(elementRoot, "$label processors", location),
                    )
                }
            }
            "empty_pool_element" -> null
            else -> null
        }
    }

    private fun parseTemplatePoolProcessors(
        root: JsonObject,
        label: String,
        location: SourceLocation?,
    ): SandboxStructureProcessorList {
        val value = root.get("processors") ?: return SandboxStructureProcessorList.empty
        return parseStructureProcessorSource(value, label, location)
    }

    private fun placeSandboxFeature(
        id: ResourceLocation,
        position: Position,
        payload: JsonObject,
        location: SourceLocation?,
    ): List<String> {
        val feature = resolvePlaceFeature(id, payload, location)
        if (feature == null) {
            payload.addProperty("placed", false)
            payload.addProperty("reason", "Sandbox records place commands but no placed/configured feature resource was loaded")
            return emptyList()
        }
        val plan = feature.root.parseFeaturePlacementPlan(location)
        if (plan == null || plan.blocks.isEmpty()) {
            payload.addProperty("placed", false)
            payload.addProperty("format", "raw-json-index-only")
            payload.addProperty(
                "reason",
                "Loaded feature resource has no sandbox block or supported simple_block/block_column/disk/vegetation_patch/tree/basalt_columns/delta_feature/lake/spring_feature/block_pile/glowstone_blob/forest_rock/netherrack_replace_blobs/chorus_plant/replace_single_block/replace_blob/random_patch/flower/selector/ore state",
            )
            return emptyList()
        }

        val origin = BlockPos(floor(position.x).toInt(), floor(position.y).toInt(), floor(position.z).toInt())
        val changedPositions = mutableListOf<BlockPos>()
        var skippedBlocks = 0
        plan.blocks.forEach { placement ->
            val pos =
                BlockPos(
                    origin.x + placement.offset.x,
                    origin.y + placement.offset.y,
                    origin.z + placement.offset.z,
                )
            val before = world.block(pos)?.copyForClone()
            val currentId = before?.id ?: ResourceLocation("minecraft", "air")
            if (placement.replaceBlocks != null && currentId !in placement.replaceBlocks) {
                skippedBlocks++
                return@forEach
            }
            world.setBlock(pos, placement.block.toBlock(pos, profile, location))
            val after = world.block(pos)?.copyForClone()
            if (!sameBlock(before, after)) changedPositions += pos
        }

        payload.addProperty("placed", true)
        payload.addProperty("format", feature.formatFor(plan))
        payload.addProperty("featureType", plan.type)
        payload.addProperty("resourceKind", feature.kind)
        payload.addProperty("attemptedBlocks", plan.blocks.size)
        payload.addProperty("changedBlocks", changedPositions.size)
        payload.addProperty("skippedBlocks", skippedBlocks)
        payload.add("origin", blockPosOutput(origin))
        payload.add("positions", blockPosArrayOutput(changedPositions))
        payload.add("block", blockArgumentOutput(plan.blocks.first().block))
        payload.add(
            "blocks",
            JsonArray().also { blocks ->
                plan.blocks.forEach { placement ->
                    blocks.add(
                        JsonObject().also { entry ->
                            entry.add("offset", blockPosOutput(placement.offset))
                            entry.add("block", blockArgumentOutput(placement.block))
                            placement.replaceBlocks?.let { replaceBlocks ->
                                entry.add(
                                    "replaceBlocks",
                                    JsonArray().also { array ->
                                        replaceBlocks.map { it.toString() }.sorted().forEach(array::add)
                                    },
                                )
                            }
                        },
                    )
                }
            },
        )
        return changedPositions.map { it.toString() }
    }

    private fun SandboxFeaturePlacement.formatFor(plan: SandboxFeaturePlacementPlan): String =
        when (plan.type) {
            "simple_block", "sandbox_block" -> format
            else -> "configured-${plan.type}"
        }

    private fun resolvePlaceFeature(
        id: ResourceLocation,
        payload: JsonObject,
        location: SourceLocation?,
    ): SandboxFeaturePlacement? {
        datapack.rawResources["worldgen/placed_feature"]?.get(id)?.let { placed ->
            if (!placed.root.isJsonObject) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Placed feature resource '$id' must be a JSON object", location)
            }
            val root = placed.root.asJsonObject
            payload.addProperty("resourceKind", "worldgen/placed_feature")
            root.get("feature")?.let { feature ->
                if (feature.isJsonPrimitive) {
                    val configuredId = ResourceLocation.parse(feature.asJsonPrimitive.asString)
                    val configured =
                        datapack.rawResources["worldgen/configured_feature"]?.get(configuredId)
                            ?: throw SandboxException(
                                DiagnosticCode.RESOURCE_NOT_FOUND,
                                "Configured feature '$configuredId' referenced by placed feature '$id' was not found",
                                location,
                            )
                    if (!configured.root.isJsonObject) {
                        throw SandboxException(
                            DiagnosticCode.INPUT_FORMAT,
                            "Configured feature resource '$configuredId' must be a JSON object",
                            location,
                        )
                    }
                    payload.addProperty("configuredFeature", configuredId.toString())
                    return SandboxFeaturePlacement("worldgen/configured_feature", configured.root.asJsonObject, "configured-simple-block")
                }
                if (feature.isJsonObject) {
                    payload.addProperty("configuredFeature", "inline")
                    return SandboxFeaturePlacement("worldgen/placed_feature", feature.asJsonObject, "configured-simple-block")
                }
                throw SandboxException(
                    DiagnosticCode.INPUT_FORMAT,
                    "Placed feature resource '$id' field 'feature' must be a string id or object",
                    location,
                )
            }
            return SandboxFeaturePlacement("worldgen/placed_feature", root, "sandbox-feature-json")
        }
        datapack.rawResources["worldgen/configured_feature"]?.get(id)?.let { configured ->
            if (!configured.root.isJsonObject) {
                throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Configured feature resource '$id' must be a JSON object", location)
            }
            payload.addProperty("resourceKind", "worldgen/configured_feature")
            return SandboxFeaturePlacement("worldgen/configured_feature", configured.root.asJsonObject, "configured-simple-block")
        }
        return null
    }

    private fun JsonObject.parseFeaturePlacementPlan(
        location: SourceLocation?,
        depth: Int = 0,
    ): SandboxFeaturePlacementPlan? {
        if (depth > 8) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Feature nesting depth exceeds sandbox limit 8", location)
        }
        val type = placeString("type", location)?.removePrefix("minecraft:") ?: "sandbox_block"
        return when (type) {
            "random_patch", "flower" -> parseRandomPatchFeature(type, location, depth)
            "random_selector", "simple_random_selector", "random_boolean_selector" -> parseSelectorFeature(type, location, depth)
            "ore" -> parseOreFeature(location)
            "block_column" -> parseBlockColumnFeature(location)
            "disk", "disk_replace" -> parseDiskFeature(type, location)
            "vegetation_patch", "waterlogged_vegetation_patch" -> parseVegetationPatchFeature(type, location, depth)
            "replace_single_block" -> parseReplaceSingleBlockFeature(location)
            "replace_blob" -> parseReplaceBlobFeature(type, location)
            "tree", "fancy_tree", "mega_jungle_tree", "dark_oak", "jungle_tree" -> parseTreeFeature(type, location)
            "basalt_columns" -> parseBasaltColumnsFeature(type, location)
            "delta_feature" -> parseDeltaFeature(type, location)
            "lake" -> parseLakeFeature(type, location)
            "spring_feature", "spring" -> parseSpringFeature(type, location)
            "block_pile" -> parseBlockPileFeature(type, location)
            "glowstone_blob" -> parseGlowstoneBlobFeature(type, location)
            "forest_rock" -> parseForestRockFeature(type, location)
            "netherrack_replace_blobs" -> parseNetherrackReplaceBlobsFeature(type, location)
            "chorus_plant" -> parseChorusPlantFeature(type, location)
            else ->
                parseFeatureBlockArgument(location)?.let { block ->
                    SandboxFeaturePlacementPlan(type, listOf(SandboxFeatureBlockPlacement(BlockPos(0, 0, 0), block)))
                }
        }
    }

    private fun JsonObject.parseBlockColumnFeature(location: SourceLocation?): SandboxFeaturePlacementPlan? {
        val config = getAsJsonObjectOrNull("config", location) ?: this
        val layers = config.getAsJsonArrayOrNull("layers") ?: return null
        val direction = (config.placeString("direction", location) ?: "up").removePrefix("minecraft:")
        val yStep =
            when (direction) {
                "up" -> 1
                "down" -> -1
                else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "block_column direction must be up or down", location)
            }
        val blocks = mutableListOf<SandboxFeatureBlockPlacement>()
        var y = 0
        layers.forEachIndexed { index, element ->
            val layer = element.asPlaceJsonObject("block_column layer $index", location)
            val height =
                layer.get("height")?.featureIntProvider("block_column layer $index height", location)
                    ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "block_column layer $index height is required", location)
            val provider =
                layer.getAsJsonObjectOrNull("provider", location)
                    ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "block_column layer $index provider is required", location)
            val block = provider.parseBlockStateProviderBlock("block_column layer $index provider", location)
            repeat(height.coerceIn(0, 64 - blocks.size)) {
                blocks += SandboxFeatureBlockPlacement(BlockPos(0, y, 0), block)
                y += yStep
            }
        }
        return SandboxFeaturePlacementPlan("block_column", blocks)
    }

    private fun JsonObject.parseDiskFeature(
        type: String,
        location: SourceLocation?,
    ): SandboxFeaturePlacementPlan? {
        val config = getAsJsonObjectOrNull("config", location) ?: this
        val block =
            config
                .getAsJsonObjectOrNull("state_provider", location)
                ?.parseBlockStateProviderBlock("$type state provider", location)
                ?: config
                    .getAsJsonObjectOrNull("provider", location)
                    ?.parseBlockStateProviderBlock("$type provider", location)
                ?: config.get("state")?.parseStructureProcessorBlockArgument("$type state", location)
                ?: return null
        val radius = (config.get("radius")?.featureIntProvider("$type radius", location) ?: 2).coerceIn(0, 8)
        val halfHeight = (config.get("half_height")?.featureIntProvider("$type half_height", location) ?: 0).coerceIn(0, 4)
        val replaceBlocks = config.featureTargetListBlocks("targets", "$type target", location)
        val blocks =
            diskOffsets(radius, halfHeight).map { offset ->
                SandboxFeatureBlockPlacement(offset = offset, block = block, replaceBlocks = replaceBlocks)
            }
        return SandboxFeaturePlacementPlan(type, blocks)
    }

    private fun JsonObject.parseVegetationPatchFeature(
        type: String,
        location: SourceLocation?,
        depth: Int,
    ): SandboxFeaturePlacementPlan? {
        val config = getAsJsonObjectOrNull("config", location) ?: this
        val ground =
            config
                .getAsJsonObjectOrNull("ground_state", location)
                ?.parseBlockStateProviderBlock("$type ground_state", location)
                ?: config
                    .getAsJsonObjectOrNull("ground_provider", location)
                    ?.parseBlockStateProviderBlock("$type ground_provider", location)
                ?: return null
        val radius =
            (
                config.get("xz_radius")?.featureIntProvider("$type xz_radius", location)
                    ?: config.get("radius")?.featureIntProvider("$type radius", location)
                    ?: 1
            ).coerceIn(0, 8)
        val groundDepth = (config.get("depth")?.featureIntProvider("$type depth", location) ?: 1).coerceIn(1, 4)
        val replaceBlocks =
            config
                .getAsJsonObjectOrNull("replaceable", location)
                ?.featureTargetBlocks("$type replaceable", location)
                ?: config.featureTargetListBlocks("targets", "$type target", location)
        val groundBlocks =
            diskOffsets(radius, 0).flatMap { offset ->
                (0 until groundDepth).map { layer ->
                    SandboxFeatureBlockPlacement(
                        offset = BlockPos(offset.x, offset.y - layer, offset.z),
                        block = ground,
                        replaceBlocks = replaceBlocks,
                    )
                }
            }
        val vegetationBlocks =
            config
                .get("vegetation_feature")
                ?.let { parseNestedFeaturePlan(it, "$type vegetation_feature", location, depth + 1) }
                ?.blocks
                ?.map { nested ->
                    SandboxFeatureBlockPlacement(
                        offset = BlockPos(nested.offset.x, nested.offset.y + 1, nested.offset.z),
                        block = nested.block,
                        replaceBlocks = nested.replaceBlocks,
                    )
                }
                ?: emptyList()
        return SandboxFeaturePlacementPlan(type, groundBlocks + vegetationBlocks)
    }

    private fun JsonObject.parseReplaceSingleBlockFeature(location: SourceLocation?): SandboxFeaturePlacementPlan? {
        val target = firstReplacementTarget("replace_single_block", location) ?: return null
        return SandboxFeaturePlacementPlan(
            "replace_single_block",
            listOf(SandboxFeatureBlockPlacement(BlockPos(0, 0, 0), target.first, target.second)),
        )
    }

    private fun JsonObject.parseReplaceBlobFeature(
        type: String,
        location: SourceLocation?,
    ): SandboxFeaturePlacementPlan? {
        val config = getAsJsonObjectOrNull("config", location) ?: this
        val target = firstReplacementTarget(type, location) ?: return null
        val radius = (config.get("radius")?.featureIntProvider("$type radius", location) ?: 2).coerceIn(0, 6)
        val blocks =
            blobOffsets(radius).map { offset ->
                SandboxFeatureBlockPlacement(offset = offset, block = target.first, replaceBlocks = target.second)
            }
        return SandboxFeaturePlacementPlan(type, blocks)
    }

    private fun JsonObject.firstReplacementTarget(
        label: String,
        location: SourceLocation?,
    ): Pair<BlockArgument, Set<ResourceLocation>?>? {
        val config = getAsJsonObjectOrNull("config", location) ?: this
        val targets = config.getAsJsonArrayOrNull("targets")
        if (targets != null) {
            return targets
                .mapIndexed { index, element ->
                    element
                        .asPlaceJsonObject("$label target $index", location)
                        .parseOreTarget("$label target $index", location)
                }.firstOrNull()
        }
        val state =
            config.get("state")?.parseStructureProcessorBlockArgument("$label state", location)
                ?: config.get("output_state")?.parseStructureProcessorBlockArgument("$label output_state", location)
                ?: return null
        val replaceBlocks = config.get("target")?.featureTargetElementBlocks("$label target", location)
        return state to replaceBlocks
    }

    private fun JsonObject.parseTreeFeature(
        type: String,
        location: SourceLocation?,
    ): SandboxFeaturePlacementPlan? {
        val config = getAsJsonObjectOrNull("config", location) ?: this
        val trunk =
            config
                .getAsJsonObjectOrNull("trunk_provider", location)
                ?.parseBlockStateProviderBlock("$type trunk_provider", location)
                ?: config.get("trunk")?.parseStructureProcessorBlockArgument("$type trunk", location)
                ?: return null
        val foliage =
            config
                .getAsJsonObjectOrNull("foliage_provider", location)
                ?.parseBlockStateProviderBlock("$type foliage_provider", location)
                ?: config.get("foliage")?.parseStructureProcessorBlockArgument("$type foliage", location)
                ?: return null
        val dirt =
            config
                .getAsJsonObjectOrNull("dirt_provider", location)
                ?.parseBlockStateProviderBlock("$type dirt_provider", location)
        val trunkPlacer = config.getAsJsonObjectOrNull("trunk_placer", location)
        val height =
            (
                trunkPlacer?.get("base_height")?.featureIntProvider("$type trunk_placer base_height", location)
                    ?: config.get("height")?.featureIntProvider("$type height", location)
                    ?: 4
            ).coerceIn(1, 16)
        val foliagePlacer = config.getAsJsonObjectOrNull("foliage_placer", location)
        val radius =
            (
                foliagePlacer?.get("radius")?.featureIntProvider("$type foliage_placer radius", location)
                    ?: config.get("foliage_radius")?.featureIntProvider("$type foliage_radius", location)
                    ?: 2
            ).coerceIn(0, 4)
        val blocks = mutableListOf<SandboxFeatureBlockPlacement>()
        if (dirt != null) blocks += SandboxFeatureBlockPlacement(BlockPos(0, -1, 0), dirt)
        repeat(height) { y -> blocks += SandboxFeatureBlockPlacement(BlockPos(0, y, 0), trunk) }
        treeFoliageOffsets(height, radius).forEach { offset ->
            blocks += SandboxFeatureBlockPlacement(offset = offset, block = foliage)
        }
        return SandboxFeaturePlacementPlan(type, blocks.distinctBy { it.offset })
    }

    private fun JsonObject.parseBasaltColumnsFeature(
        type: String,
        location: SourceLocation?,
    ): SandboxFeaturePlacementPlan? {
        val config = getAsJsonObjectOrNull("config", location) ?: this
        val block =
            config
                .getAsJsonObjectOrNull("state_provider", location)
                ?.parseBlockStateProviderBlock("$type state_provider", location)
                ?: config
                    .getAsJsonObjectOrNull("provider", location)
                    ?.parseBlockStateProviderBlock("$type provider", location)
                ?: config.get("state")?.parseFeatureBlockValue("$type state", location)
                ?: BlockArgument(ResourceLocation.parse("minecraft:basalt"))
        val height = (config.get("height")?.featureIntProvider("$type height", location) ?: 3).coerceIn(1, 32)
        val reach = (config.get("reach")?.featureIntProvider("$type reach", location) ?: 2).coerceIn(0, 8)
        val replaceBlocks =
            config
                .getAsJsonObjectOrNull("replaceable", location)
                ?.featureTargetBlocks("$type replaceable", location)
                ?: config.featureTargetListBlocks("targets", "$type target", location)
        val blocks =
            basaltColumnOffsets(height, reach).map { offset ->
                SandboxFeatureBlockPlacement(offset = offset, block = block, replaceBlocks = replaceBlocks)
            }
        return SandboxFeaturePlacementPlan(type, blocks)
    }

    private fun JsonObject.parseDeltaFeature(
        type: String,
        location: SourceLocation?,
    ): SandboxFeaturePlacementPlan? {
        val config = getAsJsonObjectOrNull("config", location) ?: this
        val contents =
            config.get("contents")?.parseFeatureBlockValue("$type contents", location)
                ?: config.get("state")?.parseFeatureBlockValue("$type state", location)
                ?: config
                    .getAsJsonObjectOrNull("state_provider", location)
                    ?.parseBlockStateProviderBlock("$type state_provider", location)
                ?: return null
        val rim =
            config.get("rim")?.parseFeatureBlockValue("$type rim", location)
                ?: config.get("rim_state")?.parseFeatureBlockValue("$type rim_state", location)
                ?: contents
        val size = (config.get("size")?.featureIntProvider("$type size", location) ?: 2).coerceIn(0, 8)
        val rimSize = (config.get("rim_size")?.featureIntProvider("$type rim_size", location) ?: 1).coerceIn(0, 4)
        val replaceBlocks =
            config
                .getAsJsonObjectOrNull("replaceable", location)
                ?.featureTargetBlocks("$type replaceable", location)
                ?: config.featureTargetListBlocks("targets", "$type target", location)
        val contentBlocks =
            diskOffsets(size, 0).map { offset ->
                SandboxFeatureBlockPlacement(offset = offset, block = contents, replaceBlocks = replaceBlocks)
            }
        val rimBlocks =
            ringOffsets(size, size + rimSize).map { offset ->
                SandboxFeatureBlockPlacement(offset = offset, block = rim, replaceBlocks = replaceBlocks)
            }
        return SandboxFeaturePlacementPlan(type, (contentBlocks + rimBlocks).distinctBy { it.offset })
    }

    private fun JsonObject.parseLakeFeature(
        type: String,
        location: SourceLocation?,
    ): SandboxFeaturePlacementPlan? {
        val config = getAsJsonObjectOrNull("config", location) ?: this
        val fluid =
            config.get("state")?.parseFeatureBlockValue("$type state", location)
                ?: config.get("fluid")?.parseFeatureBlockValue("$type fluid", location)
                ?: config.get("fluid_state")?.parseFeatureBlockValue("$type fluid_state", location)
                ?: config.get("contents")?.parseFeatureBlockValue("$type contents", location)
                ?: BlockArgument(ResourceLocation.parse("minecraft:water"))
        val barrier =
            config.get("barrier")?.parseFeatureBlockValue("$type barrier", location)
                ?: config.get("barrier_state")?.parseFeatureBlockValue("$type barrier_state", location)
        val radius = (config.get("radius")?.featureIntProvider("$type radius", location) ?: 2).coerceIn(0, 8)
        val depth = (config.get("depth")?.featureIntProvider("$type depth", location) ?: 1).coerceIn(1, 4)
        val replaceBlocks =
            config
                .getAsJsonObjectOrNull("replaceable", location)
                ?.featureTargetBlocks("$type replaceable", location)
                ?: config.featureTargetListBlocks("targets", "$type target", location)
        val fluidBlocks =
            diskOffsets(radius, 0).flatMap { offset ->
                (0 until depth).map { layer ->
                    SandboxFeatureBlockPlacement(
                        offset = BlockPos(offset.x, offset.y - layer, offset.z),
                        block = fluid,
                        replaceBlocks = replaceBlocks,
                    )
                }
            }
        val barrierBlocks =
            barrier?.let { block ->
                ringOffsets(radius, radius + 1).map { offset ->
                    SandboxFeatureBlockPlacement(offset = offset, block = block, replaceBlocks = replaceBlocks)
                }
            } ?: emptyList()
        return SandboxFeaturePlacementPlan(type, (fluidBlocks + barrierBlocks).distinctBy { it.offset })
    }

    private fun JsonObject.parseSpringFeature(
        type: String,
        location: SourceLocation?,
    ): SandboxFeaturePlacementPlan? {
        val config = getAsJsonObjectOrNull("config", location) ?: this
        val fluid =
            config.get("state")?.parseFeatureBlockValue("$type state", location)
                ?: config.get("fluid")?.parseFeatureBlockValue("$type fluid", location)
                ?: config.get("fluid_state")?.parseFeatureBlockValue("$type fluid_state", location)
                ?: config.get("contents")?.parseFeatureBlockValue("$type contents", location)
                ?: BlockArgument(ResourceLocation.parse("minecraft:water"))
        val replaceBlocks =
            config
                .getAsJsonObjectOrNull("replaceable", location)
                ?.featureTargetBlocks("$type replaceable", location)
                ?: config.get("target")?.featureTargetElementBlocks("$type target", location)
                ?: config.featureTargetListBlocks("targets", "$type target", location)
        return SandboxFeaturePlacementPlan(type, listOf(SandboxFeatureBlockPlacement(BlockPos(0, 0, 0), fluid, replaceBlocks)))
    }

    private fun JsonObject.parseBlockPileFeature(
        type: String,
        location: SourceLocation?,
    ): SandboxFeaturePlacementPlan? {
        val config = getAsJsonObjectOrNull("config", location) ?: this
        val block =
            config
                .getAsJsonObjectOrNull("state_provider", location)
                ?.parseBlockStateProviderBlock("$type state_provider", location)
                ?: config
                    .getAsJsonObjectOrNull("provider", location)
                    ?.parseBlockStateProviderBlock("$type provider", location)
                ?: config.get("state")?.parseFeatureBlockValue("$type state", location)
                ?: return null
        val radius = (config.get("radius")?.featureIntProvider("$type radius", location) ?: 1).coerceIn(0, 4)
        val height = (config.get("height")?.featureIntProvider("$type height", location) ?: 1).coerceIn(1, 4)
        val replaceBlocks =
            config
                .getAsJsonObjectOrNull("replaceable", location)
                ?.featureTargetBlocks("$type replaceable", location)
                ?: config.get("target")?.featureTargetElementBlocks("$type target", location)
                ?: config.featureTargetListBlocks("targets", "$type target", location)
        val blocks =
            diskOffsets(radius, 0).flatMap { offset ->
                val columnHeight = (height - abs(offset.x) - abs(offset.z)).coerceAtLeast(1)
                (0 until columnHeight).map { y ->
                    SandboxFeatureBlockPlacement(BlockPos(offset.x, offset.y + y, offset.z), block, replaceBlocks)
                }
            }
        return SandboxFeaturePlacementPlan(type, blocks.distinctBy { it.offset })
    }

    private fun JsonObject.parseGlowstoneBlobFeature(
        type: String,
        location: SourceLocation?,
    ): SandboxFeaturePlacementPlan? {
        val config = getAsJsonObjectOrNull("config", location) ?: this
        val block =
            config
                .getAsJsonObjectOrNull("state_provider", location)
                ?.parseBlockStateProviderBlock("$type state_provider", location)
                ?: config
                    .getAsJsonObjectOrNull("provider", location)
                    ?.parseBlockStateProviderBlock("$type provider", location)
                ?: config.get("state")?.parseFeatureBlockValue("$type state", location)
                ?: BlockArgument(ResourceLocation.parse("minecraft:glowstone"))
        val radius = (config.get("radius")?.featureIntProvider("$type radius", location) ?: 1).coerceIn(0, 4)
        val replaceBlocks =
            config
                .getAsJsonObjectOrNull("replaceable", location)
                ?.featureTargetBlocks("$type replaceable", location)
                ?: config.get("target")?.featureTargetElementBlocks("$type target", location)
                ?: config.featureTargetListBlocks("targets", "$type target", location)
        return SandboxFeaturePlacementPlan(
            type,
            blobOffsets(radius).map { offset -> SandboxFeatureBlockPlacement(offset, block, replaceBlocks) },
        )
    }

    private fun JsonObject.parseForestRockFeature(
        type: String,
        location: SourceLocation?,
    ): SandboxFeaturePlacementPlan? {
        val config = getAsJsonObjectOrNull("config", location) ?: this
        val block =
            config
                .getAsJsonObjectOrNull("state_provider", location)
                ?.parseBlockStateProviderBlock("$type state_provider", location)
                ?: config
                    .getAsJsonObjectOrNull("provider", location)
                    ?.parseBlockStateProviderBlock("$type provider", location)
                ?: config.get("state")?.parseFeatureBlockValue("$type state", location)
                ?: BlockArgument(ResourceLocation.parse("minecraft:mossy_cobblestone"))
        val radius = (config.get("radius")?.featureIntProvider("$type radius", location) ?: 1).coerceIn(0, 4)
        val replaceBlocks =
            config
                .getAsJsonObjectOrNull("replaceable", location)
                ?.featureTargetBlocks("$type replaceable", location)
                ?: config.featureTargetListBlocks("targets", "$type target", location)
        return SandboxFeaturePlacementPlan(
            type,
            blobOffsets(radius).map { offset -> SandboxFeatureBlockPlacement(offset, block, replaceBlocks) },
        )
    }

    private fun JsonObject.parseNetherrackReplaceBlobsFeature(
        type: String,
        location: SourceLocation?,
    ): SandboxFeaturePlacementPlan? {
        val config = getAsJsonObjectOrNull("config", location) ?: this
        val block =
            config.get("target_state")?.parseFeatureBlockValue("$type target_state", location)
                ?: config.get("state")?.parseFeatureBlockValue("$type state", location)
                ?: config
                    .getAsJsonObjectOrNull("state_provider", location)
                    ?.parseBlockStateProviderBlock("$type state_provider", location)
                ?: BlockArgument(ResourceLocation.parse("minecraft:blackstone"))
        val replaceBlocks =
            config
                .get("replace_state")
                ?.parseFeatureBlockValue("$type replace_state", location)
                ?.let { setOf(it.id) }
                ?: config.getAsJsonObjectOrNull("replaceable", location)?.featureTargetBlocks("$type replaceable", location)
                ?: config.featureTargetListBlocks("targets", "$type target", location)
                ?: setOf(ResourceLocation.parse("minecraft:netherrack"))
        val radius = (config.get("radius")?.featureIntProvider("$type radius", location) ?: 1).coerceIn(0, 4)
        return SandboxFeaturePlacementPlan(
            type,
            blobOffsets(radius).map { offset -> SandboxFeatureBlockPlacement(offset, block, replaceBlocks) },
        )
    }

    private fun JsonObject.parseChorusPlantFeature(
        type: String,
        location: SourceLocation?,
    ): SandboxFeaturePlacementPlan? {
        val config = getAsJsonObjectOrNull("config", location) ?: this
        val plant =
            config.get("plant_state")?.parseFeatureBlockValue("$type plant_state", location)
                ?: config.get("body_state")?.parseFeatureBlockValue("$type body_state", location)
                ?: config.get("state")?.parseFeatureBlockValue("$type state", location)
                ?: BlockArgument(ResourceLocation.parse("minecraft:chorus_plant"))
        val flower =
            config.get("flower_state")?.parseFeatureBlockValue("$type flower_state", location)
                ?: BlockArgument(ResourceLocation.parse("minecraft:chorus_flower"))
        val height = (config.get("height")?.featureIntProvider("$type height", location) ?: 4).coerceIn(1, 16)
        val blocks =
            (0 until height).map { y ->
                SandboxFeatureBlockPlacement(BlockPos(0, y, 0), plant)
            } + SandboxFeatureBlockPlacement(BlockPos(0, height, 0), flower)
        return SandboxFeaturePlacementPlan(type, blocks)
    }

    private fun JsonObject.parseSelectorFeature(
        type: String,
        location: SourceLocation?,
        depth: Int,
    ): SandboxFeaturePlacementPlan? {
        val config = getAsJsonObjectOrNull("config", location) ?: this
        val selected =
            when (type) {
                "random_boolean_selector" -> config.get("feature_true") ?: config.get("feature_false")
                else -> {
                    val features = config.getAsJsonArrayOrNull("features")
                    val entry = features?.firstOrNull()
                    entry?.takeIf { it.isJsonObject }?.asJsonObject?.get("feature")
                        ?: entry
                        ?: config.get("default")
                        ?: config.get("default_feature")
                }
            } ?: return null
        return parseNestedFeaturePlan(selected, "$type feature", location, depth + 1)
            ?.let { it.copy(type = type) }
    }

    private fun JsonObject.parseOreFeature(location: SourceLocation?): SandboxFeaturePlacementPlan? {
        val config = getAsJsonObjectOrNull("config", location) ?: this
        val targets = config.getAsJsonArrayOrNull("targets") ?: return null
        val target =
            targets
                .mapIndexed { index, element ->
                    element.asPlaceJsonObject("ore target $index", location).parseOreTarget("ore target $index", location)
                }.firstOrNull() ?: return null
        val size = config.placeInt("size", 1, "ore size", location).coerceIn(0, 64)
        val blocks =
            oreOffsets(size).map { offset ->
                SandboxFeatureBlockPlacement(offset = offset, block = target.first, replaceBlocks = target.second)
            }
        return SandboxFeaturePlacementPlan("ore", blocks)
    }

    private fun JsonObject.parseOreTarget(
        label: String,
        location: SourceLocation?,
    ): Pair<BlockArgument, Set<ResourceLocation>?> {
        val state =
            get("state")?.parseStructureProcessorBlockArgument("$label state", location)
                ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label state is required", location)
        val replaceBlocks = getAsJsonObjectOrNull("target", location)?.featureTargetBlocks("$label target", location)
        return state to replaceBlocks
    }

    private fun JsonObject.featureTargetBlocks(
        label: String,
        location: SourceLocation?,
    ): Set<ResourceLocation>? {
        val type = placeString("predicate_type", location) ?: placeString("type", location) ?: "minecraft:always_true"
        return when (type.removePrefix("minecraft:")) {
            "always_true" -> null
            "matching_blocks" -> processorBlockIds("blocks", "block", "$label blocks", location)
            "matching_block_tag" -> {
                val tag =
                    placeString("tag", location)
                        ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label tag is required", location)
                blockTagBlocks(ResourceLocation.parse(tag))
            }
            else -> emptySet()
        }
    }

    private fun JsonElement.featureTargetElementBlocks(
        label: String,
        location: SourceLocation?,
    ): Set<ResourceLocation>? =
        when {
            isJsonPrimitive -> setOf(ResourceLocation.parse(asJsonPrimitive.asString))
            isJsonObject -> asJsonObject.featureTargetBlocks(label, location)
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must be a block id string or predicate object", location)
        }

    private fun JsonObject.featureTargetListBlocks(
        name: String,
        label: String,
        location: SourceLocation?,
    ): Set<ResourceLocation>? {
        val targets = getAsJsonArrayOrNull(name) ?: return null
        val blocks = mutableSetOf<ResourceLocation>()
        targets.forEachIndexed { index, element ->
            val targetBlocks =
                element
                    .asPlaceJsonObject("$label $index", location)
                    .featureTargetBlocks("$label $index", location)
            if (targetBlocks == null) return null
            blocks += targetBlocks
        }
        return blocks
    }

    private fun blockTagBlocks(
        id: ResourceLocation,
        visited: Set<ResourceLocation> = emptySet(),
    ): Set<ResourceLocation> {
        if (id in visited) return emptySet()
        val definition = datapack.tags[TagKey("block", id)] ?: datapack.tags[TagKey("blocks", id)] ?: return emptySet()
        return definition.values
            .flatMap { value ->
                if (value.id.startsWith("#")) {
                    blockTagBlocks(ResourceLocation.parse(value.id.removePrefix("#")), visited + id)
                } else {
                    listOf(ResourceLocation.parse(value.id))
                }
            }.toSet()
    }

    private fun JsonObject.parseRandomPatchFeature(
        type: String,
        location: SourceLocation?,
        depth: Int,
    ): SandboxFeaturePlacementPlan? {
        val config = getAsJsonObjectOrNull("config", location) ?: this
        val nested = config.get("feature") ?: get("feature") ?: return null
        val nestedPlan = parseNestedFeaturePlan(nested, "$type feature", location, depth + 1) ?: return null
        val tries = config.placeInt("tries", nestedPlan.blocks.size.coerceAtLeast(1), "$type tries", location)
        val xzSpread = config.placeInt("xz_spread", 0, "$type xz_spread", location).coerceAtLeast(0)
        val ySpread = config.placeInt("y_spread", 0, "$type y_spread", location).coerceAtLeast(0)
        val offsets = randomPatchOffsets(tries, xzSpread, ySpread)
        val blocks =
            offsets
                .flatMap { patchOffset ->
                    nestedPlan.blocks.map { nestedBlock ->
                        SandboxFeatureBlockPlacement(
                            offset =
                                BlockPos(
                                    patchOffset.x + nestedBlock.offset.x,
                                    patchOffset.y + nestedBlock.offset.y,
                                    patchOffset.z + nestedBlock.offset.z,
                                ),
                            block = nestedBlock.block,
                        )
                    }
                }.distinctBy { it.offset }
        return SandboxFeaturePlacementPlan(type, blocks)
    }

    private fun parseNestedFeaturePlan(
        value: JsonElement,
        label: String,
        location: SourceLocation?,
        depth: Int,
    ): SandboxFeaturePlacementPlan? =
        when {
            value.isJsonPrimitive -> {
                val id = ResourceLocation.parse(value.asJsonPrimitive.asString)
                val root = resolveReferencedFeature(id, label, location)
                root.parseFeaturePlacementPlan(location, depth)
            }
            value.isJsonObject -> {
                val root = value.asJsonObject
                val nested = root.get("feature")
                if (nested != null && (root.has("placement") || root.placeString("type", location) == null)) {
                    parseNestedFeaturePlan(nested, "$label nested", location, depth)
                } else {
                    root.parseFeaturePlacementPlan(location, depth)
                }
            }
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must be a feature id or object", location)
        }

    private fun resolveReferencedFeature(
        id: ResourceLocation,
        label: String,
        location: SourceLocation?,
    ): JsonObject {
        val resource =
            datapack.rawResources["worldgen/configured_feature"]?.get(id)
                ?: datapack.rawResources["worldgen/placed_feature"]?.get(id)
                ?: throw SandboxException(DiagnosticCode.RESOURCE_NOT_FOUND, "Feature '$id' referenced by $label was not found", location)
        if (!resource.root.isJsonObject) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "Feature resource '$id' referenced by $label must be a JSON object",
                location,
            )
        }
        return resource.root.asJsonObject
    }

    private fun randomPatchOffsets(
        tries: Int,
        xzSpread: Int,
        ySpread: Int,
    ): List<BlockPos> {
        val cappedTries = tries.coerceIn(0, 64)
        if (cappedTries == 0) return emptyList()
        val yOrder =
            buildList {
                add(0)
                for (delta in 1..ySpread) {
                    add(delta)
                    add(-delta)
                }
            }
        val candidates = mutableListOf(BlockPos(0, 0, 0))
        yOrder.forEach { y ->
            for (x in -xzSpread..xzSpread) {
                for (z in -xzSpread..xzSpread) {
                    if (x == 0 && y == 0 && z == 0) continue
                    candidates += BlockPos(x, y, z)
                }
            }
        }
        return candidates.take(cappedTries)
    }

    private fun oreOffsets(size: Int): List<BlockPos> {
        val cappedSize = size.coerceIn(0, 64)
        if (cappedSize == 0) return emptyList()
        val candidates =
            mutableListOf(
                BlockPos(0, 0, 0),
                BlockPos(1, 0, 0),
                BlockPos(-1, 0, 0),
                BlockPos(0, 0, 1),
                BlockPos(0, 0, -1),
                BlockPos(0, 1, 0),
                BlockPos(0, -1, 0),
            )
        for (radius in 1..3) {
            for (x in -radius..radius) {
                for (y in -radius..radius) {
                    for (z in -radius..radius) {
                        val pos = BlockPos(x, y, z)
                        if (pos !in candidates) candidates += pos
                    }
                }
            }
        }
        return candidates.take(cappedSize)
    }

    private fun basaltColumnOffsets(
        height: Int,
        reach: Int,
    ): List<BlockPos> {
        val columns = mutableListOf(0 to 0)
        for (distance in 1..reach) {
            for (x in -distance..distance) {
                for (z in -distance..distance) {
                    if (abs(x) + abs(z) == distance) columns += x to z
                }
            }
        }
        val offsets = mutableListOf<BlockPos>()
        columns.forEach { (x, z) ->
            val columnHeight = (height - abs(x) - abs(z)).coerceAtLeast(1)
            repeat(columnHeight) { y -> offsets += BlockPos(x, y, z) }
        }
        return offsets.distinct().take(256)
    }

    private fun blobOffsets(radius: Int): List<BlockPos> {
        if (radius <= 0) return listOf(BlockPos(0, 0, 0))
        val candidates = mutableListOf(BlockPos(0, 0, 0))
        for (r in 1..radius) {
            for (x in -r..r) {
                for (y in -r..r) {
                    for (z in -r..r) {
                        if (x * x + y * y + z * z > r * r) continue
                        val pos = BlockPos(x, y, z)
                        if (pos !in candidates) candidates += pos
                    }
                }
            }
        }
        return candidates.take(256)
    }

    private fun treeFoliageOffsets(
        height: Int,
        radius: Int,
    ): List<BlockPos> {
        val y = height
        val offsets = mutableListOf(BlockPos(0, y, 0))
        for (x in -radius..radius) {
            for (z in -radius..radius) {
                if (x == 0 && z == 0) continue
                if (x * x + z * z <= radius * radius) offsets += BlockPos(x, y, z)
            }
        }
        if (radius > 1) offsets += BlockPos(0, y + 1, 0)
        return offsets.distinct()
    }

    private fun ringOffsets(
        innerRadius: Int,
        outerRadius: Int,
    ): List<BlockPos> {
        val innerSquared = innerRadius * innerRadius
        val outerSquared = outerRadius * outerRadius
        val offsets = mutableListOf<BlockPos>()
        for (x in -outerRadius..outerRadius) {
            for (z in -outerRadius..outerRadius) {
                val distanceSquared = x * x + z * z
                if (distanceSquared <= outerSquared && distanceSquared > innerSquared) {
                    offsets += BlockPos(x, 0, z)
                }
            }
        }
        return offsets.distinct().take(256)
    }

    private fun diskOffsets(
        radius: Int,
        halfHeight: Int,
    ): List<BlockPos> {
        val yOrder =
            buildList {
                add(0)
                for (delta in 1..halfHeight) {
                    add(delta)
                    add(-delta)
                }
            }
        val candidates = mutableListOf(BlockPos(0, 0, 0))
        yOrder.forEach { y ->
            for (x in -radius..radius) {
                for (z in -radius..radius) {
                    if (x == 0 && y == 0 && z == 0) continue
                    if (x * x + z * z <= radius * radius) candidates += BlockPos(x, y, z)
                }
            }
        }
        return candidates.distinct().take(256)
    }

    private fun JsonElement.parseFeatureBlockValue(
        label: String,
        location: SourceLocation?,
    ): BlockArgument =
        when {
            isJsonPrimitive -> BlockArgument(ResourceLocation.parse(asJsonPrimitive.asString))
            isJsonObject -> asJsonObject.parseBlockStateProviderBlock(label, location)
            else -> throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "$label must be a block id, state object, or state provider",
                location,
            )
        }

    private fun JsonObject.parseFeatureBlockArgument(location: SourceLocation?): BlockArgument? {
        get("block")?.let { block ->
            return when {
                block.isJsonPrimitive -> BlockArgument(ResourceLocation.parse(block.asJsonPrimitive.asString))
                block.isJsonObject -> block.asJsonObject.parseBlockStateObject("feature block", location)
                else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "feature block must be a string or object", location)
            }
        }
        getAsJsonObjectOrNull("state", location)?.let { return it.parseBlockStateObject("feature state", location) }
        val type = placeString("type", location)
        if (type == null || type == "minecraft:simple_block" || type == "simple_block") {
            val config = getAsJsonObjectOrNull("config", location)
            val provider = config?.getAsJsonObjectOrNull("to_place", location) ?: getAsJsonObjectOrNull("to_place", location)
            provider?.parseBlockStateProviderBlock("simple_block state provider", location)?.let { return it }
        }
        return null
    }

    private fun JsonObject.parseBlockStateProviderBlock(
        label: String,
        location: SourceLocation?,
    ): BlockArgument {
        val weighted = getAsJsonArrayOrNull("entries")?.firstOrNull()?.asPlaceJsonObject("$label weighted entry", location)
        val weightedState = weighted?.getAsJsonObjectOrNull("data", location) ?: weighted?.getAsJsonObjectOrNull("state", location)
        if (weightedState != null) return weightedState.parseBlockStateObject("$label weighted state", location)
        val state = getAsJsonObjectOrNull("state", location) ?: takeIf { has("Name") || has("id") }
        if (state != null) return state.parseBlockStateObject(label, location)
        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label requires state, entries[0].data, Name, or id", location)
    }

    private fun JsonElement.featureIntProvider(
        label: String,
        location: SourceLocation?,
    ): Int =
        when {
            isJsonPrimitive -> placeInt(label, location)
            isJsonObject -> {
                val root = asJsonObject
                root.get("value")?.placeInt("$label value", location)
                    ?: root.get("min_inclusive")?.placeInt("$label min_inclusive", location)
                    ?: root.get("min")?.placeInt("$label min", location)
                    ?: root.get("base")?.placeInt("$label base", location)
                    ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label int provider requires value or min", location)
            }
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must be an integer provider", location)
        }

    private fun JsonObject.parseBlockStateObject(
        label: String,
        location: SourceLocation?,
    ): BlockArgument {
        val rawId =
            placeString("id", location) ?: placeString("Name", location)
                ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label requires id or Name", location)
        val properties =
            (getAsJsonObjectOrNull("properties", location) ?: getAsJsonObjectOrNull("Properties", location))
                ?.entrySet()
                ?.associate { (key, value) ->
                    if (!value.isJsonPrimitive) {
                        throw SandboxException(
                            DiagnosticCode.INPUT_FORMAT,
                            "$label property '$key' must be a primitive",
                            location,
                        )
                    }
                    key to value.asJsonPrimitive.asString
                }
                ?: emptyMap()
        val nbt = getAsJsonObjectOrNull("nbt", location) ?: getAsJsonObjectOrNull("Nbt", location) ?: JsonObject()
        return BlockArgument(ResourceLocation.parse(rawId), properties, nbt)
    }

    private fun placeSandboxStructure(
        id: ResourceLocation,
        position: Position,
        context: ExecutionContext,
        payload: JsonObject,
        placement: SandboxStructurePlacement,
        location: SourceLocation?,
        extraProcessors: SandboxStructureProcessorList = SandboxStructureProcessorList.empty,
    ): List<String> {
        val resource = datapack.rawResources["worldgen/structure"]?.get(id)
        if (resource == null) {
            payload.addProperty("placed", false)
            payload.addProperty("reason", "Sandbox records place commands but no sandbox structure JSON resource was loaded")
            return emptyList()
        }
        if (!resource.root.isJsonObject) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Structure resource '$id' must be a JSON object", location)
        }
        val root = resource.root.asJsonObject
        val blockPlan = root.parseStructureBlockPlan(location)
        val blocks = blockPlan?.blocks
        val entities = root.getAsJsonArrayOrNull("entities")
        val processors = parseStructureProcessors(root, location).plus(extraProcessors)
        resource.sourceFormat?.let { payload.addProperty("sourceFormat", it) }
        if (blocks == null && entities == null) {
            payload.addProperty("placed", false)
            payload.addProperty("format", "raw-json-index-only")
            payload.addProperty("reason", "Loaded structure resource has no sandbox blocks or entities")
            return emptyList()
        }
        if ((blocks?.size ?: 0) > 32768) {
            throw SandboxException(
                DiagnosticCode.COMMAND_ERROR,
                "Structure resource '$id' has ${blocks?.size} blocks; limit is 32768",
                location,
            )
        }

        val origin = BlockPos(floor(position.x).toInt(), floor(position.y).toInt(), floor(position.z).toInt())
        val changedBlocks = mutableListOf<BlockPos>()
        var skippedBlocks = 0
        var processedBlocks = 0
        blocks?.forEachIndexed { index, element ->
            val offset = element.offset
            if (!placement.shouldPlace(id, origin, index)) {
                skippedBlocks++
                return@forEachIndexed
            }
            val transformedOffset = placement.transform(offset)
            val pos = BlockPos(origin.x + transformedOffset.x, origin.y + transformedOffset.y, origin.z + transformedOffset.z)
            val blockArgument = element.block
            val before = world.block(pos)?.copyForClone()
            val processed = applyStructureProcessors(blockArgument, before?.id, processors.processors, location)
            if (processed.processed) processedBlocks++
            val processedArgument = processed.block
            if (processedArgument == null) {
                skippedBlocks++
                return@forEachIndexed
            }
            val placedBlock = processedArgument.toBlock(pos, profile, location)
            world.setBlock(pos, placedBlock)
            val after = world.block(pos)?.copyForClone()
            if (!sameBlock(before, after)) changedBlocks += pos
        }

        val createdEntities = mutableListOf<SandboxEntity>()
        entities?.forEachIndexed { index, element ->
            val entityRoot = element.asPlaceJsonObject("structure entity $index", location)
            val offset = entityRoot.optionalPlacePosition("offset", "pos", "structure entity $index offset", location)
            val transformedOffset = placement.transform(offset)
            val positionAtOffset = Position(origin.x + transformedOffset.x, origin.y + transformedOffset.y, origin.z + transformedOffset.z)
            val entityNbt = entityRoot.placeJsonObject("nbt", "structure entity $index nbt", location)
            val tags = entityRoot.placeStringArray("tags", "structure entity $index tags", location).toMutableSet()
            if (entityNbt != null) tags += extractTags(entityNbt)
            val entityType =
                entityRoot.placeString("type", location)
                    ?: entityRoot.placeString("id", location)
                    ?: entityNbt?.placeString("id", location)
                    ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "structure entity $index type or nbt.id is required", location)
            val entity =
                SandboxEntity(
                    type = ResourceLocation.parse(entityType),
                    position = positionAtOffset,
                    tags = tags,
                    yaw = entityRoot.placeDouble("yaw", 0.0, "structure entity $index yaw", location),
                    pitch = entityRoot.placeDouble("pitch", 0.0, "structure entity $index pitch", location),
                    dimension = entityRoot.placeString("dimension", location)?.let(ResourceLocation::parse) ?: context.dimension,
                )
            val fullNbt = entity.fullNbt(profile, location)
            if (entityNbt != null) JsonPaths.merge(fullNbt, null, entityNbt.deepCopy())
            entityRoot.get("health")?.let { fullNbt.addProperty("Health", it.placeDouble("structure entity $index health", location)) }
            entity.writeFullNbt(profile, fullNbt, location)
            world.entities += entity
            createdEntities += entity
        }

        payload.addProperty("placed", true)
        payload.addProperty("format", blockPlan?.format ?: "sandbox-structure-json")
        payload.addProperty("changedBlocks", changedBlocks.size)
        payload.addProperty("skippedBlocks", skippedBlocks)
        payload.addProperty("processedBlocks", processedBlocks)
        payload.addProperty("unsupportedProcessors", processors.unsupportedCount)
        payload.addProperty("entities", createdEntities.size)
        if (processors.ids.isNotEmpty()) {
            payload.add(
                "processorLists",
                JsonArray().also { array ->
                    processors.ids
                        .map { it.toString() }
                        .sorted()
                        .forEach { array.add(it) }
                },
            )
        }
        payload.add("origin", blockPosOutput(origin))
        payload.add("positions", blockPosArrayOutput(changedBlocks))
        payload.add(
            "entityTargets",
            JsonArray().also { targets -> createdEntities.map { it.scoreHolder }.sorted().forEach { targets.add(it) } },
        )
        return changedBlocks.map { it.toString() } + createdEntities.map { it.scoreHolder }
    }

    private fun JsonObject.parseStructureBlockPlan(location: SourceLocation?): SandboxStructureBlockPlan? {
        val blockArray = getAsJsonArrayOrNull("blocks") ?: return null
        val palette = parseStructurePalette(location)
        val usePalette =
            palette != null &&
                blockArray.any { element ->
                    element.isJsonObject && element.asJsonObject.has("state") && !element.asJsonObject.has("id")
                }
        val blocks =
            blockArray.mapIndexed { index, element ->
                val root = element.asPlaceJsonObject("structure block $index", location)
                val offset = root.placeBlockPos("offset", "pos", "structure block $index offset", location)
                val block =
                    if (usePalette && root.has("state") && !root.has("id")) {
                        root.parsePaletteStructureBlock(index, requireNotNull(palette), location)
                    } else {
                        root.parseSandboxStructureBlock(index, location)
                    }
                SandboxStructureBlockPlacement(offset = offset, block = block)
            }
        return SandboxStructureBlockPlan(
            format = if (usePalette) "palette-structure-json" else "sandbox-structure-json",
            blocks = blocks,
        )
    }

    private fun JsonObject.parseStructurePalette(location: SourceLocation?): List<BlockArgument>? {
        val value = get("palette") ?: getAsJsonArrayOrNull("palettes")?.firstOrNull() ?: return null
        val paletteArray =
            when {
                value.isJsonArray -> value.asJsonArray
                value.isJsonObject ->
                    value.asJsonObject.getAsJsonArrayOrNull("palette")
                        ?: throw SandboxException(
                            DiagnosticCode.INPUT_FORMAT,
                            "Structure palette object must contain palette array",
                            location,
                        )
                else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Structure palette must be an array", location)
            }
        return paletteArray.mapIndexed { index, element ->
            element.parseStructureProcessorBlockArgument("structure palette entry $index", location)
        }
    }

    private fun JsonObject.parsePaletteStructureBlock(
        index: Int,
        palette: List<BlockArgument>,
        location: SourceLocation?,
    ): BlockArgument {
        val stateIndex =
            get("state")?.placeInt("structure block $index state", location)
                ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "structure block $index state is required", location)
        val base =
            palette.getOrNull(stateIndex)
                ?: throw SandboxException(
                    DiagnosticCode.INPUT_FORMAT,
                    "structure block $index state index $stateIndex is outside palette size ${palette.size}",
                    location,
                )
        return base.withStructureBlockNbt(getAsJsonObjectOrNull("nbt", location))
    }

    private fun JsonObject.parseSandboxStructureBlock(
        index: Int,
        location: SourceLocation?,
    ): BlockArgument {
        val state = get("state")?.takeUnless { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
        val base =
            state?.parseStructureProcessorBlockArgument("structure block $index state", location)
                ?: BlockArgument(
                    id = ResourceLocation.parse(requiredPlaceString("id", "structure block $index id", location)),
                    properties = placeProperties(location),
                    nbt = JsonObject(),
                )
        return base.withStructureBlockNbt(placeJsonObject("nbt", "structure block $index nbt", location))
    }

    private fun BlockArgument.withStructureBlockNbt(extra: JsonObject?): BlockArgument {
        if (extra == null) return this
        val merged = nbt.deepCopy()
        JsonPaths.merge(merged, null, extra.deepCopy())
        return copy(nbt = merged)
    }

    private fun parseStructureProcessors(
        root: JsonObject,
        location: SourceLocation?,
    ): SandboxStructureProcessorList {
        val value = root.get("processors") ?: root.get("processor_list") ?: return SandboxStructureProcessorList.empty
        return parseStructureProcessorSource(value, "structure processors", location)
    }

    private fun parseStructureProcessorSource(
        value: JsonElement,
        label: String,
        location: SourceLocation?,
    ): SandboxStructureProcessorList =
        when {
            value.isJsonPrimitive -> {
                val id = ResourceLocation.parse(value.asJsonPrimitive.asString)
                val resource =
                    datapack.rawResources["worldgen/processor_list"]?.get(id)
                        ?: throw SandboxException(
                            DiagnosticCode.RESOURCE_NOT_FOUND,
                            "Processor list '$id' referenced by structure was not found",
                            location,
                        )
                if (!resource.root.isJsonObject) {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Processor list resource '$id' must be a JSON object", location)
                }
                parseStructureProcessorListObject(resource.root.asJsonObject, "$label $id", location, listOf(id))
            }
            value.isJsonObject -> parseStructureProcessorListObject(value.asJsonObject, label, location)
            value.isJsonArray ->
                SandboxStructureProcessorList(
                    ids = emptyList(),
                    processors =
                        value.asJsonArray.mapIndexed { index, element ->
                            element
                                .asPlaceJsonObject(
                                    "$label entry $index",
                                    location,
                                ).parseStructureProcessor("$label entry $index", location)
                        },
                )
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must be a resource id, object, or array", location)
        }

    private fun parseStructureProcessorListObject(
        root: JsonObject,
        label: String,
        location: SourceLocation?,
        ids: List<ResourceLocation> = emptyList(),
    ): SandboxStructureProcessorList {
        val processors = root.getAsJsonArrayOrNull("processors")
        if (processors != null) {
            return SandboxStructureProcessorList(
                ids = ids,
                processors =
                    processors.mapIndexed { index, element ->
                        element
                            .asPlaceJsonObject(
                                "$label processor $index",
                                location,
                            ).parseStructureProcessor("$label processor $index", location)
                    },
            )
        }
        return SandboxStructureProcessorList(
            ids = ids,
            processors = listOf(root.parseStructureProcessor(label, location)),
        )
    }

    private fun JsonObject.parseStructureProcessor(
        label: String,
        location: SourceLocation?,
    ): SandboxStructureProcessor {
        val type =
            placeString("processor_type", location) ?: placeString("type", location)
                ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label processor type is required", location)
        return when (type.removePrefix("minecraft:")) {
            "block_ignore" ->
                SandboxStructureProcessor(
                    type = type,
                    ignoredBlocks = processorBlockIds("blocks", "block", "$label block_ignore blocks", location),
                )
            "protected_blocks" ->
                SandboxStructureProcessor(
                    type = type,
                    protectedBlocks = protectedProcessorBlockIds("$label protected_blocks", location),
                )
            "rule" ->
                SandboxStructureProcessor(
                    type = type,
                    rules =
                        (getAsJsonArrayOrNull("rules") ?: JsonArray()).mapIndexed { index, element ->
                            element
                                .asPlaceJsonObject(
                                    "$label rule $index",
                                    location,
                                ).parseStructureProcessorRule("$label rule $index", location)
                        },
                )
            "jigsaw_replacement" -> SandboxStructureProcessor(type = type, jigsawReplacement = true)
            "nop" -> SandboxStructureProcessor(type = type)
            "capped" -> {
                val delegate =
                    getAsJsonObjectOrNull("delegate", location)
                        ?: getAsJsonObjectOrNull("processor", location)
                        ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label capped delegate is required", location)
                val limit =
                    get("limit")?.featureIntProvider("$label capped limit", location)
                        ?: get("max")?.featureIntProvider("$label capped max", location)
                        ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label capped limit is required", location)
                SandboxStructureProcessor(
                    type = type,
                    cappedDelegate = delegate.parseStructureProcessor("$label capped delegate", location),
                    cappedLimit = limit.coerceIn(0, 32768),
                )
            }
            else -> SandboxStructureProcessor(type = type, supported = false)
        }
    }

    private fun JsonObject.parseStructureProcessorRule(
        label: String,
        location: SourceLocation?,
    ): SandboxStructureProcessorRule {
        val input = get("input_predicate") ?: get("input")
        val output =
            get("output_state") ?: get("output") ?: get("block")
                ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label output_state is required", location)
        return SandboxStructureProcessorRule(
            inputBlocks = input?.structureProcessorPredicateBlocks("$label input_predicate", location),
            output = output.parseStructureProcessorBlockArgument("$label output_state", location),
        )
    }

    private fun JsonElement.structureProcessorPredicateBlocks(
        label: String,
        location: SourceLocation?,
    ): Set<ResourceLocation>? =
        when {
            isJsonPrimitive -> setOf(ResourceLocation.parse(asJsonPrimitive.asString))
            isJsonObject -> asJsonObject.structureProcessorPredicateBlocks(label, location)
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must be a block id string or object", location)
        }

    private fun JsonObject.structureProcessorPredicateBlocks(
        label: String,
        location: SourceLocation?,
    ): Set<ResourceLocation>? {
        val type = placeString("predicate_type", location) ?: placeString("type", location) ?: "minecraft:always_true"
        return when (type.removePrefix("minecraft:")) {
            "always_true" -> null
            "matching_blocks", "block_match" -> processorBlockIds("blocks", "block", "$label blocks", location)
            "blockstate_match" -> {
                val state =
                    get("block_state") ?: get("state") ?: get("block")
                        ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label blockstate_match requires block_state", location)
                setOf(state.parseStructureProcessorBlockArgument("$label block_state", location).id)
            }
            "matching_block_tag", "tag_match" -> {
                val tag =
                    placeString("tag", location) ?: placeString("value", location)
                        ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label tag_match tag is required", location)
                blockTagBlocks(ResourceLocation.parse(tag))
            }
            "random_block_match" -> {
                if (placeDouble("probability", 1.0, "$label probability", location) <= 0.0) {
                    emptySet()
                } else {
                    processorBlockIds("blocks", "block", "$label blocks", location)
                }
            }
            "random_blockstate_match" -> {
                if (placeDouble("probability", 1.0, "$label probability", location) <= 0.0) {
                    emptySet()
                } else {
                    val state =
                        get("block_state") ?: get("state") ?: get("block")
                            ?: throw SandboxException(
                                DiagnosticCode.INPUT_FORMAT,
                                "$label random_blockstate_match requires block_state",
                                location,
                            )
                    setOf(state.parseStructureProcessorBlockArgument("$label block_state", location).id)
                }
            }
            else -> emptySet()
        }
    }

    private fun JsonObject.protectedProcessorBlockIds(
        label: String,
        location: SourceLocation?,
    ): Set<ResourceLocation> {
        val tag = placeString("value", location) ?: placeString("tag", location)
        if (tag != null) return blockTagBlocks(ResourceLocation.parse(tag))
        return processorBlockIds("blocks", "block", "$label blocks", location)
    }

    private fun JsonObject.processorBlockIds(
        primary: String,
        secondary: String,
        label: String,
        location: SourceLocation?,
    ): Set<ResourceLocation> {
        val value = get(primary) ?: get(secondary) ?: return emptySet()
        return when {
            value.isJsonPrimitive -> setOf(ResourceLocation.parse(value.asJsonPrimitive.asString))
            value.isJsonArray ->
                value.asJsonArray
                    .mapIndexed { index, element ->
                        if (!element.isJsonPrimitive) {
                            throw SandboxException(
                                DiagnosticCode.INPUT_FORMAT,
                                "$label entry $index must be a resource id string",
                                location,
                            )
                        }
                        ResourceLocation.parse(element.asJsonPrimitive.asString)
                    }.toSet()
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must be a resource id string or array", location)
        }
    }

    private fun JsonElement.parseStructureProcessorBlockArgument(
        label: String,
        location: SourceLocation?,
    ): BlockArgument =
        when {
            isJsonPrimitive -> BlockArgument(ResourceLocation.parse(asJsonPrimitive.asString))
            isJsonObject -> asJsonObject.parseBlockStateObject(label, location)
            else -> throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must be a block id string or state object", location)
        }

    private fun applyStructureProcessors(
        block: BlockArgument,
        destinationId: ResourceLocation?,
        processors: List<SandboxStructureProcessor>,
        location: SourceLocation?,
    ): SandboxProcessedStructureBlock {
        var current = block
        var processed = false
        processors.forEach { processor ->
            val result =
                if (processor.jigsawReplacement) {
                    applyJigsawReplacementProcessor(current, location)
                } else {
                    processor.apply(current, destinationId)
                }
            if (result.processed) processed = true
            current = result.block ?: return SandboxProcessedStructureBlock(block = null, processed = true)
        }
        return SandboxProcessedStructureBlock(current, processed)
    }

    private fun applyJigsawReplacementProcessor(
        block: BlockArgument,
        location: SourceLocation?,
    ): SandboxProcessedStructureBlock {
        if (block.id != ResourceLocation("minecraft", "jigsaw")) return SandboxProcessedStructureBlock(block)
        val finalState =
            block.nbt.get("final_state")
                ?: block.nbt.get("FinalState")
                ?: return SandboxProcessedStructureBlock(block = null, processed = true)
        if (!finalState.isJsonPrimitive) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "jigsaw final_state must be a block string", location)
        }
        val replacement = parseBlockArgument(finalState.asJsonPrimitive.asString, location)
        return SandboxProcessedStructureBlock(block = replacement, processed = true)
    }

    private fun parseTemplatePlacement(
        extras: List<String>,
        payload: JsonObject,
        location: SourceLocation?,
    ): SandboxStructurePlacement {
        val rotation = extras.getOrNull(0) ?: "none"
        val mirror = extras.getOrNull(1) ?: "none"
        val integrity = extras.getOrNull(2)?.let { parseDouble(it, "template integrity", location).coerceIn(0.0, 1.0) } ?: 1.0
        val seed = extras.getOrNull(3)?.let { parseLong(it, "template seed", location) }
        if (rotation !in SandboxStructurePlacement.rotations) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Unsupported template rotation '$rotation'", location)
        }
        if (mirror !in SandboxStructurePlacement.mirrors) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Unsupported template mirror '$mirror'", location)
        }
        payload.addProperty("rotation", rotation)
        payload.addProperty("mirror", mirror)
        payload.addProperty("integrity", integrity)
        seed?.let { payload.addProperty("seed", it) }
        return SandboxStructurePlacement(rotation = rotation, mirror = mirror, integrity = integrity, seed = seed)
    }

    private fun JsonObject.getAsJsonArrayOrNull(name: String): JsonArray? {
        val value = get(name) ?: return null
        if (!value.isJsonArray) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Structure field '$name' must be an array")
        }
        return value.asJsonArray
    }

    private fun JsonObject.getAsJsonObjectOrNull(
        name: String,
        location: SourceLocation? = null,
    ): JsonObject? {
        val value = get(name) ?: return null
        if (!value.isJsonObject) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "JSON field '$name' must be an object", location)
        }
        return value.asJsonObject
    }

    private fun JsonElement.asPlaceJsonObject(
        label: String,
        location: SourceLocation?,
    ): JsonObject {
        if (!isJsonObject) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must be an object", location)
        return asJsonObject
    }

    private fun JsonObject.requiredPlaceString(
        name: String,
        label: String,
        location: SourceLocation?,
    ): String = placeString(name, location) ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label is required", location)

    private fun JsonObject.placeString(
        name: String,
        location: SourceLocation?,
    ): String? {
        val value = get(name) ?: return null
        if (!value.isJsonPrimitive) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "Structure field '$name' must be a primitive",
                location,
            )
        }
        return value.asJsonPrimitive.asString
    }

    private fun JsonObject.placeProperties(location: SourceLocation?): Map<String, String> {
        val value = get("properties") ?: return emptyMap()
        if (!value.isJsonObject) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "Structure block properties must be an object",
                location,
            )
        }
        return value.asJsonObject.entrySet().associate { (key, property) ->
            if (!property.isJsonPrimitive) {
                throw SandboxException(
                    DiagnosticCode.INPUT_FORMAT,
                    "Structure block property '$key' must be a primitive",
                    location,
                )
            }
            key to property.asJsonPrimitive.asString
        }
    }

    private fun JsonObject.placeJsonObject(
        name: String,
        label: String,
        location: SourceLocation?,
    ): JsonObject? {
        val value = get(name) ?: return null
        if (!value.isJsonObject) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must be an object", location)
        return value.asJsonObject
    }

    private fun JsonObject.placeBlockPos(
        primary: String,
        secondary: String,
        label: String,
        location: SourceLocation?,
    ): BlockPos {
        val value = get(primary) ?: get(secondary) ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label is required", location)
        if (!value.isJsonArray) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must be [x,y,z]", location)
        val array = value.asJsonArray
        if (array.size() != 3) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must have exactly 3 entries", location)
        return BlockPos(
            array[0].placeInt("$label x", location),
            array[1].placeInt("$label y", location),
            array[2].placeInt("$label z", location),
        )
    }

    private fun JsonObject.optionalPlacePosition(
        primary: String,
        secondary: String,
        label: String,
        location: SourceLocation?,
    ): Position {
        val value = get(primary) ?: get(secondary) ?: return Position.zero
        if (!value.isJsonArray) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must be [x,y,z]", location)
        val array = value.asJsonArray
        if (array.size() != 3) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must have exactly 3 entries", location)
        return Position(
            array[0].placeDouble("$label x", location),
            array[1].placeDouble("$label y", location),
            array[2].placeDouble("$label z", location),
        )
    }

    private fun JsonObject.placeStringArray(
        name: String,
        label: String,
        location: SourceLocation?,
    ): List<String> {
        val value = get(name) ?: return emptyList()
        if (!value.isJsonArray) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must be an array", location)
        return value.asJsonArray.mapIndexed { index, element ->
            if (!element.isJsonPrimitive) {
                throw SandboxException(
                    DiagnosticCode.INPUT_FORMAT,
                    "$label entry $index must be a primitive",
                    location,
                )
            }
            element.asJsonPrimitive.asString
        }
    }

    private fun JsonObject.placeDouble(
        name: String,
        default: Double,
        label: String,
        location: SourceLocation?,
    ): Double = get(name)?.placeDouble(label, location) ?: default

    private fun JsonObject.placeInt(
        name: String,
        default: Int,
        label: String,
        location: SourceLocation?,
    ): Int = get(name)?.placeInt(label, location) ?: default

    private fun JsonElement.placeInt(
        label: String,
        location: SourceLocation?,
    ): Int =
        try {
            asInt
        } catch (error: Exception) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must be an integer", location, cause = error)
        }

    private fun JsonElement.placeDouble(
        label: String,
        location: SourceLocation?,
    ): Double =
        try {
            asDouble
        } catch (error: Exception) {
            throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label must be a number", location, cause = error)
        }
}
