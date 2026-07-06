package moe.afox.dpsandbox.core

import com.google.gson.JsonObject
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

        sandbox.executeCommand("damage @e[type=minecraft:cow,limit=1] 4 minecraft:player_attack by Steve")

        assertTrue(player.advancementProgress.getValue(advancementId).criteria.getValue("hit_cow"))
        val output = sandbox.world.outputs.single { it.command == "damage" }
        val payload = output.payload?.asJsonObject ?: error("missing damage payload")
        assertEquals("Steve", payload.get("source").asString)
        assertEquals("minecraft:cow", payload.getAsJsonArray("targets")[0].asJsonObject.get("type").asString)
        assertEquals(8.0, payload.getAsJsonArray("targets")[0].asJsonObject.get("beforeHealth").asDouble)
        assertEquals(4.0, payload.getAsJsonArray("targets")[0].asJsonObject.get("afterHealth").asDouble)
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
}
