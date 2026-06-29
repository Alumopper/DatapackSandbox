package moe.afox.dpsandbox.core

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SandboxBehaviorTest {
    private fun fixturePack(): Path =
        Path.of("src/test/resources/packs/counter")

    @Test
    fun `runs load and tick functions`() {
        val sandbox = createSandbox("26.1.2", listOf(fixturePack()))
        sandbox.runLoad()
        sandbox.runTicks(20)

        assertEquals(20, sandbox.world.getScore("#clock", "ticks"))
        assertEquals("ticking", sandbox.world.storages[ResourceLocation.parse("demo:state")]?.get("phase")?.asString)
        assertTrue(sandbox.snapshotString().contains("\"version\": \"26.1.2\""))
    }

    @Test
    fun `runs functions entities tags and scheduled functions`() {
        val sandbox = createSandbox("26.1.2", listOf(fixturePack()))

        sandbox.runLoad()
        sandbox.runTicks(20)
        sandbox.runFunction("demo:main")
        sandbox.runTicks(1)

        assertEquals(26, sandbox.world.getScore("#clock", "ticks"))
        assertEquals(1, sandbox.world.entities.count { it.type == ResourceLocation.parse("minecraft:marker") && "active" in it.tags })
        assertEquals(true, sandbox.world.storages[ResourceLocation.parse("demo:state")]?.get("scheduled")?.asBoolean)
    }

    @Test
    fun `warns for unsupported vanilla commands by default`() {
        val sandbox = createSandbox("26.1.2", listOf(fixturePack()))

        sandbox.executeCommand("ban Steve")

        assertEquals("warning", sandbox.world.outputs.single().channel)
        assertTrue(sandbox.world.outputs.single().text.contains("ban"))
    }

    @Test
    fun `can ignore or error for unsupported vanilla commands`() {
        val ignored = createSandbox("26.1.2", listOf(fixturePack()), unsupportedFeatureMode = UnsupportedFeatureMode.IGNORE)
        ignored.executeCommand("ban Steve")
        assertTrue(ignored.world.outputs.isEmpty())

        val strict = createSandbox("26.1.2", listOf(fixturePack()), unsupportedFeatureMode = UnsupportedFeatureMode.ERROR)
        val error = assertFailsWith<SandboxException> {
            strict.executeCommand("ban Steve")
        }
        assertEquals(DiagnosticCode.UNSUPPORTED_FEATURE, error.code)
    }

    @Test
    fun `supports sandbox state commands for players teams time weather and bossbars`() {
        val sandbox = createSandbox("26.1.2", listOf(fixturePack()))

        sandbox.executeCommand("bossbar add demo:timer {\"text\":\"Timer\"}")
        sandbox.executeCommand("bossbar set demo:timer value 5")
        sandbox.executeCommand("gamerule doDaylightCycle false")
        sandbox.executeCommand("time set noon")
        sandbox.executeCommand("weather rain 200")
        sandbox.executeCommand("team add red")
        sandbox.executeCommand("team join red Steve")
        sandbox.executeCommand("give Steve minecraft:apple 3")
        sandbox.executeCommand("effect give Steve minecraft:speed 10 1 true")
        sandbox.executeCommand("enchant Steve minecraft:sharpness 2")
        sandbox.executeCommand("xp add Steve 7 points")
        sandbox.executeCommand("recipe give Steve minecraft:bread")

        val player = sandbox.world.requirePlayer("Steve")
        assertEquals(5, sandbox.world.bossbars[ResourceLocation.parse("demo:timer")]?.value)
        assertEquals("false", sandbox.world.gamerules["doDaylightCycle"])
        assertEquals(6000, sandbox.world.dayTime)
        assertEquals("rain", sandbox.world.weather)
        assertEquals(200, sandbox.world.weatherDuration)
        assertTrue("Steve" in sandbox.world.teams.getValue("red").members)
        assertEquals(ResourceLocation.parse("minecraft:apple"), player.inventory.single().id)
        assertEquals(3, player.inventory.single().count)
        assertTrue(ResourceLocation.parse("minecraft:speed") in player.effects)
        assertEquals(7, player.xp)
        assertTrue(ResourceLocation.parse("minecraft:bread") in player.recipes)
        assertEquals(2, player.selectedItem?.components?.getAsJsonObject("minecraft:enchantments")?.get("minecraft:sharpness")?.asInt)
    }

    @Test
    fun `supports world entity inventory random and execute store commands`() {
        val sandbox = createSandbox("26.1.2", listOf(fixturePack()))

        sandbox.executeCommand("fill 0 0 0 1 0 0 minecraft:stone")
        sandbox.executeCommand("clone 0 0 0 1 0 0 0 1 0")
        sandbox.executeCommand("""summon minecraft:pig 0 0 0 {Tags:["rider"]}""")
        sandbox.executeCommand("""summon minecraft:cow 1 0 0 {Tags:["vehicle"]}""")
        sandbox.executeCommand("ride @e[tag=rider] mount @e[tag=vehicle]")
        sandbox.executeCommand("rotate @e[tag=rider] 90 15")
        sandbox.executeCommand("damage @e[tag=rider] 5")
        sandbox.executeCommand("item replace entity Steve hotbar.0 with minecraft:diamond_sword 1")
        sandbox.executeCommand("scoreboard objectives add runs dummy")
        sandbox.executeCommand("execute store result score Steve runs run random value 3..3")

        assertEquals(ResourceLocation.parse("minecraft:stone"), sandbox.world.requireBlock(BlockPos(0, 1, 0)).id)
        assertEquals(ResourceLocation.parse("minecraft:stone"), sandbox.world.requireBlock(BlockPos(1, 1, 0)).id)
        val pig = sandbox.world.entities.single { "rider" in it.tags }
        val cow = sandbox.world.entities.single { "vehicle" in it.tags }
        assertEquals(cow.uuid, pig.vehicle)
        assertTrue(pig.uuid in cow.passengers)
        assertEquals(90.0, pig.yaw)
        assertEquals(15.0, pig.pitch)
        assertEquals(5.0, pig.fullNbt().get("Health").asDouble)
        assertEquals(ResourceLocation.parse("minecraft:diamond_sword"), sandbox.world.requirePlayer("Steve").inventory[0].id)
        assertEquals(1, sandbox.world.getScore("Steve", "runs"))
        assertTrue(sandbox.world.outputs.any { it.command == "random value" && it.text == "3" })
    }

    @Test
    fun `records output commands`() {
        val sandbox = createSandbox("26.1.2", listOf(fixturePack()))
        sandbox.createPlayer("Steve")

        sandbox.executeCommand("""tellraw @a {"text":"hello","color":"green"}""")
        sandbox.executeCommand("""title @a actionbar {"text":"ready"}""")
        sandbox.executeCommand("playsound minecraft:ui.button.click master @a")

        assertEquals(listOf("tellraw", "title actionbar", "playsound"), sandbox.world.outputs.map { it.command })
        assertEquals("hello", sandbox.world.outputs[0].text)
        assertEquals(listOf("Steve"), sandbox.world.outputs[0].targets)
    }

    @Test
    fun `resolves styled raw json text score components`() {
        val sandbox = createSandbox("26.1.2", listOf(fixturePack()))
        sandbox.createPlayer("Steve")
        sandbox.executeCommand("scoreboard objectives add rewards dummy")
        sandbox.executeCommand("scoreboard players set Steve rewards 7")

        sandbox.executeCommand("""tellraw @a {"score":{"name":"Steve","objective":"rewards"},"color":"yellow"}""")

        val output = sandbox.world.outputs.single()
        assertEquals("7", output.text)
        assertEquals("yellow", output.segments.single().color)
        assertEquals("7", output.segments.single().text)
    }

    @Test
    fun `lists scoreboard objectives without requiring an objective argument`() {
        val sandbox = createSandbox("26.1.2", listOf(fixturePack()))

        sandbox.executeCommand("scoreboard objectives list")
        sandbox.executeCommand("scoreboard objectives add rewards dummy")
        sandbox.executeCommand("scoreboard objectives list")

        assertEquals("scoreboard objectives list", sandbox.world.outputs[0].command)
        assertEquals("No scoreboard objectives", sandbox.world.outputs[0].text)
        assertEquals("rewards (dummy)", sandbox.world.outputs[1].text)
        assertEquals("dummy", sandbox.world.outputs[1].payload?.asJsonObject?.get("rewards")?.asString)
    }

    @Test
    fun `creates a default player with readable vanilla style nbt`() {
        val sandbox = createSandbox("26.1.2", listOf(fixturePack()))

        sandbox.executeCommand("data get entity Steve Inventory")

        val playerNbt = sandbox.world.requirePlayer("Steve").fullNbt()
        assertEquals("minecraft:player", playerNbt.get("id").asString)
        assertEquals("Steve", playerNbt.get("Name").asString)
        assertTrue(playerNbt.has("abilities"))
        assertTrue(playerNbt.has("EnderItems"))
        assertTrue(playerNbt.has("foodLevel"))
        assertEquals("[]", sandbox.world.outputs.single().text.trim())
    }

    @Test
    fun `summons entities with full nbt and mutates entity nbt`() {
        val sandbox = createSandbox("26.1.2", listOf(fixturePack()))

        sandbox.executeCommand("""summon minecraft:zombie 1 2 3 {Tags:["mob"],Health:20f,CustomName:'{"text":"Bob"}'}""")
        sandbox.executeCommand("data modify entity @e[type=minecraft:zombie,limit=1] Health set value 10f")

        val entity = sandbox.world.entities.single { it.type == ResourceLocation.parse("minecraft:zombie") }
        val nbt = entity.fullNbt()
        assertEquals("minecraft:zombie", nbt.get("id").asString)
        assertEquals(1.0, nbt.getAsJsonArray("Pos")[0].asDouble)
        assertEquals(10.0, nbt.get("Health").asDouble)
        assertTrue("mob" in entity.tags)
        assertTrue(!nbt.has("NoAI"), "Entities do not tick AI, but NoAI is not injected")
    }

    @Test
    fun `rejects custom entity nbt fields`() {
        val sandbox = createSandbox("26.1.2", listOf(fixturePack()))

        val error = assertFailsWith<SandboxException> {
            sandbox.executeCommand("""summon minecraft:pig 0 0 0 {test:1}""")
        }

        assertEquals(DiagnosticCode.INPUT_FORMAT, error.code)
        assertTrue(error.message.contains("Unknown NBT field"))
    }

    @Test
    fun `accepts entity nbt fields from generated vanilla mcdoc schema`() {
        val sandbox = createSandbox("26.1.2", listOf(fixturePack()))

        sandbox.executeCommand("""summon minecraft:pig 0 0 0 {AgeLocked:true}""")

        val pig = sandbox.world.entities.single { it.type == ResourceLocation.parse("minecraft:pig") }
        assertEquals(true, pig.fullNbt().get("AgeLocked").asBoolean)
    }

    @Test
    fun `selects nearest entity with at n selector`() {
        val sandbox = createSandbox("26.1.2", listOf(fixturePack()))

        sandbox.executeCommand("""summon minecraft:pig 10 0 0 {Tags:["far"]}""")
        sandbox.executeCommand("""summon minecraft:pig 1 0 0 {Tags:["near"]}""")
        sandbox.executeCommand("tag @n[type=minecraft:pig] add picked")

        val near = sandbox.world.entities.single { "near" in it.tags }
        val far = sandbox.world.entities.single { "far" in it.tags }
        assertTrue("picked" in near.tags)
        assertTrue("picked" !in far.tags)
    }

    @Test
    fun `reads player nbt but rejects player nbt writes and supports teleport movement`() {
        val sandbox = createSandbox("26.1.2", listOf(fixturePack()))
        sandbox.createPlayer("Steve")

        sandbox.executeCommand("tp Steve 10 64 -2")
        sandbox.executeCommand("data get entity Steve Pos")

        val player = sandbox.world.requirePlayer("Steve")
        assertEquals(Position(10.0, 64.0, -2.0), player.position)
        assertEquals(10.0, sandbox.world.requirePlayer("Steve").fullNbt().getAsJsonArray("Pos")[0].asDouble)
        assertEquals("get", sandbox.world.outputs.single().command)

        val error = assertFailsWith<SandboxException> {
            sandbox.executeCommand("data modify entity Steve Health set value 1f")
        }
        assertEquals(DiagnosticCode.COMMAND_ERROR, error.code)
    }

    @Test
    fun `places blocks in an initially void world and mutates block nbt`() {
        val sandbox = createSandbox("26.1.2", listOf(fixturePack()))

        assertTrue(sandbox.world.blocks.isEmpty())
        sandbox.executeCommand("""setblock 0 64 0 minecraft:chest[facing=north]{Items:[{Slot:0b,id:"minecraft:apple",count:1b}]}""")
        sandbox.executeCommand("""data modify block 0 64 0 Items append value {Slot:0b,id:"minecraft:stone",count:1b}""")
        sandbox.executeCommand("execute if block 0 64 0 minecraft:chest[facing=north] run setblock 1 64 0 minecraft:stone")

        val chest = sandbox.world.requireBlock(BlockPos(0, 64, 0))
        assertEquals(ResourceLocation.parse("minecraft:chest"), chest.id)
        assertEquals("north", chest.properties["facing"])
        assertEquals(2, chest.nbt.getAsJsonArray("Items").size())
        assertEquals(ResourceLocation.parse("minecraft:stone"), sandbox.world.requireBlock(BlockPos(1, 64, 0)).id)
    }

    @Test
    fun `uses generated vanilla mcdoc block entity mappings and item stack fields`() {
        val sandbox = createSandbox("26.1.2", listOf(fixturePack()))

        sandbox.executeCommand("""setblock 0 0 2 minecraft:copper_chest{Items:[{Slot:0b,id:"minecraft:apple",Count:1b}]}""")

        val pos = BlockPos(0, 0, 2)
        val copperChest = sandbox.world.requireBlock(pos)
        val nbt = NbtSchemas.blockEntityNbt(copperChest, pos)
        assertEquals(ResourceLocation.parse("minecraft:copper_chest"), copperChest.id)
        assertEquals("minecraft:chest", nbt.get("id").asString)
        assertEquals(1, nbt.getAsJsonArray("Items").size())
    }

    @Test
    fun `rejects custom block entity nbt fields`() {
        val sandbox = createSandbox("26.1.2", listOf(fixturePack()))

        sandbox.executeCommand("setblock 0 0 1 minecraft:chest")
        val error = assertFailsWith<SandboxException> {
            sandbox.executeCommand("data modify block 0 0 1 test set value 1")
        }

        assertEquals(DiagnosticCode.INPUT_FORMAT, error.code)
        assertTrue(error.message.contains("custom block NBT fields are not allowed"))
        assertTrue(!sandbox.world.requireBlock(BlockPos(0, 0, 1)).nbt.has("test"))
    }

    @Test
    fun `rejects custom fields inside block item stacks`() {
        val sandbox = createSandbox("26.1.2", listOf(fixturePack()))

        sandbox.executeCommand("setblock 0 0 1 minecraft:chest")
        val error = assertFailsWith<SandboxException> {
            sandbox.executeCommand("""data modify block 0 0 1 Items append value {Slot:0b,id:"minecraft:stone",count:1b,test:1}""")
        }

        assertEquals(DiagnosticCode.INPUT_FORMAT, error.code)
        assertTrue(error.message.contains("Unknown NBT field(s) for item stack"))
    }
}
