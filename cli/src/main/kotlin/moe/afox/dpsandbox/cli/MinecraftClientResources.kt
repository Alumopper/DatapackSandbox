package moe.afox.dpsandbox.cli

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import moe.afox.dpsandbox.render.RenderAssets
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlin.math.ceil

internal class MinecraftClientResources(
    assets: RenderAssets,
) {
    private val sources = listOfNotNull(assets.minecraftAssets) + assets.resourcePacks
    private val byteCache = mutableMapOf<String, ByteArray?>()
    private val particleCache = mutableMapOf<String, List<MinecraftParticleSprite>>()

    fun defaultFont(): MinecraftBitmapFont = loadDefaultFont() ?: fallbackFont()

    fun particleSprites(rawId: String): List<MinecraftParticleSprite> {
        val id = normalizeResourceId(rawId.substringBefore('{').substringBefore('['))
        return particleCache.getOrPut(id) {
            val (namespace, path) = splitResourceId(id)
            val definition = json("assets/$namespace/particles/$path.json") ?: return@getOrPut emptyList()
            definition
                .getAsJsonArray("textures")
                ?.mapNotNull { element ->
                    element.takeIf { it.isJsonPrimitive }?.asString?.let(::normalizeResourceId)
                }?.distinct()
                ?.mapNotNull { textureId ->
                    val (textureNamespace, texturePath) = splitResourceId(textureId)
                    val bytes = bytes("assets/$textureNamespace/textures/particle/$texturePath.png") ?: return@mapNotNull null
                    val image = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return@mapNotNull null
                    MinecraftParticleSprite(textureId, firstAnimationFrame(image))
                }.orEmpty()
        }
    }

    private fun loadDefaultFont(): MinecraftBitmapFont? {
        val providers = mutableListOf<BitmapProvider>()
        collectBitmapProviders("minecraft:default", mutableSetOf(), providers)
        val provider =
            providers.maxByOrNull { candidate ->
                candidate.characters.sumOf { row -> row.codePoints().filter { it in 32..126 }.count() }
            } ?: return null
        val (namespace, path) = splitResourceId(provider.file)
        val imageBytes = bytes("assets/$namespace/textures/${path.removeSuffix(".png")}.png") ?: return null
        val image = runCatching { ImageIO.read(ByteArrayInputStream(imageBytes)) }.getOrNull() ?: return null
        val rows = provider.characters.size
        val columns = provider.characters.maxOfOrNull { it.codePointCount(0, it.length) } ?: return null
        if (rows == 0 || columns == 0 || image.width % columns != 0 || image.height % rows != 0) return null
        val cellWidth = image.width / columns
        val cellHeight = image.height / rows
        val renderedHeight = provider.height ?: 8
        val sourceScale = renderedHeight.toFloat() / cellHeight
        val glyphs = linkedMapOf<Int, MinecraftBitmapGlyph>()
        provider.characters.forEachIndexed { rowIndex, row ->
            row.codePoints().toArray().forEachIndexed { columnIndex, codePoint ->
                if (codePoint == 0) return@forEachIndexed
                val cellX = columnIndex * cellWidth
                val cellY = rowIndex * cellHeight
                val opaqueWidth = opaqueWidth(image, cellX, cellY, cellWidth, cellHeight)
                val advance = ceil(opaqueWidth * sourceScale).toInt() + 1
                glyphs[codePoint] =
                    MinecraftBitmapGlyph(
                        x = cellX,
                        y = cellY,
                        width = cellWidth,
                        height = cellHeight,
                        renderedWidth = cellWidth * sourceScale,
                        renderedHeight = renderedHeight.toFloat(),
                        advance = advance.toFloat(),
                        topOffset = (DEFAULT_ASCENT - provider.ascent).toFloat(),
                    )
            }
        }
        return MinecraftBitmapFont(image, glyphs, glyphs['?'.code], source = provider.file)
    }

    private fun collectBitmapProviders(
        fontId: String,
        visited: MutableSet<String>,
        sink: MutableList<BitmapProvider>,
    ) {
        val normalized = normalizeResourceId(fontId)
        if (!visited.add(normalized)) return
        val (namespace, path) = splitResourceId(normalized)
        val root = json("assets/$namespace/font/$path.json") ?: return
        root.getAsJsonArray("providers")?.forEach { element ->
            val provider = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            when (provider.string("type")?.substringAfter(':')) {
                "reference" -> provider.string("id")?.let { collectBitmapProviders(it, visited, sink) }
                "bitmap" -> {
                    val file = provider.string("file") ?: return@forEach
                    val chars = provider.getAsJsonArray("chars")?.mapNotNull { it.takeIf { value -> value.isJsonPrimitive }?.asString }.orEmpty()
                    if (chars.isNotEmpty()) {
                        sink += BitmapProvider(file, provider.int("height"), provider.int("ascent") ?: DEFAULT_ASCENT, chars)
                    }
                }
            }
        }
    }

    private fun fallbackFont(): MinecraftBitmapFont {
        val cellWidth = 12
        val cellHeight = 16
        val columns = 16
        val rows = 6
        val image = BufferedImage(columns * cellWidth, rows * cellHeight, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.font = Font(Font.MONOSPACED, Font.PLAIN, 14)
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            for (codePoint in 32..126) {
                val index = codePoint - 32
                graphics.drawString(codePoint.toChar().toString(), index % columns * cellWidth, index / columns * cellHeight + 13)
            }
        } finally {
            graphics.dispose()
        }
        val glyphs =
            (32..126).associateWith { codePoint ->
                val index = codePoint - 32
                MinecraftBitmapGlyph(
                    x = index % columns * cellWidth,
                    y = index / columns * cellHeight,
                    width = cellWidth,
                    height = cellHeight,
                    renderedWidth = 6f,
                    renderedHeight = 8f,
                    advance = 7f,
                    topOffset = 0f,
                )
            }
        return MinecraftBitmapFont(image, glyphs, glyphs.getValue('?'.code), source = "system fallback")
    }

    private fun bytes(key: String): ByteArray? =
        synchronized(byteCache) {
            if (byteCache.containsKey(key)) {
                byteCache[key]
            } else {
                sources.asReversed().firstNotNullOfOrNull { source -> readSource(source, key) }.also { byteCache[key] = it }
            }
        }

    private fun json(key: String): JsonObject? =
        bytes(key)?.let { raw ->
            runCatching { JsonParser.parseString(raw.toString(Charsets.UTF_8)).asJsonObject }.getOrNull()
        }

    private fun readSource(
        source: Path,
        key: String,
    ): ByteArray? =
        if (Files.isDirectory(source)) {
            source.resolve(key.replace('/', source.fileSystem.separator.first())).takeIf(Files::isRegularFile)?.let(Files::readAllBytes)
        } else {
            runCatching {
                ZipFile(source.toFile()).use { zip ->
                    zip.getEntry(key)?.let { entry -> zip.getInputStream(entry).use { it.readAllBytes() } }
                }
            }.getOrNull()
        }

    private fun opaqueWidth(
        image: BufferedImage,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): Int {
        for (column in width - 1 downTo 0) {
            for (row in 0 until height) {
                if (image.getRGB(x + column, y + row) ushr 24 != 0) return column + 1
            }
        }
        return 0
    }

    private fun firstAnimationFrame(image: BufferedImage): BufferedImage =
        if (image.height > image.width && image.height % image.width == 0) {
            image.getSubimage(0, 0, image.width, image.width)
        } else {
            image
        }

    private fun normalizeResourceId(id: String): String = if (':' in id) id else "minecraft:$id"

    private fun splitResourceId(id: String): Pair<String, String> = id.substringBefore(':') to id.substringAfter(':')

    private data class BitmapProvider(
        val file: String,
        val height: Int?,
        val ascent: Int,
        val characters: List<String>,
    )

    companion object {
        private const val DEFAULT_ASCENT = 7
    }
}

internal data class MinecraftBitmapFont(
    val image: BufferedImage,
    val glyphs: Map<Int, MinecraftBitmapGlyph>,
    val fallback: MinecraftBitmapGlyph?,
    val source: String,
)

internal data class MinecraftBitmapGlyph(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val renderedWidth: Float,
    val renderedHeight: Float,
    val advance: Float,
    val topOffset: Float,
)

internal data class MinecraftParticleSprite(
    val id: String,
    val image: BufferedImage,
)

private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString

private fun JsonObject.int(name: String): Int? = get(name)?.takeIf { it.isJsonPrimitive }?.asInt
