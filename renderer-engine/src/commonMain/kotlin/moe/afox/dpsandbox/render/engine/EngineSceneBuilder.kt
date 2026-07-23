package moe.afox.dpsandbox.render.engine

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

internal class EngineSceneBuilder(
    private val textures: EngineTextureStore,
) {
    fun build(
        world: EngineRenderWorld,
        width: Int,
        height: Int,
        cameraOverride: EngineCamera? = null,
        cull: Boolean = true,
    ): EngineRenderScene {
        val camera = cameraOverride ?: autoCamera(world)
        val occupied = world.blocks.mapTo(hashSetOf()) { blockKey(it.x, it.y, it.z) }
        val modelBaker = EngineModelBaker(textures, occupied, world.seed)
        val displayModelBaker = EngineModelBaker(textures, emptySet(), world.seed)
        val triangles = mutableListOf<EngineSceneTriangle>()
        var visibleBlocks = 0
        world.blocks.take(SoftwareWorldRenderer.MAX_VISIBLE_BLOCKS).forEach { block ->
            val center = EngineVec3(block.x + 0.5, block.y + 0.5, block.z + 0.5)
            if (!cull || intersectsFrustum(center, BLOCK_RADIUS, camera, width, height)) {
                triangles += modelBaker.bake(block)
                visibleBlocks += 1
            }
        }
        var visibleEntities = 0
        world.entities.take(MAX_VISIBLE_ENTITIES).forEach { entity ->
            val center = EngineVec3(entity.x, entity.y + entityHeight(entity.type) / 2.0, entity.z)
            val displayDistance = RENDER_DISTANCE * (entity.display?.viewRange ?: 1.0).coerceAtLeast(0.0)
            val relative = center - camera.position
            val radius = displayRadius(entity)
            if (!cull || (relative.dot(relative) <= displayDistance * displayDistance && intersectsFrustum(center, radius, camera, width, height))) {
                triangles += entityTriangles(entity, displayModelBaker, camera)
                visibleEntities += 1
            }
        }
        if (world.entities.isEmpty()) visibleEntities = world.entityCount
        return EngineRenderScene(camera, triangles, visibleBlocks, visibleEntities)
    }

    private fun autoCamera(world: EngineRenderWorld): EngineCamera {
        val points =
            buildList {
                world.blocks.forEach { add(EngineVec3(it.x + 0.5, it.y + 0.5, it.z + 0.5)) }
                world.entities.forEach { add(EngineVec3(it.x, it.y + 0.9, it.z)) }
            }
        if (points.isEmpty()) {
            val target = EngineVec3.ZERO
            return lookAt(target + EngineVec3(6.0, 5.0, 6.0), target, "auto:world-spawn")
        }
        val minimum = EngineVec3(points.minOf { it.x }, points.minOf { it.y }, points.minOf { it.z })
        val maximum = EngineVec3(points.maxOf { it.x }, points.maxOf { it.y }, points.maxOf { it.z })
        val target = (minimum + maximum) * 0.5
        val extent = max(max(maximum.x - minimum.x, maximum.y - minimum.y), maximum.z - minimum.z).coerceAtLeast(1.0)
        val distance = extent * 1.35 + 1.5
        val offset = EngineVec3(1.0, 0.72, 1.0).normalized() * distance
        return lookAt(target + offset, target, "auto:scene")
    }

    private fun lookAt(
        position: EngineVec3,
        target: EngineVec3,
        description: String,
    ): EngineCamera {
        val direction = (target - position).normalized()
        val yaw = atan2(-direction.x, direction.z) * 180.0 / PI
        val pitch = asin(-direction.y.coerceIn(-1.0, 1.0)) * 180.0 / PI
        return EngineCamera(position, yaw, pitch, description)
    }

    private fun blockTriangles(
        block: EngineRenderBlock,
        occupied: Set<String>,
    ): List<EngineSceneTriangle> {
        val minimum = EngineVec3(block.x.toDouble(), block.y.toDouble(), block.z.toDouble())
        val maximum = minimum + EngineVec3(1.0, 1.0, 1.0)
        return DIRECTIONS.flatMap { direction ->
            val neighbor = neighbor(block, direction)
            if (blockKey(neighbor.first, neighbor.second, neighbor.third) in occupied) return@flatMap emptyList()
            faceTriangles(faceVertices(direction, minimum, maximum), textures.blockTexture(block.id, direction))
        }
    }

    private fun entityTriangles(
        entity: EngineRenderEntity,
        displayModelBaker: EngineModelBaker,
        camera: EngineCamera,
    ): List<EngineSceneTriangle> {
        val type = entity.type.substringAfter(':')
        entity.display?.let { display ->
            val content =
                when (type) {
                    "block_display" -> blockDisplayTriangles(entity, display, displayModelBaker, camera)
                    "item_display" -> itemDisplayTriangles(entity, display, camera)
                    "text_display" -> textDisplayTriangles(entity, display, camera)
                    else -> emptyList()
                }
            return shadowTriangles(entity, display) + content
        }
        val texture = textures.entityTexture(entity.type)
        if (type in HUMANOIDS) {
            val pivot = EngineVec3(entity.x, entity.y, entity.z)
            val slender = type == "skeleton"
            val armWidth = if (slender) 0.12 else 0.2
            val legWidth = if (slender) 0.14 else 0.22
            return buildList {
                addAll(cuboid(EngineVec3(entity.x, entity.y + 1.55, entity.z), 0.5, 0.5, 0.5, texture))
                addAll(cuboid(EngineVec3(entity.x, entity.y + 1.0, entity.z), 0.5, 0.65, 0.28, texture))
                addAll(cuboid(EngineVec3(entity.x - 0.16, entity.y + 0.35, entity.z), legWidth, 0.7, 0.24, texture))
                addAll(cuboid(EngineVec3(entity.x + 0.16, entity.y + 0.35, entity.z), legWidth, 0.7, 0.24, texture))
                addAll(cuboid(EngineVec3(entity.x - 0.36, entity.y + 1.0, entity.z), armWidth, 0.7, 0.22, texture))
                addAll(cuboid(EngineVec3(entity.x + 0.36, entity.y + 1.0, entity.z), armWidth, 0.7, 0.22, texture))
            }.map { triangle -> triangle.rotateAround(pivot, entity.yaw) }
        }
        return cuboid(
            EngineVec3(entity.x, entity.y + entityHeight(entity.type) / 2.0, entity.z),
            entityWidth(entity.type),
            entityHeight(entity.type),
            entityWidth(entity.type),
            texture,
        )
    }

    private fun blockDisplayTriangles(
        entity: EngineRenderEntity,
        display: EngineDisplayData,
        modelBaker: EngineModelBaker,
        camera: EngineCamera,
    ): List<EngineSceneTriangle> {
        val id = display.blockId ?: return emptyList()
        if (id == "minecraft:air") return emptyList()
        val x = floor(entity.x).toInt()
        val y = floor(entity.y).toInt()
        val z = floor(entity.z).toInt()
        val localBase = EngineVec3(x.toDouble(), y.toDouble(), z.toDouble())
        return modelBaker
            .bake(EngineRenderBlock(x, y, z, id, display.blockProperties))
            .map { triangle -> transformDisplayTriangle(triangle, entity, display, camera, localBase) }
    }

    private fun itemDisplayTriangles(
        entity: EngineRenderEntity,
        display: EngineDisplayData,
        camera: EngineCamera,
    ): List<EngineSceneTriangle> {
        val item = display.itemId ?: return emptyList()
        if (item == "minecraft:air") return emptyList()
        val asset = textures.itemAsset(item, display.itemDisplay)
        val context = itemDisplayTransformation(display.itemDisplay, asset.transform)
        return generatedItemMesh(asset.texture).map { triangle ->
            transformDisplayTriangle(triangle, entity, display, camera, EngineVec3.ZERO, context)
        }
    }

    private fun textDisplayTriangles(
        entity: EngineRenderEntity,
        display: EngineDisplayData,
        camera: EngineCamera,
    ): List<EngineSceneTriangle> {
        val texture = textures.displayTextTexture(display)
        val width = texture.width / TEXT_PIXELS_PER_BLOCK
        val height = texture.height / TEXT_PIXELS_PER_BLOCK
        return doubleSidedPlane(width, height, texture).map { triangle ->
            transformDisplayTriangle(triangle, entity, display, camera, EngineVec3.ZERO).copy(seeThrough = display.seeThrough)
        }
    }

    private fun shadowTriangles(
        entity: EngineRenderEntity,
        display: EngineDisplayData,
    ): List<EngineSceneTriangle> {
        if (display.shadowRadius <= 0.0 || display.shadowStrength <= 0.0) return emptyList()
        val radius = display.shadowRadius
        val y = entity.y + 0.002
        val texture = textures.shadowTexture(display.shadowStrength)
        val points =
            listOf(
                EngineVec3(entity.x - radius, y, entity.z - radius),
                EngineVec3(entity.x - radius, y, entity.z + radius),
                EngineVec3(entity.x + radius, y, entity.z + radius),
                EngineVec3(entity.x + radius, y, entity.z - radius),
            )
        return faceTriangles(points, texture)
    }

    private fun doubleSidedPlane(
        width: Double,
        height: Double,
        texture: EngineTexture,
    ): List<EngineSceneTriangle> {
        val left = -width / 2.0
        val right = width / 2.0
        val bottom = -height / 2.0
        val top = height / 2.0
        val front =
            listOf(
                EngineSceneTriangle(
                    EngineSceneVertex(EngineVec3(left, bottom, 0.0), UV[0]),
                    EngineSceneVertex(EngineVec3(right, top, 0.0), UV[2]),
                    EngineSceneVertex(EngineVec3(left, top, 0.0), UV[3]),
                    texture,
                ),
                EngineSceneTriangle(
                    EngineSceneVertex(EngineVec3(left, bottom, 0.0), UV[0]),
                    EngineSceneVertex(EngineVec3(right, bottom, 0.0), UV[1]),
                    EngineSceneVertex(EngineVec3(right, top, 0.0), UV[2]),
                    texture,
                ),
            )
        return front +
            front.map { triangle ->
                EngineSceneTriangle(triangle.c, triangle.b, triangle.a, triangle.texture)
            }
    }

    private fun generatedItemMesh(texture: EngineTexture): List<EngineSceneTriangle> {
        val depth = 1.0 / 16.0
        val frontZ = depth / 2.0
        val backZ = -frontZ
        val triangles = mutableListOf<EngineSceneTriangle>()
        triangles +=
            faceTriangles(
                listOf(
                    EngineVec3(-0.5, -0.5, frontZ),
                    EngineVec3(0.5, -0.5, frontZ),
                    EngineVec3(0.5, 0.5, frontZ),
                    EngineVec3(-0.5, 0.5, frontZ),
                ),
                texture,
            )
        triangles +=
            faceTriangles(
                listOf(
                    EngineVec3(0.5, -0.5, backZ),
                    EngineVec3(-0.5, -0.5, backZ),
                    EngineVec3(-0.5, 0.5, backZ),
                    EngineVec3(0.5, 0.5, backZ),
                ),
                texture,
            )
        val gridWidth = texture.width.coerceAtMost(MAX_ITEM_SPRITE_GRID)
        val gridHeight = texture.height.coerceAtMost(MAX_ITEM_SPRITE_GRID)
        fun opaque(x: Int, y: Int): Boolean {
            if (x !in 0 until gridWidth || y !in 0 until gridHeight) return false
            val sourceX = x * texture.width / gridWidth
            val sourceY = y * texture.height / gridHeight
            return texture.pixels[sourceY * texture.width + sourceX] ushr 24 != 0
        }
        for (y in 0 until gridHeight) {
            for (x in 0 until gridWidth) {
                if (!opaque(x, y)) continue
                val left = -0.5 + x.toDouble() / gridWidth
                val right = -0.5 + (x + 1.0) / gridWidth
                val top = 0.5 - y.toDouble() / gridHeight
                val bottom = 0.5 - (y + 1.0) / gridHeight
                val uv = EngineVec2((x + 0.5) / gridWidth, (y + 0.5) / gridHeight)
                if (!opaque(x - 1, y)) {
                    triangles += sideQuad(
                        listOf(EngineVec3(left, bottom, backZ), EngineVec3(left, bottom, frontZ), EngineVec3(left, top, frontZ), EngineVec3(left, top, backZ)),
                        uv,
                        texture,
                    )
                }
                if (!opaque(x + 1, y)) {
                    triangles += sideQuad(
                        listOf(EngineVec3(right, bottom, frontZ), EngineVec3(right, bottom, backZ), EngineVec3(right, top, backZ), EngineVec3(right, top, frontZ)),
                        uv,
                        texture,
                    )
                }
                if (!opaque(x, y - 1)) {
                    triangles += sideQuad(
                        listOf(EngineVec3(left, top, frontZ), EngineVec3(right, top, frontZ), EngineVec3(right, top, backZ), EngineVec3(left, top, backZ)),
                        uv,
                        texture,
                    )
                }
                if (!opaque(x, y + 1)) {
                    triangles += sideQuad(
                        listOf(EngineVec3(left, bottom, backZ), EngineVec3(right, bottom, backZ), EngineVec3(right, bottom, frontZ), EngineVec3(left, bottom, frontZ)),
                        uv,
                        texture,
                    )
                }
            }
        }
        return triangles
    }

    private fun sideQuad(
        points: List<EngineVec3>,
        uv: EngineVec2,
        texture: EngineTexture,
    ): List<EngineSceneTriangle> {
        val vertices = points.map { EngineSceneVertex(it, uv) }
        return listOf(
            EngineSceneTriangle(vertices[0], vertices[1], vertices[2], texture),
            EngineSceneTriangle(vertices[0], vertices[2], vertices[3], texture),
        )
    }

    private fun transformDisplayTriangle(
        triangle: EngineSceneTriangle,
        entity: EngineRenderEntity,
        display: EngineDisplayData,
        camera: EngineCamera,
        localBase: EngineVec3,
        contentTransformation: List<Double> = EngineDisplayData.IDENTITY_TRANSFORMATION,
    ): EngineSceneTriangle {
        val origin = EngineVec3(entity.x, entity.y, entity.z)
        fun transformVertex(vertex: EngineSceneVertex): EngineSceneVertex {
            val local = vertex.position - localBase
            val content = transform(contentTransformation, local)
            val model = transform(display.transformation, content)
            val oriented = orientDisplay(model, entity, display.billboard, camera)
            val billboardMode =
                when (display.billboard) {
                    "vertical" -> 1
                    "horizontal" -> 2
                    "center" -> 3
                    else -> 0
                }
            return vertex.copy(
                position = origin + oriented,
                billboardLocal = model.takeIf { billboardMode != 0 },
                billboardPivot = origin.takeIf { billboardMode != 0 },
                billboardMode = billboardMode,
            )
        }
        val light =
            listOfNotNull(display.brightnessSky, display.brightnessBlock)
                .maxOrNull()
                ?.coerceIn(0, 15)
                ?.div(15.0)
        return triangle.copy(
            a = transformVertex(triangle.a),
            b = transformVertex(triangle.b),
            c = transformVertex(triangle.c),
            lightOverride = light,
        )
    }

    private fun orientDisplay(
        value: EngineVec3,
        entity: EngineRenderEntity,
        billboard: String,
        camera: EngineCamera,
    ): EngineVec3 {
        val facing = (camera.position - EngineVec3(entity.x, entity.y, entity.z)).normalized()
        val horizontalLength = kotlin.math.sqrt(facing.x * facing.x + facing.z * facing.z)
        val lookYaw = atan2(facing.x, facing.z) * 180.0 / PI
        val lookPitch = -atan2(facing.y, horizontalLength) * 180.0 / PI
        val yaw = if (billboard == "vertical" || billboard == "center") lookYaw else -entity.yaw
        val pitch = if (billboard == "horizontal" || billboard == "center") lookPitch else entity.pitch
        return value.rotateAround(EngineVec3.ZERO, 'x', pitch).rotateAround(EngineVec3.ZERO, 'y', yaw)
    }

    private fun transform(
        matrix: List<Double>,
        value: EngineVec3,
    ): EngineVec3 =
        EngineVec3(
            matrix[0] * value.x + matrix[1] * value.y + matrix[2] * value.z + matrix[3],
            matrix[4] * value.x + matrix[5] * value.y + matrix[6] * value.z + matrix[7],
            matrix[8] * value.x + matrix[9] * value.y + matrix[10] * value.z + matrix[11],
        )

    private fun itemDisplayTransformation(
        context: String,
        model: EngineItemModelTransform?,
    ): List<Double> {
        val transform =
            model?.let { ItemTransform(it.translation, it.rotation, it.scale) }
                ?: when (context) {
                    "ground" -> ItemTransform(EngineVec3(0.0, 0.125, 0.0), EngineVec3(90.0, 0.0, 0.0), EngineVec3(0.5, 0.5, 0.5))
                    "head" -> ItemTransform(EngineVec3(0.0, 0.25, 0.0), EngineVec3(0.0, 180.0, 0.0), EngineVec3(0.625, 0.625, 0.625))
                    "gui" -> ItemTransform(EngineVec3.ZERO, EngineVec3(30.0, 225.0, 0.0), EngineVec3(0.625, 0.625, 0.625))
                    "fixed" -> ItemTransform(EngineVec3.ZERO, EngineVec3(0.0, 180.0, 0.0), EngineVec3(0.5, 0.5, 0.5))
                    "thirdperson_lefthand" -> ItemTransform(EngineVec3(0.0, 0.1875, 0.0), EngineVec3(75.0, 45.0, 0.0), EngineVec3(0.55, 0.55, 0.55))
                    "thirdperson_righthand" -> ItemTransform(EngineVec3(0.0, 0.1875, 0.0), EngineVec3(75.0, -45.0, 0.0), EngineVec3(0.55, 0.55, 0.55))
                    "firstperson_lefthand" -> ItemTransform(EngineVec3(0.1, 0.0, 0.0), EngineVec3(0.0, 225.0, 0.0), EngineVec3(0.68, 0.68, 0.68))
                    "firstperson_righthand" -> ItemTransform(EngineVec3(-0.1, 0.0, 0.0), EngineVec3(0.0, 135.0, 0.0), EngineVec3(0.68, 0.68, 0.68))
                    else -> ItemTransform(EngineVec3.ZERO, EngineVec3.ZERO, EngineVec3(1.0, 1.0, 1.0))
                }
        return multiplyMatrices(
            translationMatrix(transform.translation),
            multiplyMatrices(
                rotationZMatrix(transform.rotation.z),
                multiplyMatrices(
                    rotationYMatrix(transform.rotation.y),
                    multiplyMatrices(rotationXMatrix(transform.rotation.x), scaleMatrix(transform.scale)),
                ),
            ),
        )
    }

    private fun translationMatrix(value: EngineVec3): List<Double> =
        listOf(
            1.0, 0.0, 0.0, value.x,
            0.0, 1.0, 0.0, value.y,
            0.0, 0.0, 1.0, value.z,
            0.0, 0.0, 0.0, 1.0,
        )

    private fun scaleMatrix(value: EngineVec3): List<Double> =
        listOf(
            value.x, 0.0, 0.0, 0.0,
            0.0, value.y, 0.0, 0.0,
            0.0, 0.0, value.z, 0.0,
            0.0, 0.0, 0.0, 1.0,
        )

    private fun rotationXMatrix(degrees: Double): List<Double> {
        val radians = degrees * PI / 180.0
        val c = cos(radians)
        val s = sin(radians)
        return listOf(
            1.0, 0.0, 0.0, 0.0,
            0.0, c, -s, 0.0,
            0.0, s, c, 0.0,
            0.0, 0.0, 0.0, 1.0,
        )
    }

    private fun rotationYMatrix(degrees: Double): List<Double> {
        val radians = degrees * PI / 180.0
        val c = cos(radians)
        val s = sin(radians)
        return listOf(
            c, 0.0, s, 0.0,
            0.0, 1.0, 0.0, 0.0,
            -s, 0.0, c, 0.0,
            0.0, 0.0, 0.0, 1.0,
        )
    }

    private fun rotationZMatrix(degrees: Double): List<Double> {
        val radians = degrees * PI / 180.0
        val c = cos(radians)
        val s = sin(radians)
        return listOf(
            c, -s, 0.0, 0.0,
            s, c, 0.0, 0.0,
            0.0, 0.0, 1.0, 0.0,
            0.0, 0.0, 0.0, 1.0,
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

    private fun displayRadius(entity: EngineRenderEntity): Double {
        val display = entity.display ?: return entityHeight(entity.type)
        return max(
            display.cullingWidth.takeIf { it > 0.0 }?.div(2.0) ?: entityWidth(entity.type),
            display.cullingHeight.takeIf { it > 0.0 } ?: entityHeight(entity.type),
        )
    }

    private fun cuboid(
        center: EngineVec3,
        width: Double,
        height: Double,
        depth: Double,
        texture: EngineTexture,
    ): List<EngineSceneTriangle> {
        val minimum = EngineVec3(center.x - width / 2.0, center.y - height / 2.0, center.z - depth / 2.0)
        val maximum = EngineVec3(center.x + width / 2.0, center.y + height / 2.0, center.z + depth / 2.0)
        return DIRECTIONS.flatMap { faceTriangles(faceVertices(it, minimum, maximum), texture) }
    }

    private fun faceTriangles(
        points: List<EngineVec3>,
        texture: EngineTexture,
    ): List<EngineSceneTriangle> =
        listOf(
            EngineSceneTriangle(EngineSceneVertex(points[0], UV[0]), EngineSceneVertex(points[1], UV[1]), EngineSceneVertex(points[2], UV[2]), texture),
            EngineSceneTriangle(EngineSceneVertex(points[0], UV[0]), EngineSceneVertex(points[2], UV[2]), EngineSceneVertex(points[3], UV[3]), texture),
        )

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

    private fun EngineSceneTriangle.rotateAround(
        pivot: EngineVec3,
        yaw: Double,
    ): EngineSceneTriangle {
        fun rotate(vertex: EngineSceneVertex) = vertex.copy(position = vertex.position.rotateAround(pivot, 'y', -yaw))
        return copy(a = rotate(a), b = rotate(b), c = rotate(c))
    }

    private fun intersectsFrustum(
        center: EngineVec3,
        radius: Double,
        camera: EngineCamera,
        width: Int,
        height: Int,
    ): Boolean {
        val yaw = camera.yaw * PI / 180.0
        val pitch = camera.pitch * PI / 180.0
        val forward = EngineVec3(-sin(yaw) * cos(pitch), -sin(pitch), cos(yaw) * cos(pitch)).normalized()
        val candidateRight = forward.cross(EngineVec3.UP)
        val right = if (candidateRight.dot(candidateRight) <= 1e-12) EngineVec3(1.0, 0.0, 0.0) else candidateRight.normalized()
        val up = right.cross(forward).normalized()
        val relative = center - camera.position
        val depth = relative.dot(forward)
        if (depth + radius < NEAR_PLANE || depth - radius > RENDER_DISTANCE) return false
        val vertical = tan(FIELD_OF_VIEW * PI / 360.0) * max(depth, NEAR_PLANE)
        val horizontal = vertical * width / height
        return kotlin.math.abs(relative.dot(up)) <= vertical + radius && kotlin.math.abs(relative.dot(right)) <= horizontal + radius
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

    private fun blockKey(
        x: Int,
        y: Int,
        z: Int,
    ): String = "$x,$y,$z"

    private fun entityHeight(type: String): Double =
        when (type.substringAfter(':')) {
            "item" -> 0.25
            "experience_orb" -> 0.3
            "block_display", "item_display" -> 1.0
            "text_display" -> 0.25
            else -> 1.8
        }

    private fun entityWidth(type: String): Double =
        when (type.substringAfter(':')) {
            "item" -> 0.25
            "experience_orb" -> 0.3
            "skeleton" -> 0.5
            "block_display", "item_display" -> 1.0
            "text_display" -> 1.0
            else -> 0.6
        }

    private data class ItemTransform(
        val translation: EngineVec3,
        val rotation: EngineVec3,
        val scale: EngineVec3,
    )

    companion object {
        private const val FIELD_OF_VIEW = 70.0
        private const val NEAR_PLANE = 0.05
        private const val RENDER_DISTANCE = 128.0
        private const val BLOCK_RADIUS = 0.8660254037844386
        private const val MAX_VISIBLE_ENTITIES = 512
        private const val MAX_ITEM_SPRITE_GRID = 64
        private const val TEXT_PIXELS_PER_BLOCK = 40.0
        private val DIRECTIONS = listOf("west", "east", "down", "up", "north", "south")
        private val HUMANOIDS = setOf("player", "zombie", "skeleton")
        private val UV = listOf(EngineVec2(0.0, 1.0), EngineVec2(1.0, 1.0), EngineVec2(1.0, 0.0), EngineVec2(0.0, 0.0))
    }
}
