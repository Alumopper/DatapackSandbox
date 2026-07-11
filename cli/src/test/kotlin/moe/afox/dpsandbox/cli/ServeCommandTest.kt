package moe.afox.dpsandbox.cli

import com.google.gson.JsonParser
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServeCommandTest {
    @Test
    fun `serve creates sandbox and runs command`() {
        val responses = runServe(
            """
            {"id":"create","method":"createSandbox","params":{"version":"26.2"}}
            {"id":"cmd","method":"runCommand","params":{"command":"scoreboard objectives add runs dummy"}}
            {"id":"set","method":"runCommand","params":{"command":"scoreboard players set #serve runs 7"}}
            {"id":"snapshot","method":"snapshot"}
            """.trimIndent(),
        )

        assertTrue(responses[0].get("ok").asBoolean, responses[0].toString())
        assertEquals("dps-jsonl", responses[0].getAsJsonObject("result").get("protocol").asString)
        assertTrue(responses.byId("create").get("ok").asBoolean)
        assertEquals(1, responses.byId("set").getAsJsonObject("result").get("commands").asInt)
        val snapshot = responses.byId("snapshot").getAsJsonObject("result")
        assertEquals(7, snapshot.getAsJsonObject("scores").getAsJsonObject("runs").get("#serve").asInt)
    }

    @Test
    fun `serve reports structured command failures`() {
        val responses = runServe(
            """
            {"id":"create","method":"createSandbox","params":{"version":"26.2"}}
            {"id":"bad","method":"runCommand","params":{"command":"scoreboard objectives add"}}
            """.trimIndent(),
        )

        val failure = responses.byId("bad")
        assertFalse(failure.get("ok").asBoolean)
        assertTrue(failure.getAsJsonObject("error").get("code").asString.isNotBlank())
        assertTrue(failure.getAsJsonObject("error").get("message").asString.isNotBlank())
    }

    @Test
    fun `serve creates synthetic function sandbox`() {
        val responses = runServe(
            """
            {"id":"create","method":"createSandbox","params":{"version":"26.2","functionSources":[{"id":"demo:main","text":"scoreboard objectives add runs dummy\nscoreboard players set #function runs 3"}]}}
            {"id":"run","method":"runFunction","params":{"id":"demo:main"}}
            """.trimIndent(),
        )

        val run = responses.byId("run")
        assertTrue(run.get("ok").asBoolean, run.toString())
        assertEquals(2, run.getAsJsonObject("result").get("commands").asInt)
        assertTrue(run.getAsJsonObject("result").getAsJsonArray("snapshotDiffs").size() > 0)
    }

    @Test
    fun `serve updates function source while preserving active world`() {
        val responses = runServe(
            """
            {"id":"create","method":"createSandbox","params":{"version":"26.2"}}
            {"id":"objective","method":"runCommand","params":{"command":"scoreboard objectives add runs dummy"}}
            {"id":"source","method":"upsertFunctionSource","params":{"id":"demo:main","text":"scoreboard players add #active runs 2"}}
            {"id":"run","method":"runFunction","params":{"id":"demo:main"}}
            {"id":"snapshot","method":"snapshot"}
            """.trimIndent(),
        )

        assertTrue(responses.byId("source").get("ok").asBoolean)
        assertTrue(responses.byId("run").get("ok").asBoolean)
        assertEquals(2, responses.byId("snapshot").getAsJsonObject("result").getAsJsonObject("scores").getAsJsonObject("runs").get("#active").asInt)
    }

    @Test
    fun `serve runs manifest in active sandbox`() {
        val manifest = Files.createTempFile("dps-active-", ".dps.json")
        try {
            Files.writeString(
                manifest,
                """{"steps":[{"command":"scoreboard players add #manifest runs 3"}],"assertions":[{"score":{"objective":"runs","target":"#manifest","equals":3}}]}""",
            )
            val request = com.google.gson.JsonObject().also { root ->
                root.addProperty("id", "manifest")
                root.addProperty("method", "runManifest")
                root.add("params", com.google.gson.JsonObject().also { params -> params.addProperty("path", manifest.toString()) })
            }
            val responses = runServe(
                """
                {"id":"create","method":"createSandbox","params":{"version":"26.2"}}
                {"id":"objective","method":"runCommand","params":{"command":"scoreboard objectives add runs dummy"}}
                $request
                {"id":"snapshot","method":"snapshot"}
                """.trimIndent(),
            )

            assertTrue(responses.byId("manifest").get("ok").asBoolean, responses.byId("manifest").toString())
            assertTrue(responses.byId("manifest").getAsJsonObject("result").get("passed").asBoolean)
            assertEquals(3, responses.byId("snapshot").getAsJsonObject("result").getAsJsonObject("scores").getAsJsonObject("runs").get("#manifest").asInt)
        } finally {
            Files.deleteIfExists(manifest)
        }
    }

    @Test
    fun `serve completes and checks commands without mutating active world`() {
        val responses = runServe(
            """
            {"id":"create","method":"createSandbox","params":{"version":"26.2"}}
            {"id":"objective","method":"runCommand","params":{"command":"scoreboard objectives add runs dummy"}}
            {"id":"complete","method":"completions","params":{"buffer":"scoreboard players set #check r","cursor":31}}
            {"id":"valid","method":"checkCommand","params":{"command":"scoreboard players set #check runs 4"}}
            {"id":"invalid","method":"checkCommand","params":{"command":"scoreboard players set"}}
            {"id":"snapshot","method":"snapshot"}
            """.trimIndent(),
        )

        val suggestions = responses.byId("complete").getAsJsonObject("result").getAsJsonArray("suggestions")
        assertTrue(suggestions.any { it.asJsonObject.get("value").asString == "runs" })
        assertTrue(responses.byId("valid").get("ok").asBoolean, responses.byId("valid").toString())
        assertTrue(responses.byId("valid").getAsJsonObject("result").get("valid").asBoolean)
        assertFalse(responses.byId("invalid").getAsJsonObject("result").get("valid").asBoolean)
        val scores = responses.byId("snapshot").getAsJsonObject("result").getAsJsonObject("scores")
        assertFalse(scores.has("runs"), "Command checks must not mutate active scores")
    }

    private fun runServe(input: String): List<com.google.gson.JsonObject> {
        val writer = StringWriter()
        ServeSession().run(StringReader(input).buffered(), writer.buffered())
        return writer.toString()
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { JsonParser.parseString(it).asJsonObject }
            .toList()
    }

    private fun List<com.google.gson.JsonObject>.byId(id: String): com.google.gson.JsonObject =
        single { response -> response.get("id")?.takeIf { it.isJsonPrimitive }?.asString == id }
}
