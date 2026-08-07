package moe.afox.dpsandbox.core

import com.google.gson.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class JsonValuesTest {
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
