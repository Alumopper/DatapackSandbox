package moe.afox.dpsandbox.render

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.SandboxException
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import javax.imageio.ImageIO

internal class AssetResolver(
    assets: RenderAssets,
    private val diagnostics: MutableList<RenderDiagnostic>,
    private val strict: Boolean,
    private val gameTime: Long = 0,
    private val bytesCache: MutableMap<String, ByteArray?> = mutableMapOf(),
) {
    private val playerSkins = assets.playerSkins.mapKeys { it.key.lowercase() }
    private val sources =
        buildList {
            assets.minecraftAssets?.let { add(AssetSource(it, "minecraft-assets")) }
            assets.resourcePacks.forEach { add(AssetSource(it, "resource-pack")) }
        }
    private val textureCache = mutableMapOf<String, TextureData>()
    private val missingDiagnostics = mutableSetOf<String>()
    private var measurementDepth = 0

    var assetResolveNanos: Long = 0
        private set

    val sourceDescriptions: List<String> = sources.map { "${it.kind}:${it.path.toAbsolutePath().normalize()}" }

    fun json(key: String): JsonObject? =
        measured {
            val bytes = bytes(key) ?: return@measured null
            try {
                val parsed = JsonParser.parseString(bytes.toString(Charsets.UTF_8))
                if (!parsed.isJsonObject) {
                    invalid("INVALID_JSON_ASSET", "Asset must contain a JSON object", key)
                    null
                } else {
                    parsed.asJsonObject
                }
            } catch (error: Exception) {
                invalid("INVALID_JSON_ASSET", "Invalid JSON asset: ${error.message}", key)
                null
            }
        }

    fun texture(id: String): TextureData =
        measured {
            val normalizedId = normalizeResourceId(id)
            textureCache.getOrPut(normalizedId) {
                val (namespace, path) = splitResourceId(normalizedId)
                val key = "assets/$namespace/textures/$path.png"
                val bytes = bytes(key)
                val image =
                    bytes?.let {
                        try {
                            ImageIO.read(ByteArrayInputStream(it))
                        } catch (_: Exception) {
                            null
                        }
                    }
                if (image == null) {
                    missing("MISSING_TEXTURE", "Missing or unreadable texture $normalizedId", key)
                    fallbackTexture(normalizedId)
                } else {
                    TextureData.fromImage(normalizedId, animatedFrame(image, key))
                }
            }
        }

    fun playerTexture(name: String?): TextureData =
        measured {
            val path = name?.lowercase()?.let(playerSkins::get)
            if (path != null) {
                val image =
                    try {
                        ImageIO.read(path.toFile())
                    } catch (_: Exception) {
                        null
                    }
                if (image != null) return@measured TextureData.fromImage("player:${name.orEmpty().lowercase()}", image)
                invalid("INVALID_PLAYER_SKIN", "Player skin is not a readable PNG", path.toString())
            }
            proceduralTexture("player:${name ?: "unknown"}", 0xff4da6d8.toInt(), 0xff254f73.toInt())
        }

    fun proceduralTexture(
        id: String,
        primary: Int,
        secondary: Int,
    ): TextureData =
        textureCache.getOrPut("procedural:$id") {
            val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until 16) {
                for (x in 0 until 16) {
                    image.setRGB(x, y, if ((x / 4 + y / 4) % 2 == 0) primary else secondary)
                }
            }
            TextureData.fromImage("procedural:$id", image)
        }

    fun bytes(key: String): ByteArray? =
        measured {
            val safe = normalizeAssetKey(key)
            synchronized(bytesCache) {
                if (bytesCache.containsKey(safe)) {
                    bytesCache[safe]
                } else {
                    sources
                        .asReversed()
                        .firstNotNullOfOrNull { source -> source.read(safe) }
                        .also { bytesCache[safe] = it }
                }
            }
        }

    private inline fun <T> measured(block: () -> T): T {
        val outermost = measurementDepth == 0
        measurementDepth += 1
        val started = if (outermost) System.nanoTime() else 0L
        return try {
            block()
        } finally {
            measurementDepth -= 1
            if (outermost) assetResolveNanos += System.nanoTime() - started
        }
    }

    fun missing(
        code: String,
        message: String,
        resource: String,
    ) {
        if (!missingDiagnostics.add("$code:$resource")) return
        if (strict) {
            throw SandboxException(DiagnosticCode.RESOURCE_NOT_FOUND, "$message ($resource)")
        }
        diagnostics += RenderDiagnostic(RenderDiagnosticSeverity.WARNING, code, message, resource)
    }

    fun invalid(
        code: String,
        message: String,
        resource: String,
    ) {
        if (strict) throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$message ($resource)")
        diagnostics += RenderDiagnostic(RenderDiagnosticSeverity.WARNING, code, message, resource)
    }

    private fun fallbackTexture(id: String): TextureData {
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        val hash = id.hashCode()
        val hue = (hash ushr 1 and 0xffff) / 65535f
        val generatedBase = Color.HSBtoRGB(hue, 0.45f, 0.82f) or (0xff shl 24)
        val generatedDark = Color.HSBtoRGB(hue, 0.55f, 0.48f) or (0xff shl 24)
        val (base, dark) =
            when (id) {
                "minecraft:item/diamond" -> 0xff7df5e8.toInt() to 0xff29aaa3.toInt()
                else -> generatedBase to generatedDark
            }
        for (y in 0 until 16) {
            for (x in 0 until 16) {
                val color =
                    if (":item/" in id) {
                        val distance = kotlin.math.abs(x * 2 - 15) + kotlin.math.abs(y * 2 - 15)
                        when {
                            distance > 14 -> 0
                            y < 8 || x in 6..9 -> base
                            else -> dark
                        }
                    } else if (id == "minecraft:block/stone") {
                        val noise = ((x * 37 + y * 57 + x * y * 11) xor (x shl 3) xor (y shl 5)) and 31
                        val value = 105 + noise
                        (0xff shl 24) or ((value + 3) shl 16) or ((value + 2) shl 8) or value
                    } else if (id == "minecraft:block/diamond_block") {
                        when {
                            x == 0 || y == 0 || x == 15 || y == 15 -> 0xff168f8b.toInt()
                            x == y || x + y == 15 -> 0xffb4fff7.toInt()
                            else -> 0xff55d8cf.toInt()
                        }
                    } else if ((x / 4 + y / 4) % 2 == 0) {
                        base
                    } else {
                        dark
                    }
                image.setRGB(x, y, color)
            }
        }
        return TextureData.fromImage("fallback:$id", image)
    }

    private fun animatedFrame(
        image: BufferedImage,
        textureKey: String,
    ): BufferedImage {
        val metadata =
            json("$textureKey.mcmeta")
                ?.get("animation")
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?: return image
        if (image.width <= 0 || image.height <= image.width || image.height % image.width != 0) return image
        val frameCount = image.height / image.width
        val defaultTime =
            metadata
                .get("frametime")
                ?.takeIf { it.isJsonPrimitive }
                ?.asInt
                ?.coerceAtLeast(1)
                ?: 1
        val frames =
            metadata.get("frames")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { raw ->
                when {
                    raw.isJsonPrimitive && raw.asJsonPrimitive.isNumber -> AnimatedFrame(raw.asInt, defaultTime)
                    raw.isJsonObject -> {
                        val index =
                            raw.asJsonObject
                                .get("index")
                                ?.takeIf { it.isJsonPrimitive }
                                ?.asInt
                                ?: return@mapNotNull null
                        val duration =
                            raw.asJsonObject
                                .get("time")
                                ?.takeIf { it.isJsonPrimitive }
                                ?.asInt
                                ?.coerceAtLeast(1)
                                ?: defaultTime
                        AnimatedFrame(index, duration)
                    }
                    else -> null
                }
            } ?: (0 until frameCount).map { AnimatedFrame(it, defaultTime) }
        val valid = frames.filter { it.index in 0 until frameCount }
        if (valid.isEmpty()) {
            invalid("INVALID_TEXTURE_ANIMATION", "Texture animation contains no valid frames", "$textureKey.mcmeta")
            return image.getSubimage(0, 0, image.width, image.width)
        }
        val total = valid.sumOf(AnimatedFrame::duration)
        var tick = Math.floorMod(gameTime, total.toLong()).toInt()
        val selected =
            valid.firstOrNull { frame ->
                if (tick < frame.duration) {
                    true
                } else {
                    tick -= frame.duration
                    false
                }
            } ?: valid.last()
        return image.getSubimage(0, selected.index * image.width, image.width, image.width)
    }

    private data class AssetSource(
        val path: Path,
        val kind: String,
    ) {
        fun read(key: String): ByteArray? =
            when {
                Files.isDirectory(path) -> readDirectory(key)
                Files.isRegularFile(path) -> readZip(key)
                else -> null
            }

        private fun readDirectory(key: String): ByteArray? {
            val normalizedRoot = path.toAbsolutePath().normalize()
            val relative = if (normalizedRoot.fileName?.toString() == "assets") key.removePrefix("assets/") else key
            val candidate = normalizedRoot.resolve(relative.replace('/', java.io.File.separatorChar)).normalize()
            if (!candidate.startsWith(normalizedRoot) || !Files.isRegularFile(candidate)) return null
            val realRoot = normalizedRoot.toRealPath()
            val realCandidate = candidate.toRealPath()
            if (!realCandidate.startsWith(realRoot) || Files.size(realCandidate) > MAX_ASSET_BYTES) return null
            return Files.readAllBytes(realCandidate)
        }

        private fun readZip(key: String): ByteArray? =
            try {
                ZipFile(path.toFile()).use { zip ->
                    val entry = zip.getEntry(key) ?: return null
                    if (entry.isDirectory || entry.size > MAX_ASSET_BYTES) return null
                    zip.getInputStream(entry).use { input ->
                        input.readNBytes((MAX_ASSET_BYTES + 1).toInt()).takeIf { it.size <= MAX_ASSET_BYTES }
                    }
                }
            } catch (_: Exception) {
                null
            }

        companion object {
            private const val MAX_ASSET_BYTES = 64L * 1024L * 1024L
        }
    }

    private data class AnimatedFrame(
        val index: Int,
        val duration: Int,
    )
}

internal fun splitResourceId(raw: String): Pair<String, String> {
    val normalized = normalizeResourceId(raw)
    val split = normalized.split(':', limit = 2)
    return split[0] to split[1]
}

internal fun normalizeResourceId(raw: String): String {
    val trimmed = raw.trim().removePrefix("#")
    val value = if (':' in trimmed) trimmed else "minecraft:$trimmed"
    require(RESOURCE_ID.matches(value)) { "Invalid render resource id: $raw" }
    return value
}

private fun normalizeAssetKey(raw: String): String {
    val normalized = raw.replace('\\', '/').trimStart('/')
    require(normalized.startsWith("assets/")) { "Render asset path must start with assets/: $raw" }
    require(".." !in normalized.split('/')) { "Render asset path must not traverse parents: $raw" }
    require(ASSET_KEY.matches(normalized)) { "Invalid render asset path: $raw" }
    return normalized
}

private val RESOURCE_ID = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")
private val ASSET_KEY = Regex("assets/[a-z0-9_.-]+/[a-z0-9_./-]+")
