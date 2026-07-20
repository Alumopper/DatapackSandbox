package moe.afox.dpsandbox.core

import java.nio.file.Files
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
        assertEquals(
            "adventure",
            snapshot
                .getAsJsonObject("players")
                .getAsJsonObject("Steve")
                .get("gameMode")
                .asString,
        )
        assertEquals(
            "creative",
            snapshot
                .getAsJsonObject("players")
                .getAsJsonObject("Builder")
                .get("gameMode")
                .asString,
        )
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
        assertEquals(
            1.0,
            snapshot
                .getAsJsonObject("players")
                .getAsJsonObject("Steve")
                .getAsJsonObject("spawnPoint")
                .get("x")
                .asDouble,
        )
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
    fun `datapack list exposes missing resource diagnostics`() {
        val pack = writeMissingFunctionTagPack(Files.createTempDirectory("dps-missing-function-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))

        sandbox.executeCommand("datapack list")

        val payload =
            sandbox.world.outputs
                .single { it.command == "datapack list" }
                .payload
                ?.asJsonObject ?: error("missing datapack list payload")
        assertEquals("all", payload.get("filter").asString)
        assertEquals(1, payload.get("packCount").asInt)
        assertEquals(listOf(pack.toAbsolutePath().normalize().toString()), payload.getAsJsonArray("packs").map { it.asString })
        val missing = payload.getAsJsonArray("missingReferences")
        assertEquals(1, missing.size())
        val reference = missing[0].asJsonObject
        assertEquals("#minecraft:load", reference.get("source").asString)
        assertEquals("function", reference.get("type").asString)
        assertEquals("demo:missing_load", reference.get("id").asString)
        assertTrue(payload.has("resourceOverrides"))
    }

    @Test
    fun `datapack list accepts vanilla filters and rejects invalid filters`() {
        val pack = writeMissingFunctionTagPack(Files.createTempDirectory("dps-filtered-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))

        sandbox.executeCommand("datapack list enabled")
        sandbox.executeCommand("datapack list available")

        val payloads =
            sandbox.world.outputs
                .filter { it.command == "datapack list" }
                .map { it.payload?.asJsonObject ?: error("missing datapack list payload") }
        assertEquals(listOf("enabled", "available"), payloads.map { it.get("filter").asString })
        payloads.forEach { payload ->
            assertEquals(1, payload.get("packCount").asInt)
            assertEquals(listOf(pack.toAbsolutePath().normalize().toString()), payload.getAsJsonArray("packs").map { it.asString })
            assertEquals(1, payload.getAsJsonArray("missingReferences").size())
        }

        val error =
            assertFailsWith<SandboxException> {
                sandbox.executeCommand("datapack list disabled")
            }
        assertEquals(DiagnosticCode.INPUT_FORMAT, error.code)
        assertTrue(error.message.contains("datapack list [available|enabled]"), error.message)
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
        sandbox.executeCommand("datapack enable file/demo first")
        sandbox.executeCommand("datapack disable file/demo")
        sandbox.executeCommand("ban Steve generated reason")
        sandbox.executeCommand("ban-ip 127.0.0.1 generated ip reason")
        sandbox.executeCommand("banlist ips")
        sandbox.executeCommand("debug start")
        sandbox.executeCommand("deop Steve")
        sandbox.executeCommand("jfr start")
        sandbox.executeCommand("kick Steve generated kick reason")
        sandbox.executeCommand("op Steve")
        sandbox.executeCommand("pardon Steve")
        sandbox.executeCommand("pardon-ip 127.0.0.1")
        sandbox.executeCommand("perf start")
        sandbox.executeCommand("publish true creative 25566")
        sandbox.executeCommand("reload")
        sandbox.executeCommand("save-all flush")
        sandbox.executeCommand("save-off")
        sandbox.executeCommand("save-on")
        sandbox.executeCommand("setidletimeout 10")
        sandbox.executeCommand("stop")
        sandbox.executeCommand("whitelist add Steve")
        sandbox.executeCommand("whitelist list")
        sandbox.executeCommand("scoreboard objectives add trig trigger")
        sandbox.executeCommand("trigger trig set 4")

        assertEquals(4, sandbox.world.getScore("Steve", "trig"))
        assertTrue(sandbox.world.outputs.any { it.command == "help" && it.text.contains("gamemode") })
        assertTrue(sandbox.world.outputs.any { it.command == "list" && it.text.contains("Steve") })
        assertTrue(
            sandbox.world.outputs.any {
                it.command == "locate" &&
                    it.payload
                        ?.asJsonObject
                        ?.get("found")
                        ?.asBoolean == false
            },
        )
        val placeStructure = sandbox.world.outputs.single { it.command == "place structure" }
        val placeStructurePayload = placeStructure.payload?.asJsonObject ?: error("missing place structure payload")
        assertEquals("worldgen", placeStructure.channel)
        assertEquals("structure:demo:ruin", placeStructure.text)
        assertEquals(false, placeStructurePayload.get("placed").asBoolean)
        assertEquals(1.0, placeStructurePayload.getAsJsonObject("position").get("x").asDouble)
        assertEquals(64.0, placeStructurePayload.getAsJsonObject("position").get("y").asDouble)
        assertEquals(2.0, placeStructurePayload.getAsJsonObject("position").get("z").asDouble)
        val placeTemplatePayload =
            sandbox.world.outputs
                .single { it.command == "place template" }
                .payload
                ?.asJsonObject
                ?: error("missing place template payload")
        assertEquals("demo:room", placeTemplatePayload.get("id").asString)
        assertEquals(1.0, placeTemplatePayload.getAsJsonObject("position").get("y").asDouble)
        assertEquals(listOf("clockwise_90", "front_back", "0.5", "123"), placeTemplatePayload.getAsJsonArray("extra").map { it.asString })
        assertTrue(sandbox.world.outputs.any { it.command == "datapack list" })
        val datapackEnable = sandbox.world.outputs.single { it.command == "datapack enable" }
        val datapackEnablePayload = datapackEnable.payload?.asJsonObject ?: error("missing datapack enable payload")
        assertEquals("warning", datapackEnable.channel)
        assertEquals("enable", datapackEnablePayload.get("action").asString)
        assertEquals("file/demo", datapackEnablePayload.get("name").asString)
        assertEquals(true, datapackEnablePayload.get("noOp").asBoolean)
        assertEquals(listOf("first"), datapackEnablePayload.getAsJsonArray("arguments").map { it.asString })
        val datapackDisablePayload =
            sandbox.world.outputs
                .single { it.command == "datapack disable" }
                .payload
                ?.asJsonObject ?: error("missing datapack disable payload")
        assertEquals("disable", datapackDisablePayload.get("action").asString)
        assertEquals("file/demo", datapackDisablePayload.get("name").asString)
        assertEquals(true, datapackDisablePayload.get("noOp").asBoolean)
        listOf("ban", "ban-ip", "banlist", "deop", "kick", "op", "pardon", "pardon-ip", "whitelist add", "whitelist list")
            .forEach { command ->
                val output = sandbox.world.outputs.single { it.command == command }
                val payload = output.payload?.asJsonObject ?: error("missing $command payload")
                assertEquals("debug", output.channel)
                assertEquals(true, payload.get("noOp").asBoolean)
            }
        assertEquals(
            "generated reason",
            sandbox.world.outputs
                .single { it.command == "ban" }
                .payload
                ?.asJsonObject
                ?.get("message")
                ?.asString,
        )
        assertEquals(
            "ips",
            sandbox.world.outputs
                .single { it.command == "banlist" }
                .payload
                ?.asJsonObject
                ?.get("filter")
                ?.asString,
        )
        assertEquals(
            "Steve",
            sandbox.world.outputs
                .single { it.command == "whitelist add" }
                .payload
                ?.asJsonObject
                ?.get("target")
                ?.asString,
        )
        listOf("debug", "jfr", "perf").forEach { command ->
            val profilingOutput = sandbox.world.outputs.single { it.command == command }
            val profilingPayload = profilingOutput.payload?.asJsonObject ?: error("missing $command payload")
            assertEquals("debug", profilingOutput.channel)
            assertEquals("start", profilingPayload.get("action").asString)
            assertEquals(true, profilingPayload.get("noOp").asBoolean)
        }
        val publishPayload =
            sandbox.world.outputs
                .single { it.command == "publish" }
                .payload
                ?.asJsonObject
                ?: error("missing publish payload")
        assertEquals(
            "debug",
            sandbox.world.outputs
                .single { it.command == "publish" }
                .channel,
        )
        assertEquals(true, publishPayload.get("allowCommands").asBoolean)
        assertEquals("creative", publishPayload.get("gamemode").asString)
        assertEquals(25566, publishPayload.get("port").asInt)
        assertEquals(true, publishPayload.get("noOp").asBoolean)
        val reloadPayload =
            sandbox.world.outputs
                .single { it.command == "reload" }
                .payload
                ?.asJsonObject
                ?: error("missing reload payload")
        assertEquals(true, reloadPayload.get("noOp").asBoolean)
        assertEquals("reload", reloadPayload.get("action").asString)
        assertEquals(
            true,
            sandbox.world.outputs
                .single { it.command == "save-all" }
                .payload
                ?.asJsonObject
                ?.get("flush")
                ?.asBoolean,
        )
        assertEquals(
            true,
            sandbox.world.outputs
                .single { it.command == "save-off" }
                .payload
                ?.asJsonObject
                ?.get("noOp")
                ?.asBoolean,
        )
        assertEquals(
            true,
            sandbox.world.outputs
                .single { it.command == "save-on" }
                .payload
                ?.asJsonObject
                ?.get("noOp")
                ?.asBoolean,
        )
        assertEquals(
            10,
            sandbox.world.outputs
                .single { it.command == "setidletimeout" }
                .payload
                ?.asJsonObject
                ?.get("minutes")
                ?.asInt,
        )
        assertEquals(
            true,
            sandbox.world.outputs
                .single { it.command == "stop" }
                .payload
                ?.asJsonObject
                ?.get("noOp")
                ?.asBoolean,
        )
        val publishError =
            assertFailsWith<SandboxException> {
                sandbox.executeCommand("publish true creative 70000")
            }
        assertEquals(DiagnosticCode.INPUT_FORMAT, publishError.code)
        assertTrue(publishError.message.contains("publish port"), publishError.message)
        val whitelistError =
            assertFailsWith<SandboxException> {
                sandbox.executeCommand("whitelist add")
            }
        assertEquals(DiagnosticCode.INPUT_FORMAT, whitelistError.code)
        val idleTimeoutError =
            assertFailsWith<SandboxException> {
                sandbox.executeCommand("setidletimeout -1")
            }
        assertEquals(DiagnosticCode.INPUT_FORMAT, idleTimeoutError.code)
    }

    @Test
    fun `chat commands expose loaded chat type metadata`() {
        val sandbox = createSandbox("26.2", listOf(writeChatTypePack(Files.createTempDirectory("dps-chat-type-pack"))))

        sandbox.executeCommand("say generated chat")

        val payload =
            sandbox.world.outputs
                .single { it.command == "say" }
                .payload
                ?.asJsonObject ?: error("missing say payload")
        assertEquals("minecraft:say_command", payload.get("chatType").asString)
        val chatType = payload.getAsJsonObject("chatTypeResource")
        assertEquals("minecraft:say_command", chatType.get("id").asString)
        assertEquals("26.2", chatType.get("version").asString)
        assertEquals("chat.type.sandbox_say", chatType.getAsJsonObject("chat").get("translation_key").asString)
        assertEquals("sender", chatType.getAsJsonObject("chat").getAsJsonArray("parameters")[0].asString)
        assertEquals("content", chatType.getAsJsonObject("chat").getAsJsonArray("parameters")[1].asString)
        assertEquals("chat.type.sandbox_say.narrate", chatType.getAsJsonObject("narration").get("translation_key").asString)
    }

    @Test
    fun `dimension aware command outputs expose loaded dimension metadata`() {
        val sandbox = createSandbox("26.2", listOf(writeDimensionPack(Files.createTempDirectory("dps-dimension-pack"))))

        sandbox.executeCommand("""execute in demo:debug run summon minecraft:marker 1 2 3 {Tags:["dimension_debug"]}""")
        sandbox.executeCommand("""summon minecraft:pig 0 0 0 {Tags:["traveler"]}""")
        sandbox.executeCommand("""tp @e[tag=traveler,limit=1] @e[tag=dimension_debug,limit=1]""")

        val summonPayload =
            sandbox.world.outputs
                .filter { it.command == "summon" }
                .mapNotNull { it.payload?.asJsonObject }
                .single { it.get("dimension").asString == "demo:debug" }
        val dimension = summonPayload.getAsJsonObject("dimensionResource")
        assertEquals("demo:debug", dimension.get("id").asString)
        assertEquals("demo:debug_type", dimension.get("type").asString)
        assertEquals(
            "minecraft:noise",
            dimension
                .getAsJsonObject("definition")
                .getAsJsonObject("generator")
                .get("type")
                .asString,
        )
        val dimensionType = dimension.getAsJsonObject("dimensionType")
        assertEquals("demo:debug_type", dimensionType.get("id").asString)
        assertEquals(false, dimensionType.getAsJsonObject("definition").get("natural").asBoolean)
        assertEquals(0.25, dimensionType.getAsJsonObject("definition").get("ambient_light").asDouble)

        val teleportEntry =
            sandbox.world.outputs
                .single { it.command == "tp" }
                .payload
                ?.asJsonObject
                ?.getAsJsonArray("targets")
                ?.get(0)
                ?.asJsonObject
                ?: error("missing teleport target payload")
        assertEquals("demo:debug", teleportEntry.get("toDimension").asString)
        assertEquals("demo:debug", teleportEntry.getAsJsonObject("toDimensionResource").get("id").asString)
        assertEquals(
            "demo:debug_type",
            teleportEntry
                .getAsJsonObject("toDimensionResource")
                .getAsJsonObject("dimensionType")
                .get("id")
                .asString,
        )
    }

    @Test
    fun `enchant command exposes loaded enchantment metadata`() {
        val sandbox = createSandbox("26.2", listOf(writeEnchantmentPack(Files.createTempDirectory("dps-enchantment-pack"))))

        sandbox.executeCommand("give Steve minecraft:diamond_sword")
        sandbox.executeCommand("enchant Steve demo:debug_edge 4")

        val payload =
            sandbox.world.outputs
                .single { it.command == "enchant" }
                .payload
                ?.asJsonObject ?: error("missing enchant payload")
        assertEquals("demo:debug_edge", payload.get("enchantment").asString)
        assertEquals(4, payload.get("level").asInt)
        val enchantment = payload.getAsJsonObject("enchantmentResource")
        assertEquals("demo:debug_edge", enchantment.get("id").asString)
        assertEquals("26.2", enchantment.get("version").asString)
        val definition = enchantment.getAsJsonObject("definition")
        assertEquals("Debug Edge", definition.getAsJsonObject("description").get("text").asString)
        assertEquals(4, definition.get("max_level").asInt)
        assertEquals("mainhand", definition.getAsJsonArray("slots")[0].asString)
        val outputItem = payload.getAsJsonArray("items")[0].asJsonObject.getAsJsonObject("item")
        assertEquals(
            4,
            outputItem
                .getAsJsonObject("components")
                .getAsJsonObject("minecraft:enchantments")
                .get("demo:debug_edge")
                .asInt,
        )
    }

    @Test
    fun `summon exposes loaded entity variant metadata`() {
        val sandbox = createSandbox("26.2", listOf(writeEntityVariantPack(Files.createTempDirectory("dps-entity-variant-pack"))))

        sandbox.executeCommand("""summon minecraft:painting 0 64 0 {variant:"demo:small_canvas",Tags:["custom_painting"]}""")
        sandbox.executeCommand("""summon minecraft:wolf 1 64 0 {variant:"demo:ashen",sound_variant:"demo:quiet",Tags:["custom_wolf"]}""")

        val paintingPayload =
            sandbox.world.outputs
                .filter { it.command == "summon" }
                .mapNotNull { it.payload?.asJsonObject }
                .single { it.get("type").asString == "minecraft:painting" }
        val paintingVariant = paintingPayload.getAsJsonArray("variantResources")[0].asJsonObject
        assertEquals("painting_variant", paintingVariant.get("type").asString)
        assertEquals("variant", paintingVariant.get("field").asString)
        assertEquals("demo:small_canvas", paintingVariant.get("id").asString)
        assertEquals(2, paintingVariant.getAsJsonObject("definition").get("width").asInt)

        val wolfPayload =
            sandbox.world.outputs
                .filter { it.command == "summon" }
                .mapNotNull { it.payload?.asJsonObject }
                .single { it.get("type").asString == "minecraft:wolf" }
        val wolfVariants =
            wolfPayload
                .getAsJsonArray("variantResources")
                .map { it.asJsonObject }
                .associateBy { it.get("type").asString }
        assertEquals("demo:ashen", wolfVariants.getValue("wolf_variant").get("id").asString)
        assertEquals(
            "demo:entity/wolf/ashen",
            wolfVariants
                .getValue("wolf_variant")
                .getAsJsonObject("definition")
                .get("wild_texture")
                .asString,
        )
        assertEquals("demo:quiet", wolfVariants.getValue("wolf_sound_variant").get("id").asString)
        assertEquals(
            "minecraft:entity.wolf.ambient",
            wolfVariants
                .getValue("wolf_sound_variant")
                .getAsJsonObject("definition")
                .get("ambient_sound")
                .asString,
        )
    }

    @Test
    fun `item outputs expose loaded armor trim metadata`() {
        val sandbox = createSandbox("26.2", listOf(writeTrimPack(Files.createTempDirectory("dps-trim-pack"))))

        sandbox.executeCommand(
            """give Steve minecraft:iron_chestplate[minecraft:trim={material:"demo:debug_gold",pattern:"demo:debug_spire"}]""",
        )

        val item =
            sandbox.world.outputs
                .single { it.command == "give" }
                .payload
                ?.asJsonObject
                ?.getAsJsonObject("item")
                ?: error("missing give item payload")
        val trimResources =
            item
                .getAsJsonArray("trimResources")
                .map { it.asJsonObject }
                .associateBy { it.get("type").asString }
        assertEquals("demo:debug_gold", trimResources.getValue("trim_material").get("id").asString)
        assertEquals(
            "debug_gold",
            trimResources
                .getValue("trim_material")
                .getAsJsonObject("definition")
                .get("asset_name")
                .asString,
        )
        assertEquals("demo:debug_spire", trimResources.getValue("trim_pattern").get("id").asString)
        assertEquals(
            "demo:spire",
            trimResources
                .getValue("trim_pattern")
                .getAsJsonObject("definition")
                .get("asset_id")
                .asString,
        )
    }

    @Test
    fun `item outputs expose loaded component registry metadata`() {
        val sandbox = createSandbox("26.2", listOf(writeItemComponentResourcePack(Files.createTempDirectory("dps-item-component-pack"))))

        sandbox.executeCommand("""give Steve minecraft:goat_horn[minecraft:instrument="demo:debug_horn"]""")
        sandbox.executeCommand("""give Steve minecraft:music_disc_cat[minecraft:jukebox_playable={song:"demo:debug_song"}]""")
        sandbox.executeCommand(
            """give Steve minecraft:white_banner[minecraft:banner_patterns=[{pattern:"demo:debug_banner",color:"red"}]]""",
        )
        sandbox.executeCommand(
            """give Steve minecraft:diamond_chestplate[minecraft:equippable={slot:"chest",asset_id:"demo:debug_armor"}]""",
        )

        val items =
            sandbox.world.outputs
                .filter { it.command == "give" }
                .map { it.payload?.asJsonObject?.getAsJsonObject("item") ?: error("missing give item") }
                .associateBy { it.get("id").asString }
        val instrument =
            items
                .getValue("minecraft:goat_horn")
                .getAsJsonArray("componentResources")[0]
                .asJsonObject
        assertEquals("instrument", instrument.get("type").asString)
        assertEquals("minecraft:instrument", instrument.get("component").asString)
        assertEquals("demo:debug_horn", instrument.get("id").asString)
        assertEquals(24.0, instrument.getAsJsonObject("definition").get("range").asDouble)

        val song =
            items
                .getValue("minecraft:music_disc_cat")
                .getAsJsonArray("componentResources")[0]
                .asJsonObject
        assertEquals("jukebox_song", song.get("type").asString)
        assertEquals("minecraft:jukebox_playable", song.get("component").asString)
        assertEquals("demo:debug_song", song.get("id").asString)
        assertEquals(7, song.getAsJsonObject("definition").get("comparator_output").asInt)

        val banner =
            items
                .getValue("minecraft:white_banner")
                .getAsJsonArray("componentResources")[0]
                .asJsonObject
        assertEquals("banner_pattern", banner.get("type").asString)
        assertEquals("minecraft:banner_patterns", banner.get("component").asString)
        assertEquals(0, banner.get("index").asInt)
        assertEquals("demo:debug_banner", banner.get("id").asString)
        assertEquals("demo:debug_banner", banner.getAsJsonObject("definition").get("asset_id").asString)

        val equipment =
            items
                .getValue("minecraft:diamond_chestplate")
                .getAsJsonArray("componentResources")[0]
                .asJsonObject
        assertEquals("equipment_asset", equipment.get("type").asString)
        assertEquals("minecraft:equippable", equipment.get("component").asString)
        assertEquals("asset_id", equipment.get("field").asString)
        assertEquals("demo:debug_armor", equipment.get("id").asString)
        assertEquals("demo:models/equipment/debug_armor", equipment.getAsJsonObject("definition").get("texture").asString)
    }
}
