package moe.afox.dpsandbox.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PortableUuidTest {
    @Test
    fun `normalizes compact Java-compatible UUID spelling`() {
        assertEquals("00000000-0000-0000-0000-000000000000", normalizeUuid("0-0-0-0-0"))
        assertContentEquals(intArrayOf(0, 0, 0, 0), uuidIntArray("00000000000000000000000000000000"))
    }

    @Test
    fun `rejects oversized UUID input before tokenizing it`() {
        val oversized = "0-".repeat(512 * 1024)

        assertNull(normalizeUuid(oversized))
        assertNull(uuidIntArray(oversized))
    }
}
