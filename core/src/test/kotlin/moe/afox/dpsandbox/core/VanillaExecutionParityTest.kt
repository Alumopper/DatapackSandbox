package moe.afox.dpsandbox.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Observable command results and side effects in a Java Edition datapack. */
class VanillaExecutionParityTest {
    private fun sandbox(vararg functions: Pair<String, String>): DatapackSandbox =
        createFunctionSandbox(
            "26.3",
            (listOf("demo:empty" to "") + functions).map { (id, code) -> FunctionSource.text(id, code) },
        ).also { it.executeCommand("scoreboard objectives add checks dummy") }

    @Test
    fun `zero and negative explicit return values are successful`() {
        val sandbox = sandbox("demo:zero" to "return 0", "demo:negative" to "return -7")
        for ((id, value) in listOf("demo:zero" to 0, "demo:negative" to -7)) {
            val result = sandbox.runFunction(id)
            assertTrue(result.success, id)
            assertEquals(value, result.returnValue)
            sandbox.executeCommand("execute store success score #success checks run function $id")
            assertEquals(1, sandbox.world.getScore("#success", "checks"), id)
        }
    }

    @Test
    fun `function conditions require an explicit nonzero return`() {
        val sandbox = sandbox("demo:void" to "say no return", "demo:negative" to "return -7")
        sandbox.executeCommand("execute if function demo:void run scoreboard players set #void checks 1")
        sandbox.executeCommand("execute unless function demo:void run scoreboard players set #unless checks 1")
        sandbox.executeCommand("execute if function demo:negative run scoreboard players set #negative checks 1")
        assertFalse(ScoreKey("#void", "checks") in sandbox.world.scores)
        assertEquals(1, sandbox.world.getScore("#unless", "checks"))
        assertEquals(1, sandbox.world.getScore("#negative", "checks"))
    }

    @Test
    fun `void functions do not write store result or success`() {
        val sandbox = sandbox("demo:void" to "data get storage demo:state value")
        sandbox.executeCommand("data modify storage demo:state value set value 42")
        sandbox.executeCommand("scoreboard players set #value checks 9")
        sandbox.executeCommand("scoreboard players set #success checks 9")
        sandbox.executeCommand("execute store result score #value checks store success score #success checks run function demo:void")
        assertEquals(9, sandbox.world.getScore("#value", "checks"))
        assertEquals(9, sandbox.world.getScore("#success", "checks"))
    }

    @Test
    fun `return run propagates void failure and still terminates the caller`() {
        val sandbox =
            sandbox(
                "demo:void" to "say no return",
                "demo:caller" to "return run function demo:void\nscoreboard players set #after checks 1",
            )
        val result = sandbox.runFunction("demo:caller")
        assertFalse(result.success)
        assertEquals(0, result.returnValue)
        assertFalse(ScoreKey("#after", "checks") in sandbox.world.scores)
    }

    @Test
    fun `return run takes the first fork result and cancels remaining branches`() {
        val sandbox = sandbox("demo:caller" to "return run execute as @e[tag=probe] run data get entity @s Pos[0]")
        sandbox.executeCommand("summon minecraft:marker 3 0 0 {Tags:[\"probe\"]}")
        sandbox.executeCommand("summon minecraft:marker 7 0 0 {Tags:[\"probe\"]}")
        val result = sandbox.runFunction("demo:caller")
        assertEquals(3, result.returnValue)
        assertEquals(1, sandbox.world.outputs.count { it.command == "get" })
    }

    @Test
    fun `store around return writes before the enclosing function exits`() {
        val sandbox = sandbox("demo:caller" to "execute store success score #stored checks run return -7")
        val result = sandbox.runFunction("demo:caller")
        assertTrue(result.success)
        assertEquals(-7, result.returnValue)
        assertEquals(1, sandbox.world.getScore("#stored", "checks"))
    }

    @Test
    fun `store callbacks run after each branch before the next branch starts`() {
        val sandbox =
            sandbox(
                "demo:sample" to
                    """
                    execute store result storage demo:state previous int 1 run scoreboard players get #last checks
                    data modify storage demo:state seen append from storage demo:state previous
                    return run data get entity @s Pos[0]
                    """.trimIndent(),
            )
        sandbox.executeCommand("summon minecraft:marker 3 0 0 {Tags:[\"probe\"]}")
        sandbox.executeCommand("summon minecraft:marker 7 0 0 {Tags:[\"probe\"]}")
        sandbox.executeCommand("scoreboard players set #last checks 99")
        sandbox.executeCommand("data modify storage demo:state seen set value []")
        sandbox.executeCommand("execute store result score #last checks as @e[tag=probe] run function demo:sample")
        assertEquals(
            listOf(99, 3),
            sandbox.world
                .storage(ResourceLocation.parse("demo:state"))
                .getAsJsonArray("seen")
                .map { it.asInt },
        )
        assertEquals(7, sandbox.world.getScore("#last", "checks"))
    }

    @Test
    fun `terminal data condition reports the number of matching tags`() {
        val sandbox = sandbox()
        sandbox.executeCommand("data modify storage demo:state values set value [1,2,3]")
        sandbox.executeCommand("execute store result score #matches checks if data storage demo:state values[]")
        assertEquals(3, sandbox.world.getScore("#matches", "checks"))
    }

    @Test
    fun `function tags run all entries unless a caller returns early`() {
        val pack = writeExecuteFunctionPack(Files.createTempDirectory("dps-function-tag"))
        Files.writeString(pack.resolve("data/demo/function/first.mcfunction"), "return run function #demo:group")
        val sandbox = createSandbox("26.3", listOf(pack))
        sandbox.executeCommand("scoreboard objectives add checks dummy")
        assertEquals(1, sandbox.runFunction("demo:first").returnValue)
        assertEquals(1, sandbox.world.getScore("#condition", "checks"))
        sandbox.executeCommand("execute store success score #last checks run function #demo:group")
        assertEquals(12, sandbox.world.getScore("#condition", "checks"))
        assertEquals(0, sandbox.world.getScore("#last", "checks"))
        assertTrue(ScoreKey("#last", "checks") in sandbox.world.scores)
    }

    @Test
    fun `score mutations and failed reads propagate independent results`() {
        val sandbox =
            sandbox(
                "demo:write" to "return run scoreboard players set #test checks -12",
                "demo:fail" to "return run scoreboard players get #missing checks",
                "demo:continue" to "scoreboard players get #missing checks\nreturn 3",
            )
        assertEquals(-12, sandbox.runFunction("demo:write").returnValue)
        val failed = sandbox.runFunction("demo:fail")
        assertFalse(failed.success)
        assertEquals(0, failed.returnValue)
        assertEquals(3, sandbox.runFunction("demo:continue").returnValue)
    }

    @Test
    fun `data get distinguishes a single negative value from a path with multiple matches`() {
        val sandbox = sandbox()
        sandbox.executeCommand("data modify storage demo:n list set value [-1.25,4.5]")
        sandbox.executeCommand("execute store result score #value checks run data get storage demo:n list[0]")
        assertEquals(-2, sandbox.world.getScore("#value", "checks"))
        sandbox.executeCommand("execute store success score #ok checks run data get storage demo:n list[]")
        assertEquals(0, sandbox.world.scores[ScoreKey("#ok", "checks")])
    }

    @Test
    fun `macro arguments are bound before any function body side effects`() {
        val sandbox = sandbox("demo:macro" to "scoreboard players add #calls checks 1\n\$return \$(number)")
        sandbox.executeCommand("execute store result score #value checks run function demo:macro {number:-9}")
        assertEquals(-9, sandbox.world.getScore("#value", "checks"))
        sandbox.executeCommand("execute store result score #value checks run function demo:macro {}")
        assertEquals(0, sandbox.world.getScore("#value", "checks"))
        assertEquals(1, sandbox.world.getScore("#calls", "checks"))
    }

    @Test
    fun `store binds score holders before the command changes selection`() {
        val sandbox = sandbox("demo:retag" to "tag @e[tag=old] remove old\nreturn 7")
        sandbox.executeCommand("summon minecraft:marker 0 0 0 {Tags:[\"old\"]}")
        val marker = sandbox.world.entities.single { "old" in it.tags }
        sandbox.executeCommand("execute store result score @e[tag=old] checks run function demo:retag")
        assertEquals(7, sandbox.world.getScore(marker.scoreHolder, "checks"))
    }

    @Test
    fun `cross dimension execution uses the type of the source and destination`() {
        val root = writeExecuteFunctionPack(Files.createTempDirectory("dps-dimension-scale"))
        val ns = root.resolve("data/demo")
        Files.createDirectories(ns.resolve("dimension"))
        Files.createDirectories(ns.resolve("dimension_type"))
        Files.writeString(ns.resolve("dimension/outer.json"), """{"type":"demo:large"}""")
        Files.writeString(ns.resolve("dimension_type/large.json"), """{"coordinate_scale":2.0}""")
        val sandbox = createSandbox("26.3", listOf(root))
        sandbox.executeCommand("execute in demo:outer positioned 8.0 51 -4.0 in minecraft:the_nether run summon minecraft:marker")
        assertEquals(
            Position(2.0, 51.0, -1.0),
            sandbox.world.entities
                .single { it.type == ResourceLocation.parse("minecraft:marker") }
                .position,
        )
    }
}
