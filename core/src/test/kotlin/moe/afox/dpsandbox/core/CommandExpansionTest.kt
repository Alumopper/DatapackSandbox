package moe.afox.dpsandbox.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CommandExpansionTest {
    @Test
    fun `world and player state commands update sandbox snapshot`() {
        val sandbox = createSandbox("26.1.2", listOf(fixturePack()))
        sandbox.runLoad()

        sandbox.executeCommand("difficulty hard")
        sandbox.executeCommand("defaultgamemode creative")
        sandbox.createPlayer("Builder")
        sandbox.executeCommand("gamemode adventure Steve")
        sandbox.executeCommand("setworldspawn 10 70 10 45")
        sandbox.executeCommand("spawnpoint Steve 1 65 2 90")
        sandbox.executeCommand("worldborder set 100")
        sandbox.executeCommand("worldborder center 5 -6")
        sandbox.executeCommand("forceload add 0 0 16 16")
        sandbox.executeCommand("forceload query 0 0")
        sandbox.executeCommand("forceload query 48 0")
        sandbox.executeCommand("fillbiome 0 0 0 1 0 1 minecraft:plains")
        sandbox.executeCommand("tick rate 40")
        sandbox.executeCommand("tick freeze")
        sandbox.executeCommand("tick step 2")

        val snapshot = sandbox.snapshotJson()
        assertEquals("hard", snapshot.get("difficulty").asString)
        assertEquals("creative", snapshot.get("defaultGameMode").asString)
        assertEquals("adventure", snapshot.getAsJsonObject("players").getAsJsonObject("Steve").get("gameMode").asString)
        assertEquals("creative", snapshot.getAsJsonObject("players").getAsJsonObject("Builder").get("gameMode").asString)
        val gamemodeOutput = sandbox.world.outputs.single { it.command == "gamemode" }
        val gamemodePayload = gamemodeOutput.payload?.asJsonObject ?: error("missing gamemode payload")
        val gamemodePlayer = gamemodePayload.getAsJsonArray("players")[0].asJsonObject
        assertEquals("1", gamemodeOutput.text)
        assertEquals(listOf("Steve"), gamemodeOutput.targets)
        assertEquals("adventure", gamemodePayload.get("mode").asString)
        assertEquals("survival", gamemodePlayer.get("before").asString)
        assertEquals("adventure", gamemodePlayer.get("after").asString)
        assertEquals(true, gamemodePlayer.get("changed").asBoolean)
        val difficultyOutput = sandbox.world.outputs.single { it.command == "difficulty" }
        val difficultyPayload = difficultyOutput.payload?.asJsonObject ?: error("missing difficulty payload")
        assertEquals("hard", difficultyOutput.text)
        assertEquals("normal", difficultyPayload.get("before").asString)
        assertEquals("hard", difficultyPayload.get("after").asString)
        assertEquals(true, difficultyPayload.get("changed").asBoolean)
        val defaultModeOutput = sandbox.world.outputs.single { it.command == "defaultgamemode" }
        val defaultModePayload = defaultModeOutput.payload?.asJsonObject ?: error("missing defaultgamemode payload")
        assertEquals("creative", defaultModeOutput.text)
        assertEquals("survival", defaultModePayload.get("before").asString)
        assertEquals("creative", defaultModePayload.get("after").asString)
        assertEquals(true, defaultModePayload.get("changed").asBoolean)
        assertEquals(40.0, snapshot.get("tickRate").asDouble)
        assertEquals(true, snapshot.get("tickFrozen").asBoolean)
        assertEquals(2, snapshot.get("gameTime").asLong)
        val tickRateOutput = sandbox.world.outputs.single { it.command == "tick rate" }
        val tickRatePayload = tickRateOutput.payload?.asJsonObject ?: error("missing tick rate payload")
        assertEquals("40.0", tickRateOutput.text)
        assertEquals("rate", tickRatePayload.get("action").asString)
        assertEquals(20.0, tickRatePayload.get("beforeRate").asDouble)
        assertEquals(40.0, tickRatePayload.get("requestedRate").asDouble)
        assertEquals(40.0, tickRatePayload.get("rate").asDouble)
        val tickFreezeOutput = sandbox.world.outputs.single { it.command == "tick freeze" }
        val tickFreezePayload = tickFreezeOutput.payload?.asJsonObject ?: error("missing tick freeze payload")
        assertEquals("true", tickFreezeOutput.text)
        assertEquals("freeze", tickFreezePayload.get("action").asString)
        assertEquals(false, tickFreezePayload.get("beforeFrozen").asBoolean)
        assertEquals(true, tickFreezePayload.get("frozen").asBoolean)
        val tickStepOutput = sandbox.world.outputs.single { it.command == "tick step" }
        val tickStepPayload = tickStepOutput.payload?.asJsonObject ?: error("missing tick step payload")
        assertEquals("2", tickStepOutput.text)
        assertEquals("step", tickStepPayload.get("action").asString)
        assertEquals(2, tickStepPayload.get("ticks").asInt)
        assertEquals(0, tickStepPayload.get("beforeGameTime").asLong)
        assertEquals(2, tickStepPayload.get("gameTime").asLong)
        assertEquals(2, tickStepPayload.get("advancedTicks").asLong)
        assertEquals(4, snapshot.getAsJsonArray("biomes").size())
        val fillBiomeOutput = sandbox.world.outputs.single { it.command == "fillbiome" }
        val fillBiomePayload = fillBiomeOutput.payload?.asJsonObject ?: error("missing fillbiome payload")
        assertEquals("4", fillBiomeOutput.text)
        assertEquals("minecraft:plains", fillBiomePayload.get("biome").asString)
        assertEquals(4, fillBiomePayload.get("volume").asInt)
        assertEquals(4, fillBiomePayload.get("changed").asInt)
        assertEquals("0 0 0", fillBiomeOutput.targets[0])
        assertTrue(snapshot.getAsJsonArray("forcedChunks").size() >= 1)
        val forceAddOutput = sandbox.world.outputs.single { it.command == "forceload add" }
        val forceAddPayload = forceAddOutput.payload?.asJsonObject ?: error("missing forceload add payload")
        assertEquals("4", forceAddOutput.text)
        assertEquals("add", forceAddPayload.get("action").asString)
        assertEquals(4, forceAddPayload.get("changed").asInt)
        assertEquals(4, forceAddPayload.get("forcedCount").asInt)
        assertEquals("0,0", forceAddOutput.targets[0])
        val forceQueries = sandbox.world.outputs.filter { it.command == "forceload query" }
        val loadedPayload = forceQueries[0].payload?.asJsonObject ?: error("missing loaded forceload payload")
        val unloadedPayload = forceQueries[1].payload?.asJsonObject ?: error("missing unloaded forceload payload")
        assertEquals(true, loadedPayload.get("forced").asBoolean)
        assertEquals(0, loadedPayload.getAsJsonObject("chunk").get("x").asInt)
        assertEquals(0, loadedPayload.getAsJsonObject("chunk").get("z").asInt)
        assertEquals(false, unloadedPayload.get("forced").asBoolean)
        assertEquals(3, unloadedPayload.getAsJsonObject("chunk").get("x").asInt)
        assertEquals(100.0, snapshot.getAsJsonObject("worldBorder").get("size").asDouble)
        assertEquals(1.0, snapshot.getAsJsonObject("players").getAsJsonObject("Steve").getAsJsonObject("spawnPoint").get("x").asDouble)
        val worldSpawnOutput = sandbox.world.outputs.single { it.command == "setworldspawn" }
        val worldSpawn = worldSpawnOutput.payload?.asJsonObject?.getAsJsonObject("spawn") ?: error("missing world spawn payload")
        assertEquals("10.0 70.0 10.0", worldSpawnOutput.text)
        assertEquals(10.0, worldSpawn.get("x").asDouble)
        assertEquals(70.0, worldSpawn.get("y").asDouble)
        assertEquals(10.0, worldSpawn.get("z").asDouble)
        assertEquals(45.0, worldSpawn.get("angle").asDouble)
        val spawnpointOutput = sandbox.world.outputs.single { it.command == "spawnpoint" }
        val spawnpointPayload = spawnpointOutput.payload?.asJsonObject ?: error("missing spawnpoint payload")
        val playerSpawn = spawnpointPayload.getAsJsonArray("players")[0].asJsonObject.getAsJsonObject("spawn")
        assertEquals("1", spawnpointOutput.text)
        assertEquals(listOf("Steve"), spawnpointOutput.targets)
        assertEquals(1.0, playerSpawn.get("x").asDouble)
        assertEquals(65.0, playerSpawn.get("y").asDouble)
        assertEquals(2.0, playerSpawn.get("z").asDouble)
        assertEquals(90.0, playerSpawn.get("angle").asDouble)
    }

    @Test
    fun `worldborder mutations record structured outputs`() {
        val sandbox = createSandbox("26.1.2", listOf(fixturePack()))

        sandbox.executeCommand("worldborder set 200 4")
        sandbox.executeCommand("worldborder add -50 2")
        sandbox.executeCommand("worldborder center 8 -9")
        sandbox.executeCommand("worldborder damage buffer 3.5")
        sandbox.executeCommand("worldborder warning time 30")

        val border = sandbox.snapshotJson().getAsJsonObject("worldBorder")
        assertEquals(150.0, border.get("size").asDouble)
        assertEquals(150.0, border.get("targetSize").asDouble)
        assertEquals(2, border.get("lerpTimeSeconds").asLong)
        assertEquals(8.0, border.get("centerX").asDouble)
        assertEquals(-9.0, border.get("centerZ").asDouble)
        assertEquals(3.5, border.get("damageBuffer").asDouble)
        assertEquals(30, border.get("warningTime").asInt)

        val setOutput = sandbox.world.outputs.single { it.command == "worldborder set" }
        val setPayload = setOutput.payload?.asJsonObject ?: error("missing worldborder set payload")
        assertEquals("200.0", setOutput.text)
        assertEquals("set", setPayload.get("action").asString)
        assertEquals(59999968.0, setPayload.getAsJsonObject("before").get("size").asDouble)
        assertEquals(200.0, setPayload.get("size").asDouble)
        assertEquals(200.0, setPayload.get("requestedSize").asDouble)
        assertEquals(4, setPayload.get("lerpTimeSeconds").asLong)

        val addOutput = sandbox.world.outputs.single { it.command == "worldborder add" }
        val addPayload = addOutput.payload?.asJsonObject ?: error("missing worldborder add payload")
        assertEquals("150.0", addOutput.text)
        assertEquals("add", addPayload.get("action").asString)
        assertEquals(200.0, addPayload.getAsJsonObject("before").get("size").asDouble)
        assertEquals(-50.0, addPayload.get("delta").asDouble)
        assertEquals(150.0, addPayload.get("size").asDouble)

        val centerOutput = sandbox.world.outputs.single { it.command == "worldborder center" }
        val centerPayload = centerOutput.payload?.asJsonObject ?: error("missing worldborder center payload")
        assertEquals("8.0 -9.0", centerOutput.text)
        assertEquals("center", centerPayload.get("action").asString)
        assertEquals(0.0, centerPayload.getAsJsonObject("before").get("centerX").asDouble)
        assertEquals(8.0, centerPayload.get("centerX").asDouble)
        assertEquals(-9.0, centerPayload.get("centerZ").asDouble)

        val damageOutput = sandbox.world.outputs.single { it.command == "worldborder damage" }
        val damagePayload = damageOutput.payload?.asJsonObject ?: error("missing worldborder damage payload")
        assertEquals("3.5", damageOutput.text)
        assertEquals("damage", damagePayload.get("action").asString)
        assertEquals("buffer", damagePayload.get("field").asString)
        assertEquals(5.0, damagePayload.getAsJsonObject("before").get("damageBuffer").asDouble)
        assertEquals(3.5, damagePayload.get("value").asDouble)
        assertEquals(3.5, damagePayload.get("damageBuffer").asDouble)

        val warningOutput = sandbox.world.outputs.single { it.command == "worldborder warning" }
        val warningPayload = warningOutput.payload?.asJsonObject ?: error("missing worldborder warning payload")
        assertEquals("30", warningOutput.text)
        assertEquals("warning", warningPayload.get("action").asString)
        assertEquals("time", warningPayload.get("field").asString)
        assertEquals(15, warningPayload.getAsJsonObject("before").get("warningTime").asInt)
        assertEquals(30, warningPayload.get("value").asInt)
        assertEquals(30, warningPayload.get("warningTime").asInt)
    }

    @Test
    fun `utility commands record deterministic outputs`() {
        val sandbox = createSandbox("26.1.2", listOf(fixturePack()))

        sandbox.executeCommand("help gamemode")
        sandbox.executeCommand("list uuids")
        sandbox.executeCommand("seed")
        sandbox.executeCommand("locate biome minecraft:plains")
        sandbox.executeCommand("place structure demo:ruin 1 64 2")
        sandbox.executeCommand("place template demo:room ~ ~1 ~ clockwise_90 front_back 0.5 123")
        sandbox.executeCommand("datapack list")
        sandbox.executeCommand("reload")
        sandbox.executeCommand("scoreboard objectives add trig trigger")
        sandbox.executeCommand("trigger trig set 4")

        assertEquals(4, sandbox.world.getScore("Steve", "trig"))
        assertTrue(sandbox.world.outputs.any { it.command == "help" && it.text.contains("gamemode") })
        assertTrue(sandbox.world.outputs.any { it.command == "list" && it.text.contains("Steve") })
        assertTrue(sandbox.world.outputs.any { it.command == "locate" && it.payload?.asJsonObject?.get("found")?.asBoolean == false })
        val placeStructure = sandbox.world.outputs.single { it.command == "place structure" }
        val placeStructurePayload = placeStructure.payload?.asJsonObject ?: error("missing place structure payload")
        assertEquals("worldgen", placeStructure.channel)
        assertEquals("structure:demo:ruin", placeStructure.text)
        assertEquals(false, placeStructurePayload.get("placed").asBoolean)
        assertEquals(1.0, placeStructurePayload.getAsJsonObject("position").get("x").asDouble)
        assertEquals(64.0, placeStructurePayload.getAsJsonObject("position").get("y").asDouble)
        assertEquals(2.0, placeStructurePayload.getAsJsonObject("position").get("z").asDouble)
        val placeTemplatePayload = sandbox.world.outputs.single { it.command == "place template" }.payload?.asJsonObject
            ?: error("missing place template payload")
        assertEquals("demo:room", placeTemplatePayload.get("id").asString)
        assertEquals(1.0, placeTemplatePayload.getAsJsonObject("position").get("y").asDouble)
        assertEquals(listOf("clockwise_90", "front_back", "0.5", "123"), placeTemplatePayload.getAsJsonArray("extra").map { it.asString })
        assertTrue(sandbox.world.outputs.any { it.command == "datapack list" })
        assertTrue(sandbox.world.outputs.any { it.command == "reload" })
    }

    @Test
    fun `attribute and loot commands expose sandbox-visible state`() {
        val pack = writeLootPack(Files.createTempDirectory("dps-command-expansion-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))

        sandbox.executeCommand("summon minecraft:zombie 0 64 0")
        sandbox.executeCommand("attribute @e[type=minecraft:zombie,limit=1] minecraft:max_health base set 40")
        sandbox.executeCommand("attribute @e[type=minecraft:zombie,limit=1] minecraft:max_health modifier add demo:bonus 5 add_value")
        sandbox.executeCommand("attribute @e[type=minecraft:zombie,limit=1] minecraft:max_health get 0.5")
        sandbox.executeCommand("attribute @e[type=minecraft:zombie,limit=1] minecraft:max_health modifier value get demo:bonus 2")
        sandbox.executeCommand("scoreboard objectives add attr dummy")
        sandbox.executeCommand("execute store result score #max attr run attribute @e[type=minecraft:zombie,limit=1] minecraft:max_health base get 0.25")
        sandbox.executeCommand("execute store result score #bonus attr run attribute @e[type=minecraft:zombie,limit=1] minecraft:max_health modifier value get demo:bonus")
        sandbox.executeCommand("attribute @e[type=minecraft:zombie,limit=1] minecraft:max_health modifier remove demo:bonus")
        sandbox.executeCommand("attribute @e[type=minecraft:zombie,limit=1] minecraft:max_health get")
        sandbox.executeCommand("loot give Steve loot demo:gift")

        val zombie = sandbox.world.entities.first { it.type == ResourceLocation.parse("minecraft:zombie") }
        assertEquals(40.0, zombie.attributes[ResourceLocation.parse("minecraft:max_health")])
        val attributeOutput = sandbox.world.outputs.first { it.command == "attribute get" }
        val attributePayload = attributeOutput.payload?.asJsonObject ?: error("missing attribute payload")
        assertEquals("22.5", attributeOutput.text)
        assertEquals(zombie.uuid, attributePayload.get("target").asString)
        assertEquals("minecraft:max_health", attributePayload.get("attribute").asString)
        assertEquals("total", attributePayload.get("field").asString)
        assertEquals(0.5, attributePayload.get("scale").asDouble)
        assertEquals(45.0, attributePayload.get("rawValue").asDouble)
        assertEquals(22.5, attributePayload.get("value").asDouble)
        val modifierOutput = sandbox.world.outputs.first {
            it.command == "attribute modifier value get" &&
                it.payload?.asJsonObject?.get("scale")?.asDouble == 2.0
        }
        val modifierPayload = modifierOutput.payload?.asJsonObject ?: error("missing attribute modifier payload")
        assertEquals("10.0", modifierOutput.text)
        assertEquals("demo:bonus", modifierPayload.get("modifier").asString)
        assertEquals("add_value", modifierPayload.get("operation").asString)
        assertEquals(5.0, modifierPayload.get("rawValue").asDouble)
        assertEquals(10, sandbox.world.getScore("#max", "attr"))
        assertEquals(5, sandbox.world.getScore("#bonus", "attr"))
        assertTrue(zombie.attributeModifiers[ResourceLocation.parse("minecraft:max_health")].isNullOrEmpty())
        assertEquals("40.0", sandbox.world.outputs.last { it.command == "attribute get" }.text)
        val emerald = sandbox.world.requirePlayer("Steve").inventory.first { it.id == ResourceLocation.parse("minecraft:emerald") }
        assertEquals("Gift", emerald.components.getAsJsonObject("minecraft:custom_name").get("text").asString)
        assertEquals("from loot", emerald.components.getAsJsonArray("minecraft:lore")[0].asJsonObject.get("text").asString)

        val error = assertFailsWith<SandboxException> {
            sandbox.executeCommand("attribute @e[type=minecraft:zombie,limit=1] minecraft:max_health modifier add demo:bad 1 divide")
        }
        assertEquals(DiagnosticCode.INPUT_FORMAT, error.code)

        sandbox.executeCommand("attribute @e[type=minecraft:zombie,limit=1] minecraft:max_health modifier add demo:snapshot 0.1 add_multiplied_base")
        val modifierJson = zombie.toJson(sandbox.profile)
            .asJsonObject
            .getAsJsonObject("attributeModifiers")
            .getAsJsonArray("minecraft:max_health")[0]
            .asJsonObject
        assertEquals("demo:snapshot", modifierJson.get("id").asString)
        assertEquals("add_multiplied_base", modifierJson.get("operation").asString)
        val attributeNbt = zombie.fullNbt(sandbox.profile)
            .getAsJsonArray("Attributes")
            .first { it.asJsonObject.get("id").asString == "minecraft:max_health" }
            .asJsonObject
        val modifierNbt = attributeNbt.getAsJsonArray("modifiers")[0].asJsonObject
        assertEquals("demo:snapshot", modifierNbt.get("id").asString)
        assertEquals(0.1, modifierNbt.get("amount").asDouble)
        assertEquals("add_multiplied_base", modifierNbt.get("operation").asString)
    }

    @Test
    fun `loot command supports fish mine and empty kill sources`() {
        val pack = writeLootSourcePack(Files.createTempDirectory("dps-loot-source-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))

        sandbox.executeCommand("setblock 0 64 0 minecraft:stone")
        sandbox.executeCommand("loot give Steve fish demo:fish 0 64 0 minecraft:stick")
        sandbox.executeCommand("loot give Steve mine 0 64 0 minecraft:stick")
        sandbox.executeCommand("summon minecraft:zombie 2 64 0")
        sandbox.executeCommand("""summon minecraft:zombie 3 64 0 {Tags:["named"],CustomName:'{"text":"Named Zombie"}'}""")
        sandbox.executeCommand("item replace entity @e[type=minecraft:zombie,limit=1] weapon.mainhand with minecraft:stick")
        sandbox.executeCommand("loot give Steve kill @e[type=minecraft:zombie,limit=1]")
        sandbox.executeCommand("execute in minecraft:the_nether run loot spawn 1 64 1 mine 0 64 0")
        sandbox.executeCommand("loot give Steve entity demo:entity_context @e[type=minecraft:zombie,limit=1]")
        sandbox.executeCommand("loot give Steve entity demo:copy_name @e[tag=named,limit=1]")
        sandbox.executeCommand("loot give Steve block demo:block_context 0 64 0 minecraft:diamond_pickaxe")
        sandbox.executeCommand("loot give Steve block demo:copy_components 0 64 0 minecraft:diamond_pickaxe[minecraft:damage=7,demo:copied=true,demo:skip=true]")
        sandbox.executeCommand("loot give Steve block demo:apply_bonus 0 64 0 minecraft:diamond_pickaxe[minecraft:enchantments={\"minecraft:fortune\":2}]")
        sandbox.executeCommand("loot give Steve equipment demo:equipment_context @e[type=minecraft:zombie,limit=1] weapon.mainhand")
        sandbox.executeCommand("loot give Steve loot demo:enchanted")
        sandbox.executeCommand("loot give Steve loot demo:tag_items")
        sandbox.executeCommand("loot give Steve loot demo:tag_pick")
        sandbox.executeCommand("loot replace entity @e[type=minecraft:zombie,limit=1] weapon.offhand loot demo:fish")

        val zombie = sandbox.world.entities.first { it.type == ResourceLocation.parse("minecraft:zombie") }
        val inventoryIds = sandbox.world.requirePlayer("Steve").inventory.map { it.id }.toSet()
        assertTrue(ResourceLocation.parse("minecraft:diamond") in inventoryIds)
        assertTrue(ResourceLocation.parse("minecraft:stone") in inventoryIds)
        assertTrue(ResourceLocation.parse("minecraft:gold_ingot") in inventoryIds)
        assertTrue(ResourceLocation.parse("minecraft:cobblestone") in inventoryIds)
        assertTrue(ResourceLocation.parse("minecraft:apple") in inventoryIds)
        val copied = sandbox.world.requirePlayer("Steve").inventory.first { it.id == ResourceLocation.parse("minecraft:iron_nugget") }
        assertEquals(7, copied.components.get("minecraft:damage").asInt)
        assertEquals(true, copied.components.get("demo:copied").asBoolean)
        assertTrue(!copied.components.has("demo:skip"))
        val bonus = sandbox.world.requirePlayer("Steve").inventory.first { it.id == ResourceLocation.parse("minecraft:raw_gold") }
        assertEquals(4, bonus.count)
        val rawGoldCounts = sandbox.world.requirePlayer("Steve").inventory
            .filter { it.id == ResourceLocation.parse("minecraft:raw_gold") }
            .map { it.count }
        assertTrue(2 in rawGoldCounts)
        val emerald = sandbox.world.requirePlayer("Steve").inventory.first { it.id == ResourceLocation.parse("minecraft:emerald") }
        assertEquals(2, emerald.count)
        val nameTag = sandbox.world.requirePlayer("Steve").inventory.first { it.id == ResourceLocation.parse("minecraft:name_tag") }
        assertEquals("Named Zombie", nameTag.components.getAsJsonObject("minecraft:custom_name").get("text").asString)
        val enchanted = sandbox.world.requirePlayer("Steve").inventory.first { it.id == ResourceLocation.parse("minecraft:experience_bottle") }
        val enchantments = enchanted.components.getAsJsonObject("minecraft:enchantments")
        assertEquals(1, enchantments.get("minecraft:sharpness").asInt)
        assertEquals(3, enchantments.get("minecraft:unbreaking").asInt)
        assertEquals(ResourceLocation.parse("minecraft:diamond"), zombie.equipment[EquipmentSlots.OFFHAND]?.id)
        val spawnedItem = sandbox.world.entities.first {
            it.type == ResourceLocation.parse("minecraft:item") &&
                it.nbt.getAsJsonObject("Item")?.get("id")?.asString == "minecraft:stone"
        }
        assertEquals(ResourceLocation.parse("minecraft:the_nether"), spawnedItem.dimension)

        val lootGiveOutputs = sandbox.world.outputs.filter { it.command == "loot give" }
        assertEquals(12, lootGiveOutputs.size)
        assertEquals("players", lootGiveOutputs.first().payload?.asJsonObject?.get("targetKind")?.asString)
        assertEquals("minecraft:diamond", lootGiveOutputs.first().payload?.asJsonObject?.getAsJsonArray("items")?.get(0)?.asJsonObject?.get("id")?.asString)
        val copiedOutputItem = lootGiveOutputs.first {
            it.payload?.asJsonObject?.getAsJsonArray("items")?.any { item ->
                item.asJsonObject.get("id").asString == "minecraft:iron_nugget"
            } == true
        }.payload?.asJsonObject?.getAsJsonArray("items")?.get(0)?.asJsonObject ?: error("missing copied loot output")
        assertEquals(7, copiedOutputItem.getAsJsonObject("components").get("minecraft:damage").asInt)
        val bonusOutputItem = lootGiveOutputs.first {
            it.payload?.asJsonObject?.getAsJsonArray("items")?.any { item ->
                item.asJsonObject.get("id").asString == "minecraft:raw_gold"
            } == true
        }.payload?.asJsonObject?.getAsJsonArray("items")?.get(0)?.asJsonObject ?: error("missing apply_bonus loot output")
        assertEquals(4, bonusOutputItem.get("count").asInt)
        val nameTagOutputItem = lootGiveOutputs.first {
            it.payload?.asJsonObject?.getAsJsonArray("items")?.any { item ->
                item.asJsonObject.get("id").asString == "minecraft:name_tag"
            } == true
        }.payload?.asJsonObject?.getAsJsonArray("items")?.get(0)?.asJsonObject ?: error("missing copy_name loot output")
        assertEquals("Named Zombie", nameTagOutputItem.getAsJsonObject("components").getAsJsonObject("minecraft:custom_name").get("text").asString)
        val enchantedOutputItem = lootGiveOutputs.first {
            it.payload?.asJsonObject?.getAsJsonArray("items")?.any { item ->
                item.asJsonObject.get("id").asString == "minecraft:experience_bottle"
            } == true
        }.payload?.asJsonObject?.getAsJsonArray("items")?.get(0)?.asJsonObject ?: error("missing enchanted loot output")
        assertEquals(3, enchantedOutputItem.getAsJsonObject("components").getAsJsonObject("minecraft:enchantments").get("minecraft:unbreaking").asInt)
        val tagOutput = lootGiveOutputs.first {
            val items = it.payload?.asJsonObject?.getAsJsonArray("items")
            items?.size() == 2 && items.any { item ->
                item.asJsonObject.get("id").asString == "minecraft:emerald"
            }
        }.payload?.asJsonObject ?: error("missing tag loot output")
        assertEquals(2, tagOutput.getAsJsonArray("items").size())
        assertEquals(4, tagOutput.get("totalCount").asInt)
        val tagPickOutput = lootGiveOutputs.last().payload?.asJsonObject ?: error("missing expanded tag loot output")
        assertEquals(1, tagPickOutput.getAsJsonArray("items").size())
        val tagPickItem = tagPickOutput.getAsJsonArray("items")[0].asJsonObject
        assertTrue(
            tagPickItem.get("id").asString in setOf("minecraft:raw_gold", "minecraft:emerald"),
            tagPickItem.toString(),
        )
        assertEquals(2, tagPickOutput.get("totalCount").asInt)
        val lootSpawnOutput = sandbox.world.outputs.single { it.command == "loot spawn" }
        assertEquals("minecraft:the_nether", lootSpawnOutput.payload?.asJsonObject?.get("dimension")?.asString)
        val lootReplaceOutput = sandbox.world.outputs.single { it.command == "loot replace" }
        assertEquals("weapon.offhand", lootReplaceOutput.payload?.asJsonObject?.get("slot")?.asString)
        assertEquals("minecraft:diamond", lootReplaceOutput.payload?.asJsonObject?.getAsJsonArray("items")?.get(0)?.asJsonObject?.get("id")?.asString)
    }

    @Test
    fun `item modify applies common item modifier functions`() {
        val pack = writeItemModifierPack(Files.createTempDirectory("dps-item-modifier-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))

        sandbox.executeCommand("item replace entity Steve hotbar.0 with minecraft:stick[demo:source=true,demo:skip=true] 1")
        sandbox.executeCommand("item modify entity Steve hotbar.0 demo:mark")

        val item = sandbox.world.requirePlayer("Steve").inventory[0]
        assertEquals(ResourceLocation.parse("minecraft:stick"), item.id)
        assertEquals(4, item.count)
        assertEquals(true, item.nbt.get("marked").asBoolean)
        assertEquals("tagged", item.components.get("demo:tag").asString)
        assertEquals("applied", item.components.get("demo:sequence").asString)
        assertEquals("pass", item.components.get("demo:filtered").asString)
        assertEquals("shared", item.components.get("demo:referenced").asString)
        assertEquals("Marked Stick", item.components.getAsJsonObject("minecraft:custom_name").get("text").asString)
        assertEquals("debuggable", item.components.getAsJsonArray("minecraft:lore")[0].asJsonObject.get("text").asString)
        assertEquals(3.0, item.components.get("minecraft:damage").asDouble)

        sandbox.executeCommand("item replace entity Steve hotbar.3 with minecraft:diamond 1")
        sandbox.executeCommand("item modify entity Steve hotbar.3 demo:copy_selected")

        val copiedComponents = sandbox.world.requirePlayer("Steve").inventory[3]
        assertEquals(true, copiedComponents.components.get("demo:source").asBoolean)
        assertEquals(3.0, copiedComponents.components.get("minecraft:damage").asDouble)
        assertTrue(!copiedComponents.components.has("demo:skip"))

        sandbox.executeCommand("item replace entity Steve hotbar.4 with minecraft:diamond{source:{level:2}} 1")
        sandbox.executeCommand("item modify entity Steve hotbar.4 demo:copy_nbt")

        val copiedNbt = sandbox.world.requirePlayer("Steve").inventory[4]
        assertEquals(2, copiedNbt.nbt.getAsJsonObject("copied").get("level").asInt)

        sandbox.executeCommand("item replace entity Steve hotbar.1 with minecraft:diamond 1")
        sandbox.executeCommand("item modify entity Steve hotbar.1 demo:mark")

        val unmatched = sandbox.world.requirePlayer("Steve").inventory[1]
        assertTrue(!unmatched.components.has("demo:filtered"))
        assertEquals("fail", unmatched.components.get("demo:filtered_fail").asString)

        sandbox.executeCommand("item replace entity Steve hotbar.2 with minecraft:stick 2")
        sandbox.executeCommand("item modify entity Steve hotbar.2 demo:change_item")

        val changed = sandbox.world.requirePlayer("Steve").inventory[2]
        assertEquals(ResourceLocation.parse("minecraft:carrot"), changed.id)
        assertEquals(2, changed.count)

        sandbox.executeCommand("item modify entity Steve hotbar.2 demo:discard")

        val discarded = sandbox.world.requirePlayer("Steve").inventory[2]
        assertEquals(ResourceLocation.parse("minecraft:air"), discarded.id)
        assertEquals(0, discarded.count)

        val modifyOutputs = sandbox.world.outputs.filter { it.command == "item modify" }
        assertEquals(6, modifyOutputs.size)
        val firstModifyPayload = modifyOutputs.first().payload?.asJsonObject ?: error("missing item modify payload")
        assertEquals("entity", firstModifyPayload.get("targetKind").asString)
        assertEquals("hotbar.0", firstModifyPayload.get("slot").asString)
        assertEquals("demo:mark", firstModifyPayload.get("modifier").asString)
        assertEquals(1, firstModifyPayload.get("modified").asInt)
        assertEquals("minecraft:stick", firstModifyPayload.getAsJsonArray("items")[0].asJsonObject.getAsJsonObject("item").get("id").asString)
    }

    @Test
    fun `item replace and modify support block slots`() {
        val pack = writeItemModifierPack(Files.createTempDirectory("dps-block-item-modifier-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))

        sandbox.executeCommand("setblock 0 64 0 minecraft:chest")
        sandbox.executeCommand("item replace block 0 64 0 container.0 with minecraft:stick 1")
        sandbox.executeCommand("item modify block 0 64 0 container.0 demo:mark")

        val items = sandbox.world.requireBlock(BlockPos(0, 64, 0)).fullNbt(BlockPos(0, 64, 0), sandbox.profile)
            .getAsJsonArray("Items")
        val item = items.single { it.asJsonObject.get("Slot").asInt == 0 }.asJsonObject
        assertEquals("minecraft:stick", item.get("id").asString)
        assertEquals(4, item.get("count").asInt)
        assertEquals("tagged", item.getAsJsonObject("components").get("demo:tag").asString)
        assertEquals(true, item.getAsJsonObject("components").getAsJsonObject("minecraft:custom_data").get("marked").asBoolean)

        val modifyOutput = sandbox.world.outputs.single { it.command == "item modify" }
        val payload = modifyOutput.payload?.asJsonObject ?: error("missing item modify payload")
        assertEquals("block", payload.get("targetKind").asString)
        assertEquals("container.0", payload.get("slot").asString)
        assertEquals("demo:mark", payload.get("modifier").asString)
        assertEquals("0 64 0", payload.getAsJsonArray("items")[0].asJsonObject.get("target").asString)
    }

    @Test
    fun `item replace copies from entity and block slots`() {
        val sandbox = createFunctionSandboxFromString(
            version = "26.2",
            functionText = "",
            functionId = "demo:empty",
        )

        sandbox.executeCommand("setblock 0 64 0 minecraft:chest")
        sandbox.executeCommand("item replace entity Steve hotbar.0 with minecraft:diamond 3")
        sandbox.executeCommand("item replace block 0 64 0 container.0 from entity Steve hotbar.0")
        sandbox.executeCommand("item replace entity Steve hotbar.1 from block 0 64 0 container.0")

        val copiedToBlock = sandbox.world.requireBlock(BlockPos(0, 64, 0)).fullNbt(BlockPos(0, 64, 0), sandbox.profile)
            .getAsJsonArray("Items")
            .single { it.asJsonObject.get("Slot").asInt == 0 }
            .asJsonObject
        assertEquals("minecraft:diamond", copiedToBlock.get("id").asString)
        assertEquals(3, copiedToBlock.get("count").asInt)

        val copiedToPlayer = sandbox.world.requirePlayer("Steve").inventory[1]
        assertEquals(ResourceLocation.parse("minecraft:diamond"), copiedToPlayer.id)
        assertEquals(3, copiedToPlayer.count)

        sandbox.executeCommand("item replace block 0 64 0 container.0 from entity Steve hotbar.8")
        val afterClear = sandbox.world.requireBlock(BlockPos(0, 64, 0)).fullNbt(BlockPos(0, 64, 0), sandbox.profile)
            .getAsJsonArray("Items")
        assertTrue(afterClear.none { it.asJsonObject.get("Slot").asInt == 0 })
    }

    @Test
    fun `item inputs support command nbt and components`() {
        val sandbox = createFunctionSandboxFromString(
            version = "26.2",
            functionText = "",
            functionId = "demo:empty",
        )

        sandbox.executeCommand("give Steve minecraft:stick{marked: true, label: 'old value'} 2")
        sandbox.executeCommand("item replace entity Steve hotbar.1 with minecraft:diamond[custom_data={source: component, nested: {ready: true}}, damage=3] 4")
        sandbox.executeCommand("setblock 0 64 0 minecraft:chest")
        sandbox.executeCommand("item replace block 0 64 0 container.0 with minecraft:apple[minecraft:custom_data={boxed:true}] 5")
        sandbox.executeCommand("summon minecraft:zombie 1 64 0")
        sandbox.executeCommand("item replace entity @e[type=minecraft:zombie, limit=1] weapon.mainhand with minecraft:stick{marked: true}")

        val player = sandbox.world.requirePlayer("Steve")
        val nbtItem = player.inventory[0]
        assertEquals(ResourceLocation.parse("minecraft:stick"), nbtItem.id)
        assertEquals(2, nbtItem.count)
        assertEquals(true, nbtItem.nbt.get("marked").asBoolean)
        assertEquals("old value", nbtItem.nbt.get("label").asString)

        val giveOutput = sandbox.world.outputs.single { it.command == "give" }
        assertEquals("2", giveOutput.text)
        assertEquals("minecraft:stick", giveOutput.payload?.asJsonObject?.getAsJsonObject("item")?.get("id")?.asString)
        assertEquals("old value", giveOutput.payload?.asJsonObject?.getAsJsonObject("item")?.getAsJsonObject("nbt")?.get("label")?.asString)

        val replaceOutputs = sandbox.world.outputs.filter { it.command == "item replace" }
        assertEquals(3, replaceOutputs.size)
        assertEquals("entity", replaceOutputs[0].payload?.asJsonObject?.get("targetKind")?.asString)
        assertEquals("hotbar.1", replaceOutputs[0].payload?.asJsonObject?.get("slot")?.asString)
        assertEquals("minecraft:diamond", replaceOutputs[0].payload?.asJsonObject?.getAsJsonObject("item")?.get("id")?.asString)
        assertEquals("block", replaceOutputs[1].payload?.asJsonObject?.get("targetKind")?.asString)
        assertEquals("container.0", replaceOutputs[1].payload?.asJsonObject?.get("slot")?.asString)

        val componentItem = player.inventory[1]
        assertEquals(ResourceLocation.parse("minecraft:diamond"), componentItem.id)
        assertEquals(4, componentItem.count)
        assertEquals(3, componentItem.components.get("minecraft:damage").asInt)
        assertEquals("component", componentItem.nbt.get("source").asString)
        assertEquals(true, componentItem.nbt.getAsJsonObject("nested").get("ready").asBoolean)

        val blockItem = sandbox.world.requireBlock(BlockPos(0, 64, 0))
            .fullNbt(BlockPos(0, 64, 0), sandbox.profile)
            .getAsJsonArray("Items")
            .single { it.asJsonObject.get("Slot").asInt == 0 }
            .asJsonObject
        assertEquals("minecraft:apple", blockItem.get("id").asString)
        assertEquals(5, blockItem.get("count").asInt)
        assertEquals(true, blockItem.getAsJsonObject("components").getAsJsonObject("minecraft:custom_data").get("boxed").asBoolean)

        val zombie = sandbox.world.entities.single { it.type == ResourceLocation.parse("minecraft:zombie") }
        assertEquals(true, zombie.equipment[EquipmentSlots.MAINHAND]?.nbt?.get("marked")?.asBoolean)
    }

    @Test
    fun `item and loot commands support player ender chest slots`() {
        val modifierPack = writeItemModifierPack(Files.createTempDirectory("dps-ender-item-modifier-pack"))
        val lootPack = writeLootSourcePack(Files.createTempDirectory("dps-ender-loot-source-pack"))
        val sandbox = createSandbox("26.2", listOf(modifierPack, lootPack))

        sandbox.executeCommand("item replace entity Steve enderchest.0 with minecraft:stick 1")
        sandbox.executeCommand("item modify entity Steve enderchest.0 demo:mark")
        sandbox.executeCommand("item replace entity Steve hotbar.0 from entity Steve enderchest.0")
        sandbox.executeCommand("loot replace entity Steve enderchest.1 loot demo:fish")

        val player = sandbox.world.requirePlayer("Steve")
        val modifiedEnderItem = player.enderItems[0]
        assertEquals(ResourceLocation.parse("minecraft:stick"), modifiedEnderItem.id)
        assertEquals(4, modifiedEnderItem.count)
        assertEquals(true, modifiedEnderItem.nbt.get("marked").asBoolean)
        assertEquals("tagged", modifiedEnderItem.components.get("demo:tag").asString)

        val copiedToInventory = player.inventory[0]
        assertEquals(ResourceLocation.parse("minecraft:stick"), copiedToInventory.id)
        assertEquals(4, copiedToInventory.count)
        assertEquals(true, copiedToInventory.nbt.get("marked").asBoolean)

        val lootEnderItem = player.enderItems[1]
        assertEquals(ResourceLocation.parse("minecraft:diamond"), lootEnderItem.id)

        val snapshotEnderItems = sandbox.snapshotJson()
            .getAsJsonObject("players")
            .getAsJsonObject("Steve")
            .getAsJsonArray("enderItems")
        assertEquals("minecraft:stick", snapshotEnderItems[0].asJsonObject.get("id").asString)
        assertEquals("minecraft:diamond", snapshotEnderItems[1].asJsonObject.get("id").asString)
    }

    @Test
    fun `player mainhand item slots follow selected slot`() {
        val pack = writeLootSourcePack(Files.createTempDirectory("dps-selected-slot-loot-source-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))
        val player = sandbox.world.requirePlayer("Steve")
        player.selectedSlot = 4

        sandbox.executeCommand("item replace entity Steve weapon.mainhand with minecraft:carrot 2")
        sandbox.executeCommand("item replace entity Steve hotbar.0 from entity Steve weapon.mainhand")
        sandbox.executeCommand("loot replace entity Steve hotbar.selected loot demo:fish")
        sandbox.executeCommand("scoreboard objectives add items dummy")
        sandbox.executeCommand("execute if data entity Steve SelectedItem run scoreboard players set #selected items 1")

        assertEquals(ResourceLocation.parse("minecraft:carrot"), player.inventory[0].id)
        assertEquals(2, player.inventory[0].count)
        assertEquals(ResourceLocation.parse("minecraft:diamond"), player.inventory[4].id)
        assertEquals(1, sandbox.world.getScore("#selected", "items"))

        val inventoryNbt = player.fullNbt(sandbox.profile)
            .getAsJsonArray("Inventory")
        val selectedItemNbt = inventoryNbt.single { it.asJsonObject.get("Slot").asInt == 4 }.asJsonObject
        assertEquals("minecraft:diamond", selectedItemNbt.get("id").asString)
        assertEquals("minecraft:diamond", player.fullNbt(sandbox.profile).getAsJsonObject("SelectedItem").get("id").asString)
    }

    @Test
    fun `item commands support non-player entity equipment slots`() {
        val pack = writeItemModifierPack(Files.createTempDirectory("dps-entity-equipment-pack"))
        writeEquipmentPredicateEntries(pack)
        val sandbox = createSandbox("26.2", listOf(pack))

        sandbox.executeCommand("summon minecraft:zombie 0 64 0")
        sandbox.executeCommand("item replace entity @e[type=minecraft:zombie,limit=1] weapon.mainhand with minecraft:stick 1")
        sandbox.executeCommand("item modify entity @e[type=minecraft:zombie,limit=1] weapon.mainhand demo:mark")
        sandbox.executeCommand("enchant @e[type=minecraft:zombie,limit=1] minecraft:sharpness 3")
        sandbox.executeCommand("item replace entity @e[type=minecraft:zombie,limit=1] armor.head with minecraft:iron_helmet 1")
        sandbox.executeCommand("item replace entity Steve hotbar.3 from entity @e[type=minecraft:zombie,limit=1] weapon.mainhand")
        sandbox.executeCommand("scoreboard objectives add equipment dummy")
        sandbox.executeCommand("execute as @e[type=minecraft:zombie,limit=1] if predicate demo:zombie_mainhand_stick run scoreboard players add #predicate equipment 1")
        sandbox.executeCommand("execute as @e[type=minecraft:zombie,limit=1] unless predicate demo:zombie_offhand_stick run scoreboard players add #predicate equipment 1")
        sandbox.executeCommand("execute as @e[type=minecraft:zombie,limit=1] if predicate demo:zombie_helmet_nbt run scoreboard players add #predicate equipment 1")

        val zombie = sandbox.world.entities.first { it.type == ResourceLocation.parse("minecraft:zombie") }
        val equipped = zombie.equipment[EquipmentSlots.MAINHAND] ?: error("missing zombie mainhand equipment")
        assertEquals(ResourceLocation.parse("minecraft:stick"), equipped.id)
        assertEquals(4, equipped.count)
        assertEquals(true, equipped.nbt.get("marked").asBoolean)
        assertEquals(3, equipped.components.getAsJsonObject("minecraft:enchantments").get("minecraft:sharpness").asInt)

        val handItem = zombie.fullNbt(sandbox.profile).getAsJsonArray("HandItems")[0].asJsonObject
        assertEquals("minecraft:stick", handItem.get("id").asString)
        assertEquals(4, handItem.get("count").asInt)
        assertEquals(true, handItem.getAsJsonObject("components").getAsJsonObject("minecraft:custom_data").get("marked").asBoolean)
        assertEquals(3, handItem.getAsJsonObject("components").getAsJsonObject("minecraft:enchantments").get("minecraft:sharpness").asInt)

        val copiedToPlayer = sandbox.world.requirePlayer("Steve").inventory[3]
        assertEquals(ResourceLocation.parse("minecraft:stick"), copiedToPlayer.id)
        assertEquals(4, copiedToPlayer.count)
        assertEquals(true, copiedToPlayer.nbt.get("marked").asBoolean)
        assertEquals(3, copiedToPlayer.components.getAsJsonObject("minecraft:enchantments").get("minecraft:sharpness").asInt)

        val snapshotEquipment = sandbox.snapshotJson()
            .getAsJsonArray("entities")
            .single { it.asJsonObject.get("uuid").asString == zombie.uuid }
            .asJsonObject
            .getAsJsonObject("equipment")
        assertEquals("minecraft:stick", snapshotEquipment.getAsJsonObject("weapon.mainhand").get("id").asString)
        assertEquals("minecraft:iron_helmet", snapshotEquipment.getAsJsonObject("armor.head").get("id").asString)
        assertEquals(3, sandbox.world.getScore("#predicate", "equipment"))

        val enchantOutput = sandbox.world.outputs.single { it.command == "enchant" }
        val enchantPayload = enchantOutput.payload?.asJsonObject ?: error("missing enchant payload")
        assertEquals("minecraft:sharpness", enchantPayload.get("enchantment").asString)
        assertEquals(3, enchantPayload.get("level").asInt)
        assertEquals("minecraft:stick", enchantPayload.getAsJsonArray("items")[0].asJsonObject.getAsJsonObject("item").get("id").asString)
    }

    @Test
    fun `effect commands support non-player entity active effects`() {
        val pack = writeEffectPredicatePack(Files.createTempDirectory("dps-effect-predicate-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))

        sandbox.executeCommand("summon minecraft:zombie 0 64 0")
        sandbox.executeCommand("scoreboard objectives add effects dummy")
        sandbox.executeCommand("effect give @e[type=minecraft:zombie,limit=1] minecraft:speed 7 2 true")
        sandbox.executeCommand("execute if data entity @e[type=minecraft:zombie,limit=1] ActiveEffects[{id:\"minecraft:speed\"}] run scoreboard players add #nbt effects 1")
        sandbox.executeCommand("execute as @e[type=minecraft:zombie,limit=1] if predicate demo:zombie_speed_effect run scoreboard players add #predicate effects 1")
        sandbox.executeCommand("execute as @e[type=minecraft:zombie,limit=1] if predicate demo:zombie_no_strength run scoreboard players add #predicate effects 1")

        val zombie = sandbox.world.entities.first { it.type == ResourceLocation.parse("minecraft:zombie") }
        val speed = zombie.activeEffects[ResourceLocation.parse("minecraft:speed")] ?: error("missing zombie speed effect")
        assertEquals(140, speed.durationTicks)
        assertEquals(2, speed.amplifier)
        assertEquals(true, speed.hideParticles)

        val effectNbt = zombie.fullNbt(sandbox.profile)
            .getAsJsonArray("ActiveEffects")
            .single { it.asJsonObject.get("id").asString == "minecraft:speed" }
            .asJsonObject
        assertEquals(140, effectNbt.get("duration").asInt)
        assertEquals(2, effectNbt.get("amplifier").asInt)
        assertEquals(false, effectNbt.get("show_particles").asBoolean)

        val snapshotEffects = sandbox.snapshotJson()
            .getAsJsonArray("entities")
            .single { it.asJsonObject.get("uuid").asString == zombie.uuid }
            .asJsonObject
            .getAsJsonArray("effects")
        assertEquals("minecraft:speed", snapshotEffects.single().asJsonObject.get("id").asString)
        assertEquals(1, sandbox.world.getScore("#nbt", "effects"))
        assertEquals(2, sandbox.world.getScore("#predicate", "effects"))

        val giveOutput = sandbox.world.outputs.first { it.command == "effect give" }
        val givePayload = giveOutput.payload?.asJsonObject ?: error("missing effect give payload")
        assertEquals("minecraft:speed", givePayload.getAsJsonObject("effect").get("id").asString)
        assertEquals(140, givePayload.getAsJsonObject("effect").get("duration").asInt)
        assertEquals(2, givePayload.getAsJsonObject("effect").get("amplifier").asInt)
        assertEquals(true, givePayload.getAsJsonObject("effect").get("hideParticles").asBoolean)

        sandbox.executeCommand("effect clear @e[type=minecraft:zombie,limit=1] minecraft:speed")
        assertTrue(ResourceLocation.parse("minecraft:speed") !in zombie.activeEffects)

        sandbox.executeCommand("effect give @e[type=minecraft:zombie,limit=1] minecraft:strength 5 1 false")
        sandbox.executeCommand("effect clear @e[type=minecraft:zombie,limit=1]")
        assertTrue(zombie.activeEffects.isEmpty())

        val clearOutputs = sandbox.world.outputs.filter { it.command == "effect clear" }
        assertEquals("minecraft:speed", clearOutputs.first().payload?.asJsonObject?.get("effect")?.asString)
        assertEquals(false, clearOutputs.first().payload?.asJsonObject?.get("all")?.asBoolean)
        assertEquals(true, clearOutputs.last().payload?.asJsonObject?.get("all")?.asBoolean)
    }

    @Test
    fun `execute conditions cover predicate dimension biome and loaded state`() {
        val pack = writePredicatePack(Files.createTempDirectory("dps-execute-conditions-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))

        sandbox.executeCommand("scoreboard objectives add checks dummy")
        sandbox.executeCommand("execute if dimension minecraft:overworld run scoreboard players add #pass checks 1")
        sandbox.executeCommand("execute in minecraft:the_nether if dimension minecraft:the_nether run scoreboard players add #pass checks 1")
        sandbox.executeCommand("execute in minecraft:the_nether unless dimension minecraft:overworld run scoreboard players add #pass checks 1")
        sandbox.executeCommand("forceload add 0 0")
        sandbox.executeCommand("execute if loaded 1 64 1 run scoreboard players add #pass checks 1")
        sandbox.executeCommand("execute unless loaded 32 64 32 run scoreboard players add #pass checks 1")
        sandbox.executeCommand("fillbiome 0 64 0 0 64 0 minecraft:forest")
        sandbox.executeCommand("execute if biome 0 64 0 minecraft:forest run scoreboard players add #pass checks 1")
        sandbox.executeCommand("execute unless biome 0 64 0 minecraft:desert run scoreboard players add #pass checks 1")
        sandbox.executeCommand("execute as Steve if predicate demo:is_player run scoreboard players add #pass checks 1")
        sandbox.executeCommand("execute in minecraft:the_nether if predicate demo:in_nether run scoreboard players add #pass checks 1")
        sandbox.executeCommand("execute unless predicate demo:false run scoreboard players add #pass checks 1")
        sandbox.executeCommand("execute if dimension minecraft:the_nether run scoreboard players add #fail checks 1")
        sandbox.executeCommand("execute if loaded 32 64 32 run scoreboard players add #fail checks 1")
        sandbox.executeCommand("execute if biome 0 64 0 minecraft:desert run scoreboard players add #fail checks 1")
        sandbox.executeCommand("execute if predicate demo:in_nether run scoreboard players add #fail checks 1")
        sandbox.executeCommand("execute if predicate demo:false run scoreboard players add #fail checks 1")

        assertEquals(10, sandbox.world.getScore("#pass", "checks"))
        assertEquals(0, sandbox.world.getScore("#fail", "checks"))
    }

    @Test
    fun `selector options filter by name distance volume and sort`() {
        val sandbox = createFunctionSandboxFromString(
            version = "26.2",
            functionText = "",
            functionId = "demo:empty",
        )
        val alex = sandbox.createPlayer("Alex")
        val blair = sandbox.createPlayer("Blair")
        alex.position = Position(0.0, 64.0, 0.0)
        blair.position = Position(8.0, 64.0, 0.0)
        alex.advancementProgress[ResourceLocation.parse("demo:selector_complete")] =
            AdvancementProgress(mutableMapOf("done" to true))
        alex.advancementProgress[ResourceLocation.parse("demo:selector_partial")] =
            AdvancementProgress(mutableMapOf("visible" to true, "hidden" to false))

        sandbox.executeCommand("""summon minecraft:pig 1 64 1 {Tags:["near"],Health:7f}""")
        sandbox.executeCommand("""summon minecraft:pig 9 64 0 {Tags:["far"],Health:3f}""")
        sandbox.executeCommand("""summon minecraft:cow 2 64 0 {Tags:["near"]}""")
        sandbox.executeCommand("scoreboard objectives add selector dummy")
        sandbox.executeCommand("scoreboard objectives add selector_scores dummy")
        sandbox.executeCommand("scoreboard objectives add selector_other dummy")
        sandbox.executeCommand("gamemode creative Alex")
        sandbox.executeCommand("gamemode adventure Blair")
        sandbox.executeCommand("experience set Alex 5 levels")
        sandbox.executeCommand("experience set Blair 2 levels")
        sandbox.executeCommand("team add red")
        sandbox.executeCommand("team add blue")
        sandbox.executeCommand("team join red Alex")
        sandbox.executeCommand("team join blue Blair")
        sandbox.executeCommand("scoreboard players set Alex selector_scores 5")
        sandbox.executeCommand("scoreboard players set Alex selector_other 3")
        sandbox.executeCommand("scoreboard players set Blair selector_scores 2")
        sandbox.executeCommand("scoreboard players set Blair selector_other 3")
        sandbox.executeCommand("""execute as @e[type=minecraft:pig,tag=near,limit=1] run scoreboard players set @s selector_scores 7""")
        sandbox.executeCommand("""execute as @e[type=minecraft:pig,tag=far,limit=1] run scoreboard players set @s selector_scores 1""")
        sandbox.executeCommand("""rotate @e[type=minecraft:pig,tag=near,limit=1] 30 10""")
        sandbox.executeCommand("""rotate @e[type=minecraft:pig,tag=far,limit=1] -90 -20""")
        sandbox.executeCommand("""tag @e[type=minecraft:pig,x=0,y=64,z=0,dx=2,dy=0,dz=2] add boxed""")
        sandbox.executeCommand("""execute if entity @e[tag=boxed,limit=1] run scoreboard players add #boxed selector 1""")
        sandbox.executeCommand("""execute if entity @e[type=minecraft:pig,x=0,y=64,z=0,distance=..2,limit=1] run scoreboard players add #near selector 1""")
        sandbox.executeCommand("""execute if entity @a[name=Alex,x=0,y=64,z=0,distance=..1,limit=1] run scoreboard players add #name selector 1""")
        sandbox.executeCommand("""execute unless entity @a[name=!Alex,x=0,y=64,z=0,distance=..1] run scoreboard players add #name selector 1""")
        sandbox.executeCommand("""execute if entity @a[gamemode=creative,scores={selector_scores=5..,selector_other=3}] run scoreboard players add #score selector 1""")
        sandbox.executeCommand("""execute if entity @a[gamemode=!spectator,scores={selector_scores=2..5,selector_other=3},limit=2] run scoreboard players add #score selector 1""")
        sandbox.executeCommand("""execute if entity @e[type=minecraft:pig,scores={selector_scores=..1},limit=1] run scoreboard players add #score selector 1""")
        sandbox.executeCommand("""execute unless entity @e[type=minecraft:pig,scores={selector_scores=2..6}] run scoreboard players add #score selector 1""")
        sandbox.executeCommand("""execute if entity @e[type=minecraft:pig,nbt={Health:7f},limit=1] run scoreboard players add #nbt selector 1""")
        sandbox.executeCommand("""execute if entity @e[type=minecraft:pig,nbt=!{Health:7f},limit=1] run scoreboard players add #nbt selector 1""")
        sandbox.executeCommand("""execute unless entity @e[type=minecraft:pig,nbt={Health:9f}] run scoreboard players add #nbt selector 1""")
        sandbox.executeCommand("""execute if entity @a[name=Alex,advancements={demo:selector_complete=true}] run scoreboard players add #adv selector 1""")
        sandbox.executeCommand("""execute if entity @a[name=Alex,advancements={demo:selector_partial={visible=true,hidden=false}}] run scoreboard players add #adv selector 1""")
        sandbox.executeCommand("""execute if entity @a[name=Blair,advancements={demo:selector_complete=false}] run scoreboard players add #adv selector 1""")
        sandbox.executeCommand("""execute unless entity @a[name=Alex,advancements={demo:selector_complete=false}] run scoreboard players add #adv selector 1""")
        sandbox.executeCommand("""execute if entity @a[team=red,level=5] run scoreboard players add #teamlevel selector 1""")
        sandbox.executeCommand("""execute if entity @a[team=!red,level=..2] run scoreboard players add #teamlevel selector 1""")
        sandbox.executeCommand("""execute if entity @a[team=!,limit=2] run scoreboard players add #teamlevel selector 1""")
        sandbox.executeCommand("""execute unless entity @a[name=!Steve,team=] run scoreboard players add #teamlevel selector 1""")
        sandbox.executeCommand("""execute if entity @e[type=minecraft:pig,y_rotation=-100..-80,x_rotation=-25..-15] run scoreboard players add #rotation selector 1""")
        sandbox.executeCommand("""execute if entity @e[type=minecraft:pig,y_rotation=30,x_rotation=10] run scoreboard players add #rotation selector 1""")
        sandbox.executeCommand("""execute unless entity @e[type=minecraft:pig,y_rotation=31,x_rotation=10] run scoreboard players add #rotation selector 1""")
        sandbox.executeCommand("""tag @e[type=minecraft:pig,x=0,y=64,z=0,sort=furthest,limit=1] add furthest""")
        sandbox.executeCommand("""tag @e[type=minecraft:pig,x=0,y=64,z=0,sort=nearest,limit=1] add nearest""")
        sandbox.executeCommand("""execute if entity @e[tag=furthest,tag=far] run scoreboard players add #sort selector 1""")
        sandbox.executeCommand("""execute if entity @e[tag=nearest,tag=near] run scoreboard players add #sort selector 1""")

        assertEquals(1, sandbox.world.getScore("#boxed", "selector"))
        assertEquals(1, sandbox.world.getScore("#near", "selector"))
        assertEquals(2, sandbox.world.getScore("#name", "selector"))
        assertEquals(4, sandbox.world.getScore("#score", "selector"))
        assertEquals(3, sandbox.world.getScore("#nbt", "selector"))
        assertEquals(4, sandbox.world.getScore("#adv", "selector"))
        assertEquals(4, sandbox.world.getScore("#teamlevel", "selector"))
        assertEquals(3, sandbox.world.getScore("#rotation", "selector"))
        assertEquals(2, sandbox.world.getScore("#sort", "selector"))

        val invalidDistance = assertFailsWith<SandboxException> {
            sandbox.executeCommand("""execute if entity @e[distance=-1] run say invalid""")
        }
        assertEquals(DiagnosticCode.INPUT_FORMAT, invalidDistance.code)

        val invalidScoreRange = assertFailsWith<SandboxException> {
            sandbox.executeCommand("""execute if entity @a[scores={selector_scores=5..2}] run say invalid""")
        }
        assertEquals(DiagnosticCode.INPUT_FORMAT, invalidScoreRange.code)

        val invalidNbt = assertFailsWith<SandboxException> {
            sandbox.executeCommand("""execute if entity @e[nbt=1] run say invalid""")
        }
        assertEquals(DiagnosticCode.INPUT_FORMAT, invalidNbt.code)

        val invalidAdvancement = assertFailsWith<SandboxException> {
            sandbox.executeCommand("""execute if entity @a[advancements={demo:selector_complete=maybe}] run say invalid""")
        }
        assertEquals(DiagnosticCode.INPUT_FORMAT, invalidAdvancement.code)

        val invalidRotationRange = assertFailsWith<SandboxException> {
            sandbox.executeCommand("""execute if entity @e[x_rotation=10..-10] run say invalid""")
        }
        assertEquals(DiagnosticCode.INPUT_FORMAT, invalidRotationRange.code)
    }

    @Test
    fun `execute as preserves position while at and positioned as move context`() {
        val sandbox = createFunctionSandboxFromString(
            version = "26.2",
            functionText = "",
            functionId = "demo:empty",
        )

        sandbox.executeCommand("""execute in minecraft:the_nether run summon minecraft:pig 5 0 0 {Tags:["anchor"]}""")
        sandbox.executeCommand("""execute as @e[tag=anchor,limit=1] run summon minecraft:marker ~1 ~0 ~0 {Tags:["from_as"]}""")
        sandbox.executeCommand("""execute at @e[tag=anchor,limit=1] run summon minecraft:marker ~1 ~0 ~0 {Tags:["from_at"]}""")
        sandbox.executeCommand("""execute positioned as @e[tag=anchor,limit=1] run summon minecraft:marker ~2 ~0 ~0 {Tags:["from_positioned_as"]}""")

        val fromAs = sandbox.world.entities.single { "from_as" in it.tags }
        val fromAt = sandbox.world.entities.single { "from_at" in it.tags }
        val fromPositionedAs = sandbox.world.entities.single { "from_positioned_as" in it.tags }
        assertEquals(Position(1.0, 0.0, 0.0), fromAs.position)
        assertEquals(ResourceLocation.parse("minecraft:overworld"), fromAs.dimension)
        assertEquals(Position(6.0, 0.0, 0.0), fromAt.position)
        assertEquals(ResourceLocation.parse("minecraft:the_nether"), fromAt.dimension)
        assertEquals(Position(7.0, 0.0, 0.0), fromPositionedAs.position)
        assertEquals(ResourceLocation.parse("minecraft:overworld"), fromPositionedAs.dimension)
    }

    @Test
    fun `execute align floors selected axes and rejects invalid swizzles`() {
        val sandbox = createFunctionSandboxFromString(
            version = "26.2",
            functionText = "",
            functionId = "demo:empty",
        )

        sandbox.executeCommand("""execute positioned 1.9 2.9 -1.2 align xz run summon minecraft:marker ~ ~ ~ {Tags:["aligned"]}""")

        val aligned = sandbox.world.entities.single { "aligned" in it.tags }
        assertEquals(Position(1.0, 2.9, -2.0), aligned.position)

        val invalidAxis = assertFailsWith<SandboxException> {
            sandbox.executeCommand("execute align xq run say invalid")
        }
        assertEquals(DiagnosticCode.INPUT_FORMAT, invalidAxis.code)

        val duplicateAxis = assertFailsWith<SandboxException> {
            sandbox.executeCommand("execute align xx run say invalid")
        }
        assertEquals(DiagnosticCode.INPUT_FORMAT, duplicateAxis.code)
    }

    @Test
    fun `execute rotated controls relative teleport rotation`() {
        val sandbox = createFunctionSandboxFromString(
            version = "26.2",
            functionText = "",
            functionId = "demo:empty",
        )

        sandbox.executeCommand("""summon minecraft:pig 0 0 0 {Tags:["anchor"]}""")
        sandbox.executeCommand("""execute as @e[tag=anchor,limit=1] rotated 90 15 run tp @s ~ ~ ~ ~ ~""")

        val anchor = sandbox.world.entities.single { "anchor" in it.tags }
        assertEquals(90.0, anchor.yaw)
        assertEquals(15.0, anchor.pitch)

        sandbox.executeCommand("""rotate @e[tag=anchor,limit=1] 30 5""")
        sandbox.executeCommand("""execute as @e[tag=anchor,limit=1] rotated as @e[tag=anchor,limit=1] run tp @s ~ ~ ~ ~10 ~-2""")

        assertEquals(40.0, anchor.yaw)
        assertEquals(3.0, anchor.pitch)
        val rotateOutput = sandbox.world.outputs.single { it.command == "rotate" }
        val rotatePayload = rotateOutput.payload?.asJsonObject ?: error("missing rotate payload")
        val rotated = rotatePayload.getAsJsonArray("targets")[0].asJsonObject
        assertEquals("1", rotateOutput.text)
        assertEquals(listOf(anchor.uuid), rotateOutput.targets)
        assertEquals(30.0, rotatePayload.get("yaw").asDouble)
        assertEquals(5.0, rotatePayload.get("pitch").asDouble)
        assertEquals(90.0, rotated.get("beforeYaw").asDouble)
        assertEquals(15.0, rotated.get("beforePitch").asDouble)
        assertEquals(30.0, rotated.get("afterYaw").asDouble)
        assertEquals(5.0, rotated.get("afterPitch").asDouble)
    }

    @Test
    fun `execute facing controls relative teleport rotation`() {
        val sandbox = createFunctionSandboxFromString(
            version = "26.2",
            functionText = "",
            functionId = "demo:empty",
        )

        sandbox.executeCommand("""summon minecraft:pig 0 0 0 {Tags:["anchor"]}""")
        sandbox.executeCommand("""summon minecraft:marker 0 0 1 {Tags:["target"]}""")

        val anchor = sandbox.world.entities.single { "anchor" in it.tags }
        sandbox.executeCommand("""execute as @e[tag=anchor,limit=1] positioned 0 0 0 facing 1 0 0 run tp @s ~ ~ ~ ~ ~""")
        assertEquals(-90.0, anchor.yaw, 0.0001)
        assertEquals(0.0, anchor.pitch, 0.0001)

        sandbox.executeCommand("""execute as @e[tag=anchor,limit=1] positioned 0 0 0 facing 0 1 0 run tp @s ~ ~ ~ ~ ~""")
        assertEquals(0.0, anchor.yaw, 0.0001)
        assertEquals(-90.0, anchor.pitch, 0.0001)

        sandbox.executeCommand("""execute as @e[tag=anchor,limit=1] positioned 0 0 0 facing entity @e[tag=target,limit=1] feet run tp @s ~ ~ ~ ~ ~""")
        assertEquals(0.0, anchor.yaw, 0.0001)
        assertEquals(0.0, anchor.pitch, 0.0001)
    }

    @Test
    fun `teleporting to an entity copies destination rotation`() {
        val sandbox = createFunctionSandboxFromString(
            version = "26.2",
            functionText = "",
            functionId = "demo:empty",
        )

        sandbox.executeCommand("""summon minecraft:pig 0 0 0 {Tags:["traveler"]}""")
        sandbox.executeCommand("""execute in minecraft:the_nether run summon minecraft:marker 4 2 1 {Tags:["destination"]}""")
        sandbox.executeCommand("""rotate @e[tag=destination,limit=1] -45 12""")
        sandbox.executeCommand("""tp @e[tag=traveler,limit=1] @e[tag=destination,limit=1]""")

        val traveler = sandbox.world.entities.single { "traveler" in it.tags }
        assertEquals(Position(4.0, 2.0, 1.0), traveler.position)
        assertEquals(ResourceLocation.parse("minecraft:the_nether"), traveler.dimension)
        assertEquals(-45.0, traveler.yaw)
        assertEquals(12.0, traveler.pitch)
    }

    @Test
    fun `teleport supports local coordinates from execution rotation`() {
        val sandbox = createFunctionSandboxFromString(
            version = "26.2",
            functionText = "",
            functionId = "demo:empty",
        )

        sandbox.executeCommand("""summon minecraft:pig 10 64 10 {Tags:["traveler"]}""")
        val traveler = sandbox.world.entities.single { "traveler" in it.tags }

        sandbox.executeCommand("""execute as @e[tag=traveler,limit=1] rotated 0 0 run tp ^ ^ ^2""")
        assertEquals(10.0, traveler.position.x, 0.0001)
        assertEquals(64.0, traveler.position.y, 0.0001)
        assertEquals(12.0, traveler.position.z, 0.0001)

        sandbox.executeCommand("""execute as @e[tag=traveler,limit=1] rotated -90 0 run tp ^ ^ ^3""")
        assertEquals(13.0, traveler.position.x, 0.0001)
        assertEquals(64.0, traveler.position.y, 0.0001)
        assertEquals(12.0, traveler.position.z, 0.0001)

        sandbox.executeCommand("""execute positioned 10 64 10 rotated 0 0 run tp @e[tag=traveler,limit=1] ^1 ^2 ^3""")
        assertEquals(11.0, traveler.position.x, 0.0001)
        assertEquals(66.0, traveler.position.y, 0.0001)
        assertEquals(13.0, traveler.position.z, 0.0001)

        val error = assertFailsWith<SandboxException> {
            sandbox.executeCommand("""tp @e[tag=traveler,limit=1] ^ ~ ^""")
        }
        assertEquals(DiagnosticCode.INPUT_FORMAT, error.code)
    }

    @Test
    fun `execute anchored changes local coordinate base`() {
        val sandbox = createFunctionSandboxFromString(
            version = "26.2",
            functionText = "",
            functionId = "demo:empty",
        )

        sandbox.executeCommand("""summon minecraft:pig 0 64 0 {Tags:["anchor"]}""")
        val anchor = sandbox.world.entities.single { "anchor" in it.tags }

        sandbox.executeCommand("""execute as @e[tag=anchor,limit=1] anchored eyes run tp ^ ^ ^1""")
        assertEquals(Position(0.0, 65.0, 1.0), anchor.position)

        sandbox.executeCommand("""tp @e[tag=anchor,limit=1] 0 64 0""")
        sandbox.executeCommand("""execute as @e[tag=anchor,limit=1] anchored feet run tp ^ ^ ^1""")
        assertEquals(Position(0.0, 64.0, 1.0), anchor.position)

        sandbox.executeCommand("""tp @e[tag=anchor,limit=1] 0 64 0""")
        sandbox.executeCommand("""execute as @e[tag=anchor,limit=1] at @s anchored eyes run setblock ^ ^ ^ minecraft:stone""")
        sandbox.executeCommand("""execute as @e[tag=anchor,limit=1] at @s anchored feet run setblock ^ ^ ^ minecraft:dirt""")
        assertEquals(ResourceLocation.parse("minecraft:stone"), sandbox.world.requireBlock(BlockPos(0, 65, 0)).id)
        assertEquals(ResourceLocation.parse("minecraft:dirt"), sandbox.world.requireBlock(BlockPos(0, 64, 0)).id)

        val error = assertFailsWith<SandboxException> {
            sandbox.executeCommand("""execute anchored head run say invalid""")
        }
        assertEquals(DiagnosticCode.INPUT_FORMAT, error.code)
    }

    @Test
    fun `teleport facing rotates moved entities toward positions and anchors`() {
        val sandbox = createFunctionSandboxFromString(
            version = "26.2",
            functionText = "",
            functionId = "demo:empty",
        )

        sandbox.executeCommand("""summon minecraft:pig 3 0 0 {Tags:["traveler"]}""")
        sandbox.executeCommand("""summon minecraft:marker 0 0 1 {Tags:["look_at"]}""")

        val traveler = sandbox.world.entities.single { "traveler" in it.tags }
        sandbox.executeCommand("""tp @e[tag=traveler,limit=1] 0 0 0 facing 1 0 0""")
        assertEquals(Position(0.0, 0.0, 0.0), traveler.position)
        assertEquals(-90.0, traveler.yaw, 0.0001)
        assertEquals(0.0, traveler.pitch, 0.0001)

        sandbox.executeCommand("""tp @e[tag=traveler,limit=1] 0 0 0 facing entity @e[tag=look_at,limit=1] eyes""")
        assertEquals(0.0, traveler.yaw, 0.0001)
        assertEquals(-45.0, traveler.pitch, 0.0001)

        sandbox.executeCommand("""execute positioned 0 0 0 rotated -90 0 run tp @e[tag=traveler,limit=1] 0 0 0 facing ^ ^ ^1""")
        assertEquals(-90.0, traveler.yaw, 0.0001)
        assertEquals(0.0, traveler.pitch, 0.0001)
    }

    @Test
    fun `block position commands support local coordinates from execution rotation`() {
        val sandbox = createFunctionSandboxFromString(
            version = "26.2",
            functionText = "",
            functionId = "demo:empty",
        )

        sandbox.executeCommand("scoreboard objectives add checks dummy")
        sandbox.executeCommand("""execute positioned 10 64 10 rotated -90 0 run setblock ^ ^ ^1 minecraft:stone""")
        assertEquals(ResourceLocation.parse("minecraft:stone"), sandbox.world.requireBlock(BlockPos(11, 64, 10)).id)

        sandbox.executeCommand("""execute positioned 10 64 10 rotated 0 0 run fill ^ ^ ^1 ^1 ^ ^1 minecraft:dirt""")
        assertEquals(ResourceLocation.parse("minecraft:dirt"), sandbox.world.requireBlock(BlockPos(10, 64, 11)).id)
        assertEquals(ResourceLocation.parse("minecraft:dirt"), sandbox.world.requireBlock(BlockPos(11, 64, 11)).id)
        val fillOutput = sandbox.world.outputs.single { it.command == "fill" }
        val fillPayload = fillOutput.payload?.asJsonObject ?: error("missing fill payload")
        assertEquals("2", fillOutput.text)
        assertEquals(listOf("10 64 11", "11 64 11"), fillOutput.targets)
        assertEquals(2, fillPayload.get("volume").asInt)
        assertEquals(2, fillPayload.get("changed").asInt)
        assertEquals("replace", fillPayload.get("mode").asString)
        assertEquals("minecraft:dirt", fillPayload.getAsJsonObject("block").get("id").asString)
        assertEquals("10 64 11", fillPayload.getAsJsonArray("positions")[0].asString)

        sandbox.executeCommand("""execute positioned 10 64 10 rotated 0 0 if block ^ ^ ^1 minecraft:dirt run scoreboard players add #local checks 1""")
        sandbox.executeCommand("""execute positioned 10 64 10 rotated 0 0 unless block ^ ^ ^1 minecraft:stone run scoreboard players add #local checks 1""")
        assertEquals(2, sandbox.world.getScore("#local", "checks"))
    }

    @Test
    fun `execute blocks condition compares sparse block regions`() {
        val sandbox = createSandbox("26.1.2", listOf(fixturePack()))

        sandbox.executeCommand("scoreboard objectives add checks dummy")
        sandbox.executeCommand("setblock 0 64 0 minecraft:stone")
        sandbox.executeCommand("setblock 1 64 0 minecraft:chest[facing=north]")
        sandbox.executeCommand("setblock 4 64 0 minecraft:stone")
        sandbox.executeCommand("setblock 5 64 0 minecraft:chest[facing=north]")
        sandbox.executeCommand("setblock 6 64 0 minecraft:dirt")
        sandbox.executeCommand("execute if blocks 0 64 0 1 64 0 4 64 0 all run scoreboard players add #pass checks 1")
        sandbox.executeCommand("execute if blocks 0 64 0 2 64 0 4 64 0 masked run scoreboard players add #pass checks 1")
        sandbox.executeCommand("execute unless blocks 0 64 0 2 64 0 4 64 0 all run scoreboard players add #pass checks 1")
        sandbox.executeCommand("execute if blocks 0 64 0 2 64 0 4 64 0 all run scoreboard players add #fail checks 1")

        assertEquals(3, sandbox.world.getScore("#pass", "checks"))
        assertEquals(0, sandbox.world.getScore("#fail", "checks"))
    }

    @Test
    fun `execute function condition uses function return values and tags`() {
        val pack = writeExecuteFunctionPack(Files.createTempDirectory("dps-execute-function-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))

        sandbox.executeCommand("scoreboard objectives add checks dummy")
        sandbox.executeCommand("execute if function demo:pass run scoreboard players add #pass checks 1")
        sandbox.executeCommand("execute unless function demo:fail run scoreboard players add #pass checks 1")
        sandbox.executeCommand("execute if function #demo:group run scoreboard players add #pass checks 1")
        sandbox.executeCommand("execute if function demo:fail run scoreboard players add #fail checks 1")
        sandbox.executeCommand("execute unless function demo:pass run scoreboard players add #fail checks 1")
        sandbox.executeCommand("execute store success score #function_pass checks run function demo:pass")
        sandbox.executeCommand("execute store success score #function_fail checks run function demo:fail")
        sandbox.executeCommand("execute store result score #return checks run function demo:return_run")
        sandbox.executeCommand("execute store result score #explicit checks run function demo:return_after_output")

        assertEquals(3, sandbox.world.getScore("#pass", "checks"))
        assertEquals(0, sandbox.world.getScore("#fail", "checks"))
        assertEquals(1, sandbox.world.getScore("#function_pass", "checks"))
        assertEquals(0, sandbox.world.getScore("#function_fail", "checks"))
        assertEquals(44, sandbox.world.getScore("#condition", "checks"))
        assertEquals(4, sandbox.world.getScore("#return", "checks"))
        assertEquals(4, sandbox.world.getScore("#explicit", "checks"))
    }

    private fun fixturePack(): Path =
        Path.of("src/test/resources/packs/counter")

    private fun writeLootPack(root: Path): Path {
        Files.writeString(
            root.resolve("pack.mcmeta").also { Files.createDirectories(it.parent) },
            """
            {
              "pack": {
                "pack_format": 107.1,
                "description": "command expansion test"
              }
            }
            """.trimIndent(),
        )
        val lootRoot = root.resolve("data").resolve("demo").resolve("loot_table")
        Files.createDirectories(lootRoot)
        Files.writeString(
            lootRoot.resolve("gift.json"),
            """
            {
              "type": "minecraft:command",
              "pools": [
                {
                  "rolls": 1,
                  "entries": [
                    {
                      "type": "minecraft:item",
                      "name": "minecraft:diamond",
                      "functions": [
                        {
                          "function": "minecraft:set_item",
                          "item": "minecraft:emerald"
                        },
                        {
                          "function": "minecraft:set_name",
                          "name": { "text": "Gift" }
                        },
                        {
                          "function": "minecraft:set_lore",
                          "lore": [
                            { "text": "from loot" }
                          ]
                        }
                      ]
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        return root
    }

    private fun writeLootSourcePack(root: Path): Path {
        Files.writeString(
            root.resolve("pack.mcmeta").also { Files.createDirectories(it.parent) },
            """
            {
              "pack": {
                "pack_format": 107.1,
                "description": "loot source test"
              }
            }
            """.trimIndent(),
        )
        val lootRoot = root.resolve("data").resolve("demo").resolve("loot_table")
        Files.createDirectories(lootRoot)
        Files.writeString(
            lootRoot.resolve("fish.json"),
            """
            {
              "pools": [
                {
                  "rolls": 1,
                  "entries": [
                    {
                      "type": "minecraft:item",
                      "name": "minecraft:diamond"
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        Files.writeString(
            lootRoot.resolve("entity_context.json"),
            """
            {
              "type": "minecraft:entity",
              "pools": [
                {
                  "rolls": 1,
                  "conditions": [
                    {
                      "condition": "minecraft:entity_properties",
                      "entity": "this",
                      "predicate": {
                        "type": "minecraft:zombie"
                      }
                    }
                  ],
                  "entries": [
                    {
                      "type": "minecraft:item",
                      "name": "minecraft:gold_ingot"
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        Files.writeString(
            lootRoot.resolve("copy_name.json"),
            """
            {
              "type": "minecraft:entity",
              "pools": [
                {
                  "rolls": 1,
                  "entries": [
                    {
                      "type": "minecraft:item",
                      "name": "minecraft:name_tag",
                      "functions": [
                        {
                          "function": "minecraft:copy_name",
                          "source": "this"
                        }
                      ]
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        Files.writeString(
            lootRoot.resolve("block_context.json"),
            """
            {
              "type": "minecraft:block",
              "pools": [
                {
                  "rolls": 1,
                  "conditions": [
                    {
                      "condition": "minecraft:block_state_property",
                      "block": "minecraft:stone"
                    }
                  ],
                  "entries": [
                    {
                      "type": "minecraft:item",
                      "name": "minecraft:cobblestone"
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        Files.writeString(
            lootRoot.resolve("copy_components.json"),
            """
            {
              "type": "minecraft:block",
              "pools": [
                {
                  "rolls": 1,
                  "conditions": [
                    {
                      "condition": "minecraft:block_state_property",
                      "block": "minecraft:stone"
                    }
                  ],
                  "entries": [
                    {
                      "type": "minecraft:item",
                      "name": "minecraft:iron_nugget",
                      "functions": [
                        {
                          "function": "minecraft:copy_components",
                          "source": "tool",
                          "include": [
                            "minecraft:damage",
                            "demo:copied",
                            "demo:skip"
                          ],
                          "exclude": [
                            "demo:skip"
                          ]
                        }
                      ]
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        Files.writeString(
            lootRoot.resolve("apply_bonus.json"),
            """
            {
              "type": "minecraft:block",
              "pools": [
                {
                  "rolls": 1,
                  "conditions": [
                    {
                      "condition": "minecraft:block_state_property",
                      "block": "minecraft:stone"
                    }
                  ],
                  "entries": [
                    {
                      "type": "minecraft:item",
                      "name": "minecraft:raw_gold",
                      "functions": [
                        {
                          "function": "minecraft:set_count",
                          "count": 1
                        },
                        {
                          "function": "minecraft:apply_bonus",
                          "enchantment": "minecraft:fortune",
                          "formula": "minecraft:binomial_with_bonus_count",
                          "parameters": {
                            "extra": 1,
                            "probability": 1.0
                          }
                        }
                      ]
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        Files.writeString(
            lootRoot.resolve("equipment_context.json"),
            """
            {
              "type": "minecraft:equipment",
              "pools": [
                {
                  "rolls": 1,
                  "conditions": [
                    {
                      "condition": "minecraft:match_tool",
                      "predicate": {
                        "items": "minecraft:stick"
                      }
                    }
                  ],
                  "entries": [
                    {
                      "type": "minecraft:item",
                      "name": "minecraft:apple"
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        Files.writeString(
            lootRoot.resolve("enchanted.json"),
            """
            {
              "type": "minecraft:command",
              "pools": [
                {
                  "rolls": 1,
                  "entries": [
                    {
                      "type": "minecraft:item",
                      "name": "minecraft:experience_bottle",
                      "functions": [
                        {
                          "function": "minecraft:enchant_randomly",
                          "options": [
                            "minecraft:sharpness"
                          ]
                        },
                        {
                          "function": "minecraft:enchant_with_levels",
                          "options": [
                            "minecraft:unbreaking"
                          ],
                          "levels": 3
                        }
                      ]
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        Files.writeString(
            lootRoot.resolve("tag_items.json"),
            """
            {
              "type": "minecraft:command",
              "pools": [
                {
                  "rolls": 1,
                  "entries": [
                    {
                      "type": "minecraft:tag",
                      "name": "demo:ore_drops",
                      "expand": false,
                      "functions": [
                        {
                          "function": "minecraft:set_count",
                          "count": 2
                        }
                      ]
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        Files.writeString(
            lootRoot.resolve("tag_pick.json"),
            """
            {
              "type": "minecraft:command",
              "pools": [
                {
                  "rolls": 1,
                  "entries": [
                    {
                      "type": "minecraft:tag",
                      "name": "demo:ore_drops",
                      "expand": true,
                      "functions": [
                        {
                          "function": "minecraft:set_count",
                          "count": 2
                        }
                      ]
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        val itemTagRoot = root.resolve("data").resolve("demo").resolve("tags").resolve("item")
        Files.createDirectories(itemTagRoot)
        Files.writeString(
            itemTagRoot.resolve("ore_drops.json"),
            """
            {
              "values": [
                "minecraft:raw_gold",
                "#demo:bonus_drops",
                { "id": "#demo:missing_optional", "required": false }
              ]
            }
            """.trimIndent(),
        )
        Files.writeString(
            itemTagRoot.resolve("bonus_drops.json"),
            """
            {
              "values": [
                "minecraft:emerald"
              ]
            }
            """.trimIndent(),
        )
        return root
    }

    private fun writeExecuteFunctionPack(root: Path): Path {
        Files.writeString(
            root.resolve("pack.mcmeta").also { Files.createDirectories(it.parent) },
            """
            {
              "pack": {
                "pack_format": 107.1,
                "description": "execute function condition test"
              }
            }
            """.trimIndent(),
        )
        val functionRoot = root.resolve("data").resolve("demo").resolve("function")
        Files.createDirectories(functionRoot)
        Files.writeString(
            functionRoot.resolve("pass.mcfunction"),
            """
            scoreboard players add #condition checks 1
            return 1
            scoreboard players add #condition checks 100
            """.trimIndent(),
        )
        Files.writeString(
            functionRoot.resolve("fail.mcfunction"),
            """
            scoreboard players add #condition checks 10
            return fail
            scoreboard players add #condition checks 100
            """.trimIndent(),
        )
        Files.writeString(
            functionRoot.resolve("return_run.mcfunction"),
            """
            return run random value 4..4
            """.trimIndent(),
        )
        Files.writeString(
            functionRoot.resolve("return_after_output.mcfunction"),
            """
            random value 9..9
            return 4
            """.trimIndent(),
        )
        val tagRoot = root.resolve("data").resolve("demo").resolve("tags").resolve("function")
        Files.createDirectories(tagRoot)
        Files.writeString(
            tagRoot.resolve("group.json"),
            """
            {
              "values": [
                "demo:pass",
                "demo:fail",
                { "id": "demo:missing", "required": false }
              ]
            }
            """.trimIndent(),
        )
        return root
    }

    private fun writeItemModifierPack(root: Path): Path {
        Files.writeString(
            root.resolve("pack.mcmeta").also { Files.createDirectories(it.parent) },
            """
            {
              "pack": {
                "pack_format": 107.1,
                "description": "item modifier test"
              }
            }
            """.trimIndent(),
        )
        val modifierRoot = root.resolve("data").resolve("demo").resolve("item_modifier")
        Files.createDirectories(modifierRoot)
        Files.writeString(
            modifierRoot.resolve("change_item.json"),
            """
            {
              "function": "minecraft:set_item",
              "item": "minecraft:carrot"
            }
            """.trimIndent(),
        )
        Files.writeString(
            modifierRoot.resolve("discard.json"),
            """
            {
              "function": "minecraft:discard"
            }
            """.trimIndent(),
        )
        Files.writeString(
            modifierRoot.resolve("filtered_pass.json"),
            """
            {
              "function": "minecraft:set_components",
              "components": {
                "demo:filtered": "pass"
              }
            }
            """.trimIndent(),
        )
        Files.writeString(
            modifierRoot.resolve("shared.json"),
            """
            {
              "function": "minecraft:set_components",
              "components": {
                "demo:referenced": "shared"
              }
            }
            """.trimIndent(),
        )
        Files.writeString(
            modifierRoot.resolve("copy_selected.json"),
            """
            {
              "function": "minecraft:copy_components",
              "source": "this",
              "include": [
                "demo:source",
                "minecraft:damage",
                "demo:skip"
              ],
              "exclude": [
                "demo:skip"
              ]
            }
            """.trimIndent(),
        )
        Files.writeString(
            modifierRoot.resolve("copy_nbt.json"),
            """
            {
              "function": "minecraft:copy_nbt",
              "source": "tool",
              "ops": [
                {
                  "source": "source.level",
                  "target": "copied.level"
                }
              ]
            }
            """.trimIndent(),
        )
        Files.writeString(
            modifierRoot.resolve("mark.json"),
            """
            [
              {
                "function": "minecraft:set_components",
                "components": {
                  "demo:tag": "tagged"
                }
              },
              {
                "function": "minecraft:set_custom_data",
                "tag": {
                  "marked": true
                }
              },
              {
                "function": "minecraft:set_count",
                "count": 8
              },
              {
                "function": "minecraft:limit_count",
                "limit": {
                  "max": 4
                }
              },
              {
                "function": "minecraft:set_damage",
                "damage": 3
              },
              {
                "function": "minecraft:set_name",
                "name": { "text": "Marked Stick" }
              },
              {
                "function": "minecraft:set_lore",
                "lore": [
                  { "text": "debuggable" }
                ]
              },
              {
                "function": "minecraft:sequence",
                "functions": [
                  {
                    "function": "minecraft:set_components",
                    "components": {
                      "demo:sequence": "applied"
                    }
                  }
                ]
              },
              {
                "function": "minecraft:filtered",
                "item_filter": {
                  "items": "minecraft:stick"
                },
                "on_pass": "demo:filtered_pass",
                "on_fail": [
                  {
                    "function": "minecraft:set_components",
                    "components": {
                      "demo:filtered_fail": "fail"
                    }
                  }
                ]
              },
              {
                "function": "minecraft:reference",
                "name": "demo:shared"
              }
            ]
            """.trimIndent(),
        )
        return root
    }

    private fun writeEffectPredicatePack(root: Path): Path {
        Files.writeString(
            root.resolve("pack.mcmeta").also { Files.createDirectories(it.parent) },
            """
            {
              "pack": {
                "pack_format": 107.1,
                "description": "effect predicate test"
              }
            }
            """.trimIndent(),
        )
        val predicateRoot = root.resolve("data").resolve("demo").resolve("predicate")
        Files.createDirectories(predicateRoot)
        Files.writeString(
            predicateRoot.resolve("zombie_speed_effect.json"),
            """
            {
              "condition": "minecraft:entity_properties",
              "entity": "this",
              "predicate": {
                "type": "minecraft:zombie",
                "effects": {
                  "minecraft:speed": {
                    "amplifier": 2,
                    "duration": {
                      "min": 140,
                      "max": 140
                    },
                    "hide_particles": true
                  }
                }
              }
            }
            """.trimIndent(),
        )
        Files.writeString(
            predicateRoot.resolve("zombie_no_strength.json"),
            """
            {
              "condition": "minecraft:entity_properties",
              "entity": "this",
              "predicate": {
                "type": "minecraft:zombie",
                "effects": {
                  "minecraft:strength": false
                }
              }
            }
            """.trimIndent(),
        )
        return root
    }

    private fun writeEquipmentPredicateEntries(root: Path) {
        val predicateRoot = root.resolve("data").resolve("demo").resolve("predicate")
        Files.createDirectories(predicateRoot)
        Files.writeString(
            predicateRoot.resolve("zombie_mainhand_stick.json"),
            """
            {
              "condition": "minecraft:entity_properties",
              "entity": "this",
              "predicate": {
                "type": "minecraft:zombie",
                "equipment": {
                  "mainhand": {
                    "items": "minecraft:stick",
                    "count": 4,
                    "nbt": "{marked:true}",
                    "enchantments": {
                      "minecraft:sharpness": 3
                    }
                  }
                }
              }
            }
            """.trimIndent(),
        )
        Files.writeString(
            predicateRoot.resolve("zombie_offhand_stick.json"),
            """
            {
              "condition": "minecraft:entity_properties",
              "entity": "this",
              "predicate": {
                "type": "minecraft:zombie",
                "equipment": {
                  "offhand": {
                    "items": "minecraft:stick"
                  }
                }
              }
            }
            """.trimIndent(),
        )
        Files.writeString(
            predicateRoot.resolve("zombie_helmet_nbt.json"),
            """
            {
              "condition": "minecraft:entity_properties",
              "entity": "this",
              "predicate": {
                "type": "minecraft:zombie",
                "nbt": {
                  "ArmorItems": [
                    {
                      "id": "minecraft:iron_helmet",
                      "count": 1,
                      "components": {}
                    }
                  ]
                }
              }
            }
            """.trimIndent(),
        )
    }

    private fun writePredicatePack(root: Path): Path {
        Files.writeString(
            root.resolve("pack.mcmeta").also { Files.createDirectories(it.parent) },
            """
            {
              "pack": {
                "pack_format": 107.1,
                "description": "execute condition test"
              }
            }
            """.trimIndent(),
        )
        val predicateRoot = root.resolve("data").resolve("demo").resolve("predicate")
        Files.createDirectories(predicateRoot)
        Files.writeString(
            predicateRoot.resolve("is_player.json"),
            """
            {
              "condition": "minecraft:entity_properties",
              "entity": "this",
              "predicate": {
                "type": "minecraft:player"
              }
            }
            """.trimIndent(),
        )
        Files.writeString(
            predicateRoot.resolve("in_nether.json"),
            """
            {
              "condition": "minecraft:location_check",
              "predicate": {
                "dimension": "minecraft:the_nether"
              }
            }
            """.trimIndent(),
        )
        Files.writeString(predicateRoot.resolve("false.json"), "false")
        return root
    }
}
