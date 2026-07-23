package moe.afox.dpsandbox.render.engine

import kotlin.math.floor
import kotlin.math.sqrt

internal data class EngineItemRenderAsset(
    val texture: EngineTexture,
    val transform: EngineItemModelTransform?,
)

internal data class EngineItemModelTransform(
    val translation: EngineVec3,
    val rotation: EngineVec3,
    val scale: EngineVec3,
)

private data class EngineResolvedItemModel(
    val textures: Map<String, String> = emptyMap(),
    val displays: Map<String, EngineJsonObject> = emptyMap(),
)

internal class EngineTextureStore {
    private val imported = mutableMapOf<String, EngineTexture>()
    private val generated = mutableMapOf<String, EngineTexture>()
    private val assetTexts = mutableMapOf<String, String>()
    private val parsedAssets = mutableMapOf<String, EngineJsonObject?>()

    fun register(
        id: String,
        width: Int,
        height: Int,
        rgba: ByteArray,
    ) {
        require(width > 0 && height > 0) { "Texture dimensions must be positive" }
        require(rgba.size == width * height * 4) { "Texture $id has ${rgba.size} RGBA bytes; expected ${width * height * 4}" }
        val pixels = IntArray(width * height)
        var hasTransparent = false
        var hasPartial = false
        pixels.indices.forEach { index ->
            val offset = index * 4
            val red = rgba[offset].toInt() and 0xff
            val green = rgba[offset + 1].toInt() and 0xff
            val blue = rgba[offset + 2].toInt() and 0xff
            val alpha = rgba[offset + 3].toInt() and 0xff
            hasTransparent = hasTransparent || alpha == 0
            hasPartial = hasPartial || alpha in 1..254
            pixels[index] = argb(alpha, red, green, blue)
        }
        val pass =
            when {
                hasPartial -> EngineMaterialPass.TRANSLUCENT
                hasTransparent -> EngineMaterialPass.CUTOUT
                else -> EngineMaterialPass.OPAQUE
            }
        imported[normalizeTextureId(id)] = EngineTexture(id, width, height, pixels, pass)
    }

    fun clear() {
        imported.clear()
        generated.clear()
        assetTexts.clear()
        parsedAssets.clear()
    }

    fun registerAssetText(
        path: String,
        text: String,
    ) {
        val normalized = path.replace('\\', '/').removePrefix("/")
        assetTexts[normalized] = text
        parsedAssets.remove(normalized)
    }

    fun json(path: String): EngineJsonObject? {
        val normalized = path.replace('\\', '/').removePrefix("/")
        if (normalized in parsedAssets) return parsedAssets[normalized]
        return assetTexts[normalized]?.let { EngineJsonParser(it).parseObject() }.also { parsedAssets[normalized] = it }
    }

    fun texture(id: String): EngineTexture {
        val normalized = normalizeTextureId(id)
        return imported[normalized] ?: fallback(normalized)
    }

    fun blockTexture(
        blockId: String,
        direction: String,
    ): EngineTexture {
        val (namespace, path) = splitResourceId(blockId)
        val candidates =
            buildList {
                when {
                    path == "grass_block" && direction == "up" -> add("$namespace:block/grass_block_top")
                    path == "grass_block" && direction == "down" -> add("$namespace:block/dirt")
                    path == "grass_block" -> add("$namespace:block/grass_block_side")
                    (path.endsWith("_log") || path.endsWith("_stem") || path.endsWith("_hyphae")) && direction in setOf("up", "down") -> {
                        add("$namespace:block/${path}_top")
                    }
                }
                add("$namespace:block/$path")
            }
        return candidates.firstNotNullOfOrNull(imported::get) ?: texture("$namespace:block/$path")
    }

    fun entityTexture(type: String): EngineTexture {
        val path = type.substringAfter(':')
        val importedId =
            when (path) {
                "zombie" -> "minecraft:entity/zombie/zombie"
                "skeleton" -> "minecraft:entity/skeleton/skeleton"
                else -> null
            }
        importedId?.let(imported::get)?.let { return it }
        val colors =
            when (path) {
                "player" -> 0xff4da6d8.toInt() to 0xff254f73.toInt()
                "item" -> 0xffffd45a.toInt() to 0xffb86b23.toInt()
                "experience_orb" -> 0xff9cff3c.toInt() to 0xff3a9c28.toInt()
                else -> 0xffd15b5b.toInt() to 0xff702828.toInt()
            }
        return checker("entity:$path", colors.first, colors.second)
    }

    fun itemAsset(
        id: String,
        context: String,
    ): EngineItemRenderAsset {
        val (namespace, path) = splitResourceId(id)
        val modelIds = linkedSetOf<String>()
        collectItemModelIds(json("assets/$namespace/items/$path.json"), modelIds)
        modelIds += "$namespace:item/$path"
        modelIds.forEach { modelId ->
            val model = resolveItemModel(modelId, linkedSetOf())
            val reference = model.textures["layer0"] ?: model.textures["particle"] ?: model.textures.values.firstOrNull()
            resolveTextureReference(reference, model.textures, linkedSetOf())?.let { textureId ->
                imported[normalizeTextureId(textureId)]?.let { texture ->
                    return EngineItemRenderAsset(texture, model.displays[context]?.toItemTransform())
                }
            }
        }
        return EngineItemRenderAsset(imported["$namespace:item/$path"] ?: texture("$namespace:item/$path"), null)
    }

    fun displayTextTexture(display: EngineDisplayData): EngineTexture {
        val key =
            listOf(
                display.text,
                display.lineWidth,
                display.alignment,
                display.background,
                display.defaultBackground,
                display.textOpacity,
                display.textShadow,
                display.textColor,
            ).joinToString("|")
        return generated.getOrPut("display-text:$key") {
            val font = imported["minecraft:font/ascii"]?.let { DisplayTextBitmap(it.width, it.height, it.pixels.copyOf()) }
            val bitmap =
                DisplayTextRasterizer.render(
                    text = display.text,
                    lineWidth = display.lineWidth,
                    alignment = display.alignment,
                    background = display.background,
                    defaultBackground = display.defaultBackground,
                    textOpacity = display.textOpacity,
                    shadow = display.textShadow,
                    textColor = display.textColor,
                    fontAtlas = font,
                )
            textureFromBitmap("display-text:$key", bitmap)
        }
    }

    fun shadowTexture(strength: Double): EngineTexture {
        val alpha = (strength.coerceIn(0.0, 1.0) * 150).toInt()
        return generated.getOrPut("display-shadow:$alpha") {
            val size = 16
            val pixels =
                IntArray(size * size) { index ->
                    val x = (index % size + 0.5 - size / 2.0) / (size / 2.0)
                    val y = (index / size + 0.5 - size / 2.0) / (size / 2.0)
                    val distance = sqrt(x * x + y * y)
                    val value = ((1.0 - distance).coerceIn(0.0, 1.0) * alpha).toInt()
                    value shl 24
                }
            EngineTexture("display-shadow:$alpha", size, size, pixels, EngineMaterialPass.TRANSLUCENT)
        }
    }

    private fun fallback(id: String): EngineTexture =
        generated.getOrPut("fallback:$id") {
            val hash = id.hashCode()
            val hue = (hash ushr 1 and 0xffff) / 65535f
            if (id == "minecraft:block/stone") return@getOrPut stoneFallback("fallback:$id")
            if (id == "minecraft:block/diamond_block") return@getOrPut diamondBlockFallback("fallback:$id")
            val colors =
                when (id) {
                    "minecraft:item/diamond" -> 0xff7df5e8.toInt() to 0xff29aaa3.toInt()
                    else -> hsb(hue, 0.45f, 0.82f) to hsb(hue, 0.55f, 0.48f)
                }
            if (":item/" in id) itemSilhouette("fallback:$id", colors.first, colors.second) else checkerTexture("fallback:$id", colors.first, colors.second)
        }

    private fun itemSilhouette(
        id: String,
        primary: Int,
        secondary: Int,
    ): EngineTexture {
        val size = 16
        val pixels =
            IntArray(size * size) { index ->
                val x = index % size
                val y = index / size
                val distance = kotlin.math.abs(x * 2 - (size - 1)) + kotlin.math.abs(y * 2 - (size - 1))
                when {
                    distance > 14 -> 0
                    y < size / 2 || x in 6..9 -> primary
                    else -> secondary
                }
            }
        return EngineTexture(id, size, size, pixels, EngineMaterialPass.CUTOUT)
    }

    private fun stoneFallback(id: String): EngineTexture {
        val size = 16
        val pixels =
            IntArray(size * size) { index ->
                val x = index % size
                val y = index / size
                val noise = ((x * 37 + y * 57 + x * y * 11) xor (x shl 3) xor (y shl 5)) and 31
                val value = 105 + noise
                argb(255, value + 3, value + 2, value)
            }
        return EngineTexture(id, size, size, pixels, EngineMaterialPass.OPAQUE)
    }

    private fun diamondBlockFallback(id: String): EngineTexture {
        val size = 16
        val pixels =
            IntArray(size * size) { index ->
                val x = index % size
                val y = index / size
                when {
                    x == 0 || y == 0 || x == size - 1 || y == size - 1 -> 0xff168f8b.toInt()
                    x == y || x + y == size - 1 -> 0xffb4fff7.toInt()
                    else -> 0xff55d8cf.toInt()
                }
            }
        return EngineTexture(id, size, size, pixels, EngineMaterialPass.OPAQUE)
    }

    private fun collectItemModelIds(
        value: EngineJsonValue?,
        result: MutableSet<String>,
    ) {
        when (value) {
            is EngineJsonArray -> value.values.forEach { collectItemModelIds(it, result) }
            is EngineJsonObject ->
                value.values.forEach { (key, child) ->
                    if (key == "model" && child is EngineJsonString) {
                        result += normalizeResourceId(child.value)
                    } else {
                        collectItemModelIds(child, result)
                    }
                }
            else -> Unit
        }
    }

    private fun resolveItemModel(
        rawId: String,
        visited: MutableSet<String>,
    ): EngineResolvedItemModel {
        val id = normalizeResourceId(rawId)
        if (!visited.add(id)) return EngineResolvedItemModel()
        val (namespace, path) = splitResourceId(id)
        val model = json("assets/$namespace/models/$path.json") ?: return EngineResolvedItemModel()
        val textures = linkedMapOf<String, String>()
        val displays = linkedMapOf<String, EngineJsonObject>()
        model.string("parent")?.let { parent ->
            val inherited = resolveItemModel(parent, visited)
            textures.putAll(inherited.textures)
            displays.putAll(inherited.displays)
        }
        model.objectValue("textures")?.values?.forEach { (key, value) ->
            if (value is EngineJsonString) textures[key] = value.value
        }
        model.objectValue("display")?.values?.forEach { (key, value) ->
            if (value is EngineJsonObject) displays[key] = value
        }
        return EngineResolvedItemModel(textures, displays)
    }

    private fun EngineJsonObject.toItemTransform(): EngineItemModelTransform =
        EngineItemModelTransform(
            translation = (vector("translation") ?: EngineVec3.ZERO) * (1.0 / 16.0),
            rotation = vector("rotation") ?: EngineVec3.ZERO,
            scale = vector("scale") ?: EngineVec3(1.0, 1.0, 1.0),
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

    private fun checker(
        id: String,
        primary: Int,
        secondary: Int,
    ): EngineTexture = generated.getOrPut(id) { checkerTexture(id, primary, secondary) }

    private fun checkerTexture(
        id: String,
        primary: Int,
        secondary: Int,
    ): EngineTexture =
        EngineTexture(
            id = id,
            width = 16,
            height = 16,
            pixels = IntArray(16 * 16) { index -> if (((index % 16) / 4 + (index / 16) / 4) % 2 == 0) primary else secondary },
            materialPass = EngineMaterialPass.OPAQUE,
        )

    private fun textureFromBitmap(
        id: String,
        bitmap: DisplayTextBitmap,
    ): EngineTexture {
        var transparent = false
        var partial = false
        bitmap.pixels.forEach { color ->
            val alpha = color ushr 24 and 0xff
            transparent = transparent || alpha == 0
            partial = partial || alpha in 1..254
        }
        val pass =
            when {
                partial -> EngineMaterialPass.TRANSLUCENT
                transparent -> EngineMaterialPass.CUTOUT
                else -> EngineMaterialPass.OPAQUE
            }
        return EngineTexture(id, bitmap.width, bitmap.height, bitmap.pixels, pass)
    }

    private fun hsb(
        hue: Float,
        saturation: Float,
        brightness: Float,
    ): Int {
        if (saturation == 0f) {
            val value = (brightness * 255f + 0.5f).toInt()
            return argb(255, value, value, value)
        }
        val scaled = (hue - floor(hue)) * 6f
        val sector = scaled.toInt()
        val fraction = scaled - sector
        val p = brightness * (1f - saturation)
        val q = brightness * (1f - saturation * fraction)
        val t = brightness * (1f - saturation * (1f - fraction))
        val (red, green, blue) =
            when (sector) {
                0 -> Triple(brightness, t, p)
                1 -> Triple(q, brightness, p)
                2 -> Triple(p, brightness, t)
                3 -> Triple(p, q, brightness)
                4 -> Triple(t, p, brightness)
                else -> Triple(brightness, p, q)
            }
        return argb(255, (red * 255f + 0.5f).toInt(), (green * 255f + 0.5f).toInt(), (blue * 255f + 0.5f).toInt())
    }

    private fun normalizeTextureId(id: String): String {
        val normalized = if (':' in id) id else "minecraft:$id"
        val (namespace, path) = splitResourceId(normalized)
        return "$namespace:${path.removePrefix("textures/").removeSuffix(".png")}"
    }

    private fun normalizeResourceId(id: String): String = if (':' in id) id else "minecraft:$id"

    private fun splitResourceId(id: String): Pair<String, String> =
        if (':' in id) id.substringBefore(':') to id.substringAfter(':') else "minecraft" to id

    private fun argb(
        alpha: Int,
        red: Int,
        green: Int,
        blue: Int,
    ): Int =
        (alpha.coerceIn(0, 255) shl 24) or
            (red.coerceIn(0, 255) shl 16) or
            (green.coerceIn(0, 255) shl 8) or
            blue.coerceIn(0, 255)
}
