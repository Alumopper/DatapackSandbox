package moe.afox.dpsandbox.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ResourceLocationTest {
    @Test
    fun `parses explicit and default namespaces`() {
        assertEquals(ResourceLocation("demo", "main"), ResourceLocation.parse("demo:main"))
        assertEquals(ResourceLocation("minecraft", "load"), ResourceLocation.parse("load"))
    }

    @Test
    fun `storage ids allow an empty path without weakening regular resource parsing`() {
        assertEquals(ResourceLocation("ram", ""), ResourceLocation.parseNullable("ram:"))
        assertEquals("ram:", ResourceLocation.parseNullable("ram:").toString())
        assertFailsWith<SandboxException> {
            ResourceLocation.parse("ram:")
        }
    }

    @Test
    fun `rejects invalid identifiers`() {
        assertFailsWith<SandboxException> {
            ResourceLocation.parse("Demo:Main")
        }
    }
}
