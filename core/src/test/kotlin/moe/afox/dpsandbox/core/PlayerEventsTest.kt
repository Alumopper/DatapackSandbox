package moe.afox.dpsandbox.core

import com.google.gson.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerEventsTest {
    @Test
    fun `shorthand changed dimension event carries from and to dimensions`() {
        val advancementId = ResourceLocation.parse("demo:nether_trip")
        val criterion = Criterion(
            name = "enter_nether",
            trigger = ResourceLocation.parse("minecraft:changed_dimension"),
            conditions = JsonObject().also {
                it.addProperty("from", "minecraft:overworld")
                it.addProperty("to", "minecraft:the_nether")
            },
        )
        val sandbox = DatapackSandbox(
            profile = VersionProfiles.default,
            datapack = Datapack(
                functions = emptyMap(),
                loadFunctions = emptyList(),
                tickFunctions = emptyList(),
                advancements = mapOf(
                    advancementId to AdvancementDefinition(
                        id = advancementId,
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
}
