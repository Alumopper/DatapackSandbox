package moe.afox.dpsandbox.render

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import moe.afox.dpsandbox.core.BlockPos

internal class ModelBaker(
    private val resolver: AssetResolver,
    private val diagnostics: MutableList<RenderDiagnostic>,
    private val occupiedBlocks: Set<BlockPos>,
    private val worldSeed: Long,
    private val biomes: Map<BlockPos, String>,
) {
    private val modelCache = mutableMapOf<String, ResolvedModel?>()

    fun bake(block: RenderBlock): List<SceneTriangle> {
        val refs = resolveBlockState(block)
        return refs.flatMap { ref -> bakeModel(ref, block) }
    }

    private fun resolveBlockState(block: RenderBlock): List<ModelRef> {
        val (namespace, path) = splitResourceId(block.id)
        val key = "assets/$namespace/blockstates/$path.json"
        val state = resolver.json(key)
        if (state == null) {
            resolver.missing("MISSING_BLOCKSTATE", "Missing blockstate for ${block.id}; using direct model fallback", key)
            return listOf(ModelRef("$namespace:block/$path"))
        }

        val refs = mutableListOf<ModelRef>()
        state.getAsJsonObjectOrNull("variants")?.let { variants ->
            val matches =
                variants
                    .entrySet()
                    .filter { (condition, _) -> variantMatches(condition, block.properties) }
                    .sortedByDescending { (condition, _) -> condition.count { it == '=' } }
            matches.firstOrNull()?.value?.let { value -> refs += selectVariant(value, block) }
        }
        state.getAsJsonArrayOrNull("multipart")?.forEach { element ->
            if (!element.isJsonObject) return@forEach
            val part = element.asJsonObject
            val whenObject = part.get("when")
            if (whenObject == null || multipartMatches(whenObject, block.properties)) {
                part.get("apply")?.let { refs += selectVariant(it, block) }
            }
        }
        if (refs.isEmpty()) {
            resolver.missing("UNMATCHED_BLOCKSTATE", "No blockstate variant matched ${block.id}${block.properties}", key)
            refs += ModelRef("$namespace:block/$path")
        }
        return refs
    }

    private fun selectVariant(
        value: JsonElement,
        block: RenderBlock,
    ): ModelRef {
        if (value.isJsonObject) return parseModelRef(value.asJsonObject)
        if (!value.isJsonArray || value.asJsonArray.size() == 0) {
            resolver.invalid("INVALID_BLOCKSTATE_VARIANT", "Blockstate variant must be an object or non-empty array", block.id)
            return ModelRef("minecraft:block/missing")
        }
        val refs = value.asJsonArray.mapNotNull { it.takeIf { candidate -> candidate.isJsonObject }?.asJsonObject?.let(::parseModelRef) }
        if (refs.isEmpty()) return ModelRef("minecraft:block/missing")
        val totalWeight = refs.sumOf { it.weight.coerceAtLeast(1) }
        val selected = stableVariant(block, totalWeight)
        var cursor = 0
        refs.forEach { ref ->
            cursor += ref.weight.coerceAtLeast(1)
            if (selected < cursor) return ref
        }
        return refs.last()
    }

    private fun stableVariant(
        block: RenderBlock,
        totalWeight: Int,
    ): Int {
        var hash = 17L
        hash = hash * 31 + worldSeed
        hash = hash * 31 + block.position.x
        hash = hash * 31 + block.position.y
        hash = hash * 31 + block.position.z
        hash = hash * 31 + block.id.hashCode()
        return Math.floorMod(hash, totalWeight.toLong()).toInt()
    }

    private fun parseModelRef(json: JsonObject): ModelRef =
        ModelRef(
            id = json.string("model") ?: "minecraft:block/missing",
            xRotation = json.int("x") ?: 0,
            yRotation = json.int("y") ?: 0,
            uvLock = json.boolean("uvlock") ?: false,
            weight = json.int("weight") ?: 1,
        )

    private fun bakeModel(
        ref: ModelRef,
        block: RenderBlock,
    ): List<SceneTriangle> {
        val modelId = normalizeResourceId(ref.id)
        val model = loadModel(modelId, linkedSetOf())
        if (model == null || model.elements.isEmpty()) return fallbackCube(block, ref)
        val offset = Vec3(block.position.x.toDouble(), block.position.y.toDouble(), block.position.z.toDouble())
        return buildList {
            model.elements.forEach { element ->
                val from = element.from * (1.0 / 16.0)
                val to = element.to * (1.0 / 16.0)
                element.faces.forEach { (direction, face) ->
                    if (face.cullFace != null && neighbor(block.position, face.cullFace) in occupiedBlocks) {
                        return@forEach
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
                    val textureId = resolveTexture(face.texture, model.textures, modelId)
                    val texture = resolver.texture(textureId)
                    val uv =
                        rotatedUv(
                            face.rotation + if (ref.uvLock) ref.yRotation else 0,
                            face.uv ?: defaultFaceUv(direction, element.from, element.to),
                        )
                    val tint = if (face.tintIndex != null) biomeTint(block.position) else 0xffffffff.toInt()
                    add(
                        SceneTriangle(
                            SceneVertex(positions[0], uv[0]),
                            SceneVertex(positions[1], uv[1]),
                            SceneVertex(positions[2], uv[2]),
                            texture,
                            tint,
                            emissive = !element.shade,
                        ),
                    )
                    add(
                        SceneTriangle(
                            SceneVertex(positions[0], uv[0]),
                            SceneVertex(positions[2], uv[2]),
                            SceneVertex(positions[3], uv[3]),
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
        if (!stack.add(id)) {
            resolver.invalid("MODEL_PARENT_CYCLE", "Model parent cycle: ${stack.joinToString(" -> ")} -> $id", id)
            return null
        }
        val (namespace, path) = splitResourceId(id)
        val key = "assets/$namespace/models/$path.json"
        val json = resolver.json(key)
        if (json == null) {
            resolver.missing("MISSING_MODEL", "Missing model $id", key)
            modelCache[id] = null
            stack.remove(id)
            return null
        }
        val parent = json.string("parent")?.let { loadModel(it, stack) }
        val textures = linkedMapOf<String, String>()
        parent?.textures?.let(textures::putAll)
        json.getAsJsonObjectOrNull("textures")?.entrySet()?.forEach { (name, value) ->
            if (value.isJsonPrimitive && value.asJsonPrimitive.isString) textures[name] = value.asString
        }
        val elements =
            json.getAsJsonArrayOrNull("elements")?.mapNotNull { parseElement(it, key) }
                ?: parent?.elements
                ?: emptyList()
        val resolved = ResolvedModel(textures, elements)
        modelCache[id] = resolved
        stack.remove(id)
        return resolved
    }

    private fun parseElement(
        raw: JsonElement,
        resource: String,
    ): ModelElement? {
        if (!raw.isJsonObject) return null
        val json = raw.asJsonObject
        val from = json.vector("from") ?: return null
        val to = json.vector("to") ?: return null
        val rotation =
            json.getAsJsonObjectOrNull("rotation")?.let { value ->
                val origin = value.vector("origin") ?: Vec3(8.0, 8.0, 8.0)
                val axis = value.string("axis")?.singleOrNull() ?: 'y'
                val angle = value.double("angle") ?: 0.0
                if (axis !in "xyz" || !angle.isFinite()) {
                    resolver.invalid("INVALID_MODEL_ROTATION", "Invalid element rotation", resource)
                    null
                } else {
                    ElementRotation(origin, axis, angle)
                }
            }
        val faces = linkedMapOf<String, ModelFace>()
        json.getAsJsonObjectOrNull("faces")?.entrySet()?.forEach { (direction, faceValue) ->
            if (direction !in DIRECTIONS || !faceValue.isJsonObject) return@forEach
            val face = faceValue.asJsonObject
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

    private fun fallbackCube(
        block: RenderBlock,
        ref: ModelRef,
    ): List<SceneTriangle> {
        val (namespace, path) = splitResourceId(block.id)
        val texture = resolver.texture("$namespace:block/$path")
        val offset = Vec3(block.position.x.toDouble(), block.position.y.toDouble(), block.position.z.toDouble())
        return DIRECTIONS.flatMap { direction ->
            val p = faceVertices(direction, Vec3.ZERO, Vec3(1.0, 1.0, 1.0)).map { it + offset }
            val uv = rotatedUv(0, listOf(0.0, 0.0, 16.0, 16.0))
            listOf(
                SceneTriangle(SceneVertex(p[0], uv[0]), SceneVertex(p[1], uv[1]), SceneVertex(p[2], uv[2]), texture),
                SceneTriangle(SceneVertex(p[0], uv[0]), SceneVertex(p[2], uv[2]), SceneVertex(p[3], uv[3]), texture),
            )
        }
    }

    private fun resolveTexture(
        raw: String,
        textures: Map<String, String>,
        modelId: String,
    ): String {
        var value = raw
        val visited = linkedSetOf<String>()
        while (value.startsWith('#')) {
            val key = value.removePrefix("#")
            if (!visited.add(key)) {
                resolver.invalid("TEXTURE_REFERENCE_CYCLE", "Texture reference cycle in $modelId", modelId)
                return "minecraft:block/missing"
            }
            value = textures[key] ?: run {
                resolver.missing("MISSING_TEXTURE_REFERENCE", "Missing texture variable #$key in $modelId", modelId)
                return "minecraft:block/missing"
            }
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
            val values = entry.substringAfter('=', "").split('|')
            properties[key] in values
        }
    }

    private fun multipartMatches(
        raw: JsonElement,
        properties: Map<String, String>,
    ): Boolean {
        if (!raw.isJsonObject) return false
        val json = raw.asJsonObject
        json.getAsJsonArrayOrNull("OR")?.let { return it.any { item -> multipartMatches(item, properties) } }
        json.getAsJsonArrayOrNull("AND")?.let { return it.all { item -> multipartMatches(item, properties) } }
        return json.entrySet().all { (name, value) ->
            value.isJsonPrimitive && properties[name] in value.asString.split('|')
        }
    }

    private fun faceVertices(
        direction: String,
        min: Vec3,
        max: Vec3,
    ): List<Vec3> =
        when (direction) {
            "west" -> listOf(Vec3(min.x, min.y, min.z), Vec3(min.x, min.y, max.z), Vec3(min.x, max.y, max.z), Vec3(min.x, max.y, min.z))
            "east" -> listOf(Vec3(max.x, min.y, max.z), Vec3(max.x, min.y, min.z), Vec3(max.x, max.y, min.z), Vec3(max.x, max.y, max.z))
            "down" -> listOf(Vec3(min.x, min.y, max.z), Vec3(min.x, min.y, min.z), Vec3(max.x, min.y, min.z), Vec3(max.x, min.y, max.z))
            "up" -> listOf(Vec3(min.x, max.y, min.z), Vec3(min.x, max.y, max.z), Vec3(max.x, max.y, max.z), Vec3(max.x, max.y, min.z))
            "north" -> listOf(Vec3(max.x, min.y, min.z), Vec3(min.x, min.y, min.z), Vec3(min.x, max.y, min.z), Vec3(max.x, max.y, min.z))
            else -> listOf(Vec3(min.x, min.y, max.z), Vec3(max.x, min.y, max.z), Vec3(max.x, max.y, max.z), Vec3(min.x, max.y, max.z))
        }

    private fun rotatedUv(
        rotation: Int,
        values: List<Double>,
    ): List<Vec2> {
        val u0 = values[0] / 16.0
        val v0 = values[1] / 16.0
        val u1 = values[2] / 16.0
        val v1 = values[3] / 16.0
        val base = listOf(Vec2(u0, v1), Vec2(u1, v1), Vec2(u1, v0), Vec2(u0, v0))
        val turns = Math.floorMod(rotation, 360) / 90
        return List(4) { index -> base[Math.floorMod(index - turns, 4)] }
    }

    private fun defaultFaceUv(
        direction: String,
        from: Vec3,
        to: Vec3,
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
        position: BlockPos,
        direction: String,
    ): BlockPos =
        when (direction) {
            "west" -> BlockPos(position.x - 1, position.y, position.z)
            "east" -> BlockPos(position.x + 1, position.y, position.z)
            "down" -> BlockPos(position.x, position.y - 1, position.z)
            "up" -> BlockPos(position.x, position.y + 1, position.z)
            "north" -> BlockPos(position.x, position.y, position.z - 1)
            else -> BlockPos(position.x, position.y, position.z + 1)
        }

    private fun biomeTint(position: BlockPos): Int =
        when (biomes[position]?.substringAfter(':')) {
            "desert" -> 0xffbfb755.toInt()
            "forest" -> 0xff59a33b.toInt()
            else -> 0xff78a94b.toInt()
        }

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
        val from: Vec3,
        val to: Vec3,
        val rotation: ElementRotation?,
        val faces: Map<String, ModelFace>,
        val shade: Boolean,
    )

    private data class ElementRotation(
        val origin: Vec3,
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
        private val BLOCK_CENTER = Vec3(0.5, 0.5, 0.5)
    }
}

private fun JsonObject.getAsJsonObjectOrNull(name: String): JsonObject? =
    get(name)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.getAsJsonArrayOrNull(name: String): JsonArray? =
    get(name)?.takeIf { it.isJsonArray }?.asJsonArray

private fun JsonObject.string(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

private fun JsonObject.int(name: String): Int? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

private fun JsonObject.double(name: String): Double? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble

private fun JsonObject.boolean(name: String): Boolean? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

private fun JsonObject.vector(name: String): Vec3? {
    val array = get(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
    if (array.size() != 3 || array.any { !it.isJsonPrimitive || !it.asJsonPrimitive.isNumber }) return null
    return Vec3(array[0].asDouble, array[1].asDouble, array[2].asDouble)
}

private fun JsonObject.numberArray(
    name: String,
    expectedSize: Int,
): List<Double>? {
    val array = get(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
    if (array.size() != expectedSize || array.any { !it.isJsonPrimitive || !it.asJsonPrimitive.isNumber }) return null
    return array.map(JsonElement::getAsDouble)
}
