package moe.afox.dpsandbox.core

import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerEventsTest {
    @Test
    fun `shorthand changed dimension event carries from and to dimensions`() {
        val criterion = Criterion(
            name = "enter_nether",
            trigger = ResourceLocation.parse("minecraft:changed_dimension"),
            conditions = JsonObject().also {
                it.addProperty("from", "minecraft:overworld")
                it.addProperty("to", "minecraft:the_nether")
            },
        )
        val advancementId = ResourceLocation.parse("demo:nether_trip")
        val sandbox = sandboxWithAdvancement(advancementId, criterion)
        val player = sandbox.createPlayer("Steve")
        val event = PlayerEvents.shorthand(
            playerName = "Steve",
            type = "changed-dimension",
            id = "minecraft:overworld",
            action = "minecraft:the_nether",
        )

        val updates = sandbox.handlePlayerEvent(event)

        assertEquals(ResourceLocation.parse("minecraft:overworld"), event.fromDimension)
        assertEquals(ResourceLocation.parse("minecraft:the_nether"), event.toDimension)
        assertEquals(1, updates.size)
        assertTrue(player.advancementProgress.getValue(advancementId).criteria.getValue("enter_nether"))
    }

    @Test
    fun `kill command from player context fires killed entity advancement event`() {
        val criterion = Criterion(
            name = "kill_zombie",
            trigger = ResourceLocation.parse("minecraft:player_killed_entity"),
            conditions = JsonObject().also { condition ->
                condition.add(
                    "entity",
                    JsonObject().also {
                        it.addProperty("type", "minecraft:zombie")
                    },
                )
            },
        )
        val advancementId = ResourceLocation.parse("demo:kill_zombie")
        val sandbox = sandboxWithAdvancement(advancementId, criterion)
        val player = sandbox.createPlayer("Steve")
        sandbox.executeCommand("summon minecraft:zombie 0 64 0")

        sandbox.executeCommand(
            "kill @e[type=minecraft:zombie]",
            context = ExecutionContext(entity = player, position = player.position),
        )

        assertTrue(sandbox.world.entities.none { it.type == ResourceLocation.parse("minecraft:zombie") })
        assertTrue(player.advancementProgress.getValue(advancementId).criteria.getValue("kill_zombie"))
        val output = sandbox.world.outputs.single { it.command == "kill" }
        val payload = output.payload?.asJsonObject ?: error("missing kill payload")
        val killed = payload.getAsJsonArray("targets")[0].asJsonObject
        assertEquals("1", output.text)
        assertEquals(1, payload.get("count").asInt)
        assertEquals("minecraft:zombie", killed.get("type").asString)
        assertEquals("minecraft:overworld", killed.get("dimension").asString)
        assertEquals(false, killed.get("player").asBoolean)
    }

    @Test
    fun `shorthand entity interacted event triggers interaction advancements`() {
        val criterion = Criterion(
            name = "talk_to_villager",
            trigger = ResourceLocation.parse("minecraft:player_interacted_with_entity"),
            conditions = JsonObject().also { condition ->
                condition.add(
                    "entity",
                    JsonObject().also {
                        it.addProperty("type", "minecraft:villager")
                    },
                )
            },
        )
        val advancementId = ResourceLocation.parse("demo:talk_to_villager")
        val sandbox = sandboxWithAdvancement(advancementId, criterion)
        val player = sandbox.createPlayer("Steve")

        val updates = sandbox.handlePlayerEvent(PlayerEvents.shorthand("Steve", "entity_interacted", "minecraft:villager"))

        assertEquals(1, updates.size)
        assertTrue(player.advancementProgress.getValue(advancementId).criteria.getValue("talk_to_villager"))
    }

    @Test
    fun `roadmap event names trigger matching advancement aliases`() {
        val placedId = ResourceLocation.parse("demo:placed_stone")
        val brokenId = ResourceLocation.parse("demo:broke_nest")
        val killedId = ResourceLocation.parse("demo:killed_zombie")
        val sandbox = DatapackSandbox(
            profile = VersionProfiles.default,
            datapack = Datapack(
                functions = emptyMap(),
                loadFunctions = emptyList(),
                tickFunctions = emptyList(),
                advancements = mapOf(
                    placedId to advancement(
                        placedId,
                        "placed_stone",
                        "minecraft:placed_block",
                        JsonObject().also { it.addProperty("block", "minecraft:stone") },
                    ),
                    brokenId to advancement(
                        brokenId,
                        "broke_nest",
                        "minecraft:bee_nest_destroyed",
                        JsonObject().also { it.addProperty("block", "minecraft:bee_nest") },
                    ),
                    killedId to advancement(
                        killedId,
                        "killed_zombie",
                        "minecraft:player_killed_entity",
                        JsonObject().also { condition ->
                            condition.add(
                                "entity",
                                JsonObject().also { it.addProperty("type", "minecraft:zombie") },
                            )
                        },
                    ),
                ),
            ),
        )
        val player = sandbox.createPlayer("Steve")

        sandbox.handlePlayerEvent(PlayerEvents.shorthand("Steve", "block_placed", "minecraft:stone"))
        sandbox.handlePlayerEvent(PlayerEvents.shorthand("Steve", "block_broken", "minecraft:bee_nest"))
        sandbox.handlePlayerEvent(PlayerEvents.shorthand("Steve", "entity_killed", "minecraft:zombie"))

        assertTrue(player.advancementProgress.getValue(placedId).criteria.getValue("placed_stone"))
        assertTrue(player.advancementProgress.getValue(brokenId).criteria.getValue("broke_nest"))
        assertTrue(player.advancementProgress.getValue(killedId).criteria.getValue("killed_zombie"))
        assertEquals(listOf("block_placed", "block_broken", "entity_killed"), sandbox.world.playerEventTraces.map { it.type })
        assertTrue(sandbox.world.playerEventTraces.all { it.success })
    }

    @Test
    fun `shorthand damage event triggers hurt player advancements`() {
        val criterion = Criterion(
            name = "fell_far",
            trigger = ResourceLocation.parse("minecraft:entity_hurt_player"),
            conditions = JsonObject().also { condition ->
                condition.add(
                    "damage",
                    JsonObject().also { damage ->
                        damage.add(
                            "type",
                            JsonObject().also { it.addProperty("type", "minecraft:fall") },
                        )
                        damage.add(
                            "dealt",
                            JsonObject().also { it.addProperty("min", 4.0) },
                        )
                    },
                )
            },
        )
        val advancementId = ResourceLocation.parse("demo:fall_damage")
        val sandbox = sandboxWithAdvancement(advancementId, criterion)
        val player = sandbox.createPlayer("Steve")
        val event = PlayerEvents.shorthand("Steve", "damage", "minecraft:fall", "4.5")

        val updates = sandbox.handlePlayerEvent(event)

        assertEquals(ResourceLocation.parse("minecraft:fall"), event.damageSource)
        assertEquals(4.5, event.damageAmount)
        assertEquals(1, updates.size)
        assertTrue(player.advancementProgress.getValue(advancementId).criteria.getValue("fell_far"))
        val trace = sandbox.world.playerEventTraces.single()
        assertEquals("damage", trace.type)
        assertTrue(trace.success)
        assertEquals(ResourceLocation.parse("minecraft:fall"), trace.damageSource)
        assertEquals(advancementId, trace.advancements.single().advancement)
        assertEquals(1, sandbox.snapshotJson().getAsJsonArray("playerEventTraces").size())
    }

    @Test
    fun `player event trace expectations filter recorded event context`() {
        val sandbox = DatapackSandbox(
            profile = VersionProfiles.default,
            datapack = Datapack(
                functions = emptyMap(),
                loadFunctions = emptyList(),
                tickFunctions = emptyList(),
            ),
        )
        sandbox.createPlayer("Steve")

        sandbox.handlePlayerEvent(PlayerEvents.shorthand("Steve", "item_used", "minecraft:carrot"))
        sandbox.handlePlayerEvent(PlayerEvents.shorthand("Steve", "entity_killed", "minecraft:zombie"))
        sandbox.handlePlayerEvent(PlayerEvents.shorthand("Steve", "block_placed", "minecraft:stone"))
        sandbox.handlePlayerEvent(PlayerEvents.shorthand("Steve", "recipe_unlocked", "demo:toast"))
        sandbox.handlePlayerEvent(
            PlayerEvents.shorthand("Steve", "changed_dimension", "minecraft:overworld", "minecraft:the_nether"),
        )
        sandbox.handlePlayerEvent(PlayerEvents.shorthand("Steve", "damage", "minecraft:fall", "4.5"))

        assertEventTraceCount(
            sandbox,
            PlayerEventTraceExpectation(item = ResourceLocation.parse("minecraft:carrot")),
        )
        assertEventTraceCount(
            sandbox,
            PlayerEventTraceExpectation(entity = ResourceLocation.parse("minecraft:zombie")),
        )
        assertEventTraceCount(
            sandbox,
            PlayerEventTraceExpectation(block = ResourceLocation.parse("minecraft:stone")),
        )
        assertEventTraceCount(
            sandbox,
            PlayerEventTraceExpectation(recipe = ResourceLocation.parse("demo:toast")),
        )
        assertEventTraceCount(
            sandbox,
            PlayerEventTraceExpectation(
                fromDimension = ResourceLocation.parse("minecraft:overworld"),
                toDimension = ResourceLocation.parse("minecraft:the_nether"),
            ),
        )
        assertEventTraceCount(
            sandbox,
            PlayerEventTraceExpectation(
                damageSource = ResourceLocation.parse("minecraft:fall"),
                damageAmount = 4.5,
            ),
        )
    }

    @Test
    fun `player event traces explain failed advancement criteria`() {
        val advancementId = ResourceLocation.parse("demo:place_diamond")
        val sandbox = DatapackSandbox(
            profile = VersionProfiles.default,
            datapack = Datapack(
                functions = emptyMap(),
                loadFunctions = emptyList(),
                tickFunctions = emptyList(),
                advancements = mapOf(
                    advancementId to advancement(
                        advancementId,
                        "place_diamond",
                        "minecraft:placed_block",
                        JsonObject().also { it.addProperty("block", "minecraft:diamond_block") },
                    ),
                ),
            ),
        )
        sandbox.createPlayer("Steve")

        sandbox.handlePlayerEvent(PlayerEvents.shorthand("Steve", "block_placed", "minecraft:stone"))

        val trace = sandbox.world.playerEventTraces.single()
        val failure = trace.advancementFailures.single()
        assertEquals(advancementId, failure.advancement)
        assertEquals("place_diamond", failure.criterion)
        assertTrue("block expected minecraft:diamond_block" in failure.reason, failure.reason)
        assertEquals(
            1,
            PlayerEventTraceAssertions.matching(
                sandbox.world.playerEventTraces,
                PlayerEventTraceExpectation(
                    failedAdvancement = advancementId,
                    failedCriterion = "place_diamond",
                    failureContains = "minecraft:stone",
                ),
            ).size,
        )
        assertTrue(
            "block expected minecraft:diamond_block" in sandbox.snapshotJson()
                .getAsJsonArray("playerEventTraces")
                .single()
                .asJsonObject
                .getAsJsonArray("advancementFailures")
                .single()
                .asJsonObject
                .get("reason")
                .asString,
        )
    }

    @Test
    fun `player events mutate observable player state`() {
        val sandbox = DatapackSandbox(
            profile = VersionProfiles.default,
            datapack = Datapack(
                functions = emptyMap(),
                loadFunctions = emptyList(),
                tickFunctions = emptyList(),
            ),
        )
        val player = sandbox.createPlayer("Steve")
        player.food = 16
        player.inventory += ItemStack(ResourceLocation.parse("minecraft:apple"), count = 2)

        sandbox.handlePlayerEvent(PlayerEvents.shorthand("Steve", "item_consumed", "minecraft:apple"))
        sandbox.handlePlayerEvent(PlayerEvents.shorthand("Steve", "item_picked_up", "minecraft:bread"))
        sandbox.handlePlayerEvent(PlayerEvents.shorthand("Steve", "inventory_changed", "minecraft:carrot"))
        sandbox.handlePlayerEvent(PlayerEvents.shorthand("Steve", "changed_dimension", "minecraft:overworld", "minecraft:the_nether"))
        sandbox.handlePlayerEvent(PlayerEvents.shorthand("Steve", "damage", "minecraft:fall", "4.5"))
        sandbox.handlePlayerEvent(PlayerEvents.shorthand("Steve", "recipe_unlocked", "demo:toast"))

        assertEquals(1, player.inventory.single { it.id == ResourceLocation.parse("minecraft:apple") }.count)
        assertEquals(20, player.food)
        assertTrue(player.inventory.any { it.id == ResourceLocation.parse("minecraft:bread") && it.count == 1 })
        assertTrue(player.inventory.any { it.id == ResourceLocation.parse("minecraft:carrot") && it.count == 1 })
        assertEquals(ResourceLocation.parse("minecraft:the_nether"), player.dimension)
        assertEquals(15.5, player.health)
        assertTrue(ResourceLocation.parse("demo:toast") in player.recipes)
        assertEquals(
            listOf("item_consumed", "item_picked_up", "inventory_changed", "changed_dimension", "damage", "recipe_unlocked"),
            sandbox.world.playerEventTraces.map { it.type },
        )
        assertTrue(sandbox.world.playerEventTraces.all { it.success })
    }

    @Test
    fun `damage command with source entity fires killed player advancement event`() {
        val criterion = Criterion(
            name = "killed_by_zombie",
            trigger = ResourceLocation.parse("minecraft:entity_killed_player"),
            conditions = JsonObject().also { condition ->
                condition.add(
                    "entity",
                    JsonObject().also {
                        it.addProperty("type", "minecraft:zombie")
                    },
                )
                condition.add(
                    "damage",
                    JsonObject().also { damage ->
                        damage.addProperty("type", "minecraft:mob_attack")
                    },
                )
            },
        )
        val advancementId = ResourceLocation.parse("demo:killed_by_zombie")
        val sandbox = sandboxWithAdvancement(advancementId, criterion)
        val player = sandbox.createPlayer("Steve")
        sandbox.executeCommand("summon minecraft:zombie 0 64 0")

        sandbox.executeCommand("damage Steve 25 minecraft:mob_attack by @e[type=minecraft:zombie,limit=1]")

        assertEquals(0.0, player.health)
        assertTrue(player.advancementProgress.getValue(advancementId).criteria.getValue("killed_by_zombie"))
        val output = sandbox.world.outputs.single { it.command == "damage" }
        val payload = output.payload?.asJsonObject ?: error("missing damage payload")
        assertEquals("minecraft:mob_attack", payload.get("damageSource").asString)
        assertEquals("minecraft:zombie", payload.get("sourceType").asString)
        assertEquals("Steve", payload.getAsJsonArray("targets")[0].asJsonObject.get("target").asString)
        assertEquals(true, payload.getAsJsonArray("targets")[0].asJsonObject.get("dead").asBoolean)
    }

    @Test
    fun `damage command from player source fires hurt entity advancement event`() {
        val criterion = Criterion(
            name = "hit_cow",
            trigger = ResourceLocation.parse("minecraft:player_hurt_entity"),
            conditions = JsonObject().also { condition ->
                condition.add(
                    "entity",
                    JsonObject().also {
                        it.addProperty("type", "minecraft:cow")
                    },
                )
                condition.add(
                    "damage",
                    JsonObject().also { damage ->
                        damage.add(
                            "taken",
                            JsonObject().also { it.addProperty("min", 4.0) },
                        )
                    },
                )
            },
        )
        val advancementId = ResourceLocation.parse("demo:hit_cow")
        val sandbox = sandboxWithAdvancement(advancementId, criterion)
        val player = sandbox.createPlayer("Steve")
        sandbox.executeCommand("""summon minecraft:cow 0 64 0 {Health:8f}""")

        sandbox.executeCommand("damage @e[type=minecraft:cow,limit=1] 4 minecraft:player_attack at 1 65 -2 by Steve")

        assertTrue(player.advancementProgress.getValue(advancementId).criteria.getValue("hit_cow"))
        val output = sandbox.world.outputs.single { it.command == "damage" }
        val payload = output.payload?.asJsonObject ?: error("missing damage payload")
        assertEquals("Steve", payload.get("source").asString)
        assertEquals("Steve", payload.get("directSource").asString)
        assertEquals(1.0, payload.getAsJsonObject("position").get("x").asDouble)
        assertEquals(65.0, payload.getAsJsonObject("position").get("y").asDouble)
        assertEquals(-2.0, payload.getAsJsonObject("position").get("z").asDouble)
        assertEquals("minecraft:cow", payload.getAsJsonArray("targets")[0].asJsonObject.get("type").asString)
        assertEquals(8.0, payload.getAsJsonArray("targets")[0].asJsonObject.get("beforeHealth").asDouble)
        assertEquals(4.0, payload.getAsJsonArray("targets")[0].asJsonObject.get("afterHealth").asDouble)
    }

    @Test
    fun `damage command records direct and causing source entities`() {
        val sandbox = DatapackSandbox(
            profile = VersionProfiles.default,
            datapack = Datapack(
                functions = emptyMap(),
                loadFunctions = emptyList(),
                tickFunctions = emptyList(),
            ),
        )
        val player = sandbox.createPlayer("Steve")
        sandbox.executeCommand("""summon minecraft:arrow 0 64 0 {Tags:["projectile"]}""")
        sandbox.executeCommand("""summon minecraft:skeleton 0 64 0 {Tags:["shooter"]}""")

        sandbox.executeCommand("damage Steve 3 minecraft:arrow at ~1 ~2 ~3 by @e[tag=projectile,limit=1] from @e[tag=shooter,limit=1]")

        assertEquals(17.0, player.health)
        val payload = sandbox.world.outputs.single { it.command == "damage" }.payload?.asJsonObject ?: error("missing damage payload")
        assertEquals("minecraft:arrow", payload.get("damageSource").asString)
        assertEquals("minecraft:arrow", payload.get("sourceType").asString)
        assertEquals("minecraft:arrow", payload.get("directSourceType").asString)
        assertEquals("minecraft:skeleton", payload.get("causingSourceType").asString)
        assertEquals(1.0, payload.getAsJsonObject("position").get("x").asDouble)
        assertEquals(2.0, payload.getAsJsonObject("position").get("y").asDouble)
        assertEquals(3.0, payload.getAsJsonObject("position").get("z").asDouble)
    }

    @Test
    fun `damage command exposes loaded damage type metadata`() {
        val sandbox = createSandbox("26.2", listOf(writeDamageTypePack(Files.createTempDirectory("dps-damage-type-pack"))))
        val player = sandbox.createPlayer("Steve")

        sandbox.executeCommand("damage Steve 3 demo:acid")

        assertEquals(17.0, player.health)
        val payload = sandbox.world.outputs.single { it.command == "damage" }.payload?.asJsonObject ?: error("missing damage payload")
        assertEquals("demo:acid", payload.get("damageSource").asString)
        val damageType = payload.getAsJsonObject("damageType")
        assertEquals("demo:acid", damageType.get("id").asString)
        assertEquals("acid", damageType.get("messageId").asString)
        assertEquals("always", damageType.get("scaling").asString)
        assertEquals(0.25, damageType.get("exhaustion").asDouble)
        assertEquals("burning", damageType.get("effects").asString)
        assertEquals("intentional_game_design", damageType.get("deathMessageType").asString)
        assertEquals("26.2", damageType.get("version").asString)
    }

    private fun assertEventTraceCount(
        sandbox: DatapackSandbox,
        expectation: PlayerEventTraceExpectation,
        count: Int = 1,
    ) {
        assertEquals(count, PlayerEventTraceAssertions.matching(sandbox.world.playerEventTraces, expectation).size)
    }

    private fun sandboxWithAdvancement(id: ResourceLocation, criterion: Criterion): DatapackSandbox =
        DatapackSandbox(
            profile = VersionProfiles.default,
            datapack = Datapack(
                functions = emptyMap(),
                loadFunctions = emptyList(),
                tickFunctions = emptyList(),
                advancements = mapOf(
                    id to AdvancementDefinition(
                        id = id,
                        file = "<test>",
                        root = JsonObject(),
                        parent = null,
                        criteria = mapOf(criterion.name to criterion),
                        requirements = listOf(listOf(criterion.name)),
                        rewards = AdvancementReward(),
                    ),
                ),
            ),
        )

    private fun advancement(id: ResourceLocation, criterionName: String, trigger: String, conditions: JsonObject): AdvancementDefinition =
        AdvancementDefinition(
            id = id,
            file = "<test>",
            root = JsonObject(),
            parent = null,
            criteria = mapOf(
                criterionName to Criterion(
                    name = criterionName,
                    trigger = ResourceLocation.parse(trigger),
                    conditions = conditions,
                ),
            ),
            requirements = listOf(listOf(criterionName)),
            rewards = AdvancementReward(),
        )

    private fun writeDamageTypePack(root: Path): Path {
        Files.writeString(
            root.resolve("pack.mcmeta").also { Files.createDirectories(it.parent) },
            """
            {
              "pack": {
                "pack_format": 107.1,
                "description": "damage type test"
              }
            }
            """.trimIndent(),
        )
        val damageTypeRoot = root.resolve("data").resolve("demo").resolve("damage_type")
        Files.createDirectories(damageTypeRoot)
        Files.writeString(
            damageTypeRoot.resolve("acid.json"),
            """
            {
              "message_id": "acid",
              "scaling": "always",
              "exhaustion": 0.25,
              "effects": "burning",
              "death_message_type": "intentional_game_design"
            }
            """.trimIndent(),
        )
        return root
    }
}
