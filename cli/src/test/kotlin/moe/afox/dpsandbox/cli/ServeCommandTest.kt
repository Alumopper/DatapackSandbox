package moe.afox.dpsandbox.cli

import com.google.gson.JsonParser
import moe.afox.dpsandbox.core.VersionProfiles
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServeCommandTest {
    @Test
    fun `serve starts every built in version profile`() {
        val requests =
            VersionProfiles.all.joinToString("\n") { profile ->
                """{"id":"${profile.id}","method":"createSandbox","params":{"version":"${profile.id}"}}"""
            }
        val responses = runServe(requests)

        assertTrue(responses.first().has("id"), responses.first().toString())
        assertTrue(responses.first().get("id").isJsonNull, responses.first().toString())
        VersionProfiles.all.forEach { profile ->
            val response = responses.byId(profile.id)
            assertTrue(response.get("ok").asBoolean, response.toString())
            assertEquals(profile.id, response.getAsJsonObject("result").get("version").asString)
        }
    }

    @Test
    fun `serve creates sandbox and runs command`() {
        val responses =
            runServe(
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
        assertEquals(
            1,
            responses
                .byId("set")
                .getAsJsonObject("result")
                .get("commands")
                .asInt,
        )
        val snapshot = responses.byId("snapshot").getAsJsonObject("result")
        assertEquals(
            7,
            snapshot
                .getAsJsonObject("scores")
                .getAsJsonObject("runs")
                .get("#serve")
                .asInt,
        )
    }

    @Test
    fun `serve reports structured command failures`() {
        val responses =
            runServe(
                """
                {"id":"create","method":"createSandbox","params":{"version":"26.2"}}
                {"id":"bad","method":"runCommand","params":{"command":"scoreboard objectives add"}}
                """.trimIndent(),
            )

        val failure = responses.byId("bad")
        assertFalse(failure.get("ok").asBoolean)
        assertTrue(
            failure
                .getAsJsonObject("error")
                .get("code")
                .asString
                .isNotBlank(),
        )
        assertTrue(
            failure
                .getAsJsonObject("error")
                .get("message")
                .asString
                .isNotBlank(),
        )
    }

    @Test
    fun `serve creates synthetic function sandbox`() {
        val responses =
            runServe(
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
    fun `serve exposes filtered coverage and reset`() {
        val responses =
            runServe(
                """
                {"id":"create","method":"createSandbox","params":{"version":"26.2","functionSources":[{"id":"demo:main","text":"say first\nsay second"},{"id":"demo:unused","text":"say unused"}]}}
                {"id":"run","method":"runFunction","params":{"id":"demo:main"}}
                {"id":"coverage","method":"coverage","params":{"minimumLine":100,"include":"demo:*"}}
                {"id":"reset","method":"resetCoverage"}
                {"id":"after","method":"coverage","params":{"include":"demo:main"}}
                """.trimIndent(),
            )

        val coverage = responses.byId("coverage").getAsJsonObject("result")
        assertTrue(!coverage.get("passed").asBoolean)
        assertEquals(2, coverage.get("coveredLines").asInt)
        assertEquals(3, coverage.get("totalLines").asInt)
        assertTrue("below required 100.00%" in coverage.getAsJsonArray("failures")[0].asString)
        assertTrue(
            responses
                .byId("reset")
                .getAsJsonObject("result")
                .get("reset")
                .asBoolean,
        )
        val after = responses.byId("after").getAsJsonObject("result")
        assertEquals(0, after.get("coveredLines").asInt)
        assertEquals(2, after.get("totalLines").asInt)
    }

    @Test
    fun `serve updates function source while preserving active world`() {
        val responses =
            runServe(
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
        assertEquals(
            2,
            responses
                .byId("snapshot")
                .getAsJsonObject("result")
                .getAsJsonObject("scores")
                .getAsJsonObject("runs")
                .get("#active")
                .asInt,
        )
    }

    @Test
    fun `serve runs manifest in active sandbox`() {
        val manifest = Files.createTempFile("dps-active-", ".dps.json")
        try {
            Files.writeString(
                manifest,
                """{"steps":[{"command":"scoreboard players add #manifest runs 3"}],"assertions":[{"score":{"objective":"runs","target":"#manifest","equals":3}}]}""",
            )
            val request =
                com.google.gson.JsonObject().also { root ->
                    root.addProperty("id", "manifest")
                    root.addProperty("method", "runManifest")
                    root.add(
                        "params",
                        com.google.gson
                            .JsonObject()
                            .also { params -> params.addProperty("path", manifest.toString()) },
                    )
                }
            val responses =
                runServe(
                    """
                    {"id":"create","method":"createSandbox","params":{"version":"26.2"}}
                    {"id":"objective","method":"runCommand","params":{"command":"scoreboard objectives add runs dummy"}}
                    $request
                    {"id":"snapshot","method":"snapshot"}
                    """.trimIndent(),
                )

            assertTrue(responses.byId("manifest").get("ok").asBoolean, responses.byId("manifest").toString())
            assertTrue(
                responses
                    .byId("manifest")
                    .getAsJsonObject("result")
                    .get("passed")
                    .asBoolean,
            )
            assertEquals(
                3,
                responses
                    .byId(
                        "snapshot",
                    ).getAsJsonObject("result")
                    .getAsJsonObject("scores")
                    .getAsJsonObject("runs")
                    .get("#manifest")
                    .asInt,
            )
        } finally {
            Files.deleteIfExists(manifest)
        }
    }

    @Test
    fun `serve completes and checks commands without mutating active world`() {
        val responses =
            runServe(
                """
                {"id":"create","method":"createSandbox","params":{"version":"26.2"}}
                {"id":"objective","method":"runCommand","params":{"command":"scoreboard objectives add runs dummy"}}
                {"id":"complete","method":"completions","params":{"buffer":"scoreboard players set #check r","cursor":31}}
                {"id":"valid","method":"checkCommand","params":{"command":"scoreboard players set #check runs 4"}}
                {"id":"invalid","method":"checkCommand","params":{"command":"scoreboard players set"}}
                {"id":"batch","method":"checkCommands","params":{"commands":["scoreboard objectives add preview dummy","scoreboard players set #check preview 4","scoreboard players set"]}}
                {"id":"snapshot","method":"snapshot"}
                """.trimIndent(),
            )

        val suggestions = responses.byId("complete").getAsJsonObject("result").getAsJsonArray("suggestions")
        assertTrue(suggestions.any { it.asJsonObject.get("value").asString == "runs" })
        assertTrue(responses.byId("valid").get("ok").asBoolean, responses.byId("valid").toString())
        assertTrue(
            responses
                .byId("valid")
                .getAsJsonObject("result")
                .get("valid")
                .asBoolean,
        )
        assertFalse(
            responses
                .byId("invalid")
                .getAsJsonObject("result")
                .get("valid")
                .asBoolean,
        )
        val batch = responses.byId("batch").getAsJsonObject("result").getAsJsonArray("checks")
        assertEquals(3, batch.size())
        assertTrue(batch[0].asJsonObject.get("valid").asBoolean)
        assertTrue(batch[1].asJsonObject.get("valid").asBoolean)
        assertFalse(batch[2].asJsonObject.get("valid").asBoolean)
        val scores = responses.byId("snapshot").getAsJsonObject("result").getAsJsonObject("scores")
        assertFalse(scores.has("runs"), "Command checks must not mutate active scores")
        assertFalse(scores.has("preview"), "Batched command checks must not mutate active scores")
    }

    @Test
    fun `serve rejects oversized request lines without retaining unbounded input`() {
        val responses = runServe("x".repeat(1024 * 1024 + 1))

        val failure = responses.last()
        assertFalse(failure.get("ok").asBoolean)
        assertTrue(
            failure
                .getAsJsonObject("error")
                .get("message")
                .asString
                .contains("request exceeds character limit"),
            failure.toString(),
        )
    }

    @Test
    fun `serve paginates event lists without changing requested offsets`() {
        val responses =
            runServe(
                """
                {"id":"create","method":"createSandbox","params":{"version":"26.2"}}
                {"id":"one","method":"runCommand","params":{"command":"say one"}}
                {"id":"two","method":"runCommand","params":{"command":"say two"}}
                {"id":"tail","method":"outputs","params":{"from":1}}
                {"id":"past","method":"outputs","params":{"from":99}}
                {"id":"negative","method":"outputs","params":{"from":-1}}
                """.trimIndent(),
            )

        val tail = responses.byId("tail").getAsJsonObject("result")
        assertEquals(1, tail.get("from").asInt)
        assertEquals(2, tail.get("total").asInt)
        assertEquals(1, tail.getAsJsonArray("outputs").size())

        val past = responses.byId("past").getAsJsonObject("result")
        assertEquals(99, past.get("from").asInt)
        assertTrue(past.getAsJsonArray("outputs").isEmpty)

        val negative = responses.byId("negative").getAsJsonObject("result")
        assertEquals(0, negative.get("from").asInt)
        assertEquals(2, negative.getAsJsonArray("outputs").size())
    }

    @Test
    fun `serve saves restores and lists reusable checkpoints`() {
        val responses =
            runServe(
                """
                {"id":"create","method":"createSandbox","params":{"version":"26.2"}}
                {"id":"objective","method":"runCommand","params":{"command":"scoreboard objectives add runs dummy"}}
                {"id":"initial","method":"runCommand","params":{"command":"scoreboard players set #checkpoint runs 3"}}
                {"id":"save","method":"saveCheckpoint","params":{"name":"editor"}}
                {"id":"mutate","method":"runCommand","params":{"command":"scoreboard players set #checkpoint runs 9"}}
                {"id":"restore","method":"restoreCheckpoint","params":{"name":"editor"}}
                {"id":"snapshot","method":"snapshot"}
                {"id":"list","method":"checkpoints"}
                {"id":"delete","method":"deleteCheckpoint","params":{"name":"editor"}}
                """.trimIndent(),
            )

        assertEquals(listOf("editor"), responses.byId("save").resultArray("names"))
        assertEquals(
            "restored",
            responses
                .byId("restore")
                .getAsJsonObject("result")
                .get("action")
                .asString,
        )
        assertEquals(
            3,
            responses
                .byId("snapshot")
                .getAsJsonObject("result")
                .getAsJsonObject("scores")
                .getAsJsonObject("runs")
                .get("#checkpoint")
                .asInt,
        )
        assertEquals(listOf("editor"), responses.byId("list").resultArray("names"))
        assertTrue(
            responses
                .byId("delete")
                .getAsJsonObject("result")
                .get("changed")
                .asBoolean,
        )
        assertTrue(responses.byId("delete").resultArray("names").isEmpty())
    }

    @Test
    fun `serve exposes effective function source and editor capabilities`() {
        val responses =
            runServe(
                """
                {"id":"create","method":"createSandbox","params":{"version":"26.2","functionSources":[{"id":"demo:main","text":"say first\nsay second"}]}}
                {"id":"source","method":"functionSource","params":{"id":"demo:main"}}
                """.trimIndent(),
            )

        val capabilities = responses.first().getAsJsonObject("result").getAsJsonObject("capabilities")
        assertTrue(capabilities.get("checkpoints").asBoolean)
        assertTrue(capabilities.get("functionSource").asBoolean)
        assertTrue(capabilities.get("pagedEvents").asBoolean)
        assertTrue(capabilities.get("coverage").asBoolean)
        assertTrue(capabilities.get("commandDiagnostics").asBoolean)
        val source = responses.byId("source").getAsJsonObject("result")
        assertEquals("demo:main", source.get("id").asString)
        assertEquals("say first\nsay second", source.get("source").asString)
    }

    private fun runServe(input: String): List<com.google.gson.JsonObject> {
        val writer = StringWriter()
        ServeSession().run(StringReader(input).buffered(), writer.buffered())
        return writer
            .toString()
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { JsonParser.parseString(it).asJsonObject }
            .toList()
    }

    private fun List<com.google.gson.JsonObject>.byId(id: String): com.google.gson.JsonObject = single { response -> response.get("id")?.takeIf { it.isJsonPrimitive }?.asString == id }

    private fun com.google.gson.JsonObject.resultArray(name: String): List<String> =
        getAsJsonObject("result").getAsJsonArray(name).map { it.asString }
}
