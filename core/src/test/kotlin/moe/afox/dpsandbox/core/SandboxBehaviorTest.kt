package moe.afox.dpsandbox.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SandboxBehaviorTest {
    private fun fixturePack(): Path =
        Path.of("src/test/resources/packs/counter")

    private fun writeRecipeCommandPack(root: Path): Path {
        Files.writeString(
            root.resolve("pack.mcmeta").also { Files.createDirectories(it.parent) },
            """
            {
              "pack": {
                "pack_format": 107.1,
                "description": "recipe command test"
              }
            }
            """.trimIndent(),
        )
        val recipeRoot = root.resolve("data").resolve("demo").resolve("recipe")
        Files.createDirectories(recipeRoot)
        Files.writeString(
            recipeRoot.resolve("marker.json"),
            """
            {
              "type": "minecraft:crafting_shapeless",
              "ingredients": [],
              "result": { "id": "minecraft:stone", "count": 1 }
            }
            """.trimIndent(),
        )
        return root
    }

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
        val scheduleOutput = sandbox.world.outputs.single { it.command == "schedule function" }
        val schedulePayload = scheduleOutput.payload?.asJsonObject ?: error("missing schedule payload")
        assertEquals("demo:scheduled", schedulePayload.get("id").asString)
        assertEquals(1, schedulePayload.get("delay").asLong)
        assertEquals(21, schedulePayload.get("dueTick").asLong)
        assertEquals("replace", schedulePayload.get("mode").asString)
        assertEquals(0, schedulePayload.get("replaced").asInt)
        sandbox.runTicks(1)

        assertEquals(26, sandbox.world.getScore("#clock", "ticks"))
        assertEquals(1, sandbox.world.entities.count { it.type == ResourceLocation.parse("minecraft:marker") && "active" in it.tags })
        assertEquals(true, sandbox.world.storages[ResourceLocation.parse("demo:state")]?.get("scheduled")?.asBoolean)

        sandbox.executeCommand("schedule function demo:scheduled 5t append")
        sandbox.executeCommand("schedule clear demo:scheduled")
        val clearPayload = sandbox.world.outputs.last { it.command == "schedule clear" }.payload?.asJsonObject
            ?: error("missing schedule clear payload")
        assertEquals("demo:scheduled", clearPayload.get("id").asString)
        assertEquals(1, clearPayload.get("removed").asInt)
        assertEquals(0, clearPayload.get("remaining").asInt)
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
        sandbox.executeCommand("bossbar get demo:timer value")
        sandbox.executeCommand("scoreboard objectives add boss_copy dummy")
        sandbox.executeCommand("execute store result score #boss boss_copy run bossbar get demo:timer value")
        sandbox.executeCommand("gamerule doDaylightCycle false")
        sandbox.executeCommand("gamerule doDaylightCycle")
        sandbox.executeCommand("gamerule missingRule")
        sandbox.executeCommand("time set noon")
        sandbox.executeCommand("time query daytime")
        sandbox.executeCommand("scoreboard objectives add time_copy dummy")
        sandbox.executeCommand("execute store result score #daytime time_copy run time query daytime")
        sandbox.executeCommand("weather rain 200")
        sandbox.executeCommand("team add red")
        sandbox.executeCommand("team join red Steve")
        sandbox.executeCommand("team modify red color blue")
        sandbox.executeCommand("give Steve minecraft:apple 3")
        sandbox.executeCommand("effect give Steve minecraft:speed 10 1 true")
        sandbox.executeCommand("enchant Steve minecraft:sharpness 2")
        sandbox.executeCommand("xp add Steve 7 points")
        sandbox.executeCommand("xp query Steve points")
        sandbox.executeCommand("scoreboard objectives add xp_copy dummy")
        sandbox.executeCommand("execute store result score #xp xp_copy run experience query Steve levels")
        sandbox.executeCommand("recipe give Steve minecraft:bread")

        val player = sandbox.world.requirePlayer("Steve")
        assertEquals(5, sandbox.world.bossbars[ResourceLocation.parse("demo:timer")]?.value)
        val bossbarOutput = sandbox.world.outputs.first { it.command == "bossbar get" }
        val bossbarPayload = bossbarOutput.payload?.asJsonObject ?: error("missing bossbar get payload")
        assertEquals("5", bossbarOutput.text)
        assertEquals("demo:timer", bossbarPayload.get("id").asString)
        assertEquals("value", bossbarPayload.get("field").asString)
        assertEquals(5, bossbarPayload.get("value").asInt)
        assertEquals(5, sandbox.world.getScore("#boss", "boss_copy"))
        assertEquals("false", sandbox.world.gamerules["doDaylightCycle"])
        val gameruleSetOutput = sandbox.world.outputs.single { it.command == "gamerule set" }
        val gameruleSetPayload = gameruleSetOutput.payload?.asJsonObject ?: error("missing gamerule set payload")
        assertEquals("false", gameruleSetOutput.text)
        assertEquals("set", gameruleSetPayload.get("action").asString)
        assertEquals("doDaylightCycle", gameruleSetPayload.get("rule").asString)
        assertEquals(false, gameruleSetPayload.get("beforeExists").asBoolean)
        assertEquals("false", gameruleSetPayload.get("value").asString)
        val gameruleOutputs = sandbox.world.outputs.filter { it.command == "gamerule" }
        val gamerulePayload = gameruleOutputs[0].payload?.asJsonObject ?: error("missing gamerule payload")
        val missingGamerulePayload = gameruleOutputs[1].payload?.asJsonObject ?: error("missing missing gamerule payload")
        assertEquals("false", gameruleOutputs[0].text)
        assertEquals("doDaylightCycle", gamerulePayload.get("rule").asString)
        assertEquals(true, gamerulePayload.get("exists").asBoolean)
        assertEquals("false", gamerulePayload.get("value").asString)
        assertEquals("<unset>", gameruleOutputs[1].text)
        assertEquals("missingRule", missingGamerulePayload.get("rule").asString)
        assertEquals(false, missingGamerulePayload.get("exists").asBoolean)
        assertEquals(6000, sandbox.world.dayTime)
        val timeSetOutput = sandbox.world.outputs.single { it.command == "time set" }
        val timeSetPayload = timeSetOutput.payload?.asJsonObject ?: error("missing time set payload")
        assertEquals("6000", timeSetOutput.text)
        assertEquals("noon", timeSetPayload.get("argument").asString)
        assertEquals(0, timeSetPayload.get("beforeDayTime").asLong)
        assertEquals(6000, timeSetPayload.get("afterDayTime").asLong)
        val timeOutput = sandbox.world.outputs.first { it.command == "time query" }
        val timePayload = timeOutput.payload?.asJsonObject ?: error("missing time query payload")
        assertEquals("6000", timeOutput.text)
        assertEquals("daytime", timePayload.get("query").asString)
        assertEquals(6000, timePayload.get("value").asLong)
        assertEquals(6000, sandbox.world.getScore("#daytime", "time_copy"))
        assertEquals("rain", sandbox.world.weather)
        assertEquals(200, sandbox.world.weatherDuration)
        val weatherOutput = sandbox.world.outputs.single { it.command == "weather" }
        val weatherPayload = weatherOutput.payload?.asJsonObject ?: error("missing weather payload")
        assertEquals("rain", weatherOutput.text)
        assertEquals("rain", weatherPayload.get("weather").asString)
        assertEquals(200, weatherPayload.get("duration").asInt)
        assertEquals(true, weatherPayload.get("raining").asBoolean)
        assertEquals(false, weatherPayload.get("thundering").asBoolean)
        assertTrue("Steve" in sandbox.world.teams.getValue("red").members)
        assertEquals("blue", sandbox.world.teams.getValue("red").options["color"])
        val teamAddOutput = sandbox.world.outputs.single { it.command == "team add" }
        val teamAddPayload = teamAddOutput.payload?.asJsonObject ?: error("missing team add payload")
        assertEquals("red", teamAddOutput.text)
        assertEquals("add", teamAddPayload.get("action").asString)
        assertEquals("red", teamAddPayload.get("name").asString)
        assertEquals(false, teamAddPayload.get("replaced").asBoolean)
        val teamJoinOutput = sandbox.world.outputs.single { it.command == "team join" }
        val teamJoinPayload = teamJoinOutput.payload?.asJsonObject ?: error("missing team join payload")
        assertEquals("1", teamJoinOutput.text)
        assertEquals("join", teamJoinPayload.get("action").asString)
        assertEquals(1, teamJoinPayload.get("added").asInt)
        assertEquals("Steve", teamJoinPayload.getAsJsonArray("requestedMembers")[0].asString)
        assertEquals("Steve", teamJoinPayload.getAsJsonArray("members")[0].asString)
        val teamModifyOutput = sandbox.world.outputs.single { it.command == "team modify" }
        val teamModifyPayload = teamModifyOutput.payload?.asJsonObject ?: error("missing team modify payload")
        assertEquals("blue", teamModifyOutput.text)
        assertEquals("modify", teamModifyPayload.get("action").asString)
        assertEquals("color", teamModifyPayload.get("option").asString)
        assertEquals("blue", teamModifyPayload.get("value").asString)
        assertEquals(false, teamModifyPayload.getAsJsonObject("before").getAsJsonObject("options").has("color"))
        assertEquals(ResourceLocation.parse("minecraft:apple"), player.inventory.single().id)
        assertEquals(3, player.inventory.single().count)
        assertTrue(ResourceLocation.parse("minecraft:speed") in player.effects)
        assertEquals(7, player.xp)
        val xpOutput = sandbox.world.outputs.first { it.command == "xp query" }
        val xpPayload = xpOutput.payload?.asJsonObject ?: error("missing xp query payload")
        assertEquals("7", xpOutput.text)
        assertEquals("Steve", xpPayload.get("player").asString)
        assertEquals("points", xpPayload.get("kind").asString)
        assertEquals(7, xpPayload.get("value").asInt)
        assertEquals(7, sandbox.world.getScore("#xp", "xp_copy"))
        assertTrue(ResourceLocation.parse("minecraft:bread") in player.recipes)
        assertEquals(2, player.selectedItem?.components?.getAsJsonObject("minecraft:enchantments")?.get("minecraft:sharpness")?.asInt)
    }

    @Test
    fun `bossbar mutations record structured outputs`() {
        val sandbox = createSandbox("26.1.2", listOf(fixturePack()))

        sandbox.executeCommand("""bossbar add demo:bar {"text":"Demo"}""")
        sandbox.executeCommand("bossbar set demo:bar value 8")
        sandbox.executeCommand("bossbar set demo:bar players Steve")
        sandbox.executeCommand("bossbar remove demo:bar")

        assertEquals(null, sandbox.world.bossbars[ResourceLocation.parse("demo:bar")])
        val addOutput = sandbox.world.outputs.single { it.command == "bossbar add" }
        val addPayload = addOutput.payload?.asJsonObject ?: error("missing bossbar add payload")
        assertEquals("demo:bar", addOutput.text)
        assertEquals("add", addPayload.get("action").asString)
        assertEquals("demo:bar", addPayload.get("id").asString)
        assertEquals("""{"text":"Demo"}""", addPayload.get("name").asString)
        assertEquals(false, addPayload.get("replaced").asBoolean)

        val setOutputs = sandbox.world.outputs.filter { it.command == "bossbar set" }
        val valuePayload = setOutputs[0].payload?.asJsonObject ?: error("missing bossbar value payload")
        assertEquals("8", setOutputs[0].text)
        assertEquals("set", valuePayload.get("action").asString)
        assertEquals("value", valuePayload.get("field").asString)
        assertEquals(0, valuePayload.getAsJsonObject("before").get("value").asInt)
        assertEquals(8, valuePayload.get("value").asInt)
        assertEquals(8, valuePayload.get("fieldValue").asInt)

        val playersPayload = setOutputs[1].payload?.asJsonObject ?: error("missing bossbar players payload")
        assertTrue(setOutputs[1].text.contains("Steve"))
        assertEquals("players", playersPayload.get("field").asString)
        assertEquals(0, playersPayload.getAsJsonObject("before").getAsJsonArray("players").size())
        assertEquals(8, playersPayload.get("value").asInt)
        assertEquals("Steve", playersPayload.getAsJsonArray("fieldValue")[0].asString)
        assertEquals("Steve", playersPayload.getAsJsonArray("players")[0].asString)

        val removeOutput = sandbox.world.outputs.single { it.command == "bossbar remove" }
        val removePayload = removeOutput.payload?.asJsonObject ?: error("missing bossbar remove payload")
        assertEquals("true", removeOutput.text)
        assertEquals("remove", removePayload.get("action").asString)
        assertEquals(true, removePayload.get("removed").asBoolean)
        assertEquals(8, removePayload.getAsJsonObject("before").get("value").asInt)
        assertEquals("Steve", removePayload.getAsJsonObject("before").getAsJsonArray("players")[0].asString)
    }

    @Test
    fun `clear can query and remove bounded inventory stacks`() {
        val sandbox = createSandbox("26.1.2", listOf(fixturePack()))
        val apple = ResourceLocation.parse("minecraft:apple")
        val stick = ResourceLocation.parse("minecraft:stick")
        val diamond = ResourceLocation.parse("minecraft:diamond")

        sandbox.executeCommand("give Steve minecraft:apple 5")
        sandbox.executeCommand("give Steve minecraft:stick 2")
        sandbox.executeCommand("clear Steve minecraft:apple 0")

        val player = sandbox.world.requirePlayer("Steve")
        assertEquals(5, player.inventory.single { it.id == apple }.count)
        val queryPayload = sandbox.world.outputs.last { it.command == "clear" }.payload?.asJsonObject
            ?: error("missing clear payload")
        assertEquals("5", sandbox.world.outputs.last { it.command == "clear" }.text)
        assertEquals(true, queryPayload.get("query").asBoolean)
        assertEquals(5, queryPayload.get("matched").asInt)
        assertEquals(0, queryPayload.get("removed").asInt)

        sandbox.executeCommand("clear Steve minecraft:apple 2")

        assertEquals(3, player.inventory.single { it.id == apple }.count)
        val removePayload = sandbox.world.outputs.last { it.command == "clear" }.payload?.asJsonObject
            ?: error("missing clear payload")
        assertEquals("2", sandbox.world.outputs.last { it.command == "clear" }.text)
        assertEquals(false, removePayload.get("query").asBoolean)
        assertEquals(5, removePayload.get("matched").asInt)
        assertEquals(2, removePayload.get("removed").asInt)

        sandbox.executeCommand("scoreboard objectives add cleared dummy")
        sandbox.executeCommand("execute store result score Steve cleared run clear Steve minecraft:apple 0")

        assertEquals(3, sandbox.world.getScore("Steve", "cleared"))
        assertEquals(3, player.inventory.single { it.id == apple }.count)

        sandbox.executeCommand("give Steve minecraft:stick{marked:true} 4")
        sandbox.executeCommand("give Steve minecraft:stick{marked:false} 3")
        sandbox.executeCommand("clear Steve minecraft:stick{marked:true} 2")

        assertEquals(2, player.inventory.single { it.id == stick && it.nbt.get("marked")?.asBoolean == true }.count)
        assertEquals(3, player.inventory.single { it.id == stick && it.nbt.get("marked")?.asBoolean == false }.count)

        sandbox.executeCommand("give Steve minecraft:diamond[damage=3] 2")
        sandbox.executeCommand("give Steve minecraft:diamond[damage=4] 2")
        sandbox.executeCommand("clear Steve minecraft:diamond[damage=3] 1")

        assertEquals(1, player.inventory.single { it.id == diamond && it.components.get("minecraft:damage").asInt == 3 }.count)
        assertEquals(2, player.inventory.single { it.id == diamond && it.components.get("minecraft:damage").asInt == 4 }.count)
    }

    @Test
    fun `scoreboard players get records output and can be stored`() {
        val sandbox = createSandbox("26.1.2", listOf(fixturePack()))

        sandbox.executeCommand("scoreboard objectives add points dummy")
        sandbox.executeCommand("scoreboard objectives add copied dummy")
        sandbox.executeCommand("scoreboard players set Steve points 42")
        sandbox.executeCommand("scoreboard players get Steve points")

        val output = sandbox.world.outputs.last { it.command == "scoreboard players get" }
        val payload = output.payload?.asJsonObject ?: error("missing scoreboard get payload")
        assertEquals("42", output.text)
        assertEquals("Steve", payload.get("target").asString)
        assertEquals("points", payload.get("objective").asString)
        assertEquals(42, payload.get("value").asInt)

        sandbox.executeCommand("execute store result score #copied copied run scoreboard players get Steve points")

        assertEquals(42, sandbox.world.getScore("#copied", "copied"))
    }

    @Test
    fun `recipe give star and take report changed counts`() {
        val pack = writeRecipeCommandPack(Files.createTempDirectory("dps-recipe-command-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))
        val recipe = ResourceLocation.parse("demo:marker")
        val player = sandbox.world.requirePlayer("Steve")

        sandbox.executeCommand("recipe give Steve *")

        val giveOutput = sandbox.world.outputs.single { it.command == "recipe give" }
        assertTrue(recipe in player.recipes)
        assertEquals("1", giveOutput.text)
        assertEquals("*", giveOutput.payload?.asJsonObject?.get("recipe")?.asString)
        assertEquals(1, giveOutput.payload?.asJsonObject?.get("changed")?.asInt)

        sandbox.executeCommand("scoreboard objectives add recipes dummy")
        sandbox.executeCommand("execute store result score Steve recipes run recipe take Steve *")

        val takeOutput = sandbox.world.outputs.last { it.command == "recipe take" }
        assertTrue(recipe !in player.recipes)
        assertEquals("1", takeOutput.text)
        assertEquals(1, sandbox.world.getScore("Steve", "recipes"))
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
        sandbox.executeCommand("""setblock 2 0 0 minecraft:chest{Items:[{Slot:0b,id:"minecraft:stone",count:1b}]}""")
        sandbox.executeCommand("""bossbar add demo:stored {"text":"Stored"}""")
        sandbox.executeCommand("scoreboard objectives add runs dummy")
        sandbox.executeCommand("scoreboard objectives add success dummy")
        sandbox.executeCommand("execute store result score Steve runs run random value 3..3")
        sandbox.executeCommand("execute store result storage demo:store value int 2 run random value 3..3")
        sandbox.executeCommand("execute store result entity @e[tag=rider,limit=1] Health double 2 run random value 2..2")
        sandbox.executeCommand("execute store result block 2 0 0 Items[0].count int 3 run random value 2..2")
        sandbox.executeCommand("execute store result bossbar demo:stored value run random value 7..7")
        sandbox.executeCommand("execute store success bossbar demo:stored max run random value 1..1")
        sandbox.executeCommand("execute store success score Steve success run random value 1..1")
        sandbox.executeCommand("execute if entity @e[type=minecraft:skeleton] store success score #none success run random value 1..1")

        assertEquals(ResourceLocation.parse("minecraft:stone"), sandbox.world.requireBlock(BlockPos(0, 1, 0)).id)
        assertEquals(ResourceLocation.parse("minecraft:stone"), sandbox.world.requireBlock(BlockPos(1, 1, 0)).id)
        val cloneOutput = sandbox.world.outputs.single { it.command == "clone" }
        val clonePayload = cloneOutput.payload?.asJsonObject ?: error("missing clone payload")
        assertEquals("2", cloneOutput.text)
        assertEquals(listOf("0 1 0", "1 1 0"), cloneOutput.targets)
        assertEquals("replace", clonePayload.get("maskMode").asString)
        assertEquals("normal", clonePayload.get("cloneMode").asString)
        assertEquals(2, clonePayload.get("copied").asInt)
        assertEquals(2, clonePayload.get("changed").asInt)
        assertEquals(0, clonePayload.getAsJsonObject("from").get("x").asInt)
        assertEquals(1, clonePayload.getAsJsonObject("to").get("x").asInt)
        assertEquals(0, clonePayload.getAsJsonObject("destination").get("x").asInt)
        assertEquals("0 1 0", clonePayload.getAsJsonArray("copiedPositions")[0].asString)
        val pig = sandbox.world.entities.single { "rider" in it.tags }
        val cow = sandbox.world.entities.single { "vehicle" in it.tags }
        assertEquals(cow.uuid, pig.vehicle)
        assertTrue(pig.uuid in cow.passengers)
        val rideOutput = sandbox.world.outputs.single { it.command == "ride mount" }
        val ridePayload = rideOutput.payload?.asJsonObject ?: error("missing ride mount payload")
        val riderPayload = ridePayload.getAsJsonArray("riders")[0].asJsonObject
        assertEquals("1", rideOutput.text)
        assertEquals(listOf(pig.uuid), rideOutput.targets)
        assertEquals("mount", ridePayload.get("action").asString)
        assertEquals(cow.uuid, ridePayload.get("vehicleUuid").asString)
        assertEquals("minecraft:cow", ridePayload.get("vehicleType").asString)
        assertEquals(pig.uuid, riderPayload.get("uuid").asString)
        assertEquals(cow.uuid, riderPayload.get("vehicleUuid").asString)
        assertEquals(90.0, pig.yaw)
        assertEquals(15.0, pig.pitch)
        assertEquals(4.0, pig.fullNbt().get("Health").asDouble)
        assertEquals(ResourceLocation.parse("minecraft:diamond_sword"), sandbox.world.requirePlayer("Steve").inventory[0].id)
        assertEquals(3, sandbox.world.getScore("Steve", "runs"))
        assertEquals(1, sandbox.world.getScore("Steve", "success"))
        assertEquals(0, sandbox.world.getScore("#none", "success"))
        assertEquals(6L, JsonPaths.get(sandbox.world.storage(ResourceLocation.parse("demo:store")), "value")?.asLong)
        assertEquals(
            6,
            sandbox.world.requireBlock(BlockPos(2, 0, 0)).fullNbt(BlockPos(2, 0, 0), sandbox.profile)
                .getAsJsonArray("Items")[0].asJsonObject.get("count").asInt,
        )
        assertEquals(7, sandbox.world.bossbars[ResourceLocation.parse("demo:stored")]?.value)
        assertEquals(1, sandbox.world.bossbars[ResourceLocation.parse("demo:stored")]?.max)
        assertTrue(sandbox.world.outputs.any { it.command == "random value" && it.text == "3" })
    }

    @Test
    fun `random default sequences include world seed`() {
        fun randomValue(seed: Long): String {
            val sandbox = createSandbox("26.1.2", listOf(fixturePack()), world = SandboxWorld().apply { this.seed = seed })
            sandbox.executeCommand("random value 0..1000000")
            return sandbox.world.outputs.single { it.command == "random value" }.text.orEmpty()
        }

        assertEquals(randomValue(123), randomValue(123))
        assertNotEquals(randomValue(123), randomValue(456))
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
    fun `records command traces and output source metadata`() {
        val sandbox = createFunctionSandboxFromString(
            version = "26.2",
            functionText = """
            say traced
            scoreboard objectives add runs dummy
            scoreboard players set #trace runs 1
            """.trimIndent(),
            functionId = "demo:main",
            sourceName = "<trace-test>",
        )

        sandbox.runFunction("demo:main")

        assertEquals(3, sandbox.world.traces.size)
        val first = sandbox.world.traces.first()
        assertEquals("say traced", first.command)
        assertEquals("say", first.root)
        assertTrue(first.success)
        assertEquals(1, first.outputs)
        assertEquals("<trace-test>", first.source?.file)
        assertEquals(1, first.source?.line)
        assertEquals("demo:main", first.source?.functionStack?.singleOrNull()?.id.toString())
        assertEquals(first.source, sandbox.world.outputs.single().source)
        assertEquals(3, sandbox.snapshotJson().getAsJsonArray("traces").size())
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
        val output = sandbox.world.outputs.single { it.command == "summon" }
        val payload = output.payload?.asJsonObject ?: error("missing summon payload")
        val position = payload.getAsJsonObject("position")
        assertEquals("1", output.text)
        assertEquals(listOf(entity.uuid), output.targets)
        assertEquals(entity.uuid, payload.get("uuid").asString)
        assertEquals("minecraft:zombie", payload.get("type").asString)
        assertEquals("minecraft:overworld", payload.get("dimension").asString)
        assertEquals(1.0, position.get("x").asDouble)
        assertEquals(2.0, position.get("y").asDouble)
        assertEquals(3.0, position.get("z").asDouble)
        assertTrue(payload.getAsJsonArray("tags").any { it.asString == "mob" })
        assertEquals(20.0, payload.getAsJsonObject("nbt").get("Health").asDouble)
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
        val teleportOutput = sandbox.world.outputs.single { it.command == "tp" }
        val teleportPayload = teleportOutput.payload?.asJsonObject ?: error("missing teleport payload")
        val moved = teleportPayload.getAsJsonArray("targets")[0].asJsonObject
        val to = moved.getAsJsonObject("to")
        assertEquals("1", teleportOutput.text)
        assertEquals(listOf("Steve"), teleportOutput.targets)
        assertEquals(1, teleportPayload.get("count").asInt)
        assertEquals("Steve", moved.get("target").asString)
        assertEquals("minecraft:overworld", moved.get("fromDimension").asString)
        assertEquals("minecraft:overworld", moved.get("toDimension").asString)
        assertEquals(10.0, to.get("x").asDouble)
        assertEquals(64.0, to.get("y").asDouble)
        assertEquals(-2.0, to.get("z").asDouble)
        assertEquals("get", sandbox.world.outputs.last { it.command == "get" }.command)

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
        sandbox.executeCommand("""data modify block 0 64 0 Items[{id:"minecraft:stone"}].count set value 3b""")
        sandbox.executeCommand("execute if block 0 64 0 minecraft:chest[facing=north] run setblock 1 64 0 minecraft:stone")

        val chest = sandbox.world.requireBlock(BlockPos(0, 64, 0))
        assertEquals(ResourceLocation.parse("minecraft:chest"), chest.id)
        assertEquals("north", chest.properties["facing"])
        assertEquals(2, chest.nbt.getAsJsonArray("Items").size())
        assertEquals(3, JsonPaths.get(chest.fullNbt(BlockPos(0, 64, 0), sandbox.profile), """Items[{id:"minecraft:stone"}].count""")?.asInt)
        assertEquals(ResourceLocation.parse("minecraft:stone"), sandbox.world.requireBlock(BlockPos(1, 64, 0)).id)
        val setblockOutputs = sandbox.world.outputs.filter { it.command == "setblock" }
        assertEquals(2, setblockOutputs.size)
        val chestPayload = setblockOutputs[0].payload?.asJsonObject ?: error("missing setblock payload")
        val chestAfter = chestPayload.getAsJsonObject("after")
        val chestPos = chestPayload.getAsJsonObject("pos")
        assertEquals("1", setblockOutputs[0].text)
        assertEquals(listOf("0 64 0"), setblockOutputs[0].targets)
        assertEquals(true, chestPayload.get("changed").asBoolean)
        assertEquals("replace", chestPayload.get("mode").asString)
        assertEquals(0, chestPos.get("x").asInt)
        assertEquals(64, chestPos.get("y").asInt)
        assertEquals(0, chestPos.get("z").asInt)
        assertEquals("minecraft:chest", chestAfter.get("id").asString)
        assertEquals("north", chestAfter.getAsJsonObject("properties").get("facing").asString)
        assertTrue(chestAfter.getAsJsonObject("nbt").has("Items"))
        val stonePayload = setblockOutputs[1].payload?.asJsonObject ?: error("missing conditional setblock payload")
        assertEquals(listOf("1 64 0"), setblockOutputs[1].targets)
        assertEquals("minecraft:stone", stonePayload.getAsJsonObject("after").get("id").asString)
    }

    @Test
    fun `data modify copies values from source paths`() {
        val sandbox = createSandbox("26.1.2", listOf(fixturePack()))

        sandbox.executeCommand("""data merge storage demo:src {value:{foo:1},list:[1,2],merge:{a:1}}""")
        sandbox.executeCommand("data modify storage demo:dst copied set from storage demo:src value")
        sandbox.executeCommand("data modify storage demo:dst list set value []")
        sandbox.executeCommand("data modify storage demo:dst list append from storage demo:src value")
        sandbox.executeCommand("data modify storage demo:dst list prepend from storage demo:src merge")
        sandbox.executeCommand("data modify storage demo:dst list insert 1 from storage demo:src value.foo")
        sandbox.executeCommand("data modify storage demo:dst merged merge from storage demo:src merge")

        val dst = sandbox.world.storage(ResourceLocation.parse("demo:dst"))
        assertEquals(1, JsonPaths.get(dst, "copied.foo")?.asInt)
        assertEquals(1, JsonPaths.get(dst, "merged.a")?.asInt)
        val list = JsonPaths.get(dst, "list")?.asJsonArray ?: error("missing list")
        assertEquals(3, list.size())
        assertEquals(1, list[1].asInt)
        assertEquals(1, list[2].asJsonObject.get("foo").asInt)
        val mergeOutput = sandbox.world.outputs.single { it.command == "data merge" }
        val mergePayload = mergeOutput.payload?.asJsonObject ?: error("missing data merge payload")
        val mergeResult = mergePayload.getAsJsonArray("results")[0].asJsonObject
        assertEquals("1", mergeOutput.text)
        assertEquals(listOf("demo:src"), mergeOutput.targets)
        assertEquals("storage", mergePayload.get("targetKind").asString)
        assertEquals(1, mergePayload.get("changed").asInt)
        assertEquals(1, mergePayload.get("count").asInt)
        assertTrue(mergePayload.getAsJsonObject("value").has("value"))
        assertEquals(true, mergeResult.get("changed").asBoolean)
        assertEquals("demo:src", mergeResult.get("target").asString)
        assertEquals(1, mergeResult.getAsJsonObject("after").getAsJsonObject("value").get("foo").asInt)
        val modifyOutputs = sandbox.world.outputs.filter { it.command == "data modify" }
        val mergedOutput = modifyOutputs.last()
        val mergedPayload = mergedOutput.payload?.asJsonObject ?: error("missing data modify payload")
        val mergedDetails = mergedPayload.getAsJsonObject("details")
        val mergedResult = mergedPayload.getAsJsonArray("results")[0].asJsonObject
        assertEquals(6, modifyOutputs.size)
        assertEquals("1", mergedOutput.text)
        assertEquals(listOf("demo:dst"), mergedOutput.targets)
        assertEquals("storage", mergedPayload.get("targetKind").asString)
        assertEquals("merge", mergedDetails.get("operation").asString)
        assertEquals("merged", mergedDetails.get("path").asString)
        assertEquals(1, mergedPayload.getAsJsonObject("value").get("a").asInt)
        assertEquals(true, mergedResult.get("changed").asBoolean)
        assertEquals(1, mergedResult.getAsJsonObject("after").getAsJsonObject("merged").get("a").asInt)

        val error = assertFailsWith<SandboxException> {
            sandbox.executeCommand("data modify storage demo:dst missing set from storage demo:src nope")
        }
        assertEquals(DiagnosticCode.COMMAND_ERROR, error.code)
    }

    @Test
    fun `data paths support negative list indexes`() {
        val sandbox = createSandbox("26.1.2", listOf(fixturePack()))

        sandbox.executeCommand("""data merge storage demo:path {list:[{id:"first",count:1},{id:"last",count:2}]}""")
        sandbox.executeCommand("data modify storage demo:path list[-1].count set value 5")
        sandbox.executeCommand("data modify storage demo:path beforeLast set from storage demo:path list[-2].id")
        sandbox.executeCommand("data remove storage demo:path list[-2]")

        val storage = sandbox.world.storage(ResourceLocation.parse("demo:path"))
        assertEquals("first", JsonPaths.get(storage, "beforeLast")?.asString)
        val list = JsonPaths.get(storage, "list")?.asJsonArray ?: error("missing list")
        assertEquals(1, list.size())
        assertEquals("last", list[0].asJsonObject.get("id").asString)
        assertEquals(5, list[0].asJsonObject.get("count").asInt)
        val removeOutput = sandbox.world.outputs.single { it.command == "data remove" }
        val removePayload = removeOutput.payload?.asJsonObject ?: error("missing data remove payload")
        val removeDetails = removePayload.getAsJsonObject("details")
        val removeResult = removePayload.getAsJsonArray("results")[0].asJsonObject
        assertEquals("1", removeOutput.text)
        assertEquals(listOf("demo:path"), removeOutput.targets)
        assertEquals("storage", removePayload.get("targetKind").asString)
        assertEquals("remove", removeDetails.get("operation").asString)
        assertEquals("list[-2]", removeDetails.get("path").asString)
        assertEquals(true, removeResult.get("changed").asBoolean)
        assertEquals(1, removeResult.getAsJsonObject("after").getAsJsonArray("list").size())

        val error = assertFailsWith<SandboxException> {
            sandbox.executeCommand("data modify storage demo:path list[-2].count set value 9")
        }
        assertEquals(DiagnosticCode.COMMAND_ERROR, error.code)
    }

    @Test
    fun `data get scales numeric path output`() {
        val sandbox = createSandbox("26.1.2", listOf(fixturePack()))

        sandbox.executeCommand("""data merge storage demo:src {value:2.5,name:"Alpha"}""")
        sandbox.executeCommand("scoreboard objectives add data dummy")
        sandbox.executeCommand("data get storage demo:src value 4")
        sandbox.executeCommand("execute store result score #scaled data run data get storage demo:src value 3")

        val output = sandbox.world.outputs.first { it.command == "get" }
        assertEquals(10.0, output.payload?.asDouble)
        assertEquals(7, sandbox.world.getScore("#scaled", "data"))

        val error = assertFailsWith<SandboxException> {
            sandbox.executeCommand("data get storage demo:src name 2")
        }
        assertEquals(DiagnosticCode.COMMAND_ERROR, error.code)
    }

    @Test
    fun `data modify copies string slices from source paths`() {
        val sandbox = createSandbox("26.1.2", listOf(fixturePack()))

        sandbox.executeCommand("""data merge storage demo:src {name:"AlphaBeta"}""")
        sandbox.executeCommand("data modify storage demo:dst full set string storage demo:src name")
        sandbox.executeCommand("data modify storage demo:dst middle set string storage demo:src name 1 5")
        sandbox.executeCommand("data modify storage demo:dst tail set string storage demo:src name -4 -1")
        sandbox.executeCommand("data modify storage demo:dst list set value []")
        sandbox.executeCommand("data modify storage demo:dst list append string storage demo:src name 5")

        val dst = sandbox.world.storage(ResourceLocation.parse("demo:dst"))
        assertEquals("AlphaBeta", JsonPaths.get(dst, "full")?.asString)
        assertEquals("lpha", JsonPaths.get(dst, "middle")?.asString)
        assertEquals("Bet", JsonPaths.get(dst, "tail")?.asString)
        assertEquals("Beta", JsonPaths.get(dst, "list[0]")?.asString)

        val error = assertFailsWith<SandboxException> {
            sandbox.executeCommand("data modify storage demo:dst missing set string storage demo:src nope")
        }
        assertEquals(DiagnosticCode.COMMAND_ERROR, error.code)
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
