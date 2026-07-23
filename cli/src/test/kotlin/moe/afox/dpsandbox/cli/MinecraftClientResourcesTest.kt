package moe.afox.dpsandbox.cli

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import moe.afox.dpsandbox.render.RenderAssets
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MinecraftClientResourcesTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `loads the default bitmap font and particle sprites from a client resource source`() {
        val fontDirectory = tempDirectory.resolve("assets/minecraft/font")
        val fontTextureDirectory = tempDirectory.resolve("assets/minecraft/textures/font")
        val particleDirectory = tempDirectory.resolve("assets/minecraft/particles")
        val particleTextureDirectory = tempDirectory.resolve("assets/minecraft/textures/particle")
        listOf(fontDirectory, fontTextureDirectory, particleDirectory, particleTextureDirectory).forEach(Files::createDirectories)

        val characters = JsonArray()
        repeat(16) { row ->
            characters.add(String(IntArray(16) { column -> row * 16 + column }, 0, 16))
        }
        val provider =
            JsonObject().also { json ->
                json.addProperty("type", "bitmap")
                json.addProperty("file", "minecraft:font/ascii.png")
                json.addProperty("ascent", 7)
                json.add("chars", characters)
            }
        val fontDefinition = JsonObject().also { it.add("providers", JsonArray().also { providers -> providers.add(provider) }) }
        Files.writeString(fontDirectory.resolve("default.json"), fontDefinition.toString())
        val fontImage = BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB)
        for (codePoint in 32..126) {
            val cellX = codePoint % 16 * 8
            val cellY = codePoint / 16 * 8
            for (y in 1..6) for (x in 1..5) fontImage.setRGB(cellX + x, cellY + y, 0xffffffff.toInt())
        }
        ImageIO.write(fontImage, "png", fontTextureDirectory.resolve("ascii.png").toFile())

        Files.writeString(particleDirectory.resolve("flame.json"), """{"textures":["minecraft:flame"]}""")
        val flame = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB).also { it.setRGB(8, 8, 0xffffaa22.toInt()) }
        ImageIO.write(flame, "png", particleTextureDirectory.resolve("flame.png").toFile())

        val resources = MinecraftClientResources(RenderAssets(resourcePacks = listOf(tempDirectory)))
        val font = resources.defaultFont()
        assertEquals("minecraft:font/ascii.png", font.source)
        assertTrue((' '..'~').all { font.glyphs.containsKey(it.code) })
        assertEquals("minecraft:flame", resources.particleSprites("minecraft:flame").single().id)
    }
}
