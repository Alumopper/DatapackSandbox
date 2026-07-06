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
        sandbox.executeCommand("gamemode adventure Steve")
        sandbox.executeCommand("setworldspawn 10 70 10 45")
        sandbox.executeCommand("spawnpoint Steve 1 65 2 90")
        sandbox.executeCommand("worldborder set 100")
        sandbox.executeCommand("worldborder center 5 -6")
        sandbox.executeCommand("forceload add 0 0 16 16")
        sandbox.executeCommand("forceload query 0 0")
        sandbox.executeCommand("forceload query 48 0")
        sandbox.executeCommand("fillbiome 0 0 0 1 0 1 minecraft:plains")
        sandbox.executeCommand("tick freeze")
        sandbox.executeCommand("tick step 2")

        val snapshot = sandbox.snapshotJson()
        assertEquals("hard", snapshot.get("difficulty").asString)
        assertEquals("creative", snapshot.get("defaultGameMode").asString)
        assertEquals("adventure", snapshot.getAsJsonObject("players").getAsJsonObject("Steve").get("gameMode").asString)
        assertEquals(true, snapshot.get("tickFrozen").asBoolean)
        assertEquals(2, snapshot.get("gameTime").asLong)
        assertEquals(4, snapshot.getAsJsonArray("biomes").size())
        assertTrue(snapshot.getAsJsonArray("forcedChunks").size() >= 1)
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
    }

    @Test
    fun `utility commands record deterministic outputs`() {
        val sandbox = createSandbox("26.1.2", listOf(fixturePack()))

        sandbox.executeCommand("help gamemode")
        sandbox.executeCommand("list uuids")
        sandbox.executeCommand("seed")
        sandbox.executeCommand("locate biome minecraft:plains")
        sandbox.executeCommand("datapack list")
        sandbox.executeCommand("reload")
        sandbox.executeCommand("scoreboard objectives add trig trigger")
        sandbox.executeCommand("trigger trig set 4")

        assertEquals(4, sandbox.world.getScore("Steve", "trig"))
        assertTrue(sandbox.world.outputs.any { it.command == "help" && it.text.contains("gamemode") })
        assertTrue(sandbox.world.outputs.any { it.command == "list" && it.text.contains("Steve") })
        assertTrue(sandbox.world.outputs.any { it.command == "locate" && it.payload?.asJsonObject?.get("found")?.asBoolean == false })
        assertTrue(sandbox.world.outputs.any { it.command == "datapack list" })
        assertTrue(sandbox.world.outputs.any { it.command == "reload" })
    }

    @Test
    fun `attribute and loot commands expose sandbox-visible state`() {
        val pack = writeLootPack(Files.createTempDirectory("dps-command-expansion-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))

        sandbox.executeCommand("summon minecraft:zombie 0 64 0")
        sandbox.executeCommand("attribute @e[type=minecraft:zombie,limit=1] minecraft:max_health base set 40")
        sandbox.executeCommand("attribute @e[type=minecraft:zombie,limit=1] minecraft:max_health get 0.5")
        sandbox.executeCommand("scoreboard objectives add attr dummy")
        sandbox.executeCommand("execute store result score #max attr run attribute @e[type=minecraft:zombie,limit=1] minecraft:max_health base get 0.25")
        sandbox.executeCommand("loot give Steve loot demo:gift")

        val zombie = sandbox.world.entities.first { it.type == ResourceLocation.parse("minecraft:zombie") }
        assertEquals(40.0, zombie.attributes[ResourceLocation.parse("minecraft:max_health")])
        val attributeOutput = sandbox.world.outputs.first { it.command == "attribute get" }
        val attributePayload = attributeOutput.payload?.asJsonObject ?: error("missing attribute payload")
        assertEquals("20.0", attributeOutput.text)
        assertEquals(zombie.uuid, attributePayload.get("target").asString)
        assertEquals("minecraft:max_health", attributePayload.get("attribute").asString)
        assertEquals("total", attributePayload.get("field").asString)
        assertEquals(0.5, attributePayload.get("scale").asDouble)
        assertEquals(40.0, attributePayload.get("rawValue").asDouble)
        assertEquals(20.0, attributePayload.get("value").asDouble)
        assertEquals(10, sandbox.world.getScore("#max", "attr"))
        val emerald = sandbox.world.requirePlayer("Steve").inventory.first { it.id == ResourceLocation.parse("minecraft:emerald") }
        assertEquals("Gift", emerald.components.getAsJsonObject("minecraft:custom_name").get("text").asString)
        assertEquals("from loot", emerald.components.getAsJsonArray("minecraft:lore")[0].asJsonObject.get("text").asString)
    }

    @Test
    fun `loot command supports fish mine and empty kill sources`() {
        val pack = writeLootSourcePack(Files.createTempDirectory("dps-loot-source-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))

        sandbox.executeCommand("setblock 0 64 0 minecraft:stone")
        sandbox.executeCommand("loot give Steve fish demo:fish 0 64 0 minecraft:stick")
        sandbox.executeCommand("loot give Steve mine 0 64 0 minecraft:stick")
        sandbox.executeCommand("summon minecraft:zombie 2 64 0")
        sandbox.executeCommand("loot give Steve kill @e[type=minecraft:zombie,limit=1]")
        sandbox.executeCommand("loot spawn 1 64 1 mine 0 64 0")

        val inventoryIds = sandbox.world.requirePlayer("Steve").inventory.map { it.id }.toSet()
        assertTrue(ResourceLocation.parse("minecraft:diamond") in inventoryIds)
        assertTrue(ResourceLocation.parse("minecraft:stone") in inventoryIds)
        assertTrue(
            sandbox.world.entities.any {
                it.type == ResourceLocation.parse("minecraft:item") &&
                    it.nbt.getAsJsonObject("Item")?.get("id")?.asString == "minecraft:stone"
            },
        )
    }

    @Test
    fun `item modify applies common item modifier functions`() {
        val pack = writeItemModifierPack(Files.createTempDirectory("dps-item-modifier-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))

        sandbox.executeCommand("item replace entity Steve hotbar.0 with minecraft:stick 1")
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
    fun `item commands support non-player entity equipment slots`() {
        val pack = writeItemModifierPack(Files.createTempDirectory("dps-entity-equipment-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))

        sandbox.executeCommand("summon minecraft:zombie 0 64 0")
        sandbox.executeCommand("item replace entity @e[type=minecraft:zombie,limit=1] weapon.mainhand with minecraft:stick 1")
        sandbox.executeCommand("item modify entity @e[type=minecraft:zombie,limit=1] weapon.mainhand demo:mark")
        sandbox.executeCommand("item replace entity Steve hotbar.3 from entity @e[type=minecraft:zombie,limit=1] weapon.mainhand")

        val zombie = sandbox.world.entities.first { it.type == ResourceLocation.parse("minecraft:zombie") }
        val equipped = zombie.equipment[EquipmentSlots.MAINHAND] ?: error("missing zombie mainhand equipment")
        assertEquals(ResourceLocation.parse("minecraft:stick"), equipped.id)
        assertEquals(4, equipped.count)
        assertEquals(true, equipped.nbt.get("marked").asBoolean)

        val handItem = zombie.fullNbt(sandbox.profile).getAsJsonArray("HandItems")[0].asJsonObject
        assertEquals("minecraft:stick", handItem.get("id").asString)
        assertEquals(4, handItem.get("count").asInt)
        assertEquals(true, handItem.getAsJsonObject("components").getAsJsonObject("minecraft:custom_data").get("marked").asBoolean)

        val copiedToPlayer = sandbox.world.requirePlayer("Steve").inventory[3]
        assertEquals(ResourceLocation.parse("minecraft:stick"), copiedToPlayer.id)
        assertEquals(4, copiedToPlayer.count)
        assertEquals(true, copiedToPlayer.nbt.get("marked").asBoolean)

        val snapshotEquipment = sandbox.snapshotJson()
            .getAsJsonArray("entities")
            .single { it.asJsonObject.get("uuid").asString == zombie.uuid }
            .asJsonObject
            .getAsJsonObject("equipment")
        assertEquals("minecraft:stick", snapshotEquipment.getAsJsonObject("weapon.mainhand").get("id").asString)
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
    fun `execute as preserves position while at and positioned as move context`() {
        val sandbox = createFunctionSandboxFromString(
            version = "26.2",
            functionText = "",
            functionId = "demo:empty",
        )

        sandbox.executeCommand("""summon minecraft:pig 5 0 0 {Tags:["anchor"]}""")
        sandbox.executeCommand("""execute as @e[tag=anchor,limit=1] run summon minecraft:marker ~1 ~0 ~0 {Tags:["from_as"]}""")
        sandbox.executeCommand("""execute at @e[tag=anchor,limit=1] run summon minecraft:marker ~1 ~0 ~0 {Tags:["from_at"]}""")
        sandbox.executeCommand("""execute positioned as @e[tag=anchor,limit=1] run summon minecraft:marker ~2 ~0 ~0 {Tags:["from_positioned_as"]}""")

        assertEquals(Position(1.0, 0.0, 0.0), sandbox.world.entities.single { "from_as" in it.tags }.position)
        assertEquals(Position(6.0, 0.0, 0.0), sandbox.world.entities.single { "from_at" in it.tags }.position)
        assertEquals(Position(7.0, 0.0, 0.0), sandbox.world.entities.single { "from_positioned_as" in it.tags }.position)
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
        sandbox.executeCommand("""summon minecraft:marker 4 2 1 {Tags:["destination"]}""")
        sandbox.executeCommand("""rotate @e[tag=destination,limit=1] -45 12""")
        sandbox.executeCommand("""tp @e[tag=traveler,limit=1] @e[tag=destination,limit=1]""")

        val traveler = sandbox.world.entities.single { "traveler" in it.tags }
        assertEquals(Position(4.0, 2.0, 1.0), traveler.position)
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
        sandbox.executeCommand("execute store result score #return checks run function demo:return_run")
        sandbox.executeCommand("execute store result score #explicit checks run function demo:return_after_output")

        assertEquals(3, sandbox.world.getScore("#pass", "checks"))
        assertEquals(0, sandbox.world.getScore("#fail", "checks"))
        assertEquals(33, sandbox.world.getScore("#condition", "checks"))
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
