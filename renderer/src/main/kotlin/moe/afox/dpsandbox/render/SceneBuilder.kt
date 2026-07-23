package moe.afox.dpsandbox.render

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import moe.afox.dpsandbox.core.BlockPos
import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.SandboxException
import moe.afox.dpsandbox.render.engine.DisplayTextBitmap
import moe.afox.dpsandbox.render.engine.DisplayTextRasterizer
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

internal class SceneBuilder(
    private val resolver: AssetResolver,
    private val diagnostics: MutableList<RenderDiagnostic>,
) {
    private val diagnosedEntityTypes = mutableSetOf<String>()
    private val displayTextures = mutableMapOf<String, TextureData>()
    private var fontAtlasLoaded = false
    private var fontAtlas: DisplayTextBitmap? = null

    fun build(
        view: WorldView,
        request: RenderRequest,
        cull: Boolean = true,
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
                if (
                    !cull ||
                    (
                        distanceSquared(center, camera.position) <= request.renderDistance * request.renderDistance &&
                            intersectsFrustum(center, BLOCK_BOUNDING_RADIUS, camera, request)
                    )
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
            val position = entity.renderPosition()
            val display = entity.type.substringAfter(':').endsWith("_display")
            val viewRange =
                if (display) {
                    entity.nbt
                        .get("view_range")
                        ?.asDouble
                        ?.coerceAtLeast(0.0) ?: 1.0
                } else {
                    1.0
                }
            val maximumDistance = request.renderDistance * viewRange
            if (cull && distanceSquared(position, camera.position) > maximumDistance * maximumDistance) return@forEach
            val cullingWidth =
                if (display) {
                    entity.nbt
                        .get("width")
                        ?.asDouble
                        ?.takeIf { it > 0.0 }
                } else {
                    null
                }
            val cullingHeight =
                if (display) {
                    entity.nbt
                        .get("height")
                        ?.asDouble
                        ?.takeIf { it > 0.0 }
                } else {
                    null
                }
            val radius = max(cullingWidth?.div(2.0) ?: entityWidth(entity.type), cullingHeight ?: entityHeight(entity.type))
            if (cull &&
                !intersectsFrustum(
                    position + Vec3(0.0, (cullingHeight ?: entityHeight(entity.type)) / 2.0, 0.0),
                    radius,
                    camera,
                    request,
                )
            ) {
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
        val extent = max(max(maxPoint.x - min.x, maxPoint.y - min.y), maxPoint.z - min.z).coerceAtLeast(1.0)
        val distance = extent * 1.35 + 1.5
        val offset = Vec3(1.0, 0.72, 1.0).normalized() * distance
        return lookAt(target + offset, target, OVERWORLD, "auto:scene")
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
            blockDisplayTriangles(entity, modelBaker, camera)?.let { return it }
        }
        if (typePath == "item_display") return displayShadow(entity) + itemDisplayTriangles(entity, camera)
        if (typePath == "text_display") return displayShadow(entity) + textDisplayTriangles(entity, camera)
        val renderedPosition = entity.renderPosition()
        val texture =
            when (typePath) {
                "player" -> resolver.playerTexture(entity.name)
                "zombie" -> resolver.texture("minecraft:entity/zombie/zombie")
                "skeleton" -> resolver.texture("minecraft:entity/skeleton/skeleton")
                "item" -> resolver.proceduralTexture("item", 0xffffd45a.toInt(), 0xffb86b23.toInt())
                "experience_orb" -> resolver.proceduralTexture("experience_orb", 0xff9cff3c.toInt(), 0xff3a9c28.toInt())
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
            else -> cuboid(center, width, height, width, texture)
        }
    }

    private fun itemDisplayAsset(
        entity: RenderEntity,
        context: String,
    ): ItemRenderAsset? {
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
            return itemModelAsset(id, context)
        }
        return null
    }

    private fun itemModelAsset(
        id: String,
        context: String,
    ): ItemRenderAsset {
        val (namespace, path) = splitResourceId(id)
        val modelIds = linkedSetOf<String>()
        collectItemModelIds(resolver.json("assets/$namespace/items/$path.json"), modelIds)
        modelIds += "$namespace:item/$path"
        modelIds.forEach { modelId ->
            val model = resolveItemModel(modelId, linkedSetOf())
            val reference = model.textures["layer0"] ?: model.textures["particle"] ?: model.textures.values.firstOrNull()
            resolveTextureReference(reference, model.textures, linkedSetOf())?.let { textureId ->
                val (textureNamespace, texturePath) = splitResourceId(textureId)
                if (resolver.bytes("assets/$textureNamespace/textures/$texturePath.png") != null) {
                    return ItemRenderAsset(resolver.texture(textureId), model.displays[context]?.toItemTransform())
                }
            }
        }
        return ItemRenderAsset(resolver.texture("$namespace:item/$path"), null)
    }

    private fun collectItemModelIds(
        value: JsonElement?,
        result: MutableSet<String>,
    ) {
        when {
            value == null || value.isJsonNull -> Unit
            value.isJsonArray -> value.asJsonArray.forEach { collectItemModelIds(it, result) }
            value.isJsonObject ->
                value.asJsonObject.entrySet().forEach { (key, child) ->
                    if (key == "model" && child.isJsonPrimitive && child.asJsonPrimitive.isString) {
                        result += normalizeResourceId(child.asString)
                    } else {
                        collectItemModelIds(child, result)
                    }
                }
        }
    }

    private fun resolveItemModel(
        rawId: String,
        visited: MutableSet<String>,
    ): ResolvedItemModel {
        val id = normalizeResourceId(rawId)
        if (!visited.add(id)) return ResolvedItemModel()
        val (namespace, path) = splitResourceId(id)
        val model = resolver.json("assets/$namespace/models/$path.json") ?: return ResolvedItemModel()
        val textures = linkedMapOf<String, String>()
        val displays = linkedMapOf<String, JsonObject>()
        model
            .get("parent")
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
            ?.let { parent ->
                val inherited = resolveItemModel(parent, visited)
                textures.putAll(inherited.textures)
                displays.putAll(inherited.displays)
            }
        model.getAsJsonObject("textures")?.entrySet()?.forEach { (key, value) ->
            if (value.isJsonPrimitive) textures[key] = value.asString
        }
        model.getAsJsonObject("display")?.entrySet()?.forEach { (key, value) ->
            if (value.isJsonObject) displays[key] = value.asJsonObject
        }
        return ResolvedItemModel(textures, displays)
    }

    private fun JsonObject.toItemTransform(): ItemTransform =
        ItemTransform(
            translation = vector("translation", Vec3.ZERO) * (1.0 / 16.0),
            rotation = vector("rotation", Vec3.ZERO),
            scale = vector("scale", Vec3(1.0, 1.0, 1.0)),
        )

    private fun resolveTextureReference(
        value: String?,
        textures: Map<String, String>,
        visited: MutableSet<String>,
    ): String? {
        value ?: return null
        if (!value.startsWith('#')) return normalizeResourceId(value)
        val key = value.removePrefix("#")
        if (!visited.add(key)) return null
        return resolveTextureReference(textures[key], textures, visited)
    }

    private fun itemDisplayTriangles(
        entity: RenderEntity,
        camera: ResolvedCamera,
    ): List<SceneTriangle> {
        val context =
            entity.nbt
                .get("item_display")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString ?: "none"
        val asset = itemDisplayAsset(entity, context) ?: return emptyList()
        return generatedItemMesh(asset.texture).map { triangle ->
            transformDisplayTriangle(
                triangle,
                entity,
                camera,
                Vec3.ZERO,
                itemDisplayTransformation(context, asset.transform),
            )
        }
    }

    private fun textDisplayTriangles(
        entity: RenderEntity,
        camera: ResolvedCamera,
    ): List<SceneTriangle> {
        val content = entity.special?.getAsJsonObject("content")?.get("text")
        val text = plainText(content)
        val opacity = entity.special?.get("textOpacity")?.asInt ?: entity.nbt.get("text_opacity")?.asInt ?: 255
        val background = entity.special?.get("background")?.asInt ?: entity.nbt.get("background")?.asInt ?: 0x40000000
        val lineWidth = entity.nbt.get("line_width")?.asInt ?: 200
        val alignment =
            entity.nbt
                .get("alignment")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString ?: "center"
        val shadow =
            entity.nbt
                .get("shadow")
                ?.takeIf { it.isJsonPrimitive }
                ?.asBoolean ?: false
        val defaultBackground =
            entity.nbt
                .get("default_background")
                ?.takeIf { it.isJsonPrimitive }
                ?.asBoolean ?: false
        val seeThrough =
            entity.nbt
                .get("see_through")
                ?.takeIf { it.isJsonPrimitive }
                ?.asBoolean ?: false
        val color = textColor(content)
        val key = "$text|$opacity|$background|$lineWidth|$alignment|$shadow|$defaultBackground|$color"
        val texture =
            displayTextures.getOrPut("text:$key") {
                val bitmap =
                    DisplayTextRasterizer.render(
                        text = text,
                        lineWidth = lineWidth,
                        alignment = alignment,
                        background = background,
                        defaultBackground = defaultBackground,
                        textOpacity = opacity,
                        shadow = shadow,
                        textColor = color,
                        fontAtlas = displayFontAtlas(),
                    )
                texture(bitmap, "text:$key")
            }
        val width = texture.width / TEXT_PIXELS_PER_BLOCK
        val height = texture.height / TEXT_PIXELS_PER_BLOCK
        return doubleSidedPlane(width, height, texture).map { triangle ->
            transformDisplayTriangle(triangle, entity, camera, Vec3.ZERO).also { it.seeThrough = seeThrough }
        }
    }

    private fun displayFontAtlas(): DisplayTextBitmap? {
        if (fontAtlasLoaded) return fontAtlas
        fontAtlasLoaded = true
        val bytes = resolver.bytes("assets/minecraft/textures/font/ascii.png") ?: return null
        val image = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
        val pixels = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)
        return DisplayTextBitmap(image.width, image.height, pixels).also { fontAtlas = it }
    }

    private fun texture(
        bitmap: DisplayTextBitmap,
        id: String,
    ): TextureData {
        var transparent = false
        var partial = false
        bitmap.pixels.forEach { color ->
            val alpha = color ushr 24 and 0xff
            transparent = transparent || alpha == 0
            partial = partial || alpha in 1..254
        }
        val pass =
            when {
                partial -> MaterialPass.TRANSLUCENT
                transparent -> MaterialPass.CUTOUT
                else -> MaterialPass.OPAQUE
            }
        return TextureData(id, bitmap.width, bitmap.height, bitmap.pixels, pass)
    }

    private fun plainText(value: JsonElement?): String =
        when {
            value == null || value.isJsonNull -> ""
            value.isJsonArray -> value.asJsonArray.joinToString("") { plainText(it) }
            value.isJsonObject ->
                buildString {
                    append(
                        value.asJsonObject
                            .get("text")
                            ?.takeIf { it.isJsonPrimitive }
                            ?.asString
                            .orEmpty(),
                    )
                    if (isEmpty()) {
                        append(
                            value.asJsonObject
                                .get("translate")
                                ?.takeIf { it.isJsonPrimitive }
                                ?.asString
                                .orEmpty(),
                        )
                    }
                    value.asJsonObject.get("extra")?.let { append(plainText(it)) }
                }
            value.isJsonPrimitive -> {
                val raw = value.asString
                val parsed =
                    raw
                        .trim()
                        .takeIf { (it.startsWith('{') && it.endsWith('}')) || (it.startsWith('[') && it.endsWith(']')) }
                        ?.let { runCatching { JsonParser.parseString(it) }.getOrNull() }
                if (parsed == null) raw else plainText(parsed)
            }
            else -> value.toString()
        }

    private fun textColor(value: JsonElement?): Int {
        val parsed =
            when {
                value?.isJsonObject == true -> value.asJsonObject
                value?.isJsonPrimitive == true ->
                    value.asString.trim().takeIf { it.startsWith('{') }?.let {
                        runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull()
                    }
                else -> null
            }
        val color = parsed?.get("color")?.takeIf { it.isJsonPrimitive }?.asString ?: return 0xffffffff.toInt()
        return TEXT_COLORS[color.lowercase()] ?: color.removePrefix("#").toIntOrNull(16)?.let { 0xff000000.toInt() or it }
            ?: 0xffffffff.toInt()
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
        camera: ResolvedCamera,
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
        val localBase = Vec3(base.x.toDouble(), base.y.toDouble(), base.z.toDouble())
        val content =
            modelBaker.bake(RenderBlock(base, id, properties)).map { triangle ->
                transformDisplayTriangle(triangle, entity, camera, localBase)
            }
        return displayShadow(entity) + content
    }

    private fun doubleSidedPlane(
        width: Double,
        height: Double,
        texture: TextureData,
    ): List<SceneTriangle> {
        val left = -width / 2.0
        val right = width / 2.0
        val bottom = -height / 2.0
        val top = height / 2.0
        val front =
            listOf(
                SceneTriangle(
                    SceneVertex(Vec3(left, bottom, 0.0), UV[0]),
                    SceneVertex(Vec3(right, top, 0.0), UV[2]),
                    SceneVertex(Vec3(left, top, 0.0), UV[3]),
                    texture,
                ),
                SceneTriangle(
                    SceneVertex(Vec3(left, bottom, 0.0), UV[0]),
                    SceneVertex(Vec3(right, bottom, 0.0), UV[1]),
                    SceneVertex(Vec3(right, top, 0.0), UV[2]),
                    texture,
                ),
            )
        return front + front.map { triangle -> SceneTriangle(triangle.c, triangle.b, triangle.a, triangle.texture) }
    }

    private fun generatedItemMesh(texture: TextureData): List<SceneTriangle> {
        val depth = 1.0 / 16.0
        val frontZ = depth / 2.0
        val backZ = -frontZ
        val triangles = mutableListOf<SceneTriangle>()
        triangles +=
            itemFace(
                listOf(
                    Vec3(-0.5, -0.5, frontZ),
                    Vec3(0.5, -0.5, frontZ),
                    Vec3(0.5, 0.5, frontZ),
                    Vec3(-0.5, 0.5, frontZ),
                ),
                texture,
            )
        triangles +=
            itemFace(
                listOf(
                    Vec3(0.5, -0.5, backZ),
                    Vec3(-0.5, -0.5, backZ),
                    Vec3(-0.5, 0.5, backZ),
                    Vec3(0.5, 0.5, backZ),
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
                val uv = Vec2((x + 0.5) / gridWidth, (y + 0.5) / gridHeight)
                if (!opaque(x - 1, y)) {
                    triangles +=
                        sideQuad(
                            listOf(Vec3(left, bottom, backZ), Vec3(left, bottom, frontZ), Vec3(left, top, frontZ), Vec3(left, top, backZ)),
                            uv,
                            texture,
                        )
                }
                if (!opaque(x + 1, y)) {
                    triangles +=
                        sideQuad(
                            listOf(Vec3(right, bottom, frontZ), Vec3(right, bottom, backZ), Vec3(right, top, backZ), Vec3(right, top, frontZ)),
                            uv,
                            texture,
                        )
                }
                if (!opaque(x, y - 1)) {
                    triangles +=
                        sideQuad(
                            listOf(Vec3(left, top, frontZ), Vec3(right, top, frontZ), Vec3(right, top, backZ), Vec3(left, top, backZ)),
                            uv,
                            texture,
                        )
                }
                if (!opaque(x, y + 1)) {
                    triangles +=
                        sideQuad(
                            listOf(Vec3(left, bottom, backZ), Vec3(right, bottom, backZ), Vec3(right, bottom, frontZ), Vec3(left, bottom, frontZ)),
                            uv,
                            texture,
                        )
                }
            }
        }
        return triangles
    }

    private fun itemFace(
        points: List<Vec3>,
        texture: TextureData,
    ): List<SceneTriangle> =
        listOf(
            SceneTriangle(SceneVertex(points[0], UV[0]), SceneVertex(points[1], UV[1]), SceneVertex(points[2], UV[2]), texture),
            SceneTriangle(SceneVertex(points[0], UV[0]), SceneVertex(points[2], UV[2]), SceneVertex(points[3], UV[3]), texture),
        )

    private fun sideQuad(
        points: List<Vec3>,
        uv: Vec2,
        texture: TextureData,
    ): List<SceneTriangle> {
        val vertices = points.map { SceneVertex(it, uv) }
        return listOf(
            SceneTriangle(vertices[0], vertices[1], vertices[2], texture),
            SceneTriangle(vertices[0], vertices[2], vertices[3], texture),
        )
    }

    private fun displayShadow(entity: RenderEntity): List<SceneTriangle> {
        val radius = entity.special?.get("shadowRadius")?.asDouble ?: entity.nbt.get("shadow_radius")?.asDouble ?: 0.0
        val strength = entity.special?.get("shadowStrength")?.asDouble ?: entity.nbt.get("shadow_strength")?.asDouble ?: 1.0
        if (radius <= 0.0 || strength <= 0.0) return emptyList()
        val texture =
            displayTextures.getOrPut("shadow:${(strength * 1000).toInt()}") {
                val size = 16
                val alpha = (strength.coerceIn(0.0, 1.0) * 150).toInt()
                val pixels =
                    IntArray(size * size) { index ->
                        val x = (index % size + 0.5 - size / 2.0) / (size / 2.0)
                        val y = (index / size + 0.5 - size / 2.0) / (size / 2.0)
                        ((1.0 - sqrt(x * x + y * y)).coerceIn(0.0, 1.0) * alpha).toInt() shl 24
                    }
                TextureData("display-shadow", size, size, pixels, MaterialPass.TRANSLUCENT)
            }
        val position = entity.renderPosition()
        val y = position.y + 0.002
        val points =
            listOf(
                Vec3(position.x - radius, y, position.z - radius),
                Vec3(position.x - radius, y, position.z + radius),
                Vec3(position.x + radius, y, position.z + radius),
                Vec3(position.x + radius, y, position.z - radius),
            )
        return listOf(
            SceneTriangle(SceneVertex(points[0], UV[0]), SceneVertex(points[1], UV[3]), SceneVertex(points[2], UV[2]), texture),
            SceneTriangle(SceneVertex(points[0], UV[0]), SceneVertex(points[2], UV[2]), SceneVertex(points[3], UV[1]), texture),
        )
    }

    private fun transformDisplayTriangle(
        triangle: SceneTriangle,
        entity: RenderEntity,
        camera: ResolvedCamera,
        localBase: Vec3,
        contentTransformation: List<Double> = IDENTITY_MATRIX,
    ): SceneTriangle {
        val origin = entity.renderPosition()
        val transformation = entity.renderTransformation()

        fun vertex(value: SceneVertex): SceneVertex {
            val local = value.position - localBase
            val content = transform(contentTransformation, local)
            val model = transform(transformation, content)
            val oriented = orientDisplay(model, entity, camera)
            return value.copy(position = origin + oriented)
        }
        return triangle
            .copy(
                a = vertex(triangle.a),
                b = vertex(triangle.b),
                c = vertex(triangle.c),
            ).also { transformed ->
                transformed.lightOverride = displayLight(entity)
                transformed.seeThrough = triangle.seeThrough
            }
    }

    private fun orientDisplay(
        value: Vec3,
        entity: RenderEntity,
        camera: ResolvedCamera,
    ): Vec3 {
        val billboard =
            entity.nbt
                .get("billboard")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString ?: "fixed"
        val origin = entity.renderPosition()
        val facing = (camera.position - origin).normalized()
        val horizontalLength = sqrt(facing.x * facing.x + facing.z * facing.z)
        val lookYaw = Math.toDegrees(atan2(facing.x, facing.z))
        val lookPitch = -Math.toDegrees(atan2(facing.y, horizontalLength))
        val renderedRotation = entity.renderRotation()
        val yaw = if (billboard == "vertical" || billboard == "center") lookYaw else -renderedRotation.first
        val pitch = if (billboard == "horizontal" || billboard == "center") lookPitch else renderedRotation.second
        return value.rotateAround(Vec3.ZERO, 'x', pitch).rotateAround(Vec3.ZERO, 'y', yaw)
    }

    private fun displayLight(entity: RenderEntity): Double? {
        val brightness =
            entity.nbt
                .get("brightness")
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject ?: return null
        val values = listOf("sky", "block").mapNotNull { key -> brightness.get(key)?.takeIf { it.isJsonPrimitive }?.asInt }
        return values.maxOrNull()?.coerceIn(0, 15)?.div(15.0)
    }

    private fun itemDisplayTransformation(
        context: String,
        model: ItemTransform?,
    ): List<Double> {
        val transform =
            model ?: when (context) {
                "ground" -> ItemTransform(Vec3(0.0, 0.125, 0.0), Vec3(90.0, 0.0, 0.0), Vec3(0.5, 0.5, 0.5))
                "head" -> ItemTransform(Vec3(0.0, 0.25, 0.0), Vec3(0.0, 180.0, 0.0), Vec3(0.625, 0.625, 0.625))
                "gui" -> ItemTransform(Vec3.ZERO, Vec3(30.0, 225.0, 0.0), Vec3(0.625, 0.625, 0.625))
                "fixed" -> ItemTransform(Vec3.ZERO, Vec3(0.0, 180.0, 0.0), Vec3(0.5, 0.5, 0.5))
                "thirdperson_lefthand" -> ItemTransform(Vec3(0.0, 0.1875, 0.0), Vec3(75.0, 45.0, 0.0), Vec3(0.55, 0.55, 0.55))
                "thirdperson_righthand" -> ItemTransform(Vec3(0.0, 0.1875, 0.0), Vec3(75.0, -45.0, 0.0), Vec3(0.55, 0.55, 0.55))
                "firstperson_lefthand" -> ItemTransform(Vec3(0.1, 0.0, 0.0), Vec3(0.0, 225.0, 0.0), Vec3(0.68, 0.68, 0.68))
                "firstperson_righthand" -> ItemTransform(Vec3(-0.1, 0.0, 0.0), Vec3(0.0, 135.0, 0.0), Vec3(0.68, 0.68, 0.68))
                else -> ItemTransform(Vec3.ZERO, Vec3.ZERO, Vec3(1.0, 1.0, 1.0))
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

    private fun scaleMatrix(value: Double): List<Double> = scaleMatrix(Vec3(value, value, value))

    private fun rotationXMatrix(degrees: Double): List<Double> {
        val radians = Math.toRadians(degrees)
        val c = cos(radians)
        val s = sin(radians)
        return listOf(
            1.0,
            0.0,
            0.0,
            0.0,
            0.0,
            c,
            -s,
            0.0,
            0.0,
            s,
            c,
            0.0,
            0.0,
            0.0,
            0.0,
            1.0,
        )
    }

    private fun rotationYMatrix(degrees: Double): List<Double> {
        val radians = Math.toRadians(degrees)
        val c = cos(radians)
        val s = sin(radians)
        return listOf(
            c,
            0.0,
            s,
            0.0,
            0.0,
            1.0,
            0.0,
            0.0,
            -s,
            0.0,
            c,
            0.0,
            0.0,
            0.0,
            0.0,
            1.0,
        )
    }

    private fun rotationZMatrix(degrees: Double): List<Double> {
        val radians = Math.toRadians(degrees)
        val c = cos(radians)
        val s = sin(radians)
        return listOf(
            c,
            -s,
            0.0,
            0.0,
            s,
            c,
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

    private data class ItemTransform(
        val translation: Vec3,
        val rotation: Vec3,
        val scale: Vec3,
    )

    private data class ItemRenderAsset(
        val texture: TextureData,
        val transform: ItemTransform?,
    )

    private data class ResolvedItemModel(
        val textures: Map<String, String> = emptyMap(),
        val displays: Map<String, JsonObject> = emptyMap(),
    )

    companion object {
        private const val OVERWORLD = "minecraft:overworld"
        private const val PLAYER_EYE_HEIGHT = 1.62
        private const val BLOCK_BOUNDING_RADIUS = 0.8660254037844386
        private const val MAX_ITEM_SPRITE_GRID = 64
        private const val TEXT_PIXELS_PER_BLOCK = 40.0
        private val HIDDEN_ENTITIES = setOf("minecraft:marker", "minecraft:interaction")
        private val HUMANOID_ENTITIES = setOf("player", "zombie", "skeleton")
        private val MODELED_ENTITIES = HUMANOID_ENTITIES + setOf("block_display", "item_display", "text_display")
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
        private val TEXT_COLORS =
            mapOf(
                "black" to 0xff000000.toInt(),
                "dark_blue" to 0xff0000aa.toInt(),
                "dark_green" to 0xff00aa00.toInt(),
                "dark_aqua" to 0xff00aaaa.toInt(),
                "dark_red" to 0xffaa0000.toInt(),
                "dark_purple" to 0xffaa00aa.toInt(),
                "gold" to 0xffffaa00.toInt(),
                "gray" to 0xffaaaaaa.toInt(),
                "dark_gray" to 0xff555555.toInt(),
                "blue" to 0xff5555ff.toInt(),
                "green" to 0xff55ff55.toInt(),
                "aqua" to 0xff55ffff.toInt(),
                "red" to 0xffff5555.toInt(),
                "light_purple" to 0xffff55ff.toInt(),
                "yellow" to 0xffffff55.toInt(),
                "white" to 0xffffffff.toInt(),
            )
    }
}
