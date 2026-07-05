package moe.afox.dpsandbox.core

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExtendedRuntimeTest {
    private fun pack(): Path = Path.of("../examples/full-stack/pack")

    @Test
    fun `parses snbt and data paths`() {
        val value = JsonValues.parse("{foo:[1b,2s,{bar:\"baz\"}],flag:true}").asJsonObject

        assertEquals(2, JsonPaths.get(value, "foo[1]")?.asInt)
        JsonPaths.set(value, "foo[2].bar", JsonValues.parse("\"changed\""))
        JsonPaths.append(value, "foo", JsonValues.parse("{added:1}"))
        JsonPaths.set(value, "foo[{bar:\"changed\"}].flag", JsonValues.parse("true"))

        assertEquals("changed", JsonPaths.get(value, "foo[2].bar")?.asString)
        assertEquals(true, JsonPaths.get(value, "foo[{bar:\"changed\"}].flag")?.asBoolean)
        assertEquals(4, JsonPaths.get(value, "foo")?.asJsonArray?.size())
        assertEquals(true, JsonPaths.remove(value, "foo[{added:1}]"))
        assertEquals(3, JsonPaths.get(value, "foo")?.asJsonArray?.size())
    }

    @Test
    fun `loads and evaluates predicate loot and advancement player event`() {
        val sandbox = createSandbox("26.2", listOf(pack()))
        val player = sandbox.createPlayer("Steve")
        player.inventory += ItemStack(ResourceLocation.parse("minecraft:carrot_on_a_stick"))
        player.selectedSlot = 0

        assertTrue(sandbox.predicates.test(ResourceLocation.parse("demo:has_carrot"), PredicateContext(world = sandbox.world, player = player, thisEntity = player, origin = player.position, tool = player.selectedItem)))

        val loot = sandbox.generateLoot(ResourceLocation.parse("demo:gift"), ResourceLocation.parse("minecraft:advancement_reward"), player, seed = 42)
        assertEquals(ResourceLocation.parse("minecraft:diamond"), loot.items.single().id)
        assertEquals(2, loot.items.single().count)

        sandbox.handlePlayerEvent(PlayerEvent(player.name, "item_used", item = ItemStack(ResourceLocation.parse("minecraft:carrot_on_a_stick"))))

        assertEquals(5, player.xp)
        assertEquals(2, player.inventory.size)
        assertEquals(1, sandbox.world.getScore("Steve", "rewards"))
        assertTrue(sandbox.world.outputs.any { it.command == "tellraw" && it.text == "Steve has been awarded 1 reward point!" })
        assertTrue(sandbox.world.outputs.any { it.command == "tellraw" && it.text == "1" && it.segments.singleOrNull()?.color == "yellow" })
        assertEquals(1, JsonPaths.get(sandbox.world.storage(ResourceLocation.parse("demo:state")), "rewards")?.asJsonArray?.size())
        assertTrue(player.advancementProgress[ResourceLocation.parse("demo:use_carrot")]?.criteria?.get("use_carrot") == true)
    }

    @Test
    fun `advancement test records structured results`() {
        val sandbox = createSandbox("26.2", listOf(pack()))
        sandbox.createPlayer("Steve")

        sandbox.executeCommand("advancement test Steve demo:use_carrot use_carrot")

        assertEquals("0", sandbox.world.outputs.single { it.command == "advancement test" }.text)

        sandbox.executeCommand("advancement grant Steve only demo:use_carrot use_carrot")
        sandbox.executeCommand("advancement test Steve demo:use_carrot use_carrot")

        val output = sandbox.world.outputs.last { it.command == "advancement test" }
        val payload = output.payload?.asJsonObject ?: error("missing advancement test payload")
        val result = payload.getAsJsonArray("results").single().asJsonObject
        assertEquals("1", output.text)
        assertEquals("demo:use_carrot", payload.get("id").asString)
        assertEquals("use_carrot", payload.get("criterion").asString)
        assertEquals(1, payload.get("passed").asInt)
        assertEquals("Steve", result.get("player").asString)
        assertEquals(true, result.get("done").asBoolean)
        assertEquals(true, result.get("criterionDone").asBoolean)

        sandbox.executeCommand("scoreboard objectives add advancement dummy")
        sandbox.executeCommand("execute store result score Steve advancement run advancement test Steve demo:use_carrot use_carrot")

        assertEquals(1, sandbox.world.getScore("Steve", "advancement"))
    }
}
