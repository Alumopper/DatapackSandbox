package moe.afox.dpsandbox.core

import com.google.gson.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SandboxCheckpointTest {
    @Test
    fun checkpointDeepCopiesCompleteModeledStateAndCanBeReused() {
        val sandbox = createFunctionSandboxFromString("26.2", "say ready", defaultPlayerName = null)
        val player = sandbox.createPlayer("Alex")
        player.inventory += ItemStack(ResourceLocation.parse("minecraft:diamond"), 2)
        player.advancementProgress[ResourceLocation.parse("demo:start")] =
            AdvancementProgress(linkedMapOf("entered" to true))
        sandbox.world.storages[ResourceLocation.parse("demo:state")] =
            JsonObject().also { it.addProperty("branch", "saved") }
        sandbox.executeCommand("setblock 1 2 3 minecraft:stone")
        sandbox.executeCommand("scoreboard objectives add runs dummy")
        sandbox.executeCommand("scoreboard players set Alex runs 4")
        val saved = sandbox.saveCheckpoint("demo")

        player.inventory.first().count = 64
        player.advancementProgress.getValue(ResourceLocation.parse("demo:start")).criteria["entered"] = false
        sandbox.world.storages
            .getValue(ResourceLocation.parse("demo:state"))
            .addProperty("branch", "changed")
        sandbox.executeCommand("setblock 1 2 3 minecraft:dirt")
        sandbox.executeCommand("scoreboard players add Alex runs 10")

        assertEquals(saved, sandbox.restoreCheckpoint("demo"))
        assertEquals(saved, sandbox.snapshotString())
        assertEquals(
            2,
            sandbox.world
                .requirePlayer("Alex")
                .inventory
                .single()
                .count,
        )
        assertTrue(
            sandbox.world
                .requirePlayer("Alex")
                .advancementProgress
                .getValue(ResourceLocation.parse("demo:start"))
                .criteria
                .getValue("entered"),
        )

        sandbox.world
            .requirePlayer("Alex")
            .inventory
            .single()
            .count = 8
        assertEquals(saved, sandbox.restoreCheckpoint("demo"))
        assertEquals(listOf("demo"), sandbox.checkpointNames())
        assertTrue(sandbox.deleteCheckpoint("demo"))
        assertFalse(sandbox.deleteCheckpoint("demo"))
    }
}
