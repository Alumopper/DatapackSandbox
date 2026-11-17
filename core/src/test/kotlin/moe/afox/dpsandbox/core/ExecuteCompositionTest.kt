package moe.afox.dpsandbox.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExecuteCompositionTest {
    private fun sandbox() =
        createFunctionSandboxFromString(version = "26.3", functionText = "", functionId = "demo:empty")

    @Test
    fun `entity UUID data can be copied and indexed as four signed integers`() {
        val sandbox = sandbox()
        sandbox.executeCommand("summon minecraft:block_display 0 64 0 {UUID:[I;1,-2,3,-4]}")
        sandbox.executeCommand("data modify storage demo:identity uuid set from entity @e[type=minecraft:block_display,limit=1] UUID")
        val parts = sandbox.world.storage(ResourceLocation.parse("demo:identity")).getAsJsonArray("uuid")
        assertEquals(listOf(1, -2, 3, -4), parts.map { it.asInt })
        sandbox.executeCommand("scoreboard objectives add checks dummy")
        sandbox.executeCommand("execute store result score #part checks run data get storage demo:identity uuid[1]")
        assertEquals(-2, sandbox.world.getScore("#part", "checks"))
    }

    @Test
    fun `execute summon binds the new entity in the current position and dimension`() {
        val sandbox = sandbox()
        sandbox.executeCommand(
            "execute positioned 3 64 -2 in minecraft:the_nether summon minecraft:block_display run tag @s add created",
        )
        val entity = sandbox.world.entities.single { "created" in it.tags }
        assertEquals(ResourceLocation.parse("minecraft:block_display"), entity.type)
        assertEquals(Position(3.0, 64.0, -2.0), entity.position)
        assertEquals(ResourceLocation.parse("minecraft:the_nether"), entity.dimension)
        assertTrue("created" in entity.tags)
    }

    @Test
    fun `store accepts terminal float predicates and resets a failed condition to zero`() {
        val sandbox = sandbox()
        sandbox.executeCommand("scoreboard objectives add checks dummy")
        sandbox.executeCommand("data modify storage demo:values decimal set value 2.5f")
        val provider = "{type:\"minecraft:storage\",storage:\"demo:values\",path:\"decimal\"}"
        val equal = "{type:\"minecraft:float_value_check\",value:$provider,test:2.5}"
        val lower = "{type:\"minecraft:float_value_check\",value:$provider,test:{max:2.4}}"
        sandbox.executeCommand("execute store success score #equal checks if predicate $equal")
        sandbox.executeCommand("execute store success score #lower checks if predicate $lower")
        sandbox.executeCommand("execute store success score #unless checks unless predicate $lower")
        assertEquals(1, sandbox.world.getScore("#equal", "checks"))
        assertEquals(0, sandbox.world.getScore("#lower", "checks"))
        assertEquals(1, sandbox.world.getScore("#unless", "checks"))
    }

    @Test
    fun `multiple stores retain terminal entity counts`() {
        val sandbox = sandbox()
        sandbox.executeCommand("scoreboard objectives add checks dummy")
        sandbox.executeCommand("summon minecraft:marker 0 0 0 {Tags:[\"counted\"]}")
        sandbox.executeCommand("summon minecraft:marker 1 0 0 {Tags:[\"counted\"]}")
        sandbox.executeCommand(
            "execute store result score #count checks store success score #present checks if entity @e[tag=counted]",
        )
        assertEquals(2, sandbox.world.getScore("#count", "checks"))
        assertEquals(1, sandbox.world.getScore("#present", "checks"))
    }
}
