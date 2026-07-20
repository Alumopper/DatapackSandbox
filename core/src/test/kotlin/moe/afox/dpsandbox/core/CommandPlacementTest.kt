package moe.afox.dpsandbox.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommandPlacementTest {
    @Test
    fun `place structure applies sandbox structure json resources`() {
        val pack = writeStructurePlacePack(Files.createTempDirectory("dps-place-structure-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))

        sandbox.executeCommand("place structure demo:room 10 64 20")

        val base = sandbox.world.requireBlock(BlockPos(10, 64, 20))
        val chest = sandbox.world.requireBlock(BlockPos(11, 64, 20))
        assertEquals(ResourceLocation.parse("minecraft:stone"), base.id)
        assertEquals(ResourceLocation.parse("minecraft:chest"), chest.id)
        assertEquals("north", chest.properties["facing"])
        assertEquals("marker", chest.nbt.get("CustomName").asString)
        val marker = sandbox.world.entities.single { it.type == ResourceLocation.parse("minecraft:pig") && "placed_structure" in it.tags }
        assertEquals(Position(10.5, 65.0, 20.5), marker.position)
        assertEquals(ResourceLocation.parse("minecraft:the_nether"), marker.dimension)

        val output = sandbox.world.outputs.single { it.command == "place structure" }
        val payload = output.payload?.asJsonObject ?: error("missing place structure payload")
        assertEquals("worldgen", output.channel)
        assertEquals(true, payload.get("placed").asBoolean)
        assertEquals("sandbox-structure-json", payload.get("format").asString)
        assertEquals(2, payload.get("changedBlocks").asInt)
        assertEquals(1, payload.get("entities").asInt)
        assertEquals(listOf("10 64 20", "11 64 20", marker.uuid), output.targets)
    }

    @Test
    fun `place structure applies palette style structure json resources`() {
        val pack = writeStructurePlacePack(Files.createTempDirectory("dps-place-palette-structure-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))

        sandbox.executeCommand("place structure demo:palette_room 15 66 25")

        assertEquals(ResourceLocation.parse("minecraft:polished_deepslate"), sandbox.world.requireBlock(BlockPos(15, 66, 25)).id)
        val barrel = sandbox.world.requireBlock(BlockPos(16, 66, 25))
        assertEquals(ResourceLocation.parse("minecraft:barrel"), barrel.id)
        assertEquals("east", barrel.properties["facing"])
        assertEquals("palette", barrel.nbt.get("CustomName").asString)
        val marker =
            sandbox.world.entities.single {
                it.type == ResourceLocation.parse("minecraft:armor_stand") &&
                    "palette_structure" in it.tags
            }
        assertEquals(Position(15.5, 67.0, 25.5), marker.position)

        val output = sandbox.world.outputs.single { it.command == "place structure" }
        val payload = output.payload?.asJsonObject ?: error("missing place structure payload")
        assertEquals(true, payload.get("placed").asBoolean)
        assertEquals("palette-structure-json", payload.get("format").asString)
        assertEquals(2, payload.get("changedBlocks").asInt)
        assertEquals(1, payload.get("entities").asInt)
        assertEquals(listOf("15 66 25", "16 66 25", marker.uuid), output.targets)
    }

    @Test
    fun `place structure applies binary nbt structure resources`() {
        val pack = writeStructurePlacePack(Files.createTempDirectory("dps-place-nbt-structure-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))

        sandbox.executeCommand("place structure demo:binary_room 25 68 35")

        assertEquals(ResourceLocation.parse("minecraft:cut_copper"), sandbox.world.requireBlock(BlockPos(25, 68, 35)).id)
        val furnace = sandbox.world.requireBlock(BlockPos(26, 68, 35))
        assertEquals(ResourceLocation.parse("minecraft:furnace"), furnace.id)
        assertEquals("south", furnace.properties["facing"])
        assertEquals("nbt", furnace.nbt.get("CustomName").asString)
        val marker =
            sandbox.world.entities.single {
                it.type == ResourceLocation.parse("minecraft:armor_stand") &&
                    "binary_structure" in it.tags
            }
        assertEquals(Position(25.5, 69.0, 35.5), marker.position)

        val output = sandbox.world.outputs.single { it.command == "place structure" }
        val payload = output.payload?.asJsonObject ?: error("missing place structure payload")
        assertEquals(true, payload.get("placed").asBoolean)
        assertEquals("palette-structure-json", payload.get("format").asString)
        assertEquals("binary-structure-nbt", payload.get("sourceFormat").asString)
        assertEquals(2, payload.get("changedBlocks").asInt)
        assertEquals(1, payload.get("entities").asInt)
        assertEquals(listOf("25 68 35", "26 68 35", marker.uuid), output.targets)
    }

    @Test
    fun `place template applies sandbox structure json with transforms`() {
        val pack = writeStructurePlacePack(Files.createTempDirectory("dps-place-template-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))

        sandbox.executeCommand("place template demo:room 30 64 40 clockwise_90 front_back 1.0 123")

        assertEquals(ResourceLocation.parse("minecraft:stone"), sandbox.world.requireBlock(BlockPos(30, 64, 40)).id)
        val chest = sandbox.world.requireBlock(BlockPos(30, 64, 41))
        assertEquals(ResourceLocation.parse("minecraft:chest"), chest.id)
        assertEquals("north", chest.properties["facing"])
        val marker = sandbox.world.entities.single { it.type == ResourceLocation.parse("minecraft:pig") && "placed_structure" in it.tags }
        assertEquals(Position(30.5, 65.0, 40.5), marker.position)

        val output = sandbox.world.outputs.single { it.command == "place template" }
        val payload = output.payload?.asJsonObject ?: error("missing place template payload")
        assertEquals(true, payload.get("placed").asBoolean)
        assertEquals("clockwise_90", payload.get("rotation").asString)
        assertEquals("front_back", payload.get("mirror").asString)
        assertEquals(1.0, payload.get("integrity").asDouble)
        assertEquals(123, payload.get("seed").asLong)
        assertEquals(listOf("30 64 40", "30 64 41", marker.uuid), output.targets)
    }

    @Test
    fun `place structure applies sandbox processor list resources`() {
        val pack = writeProcessedStructurePlacePack(Files.createTempDirectory("dps-place-processor-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))
        sandbox.executeCommand("setblock 22 70 30 minecraft:obsidian")

        sandbox.executeCommand("place structure demo:processed 20 70 30")

        assertNull(sandbox.world.block(BlockPos(20, 70, 30)))
        assertEquals(ResourceLocation.parse("minecraft:gold_block"), sandbox.world.requireBlock(BlockPos(21, 70, 30)).id)
        assertEquals(ResourceLocation.parse("minecraft:obsidian"), sandbox.world.requireBlock(BlockPos(22, 70, 30)).id)
        assertEquals(ResourceLocation.parse("minecraft:emerald_block"), sandbox.world.requireBlock(BlockPos(23, 70, 30)).id)
        val output = sandbox.world.outputs.single { it.command == "place structure" }
        val payload = output.payload?.asJsonObject ?: error("missing place structure payload")
        assertEquals(true, payload.get("placed").asBoolean)
        assertEquals(2, payload.get("changedBlocks").asInt)
        assertEquals(2, payload.get("skippedBlocks").asInt)
        assertEquals(4, payload.get("processedBlocks").asInt)
        assertEquals(0, payload.get("unsupportedProcessors").asInt)
        assertEquals("demo:cleanup", payload.getAsJsonArray("processorLists")[0].asString)
        assertEquals(listOf("21 70 30", "23 70 30"), output.targets)
    }

    @Test
    fun `place structure applies capped and predicate processor resources`() {
        val pack = writeProcessedStructurePlacePack(Files.createTempDirectory("dps-place-advanced-processor-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))

        sandbox.executeCommand("place structure demo:advanced_processors 30 75 30")

        assertEquals(ResourceLocation.parse("minecraft:gold_block"), sandbox.world.requireBlock(BlockPos(30, 75, 30)).id)
        assertEquals(ResourceLocation.parse("minecraft:stone"), sandbox.world.requireBlock(BlockPos(31, 75, 30)).id)
        assertEquals(ResourceLocation.parse("minecraft:stripped_oak_log"), sandbox.world.requireBlock(BlockPos(32, 75, 30)).id)
        assertEquals(ResourceLocation.parse("minecraft:waxed_copper_block"), sandbox.world.requireBlock(BlockPos(33, 75, 30)).id)
        assertEquals(ResourceLocation.parse("minecraft:diamond_block"), sandbox.world.requireBlock(BlockPos(34, 75, 30)).id)
        val output = sandbox.world.outputs.single { it.command == "place structure" }
        val payload = output.payload?.asJsonObject ?: error("missing place structure payload")
        assertEquals(true, payload.get("placed").asBoolean)
        assertEquals(5, payload.get("changedBlocks").asInt)
        assertEquals(0, payload.get("skippedBlocks").asInt)
        assertEquals(3, payload.get("processedBlocks").asInt)
        assertEquals(0, payload.get("unsupportedProcessors").asInt)
        assertEquals("demo:advanced", payload.getAsJsonArray("processorLists")[0].asString)
        assertEquals(
            listOf("30 75 30", "31 75 30", "32 75 30", "33 75 30", "34 75 30"),
            output.targets,
        )
    }

    @Test
    fun `place jigsaw applies template pool structure elements`() {
        val pack = writeJigsawPlacePack(Files.createTempDirectory("dps-place-jigsaw-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))

        sandbox.executeCommand("place jigsaw demo:start demo:target 1 40 70 50")

        assertEquals(ResourceLocation.parse("minecraft:emerald_block"), sandbox.world.requireBlock(BlockPos(40, 70, 50)).id)
        assertEquals(ResourceLocation.parse("minecraft:chest"), sandbox.world.requireBlock(BlockPos(41, 70, 50)).id)
        val output = sandbox.world.outputs.single { it.command == "place jigsaw" }
        val payload = output.payload?.asJsonObject ?: error("missing place jigsaw payload")
        assertEquals(true, payload.get("placed").asBoolean)
        assertEquals("sandbox-template-pool", payload.get("format").asString)
        assertEquals("demo:start", payload.get("pool").asString)
        assertEquals("demo:target", payload.get("target").asString)
        assertEquals(1, payload.get("maxDepth").asInt)
        assertEquals("minecraft:single_pool_element", payload.get("elementType").asString)
        assertEquals("demo:jigsaw_room", payload.get("structure").asString)
        assertEquals(2, payload.get("changedBlocks").asInt)
        assertEquals(1, payload.get("processedBlocks").asInt)
        assertEquals("demo:jigsaw_processors", payload.getAsJsonArray("processorLists")[0].asString)
        assertEquals(listOf("40 70 50", "41 70 50"), output.targets)
    }

    @Test
    fun `place jigsaw uses template pool fallback when primary has no supported element`() {
        val pack = writeJigsawPlacePack(Files.createTempDirectory("dps-place-jigsaw-fallback-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))

        sandbox.executeCommand("place jigsaw demo:fallback_start demo:target 1 45 70 55")

        assertEquals(ResourceLocation.parse("minecraft:emerald_block"), sandbox.world.requireBlock(BlockPos(45, 70, 55)).id)
        assertEquals(ResourceLocation.parse("minecraft:chest"), sandbox.world.requireBlock(BlockPos(46, 70, 55)).id)
        val output = sandbox.world.outputs.single { it.command == "place jigsaw" }
        val payload = output.payload?.asJsonObject ?: error("missing place jigsaw payload")
        assertEquals(true, payload.get("placed").asBoolean)
        assertEquals("sandbox-template-pool", payload.get("format").asString)
        assertEquals("demo:fallback_start", payload.get("pool").asString)
        assertEquals("demo:fallback_pool", payload.get("selectedPool").asString)
        assertEquals("demo:fallback_pool", payload.get("fallbackPool").asString)
        assertEquals("demo:fallback_start", payload.get("fallbackFrom").asString)
        assertEquals(true, payload.get("usedFallback").asBoolean)
        assertEquals("demo:jigsaw_room", payload.get("structure").asString)
        assertEquals(listOf("45 70 55", "46 70 55"), output.targets)
    }

    @Test
    fun `place jigsaw applies list pool and feature pool elements`() {
        val pack = writeJigsawPlacePack(Files.createTempDirectory("dps-place-jigsaw-list-feature-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))

        sandbox.executeCommand("place jigsaw demo:list_start demo:target 1 50 70 60")
        sandbox.executeCommand("place jigsaw demo:feature_start demo:target 1 52 70 60")

        assertEquals(ResourceLocation.parse("minecraft:emerald_block"), sandbox.world.requireBlock(BlockPos(50, 70, 60)).id)
        assertEquals(ResourceLocation.parse("minecraft:chest"), sandbox.world.requireBlock(BlockPos(51, 70, 60)).id)
        assertEquals(ResourceLocation.parse("minecraft:lapis_block"), sandbox.world.requireBlock(BlockPos(52, 70, 60)).id)
        val outputs = sandbox.world.outputs.filter { it.command == "place jigsaw" }
        assertEquals(2, outputs.size)

        val listPayload = outputs[0].payload?.asJsonObject ?: error("missing list payload")
        assertEquals(true, listPayload.get("placed").asBoolean)
        assertEquals("sandbox-template-pool", listPayload.get("format").asString)
        assertEquals("demo:list_start", listPayload.get("selectedPool").asString)
        assertEquals(false, listPayload.get("usedFallback").asBoolean)
        assertEquals("minecraft:list_pool_element", listPayload.get("elementType").asString)
        assertEquals("demo:jigsaw_room", listPayload.get("structure").asString)
        assertEquals(2, listPayload.get("changedBlocks").asInt)
        assertEquals(listOf("50 70 60", "51 70 60"), outputs[0].targets)

        val featurePayload = outputs[1].payload?.asJsonObject ?: error("missing feature payload")
        assertEquals(true, featurePayload.get("placed").asBoolean)
        assertEquals("sandbox-template-pool", featurePayload.get("format").asString)
        assertEquals("demo:feature_start", featurePayload.get("selectedPool").asString)
        assertEquals(false, featurePayload.get("usedFallback").asBoolean)
        assertEquals("minecraft:feature_pool_element", featurePayload.get("elementType").asString)
        assertEquals("demo:jigsaw_feature", featurePayload.get("feature").asString)
        assertEquals("simple_block", featurePayload.get("featureType").asString)
        assertEquals(1, featurePayload.get("changedBlocks").asInt)
        assertEquals(listOf("52 70 60"), outputs[1].targets)
    }

    @Test
    fun `place jigsaw follows deterministic jigsaw connector pools`() {
        val pack = writeJigsawPlacePack(Files.createTempDirectory("dps-place-jigsaw-connected-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))

        sandbox.executeCommand("place jigsaw demo:linked_start demo:target 3 60 70 60")

        assertEquals(ResourceLocation.parse("minecraft:stone"), sandbox.world.requireBlock(BlockPos(60, 70, 60)).id)
        assertEquals(ResourceLocation.parse("minecraft:jigsaw"), sandbox.world.requireBlock(BlockPos(61, 70, 60)).id)
        assertEquals(ResourceLocation.parse("minecraft:gold_block"), sandbox.world.requireBlock(BlockPos(62, 70, 60)).id)
        assertEquals(ResourceLocation.parse("minecraft:jigsaw"), sandbox.world.requireBlock(BlockPos(63, 70, 60)).id)
        assertEquals(ResourceLocation.parse("minecraft:diamond_block"), sandbox.world.requireBlock(BlockPos(64, 70, 60)).id)

        val output = sandbox.world.outputs.single { it.command == "place jigsaw" }
        val payload = output.payload?.asJsonObject ?: error("missing place jigsaw payload")
        assertEquals(true, payload.get("placed").asBoolean)
        assertEquals("sandbox-template-pool", payload.get("format").asString)
        assertEquals("demo:linked_start", payload.get("selectedPool").asString)
        assertEquals("demo:linked_root", payload.get("structure").asString)
        assertEquals(2, payload.get("changedBlocks").asInt)
        assertEquals(3, payload.get("jigsawPieces").asInt)
        assertEquals(3, payload.get("jigsawChildChangedBlocks").asInt)
        assertEquals(5, payload.get("totalChangedBlocks").asInt)
        assertEquals(3, payload.get("effectiveMaxDepth").asInt)
        assertEquals(3, payload.get("connectedTargets").asInt)

        val connections = payload.getAsJsonArray("jigsawConnections")
        assertEquals(2, connections.size())
        val first = connections[0].asJsonObject
        assertEquals(2, first.get("depth").asInt)
        assertEquals("demo:linked_root", first.get("sourceStructure").asString)
        assertEquals("demo:linked_child_pool", first.get("pool").asString)
        assertEquals("demo:linked_child", first.get("structure").asString)
        assertEquals(2, first.get("changedBlocks").asInt)
        val second = connections[1].asJsonObject
        assertEquals(3, second.get("depth").asInt)
        assertEquals("demo:linked_child", second.get("sourceStructure").asString)
        assertEquals("demo:linked_grandchild_pool", second.get("pool").asString)
        assertEquals("demo:linked_grandchild", second.get("structure").asString)
        assertEquals(1, second.get("changedBlocks").asInt)
        assertEquals(
            listOf("60 70 60", "61 70 60", "62 70 60", "63 70 60", "64 70 60"),
            output.targets,
        )
    }

    @Test
    fun `place feature applies placed simple block feature resources`() {
        val pack = writeFeaturePlacePack(Files.createTempDirectory("dps-place-feature-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))

        sandbox.executeCommand("place feature demo:placed_stone 4 65 6")

        val block = sandbox.world.requireBlock(BlockPos(4, 65, 6))
        assertEquals(ResourceLocation.parse("minecraft:oak_log"), block.id)
        assertEquals("y", block.properties["axis"])
        val output = sandbox.world.outputs.single { it.command == "place feature" }
        val payload = output.payload?.asJsonObject ?: error("missing place feature payload")
        assertEquals(true, payload.get("placed").asBoolean)
        assertEquals("configured-simple-block", payload.get("format").asString)
        assertEquals("worldgen/configured_feature", payload.get("resourceKind").asString)
        assertEquals("demo:simple_log", payload.get("configuredFeature").asString)
        assertEquals(1, payload.get("changedBlocks").asInt)
        assertEquals("minecraft:oak_log", payload.getAsJsonObject("block").get("id").asString)
        assertEquals(listOf("4 65 6"), output.targets)
    }

    @Test
    fun `place feature applies deterministic random patch feature resources`() {
        val pack = writeFeaturePlacePack(Files.createTempDirectory("dps-place-random-patch-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))

        sandbox.executeCommand("place feature demo:placed_flowers 10 70 10")

        assertEquals(ResourceLocation.parse("minecraft:poppy"), sandbox.world.requireBlock(BlockPos(10, 70, 10)).id)
        assertEquals(ResourceLocation.parse("minecraft:poppy"), sandbox.world.requireBlock(BlockPos(9, 70, 9)).id)
        assertEquals(ResourceLocation.parse("minecraft:poppy"), sandbox.world.requireBlock(BlockPos(9, 70, 10)).id)
        val output = sandbox.world.outputs.single { it.command == "place feature" }
        val payload = output.payload?.asJsonObject ?: error("missing place feature payload")
        assertEquals(true, payload.get("placed").asBoolean)
        assertEquals("configured-random_patch", payload.get("format").asString)
        assertEquals("random_patch", payload.get("featureType").asString)
        assertEquals("demo:flower_patch", payload.get("configuredFeature").asString)
        assertEquals(3, payload.get("attemptedBlocks").asInt)
        assertEquals(3, payload.get("changedBlocks").asInt)
        assertEquals("minecraft:poppy", payload.getAsJsonObject("block").get("id").asString)
        assertEquals(listOf("10 70 10", "9 70 9", "9 70 10"), output.targets)
    }

    @Test
    fun `place feature applies deterministic selector feature resources`() {
        val pack = writeFeaturePlacePack(Files.createTempDirectory("dps-place-selector-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))

        sandbox.executeCommand("place feature demo:placed_selector 12 70 12")

        val block = sandbox.world.requireBlock(BlockPos(12, 70, 12))
        assertEquals(ResourceLocation.parse("minecraft:oak_log"), block.id)
        assertEquals("y", block.properties["axis"])
        val output = sandbox.world.outputs.single { it.command == "place feature" }
        val payload = output.payload?.asJsonObject ?: error("missing place feature payload")
        assertEquals(true, payload.get("placed").asBoolean)
        assertEquals("configured-simple_random_selector", payload.get("format").asString)
        assertEquals("simple_random_selector", payload.get("featureType").asString)
        assertEquals(1, payload.get("attemptedBlocks").asInt)
        assertEquals(1, payload.get("changedBlocks").asInt)
        assertEquals(listOf("12 70 12"), output.targets)
    }

    @Test
    fun `place feature applies sparse ore replacement feature resources`() {
        val pack = writeFeaturePlacePack(Files.createTempDirectory("dps-place-ore-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))
        sandbox.executeCommand("setblock 20 40 20 minecraft:stone")
        sandbox.executeCommand("setblock 21 40 20 minecraft:deepslate")
        sandbox.executeCommand("setblock 19 40 20 minecraft:dirt")

        sandbox.executeCommand("place feature demo:placed_ore 20 40 20")

        assertEquals(ResourceLocation.parse("minecraft:diamond_ore"), sandbox.world.requireBlock(BlockPos(20, 40, 20)).id)
        assertEquals(ResourceLocation.parse("minecraft:diamond_ore"), sandbox.world.requireBlock(BlockPos(21, 40, 20)).id)
        assertEquals(ResourceLocation.parse("minecraft:dirt"), sandbox.world.requireBlock(BlockPos(19, 40, 20)).id)
        val output = sandbox.world.outputs.single { it.command == "place feature" }
        val payload = output.payload?.asJsonObject ?: error("missing place feature payload")
        assertEquals(true, payload.get("placed").asBoolean)
        assertEquals("configured-ore", payload.get("format").asString)
        assertEquals("ore", payload.get("featureType").asString)
        assertEquals(4, payload.get("attemptedBlocks").asInt)
        assertEquals(2, payload.get("changedBlocks").asInt)
        assertEquals(2, payload.get("skippedBlocks").asInt)
        assertEquals("minecraft:diamond_ore", payload.getAsJsonObject("block").get("id").asString)
        assertEquals(listOf("20 40 20", "21 40 20"), output.targets)
    }

    @Test
    fun `place feature applies replace single block feature resources`() {
        val pack = writeFeaturePlacePack(Files.createTempDirectory("dps-place-replace-single-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))
        sandbox.executeCommand("setblock 28 50 28 minecraft:stone")

        sandbox.executeCommand("place feature demo:placed_replace_single 28 50 28")

        assertEquals(ResourceLocation.parse("minecraft:gold_block"), sandbox.world.requireBlock(BlockPos(28, 50, 28)).id)
        val output = sandbox.world.outputs.single { it.command == "place feature" }
        val payload = output.payload?.asJsonObject ?: error("missing place feature payload")
        assertEquals(true, payload.get("placed").asBoolean)
        assertEquals("configured-replace_single_block", payload.get("format").asString)
        assertEquals("replace_single_block", payload.get("featureType").asString)
        assertEquals(1, payload.get("attemptedBlocks").asInt)
        assertEquals(1, payload.get("changedBlocks").asInt)
        assertEquals(0, payload.get("skippedBlocks").asInt)
        assertEquals(listOf("28 50 28"), output.targets)
    }

    @Test
    fun `place feature applies deterministic replace blob feature resources`() {
        val pack = writeFeaturePlacePack(Files.createTempDirectory("dps-place-replace-blob-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))
        sandbox.executeCommand("setblock 45 55 45 minecraft:stone")
        sandbox.executeCommand("setblock 44 55 45 minecraft:stone")
        sandbox.executeCommand("setblock 46 55 45 minecraft:dirt")

        sandbox.executeCommand("place feature demo:placed_replace_blob 45 55 45")

        assertEquals(ResourceLocation.parse("minecraft:moss_block"), sandbox.world.requireBlock(BlockPos(45, 55, 45)).id)
        assertEquals(ResourceLocation.parse("minecraft:moss_block"), sandbox.world.requireBlock(BlockPos(44, 55, 45)).id)
        assertEquals(ResourceLocation.parse("minecraft:dirt"), sandbox.world.requireBlock(BlockPos(46, 55, 45)).id)
        val output = sandbox.world.outputs.single { it.command == "place feature" }
        val payload = output.payload?.asJsonObject ?: error("missing place feature payload")
        assertEquals(true, payload.get("placed").asBoolean)
        assertEquals("configured-replace_blob", payload.get("format").asString)
        assertEquals("replace_blob", payload.get("featureType").asString)
        assertEquals(7, payload.get("attemptedBlocks").asInt)
        assertEquals(2, payload.get("changedBlocks").asInt)
        assertEquals(5, payload.get("skippedBlocks").asInt)
        assertEquals(listOf("45 55 45", "44 55 45"), output.targets)
    }

    @Test
    fun `place feature applies deterministic disk replacement feature resources`() {
        val pack = writeFeaturePlacePack(Files.createTempDirectory("dps-place-disk-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))
        sandbox.executeCommand("setblock 25 60 25 minecraft:dirt")
        sandbox.executeCommand("setblock 24 60 25 minecraft:dirt")
        sandbox.executeCommand("setblock 25 60 24 minecraft:dirt")
        sandbox.executeCommand("setblock 26 60 25 minecraft:stone")

        sandbox.executeCommand("place feature demo:placed_disk 25 60 25")

        assertEquals(ResourceLocation.parse("minecraft:clay"), sandbox.world.requireBlock(BlockPos(25, 60, 25)).id)
        assertEquals(ResourceLocation.parse("minecraft:clay"), sandbox.world.requireBlock(BlockPos(24, 60, 25)).id)
        assertEquals(ResourceLocation.parse("minecraft:clay"), sandbox.world.requireBlock(BlockPos(25, 60, 24)).id)
        assertEquals(ResourceLocation.parse("minecraft:stone"), sandbox.world.requireBlock(BlockPos(26, 60, 25)).id)
        val output = sandbox.world.outputs.single { it.command == "place feature" }
        val payload = output.payload?.asJsonObject ?: error("missing place feature payload")
        assertEquals(true, payload.get("placed").asBoolean)
        assertEquals("configured-disk", payload.get("format").asString)
        assertEquals("disk", payload.get("featureType").asString)
        assertEquals(5, payload.get("attemptedBlocks").asInt)
        assertEquals(3, payload.get("changedBlocks").asInt)
        assertEquals(2, payload.get("skippedBlocks").asInt)
        assertEquals(listOf("25 60 25", "24 60 25", "25 60 24"), output.targets)
    }

    @Test
    fun `place feature applies deterministic vegetation patch feature resources`() {
        val pack = writeFeaturePlacePack(Files.createTempDirectory("dps-place-vegetation-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))
        sandbox.executeCommand("setblock 35 60 35 minecraft:dirt")
        sandbox.executeCommand("setblock 34 60 35 minecraft:dirt")
        sandbox.executeCommand("setblock 35 60 34 minecraft:dirt")
        sandbox.executeCommand("setblock 36 60 35 minecraft:stone")

        sandbox.executeCommand("place feature demo:placed_grass_patch 35 60 35")

        assertEquals(ResourceLocation.parse("minecraft:grass_block"), sandbox.world.requireBlock(BlockPos(35, 60, 35)).id)
        assertEquals(ResourceLocation.parse("minecraft:grass_block"), sandbox.world.requireBlock(BlockPos(34, 60, 35)).id)
        assertEquals(ResourceLocation.parse("minecraft:grass_block"), sandbox.world.requireBlock(BlockPos(35, 60, 34)).id)
        assertEquals(ResourceLocation.parse("minecraft:poppy"), sandbox.world.requireBlock(BlockPos(35, 61, 35)).id)
        assertNull(sandbox.world.block(BlockPos(35, 60, 36)))
        assertEquals(ResourceLocation.parse("minecraft:stone"), sandbox.world.requireBlock(BlockPos(36, 60, 35)).id)
        val output = sandbox.world.outputs.single { it.command == "place feature" }
        val payload = output.payload?.asJsonObject ?: error("missing place feature payload")
        assertEquals(true, payload.get("placed").asBoolean)
        assertEquals("configured-vegetation_patch", payload.get("format").asString)
        assertEquals("vegetation_patch", payload.get("featureType").asString)
        assertEquals(6, payload.get("attemptedBlocks").asInt)
        assertEquals(4, payload.get("changedBlocks").asInt)
        assertEquals(2, payload.get("skippedBlocks").asInt)
        assertEquals(listOf("35 60 35", "34 60 35", "35 60 34", "35 61 35"), output.targets)
    }

    @Test
    fun `place feature applies deterministic tree feature resources`() {
        val pack = writeFeaturePlacePack(Files.createTempDirectory("dps-place-tree-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))

        sandbox.executeCommand("place feature demo:placed_tree 55 64 55")

        assertEquals(ResourceLocation.parse("minecraft:dirt"), sandbox.world.requireBlock(BlockPos(55, 63, 55)).id)
        assertEquals(ResourceLocation.parse("minecraft:oak_log"), sandbox.world.requireBlock(BlockPos(55, 64, 55)).id)
        assertEquals(ResourceLocation.parse("minecraft:oak_log"), sandbox.world.requireBlock(BlockPos(55, 65, 55)).id)
        assertEquals(ResourceLocation.parse("minecraft:oak_log"), sandbox.world.requireBlock(BlockPos(55, 66, 55)).id)
        assertEquals(ResourceLocation.parse("minecraft:oak_leaves"), sandbox.world.requireBlock(BlockPos(55, 67, 55)).id)
        assertEquals(ResourceLocation.parse("minecraft:oak_leaves"), sandbox.world.requireBlock(BlockPos(54, 67, 55)).id)
        assertEquals(ResourceLocation.parse("minecraft:oak_leaves"), sandbox.world.requireBlock(BlockPos(55, 67, 54)).id)
        assertEquals(ResourceLocation.parse("minecraft:oak_leaves"), sandbox.world.requireBlock(BlockPos(55, 67, 56)).id)
        assertEquals(ResourceLocation.parse("minecraft:oak_leaves"), sandbox.world.requireBlock(BlockPos(56, 67, 55)).id)
        val output = sandbox.world.outputs.single { it.command == "place feature" }
        val payload = output.payload?.asJsonObject ?: error("missing place feature payload")
        assertEquals(true, payload.get("placed").asBoolean)
        assertEquals("configured-tree", payload.get("format").asString)
        assertEquals("tree", payload.get("featureType").asString)
        assertEquals(9, payload.get("attemptedBlocks").asInt)
        assertEquals(9, payload.get("changedBlocks").asInt)
        assertEquals(0, payload.get("skippedBlocks").asInt)
        assertEquals(
            listOf(
                "55 63 55",
                "55 64 55",
                "55 65 55",
                "55 66 55",
                "55 67 55",
                "54 67 55",
                "55 67 54",
                "55 67 56",
                "56 67 55",
            ),
            output.targets,
        )
    }

    @Test
    fun `place feature applies deterministic cave and liquid feature resources`() {
        val pack = writeFeaturePlacePack(Files.createTempDirectory("dps-place-cave-liquid-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))

        sandbox.executeCommand("place feature demo:placed_basalt_columns 60 30 60")
        sandbox.executeCommand("place feature demo:placed_delta 70 20 70")
        sandbox.executeCommand("place feature demo:placed_lake 80 22 80")

        assertEquals(ResourceLocation.parse("minecraft:basalt"), sandbox.world.requireBlock(BlockPos(60, 30, 60)).id)
        assertEquals(ResourceLocation.parse("minecraft:basalt"), sandbox.world.requireBlock(BlockPos(60, 32, 60)).id)
        assertEquals(ResourceLocation.parse("minecraft:basalt"), sandbox.world.requireBlock(BlockPos(59, 31, 60)).id)
        assertEquals(ResourceLocation.parse("minecraft:basalt"), sandbox.world.requireBlock(BlockPos(61, 31, 60)).id)
        assertEquals(ResourceLocation.parse("minecraft:lava"), sandbox.world.requireBlock(BlockPos(70, 20, 70)).id)
        assertEquals(ResourceLocation.parse("minecraft:lava"), sandbox.world.requireBlock(BlockPos(69, 20, 70)).id)
        assertEquals(ResourceLocation.parse("minecraft:magma_block"), sandbox.world.requireBlock(BlockPos(68, 20, 70)).id)
        assertEquals(ResourceLocation.parse("minecraft:magma_block"), sandbox.world.requireBlock(BlockPos(69, 20, 69)).id)
        assertEquals(ResourceLocation.parse("minecraft:water"), sandbox.world.requireBlock(BlockPos(80, 22, 80)).id)
        assertEquals(ResourceLocation.parse("minecraft:water"), sandbox.world.requireBlock(BlockPos(79, 22, 80)).id)
        assertEquals(ResourceLocation.parse("minecraft:clay"), sandbox.world.requireBlock(BlockPos(78, 22, 80)).id)
        assertEquals(ResourceLocation.parse("minecraft:clay"), sandbox.world.requireBlock(BlockPos(79, 22, 79)).id)

        val outputs = sandbox.world.outputs.filter { it.command == "place feature" }
        assertEquals(3, outputs.size)
        val basaltPayload = outputs[0].payload?.asJsonObject ?: error("missing basalt payload")
        assertEquals(true, basaltPayload.get("placed").asBoolean)
        assertEquals("configured-basalt_columns", basaltPayload.get("format").asString)
        assertEquals("basalt_columns", basaltPayload.get("featureType").asString)
        assertEquals(11, basaltPayload.get("attemptedBlocks").asInt)
        assertEquals(11, basaltPayload.get("changedBlocks").asInt)
        assertEquals(
            listOf(
                "60 30 60",
                "60 31 60",
                "60 32 60",
                "59 30 60",
                "59 31 60",
                "60 30 59",
                "60 31 59",
                "60 30 61",
                "60 31 61",
                "61 30 60",
                "61 31 60",
            ),
            outputs[0].targets,
        )

        val deltaPayload = outputs[1].payload?.asJsonObject ?: error("missing delta payload")
        assertEquals(true, deltaPayload.get("placed").asBoolean)
        assertEquals("configured-delta_feature", deltaPayload.get("format").asString)
        assertEquals("delta_feature", deltaPayload.get("featureType").asString)
        assertEquals(13, deltaPayload.get("attemptedBlocks").asInt)
        assertEquals(13, deltaPayload.get("changedBlocks").asInt)

        val lakePayload = outputs[2].payload?.asJsonObject ?: error("missing lake payload")
        assertEquals(true, lakePayload.get("placed").asBoolean)
        assertEquals("configured-lake", lakePayload.get("format").asString)
        assertEquals("lake", lakePayload.get("featureType").asString)
        assertEquals(13, lakePayload.get("attemptedBlocks").asInt)
        assertEquals(13, lakePayload.get("changedBlocks").asInt)
    }

    @Test
    fun `place feature applies deterministic spring pile and glowstone resources`() {
        val pack = writeFeaturePlacePack(Files.createTempDirectory("dps-place-longtail-feature-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))

        sandbox.executeCommand("place feature demo:placed_spring 90 30 90")
        sandbox.executeCommand("place feature demo:placed_pile 95 40 95")
        sandbox.executeCommand("place feature demo:placed_glowstone 100 50 100")

        assertEquals(ResourceLocation.parse("minecraft:water"), sandbox.world.requireBlock(BlockPos(90, 30, 90)).id)
        assertEquals(ResourceLocation.parse("minecraft:moss_block"), sandbox.world.requireBlock(BlockPos(95, 40, 95)).id)
        assertEquals(ResourceLocation.parse("minecraft:moss_block"), sandbox.world.requireBlock(BlockPos(96, 40, 95)).id)
        assertEquals(ResourceLocation.parse("minecraft:moss_block"), sandbox.world.requireBlock(BlockPos(95, 40, 96)).id)
        assertEquals(ResourceLocation.parse("minecraft:glowstone"), sandbox.world.requireBlock(BlockPos(100, 50, 100)).id)
        assertEquals(ResourceLocation.parse("minecraft:glowstone"), sandbox.world.requireBlock(BlockPos(101, 50, 100)).id)
        assertEquals(ResourceLocation.parse("minecraft:glowstone"), sandbox.world.requireBlock(BlockPos(100, 51, 100)).id)

        val outputs = sandbox.world.outputs.filter { it.command == "place feature" }
        assertEquals(3, outputs.size)
        val springPayload = outputs[0].payload?.asJsonObject ?: error("missing spring payload")
        assertEquals(true, springPayload.get("placed").asBoolean)
        assertEquals("configured-spring_feature", springPayload.get("format").asString)
        assertEquals("spring_feature", springPayload.get("featureType").asString)
        assertEquals(1, springPayload.get("attemptedBlocks").asInt)
        assertEquals(1, springPayload.get("changedBlocks").asInt)

        val pilePayload = outputs[1].payload?.asJsonObject ?: error("missing pile payload")
        assertEquals(true, pilePayload.get("placed").asBoolean)
        assertEquals("configured-block_pile", pilePayload.get("format").asString)
        assertEquals("block_pile", pilePayload.get("featureType").asString)
        assertEquals(5, pilePayload.get("attemptedBlocks").asInt)
        assertEquals(5, pilePayload.get("changedBlocks").asInt)

        val glowstonePayload = outputs[2].payload?.asJsonObject ?: error("missing glowstone payload")
        assertEquals(true, glowstonePayload.get("placed").asBoolean)
        assertEquals("configured-glowstone_blob", glowstonePayload.get("format").asString)
        assertEquals("glowstone_blob", glowstonePayload.get("featureType").asString)
        assertEquals(7, glowstonePayload.get("attemptedBlocks").asInt)
        assertEquals(7, glowstonePayload.get("changedBlocks").asInt)
    }

    @Test
    fun `place feature applies deterministic rock nether blob and chorus resources`() {
        val pack = writeFeaturePlacePack(Files.createTempDirectory("dps-place-more-longtail-feature-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))

        sandbox.executeCommand("setblock 120 30 120 minecraft:netherrack")
        sandbox.executeCommand("setblock 121 30 120 minecraft:netherrack")
        sandbox.executeCommand("setblock 120 31 120 minecraft:netherrack")
        sandbox.executeCommand("place feature demo:placed_forest_rock 110 64 110")
        sandbox.executeCommand("place feature demo:placed_nether_blob 120 30 120")
        sandbox.executeCommand("place feature demo:placed_chorus 130 40 130")

        assertEquals(ResourceLocation.parse("minecraft:mossy_cobblestone"), sandbox.world.requireBlock(BlockPos(110, 64, 110)).id)
        assertEquals(ResourceLocation.parse("minecraft:mossy_cobblestone"), sandbox.world.requireBlock(BlockPos(111, 64, 110)).id)
        assertEquals(ResourceLocation.parse("minecraft:mossy_cobblestone"), sandbox.world.requireBlock(BlockPos(110, 65, 110)).id)
        assertEquals(ResourceLocation.parse("minecraft:basalt"), sandbox.world.requireBlock(BlockPos(120, 30, 120)).id)
        assertEquals(ResourceLocation.parse("minecraft:basalt"), sandbox.world.requireBlock(BlockPos(121, 30, 120)).id)
        assertEquals(ResourceLocation.parse("minecraft:basalt"), sandbox.world.requireBlock(BlockPos(120, 31, 120)).id)
        assertEquals(ResourceLocation.parse("minecraft:chorus_plant"), sandbox.world.requireBlock(BlockPos(130, 40, 130)).id)
        assertEquals(ResourceLocation.parse("minecraft:chorus_plant"), sandbox.world.requireBlock(BlockPos(130, 42, 130)).id)
        assertEquals(ResourceLocation.parse("minecraft:chorus_flower"), sandbox.world.requireBlock(BlockPos(130, 43, 130)).id)

        val outputs = sandbox.world.outputs.filter { it.command == "place feature" }
        assertEquals(3, outputs.size)
        val rockPayload = outputs[0].payload?.asJsonObject ?: error("missing rock payload")
        assertEquals(true, rockPayload.get("placed").asBoolean)
        assertEquals("configured-forest_rock", rockPayload.get("format").asString)
        assertEquals("forest_rock", rockPayload.get("featureType").asString)
        assertEquals(7, rockPayload.get("attemptedBlocks").asInt)
        assertEquals(7, rockPayload.get("changedBlocks").asInt)

        val netherPayload = outputs[1].payload?.asJsonObject ?: error("missing nether blob payload")
        assertEquals(true, netherPayload.get("placed").asBoolean)
        assertEquals("configured-netherrack_replace_blobs", netherPayload.get("format").asString)
        assertEquals("netherrack_replace_blobs", netherPayload.get("featureType").asString)
        assertEquals(7, netherPayload.get("attemptedBlocks").asInt)
        assertEquals(3, netherPayload.get("changedBlocks").asInt)
        assertEquals(4, netherPayload.get("skippedBlocks").asInt)

        val chorusPayload = outputs[2].payload?.asJsonObject ?: error("missing chorus payload")
        assertEquals(true, chorusPayload.get("placed").asBoolean)
        assertEquals("configured-chorus_plant", chorusPayload.get("format").asString)
        assertEquals("chorus_plant", chorusPayload.get("featureType").asString)
        assertEquals(4, chorusPayload.get("attemptedBlocks").asInt)
        assertEquals(4, chorusPayload.get("changedBlocks").asInt)
        assertEquals(listOf("130 40 130", "130 41 130", "130 42 130", "130 43 130"), outputs[2].targets)
    }

    @Test
    fun `place feature applies deterministic block column feature resources`() {
        val pack = writeFeaturePlacePack(Files.createTempDirectory("dps-place-column-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))

        sandbox.executeCommand("place feature demo:placed_column 30 50 30")

        assertEquals(ResourceLocation.parse("minecraft:oak_log"), sandbox.world.requireBlock(BlockPos(30, 50, 30)).id)
        assertEquals(ResourceLocation.parse("minecraft:oak_log"), sandbox.world.requireBlock(BlockPos(30, 51, 30)).id)
        assertEquals(ResourceLocation.parse("minecraft:oak_leaves"), sandbox.world.requireBlock(BlockPos(30, 52, 30)).id)
        val output = sandbox.world.outputs.single { it.command == "place feature" }
        val payload = output.payload?.asJsonObject ?: error("missing place feature payload")
        assertEquals(true, payload.get("placed").asBoolean)
        assertEquals("configured-block_column", payload.get("format").asString)
        assertEquals("block_column", payload.get("featureType").asString)
        assertEquals(3, payload.get("attemptedBlocks").asInt)
        assertEquals(3, payload.get("changedBlocks").asInt)
        assertEquals(listOf("30 50 30", "30 51 30", "30 52 30"), output.targets)
    }
}
