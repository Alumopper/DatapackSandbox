package moe.afox.dpsandbox.render

import com.google.gson.JsonParser
import moe.afox.dpsandbox.core.BlockPos
import moe.afox.dpsandbox.core.Position
import moe.afox.dpsandbox.core.ResourceLocation
import moe.afox.dpsandbox.core.SandboxBlock
import moe.afox.dpsandbox.core.SandboxEntity
import moe.afox.dpsandbox.core.SandboxException
import moe.afox.dpsandbox.core.VersionProfiles
import moe.afox.dpsandbox.core.createFunctionSandboxFromString
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SandboxRendererTest {
    @Test
    fun `renderer emits deterministic PNG without mutating sandbox`() {
        val sandbox = createFunctionSandboxFromString("26.2", "", defaultPlayerName = null)
        sandbox.world.setBlock(BlockPos(0, 64, 0), SandboxBlock(ResourceLocation.parse("minecraft:stone")))
        val before = sandbox.snapshotString()
        val renderer = SandboxRenderer()
        val request = RenderRequest(width = 320, height = 180, showDebugOverlay = true)

        val first = renderer.render(sandbox, request)
        val second = renderer.render(sandbox, request)

        assertContentEquals(PNG_SIGNATURE, first.pngBytes().take(PNG_SIGNATURE.size).toByteArray())
        assertContentEquals(first.pngBytes(), second.pngBytes())
        assertEquals(before, sandbox.snapshotString())
        assertEquals(1, first.metadata.visibleBlocks)
        assertEquals(12, first.metadata.triangles)
        assertFalse(first.metadata.visualParity)
        assertEquals("approximate", first.metadata.lightingModel)
    }

    @Test
    fun `renderer resolves custom blockstate model and texture`() {
        val assets = Files.createTempDirectory("dps-render-assets")
        writeCubeAssets(assets, "demo", "ruby", 0xffff2018.toInt())
        val sandbox = createFunctionSandboxFromString("26.2", "", defaultPlayerName = null)
        sandbox.world.setBlock(BlockPos(0, 64, 0), SandboxBlock(ResourceLocation.parse("demo:ruby")))

        val frame =
            SandboxRenderer(RenderAssets(minecraftAssets = assets)).render(
                sandbox,
                RenderRequest(width = 320, height = 180, strictAssets = true),
            )
        val image = ImageIO.read(ByteArrayInputStream(frame.pngBytes()))
        val coloredPixels =
            image.rgbPixels().filter { color ->
                val red = color ushr 16 and 0xff
                val green = color ushr 8 and 0xff
                val blue = color and 0xff
                red > green * 2 && red > blue * 2
            }

        assertTrue(coloredPixels.any(), "Expected the custom red texture to appear in the frame")
        assertTrue(frame.metadata.diagnostics.isEmpty(), frame.metadata.diagnostics.toString())
        assertEquals(listOf("minecraft-assets:${assets.toAbsolutePath().normalize()}"), frame.metadata.assetSources)
    }

    @Test
    fun `later resource packs override earlier assets`() {
        val base = Files.createTempDirectory("dps-render-base")
        val override = Files.createTempDirectory("dps-render-override")
        writeCubeAssets(base, "demo", "override", 0xffff0000.toInt())
        writeTexture(override, "demo", "override", 0xff0040ff.toInt())
        val diagnostics = mutableListOf<RenderDiagnostic>()
        val resolver = AssetResolver(RenderAssets(minecraftAssets = base, resourcePacks = listOf(override)), diagnostics, strict = true)

        val sampled = resolver.texture("demo:block/override").sample(0.5, 0.5)

        assertTrue((sampled and 0xff) > (sampled ushr 16 and 0xff), "Expected the overriding blue texture")
        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun `missing player camera returns structured sandbox error`() {
        val sandbox = createFunctionSandboxFromString("26.2", "", defaultPlayerName = null)

        val error =
            assertFailsWith<SandboxException> {
                SandboxRenderer().render(
                    sandbox,
                    RenderRequest(width = 320, height = 180, camera = RenderCamera.Player("Alex")),
                )
            }

        assertTrue("Alex" in error.message.orEmpty())
        assertTrue("available players" in error.message.orEmpty())
    }

    @Test
    fun `fixed camera rejects a dimension absent from the world view`() {
        val sandbox = createFunctionSandboxFromString("26.2", "", defaultPlayerName = null)

        val error =
            assertFailsWith<SandboxException> {
                SandboxRenderer().render(
                    sandbox,
                    RenderRequest(
                        width = 96,
                        height = 64,
                        camera = RenderCamera.Fixed(Position(0.0, 1.0, 0.0), 0.0, 0.0, "demo:missing"),
                    ),
                )
            }

        assertTrue("demo:missing" in error.message.orEmpty())
        assertTrue("available dimensions" in error.message.orEmpty())
    }

    @Test
    fun `asset resolver rejects path traversal`() {
        val resolver = AssetResolver(RenderAssets.EMPTY, mutableListOf(), strict = false)

        assertFailsWith<IllegalArgumentException> {
            resolver.bytes("assets/minecraft/../secret")
        }
    }

    @Test
    fun `asset resolver reads data from client jar without loading classes`() {
        val source = Files.createTempDirectory("dps-render-client-source")
        writeTexture(source, "demo", "jar_texture", 0xff22cc66.toInt())
        val jar = Files.createTempFile("dps-render-client", ".jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            val texture = source.resolve("assets/demo/textures/block/jar_texture.png")
            zip.putNextEntry(ZipEntry("assets/demo/textures/block/jar_texture.png"))
            Files.copy(texture, zip)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("demo/Untrusted.class"))
            zip.write(byteArrayOf(0x01, 0x02, 0x03))
            zip.closeEntry()
        }
        val resolver = AssetResolver(RenderAssets(minecraftAssets = jar), mutableListOf(), strict = true)

        val color = resolver.texture("demo:block/jar_texture").sample(0.5, 0.5)

        assertTrue((color ushr 8 and 0xff) > 180)
        assertFailsWith<IllegalArgumentException> { resolver.bytes("demo/Untrusted.class") }
    }

    @Test
    fun `cullface removes hidden faces between adjacent blocks`() {
        val assets = Files.createTempDirectory("dps-render-cull")
        writeCubeAssets(assets, "demo", "solid", 0xff88aa44.toInt(), includeCullFace = true)
        val sandbox = createFunctionSandboxFromString("26.2", "", defaultPlayerName = null)
        sandbox.world.setBlock(BlockPos(0, 64, 0), SandboxBlock(ResourceLocation.parse("demo:solid")))
        sandbox.world.setBlock(BlockPos(1, 64, 0), SandboxBlock(ResourceLocation.parse("demo:solid")))

        val frame =
            SandboxRenderer(RenderAssets(minecraftAssets = assets)).render(
                sandbox,
                RenderRequest(width = 320, height = 180, strictAssets = true),
            )

        assertEquals(20, frame.metadata.triangles)
    }

    @Test
    fun `animated textures select deterministic frame from game time`() {
        val assets = Files.createTempDirectory("dps-render-animation")
        val textures = assets.resolve("assets/demo/textures/block")
        Files.createDirectories(textures)
        val image = BufferedImage(16, 32, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 32) {
            for (x in 0 until 16) image.setRGB(x, y, if (y < 16) 0xffff0000.toInt() else 0xff0000ff.toInt())
        }
        ImageIO.write(image, "png", textures.resolve("animated.png").toFile())
        Files.writeString(textures.resolve("animated.png.mcmeta"), """{"animation":{"frametime":1}}""")

        val first = AssetResolver(RenderAssets(minecraftAssets = assets), mutableListOf(), strict = true, gameTime = 0)
        val second = AssetResolver(RenderAssets(minecraftAssets = assets), mutableListOf(), strict = true, gameTime = 1)

        assertTrue((first.texture("demo:block/animated").sample(0.5, 0.5) ushr 16 and 0xff) > 200)
        assertTrue((second.texture("demo:block/animated").sample(0.5, 0.5) and 0xff) > 200)
    }

    @Test
    fun `multipart conditions and crossed plant geometry are baked`() {
        val assets = Files.createTempDirectory("dps-render-composite")
        writeCubeAssets(assets, "demo", "part_a", 0xffff3030.toInt())
        writeCubeAssets(assets, "demo", "part_b", 0xff3060ff.toInt())
        val blockstates = assets.resolve("assets/demo/blockstates")
        val models = assets.resolve("assets/demo/models/block")
        Files.writeString(
            blockstates.resolve("multipart.json"),
            """
            {
              "multipart": [
                {"when":{"powered":"true"},"apply":{"model":"demo:block/part_a"}},
                {"when":{"OR":[{"axis":"x"},{"axis":"z"}]},"apply":{"model":"demo:block/part_b"}}
              ]
            }
            """.trimIndent(),
        )
        Files.writeString(blockstates.resolve("cross.json"), """{"variants":{"":{"model":"demo:block/cross"}}}""")
        Files.writeString(
            models.resolve("cross.json"),
            """
            {
              "textures":{"cross":"demo:block/part_a"},
              "elements":[
                {
                  "from":[0,0,7.5],"to":[16,16,8.5],
                  "rotation":{"origin":[8,8,8],"axis":"y","angle":45},
                  "faces":{"north":{"texture":"#cross"},"south":{"texture":"#cross"}}
                },
                {
                  "from":[0,0,7.5],"to":[16,16,8.5],
                  "rotation":{"origin":[8,8,8],"axis":"y","angle":-45},
                  "faces":{"north":{"texture":"#cross"},"south":{"texture":"#cross"}}
                }
              ]
            }
            """.trimIndent(),
        )
        val multipart = createFunctionSandboxFromString("26.2", "", defaultPlayerName = null)
        multipart.world.setBlock(
            BlockPos(0, 0, 0),
            SandboxBlock(ResourceLocation.parse("demo:multipart"), linkedMapOf("powered" to "true", "axis" to "x")),
        )
        val cross = createFunctionSandboxFromString("26.2", "", defaultPlayerName = null)
        cross.world.setBlock(BlockPos(0, 0, 0), SandboxBlock(ResourceLocation.parse("demo:cross")))
        val renderer = SandboxRenderer(RenderAssets(minecraftAssets = assets))

        val multipartFrame = renderer.render(multipart, RenderRequest(width = 96, height = 64, strictAssets = true))
        val crossFrame = renderer.render(cross, RenderRequest(width = 96, height = 64, strictAssets = true))

        assertEquals(24, multipartFrame.metadata.triangles)
        assertEquals(8, crossFrame.metadata.triangles)
    }

    @Test
    fun `block display uses its block state model at rendered position`() {
        val assets = Files.createTempDirectory("dps-render-block-display")
        writeCubeAssets(assets, "demo", "ruby", 0xffff2018.toInt())
        val sandbox = createFunctionSandboxFromString("26.2", "", defaultPlayerName = null)
        sandbox.world.entities +=
            SandboxEntity(
                type = ResourceLocation.parse("minecraft:block_display"),
                position = Position(2.5, 64.0, 3.5),
                nbt = JsonParser.parseString("""{"block_state":{"Name":"demo:ruby"}}""").asJsonObject,
            )

        val frame =
            SandboxRenderer(RenderAssets(minecraftAssets = assets)).render(
                sandbox,
                RenderRequest(width = 320, height = 180, strictAssets = true),
            )

        assertEquals(0, frame.metadata.visibleBlocks)
        assertEquals(1, frame.metadata.visibleEntities)
        assertEquals(12, frame.metadata.triangles)
        assertTrue(frame.metadata.diagnostics.isEmpty(), frame.metadata.diagnostics.toString())
    }

    @Test
    fun `renderer supports every built in version profile`() {
        VersionProfiles.all.forEach { profile ->
            val sandbox = createFunctionSandboxFromString(profile.id, "", defaultPlayerName = null)
            sandbox.world.setBlock(BlockPos(0, 64, 0), SandboxBlock(ResourceLocation.parse("minecraft:stone")))

            val frame = SandboxRenderer().render(sandbox, RenderRequest(width = 64, height = 64))

            assertContentEquals(PNG_SIGNATURE, frame.pngBytes().take(PNG_SIGNATURE.size).toByteArray(), profile.id)
            assertEquals(1, frame.metadata.visibleBlocks, profile.id)
        }
    }

    @Test
    fun `near plane clips crossing triangle instead of dropping it`() {
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).also { it.setRGB(0, 0, 0xffffffff.toInt()) }
        val texture = TextureData.fromImage("test:white", image)
        val triangle =
            SceneTriangle(
                SceneVertex(Vec3(-1.0, -1.0, 0.01), Vec2(0.0, 1.0)),
                SceneVertex(Vec3(0.0, 1.0, 1.0), Vec2(0.5, 0.0)),
                SceneVertex(Vec3(1.0, -1.0, 1.0), Vec2(1.0, 1.0)),
                texture,
                emissive = true,
            )
        val view = WorldView("26.2", 0, 6000, "clear", 0, emptyList(), emptyList(), emptyMap(), Position.zero)
        val camera = ResolvedCamera(Vec3.ZERO, 0.0, 0.0, "minecraft:overworld", "test")
        val request = RenderRequest(width = 64, height = 64, camera = RenderCamera.Auto, nearPlane = 0.05)

        val rendered = SoftwareRasterizer().render(view, RenderScene(camera, listOf(triangle), 0, 0), request)
        val background = SoftwareRasterizer().render(view, RenderScene(camera, emptyList(), 0, 0), request)

        assertTrue(rendered.indices.any { rendered[it] != background[it] })
    }

    @Test
    fun `humanoid entities use recognizable body parts`() {
        val sandbox = createFunctionSandboxFromString("26.2", "", defaultPlayerName = "Steve")
        sandbox.world.entities +=
            SandboxEntity(
                type = ResourceLocation.parse("minecraft:skeleton"),
                position = Position(2.0, 0.0, 2.0),
            )

        val frame = SandboxRenderer().render(sandbox, RenderRequest(width = 160, height = 90))

        assertEquals(2, frame.metadata.visibleEntities)
        assertEquals(144, frame.metadata.triangles)
    }

    @Test
    fun `items orbs and display entities use recognizable planar geometry`() {
        val sandbox = createFunctionSandboxFromString("26.2", "", defaultPlayerName = null)
        listOf("item", "experience_orb", "item_display", "text_display").forEachIndexed { index, type ->
            sandbox.world.entities +=
                SandboxEntity(
                    type = ResourceLocation.parse("minecraft:$type"),
                    position = Position(index.toDouble(), 0.0, 0.0),
                )
        }

        val frame = SandboxRenderer().render(sandbox, RenderRequest(width = 160, height = 90))

        assertEquals(4, frame.metadata.visibleEntities)
        assertEquals(10, frame.metadata.triangles)
        assertEquals(4, frame.metadata.diagnostics.count { it.code == "ENTITY_APPROXIMATE" })
    }

    @Test
    fun `model parent cycles produce diagnostics and strict failures`() {
        val assets = Files.createTempDirectory("dps-render-cycle")
        val blockstates = assets.resolve("assets/demo/blockstates")
        val models = assets.resolve("assets/demo/models/block")
        Files.createDirectories(blockstates)
        Files.createDirectories(models)
        Files.writeString(blockstates.resolve("cycle.json"), """{"variants":{"":{"model":"demo:block/a"}}}""")
        Files.writeString(models.resolve("a.json"), """{"parent":"demo:block/b"}""")
        Files.writeString(models.resolve("b.json"), """{"parent":"demo:block/a"}""")
        val sandbox = createFunctionSandboxFromString("26.2", "", defaultPlayerName = null)
        sandbox.world.setBlock(BlockPos(0, 0, 0), SandboxBlock(ResourceLocation.parse("demo:cycle")))
        val renderer = SandboxRenderer(RenderAssets(minecraftAssets = assets))

        val fallback = renderer.render(sandbox, RenderRequest(width = 96, height = 64))

        assertTrue(fallback.metadata.diagnostics.any { it.code == "MODEL_PARENT_CYCLE" })
        assertFailsWith<SandboxException> {
            renderer.render(sandbox, RenderRequest(width = 96, height = 64, strictAssets = true))
        }
    }

    @Test
    fun `depth transparency and fog remain order stable`() {
        val red = solidTexture("red", 0xffff2020.toInt())
        val blue = solidTexture("blue", 0xff2040ff.toInt())
        val translucentRed = solidTexture("translucent-red", 0x88ff2020.toInt())
        val translucentBlue = solidTexture("translucent-blue", 0x882040ff.toInt())
        val view = WorldView("26.2", 0, 6000, "clear", 0, emptyList(), emptyList(), emptyMap(), Position.zero)
        val camera = ResolvedCamera(Vec3.ZERO, 0.0, 0.0, "minecraft:overworld", "test")
        val request = RenderRequest(width = 64, height = 64, renderDistance = 100.0)
        val rasterizer = SoftwareRasterizer()

        val opaqueFirst = rasterizer.render(view, RenderScene(camera, quad(2.0, red) + quad(3.0, blue), 0, 0), request)
        val opaqueReversed = rasterizer.render(view, RenderScene(camera, quad(3.0, blue) + quad(2.0, red), 0, 0), request)
        val translucentFirst =
            rasterizer.render(view, RenderScene(camera, quad(2.0, translucentRed) + quad(3.0, translucentBlue), 0, 0), request)
        val translucentReversed =
            rasterizer.render(view, RenderScene(camera, quad(3.0, translucentBlue) + quad(2.0, translucentRed), 0, 0), request)

        assertContentEquals(opaqueFirst, opaqueReversed)
        assertContentEquals(translucentFirst, translucentReversed)
        assertTrue((opaqueFirst[32 * 64 + 32] ushr 16 and 0xff) > 200, "Nearest opaque surface must win depth testing")
        assertFalse(opaqueFirst.contentEquals(translucentFirst))

        val farWhite = RenderScene(camera, quad(8.0, solidTexture("white", 0xffffffff.toInt())), 0, 0)
        val fogged = rasterizer.render(view, farWhite, request.copy(renderDistance = 10.0))
        val clear = rasterizer.render(view, farWhite, request)
        assertFalse(fogged.contentEquals(clear), "Far geometry must blend into the approximate distance fog")
    }

    @Test
    fun `sky brightness follows noon dawn night and weather`() {
        val camera = ResolvedCamera(Vec3.ZERO, 0.0, 0.0, "minecraft:overworld", "test")
        val scene = RenderScene(camera, emptyList(), 0, 0)
        val request = RenderRequest(width = 64, height = 64)
        val rasterizer = SoftwareRasterizer()

        fun sky(dayTime: Long, weather: String): Double =
            averageBrightness(
                rasterizer.render(
                    WorldView("26.2", 0, dayTime, weather, 0, emptyList(), emptyList(), emptyMap(), Position.zero),
                    scene,
                    request,
                ),
            )

        val noon = sky(6000, "clear")
        val dawn = sky(0, "clear")
        val night = sky(18000, "clear")
        val rain = sky(6000, "rain")
        assertTrue(noon > dawn && dawn > night, "Expected noon > dawn > night, got $noon > $dawn > $night")
        assertTrue(noon > rain, "Rain must dim the clear noon sky")
    }

    @Test
    fun `hidden entities become approximate debug geometry`() {
        val sandbox = createFunctionSandboxFromString("26.2", "", defaultPlayerName = null)
        sandbox.world.entities +=
            SandboxEntity(
                type = ResourceLocation.parse("minecraft:marker"),
                position = Position(0.0, 0.0, 0.0),
            )
        val renderer = SandboxRenderer()

        val normal = renderer.render(sandbox, RenderRequest(width = 96, height = 64))
        val debug = renderer.render(sandbox, RenderRequest(width = 96, height = 64, showDebugOverlay = true))

        assertEquals(0, normal.metadata.visibleEntities)
        assertTrue(normal.metadata.diagnostics.any { it.code == "ENTITY_HIDDEN" })
        assertEquals(1, debug.metadata.visibleEntities)
        assertTrue(debug.metadata.diagnostics.any { it.code == "ENTITY_APPROXIMATE" })
    }

    @Test
    fun `synthetic cube golden keeps structural and perceptual metrics`() {
        val scene = createFunctionSandboxFromString("26.2", "", defaultPlayerName = null)
        val background = createFunctionSandboxFromString("26.2", "", defaultPlayerName = null)
        scene.executeCommand("time set noon")
        background.executeCommand("time set noon")
        scene.world.setBlock(BlockPos(0, 0, 0), SandboxBlock(ResourceLocation.parse("demo:golden_cube")))
        val request =
            RenderRequest(
                width = 160,
                height = 90,
                camera = RenderCamera.Fixed(Position(3.5, 2.7, -4.5), 31.0, 20.6),
                renderDistance = 32.0,
            )
        val renderer = SandboxRenderer()
        val actual = ImageIO.read(ByteArrayInputStream(renderer.render(scene, request).pngBytes()))
        val empty = ImageIO.read(ByteArrayInputStream(renderer.render(background, request).pngBytes()))
        val changed =
            buildList {
                for (y in 0 until actual.height) {
                    for (x in 0 until actual.width) {
                        if (actual.getRGB(x, y) != empty.getRGB(x, y)) add(x to y)
                    }
                }
            }
        val bounds =
            listOf(
                changed.minOf { it.first },
                changed.minOf { it.second },
                changed.maxOf { it.first },
                changed.maxOf { it.second },
            )
        val meanColorDelta =
            changed
                .sumOf { (x, y) ->
                    val first = actual.getRGB(x, y)
                    val second = empty.getRGB(x, y)
                    kotlin.math.abs((first ushr 16 and 0xff) - (second ushr 16 and 0xff)) +
                        kotlin.math.abs((first ushr 8 and 0xff) - (second ushr 8 and 0xff)) +
                        kotlin.math.abs((first and 0xff) - (second and 0xff))
                }.toDouble() / (changed.size * 3)

        assertEquals(160 to 90, actual.width to actual.height)
        assertTrue(changed.size in 170..178, "Golden structural pixel count changed: ${changed.size}")
        assertEquals(listOf(73, 38, 86, 52), bounds, "Golden occupied bounds changed")
        assertTrue(meanColorDelta in 84.0..89.0, "Golden perceptual color delta changed: $meanColorDelta")
    }

    private fun writeCubeAssets(
        root: Path,
        namespace: String,
        name: String,
        color: Int,
        includeCullFace: Boolean = false,
    ) {
        val blockstates = root.resolve("assets/$namespace/blockstates")
        val models = root.resolve("assets/$namespace/models/block")
        Files.createDirectories(blockstates)
        Files.createDirectories(models)
        Files.writeString(blockstates.resolve("$name.json"), """{"variants":{"":{"model":"$namespace:block/$name"}}}""")
        Files.writeString(
            models.resolve("$name.json"),
            """
            {
              "textures": { "all": "$namespace:block/$name" },
              "elements": [
                {
                  "from": [0, 0, 0],
                  "to": [16, 16, 16],
                  "faces": {
                    "west": { "texture": "#all"${if (includeCullFace) ", \"cullface\": \"west\"" else ""} },
                    "east": { "texture": "#all"${if (includeCullFace) ", \"cullface\": \"east\"" else ""} },
                    "down": { "texture": "#all"${if (includeCullFace) ", \"cullface\": \"down\"" else ""} },
                    "up": { "texture": "#all"${if (includeCullFace) ", \"cullface\": \"up\"" else ""} },
                    "north": { "texture": "#all"${if (includeCullFace) ", \"cullface\": \"north\"" else ""} },
                    "south": { "texture": "#all"${if (includeCullFace) ", \"cullface\": \"south\"" else ""} }
                  }
                }
              ]
            }
            """.trimIndent(),
        )
        writeTexture(root, namespace, name, color)
    }

    private fun writeTexture(
        root: Path,
        namespace: String,
        name: String,
        color: Int,
    ) {
        val textures = root.resolve("assets/$namespace/textures/block")
        Files.createDirectories(textures)
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) image.setRGB(x, y, color)
        }
        ImageIO.write(image, "png", textures.resolve("$name.png").toFile())
    }

    private fun solidTexture(
        name: String,
        color: Int,
    ): TextureData {
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, color)
        return TextureData.fromImage("test:$name", image)
    }

    private fun quad(
        z: Double,
        texture: TextureData,
    ): List<SceneTriangle> {
        val lowerLeft = SceneVertex(Vec3(-1.0, -1.0, z), Vec2(0.0, 1.0))
        val upperLeft = SceneVertex(Vec3(-1.0, 1.0, z), Vec2(0.0, 0.0))
        val upperRight = SceneVertex(Vec3(1.0, 1.0, z), Vec2(1.0, 0.0))
        val lowerRight = SceneVertex(Vec3(1.0, -1.0, z), Vec2(1.0, 1.0))
        return listOf(
            SceneTriangle(lowerLeft, upperLeft, upperRight, texture, emissive = true),
            SceneTriangle(lowerLeft, upperRight, lowerRight, texture, emissive = true),
        )
    }

    private fun averageBrightness(pixels: IntArray): Double =
        pixels
            .sumOf { color ->
                (color ushr 16 and 0xff) + (color ushr 8 and 0xff) + (color and 0xff)
            }.toDouble() / (pixels.size * 3)

    private fun BufferedImage.rgbPixels(): Sequence<Int> =
        sequence {
            for (y in 0 until height) {
                for (x in 0 until width) yield(getRGB(x, y))
            }
        }

    companion object {
        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}
