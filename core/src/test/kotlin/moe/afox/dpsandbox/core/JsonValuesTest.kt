package moe.afox.dpsandbox.core

import com.google.gson.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonValuesTest {
    @Test
    fun `named compound filters read scheduler dispatch and repeat flags`() {
        val value = JsonValues.parse("{current:{code:0,keep:1b,nested:{label:'a}b'}}}").asJsonObject
        assertEquals(0, JsonPaths.get(value, "current{code:0}.code")?.asInt)
        assertEquals(1, JsonPaths.get(value, "current{keep:1b}.keep")?.asInt)
        assertEquals(0, JsonPaths.get(value, "current{nested:{label:'a}b'}}.code")?.asInt)
        assertNull(JsonPaths.get(value, "current{code:1}"))
        JsonPaths.set(value, "current{code:0}.keep", JsonPrimitive(2))
        assertEquals(2, JsonPaths.get(value, "current.keep")?.asInt)
        assertFalse(JsonPaths.remove(value, "current{code:1}"))
        assertTrue(JsonPaths.remove(value, "current{code:0}.keep"))
    }

    @Test
    fun `execute if data dispatches a matching scheduler compound`() {
        val sandbox = createFunctionSandboxFromString("1.21.8", "")
        sandbox.executeCommand("scoreboard objectives add result dummy")
        sandbox.executeCommand("data modify storage demo:scheduler current set value {code:0,keep:1b}")
        sandbox.executeCommand("execute if data storage demo:scheduler current{code:0} run scoreboard players set #dispatch result 1")
        sandbox.executeCommand("execute if data storage demo:scheduler current{keep:1b} run scoreboard players set #repeat result 1")
        sandbox.executeCommand("execute if data storage demo:scheduler current{code:1} run scoreboard players set #wrong result 1")
        assertEquals(1, sandbox.world.getScore("#dispatch", "result"))
        assertEquals(1, sandbox.world.getScore("#repeat", "result"))
        assertEquals(0, sandbox.world.getScore("#wrong", "result"))
    }

    @Test
    fun `execute store data get returns UUID string length`() {
        val sandbox = createFunctionSandboxFromString("1.21.8", "")
        sandbox.executeCommand("scoreboard objectives add result dummy")
        sandbox.executeCommand("data modify storage demo:state uuid set value '00000000-0000-0000-0000-000000000001'")
        sandbox.executeCommand("execute store result score #length result run data get storage demo:state uuid")
        assertEquals(36, sandbox.world.getScore("#length", "result"))
    }

    @Test
    fun `display values accept scientific SNBT float notation`() {
        assertEquals(5.0, JsonValues.parse("50000000e-7f").asDouble)
        assertEquals(-2.5, JsonValues.parse("-25e-1d").asDouble)
        assertEquals(100.0, JsonValues.parse("1E+2").asDouble)
    }

    @Test
    fun `scoreboard swap used by display float arithmetic updates both holders`() {
        val sandbox = createFunctionSandboxFromString("1.21.8", "")
        sandbox.executeCommand("scoreboard objectives add result dummy")
        sandbox.executeCommand("scoreboard players set #left result 3")
        sandbox.executeCommand("scoreboard players set #right result 7")
        sandbox.executeCommand("scoreboard players operation #left result >< #right result")
        assertEquals(7, sandbox.world.getScore("#left", "result"))
        assertEquals(3, sandbox.world.getScore("#right", "result"))
    }

    @Test
    fun `parses trailing commas in snbt compounds and lists`() {
        val value =
            JsonValues
                .parse(
                    """
                    {
                        particle:"particle dust{color:[1.0, 0.1, 0.1], scale:0.05} ~ ~ ~",
                        nested:{enabled:1b,},
                        values:[1,2,3,],
                    }
                    """.trimIndent(),
                ).asJsonObject

        assertEquals(
            "particle dust{color:[1.0, 0.1, 0.1], scale:0.05} ~ ~ ~",
            value.get("particle").asString,
        )
        assertEquals(1, value.getAsJsonObject("nested").get("enabled").asInt)
        assertEquals(listOf(1, 2, 3), value.getAsJsonArray("values").map { it.asInt })
    }

    @Test
    fun `list wildcard reads and updates every matching entry`() {
        val value = JsonValues.parse("{scale:[0.0f,0.0f,0.0f],groups:[{rate:0},{rate:1}]}").asJsonObject

        JsonPaths.set(value, "scale[]", JsonPrimitive(2.5))
        JsonPaths.set(value, "groups[].rate", JsonPrimitive(7))

        assertEquals(listOf(2.5, 2.5, 2.5), JsonPaths.getAll(value, "scale[]").map { it.asDouble })
        assertEquals(listOf(7, 7), JsonPaths.getAll(value, "groups[].rate").map { it.asInt })
    }
}
