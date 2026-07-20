package moe.afox.dpsandbox.cli

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.io.StringWriter
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServeRenderTest {
    @Test
    fun `serve renders current world as PNG without changing snapshot`() {
        val responses =
            runServe(
                """
                {"id":"create","method":"createSandbox","params":{"version":"26.2"}}
                {"id":"block","method":"runCommand","params":{"command":"setblock 0 0 2 minecraft:stone"}}
                {"id":"before","method":"snapshot"}
                {"id":"render","method":"render","params":{"width":320,"height":180,"showDebugOverlay":true}}
                {"id":"after","method":"snapshot"}
                """.trimIndent(),
            )

        val hello = responses.first().getAsJsonObject("result")
        assertTrue(hello.getAsJsonObject("capabilities").get("render").asBoolean)
        val rendered = responses.byId("render")
        assertTrue(rendered.get("ok").asBoolean, rendered.toString())
        val result = rendered.getAsJsonObject("result")
        assertEquals("image/png", result.get("mimeType").asString)
        val image = ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(result.get("data").asString)))
        assertEquals(320, image.width)
        assertEquals(180, image.height)
        assertFalse(result.getAsJsonObject("metadata").get("visualParity").asBoolean)
        assertEquals(
            responses.byId("before").get("result").toString(),
            responses.byId("after").get("result").toString(),
        )
    }

    @Test
    fun `serve reports missing camera player without ending session`() {
        val responses =
            runServe(
                """
                {"id":"create","method":"createSandbox","params":{"version":"26.2"}}
                {"id":"render","method":"render","params":{"cameraPlayer":"Alex","width":320,"height":180}}
                {"id":"state","method":"state"}
                """.trimIndent(),
            )

        val failure = responses.byId("render")
        assertFalse(failure.get("ok").asBoolean)
        assertEquals("MISSING_CONTEXT", failure.getAsJsonObject("error").get("code").asString)
        assertTrue(responses.byId("state").get("ok").asBoolean)
    }

    private fun runServe(input: String): List<JsonObject> {
        val output = StringWriter()
        val writer = output.buffered()
        ServeSession().run(StringReader(input).buffered(), writer)
        writer.flush()
        return output
            .toString()
            .lineSequence()
            .filter(String::isNotBlank)
            .map { JsonParser.parseString(it).asJsonObject }
            .toList()
    }

    private fun List<JsonObject>.byId(id: String): JsonObject =
        single { response -> response.get("id")?.takeIf { it.isJsonPrimitive }?.asString == id }
}
