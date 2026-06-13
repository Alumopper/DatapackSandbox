package net.datapacksandbox.cli

import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManifestRunnerTest {
    @Test
    fun `runs a manifest check`() {
        val path = Path.of("src/test/resources/cases/counter.dps.json")

        val result = ManifestRunner.run(path)

        assertTrue(result.passed, result.messages.joinToString())
    }

    @Test
    fun `records manifest output events`() {
        val path = Path.of("../examples/full-stack/full-stack.dps.json")

        val result = ManifestRunner.run(path)

        assertTrue(result.passed, result.messages.joinToString())
        assertEquals("tellraw", result.outputs.last { it.command == "tellraw" }.command)
    }

    @Test
    fun `runs keyboard input events from manifests`() {
        val dir = Files.createTempDirectory("dps-input-manifest")
        val pack = Path.of("../core/src/test/resources/packs/counter").toAbsolutePath().normalize().toString().replace("\\", "\\\\")
        val manifest = dir.resolve("input.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.1.2",
              "packs": ["$pack"],
              "steps": [
                { "event": { "player": "Steve", "type": "key_input", "key": "key.jump", "action": "press" } }
              ],
              "assertions": [
                { "player": { "name": "Steve", "lastInput": { "device": "keyboard", "code": "key.jump", "action": "press" } } }
              ]
            }
            """.trimIndent(),
        )

        val result = ManifestRunner.run(manifest)

        assertTrue(result.passed, result.messages.joinToString())
    }

    @Test
    fun `discovers manifests in directories`() {
        val manifests = ManifestRunner.discover(listOf(Path.of("src/test/resources/cases")))

        assertEquals(listOf(Path.of("src/test/resources/cases/counter.dps.json").toAbsolutePath().normalize()), manifests)
    }
}
