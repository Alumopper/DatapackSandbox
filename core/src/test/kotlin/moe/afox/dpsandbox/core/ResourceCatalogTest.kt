package moe.afox.dpsandbox.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResourceCatalogTest {
    @Test
    fun `catalog assigns stable resource behavior levels`() {
        val entries = ResourceCatalog.all.associateBy { it.type }

        assertEquals(ResourceBehaviorLevel.MODELED, entries.getValue("function").behaviorLevel)
        assertEquals(ResourceBehaviorLevel.MODELED, entries.getValue("tag/function").behaviorLevel)
        assertEquals(ResourceBehaviorLevel.MODELED, entries.getValue("loot_table").behaviorLevel)
        assertEquals(ResourceBehaviorLevel.MODELED, entries.getValue("item_modifier").behaviorLevel)
        assertEquals(ResourceBehaviorLevel.OBSERVED_NOOP, entries.getValue("tag/<registry>").behaviorLevel)
        assertEquals(ResourceBehaviorLevel.MODELED, entries.getValue("cat_variant").behaviorLevel)
        assertEquals(ResourceBehaviorLevel.MODELED, entries.getValue("chat_type").behaviorLevel)
        assertEquals(ResourceBehaviorLevel.MODELED, entries.getValue("chicken_variant").behaviorLevel)
        assertEquals(ResourceBehaviorLevel.MODELED, entries.getValue("cow_variant").behaviorLevel)
        assertEquals(ResourceBehaviorLevel.MODELED, entries.getValue("damage_type").behaviorLevel)
        assertEquals(ResourceBehaviorLevel.MODELED, entries.getValue("dimension").behaviorLevel)
        assertEquals(ResourceBehaviorLevel.MODELED, entries.getValue("dimension_type").behaviorLevel)
        assertEquals(ResourceBehaviorLevel.MODELED, entries.getValue("enchantment").behaviorLevel)
        assertEquals(ResourceBehaviorLevel.MODELED, entries.getValue("frog_variant").behaviorLevel)
        assertEquals(ResourceBehaviorLevel.MODELED, entries.getValue("painting_variant").behaviorLevel)
        assertEquals(ResourceBehaviorLevel.MODELED, entries.getValue("pig_variant").behaviorLevel)
        assertEquals(ResourceBehaviorLevel.MODELED, entries.getValue("wolf_sound_variant").behaviorLevel)
        assertEquals(ResourceBehaviorLevel.MODELED, entries.getValue("wolf_variant").behaviorLevel)
        assertEquals(ResourceBehaviorLevel.MODELED, entries.getValue("worldgen/configured_feature").behaviorLevel)
        assertEquals(ResourceBehaviorLevel.MODELED, entries.getValue("worldgen/placed_feature").behaviorLevel)
        assertEquals(ResourceBehaviorLevel.MODELED, entries.getValue("worldgen/processor_list").behaviorLevel)
        assertEquals(ResourceBehaviorLevel.MODELED, entries.getValue("worldgen/structure").behaviorLevel)
        assertEquals(ResourceBehaviorLevel.MODELED, entries.getValue("worldgen/template_pool").behaviorLevel)
    }

    @Test
    fun `raw json resource catalog is deterministic and unique`() {
        assertEquals(ResourceCatalog.additionalRawJsonTypes.sorted(), ResourceCatalog.additionalRawJsonTypes)
        assertEquals(ResourceCatalog.additionalRawJsonTypes.size, ResourceCatalog.additionalRawJsonTypes.toSet().size)
        assertTrue("cat_variant" in ResourceCatalog.additionalRawJsonTypes)
        assertTrue("damage_type" in ResourceCatalog.additionalRawJsonTypes)
        assertTrue("dimension" in ResourceCatalog.additionalRawJsonTypes)
        assertTrue("dimension_type" in ResourceCatalog.additionalRawJsonTypes)
        assertTrue("enchantment" in ResourceCatalog.additionalRawJsonTypes)
        assertTrue("painting_variant" in ResourceCatalog.additionalRawJsonTypes)
        assertTrue("wolf_variant" in ResourceCatalog.additionalRawJsonTypes)
        assertTrue("worldgen/placed_feature" in ResourceCatalog.additionalRawJsonTypes)
        assertTrue("worldgen/structure" in ResourceCatalog.additionalRawJsonTypes)
    }
}
