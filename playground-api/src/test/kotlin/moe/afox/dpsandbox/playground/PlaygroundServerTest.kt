package moe.afox.dpsandbox.playground

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Path
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PlaygroundServerTest {
    @Test
    fun `cells preserve state and reset clears it`() =
        testApplication {
            application { playgroundModule(testConfig()) }
            val client = createClient { install(WebSockets) }
            client.webSocket(
                request = {
                    url("/v1/playground")
                    header(HttpHeaders.Origin, ALLOWED_ORIGIN)
                },
            ) {
                sendRequest("create", "session.create", "\"version\":\"26.2\"")
                assertEquals("session.ready", receiveFor("create").string("type"))

                sendRequest(
                    "first",
                    "cell.execute",
                    "\"cellId\":\"one\",\"source\":\"scoreboard objectives add runs dummy\\nscoreboard players set #web runs 2\"",
                )
                val first = receiveFor("first", "cell.output")
                assertTrue(first.string("summary")!!.startsWith("Executed 2 commands"), first.toString())

                sendRequest("second", "cell.execute", "\"cellId\":\"two\",\"source\":\"scoreboard players add #web runs 3\"")
                val second = receiveFor("second", "cell.output")
                assertEquals(1, second.getAsJsonObject("result").get("commands").asInt)

                sendRequest("reset", "session.reset")
                assertEquals("reset", receiveFor("reset").string("reason"))
                sendRequest("after", "cell.execute", "\"cellId\":\"three\",\"source\":\"scoreboard players add #web runs 1\"")
                val failure = receiveFor("after", "cell.error")
                assertEquals("COMMAND_ERROR", failure.getAsJsonObject("error").string("code"))
            }
        }

    @Test
    fun `concurrent sessions are isolated`() =
        testApplication {
            application { playgroundModule(testConfig()) }
            val client = createClient { install(WebSockets) }
            client.webSocket(
                request = {
                    url("/v1/playground")
                    header(HttpHeaders.Origin, ALLOWED_ORIGIN)
                },
            ) {
                sendRequest("a-create", "session.create")
                val firstId = receiveFor("a-create").string("sessionId")
                sendRequest("a-cell", "cell.execute", "\"cellId\":\"a\",\"source\":\"scoreboard objectives add private dummy\"")
                receiveFor("a-cell", "cell.output")

                client.webSocket(
                    request = {
                        url("/v1/playground")
                        header(HttpHeaders.Origin, ALLOWED_ORIGIN)
                    },
                ) {
                    sendRequest("b-create", "session.create")
                    val secondId = receiveFor("b-create").string("sessionId")
                    assertNotEquals(firstId, secondId)
                    sendRequest("b-cell", "cell.execute", "\"cellId\":\"b\",\"source\":\"scoreboard players set #x private 1\"")
                    assertEquals("COMMAND_ERROR", receiveFor("b-cell", "cell.error").getAsJsonObject("error").string("code"))
                }
            }
        }

    @Test
    fun `completion diagnostics interrupt and PNG rendering use typed events`() =
        testApplication {
            application { playgroundModule(testConfig()) }
            val client = createClient { install(WebSockets) }
            client.webSocket(
                request = {
                    url("/v1/playground")
                    header(HttpHeaders.Origin, ALLOWED_ORIGIN)
                },
            ) {
                sendRequest("create", "session.create")
                receiveFor("create")

                sendRequest("complete", "cell.complete", "\"cellId\":\"c\",\"source\":\"setb\",\"cursor\":4")
                val completion = receiveFor("complete", "cell.output")
                assertTrue(completion.getAsJsonObject("result").getAsJsonArray("suggestions").size() > 0)

                sendRequest("check", "cell.check", "\"cellId\":\"c\",\"source\":\"setblock 0 0 0\"")
                val diagnostics = receiveFor("check", "diagnostic").getAsJsonArray("diagnostics")
                assertEquals(1, diagnostics.size())
                assertEquals(1, diagnostics[0].asJsonObject.get("line").asInt)

                sendRequest("execute", "cell.execute", "\"cellId\":\"c\",\"source\":\"setblock 0 0 2 minecraft:stone\"")
                receiveFor("execute", "cell.output")
                sendRequest("render", "cell.render", "\"cellId\":\"c\",\"render\":{\"width\":64,\"height\":64}")
                val rendered = receiveFor("render", "cell.render")
                assertEquals("image/png", rendered.string("mimeType"))
                val png = Base64.getDecoder().decode(rendered.string("data"))
                assertTrue(png.take(8).toByteArray().contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)))

                sendRequest("interrupt", "session.interrupt")
                assertEquals("idle", receiveFor("interrupt", "cell.status").string("status"))
            }
        }

    @Test
    fun `origin profile cell render output and request limits are enforced`() =
        testApplication {
            application {
                playgroundModule(
                    testConfig(
                        limits =
                            PlaygroundLimits(
                                maximumCellBytes = 20,
                                maximumOutputBytes = 200,
                                maximumRenderWidth = 64,
                                maximumRenderHeight = 64,
                                maximumRequestsPerMinute = 20,
                            ),
                    ),
                )
            }
            val client = createClient { install(WebSockets) }

            client.webSocket(
                request = {
                    url("/v1/playground")
                    header(HttpHeaders.Origin, "https://evil.example")
                },
            ) {
                assertEquals("ORIGIN_NOT_ALLOWED", receiveJson().getAsJsonObject("error").string("code"))
            }

            client.webSocket(
                request = {
                    url("/v1/playground")
                    header(HttpHeaders.Origin, ALLOWED_ORIGIN)
                },
            ) {
                send(Frame.Text("not-json"))
                assertEquals("INVALID_REQUEST", receiveJson().getAsJsonObject("error").string("code"))
                sendRequest("profile", "session.create", "\"version\":\"1.20.4\"")
                assertEquals("PROFILE_NOT_ALLOWED", receiveFor("profile", "cell.error").getAsJsonObject("error").string("code"))
                sendRequest("preset", "session.create", "\"preset\":\"missing\"")
                assertEquals("PRESET_NOT_ALLOWED", receiveFor("preset", "cell.error").getAsJsonObject("error").string("code"))
                sendRequest("create", "session.create")
                receiveFor("create")
                sendRequest("large", "cell.execute", "\"cellId\":\"c\",\"source\":\"say 12345678901234567890\"")
                assertEquals("CELL_TOO_LARGE", receiveFor("large", "cell.error").getAsJsonObject("error").string("code"))
                sendRequest("render", "cell.render", "\"render\":{\"width\":65,\"height\":64}")
                assertEquals("RENDER_SIZE_LIMIT", receiveFor("render", "cell.error").getAsJsonObject("error").string("code"))
                sendRequest("output", "cell.execute", "\"cellId\":\"c\",\"source\":\"say hello\"")
                assertEquals("OUTPUT_LIMIT", receiveFor("output", "cell.error").getAsJsonObject("error").string("code"))
            }
        }

    @Test
    fun `serve process is destroyed on close`() =
        runBlocking {
            val process = ServeProcess.start("java", cliJar().toString())
            assertTrue(process.isAlive)
            process.close()
            assertFalse(process.isAlive)
        }

    @Test
    fun `idle sessions announce expiry and clean up`() =
        testApplication {
            application {
                playgroundModule(testConfig(limits = PlaygroundLimits(idleTimeout = 250.milliseconds)))
            }
            val client = createClient { install(WebSockets) }
            client.webSocket(
                request = {
                    url("/v1/playground")
                    header(HttpHeaders.Origin, ALLOWED_ORIGIN)
                },
            ) {
                sendRequest("create", "session.create")
                receiveFor("create")
                val closed = receiveJson()
                assertEquals("session.closed", closed.string("type"))
                assertEquals("IDLE_TIMEOUT", closed.string("code"))
            }
        }

    private fun testConfig(limits: PlaygroundLimits = PlaygroundLimits()): PlaygroundConfig =
        PlaygroundConfig(
            cliJar = cliJar(),
            allowedOrigins = setOf(ALLOWED_ORIGIN),
            allowedProfiles = setOf("26.2"),
            limits = limits,
        )

    private fun cliJar(): Path = Path.of(requireNotNull(System.getProperty("dps.playground.testCliJar")))

    companion object {
        private const val ALLOWED_ORIGIN = "https://docs.example.test"
    }
}

private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.sendRequest(
    id: String,
    type: String,
    fields: String = "",
) {
    val suffix = if (fields.isBlank()) "" else ",$fields"
    send(Frame.Text("{\"id\":\"$id\",\"type\":\"$type\"$suffix}"))
}

private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.receiveJson(): JsonObject =
    withTimeout(20.seconds) {
        JsonParser.parseString((incoming.receive() as Frame.Text).readText()).asJsonObject
    }

private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.receiveFor(
    requestId: String,
    type: String? = null,
): JsonObject {
    while (true) {
        val event = receiveJson()
        if (event.get("requestId")?.asString == requestId && (type == null || event.string("type") == type)) return event
    }
}
