package moe.afox.dpsandbox.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PredicateEngineTest {
    @Test
    fun `random chance with enchanted bonus uses tool enchantments`() {
        val engine = PredicateEngine(Datapack(emptyMap(), emptyList(), emptyList()))
        val predicate = JsonValues.parse(
            """
            {
              condition: "minecraft:random_chance_with_enchanted_bonus",
              enchantment: "minecraft:fortune",
              unenchanted_chance: 0.0,
              enchanted_chance: {type: "minecraft:linear", base: 0.5, per_level_above_first: 0.5}
            }
            """.trimIndent(),
        )
        val flatTool = ItemStack(
            id = ResourceLocation.parse("minecraft:diamond_pickaxe"),
            components = JsonValues.parse("{\"minecraft:enchantments\":{\"minecraft:fortune\":2}}").asJsonObject,
        )
        val nestedTool = ItemStack(
            id = ResourceLocation.parse("minecraft:diamond_pickaxe"),
            components = JsonValues.parse("{\"minecraft:enchantments\":{levels:{fortune:2}}}").asJsonObject,
        )

        assertFalse(engine.testElement(predicate, context(tool = null)))
        assertTrue(engine.testElement(predicate, context(tool = flatTool)))
        assertTrue(engine.testElement(predicate, context(tool = nestedTool)))
    }

    @Test
    fun `random chance with enchanted bonus supports legacy multiplier`() {
        val engine = PredicateEngine(Datapack(emptyMap(), emptyList(), emptyList()))
        val predicate = JsonValues.parse(
            """
            {
              condition: "minecraft:random_chance_with_looting",
              chance: 0.0,
              looting_multiplier: 1.0
            }
            """.trimIndent(),
        )
        val sword = ItemStack(
            id = ResourceLocation.parse("minecraft:diamond_sword"),
            components = JsonValues.parse("{\"minecraft:enchantments\":{\"minecraft:looting\":1}}").asJsonObject,
        )

        assertTrue(engine.testElement(predicate, context(tool = sword)))
    }

    private fun context(tool: ItemStack?): PredicateContext =
        PredicateContext(
            world = SandboxWorld(),
            tool = tool,
            random = Random(0),
        )
}
