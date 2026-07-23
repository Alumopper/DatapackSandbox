package moe.afox.dpsandbox.render

import moe.afox.dpsandbox.core.BlockPos
import moe.afox.dpsandbox.core.Position
import moe.afox.dpsandbox.core.ResourceLocation
import moe.afox.dpsandbox.core.SandboxBlock
import moe.afox.dpsandbox.core.SandboxEntity
import moe.afox.dpsandbox.core.createFunctionSandboxFromString
import moe.afox.dpsandbox.render.engine.EngineDisplayData
import moe.afox.dpsandbox.render.engine.EngineRenderBlock
import moe.afox.dpsandbox.render.engine.EngineRenderEntity
import moe.afox.dpsandbox.render.engine.EngineRenderWorld
import moe.afox.dpsandbox.render.engine.SoftwareWorldRenderer
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class BrowserRendererDifferentialTest {
    @Test
    fun `common renderer matches jar fallback rgba`() {
        val sandbox = createFunctionSandboxFromString("26.2", "", defaultPlayerName = null)
        sandbox.executeCommand("time set noon")
        sandbox.world.setBlock(BlockPos(0, 64, 0), SandboxBlock(ResourceLocation.parse("minecraft:stone")))
        val width = 160
        val height = 90
        val jarFrame = SandboxRenderer().render(sandbox, RenderRequest(width = width, height = height))
        val expected = jarFrame.rgba(width, height)
        val browserFrame =
            SoftwareWorldRenderer().render(
                EngineRenderWorld(
                    dayTime = 6_000,
                    weather = sandbox.world.weather,
                    blocks = listOf(EngineRenderBlock(0, 64, 0, "minecraft:stone")),
                ),
                width,
                height,
            )

        assertContentEquals(expected, browserFrame.rgba)
        assertEquals(jarFrame.metadata.visibleBlocks, browserFrame.visibleBlocks)
        assertEquals(jarFrame.metadata.triangles, browserFrame.triangles)
    }

    @Test
    fun `common renderer matches jar blockstate model and texture rgba`() {
        val assets = Files.createTempDirectory("dps-browser-render-model")
        val blockstate = """{"variants":{"":{"model":"demo:block/ruby"}}}"""
        val model =
            """{"textures":{"all":"demo:block/ruby"},"elements":[{"from":[0,0,0],"to":[16,8,16],"faces":{"down":{"texture":"#all","cullface":"down"},"up":{"texture":"#all"},"north":{"texture":"#all"},"south":{"texture":"#all"},"west":{"texture":"#all"},"east":{"texture":"#all"}}}]}"""
        val blockstatePath = assets.resolve("assets/demo/blockstates/ruby.json")
        val modelPath = assets.resolve("assets/demo/models/block/ruby.json")
        val texturePath = assets.resolve("assets/demo/textures/block/ruby.png")
        listOf(blockstatePath, modelPath, texturePath).forEach { Files.createDirectories(it.parent) }
        Files.writeString(blockstatePath, blockstate)
        Files.writeString(modelPath, model)
        val texture = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 2) for (x in 0 until 2) texture.setRGB(x, y, 0xffff2018.toInt())
        ImageIO.write(texture, "png", texturePath.toFile())

        val sandbox = createFunctionSandboxFromString("26.2", "", defaultPlayerName = null)
        sandbox.executeCommand("time set noon")
        sandbox.world.setBlock(BlockPos(0, 64, 0), SandboxBlock(ResourceLocation.parse("demo:ruby")))
        val width = 160
        val height = 90
        val jarFrame = SandboxRenderer(RenderAssets(minecraftAssets = assets)).render(sandbox, RenderRequest(width = width, height = height))
        val renderer = SoftwareWorldRenderer()
        renderer.registerAssetText("assets/demo/blockstates/ruby.json", blockstate)
        renderer.registerAssetText("assets/demo/models/block/ruby.json", model)
        renderer.registerTexture(
            "demo:block/ruby",
            2,
            2,
            ByteArray(16) { index ->
                when (index % 4) {
                    0, 3 -> -1
                    1 -> 32
                    else -> 24
                }
            },
        )
        val browserFrame =
            renderer.render(
                EngineRenderWorld(dayTime = 6_000, blocks = listOf(EngineRenderBlock(0, 64, 0, "demo:ruby"))),
                width,
                height,
            )

        assertContentEquals(jarFrame.rgba(width, height), browserFrame.rgba)
        assertEquals(jarFrame.metadata.triangles, browserFrame.triangles)
    }

    @Test
    fun `common renderer matches jar transformed block display rgba`() {
        val assets = Files.createTempDirectory("dps-browser-render-display")
        val blockstate = """{"variants":{"":{"model":"demo:block/ruby"}}}"""
        val model =
            """{"textures":{"all":"demo:block/ruby"},"elements":[{"from":[0,0,0],"to":[16,16,16],"faces":{"down":{"texture":"#all"},"up":{"texture":"#all"},"north":{"texture":"#all"},"south":{"texture":"#all"},"west":{"texture":"#all"},"east":{"texture":"#all"}}}]}"""
        val blockstatePath = assets.resolve("assets/demo/blockstates/ruby.json")
        val modelPath = assets.resolve("assets/demo/models/block/ruby.json")
        val texturePath = assets.resolve("assets/demo/textures/block/ruby.png")
        listOf(blockstatePath, modelPath, texturePath).forEach { Files.createDirectories(it.parent) }
        Files.writeString(blockstatePath, blockstate)
        Files.writeString(modelPath, model)
        val texture = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 2) for (x in 0 until 2) texture.setRGB(x, y, 0xffff4020.toInt())
        ImageIO.write(texture, "png", texturePath.toFile())
        val matrix =
            listOf(
                0.75,
                0.0,
                0.0,
                0.15,
                0.0,
                1.25,
                0.0,
                0.2,
                0.0,
                0.0,
                0.5,
                -0.1,
                0.0,
                0.0,
                0.0,
                1.0,
            )
        val sandbox = createFunctionSandboxFromString("26.2", "", defaultPlayerName = null)
        sandbox.executeCommand("time set noon")
        sandbox.world.entities +=
            SandboxEntity(
                type = ResourceLocation.parse("minecraft:block_display"),
                position = Position(1.5, 64.0, -0.5),
                yaw = 25.0,
                nbt =
                    com.google.gson.JsonParser
                        .parseString(
                            """{"block_state":{"Name":"demo:ruby"},"transformation":${matrix.joinToString(prefix = "[", postfix = "]")},"brightness":{"sky":15,"block":15}}""",
                        ).asJsonObject,
            )
        val width = 160
        val height = 90
        val jarFrame = SandboxRenderer(RenderAssets(minecraftAssets = assets)).render(sandbox, RenderRequest(width = width, height = height))
        val renderer = SoftwareWorldRenderer()
        renderer.registerAssetText("assets/demo/blockstates/ruby.json", blockstate)
        renderer.registerAssetText("assets/demo/models/block/ruby.json", model)
        renderer.registerTexture(
            "demo:block/ruby",
            2,
            2,
            ByteArray(16) { index ->
                when (index % 4) {
                    0, 3 -> -1
                    1 -> 64
                    else -> 32
                }
            },
        )
        val browserFrame =
            renderer.render(
                EngineRenderWorld(
                    dayTime = 6_000,
                    entities =
                        listOf(
                            EngineRenderEntity(
                                uuid =
                                    sandbox.world.entities
                                        .single()
                                        .uuid,
                                type = "minecraft:block_display",
                                x = 1.5,
                                y = 64.0,
                                z = -0.5,
                                yaw = 25.0,
                                display =
                                    EngineDisplayData(
                                        transformation = matrix,
                                        brightnessSky = 15,
                                        brightnessBlock = 15,
                                        blockId = "demo:ruby",
                                    ),
                            ),
                        ),
                ),
                width,
                height,
            )

        assertContentEquals(jarFrame.rgba(width, height), browserFrame.rgba)
        assertEquals(12, browserFrame.triangles)
        assertEquals(jarFrame.metadata.triangles, browserFrame.triangles)
    }

    @Test
    fun `common renderer matches jar extruded item display and model transform rgba`() {
        val assets = Files.createTempDirectory("dps-browser-render-item-display")
        val definition = """{"model":{"type":"minecraft:model","model":"demo:item/gem"}}"""
        val model =
            """{"parent":"minecraft:item/generated","textures":{"layer0":"demo:item/gem_layer"},"display":{"fixed":{"rotation":[15,30,20],"translation":[3,1,0],"scale":[0.8,0.6,0.4]}}}"""
        val definitionPath = assets.resolve("assets/demo/items/gem.json")
        val modelPath = assets.resolve("assets/demo/models/item/gem.json")
        val texturePath = assets.resolve("assets/demo/textures/item/gem_layer.png")
        listOf(definitionPath, modelPath, texturePath).forEach { Files.createDirectories(it.parent) }
        Files.writeString(definitionPath, definition)
        Files.writeString(modelPath, model)
        val texture = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
        texture.setRGB(0, 0, 0xffff3020.toInt())
        texture.setRGB(1, 0, 0xffff3020.toInt())
        texture.setRGB(0, 1, 0xffff3020.toInt())
        texture.setRGB(1, 1, 0xffff3020.toInt())
        ImageIO.write(texture, "png", texturePath.toFile())
        val sandbox = createFunctionSandboxFromString("26.2", "", defaultPlayerName = null)
        sandbox.executeCommand("time set noon")
        sandbox.world.entities +=
            SandboxEntity(
                type = ResourceLocation.parse("minecraft:item_display"),
                position = Position(0.0, 64.0, 0.0),
                nbt =
                    com.google.gson.JsonParser
                        .parseString(
                            """{"item":{"id":"demo:gem","count":1},"item_display":"fixed","brightness":{"sky":15,"block":15}}""",
                        ).asJsonObject,
            )
        val width = 160
        val height = 90
        val jarFrame = SandboxRenderer(RenderAssets(minecraftAssets = assets)).render(sandbox, RenderRequest(width = width, height = height))
        val renderer = SoftwareWorldRenderer()
        renderer.registerAssetText("assets/demo/items/gem.json", definition)
        renderer.registerAssetText("assets/demo/models/item/gem.json", model)
        renderer.registerTexture(
            "demo:item/gem_layer",
            2,
            2,
            ByteArray(16) { index ->
                when (index % 4) {
                    0, 3 -> -1
                    1 -> 48
                    else -> 32
                }
            },
        )
        val browserFrame =
            renderer.render(
                EngineRenderWorld(
                    dayTime = 6_000,
                    entities =
                        listOf(
                            EngineRenderEntity(
                                uuid =
                                    sandbox.world.entities
                                        .single()
                                        .uuid,
                                type = "minecraft:item_display",
                                x = 0.0,
                                y = 64.0,
                                z = 0.0,
                                display =
                                    EngineDisplayData(
                                        brightnessSky = 15,
                                        brightnessBlock = 15,
                                        itemId = "demo:gem",
                                        itemDisplay = "fixed",
                                    ),
                            ),
                        ),
                ),
                width,
                height,
            )

        assertContentEquals(jarFrame.rgba(width, height), browserFrame.rgba)
        assertEquals(20, browserFrame.triangles)
    }

    private fun RenderedFrame.rgba(
        width: Int,
        height: Int,
    ): ByteArray {
        val image = ImageIO.read(ByteArrayInputStream(pngBytes()))
        val result = ByteArray(width * height * 4)
        image.getRGB(0, 0, width, height, null, 0, width).forEachIndexed { index, color ->
            result[index * 4] = (color ushr 16 and 0xff).toByte()
            result[index * 4 + 1] = (color ushr 8 and 0xff).toByte()
            result[index * 4 + 2] = (color and 0xff).toByte()
            result[index * 4 + 3] = (color ushr 24 and 0xff).toByte()
        }
        return result
    }
}
