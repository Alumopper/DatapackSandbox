package moe.afox.dpsandbox.render

import com.google.gson.JsonObject
import moe.afox.dpsandbox.core.BlockPos
import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.SandboxException
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

internal class SceneBuilder(
    private val resolver: AssetResolver,
    private val diagnostics: MutableList<RenderDiagnostic>,
) {
    private val diagnosedEntityTypes = mutableSetOf<String>()

    fun build(
        view: WorldView,
        request: RenderRequest,
    ): RenderScene {
        val camera = resolveCamera(view, request.camera)
        val dimensions =
            buildSet {
                add(OVERWORLD)
                view.entities.mapTo(this, RenderEntity::dimension)
            }
        if (camera.dimension !in dimensions) {
            throw SandboxException(
                DiagnosticCode.INPUT_FORMAT,
                "Render camera dimension '${camera.dimension}' does not exist; available dimensions: ${dimensions.sorted().joinToString()}",
            )
        }
        val triangles = mutableListOf<SceneTriangle>()
        val modelBaker =
            ModelBaker(
                resolver,
                diagnostics,
                view.blocks.map(RenderBlock::position).toSet(),
                view.seed,
                view.biomes,
            )
        var visibleBlocks = 0
        if (camera.dimension == OVERWORLD) {
            view.blocks.forEach { block ->
                val center = Vec3(block.position.x + 0.5, block.position.y + 0.5, block.position.z + 0.5)
                if (distanceSquared(center, camera.position) <= request.renderDistance * request.renderDistance &&
                    intersectsFrustum(center, BLOCK_BOUNDING_RADIUS, camera, request)
                ) {
                    triangles += modelBaker.bake(block)
                    visibleBlocks += 1
                }
            }
        }

        var visibleEntities = 0
        view.entities.forEach { entity ->
            if (entity.dimension != camera.dimension) return@forEach
            if (entity.type in HIDDEN_ENTITIES && !request.showDebugOverlay) {
                diagnoseEntity(entity.type, "ENTITY_HIDDEN", "Entity is intentionally hidden outside debug rendering")
                return@forEach
            }
            val position = Vec3(entity.position.x, entity.position.y, entity.position.z)
            if (distanceSquared(position, camera.position) > request.renderDistance * request.renderDistance) return@forEach
            val radius = max(entityWidth(entity.type), entityHeight(entity.type))
            if (!intersectsFrustum(position + Vec3(0.0, entityHeight(entity.type) / 2.0, 0.0), radius, camera, request)) {
                return@forEach
            }
            if (entity.type.substringAfter(':') !in MODELED_ENTITIES) {
                diagnoseEntity(entity.type, "ENTITY_APPROXIMATE", "Entity uses deterministic simplified geometry")
            }
            triangles += entityTriangles(entity, modelBaker, camera)
            visibleEntities += 1
        }
        return RenderScene(camera, triangles, visibleBlocks, visibleEntities)
    }

    private fun diagnoseEntity(
        type: String,
        code: String,
        message: String,
    ) {
        if (!diagnosedEntityTypes.add("$code:$type")) return
        diagnostics += RenderDiagnostic(RenderDiagnosticSeverity.INFO, code, "$message: $type", type)
    }

    private fun resolveCamera(
        view: WorldView,
        requested: RenderCamera,
    ): ResolvedCamera =
        when (requested) {
            is RenderCamera.Player -> {
                val player =
                    view.entities.firstOrNull { it.name == requested.name }
                        ?: throw SandboxException(
                            DiagnosticCode.MISSING_CONTEXT,
                            "Render camera player '${requested.name}' does not exist; available players: " +
                                view.entities
                                    .mapNotNull(RenderEntity::name)
                                    .sorted()
                                    .ifEmpty { listOf("<none>") }
                                    .joinToString(),
                        )
                ResolvedCamera(
                    Vec3(player.position.x, player.position.y + PLAYER_EYE_HEIGHT, player.position.z),
                    player.yaw,
                    player.pitch,
                    player.dimension,
                    "player:${player.name}",
                )
            }
            is RenderCamera.Entity -> {
                val entity =
                    view.entities.firstOrNull { it.uuid == requested.uuid }
                        ?: throw SandboxException(DiagnosticCode.MISSING_CONTEXT, "Render camera entity '${requested.uuid}' does not exist")
                ResolvedCamera(
                    Vec3(entity.position.x, entity.position.y + entityHeight(entity.type) * 0.75, entity.position.z),
                    entity.yaw,
                    entity.pitch,
                    entity.dimension,
                    "entity:${entity.uuid}",
                )
            }
            is RenderCamera.Fixed -> {
                if (!requested.position.x.isFinite() ||
                    !requested.position.y.isFinite() ||
                    !requested.position.z.isFinite() ||
                    !requested.yaw.isFinite() ||
                    !requested.pitch.isFinite()
                ) {
                    throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Fixed render camera values must be finite")
                }
                ResolvedCamera(
                    Vec3(requested.position.x, requested.position.y, requested.position.z),
                    requested.yaw,
                    requested.pitch,
                    requested.dimension,
                    "fixed",
                )
            }
            RenderCamera.Auto -> autoCamera(view)
        }

    private fun autoCamera(view: WorldView): ResolvedCamera {
        val points =
            buildList {
                view.blocks.forEach { add(Vec3(it.position.x + 0.5, it.position.y + 0.5, it.position.z + 0.5)) }
                view.entities.filter { it.dimension == OVERWORLD }.forEach { add(Vec3(it.position.x, it.position.y + 0.9, it.position.z)) }
            }
        if (points.isEmpty()) {
            val target = Vec3(view.worldSpawn.x, view.worldSpawn.y, view.worldSpawn.z)
            return lookAt(target + Vec3(6.0, 5.0, 6.0), target, OVERWORLD, "auto:world-spawn")
        }
        val min = Vec3(points.minOf { it.x }, points.minOf { it.y }, points.minOf { it.z })
        val maxPoint = Vec3(points.maxOf { it.x }, points.maxOf { it.y }, points.maxOf { it.z })
        val target = (min + maxPoint) * 0.5
        val extent = max(max(maxPoint.x - min.x, maxPoint.y - min.y), maxPoint.z - min.z).coerceAtLeast(3.0)
        return lookAt(target + Vec3(extent * 1.35, extent * 0.9 + 2.0, extent * 1.35), target, OVERWORLD, "auto:scene")
    }

    private fun lookAt(
        position: Vec3,
        target: Vec3,
        dimension: String,
        description: String,
    ): ResolvedCamera {
        val direction = (target - position).normalized()
        val yaw = Math.toDegrees(atan2(-direction.x, direction.z))
        val pitch = Math.toDegrees(asin(-direction.y.coerceIn(-1.0, 1.0)))
        return ResolvedCamera(position, yaw, pitch, dimension, description)
    }

    private fun entityTriangles(
        entity: RenderEntity,
        modelBaker: ModelBaker,
        camera: ResolvedCamera,
    ): List<SceneTriangle> {
        val typePath = entity.type.substringAfter(':')
        if (typePath == "block_display") {
            blockDisplayTriangles(entity, modelBaker)?.let { return it }
        }
        val renderedPosition = entity.renderPosition()
        val texture =
            when (typePath) {
                "player" -> resolver.playerTexture(entity.name)
                "zombie" -> resolver.texture("minecraft:entity/zombie/zombie")
                "skeleton" -> resolver.texture("minecraft:entity/skeleton/skeleton")
                "item" -> resolver.proceduralTexture("item", 0xffffd45a.toInt(), 0xffb86b23.toInt())
                "experience_orb" -> resolver.proceduralTexture("experience_orb", 0xff9cff3c.toInt(), 0xff3a9c28.toInt())
                "block_display" -> resolver.proceduralTexture("block_display", 0xff9aa0a6.toInt(), 0xff4f555b.toInt())
                "item_display" -> itemDisplayTexture(entity)
                "text_display" -> textDisplayTexture(entity)
                else -> resolver.proceduralTexture(typePath, 0xffd15b5b.toInt(), 0xff702828.toInt())
            }
        if (typePath in HUMANOID_ENTITIES) return humanoidTriangles(entity, texture, slender = typePath == "skeleton")
        val height = entityHeight(entity.type)
        val width = entityWidth(entity.type)
        val center = Vec3(renderedPosition.x, renderedPosition.y + height / 2.0, renderedPosition.z)
        return when (typePath) {
            "item" -> billboard(center, width, height, texture, camera)
            "experience_orb" ->
                billboard(center, width, height, texture, camera) +
                    billboard(center, width, height, texture, camera, perpendicular = true)
            "item_display", "text_display" -> {
                val plane = billboard(center, width, height, texture, camera)
                val transformation = entity.renderTransformation()
                val rotation = entity.renderRotation()
                val pivot = Vec3(renderedPosition.x, renderedPosition.y, renderedPosition.z)
                plane.map { triangle -> triangle.transform(pivot, Vec3.ZERO, transformation, rotation) }
            }
            else -> cuboid(center, width, height, width, texture)
        }
    }

    private fun itemDisplayTexture(entity: RenderEntity): TextureData {
        val item = entity.special?.getAsJsonObject("content")?.get("item")
        val id =
            when {
                item == null -> null
                item.isJsonPrimitive -> item.asString
                item.isJsonObject ->
                    item.asJsonObject
                        .get("id")
                        ?.takeIf { it.isJsonPrimitive }
                        ?.asString
                        ?: item.asJsonObject
                            .get("Id")
                            ?.takeIf { it.isJsonPrimitive }
                            ?.asString
                else -> null
            }
        if (id != null) {
            val (namespace, path) = splitResourceId(id)
            return resolver.texture("$namespace:item/$path")
        }
        return resolver.proceduralTexture("item_display", 0xffffb347.toInt(), 0xffb85b00.toInt())
    }

    private fun textDisplayTexture(entity: RenderEntity): TextureData {
        val text =
            entity.special
                ?.getAsJsonObject("content")
                ?.get("text")
                ?.toString()
                .orEmpty()
        return resolver.proceduralTexture("text_display:$text", 0xeeffffff.toInt(), 0xee202020.toInt())
    }

    private fun billboard(
        center: Vec3,
        width: Double,
        height: Double,
        texture: TextureData,
        camera: ResolvedCamera,
        perpendicular: Boolean = false,
    ): List<SceneTriangle> {
        val horizontal = Vec3(camera.position.x - center.x, 0.0, camera.position.z - center.z)
        val facing = if (horizontal.dot(horizontal) <= 1e-12) Vec3(0.0, 0.0, -1.0) else horizontal.normalized()
        val cameraRight = facing.cross(Vec3.UP).normalized()
        val right = if (perpendicular) (cameraRight + facing).normalized() else cameraRight
        val lowerLeft = center - right * (width / 2.0) - Vec3.UP * (height / 2.0)
        val upperLeft = center - right * (width / 2.0) + Vec3.UP * (height / 2.0)
        val upperRight = center + right * (width / 2.0) + Vec3.UP * (height / 2.0)
        val lowerRight = center + right * (width / 2.0) - Vec3.UP * (height / 2.0)
        return listOf(
            SceneTriangle(SceneVertex(lowerLeft, UV[0]), SceneVertex(upperLeft, UV[3]), SceneVertex(upperRight, UV[2]), texture),
            SceneTriangle(SceneVertex(lowerLeft, UV[0]), SceneVertex(upperRight, UV[2]), SceneVertex(lowerRight, UV[1]), texture),
        )
    }

    private fun humanoidTriangles(
        entity: RenderEntity,
        texture: TextureData,
        slender: Boolean,
    ): List<SceneTriangle> {
        val position = entity.renderPosition()
        val pivot = Vec3(position.x, position.y, position.z)
        val armWidth = if (slender) 0.12 else 0.2
        val legWidth = if (slender) 0.14 else 0.22
        val parts =
            buildList {
                addAll(cuboid(Vec3(position.x, position.y + 1.55, position.z), 0.5, 0.5, 0.5, texture))
                addAll(cuboid(Vec3(position.x, position.y + 1.0, position.z), 0.5, 0.65, 0.28, texture))
                addAll(cuboid(Vec3(position.x - 0.16, position.y + 0.35, position.z), legWidth, 0.7, 0.24, texture))
                addAll(cuboid(Vec3(position.x + 0.16, position.y + 0.35, position.z), legWidth, 0.7, 0.24, texture))
                addAll(cuboid(Vec3(position.x - 0.36, position.y + 1.0, position.z), armWidth, 0.7, 0.22, texture))
                addAll(cuboid(Vec3(position.x + 0.36, position.y + 1.0, position.z), armWidth, 0.7, 0.22, texture))
            }
        return parts.map { triangle -> triangle.rotateAround(pivot, entity.yaw) }
    }

    private fun SceneTriangle.rotateAround(
        pivot: Vec3,
        yaw: Double,
    ): SceneTriangle {
        fun vertex(value: SceneVertex): SceneVertex =
            value.copy(position = value.position.rotateAround(pivot, 'y', -yaw))
        return copy(a = vertex(a), b = vertex(b), c = vertex(c))
    }

    private fun blockDisplayTriangles(
        entity: RenderEntity,
        modelBaker: ModelBaker,
    ): List<SceneTriangle>? {
        val blockState =
            entity.special
                ?.getAsJsonObject("content")
                ?.get("blockState")
                ?: return null
        val id: String
        val properties = linkedMapOf<String, String>()
        when {
            blockState.isJsonPrimitive -> id = blockState.asString
            blockState.isJsonObject -> {
                val value = blockState.asJsonObject
                id =
                    value.get("Name")?.takeIf { it.isJsonPrimitive }?.asString
                        ?: value.get("name")?.takeIf { it.isJsonPrimitive }?.asString
                        ?: return null
                val source = value.get("Properties") ?: value.get("properties")
                source?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (name, property) ->
                    if (property.isJsonPrimitive) properties[name] = property.asString
                }
            }
            else -> return null
        }
        val rendered = entity.renderPosition()
        val base = BlockPos(floor(rendered.x).toInt(), floor(rendered.y).toInt(), floor(rendered.z).toInt())
        val delta = Vec3(rendered.x - base.x, rendered.y - base.y, rendered.z - base.z)
        val transformation = entity.renderTransformation()
        val rotation = entity.renderRotation()
        val pivot = Vec3(rendered.x, rendered.y, rendered.z)
        return modelBaker.bake(RenderBlock(base, id, properties)).map { triangle ->
            triangle.transform(pivot, delta, transformation, rotation)
        }
    }

    private fun cuboid(
        center: Vec3,
        width: Double,
        height: Double,
        depth: Double,
        texture: TextureData,
    ): List<SceneTriangle> {
        val min = Vec3(center.x - width / 2.0, center.y - height / 2.0, center.z - depth / 2.0)
        val max = Vec3(center.x + width / 2.0, center.y + height / 2.0, center.z + depth / 2.0)
        return DIRECTIONS.flatMap { direction ->
            val p = faceVertices(direction, min, max)
            listOf(
                SceneTriangle(SceneVertex(p[0], UV[0]), SceneVertex(p[1], UV[1]), SceneVertex(p[2], UV[2]), texture),
                SceneTriangle(SceneVertex(p[0], UV[0]), SceneVertex(p[2], UV[2]), SceneVertex(p[3], UV[3]), texture),
            )
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

    private fun distanceSquared(
        first: Vec3,
        second: Vec3,
    ): Double {
        val delta = first - second
        return delta.dot(delta)
    }

    private fun intersectsFrustum(
        center: Vec3,
        radius: Double,
        camera: ResolvedCamera,
        request: RenderRequest,
    ): Boolean {
        val yaw = Math.toRadians(camera.yaw)
        val pitch = Math.toRadians(camera.pitch)
        val forward = Vec3(-sin(yaw) * cos(pitch), -sin(pitch), cos(yaw) * cos(pitch)).normalized()
        val candidateRight = forward.cross(Vec3.UP)
        val right = if (candidateRight.dot(candidateRight) <= 1e-12) Vec3(1.0, 0.0, 0.0) else candidateRight.normalized()
        val up = right.cross(forward).normalized()
        val relative = center - camera.position
        val depth = relative.dot(forward)
        if (depth + radius < request.nearPlane || depth - radius > request.renderDistance) return false
        val vertical = tan(Math.toRadians(request.fieldOfViewDegrees) / 2.0) * max(depth, request.nearPlane)
        val horizontal = vertical * request.width / request.height
        return kotlin.math.abs(relative.dot(up)) <= vertical + radius &&
            kotlin.math.abs(relative.dot(right)) <= horizontal + radius
    }

    private fun RenderEntity.renderPosition(): Vec3 {
        val position = special?.getAsJsonObject("renderPosition")
        return if (position == null) {
            Vec3(this.position.x, this.position.y, this.position.z)
        } else {
            Vec3(
                position.get("x")?.asDouble ?: this.position.x,
                position.get("y")?.asDouble ?: this.position.y,
                position.get("z")?.asDouble ?: this.position.z,
            )
        }
    }

    private fun RenderEntity.renderTransformation(): List<Double> {
        val value = special?.get("renderTransformation") ?: return IDENTITY_MATRIX
        if (value.isJsonArray && value.asJsonArray.size() == 16) return value.asJsonArray.map { it.asDouble }
        if (!value.isJsonObject) return IDENTITY_MATRIX
        val root = value.asJsonObject
        val translation = root.vector("translation", Vec3.ZERO)
        val scale = root.vector("scale", Vec3(1.0, 1.0, 1.0))
        val left = root.quaternion("left_rotation")
        val right = root.quaternion("right_rotation")
        return multiplyMatrices(
            multiplyMatrices(
                multiplyMatrices(translationMatrix(translation), rotationMatrix(left)),
                scaleMatrix(scale),
            ),
            rotationMatrix(right),
        )
    }

    private fun RenderEntity.renderRotation(): Pair<Double, Double> {
        val position = special?.get("renderPosition")?.takeIf { it.isJsonObject }?.asJsonObject
        return (position?.get("yaw")?.asDouble ?: yaw) to (position?.get("pitch")?.asDouble ?: pitch)
    }

    private fun SceneTriangle.transform(
        pivot: Vec3,
        translation: Vec3,
        transformation: List<Double>,
        rotation: Pair<Double, Double>,
    ): SceneTriangle {
        fun vertex(value: SceneVertex): SceneVertex {
            val translated = value.position + translation
            val relative = translated - pivot
            val model = transform(transformation, relative)
            val rotated =
                model
                    .rotateAround(Vec3.ZERO, 'y', -rotation.first)
                    .rotateAround(Vec3.ZERO, 'x', rotation.second)
            val transformed = rotated + pivot
            return value.copy(position = transformed)
        }
        return copy(a = vertex(a), b = vertex(b), c = vertex(c))
    }

    private fun transform(
        matrix: List<Double>,
        value: Vec3,
    ): Vec3 =
        Vec3(
            matrix[0] * value.x + matrix[1] * value.y + matrix[2] * value.z + matrix[3],
            matrix[4] * value.x + matrix[5] * value.y + matrix[6] * value.z + matrix[7],
            matrix[8] * value.x + matrix[9] * value.y + matrix[10] * value.z + matrix[11],
        )

    private fun JsonObject.vector(
        name: String,
        fallback: Vec3,
    ): Vec3 {
        val values = get(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: return fallback
        if (values.size() != 3) return fallback
        return Vec3(values[0].asDouble, values[1].asDouble, values[2].asDouble)
    }

    private fun JsonObject.quaternion(name: String): List<Double> {
        val values = get(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: return IDENTITY_QUATERNION
        if (values.size() != 4) return IDENTITY_QUATERNION
        return values.map { it.asDouble }
    }

    private fun translationMatrix(value: Vec3): List<Double> =
        listOf(
            1.0,
            0.0,
            0.0,
            value.x,
            0.0,
            1.0,
            0.0,
            value.y,
            0.0,
            0.0,
            1.0,
            value.z,
            0.0,
            0.0,
            0.0,
            1.0,
        )

    private fun scaleMatrix(value: Vec3): List<Double> =
        listOf(
            value.x,
            0.0,
            0.0,
            0.0,
            0.0,
            value.y,
            0.0,
            0.0,
            0.0,
            0.0,
            value.z,
            0.0,
            0.0,
            0.0,
            0.0,
            1.0,
        )

    private fun rotationMatrix(raw: List<Double>): List<Double> {
        val length = kotlin.math.sqrt(raw.sumOf { it * it })
        val q = if (length <= 1e-12) IDENTITY_QUATERNION else raw.map { it / length }
        val (x, y, z, w) = q
        return listOf(
            1.0 - 2.0 * (y * y + z * z),
            2.0 * (x * y - w * z),
            2.0 * (x * z + w * y),
            0.0,
            2.0 * (x * y + w * z),
            1.0 - 2.0 * (x * x + z * z),
            2.0 * (y * z - w * x),
            0.0,
            2.0 * (x * z - w * y),
            2.0 * (y * z + w * x),
            1.0 - 2.0 * (x * x + y * y),
            0.0,
            0.0,
            0.0,
            0.0,
            1.0,
        )
    }

    private fun multiplyMatrices(
        left: List<Double>,
        right: List<Double>,
    ): List<Double> =
        List(16) { index ->
            val row = index / 4
            val column = index % 4
            (0..3).sumOf { offset -> left[row * 4 + offset] * right[offset * 4 + column] }
        }

    private fun entityHeight(type: String): Double =
        when (type.substringAfter(':')) {
            "item" -> 0.25
            "experience_orb" -> 0.3
            "item_display" -> 1.0
            "text_display" -> 0.45
            "block_display" -> 1.0
            else -> 1.8
        }

    private fun entityWidth(type: String): Double =
        when (type.substringAfter(':')) {
            "item" -> 0.25
            "experience_orb" -> 0.3
            "skeleton" -> 0.5
            "item_display" -> 1.0
            "text_display" -> 1.6
            "block_display" -> 1.0
            else -> 0.6
        }

    companion object {
        private const val OVERWORLD = "minecraft:overworld"
        private const val PLAYER_EYE_HEIGHT = 1.62
        private const val BLOCK_BOUNDING_RADIUS = 0.8660254037844386
        private val HIDDEN_ENTITIES = setOf("minecraft:marker", "minecraft:interaction")
        private val HUMANOID_ENTITIES = setOf("player", "zombie", "skeleton")
        private val MODELED_ENTITIES = HUMANOID_ENTITIES + "block_display"
        private val DIRECTIONS = listOf("west", "east", "down", "up", "north", "south")
        private val UV = listOf(Vec2(0.0, 1.0), Vec2(1.0, 1.0), Vec2(1.0, 0.0), Vec2(0.0, 0.0))
        private val IDENTITY_QUATERNION = listOf(0.0, 0.0, 0.0, 1.0)
        private val IDENTITY_MATRIX =
            listOf(
                1.0,
                0.0,
                0.0,
                0.0,
                0.0,
                1.0,
                0.0,
                0.0,
                0.0,
                0.0,
                1.0,
                0.0,
                0.0,
                0.0,
                0.0,
                1.0,
            )
    }
}
