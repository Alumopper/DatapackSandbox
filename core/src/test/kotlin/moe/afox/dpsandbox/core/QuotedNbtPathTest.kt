package moe.afox.dpsandbox.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class QuotedNbtPathTest {
    @Test
    fun `quoted first fields stay attached to their path suffix`() {
        val tokens = CommandTokenizer.tokenize("data modify storage mcfpp:frames \"qwq:bounce_float\".t set value 9")
        assertEquals(8, tokens.size)
        assertEquals("\"qwq:bounce_float\".t", tokens[4].sourceText)
        assertEquals("set", tokens[5].text)
        assertEquals("Player Name", CommandTokenizer.tokenize("execute as \"Player Name\" run say hi")[2].text)
    }

    @Test
    fun `compiled static frames preserve quoted keys for reads writes conditions and stores`() {
        val sandbox =
            createFunctionSandboxFromString(
                version = "1.21.8",
                functionText =
                    """
                    scoreboard objectives add result dummy
                    scoreboard players set input result 37
                    data modify storage mcfpp:frames "qwq.ns:bounce_float" set value {}
                    data modify storage mcfpp:frames "qwq.ns:bounce_float".t set value 9
                    data modify storage mcfpp:frames "qwq.ns:bounce_float"."${'$'}ir" set value {}
                    execute store result storage mcfpp:frames "qwq.ns:bounce_float"."${'$'}ir".float_2 int 1 run scoreboard players get input result
                    data modify storage mcfpp:frames "qwq.ns:bounce_float".copy set from storage mcfpp:frames "qwq.ns:bounce_float".t
                    execute if data storage mcfpp:frames "qwq.ns:bounce_float".copy store result score copied result run data get storage mcfpp:frames "qwq.ns:bounce_float".copy
                    data remove storage mcfpp:frames "qwq.ns:bounce_float".t
                    """.trimIndent(),
            )
        sandbox.runFunction(SingleFunctionDatapack.DEFAULT_ID)
        val frame = sandbox.world.storage(ResourceLocation.parse("mcfpp:frames")).getAsJsonObject("qwq.ns:bounce_float")
        assertEquals(37, frame.getAsJsonObject("\$ir").get("float_2").asInt)
        assertEquals(9, frame.get("copy").asInt)
        assertEquals(9, sandbox.world.getScore("copied", "result"))
        assertFalse(frame.has("t"))
    }
}
