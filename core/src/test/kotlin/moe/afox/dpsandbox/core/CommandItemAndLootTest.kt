package moe.afox.dpsandbox.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CommandItemAndLootTest {
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
        sandbox.executeCommand(
            "execute store result score #max attr run attribute @e[type=minecraft:zombie,limit=1] minecraft:max_health base get 0.25",
        )
        sandbox.executeCommand(
            "execute store result score #bonus attr run attribute @e[type=minecraft:zombie,limit=1] minecraft:max_health modifier value get demo:bonus",
        )
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
        val modifierOutput =
            sandbox.world.outputs.first {
                it.command == "attribute modifier value get" &&
                    it.payload
                        ?.asJsonObject
                        ?.get("scale")
                        ?.asDouble == 2.0
            }
        val modifierPayload = modifierOutput.payload?.asJsonObject ?: error("missing attribute modifier payload")
        assertEquals("10.0", modifierOutput.text)
        assertEquals("demo:bonus", modifierPayload.get("modifier").asString)
        assertEquals("add_value", modifierPayload.get("operation").asString)
        assertEquals(5.0, modifierPayload.get("rawValue").asDouble)
        assertEquals(10, sandbox.world.getScore("#max", "attr"))
        assertEquals(5, sandbox.world.getScore("#bonus", "attr"))
        assertTrue(zombie.attributeModifiers[ResourceLocation.parse("minecraft:max_health")].isNullOrEmpty())
        assertEquals(
            "40.0",
            sandbox.world.outputs
                .last { it.command == "attribute get" }
                .text,
        )
        val emerald =
            sandbox.world
                .requirePlayer("Steve")
                .inventory
                .first { it.id == ResourceLocation.parse("minecraft:emerald") }
        assertEquals(
            "Gift",
            emerald.components
                .getAsJsonObject("minecraft:custom_name")
                .get("text")
                .asString,
        )
        assertEquals(
            "from loot",
            emerald.components
                .getAsJsonArray("minecraft:lore")[0]
                .asJsonObject
                .get("text")
                .asString,
        )

        val error =
            assertFailsWith<SandboxException> {
                sandbox.executeCommand("attribute @e[type=minecraft:zombie,limit=1] minecraft:max_health modifier add demo:bad 1 divide")
            }
        assertEquals(DiagnosticCode.INPUT_FORMAT, error.code)

        sandbox.executeCommand(
            "attribute @e[type=minecraft:zombie,limit=1] minecraft:max_health modifier add demo:snapshot 0.1 add_multiplied_base",
        )
        val modifierJson =
            zombie
                .toJson(sandbox.profile)
                .asJsonObject
                .getAsJsonObject("attributeModifiers")
                .getAsJsonArray("minecraft:max_health")[0]
                .asJsonObject
        assertEquals("demo:snapshot", modifierJson.get("id").asString)
        assertEquals("add_multiplied_base", modifierJson.get("operation").asString)
        val attributeNbt =
            zombie
                .fullNbt(sandbox.profile)
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

        sandbox.executeCommand("setblock 0 64 0 minecraft:stone[variant=smooth]")
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
        sandbox.executeCommand(
            "loot give Steve block demo:copy_components 0 64 0 minecraft:diamond_pickaxe[minecraft:damage=7,demo:copied=true,demo:skip=true]",
        )
        sandbox.executeCommand(
            "loot give Steve block demo:apply_bonus 0 64 0 minecraft:diamond_pickaxe[minecraft:enchantments={\"minecraft:fortune\":2}]",
        )
        sandbox.executeCommand("loot give Steve equipment demo:equipment_context @e[type=minecraft:zombie,limit=1] weapon.mainhand")
        sandbox.executeCommand("loot give Steve loot demo:enchanted")
        sandbox.executeCommand("loot give Steve loot demo:tag_items")
        sandbox.executeCommand("loot give Steve loot demo:tag_pick")
        sandbox.executeCommand("loot give Steve loot demo:reference_function")
        sandbox.executeCommand("loot replace entity @e[type=minecraft:zombie,limit=1] weapon.offhand loot demo:fish")

        val zombie = sandbox.world.entities.first { it.type == ResourceLocation.parse("minecraft:zombie") }
        val inventoryIds =
            sandbox.world
                .requirePlayer("Steve")
                .inventory
                .map { it.id }
                .toSet()
        assertTrue(ResourceLocation.parse("minecraft:diamond") in inventoryIds)
        assertTrue(ResourceLocation.parse("minecraft:stone") in inventoryIds)
        assertTrue(ResourceLocation.parse("minecraft:gold_ingot") in inventoryIds)
        assertTrue(ResourceLocation.parse("minecraft:cobblestone") in inventoryIds)
        assertTrue(ResourceLocation.parse("minecraft:apple") in inventoryIds)
        val copied =
            sandbox.world
                .requirePlayer("Steve")
                .inventory
                .first { it.id == ResourceLocation.parse("minecraft:iron_nugget") }
        assertEquals(7, copied.components.get("minecraft:damage").asInt)
        assertEquals(true, copied.components.get("demo:copied").asBoolean)
        assertTrue(!copied.components.has("demo:skip"))
        val bonus =
            sandbox.world
                .requirePlayer("Steve")
                .inventory
                .first { it.id == ResourceLocation.parse("minecraft:raw_gold") }
        assertEquals(4, bonus.count)
        val rawGoldCounts =
            sandbox.world
                .requirePlayer("Steve")
                .inventory
                .filter { it.id == ResourceLocation.parse("minecraft:raw_gold") }
                .map { it.count }
        assertTrue(2 in rawGoldCounts)
        val emerald =
            sandbox.world
                .requirePlayer("Steve")
                .inventory
                .first { it.id == ResourceLocation.parse("minecraft:emerald") }
        assertEquals(2, emerald.count)
        val nameTag =
            sandbox.world
                .requirePlayer("Steve")
                .inventory
                .first { it.id == ResourceLocation.parse("minecraft:name_tag") }
        assertEquals(
            "Named Zombie",
            nameTag.components
                .getAsJsonObject("minecraft:custom_name")
                .get("text")
                .asString,
        )
        val enchanted =
            sandbox.world.requirePlayer("Steve").inventory.first {
                it.id ==
                    ResourceLocation.parse("minecraft:experience_bottle")
            }
        val enchantments = enchanted.components.getAsJsonObject("minecraft:enchantments")
        assertEquals(1, enchantments.get("minecraft:sharpness").asInt)
        assertEquals(3, enchantments.get("minecraft:unbreaking").asInt)
        val referencedLoot =
            sandbox.world.requirePlayer("Steve").inventory.first {
                it.id ==
                    ResourceLocation.parse("minecraft:lapis_lazuli")
            }
        assertEquals(3, referencedLoot.count)
        assertEquals("applied", referencedLoot.components.get("demo:referenced_loot").asString)
        assertEquals(ResourceLocation.parse("minecraft:diamond"), zombie.equipment[EquipmentSlots.OFFHAND]?.id)
        val spawnedItem =
            sandbox.world.entities.first {
                it.type == ResourceLocation.parse("minecraft:item") &&
                    it.nbt
                        .getAsJsonObject("Item")
                        ?.get("id")
                        ?.asString == "minecraft:stone"
            }
        assertEquals(ResourceLocation.parse("minecraft:the_nether"), spawnedItem.dimension)

        val lootGiveOutputs = sandbox.world.outputs.filter { it.command == "loot give" }
        assertEquals(13, lootGiveOutputs.size)
        assertEquals(
            "players",
            lootGiveOutputs
                .first()
                .payload
                ?.asJsonObject
                ?.get("targetKind")
                ?.asString,
        )
        assertEquals(
            "minecraft:diamond",
            lootGiveOutputs
                .first()
                .payload
                ?.asJsonObject
                ?.getAsJsonArray("items")
                ?.get(0)
                ?.asJsonObject
                ?.get("id")
                ?.asString,
        )
        val copiedOutputItem =
            lootGiveOutputs
                .first {
                    it.payload?.asJsonObject?.getAsJsonArray("items")?.any { item ->
                        item.asJsonObject.get("id").asString == "minecraft:iron_nugget"
                    } == true
                }.payload
                ?.asJsonObject
                ?.getAsJsonArray("items")
                ?.get(0)
                ?.asJsonObject ?: error("missing copied loot output")
        assertEquals(7, copiedOutputItem.getAsJsonObject("components").get("minecraft:damage").asInt)
        val bonusOutputItem =
            lootGiveOutputs
                .first {
                    it.payload?.asJsonObject?.getAsJsonArray("items")?.any { item ->
                        item.asJsonObject.get("id").asString == "minecraft:raw_gold"
                    } == true
                }.payload
                ?.asJsonObject
                ?.getAsJsonArray("items")
                ?.get(0)
                ?.asJsonObject ?: error("missing apply_bonus loot output")
        assertEquals(4, bonusOutputItem.get("count").asInt)
        val nameTagOutputItem =
            lootGiveOutputs
                .first {
                    it.payload?.asJsonObject?.getAsJsonArray("items")?.any { item ->
                        item.asJsonObject.get("id").asString == "minecraft:name_tag"
                    } == true
                }.payload
                ?.asJsonObject
                ?.getAsJsonArray("items")
                ?.get(0)
                ?.asJsonObject ?: error("missing copy_name loot output")
        assertEquals(
            "Named Zombie",
            nameTagOutputItem
                .getAsJsonObject("components")
                .getAsJsonObject("minecraft:custom_name")
                .get("text")
                .asString,
        )
        val enchantedOutputItem =
            lootGiveOutputs
                .first {
                    it.payload?.asJsonObject?.getAsJsonArray("items")?.any { item ->
                        item.asJsonObject.get("id").asString == "minecraft:experience_bottle"
                    } == true
                }.payload
                ?.asJsonObject
                ?.getAsJsonArray("items")
                ?.get(0)
                ?.asJsonObject ?: error("missing enchanted loot output")
        assertEquals(
            3,
            enchantedOutputItem
                .getAsJsonObject("components")
                .getAsJsonObject("minecraft:enchantments")
                .get("minecraft:unbreaking")
                .asInt,
        )
        val tagOutput =
            lootGiveOutputs
                .first {
                    val items = it.payload?.asJsonObject?.getAsJsonArray("items")
                    items?.size() == 2 &&
                        items.any { item ->
                            item.asJsonObject.get("id").asString == "minecraft:emerald"
                        }
                }.payload
                ?.asJsonObject ?: error("missing tag loot output")
        assertEquals(2, tagOutput.getAsJsonArray("items").size())
        assertEquals(4, tagOutput.get("totalCount").asInt)
        val tagPickOutput =
            lootGiveOutputs
                .first {
                    val items = it.payload?.asJsonObject?.getAsJsonArray("items")
                    items?.size() == 1 &&
                        items[0].asJsonObject.get("id").asString in setOf("minecraft:raw_gold", "minecraft:emerald") &&
                        items[0].asJsonObject.get("count")?.asInt == 2
                }.payload
                ?.asJsonObject ?: error("missing expanded tag loot output")
        assertEquals(1, tagPickOutput.getAsJsonArray("items").size())
        val tagPickItem = tagPickOutput.getAsJsonArray("items")[0].asJsonObject
        assertTrue(
            tagPickItem.get("id").asString in setOf("minecraft:raw_gold", "minecraft:emerald"),
            tagPickItem.toString(),
        )
        assertEquals(2, tagPickOutput.get("totalCount").asInt)
        val referencedOutputItem =
            lootGiveOutputs
                .first {
                    it.payload?.asJsonObject?.getAsJsonArray("items")?.any { item ->
                        item.asJsonObject.get("id").asString == "minecraft:lapis_lazuli"
                    } == true
                }.payload
                ?.asJsonObject
                ?.getAsJsonArray("items")
                ?.get(0)
                ?.asJsonObject ?: error("missing referenced loot output")
        assertEquals(3, referencedOutputItem.get("count").asInt)
        assertEquals("applied", referencedOutputItem.getAsJsonObject("components").get("demo:referenced_loot").asString)
        val lootSpawnOutput = sandbox.world.outputs.single { it.command == "loot spawn" }
        assertEquals(
            "minecraft:the_nether",
            lootSpawnOutput.payload
                ?.asJsonObject
                ?.get("dimension")
                ?.asString,
        )
        val lootReplaceOutput = sandbox.world.outputs.single { it.command == "loot replace" }
        assertEquals(
            "weapon.offhand",
            lootReplaceOutput.payload
                ?.asJsonObject
                ?.get("slot")
                ?.asString,
        )
        assertEquals(
            "minecraft:diamond",
            lootReplaceOutput.payload
                ?.asJsonObject
                ?.getAsJsonArray("items")
                ?.get(0)
                ?.asJsonObject
                ?.get("id")
                ?.asString,
        )
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
        assertEquals(
            "Marked Stick",
            item.components
                .getAsJsonObject("minecraft:custom_name")
                .get("text")
                .asString,
        )
        assertEquals(
            "debuggable",
            item.components
                .getAsJsonArray("minecraft:lore")[0]
                .asJsonObject
                .get("text")
                .asString,
        )
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
        assertEquals(
            2,
            copiedNbt.nbt
                .getAsJsonObject("copied")
                .get("level")
                .asInt,
        )

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
        assertEquals(
            "minecraft:stick",
            firstModifyPayload
                .getAsJsonArray("items")[0]
                .asJsonObject
                .getAsJsonObject("item")
                .get("id")
                .asString,
        )
    }

    @Test
    fun `item replace and modify support block slots`() {
        val pack = writeItemModifierPack(Files.createTempDirectory("dps-block-item-modifier-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))

        sandbox.executeCommand("setblock 0 64 0 minecraft:chest")
        sandbox.executeCommand("item replace block 0 64 0 container.0 with minecraft:stick 1")
        sandbox.executeCommand("item modify block 0 64 0 container.0 demo:mark")

        val items =
            sandbox.world
                .requireBlock(BlockPos(0, 64, 0))
                .fullNbt(BlockPos(0, 64, 0), sandbox.profile)
                .getAsJsonArray("Items")
        val item = items.single { it.asJsonObject.get("Slot").asInt == 0 }.asJsonObject
        assertEquals("minecraft:stick", item.get("id").asString)
        assertEquals(4, item.get("count").asInt)
        assertEquals("tagged", item.getAsJsonObject("components").get("demo:tag").asString)
        assertEquals(
            true,
            item
                .getAsJsonObject("components")
                .getAsJsonObject("minecraft:custom_data")
                .get("marked")
                .asBoolean,
        )

        val modifyOutput = sandbox.world.outputs.single { it.command == "item modify" }
        val payload = modifyOutput.payload?.asJsonObject ?: error("missing item modify payload")
        assertEquals("block", payload.get("targetKind").asString)
        assertEquals("container.0", payload.get("slot").asString)
        assertEquals("demo:mark", payload.get("modifier").asString)
        assertEquals(
            "0 64 0",
            payload
                .getAsJsonArray("items")[0]
                .asJsonObject
                .get("target")
                .asString,
        )
    }

    @Test
    fun `item replace copies from entity and block slots`() {
        val sandbox =
            createFunctionSandboxFromString(
                version = "26.2",
                functionText = "",
                functionId = "demo:empty",
            )

        sandbox.executeCommand("setblock 0 64 0 minecraft:chest")
        sandbox.executeCommand("item replace entity Steve hotbar.0 with minecraft:diamond 3")
        sandbox.executeCommand("item replace block 0 64 0 container.0 from entity Steve hotbar.0")
        sandbox.executeCommand("item replace entity Steve hotbar.1 from block 0 64 0 container.0")

        val copiedToBlock =
            sandbox.world
                .requireBlock(BlockPos(0, 64, 0))
                .fullNbt(BlockPos(0, 64, 0), sandbox.profile)
                .getAsJsonArray("Items")
                .single { it.asJsonObject.get("Slot").asInt == 0 }
                .asJsonObject
        assertEquals("minecraft:diamond", copiedToBlock.get("id").asString)
        assertEquals(3, copiedToBlock.get("count").asInt)

        val copiedToPlayer = sandbox.world.requirePlayer("Steve").inventory[1]
        assertEquals(ResourceLocation.parse("minecraft:diamond"), copiedToPlayer.id)
        assertEquals(3, copiedToPlayer.count)

        sandbox.executeCommand("item replace block 0 64 0 container.0 from entity Steve hotbar.8")
        val afterClear =
            sandbox.world
                .requireBlock(BlockPos(0, 64, 0))
                .fullNbt(BlockPos(0, 64, 0), sandbox.profile)
                .getAsJsonArray("Items")
        assertTrue(afterClear.none { it.asJsonObject.get("Slot").asInt == 0 })
    }

    @Test
    fun `item inputs support command nbt and components`() {
        val sandbox =
            createFunctionSandboxFromString(
                version = "26.2",
                functionText = "",
                functionId = "demo:empty",
            )

        sandbox.executeCommand("give Steve minecraft:stick{marked: true, label: 'old value'} 2")
        sandbox.executeCommand(
            "item replace entity Steve hotbar.1 with minecraft:diamond[custom_data={source: component, nested: {ready: true}}, damage=3] 4",
        )
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
        assertEquals(
            "minecraft:stick",
            giveOutput.payload
                ?.asJsonObject
                ?.getAsJsonObject("item")
                ?.get("id")
                ?.asString,
        )
        assertEquals(
            "old value",
            giveOutput.payload
                ?.asJsonObject
                ?.getAsJsonObject("item")
                ?.getAsJsonObject("nbt")
                ?.get("label")
                ?.asString,
        )

        val replaceOutputs = sandbox.world.outputs.filter { it.command == "item replace" }
        assertEquals(3, replaceOutputs.size)
        assertEquals(
            "entity",
            replaceOutputs[0]
                .payload
                ?.asJsonObject
                ?.get("targetKind")
                ?.asString,
        )
        assertEquals(
            "hotbar.1",
            replaceOutputs[0]
                .payload
                ?.asJsonObject
                ?.get("slot")
                ?.asString,
        )
        assertEquals(
            "minecraft:diamond",
            replaceOutputs[0]
                .payload
                ?.asJsonObject
                ?.getAsJsonObject("item")
                ?.get("id")
                ?.asString,
        )
        assertEquals(
            "block",
            replaceOutputs[1]
                .payload
                ?.asJsonObject
                ?.get("targetKind")
                ?.asString,
        )
        assertEquals(
            "container.0",
            replaceOutputs[1]
                .payload
                ?.asJsonObject
                ?.get("slot")
                ?.asString,
        )

        val componentItem = player.inventory[1]
        assertEquals(ResourceLocation.parse("minecraft:diamond"), componentItem.id)
        assertEquals(4, componentItem.count)
        assertEquals(3, componentItem.components.get("minecraft:damage").asInt)
        assertEquals("component", componentItem.nbt.get("source").asString)
        assertEquals(
            true,
            componentItem.nbt
                .getAsJsonObject("nested")
                .get("ready")
                .asBoolean,
        )

        val blockItem =
            sandbox.world
                .requireBlock(BlockPos(0, 64, 0))
                .fullNbt(BlockPos(0, 64, 0), sandbox.profile)
                .getAsJsonArray("Items")
                .single { it.asJsonObject.get("Slot").asInt == 0 }
                .asJsonObject
        assertEquals("minecraft:apple", blockItem.get("id").asString)
        assertEquals(5, blockItem.get("count").asInt)
        assertEquals(
            true,
            blockItem
                .getAsJsonObject("components")
                .getAsJsonObject("minecraft:custom_data")
                .get("boxed")
                .asBoolean,
        )

        val zombie = sandbox.world.entities.single { it.type == ResourceLocation.parse("minecraft:zombie") }
        assertEquals(
            true,
            zombie.equipment[EquipmentSlots.MAINHAND]
                ?.nbt
                ?.get("marked")
                ?.asBoolean,
        )
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

        val snapshotEnderItems =
            sandbox
                .snapshotJson()
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

        val inventoryNbt =
            player
                .fullNbt(sandbox.profile)
                .getAsJsonArray("Inventory")
        val selectedItemNbt = inventoryNbt.single { it.asJsonObject.get("Slot").asInt == 4 }.asJsonObject
        assertEquals("minecraft:diamond", selectedItemNbt.get("id").asString)
        assertEquals(
            "minecraft:diamond",
            player
                .fullNbt(sandbox.profile)
                .getAsJsonObject("SelectedItem")
                .get("id")
                .asString,
        )
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
        sandbox.executeCommand(
            "execute as @e[type=minecraft:zombie,limit=1] if predicate demo:zombie_mainhand_stick run scoreboard players add #predicate equipment 1",
        )
        sandbox.executeCommand(
            "execute as @e[type=minecraft:zombie,limit=1] unless predicate demo:zombie_offhand_stick run scoreboard players add #predicate equipment 1",
        )
        sandbox.executeCommand(
            "execute as @e[type=minecraft:zombie,limit=1] if predicate demo:zombie_helmet_nbt run scoreboard players add #predicate equipment 1",
        )

        val zombie = sandbox.world.entities.first { it.type == ResourceLocation.parse("minecraft:zombie") }
        val equipped = zombie.equipment[EquipmentSlots.MAINHAND] ?: error("missing zombie mainhand equipment")
        assertEquals(ResourceLocation.parse("minecraft:stick"), equipped.id)
        assertEquals(4, equipped.count)
        assertEquals(true, equipped.nbt.get("marked").asBoolean)
        assertEquals(
            3,
            equipped.components
                .getAsJsonObject("minecraft:enchantments")
                .get("minecraft:sharpness")
                .asInt,
        )

        val handItem = zombie.fullNbt(sandbox.profile).getAsJsonArray("HandItems")[0].asJsonObject
        assertEquals("minecraft:stick", handItem.get("id").asString)
        assertEquals(4, handItem.get("count").asInt)
        assertEquals(
            true,
            handItem
                .getAsJsonObject("components")
                .getAsJsonObject("minecraft:custom_data")
                .get("marked")
                .asBoolean,
        )
        assertEquals(
            3,
            handItem
                .getAsJsonObject("components")
                .getAsJsonObject("minecraft:enchantments")
                .get("minecraft:sharpness")
                .asInt,
        )

        val copiedToPlayer = sandbox.world.requirePlayer("Steve").inventory[3]
        assertEquals(ResourceLocation.parse("minecraft:stick"), copiedToPlayer.id)
        assertEquals(4, copiedToPlayer.count)
        assertEquals(true, copiedToPlayer.nbt.get("marked").asBoolean)
        assertEquals(
            3,
            copiedToPlayer.components
                .getAsJsonObject("minecraft:enchantments")
                .get("minecraft:sharpness")
                .asInt,
        )

        val snapshotEquipment =
            sandbox
                .snapshotJson()
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
        assertEquals(
            "minecraft:stick",
            enchantPayload
                .getAsJsonArray("items")[0]
                .asJsonObject
                .getAsJsonObject("item")
                .get("id")
                .asString,
        )
    }

    @Test
    fun `effect commands support non-player entity active effects`() {
        val pack = writeEffectPredicatePack(Files.createTempDirectory("dps-effect-predicate-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))

        sandbox.executeCommand("summon minecraft:zombie 0 64 0")
        sandbox.executeCommand("scoreboard objectives add effects dummy")
        sandbox.executeCommand("effect give @e[type=minecraft:zombie,limit=1] minecraft:speed 7 2 true")
        sandbox.executeCommand(
            "execute if data entity @e[type=minecraft:zombie,limit=1] ActiveEffects[{id:\"minecraft:speed\"}] run scoreboard players add #nbt effects 1",
        )
        sandbox.executeCommand(
            "execute as @e[type=minecraft:zombie,limit=1] if predicate demo:zombie_speed_effect run scoreboard players add #predicate effects 1",
        )
        sandbox.executeCommand(
            "execute as @e[type=minecraft:zombie,limit=1] if predicate demo:zombie_no_strength run scoreboard players add #predicate effects 1",
        )

        val zombie = sandbox.world.entities.first { it.type == ResourceLocation.parse("minecraft:zombie") }
        val speed = zombie.activeEffects[ResourceLocation.parse("minecraft:speed")] ?: error("missing zombie speed effect")
        assertEquals(140, speed.durationTicks)
        assertEquals(2, speed.amplifier)
        assertEquals(true, speed.hideParticles)

        val effectNbt =
            zombie
                .fullNbt(sandbox.profile)
                .getAsJsonArray("ActiveEffects")
                .single { it.asJsonObject.get("id").asString == "minecraft:speed" }
                .asJsonObject
        assertEquals(140, effectNbt.get("duration").asInt)
        assertEquals(2, effectNbt.get("amplifier").asInt)
        assertEquals(false, effectNbt.get("show_particles").asBoolean)

        val snapshotEffects =
            sandbox
                .snapshotJson()
                .getAsJsonArray("entities")
                .single { it.asJsonObject.get("uuid").asString == zombie.uuid }
                .asJsonObject
                .getAsJsonArray("effects")
        assertEquals(
            "minecraft:speed",
            snapshotEffects
                .single()
                .asJsonObject
                .get("id")
                .asString,
        )
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
        assertEquals(
            "minecraft:speed",
            clearOutputs
                .first()
                .payload
                ?.asJsonObject
                ?.get("effect")
                ?.asString,
        )
        assertEquals(
            false,
            clearOutputs
                .first()
                .payload
                ?.asJsonObject
                ?.get("all")
                ?.asBoolean,
        )
        assertEquals(
            true,
            clearOutputs
                .last()
                .payload
                ?.asJsonObject
                ?.get("all")
                ?.asBoolean,
        )
    }
}
