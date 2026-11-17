package moe.afox.dpsandbox.cli

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import moe.afox.dpsandbox.core.CommandTraceMode
import moe.afox.dpsandbox.core.FunctionSource
import moe.afox.dpsandbox.core.ResourceLocation
import moe.afox.dpsandbox.core.createFunctionSandbox
import moe.afox.dpsandbox.core.createFunctionSandboxFromString
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveDebugSessionTest {
    @Test
    fun `breakpoint suspends before side effects and stepping resumes exactly once`() {
        val sandbox =
            createFunctionSandboxFromString(
                functionText =
                    """
                    scoreboard objectives add value dummy
                    scoreboard players set counter value 0
                    scoreboard players add counter value 1
                    scoreboard players add counter value 10
                    """.trimIndent(),
                version = "26.3",
            )
        val events = LinkedBlockingQueue<Pair<String, JsonObject>>()
        val debug = LiveDebugSession(sandbox) { name, body -> events.add(name to body) }
        try {
            debug.dispatch("debug.setBreakpoints", json("""{"breakpoints":[{"function":"sandbox:main","line":3}]}"""))
            debug.dispatch("debug.start", json("""{"load":false,"functions":["sandbox:main"]}"""))
            val stop = events.poll(10, TimeUnit.SECONDS)!!
            assertEquals("debug.stopped", stop.first)
            assertEquals(
                0,
                stop.second
                    .getAsJsonObject("snapshot")
                    .getAsJsonObject("scores")
                    .getAsJsonObject("value")
                    .get("counter")
                    .asInt,
            )
            assertEquals(
                3,
                stop.second
                    .getAsJsonArray("frames")
                    .last()
                    .asJsonObject
                    .get("line")
                    .asInt,
            )
            debug.dispatch("debug.step", json("""{"mode":"into"}"""))
            val next = events.poll(10, TimeUnit.SECONDS)!!
            assertEquals("debug.stopped", next.first)
            assertEquals(
                1,
                next.second
                    .getAsJsonObject("snapshot")
                    .getAsJsonObject("scores")
                    .getAsJsonObject("value")
                    .get("counter")
                    .asInt,
            )
            debug.dispatch("debug.continue", JsonObject())
            assertEquals("debug.terminated", events.poll(10, TimeUnit.SECONDS)!!.first)
            assertEquals(11, sandbox.world.getScore("counter", "value"))
        } finally {
            debug.close()
        }
    }

    @Test
    fun `close releases a suspended execution`() {
        val sandbox = createFunctionSandboxFromString(functionText = "say must not run", version = "26.3")
        val events = LinkedBlockingQueue<Pair<String, JsonObject>>()
        val debug = LiveDebugSession(sandbox) { name, body -> events.add(name to body) }
        debug.dispatch("debug.start", json("""{"load":false,"functions":["sandbox:main"],"stopOnEntry":true}"""))
        assertEquals("debug.stopped", events.poll(10, TimeUnit.SECONDS)!!.first)
        debug.close()
        assertFalse(debug.active)
        assertTrue(sandbox.world.outputs.none { it.command == "say" })
    }

    @Test
    fun `263 native float storage predicates and integer conversion execute`() {
        val sandbox =
            createFunctionSandboxFromString(
                functionText =
                    """
                    data modify storage test:state a set value -3.75f
                    data modify storage test:state b set compute default float {type:"minecraft:add",inputs:[{type:"minecraft:storage",storage:"test:state",path:"a"},0.5]}
                    scoreboard objectives add values dummy
                    execute store result score result values run compute default integer {type:"minecraft:from_float",input:{type:"minecraft:storage",storage:"test:state",path:"b"}}
                    execute if predicate {type:"minecraft:float_value_check",value:{type:"minecraft:storage",storage:"test:state",path:"b"},test:{max:-3.0}} run scoreboard players set comparison values 1
                    """.trimIndent(),
                version = "26.3",
            )
        sandbox.runFunction("sandbox:main")
        assertEquals(
            -3.25f,
            sandbox.world
                .storage(ResourceLocation.parse("test:state"))
                .get("b")
                .asFloat,
        )
        assertEquals(-3, sandbox.world.getScore("result", "values"))
        assertEquals(1, sandbox.world.getScore("comparison", "values"))
    }

    @Test
    fun `running controls remain responsive and frozen state cannot be mutated by a client`() {
        val sandbox =
            createFunctionSandboxFromString(
                functionText = "scoreboard objectives add counter dummy\n" + "scoreboard players add value counter 1\n".repeat(500),
                version = "26.3",
            )
        sandbox.world.commandTraceMode = CommandTraceMode.OFF
        val events = LinkedBlockingQueue<Pair<String, JsonObject>>()
        val debug = LiveDebugSession(sandbox) { name, body -> events.add(name to body) }
        try {
            debug.dispatch("debug.start", json("""{"load":false,"functions":["sandbox:main"]}"""))
            debug.dispatch("debug.pause", JsonObject())
            assertEquals("debug.stopped", events.poll(10, TimeUnit.SECONDS)!!.first)
            val copy = debug.dispatch("debug.state", JsonObject())
            copy.addProperty("command", "changed by client")
            assertTrue(debug.dispatch("debug.state", JsonObject()).get("command").asString != "changed by client")
            debug.dispatch("debug.continue", JsonObject())
            debug.dispatch("debug.stop", JsonObject())
            assertEquals("debug.terminated", events.poll(10, TimeUnit.SECONDS)!!.first)
            assertFalse(debug.active)
        } finally {
            debug.close()
        }
    }

    @Test
    fun `command errors emit a diagnostic followed by termination`() {
        val sandbox = createFunctionSandboxFromString(functionText = "unknown_command", version = "26.3")
        val events = LinkedBlockingQueue<Pair<String, JsonObject>>()
        val debug = LiveDebugSession(sandbox) { name, body -> events.add(name to body) }
        try {
            debug.dispatch("debug.start", json("""{"load":false,"functions":["sandbox:main"]}"""))
            assertEquals("debug.error", events.poll(10, TimeUnit.SECONDS)!!.first)
            assertEquals("debug.terminated", events.poll(10, TimeUnit.SECONDS)!!.first)
            assertFalse(debug.active)
        } finally {
            debug.close()
        }
    }

    @Test
    fun `inspection reads entity block and contextual scores from detached state`() {
        val sandbox =
            createFunctionSandbox(
                "26.3",
                listOf(
                    FunctionSource.text("sandbox:main", "execute as @e[tag=probe,limit=1] at @s run function sandbox:read"),
                    FunctionSource.text("sandbox:read", "say must not run"),
                ),
            )
        sandbox.world.commandTraceMode = CommandTraceMode.OFF
        listOf(
            "summon minecraft:pig 12 64 0 {Health:7.0f,Tags:[\"probe\"]}",
            "setblock 12 63 0 minecraft:chest",
            "scoreboard objectives add points dummy",
            "scoreboard players set @e[tag=probe] points 13",
        ).forEach { sandbox.executeCommand(it) }
        val outputsBefore = sandbox.world.outputs.size
        val events = LinkedBlockingQueue<Pair<String, JsonObject>>()
        val debug = LiveDebugSession(sandbox) { name, body -> events.add(name to body) }
        try {
            debug.dispatch("debug.setBreakpoints", json("""{"breakpoints":[{"function":"sandbox:read","line":1}]}"""))
            debug.dispatch("debug.start", json("""{"load":false,"functions":["sandbox:main"]}"""))
            val stop = events.poll(10, TimeUnit.SECONDS)!!
            assertEquals("debug.stopped", stop.first)
            val activation =
                stop.second
                    .getAsJsonArray("frames")
                    .last()
                    .asJsonObject
                    .get("activation")
                    .asLong
            val frozen = debug.dispatch("debug.state", JsonObject())

            fun inspect(command: String) =
                debug
                    .dispatch(
                        "debug.inspect",
                        JsonObject().apply {
                            addProperty("frame", activation)
                            addProperty("command", command)
                        },
                    ).get("value")
            assertEquals(7.0f, inspect("data get entity @s Health").asFloat)
            assertEquals(12, inspect("data get block ~ ~-1 ~ x").asInt)
            assertEquals(12, inspect("data get block ^ ^-1 ^ x").asInt)
            assertEquals(13, inspect("scoreboard players get @s points").asInt)
            assertFailsWith<IllegalArgumentException> { inspect("scoreboard players set @s points 99") }
            assertEquals(frozen, debug.dispatch("debug.state", JsonObject()))
        } finally {
            debug.close()
        }
        assertEquals(outputsBefore, sandbox.world.outputs.size)
    }

    private fun json(text: String) = JsonParser.parseString(text).asJsonObject
}
