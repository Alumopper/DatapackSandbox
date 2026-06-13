package net.datapacksandbox.core

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SandboxQuickTestTest {
    private fun fixturePack(): Path =
        Path.of("src/test/resources/packs/counter")

    @Test
    fun `runs quick code tests from core api`() {
        val report = SandboxQuickTest.create(listOf(fixturePack()))
            .load()
            .ticks(20)
            .assertScore("#clock", "ticks", 20)
            .requirePassed()

        assertTrue(report.passed)
        assertEquals(20, report.snapshot.asJsonObject.get("scores").asJsonObject.get("ticks").asJsonObject.get("#clock").asInt)
    }

    @Test
    fun `quick code tests collect assertion failures`() {
        val error = assertFailsWith<SandboxQuickTestAssertionError> {
            SandboxQuickTest.create(listOf(fixturePack()))
                .assertScore("#clock", "ticks", 1)
                .requirePassed()
        }

        assertTrue(error.report.failures.single().contains("expected 1 but was 0"))
    }

    @Test
    fun `records keyboard and mouse player input events`() {
        val scenario = SandboxQuickTest.create(listOf(fixturePack()))
            .keyInput("Steve", "key.jump")
            .mouseInput("Steve", "left", "click", 12.0, 8.0)
            .assertPlayerLastInput("Steve", "mouse", "left", "click")
            .requirePassed()

        val player = scenario.snapshot.asJsonObject.get("players").asJsonObject.get("Steve").asJsonObject
        assertEquals("mouse", player.get("lastInput").asJsonObject.get("device").asString)
        assertEquals(2, player.get("inputEvents").asJsonArray.size())
    }
}
