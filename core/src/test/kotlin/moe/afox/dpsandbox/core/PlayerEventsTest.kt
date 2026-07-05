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
