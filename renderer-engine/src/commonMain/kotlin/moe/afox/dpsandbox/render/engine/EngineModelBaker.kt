package moe.afox.dpsandbox.render.engine

internal class EngineModelBaker(
    private val assets: EngineTextureStore,
    private val occupiedBlocks: Set<String>,
    private val worldSeed: Long,
) {
    private val modelCache = mutableMapOf<String, ResolvedModel?>()

    fun bake(block: EngineRenderBlock): List<EngineSceneTriangle> {
        val refs = resolveBlockState(block)
        return refs.flatMap { bakeModel(it, block) }
    }

    private fun resolveBlockState(block: EngineRenderBlock): List<ModelRef> {
        val (namespace, path) = splitResourceId(block.id)
        val state = assets.json("assets/$namespace/blockstates/$path.json") ?: return listOf(ModelRef("$namespace:block/$path"))
        val refs = mutableListOf<ModelRef>()
        state.objectValue("variants")?.values
            ?.filter { (condition, _) -> variantMatches(condition, block.properties) }
            ?.maxByOrNull { (condition, _) -> condition.count { it == '=' } }
            ?.value
            ?.let { refs += selectVariant(it, block) }
        state.arrayValue("multipart")?.forEach { raw ->
            val part = raw as? EngineJsonObject ?: return@forEach
            val condition = part.values["when"]
            if (condition == null || multipartMatches(condition, block.properties)) {
                part.values["apply"]?.let { refs += selectVariant(it, block) }
            }
        }
        return refs.ifEmpty { listOf(ModelRef("$namespace:block/$path")) }
    }

    private fun selectVariant(
        value: EngineJsonValue,
        block: EngineRenderBlock,
    ): ModelRef {
        if (value is EngineJsonObject) return parseModelRef(value)
        val refs = (value as? EngineJsonArray)?.values?.mapNotNull { (it as? EngineJsonObject)?.let(::parseModelRef) }.orEmpty()
        if (refs.isEmpty()) return ModelRef("minecraft:block/missing")
        val totalWeight = refs.sumOf { it.weight.coerceAtLeast(1) }
        var selected = stableVariant(block, totalWeight)
        refs.forEach { ref ->
            selected -= ref.weight.coerceAtLeast(1)
            if (selected < 0) return ref
        }
        return refs.last()
    }

    private fun stableVariant(
        block: EngineRenderBlock,
        totalWeight: Int,
    ): Int {
        var hash = 17L
        hash = hash * 31 + worldSeed
        hash = hash * 31 + block.x
        hash = hash * 31 + block.y
        hash = hash * 31 + block.z
        hash = hash * 31 + block.id.hashCode()
        return (((hash % totalWeight) + totalWeight) % totalWeight).toInt()
    }

    private fun parseModelRef(json: EngineJsonObject): ModelRef =
        ModelRef(
            id = json.string("model") ?: "minecraft:block/missing",
            xRotation = json.int("x") ?: 0,
            yRotation = json.int("y") ?: 0,
            uvLock = json.boolean("uvlock") ?: false,
            weight = json.int("weight") ?: 1,
        )

    private fun bakeModel(
        ref: ModelRef,
        block: EngineRenderBlock,
    ): List<EngineSceneTriangle> {
        val modelId = normalizeResourceId(ref.id)
        val model = loadModel(modelId, linkedSetOf())
        if (model == null || model.elements.isEmpty()) return fallbackCube(block)
        val offset = EngineVec3(block.x.toDouble(), block.y.toDouble(), block.z.toDouble())
        return buildList {
            model.elements.forEach { element ->
                val from = element.from * (1.0 / 16.0)
                val to = element.to * (1.0 / 16.0)
                element.faces.forEach { (direction, face) ->
                    if (face.cullFace != null) {
                        val neighbor = neighbor(block, face.cullFace)
                        if (blockKey(neighbor.first, neighbor.second, neighbor.third) in occupiedBlocks) return@forEach
                    }
                    val positions =
                        faceVertices(direction, from, to).map { raw ->
                            var value = raw
                            element.rotation?.let { rotation ->
                                value = value.rotateAround(rotation.origin * (1.0 / 16.0), rotation.axis, rotation.angle)
                            }
                            if (ref.xRotation != 0) value = value.rotateAround(BLOCK_CENTER, 'x', ref.xRotation.toDouble())
                            if (ref.yRotation != 0) value = value.rotateAround(BLOCK_CENTER, 'y', -ref.yRotation.toDouble())
                            value + offset
                        }
                    val texture = assets.texture(resolveTexture(face.texture, model.textures))
                    val uv =
                        rotatedUv(
                            face.rotation + if (ref.uvLock) ref.yRotation else 0,
                            face.uv ?: defaultFaceUv(direction, element.from, element.to),
                        )
                    val tint = if (face.tintIndex != null) DEFAULT_BIOME_TINT else -1
                    add(
                        EngineSceneTriangle(
                            EngineSceneVertex(positions[0], uv[0]),
                            EngineSceneVertex(positions[1], uv[1]),
                            EngineSceneVertex(positions[2], uv[2]),
                            texture,
                            tint,
                            emissive = !element.shade,
                        ),
                    )
                    add(
                        EngineSceneTriangle(
                            EngineSceneVertex(positions[0], uv[0]),
                            EngineSceneVertex(positions[2], uv[2]),
                            EngineSceneVertex(positions[3], uv[3]),
                            texture,
                            tint,
                            emissive = !element.shade,
                        ),
                    )
                }
            }
        }
    }

    private fun loadModel(
        rawId: String,
        stack: MutableSet<String>,
    ): ResolvedModel? {
        val id = normalizeResourceId(rawId)
        if (id in modelCache) return modelCache[id]
        if (!stack.add(id)) return null
        val (namespace, path) = splitResourceId(id)
        val json = assets.json("assets/$namespace/models/$path.json")
        if (json == null) {
            modelCache[id] = null
            stack.remove(id)
            return null
        }
        val parent = json.string("parent")?.let { loadModel(it, stack) }
        val textures = linkedMapOf<String, String>()
        parent?.textures?.let(textures::putAll)
        json.objectValue("textures")?.values?.forEach { (name, value) ->
            (value as? EngineJsonString)?.value?.let { textures[name] = it }
        }
        val elements = json.arrayValue("elements")?.mapNotNull(::parseElement) ?: parent?.elements.orEmpty()
        return ResolvedModel(textures, elements).also {
            modelCache[id] = it
            stack.remove(id)
        }
    }

    private fun parseElement(raw: EngineJsonValue): ModelElement? {
        val json = raw as? EngineJsonObject ?: return null
        val from = json.vector("from") ?: return null
        val to = json.vector("to") ?: return null
        val rotation =
            json.objectValue("rotation")?.let { value ->
                val origin = value.vector("origin") ?: EngineVec3(8.0, 8.0, 8.0)
                val axis = value.string("axis")?.singleOrNull() ?: 'y'
                val angle = value.number("angle") ?: 0.0
                if (axis in "xyz" && angle.isFinite()) ElementRotation(origin, axis, angle) else null
            }
        val faces = linkedMapOf<String, ModelFace>()
        json.objectValue("faces")?.values?.forEach { (direction, rawFace) ->
            if (direction !in DIRECTIONS) return@forEach
            val face = rawFace as? EngineJsonObject ?: return@forEach
            val texture = face.string("texture") ?: return@forEach
            faces[direction] =
                ModelFace(
                    texture = texture,
                    rotation = face.int("rotation") ?: 0,
                    tintIndex = face.int("tintindex"),
                    uv = face.numberArray("uv", 4),
                    cullFace = face.string("cullface")?.takeIf { it in DIRECTIONS },
                )
        }
        return ModelElement(from, to, rotation, faces, json.boolean("shade") ?: true)
    }

    private fun fallbackCube(block: EngineRenderBlock): List<EngineSceneTriangle> {
        val (namespace, path) = splitResourceId(block.id)
        val texture = assets.texture("$namespace:block/$path")
        val offset = EngineVec3(block.x.toDouble(), block.y.toDouble(), block.z.toDouble())
        return DIRECTIONS.flatMap { direction ->
            val positions = faceVertices(direction, EngineVec3.ZERO, EngineVec3(1.0, 1.0, 1.0)).map { it + offset }
            val uv = rotatedUv(0, listOf(0.0, 0.0, 16.0, 16.0))
            listOf(
                EngineSceneTriangle(EngineSceneVertex(positions[0], uv[0]), EngineSceneVertex(positions[1], uv[1]), EngineSceneVertex(positions[2], uv[2]), texture),
                EngineSceneTriangle(EngineSceneVertex(positions[0], uv[0]), EngineSceneVertex(positions[2], uv[2]), EngineSceneVertex(positions[3], uv[3]), texture),
            )
        }
    }

    private fun resolveTexture(
        raw: String,
        textures: Map<String, String>,
    ): String {
        var value = raw
        val visited = mutableSetOf<String>()
        while (value.startsWith('#')) {
            val key = value.removePrefix("#")
            if (!visited.add(key)) return "minecraft:block/missing"
            value = textures[key] ?: return "minecraft:block/missing"
        }
        return normalizeResourceId(value)
    }

    private fun variantMatches(
        condition: String,
        properties: Map<String, String>,
    ): Boolean {
        if (condition.isBlank()) return true
        return condition.split(',').all { entry ->
            val key = entry.substringBefore('=').trim()
            properties[key] in entry.substringAfter('=', "").split('|')
        }
    }

    private fun multipartMatches(
        raw: EngineJsonValue,
        properties: Map<String, String>,
    ): Boolean {
        val json = raw as? EngineJsonObject ?: return false
        json.arrayValue("OR")?.let { return it.any { item -> multipartMatches(item, properties) } }
        json.arrayValue("AND")?.let { return it.all { item -> multipartMatches(item, properties) } }
        return json.values.all { (name, value) -> properties[name] in ((value as? EngineJsonString)?.value?.split('|').orEmpty()) }
    }

    private fun faceVertices(
        direction: String,
        minimum: EngineVec3,
        maximum: EngineVec3,
    ): List<EngineVec3> =
        when (direction) {
            "west" -> listOf(EngineVec3(minimum.x, minimum.y, minimum.z), EngineVec3(minimum.x, minimum.y, maximum.z), EngineVec3(minimum.x, maximum.y, maximum.z), EngineVec3(minimum.x, maximum.y, minimum.z))
            "east" -> listOf(EngineVec3(maximum.x, minimum.y, maximum.z), EngineVec3(maximum.x, minimum.y, minimum.z), EngineVec3(maximum.x, maximum.y, minimum.z), EngineVec3(maximum.x, maximum.y, maximum.z))
            "down" -> listOf(EngineVec3(minimum.x, minimum.y, maximum.z), EngineVec3(minimum.x, minimum.y, minimum.z), EngineVec3(maximum.x, minimum.y, minimum.z), EngineVec3(maximum.x, minimum.y, maximum.z))
            "up" -> listOf(EngineVec3(minimum.x, maximum.y, minimum.z), EngineVec3(minimum.x, maximum.y, maximum.z), EngineVec3(maximum.x, maximum.y, maximum.z), EngineVec3(maximum.x, maximum.y, minimum.z))
            "north" -> listOf(EngineVec3(maximum.x, minimum.y, minimum.z), EngineVec3(minimum.x, minimum.y, minimum.z), EngineVec3(minimum.x, maximum.y, minimum.z), EngineVec3(maximum.x, maximum.y, minimum.z))
            else -> listOf(EngineVec3(minimum.x, minimum.y, maximum.z), EngineVec3(maximum.x, minimum.y, maximum.z), EngineVec3(maximum.x, maximum.y, maximum.z), EngineVec3(minimum.x, maximum.y, maximum.z))
        }

    private fun rotatedUv(
        rotation: Int,
        values: List<Double>,
    ): List<EngineVec2> {
        val base =
            listOf(
                EngineVec2(values[0] / 16.0, values[3] / 16.0),
                EngineVec2(values[2] / 16.0, values[3] / 16.0),
                EngineVec2(values[2] / 16.0, values[1] / 16.0),
                EngineVec2(values[0] / 16.0, values[1] / 16.0),
            )
        val turns = floorMod(rotation, 360) / 90
        return List(4) { index -> base[floorMod(index - turns, 4)] }
    }

    private fun defaultFaceUv(
        direction: String,
        from: EngineVec3,
        to: EngineVec3,
    ): List<Double> =
        when (direction) {
            "down" -> listOf(from.x, 16.0 - to.z, to.x, 16.0 - from.z)
            "up" -> listOf(from.x, from.z, to.x, to.z)
            "north" -> listOf(16.0 - to.x, 16.0 - to.y, 16.0 - from.x, 16.0 - from.y)
            "south" -> listOf(from.x, 16.0 - to.y, to.x, 16.0 - from.y)
            "west" -> listOf(from.z, 16.0 - to.y, to.z, 16.0 - from.y)
            else -> listOf(16.0 - to.z, 16.0 - to.y, 16.0 - from.z, 16.0 - from.y)
        }

    private fun neighbor(
        block: EngineRenderBlock,
        direction: String,
    ): Triple<Int, Int, Int> =
        when (direction) {
            "west" -> Triple(block.x - 1, block.y, block.z)
            "east" -> Triple(block.x + 1, block.y, block.z)
            "down" -> Triple(block.x, block.y - 1, block.z)
            "up" -> Triple(block.x, block.y + 1, block.z)
            "north" -> Triple(block.x, block.y, block.z - 1)
            else -> Triple(block.x, block.y, block.z + 1)
        }

    private fun normalizeResourceId(id: String): String = if (':' in id) id else "minecraft:$id"

    private fun splitResourceId(id: String): Pair<String, String> =
        if (':' in id) id.substringBefore(':') to id.substringAfter(':') else "minecraft" to id

    private fun blockKey(
        x: Int,
        y: Int,
        z: Int,
    ): String = "$x,$y,$z"

    private fun floorMod(
        value: Int,
        divisor: Int,
    ): Int = ((value % divisor) + divisor) % divisor

    private data class ModelRef(
        val id: String,
        val xRotation: Int = 0,
        val yRotation: Int = 0,
        val uvLock: Boolean = false,
        val weight: Int = 1,
    )

    private data class ResolvedModel(
        val textures: Map<String, String>,
        val elements: List<ModelElement>,
    )

    private data class ModelElement(
        val from: EngineVec3,
        val to: EngineVec3,
        val rotation: ElementRotation?,
        val faces: Map<String, ModelFace>,
        val shade: Boolean,
    )

    private data class ElementRotation(
        val origin: EngineVec3,
        val axis: Char,
        val angle: Double,
    )

    private data class ModelFace(
        val texture: String,
        val rotation: Int,
        val tintIndex: Int?,
        val uv: List<Double>?,
        val cullFace: String?,
    )

    companion object {
        private val DIRECTIONS = listOf("west", "east", "down", "up", "north", "south")
        private val BLOCK_CENTER = EngineVec3(0.5, 0.5, 0.5)
        private const val DEFAULT_BIOME_TINT = 0xff78a94b.toInt()
    }
}
