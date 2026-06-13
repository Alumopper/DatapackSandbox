package net.datapacksandbox.core

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
    fun `rejects invalid identifiers`() {
        assertFailsWith<SandboxException> {
            ResourceLocation.parse("Demo:Main")
        }
    }
}
