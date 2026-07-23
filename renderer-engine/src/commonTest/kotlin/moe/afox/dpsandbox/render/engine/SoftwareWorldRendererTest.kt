package moe.afox.dpsandbox.render.engine

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SoftwareWorldRendererTest {
    @Test
    fun rendersDeterministicRgbaWithoutClaimingVisualParity() {
        val world = EngineRenderWorld(blocks = listOf(EngineRenderBlock(0, 0, 2, "minecraft:stone")))
        val first = SoftwareWorldRenderer().render(world, 64, 48)
        val second = SoftwareWorldRenderer().render(world, 64, 48)
        assertEquals(64 * 48 * 4, first.rgba.size)
        assertContentEquals(first.rgba, second.rgba)
        assertEquals(1, first.visibleBlocks)
        assertEquals(12, first.triangles)
        assertEquals("auto:scene", first.cameraDescription)
        assertFalse(first.visualParity)
    }

    @Test
    fun compilesIndependentRealtimeBuffersAtlasAndMaterialBatches() {
        val renderer = SoftwareWorldRenderer()
        val world =
            EngineRenderWorld(
                blocks = listOf(EngineRenderBlock(0, 0, 0, "minecraft:stone")),
                entities =
                    listOf(
                        EngineRenderEntity(
                            "label",
                            "minecraft:text_display",
                            0.5,
                            1.5,
                            0.5,
                            display = EngineDisplayData(text = "Live", billboard = "center", background = 0x80102030.toInt()),
                        ),
                    ),
            )

        val scene = renderer.compileRealtime(world, 960, 540)

        assertTrue(scene.blocks.vertices.isNotEmpty())
        assertEquals(0, scene.blocks.vertices.size % RealtimeSceneCompiler.FLOATS_PER_VERTEX)
        assertEquals(scene.blocks.vertices.size / RealtimeSceneCompiler.FLOATS_PER_VERTEX, scene.blocks.indices.size)
        assertTrue(scene.entities.vertices.isNotEmpty())
        assertTrue(scene.blocks.batches.any { it.pass == "opaque" })
        assertTrue(scene.entities.batches.any { it.pass == "translucent" })
        assertTrue(scene.atlas.width > 0 && scene.atlas.height > 0)
        assertEquals(scene.atlas.width * scene.atlas.height * 4, scene.atlas.rgba.size)
        assertEquals(listOf(0.0, 0.0, 0.0), scene.boundsMinimum)
        assertEquals(listOf(1.0, 1.5, 1.0), scene.boundsMaximum)
    }

    @Test
    fun rendersAllDisplayEntityKindsWithSharedGeometryAndReadableText() {
        val world =
            EngineRenderWorld(
                dayTime = 6_000,
                entities =
                    listOf(
                        EngineRenderEntity(
                            "block",
                            "minecraft:block_display",
                            -1.0,
                            0.0,
                            0.0,
                            display =
                                EngineDisplayData(
                                    blockId = "minecraft:stone",
                                    transformation =
                                        listOf(
                                            0.8, 0.0, 0.0, 0.0,
                                            0.0, 1.4, 0.0, 0.0,
                                            0.0, 0.0, 0.8, 0.0,
                                            0.0, 0.0, 0.0, 1.0,
                                        ),
                                    brightnessSky = 15,
                                ),
                        ),
                        EngineRenderEntity(
                            "item",
                            "minecraft:item_display",
                            0.5,
                            0.75,
                            0.0,
                            display = EngineDisplayData(itemId = "minecraft:diamond", itemDisplay = "fixed", billboard = "vertical"),
                        ),
                        EngineRenderEntity(
                            "text",
                            "minecraft:text_display",
                            0.0,
                            1.8,
                            0.0,
                            display =
                                EngineDisplayData(
                                    text = "DISPLAY 26.2",
                                    billboard = "center",
                                    lineWidth = 80,
                                    background = 0xaa102030.toInt(),
                                    textShadow = true,
                                    alignment = "center",
                                    shadowRadius = 0.4,
                                    shadowStrength = 0.7,
                                ),
                        ),
                    ),
            )
        val frame = SoftwareWorldRenderer().render(world, 240, 160)

        assertEquals(3, frame.visibleEntities)
        assertEquals(134, frame.triangles)
        assertTrue(frame.rgba.toSet().size > 16)
    }

    @Test
    fun textRasterizerHonorsContentAlignmentAndOpacity() {
        val left = DisplayTextRasterizer.render("HELLO", alignment = "left", textOpacity = 128, shadow = true)
        val right = DisplayTextRasterizer.render("HELLO", alignment = "right", textOpacity = 128, shadow = true)
        val different = DisplayTextRasterizer.render("WORLD", alignment = "left", textOpacity = 255)

        assertEquals(left.width, right.width)
        assertEquals(left.height, right.height)
        assertContentEquals(left.pixels, right.pixels)
        assertFalse(left.pixels.contentEquals(different.pixels))
        assertTrue(left.pixels.any { it ushr 24 in 1..254 })
    }

    @Test
    fun usesDepthBufferIndependentOfBlockIterationOrder() {
        val blocks =
            listOf(
                EngineRenderBlock(0, 0, 0, "minecraft:stone"),
                EngineRenderBlock(0, 0, 1, "minecraft:dirt"),
                EngineRenderBlock(1, 0, 1, "minecraft:diamond_block"),
            )
        val renderer = SoftwareWorldRenderer()
        val forward = renderer.render(EngineRenderWorld(blocks = blocks), 160, 90)
        val reversed = renderer.render(EngineRenderWorld(blocks = blocks.reversed()), 160, 90)
        assertContentEquals(forward.rgba, reversed.rgba)
        assertEquals(36, forward.triangles)
    }

    @Test
    fun samplesImportedRgbaTextures() {
        val world = EngineRenderWorld(blocks = listOf(EngineRenderBlock(0, 0, 0, "minecraft:stone")))
        val fallback = SoftwareWorldRenderer().render(world, 96, 64)
        val texturedRenderer = SoftwareWorldRenderer()
        texturedRenderer.registerTexture(
            "minecraft:block/stone",
            2,
            2,
            byteArrayOf(
                -1, 0, 0, -1,
                -1, 0, 0, -1,
                -1, 0, 0, -1,
                -1, 0, 0, -1,
            ),
        )
        val textured = texturedRenderer.render(world, 96, 64)
        assertNotEquals(fallback.rgba.toList(), textured.rgba.toList())
        assertTrue(
            textured.rgba.indices.step(4).any { index ->
                val red = textured.rgba[index].toInt() and 0xff
                val green = textured.rgba[index + 1].toInt() and 0xff
                val blue = textured.rgba[index + 2].toInt() and 0xff
                red > green + 20 && red > blue + 20
            },
        )
    }

    @Test
    fun resolvesModernItemDefinitionModelAndLayerTextureForItemDisplay() {
        val renderer = SoftwareWorldRenderer()
        renderer.registerAssetText(
            "assets/demo/items/display_item.json",
            """{"model":{"type":"minecraft:model","model":"demo:item/display_item"}}""",
        )
        renderer.registerAssetText(
            "assets/demo/models/item/display_item.json",
            """{"parent":"minecraft:item/generated","textures":{"layer0":"demo:item/display_gem"},"display":{"fixed":{"rotation":[0,0,35],"translation":[8,0,0],"scale":[0.75,0.5,0.25]}}}""",
        )
        renderer.registerTexture(
            "demo:item/display_gem",
            2,
            2,
            ByteArray(16) { index -> if (index % 4 == 0 || index % 4 == 3) -1 else 0 },
        )
        val frame =
            renderer.render(
                EngineRenderWorld(
                    dayTime = 6_000,
                    entities =
                        listOf(
                            EngineRenderEntity(
                                "item",
                                "minecraft:item_display",
                                0.0,
                                0.0,
                                0.0,
                                display = EngineDisplayData(itemId = "demo:display_item", itemDisplay = "fixed", brightnessSky = 15),
                            ),
                        ),
                ),
                96,
                64,
            )

        assertEquals(20, frame.triangles)
        assertTrue(
            frame.rgba.indices.step(4).any { index ->
                val red = frame.rgba[index].toInt() and 0xff
                val green = frame.rgba[index + 1].toInt() and 0xff
                val blue = frame.rgba[index + 2].toInt() and 0xff
                red > 180 && green < 80 && blue < 80
            },
        )

        val untransformed = SoftwareWorldRenderer()
        untransformed.registerAssetText(
            "assets/demo/items/display_item.json",
            """{"model":{"type":"minecraft:model","model":"demo:item/display_item"}}""",
        )
        untransformed.registerAssetText(
            "assets/demo/models/item/display_item.json",
            """{"parent":"minecraft:item/generated","textures":{"layer0":"demo:item/display_gem"}}""",
        )
        untransformed.registerTexture(
            "demo:item/display_gem",
            2,
            2,
            ByteArray(16) { index -> if (index % 4 == 0 || index % 4 == 3) -1 else 0 },
        )
        val baseline =
            untransformed.render(
                EngineRenderWorld(
                    dayTime = 6_000,
                    entities = listOf(EngineRenderEntity("item", "minecraft:item_display", 0.0, 0.0, 0.0, display = EngineDisplayData(itemId = "demo:display_item", itemDisplay = "fixed", brightnessSky = 15))),
                ),
                96,
                64,
            )
        assertNotEquals(baseline.rgba.toList(), frame.rgba.toList())
    }
}
