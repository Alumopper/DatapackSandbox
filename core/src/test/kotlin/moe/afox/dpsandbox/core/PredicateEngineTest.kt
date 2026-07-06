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

    @Test
    fun `location check matches sparse block state tag and nbt`() {
        val tagId = ResourceLocation.parse("demo:containers")
        val datapack = Datapack(
            functions = emptyMap(),
            loadFunctions = emptyList(),
            tickFunctions = emptyList(),
            tags = mapOf(
                TagKey("block", tagId) to TagDefinition(
                    key = TagKey("block", tagId),
                    file = "<test>",
                    values = listOf(TagValue("minecraft:chest")),
                ),
            ),
        )
        val engine = PredicateEngine(datapack)
        val pos = BlockPos(1, 64, 2)
        val world = SandboxWorld()
        world.blocks[pos] = SandboxBlock(
            id = ResourceLocation.parse("minecraft:chest"),
            properties = mutableMapOf("facing" to "north", "slots" to "27"),
            nbt = JsonValues.parse("""{CustomName:{text:"Cache"}}""").asJsonObject,
        )
        val predicate = JsonValues.parse(
            """
            {
              condition: "minecraft:location_check",
              predicate: {
                block: {
                  blocks: "#demo:containers",
                  state: {
                    facing: "north",
                    slots: {min: 20, max: 30}
                  },
                  nbt: {CustomName:{text:"Cache"}}
                }
              }
            }
            """.trimIndent(),
        )
        val wrongState = JsonValues.parse(
            """
            {
              condition: "minecraft:location_check",
              predicate: {
                block: {
                  blocks: "#demo:containers",
                  state: {facing: "south"}
                }
              }
            }
            """.trimIndent(),
        )

        val context = PredicateContext(world = world, origin = Position(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble()))
        assertTrue(engine.testElement(predicate, context))
        assertFalse(engine.testElement(wrongState, context))
    }

    private fun context(tool: ItemStack?): PredicateContext =
        PredicateContext(
            world = SandboxWorld(),
            tool = tool,
            random = Random(0),
        )
}
