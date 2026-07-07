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
        assertEquals(ResourceBehaviorLevel.OBSERVED_NOOP, entries.getValue("damage_type").behaviorLevel)
        assertEquals(ResourceBehaviorLevel.MODELED, entries.getValue("worldgen/structure").behaviorLevel)
    }

    @Test
    fun `raw json resource catalog is deterministic and unique`() {
        assertEquals(ResourceCatalog.additionalRawJsonTypes.sorted(), ResourceCatalog.additionalRawJsonTypes)
        assertEquals(ResourceCatalog.additionalRawJsonTypes.size, ResourceCatalog.additionalRawJsonTypes.toSet().size)
        assertTrue("damage_type" in ResourceCatalog.additionalRawJsonTypes)
        assertTrue("worldgen/placed_feature" in ResourceCatalog.additionalRawJsonTypes)
        assertTrue("worldgen/structure" in ResourceCatalog.additionalRawJsonTypes)
    }
}
