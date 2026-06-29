package moe.afox.dpsandbox.cli

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
    fun `runs manifest output assertions`() {
        val dir = Files.createTempDirectory("dps-output-manifest")
        val pack = Path.of("../examples/full-stack/pack").toAbsolutePath().normalize().toString().replace("\\", "\\\\")
        val manifest = dir.resolve("outputs.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.2",
              "packs": ["$pack"],
              "steps": [
                { "command": "say hello from manifest" },
                { "command": "tellraw Steve {\"text\":\"gold\",\"color\":\"yellow\"}" }
              ],
              "assertions": [
                {
                  "output": {
                    "command": "say",
                    "channel": "chat",
                    "target": "Steve",
                    "contains": "hello from manifest",
                    "count": 1
                  }
                },
                {
                  "output": {
                    "command": "tellraw",
                    "text": "gold",
                    "segment": {
                      "text": "gold",
                      "color": "yellow"
                    },
                    "count": 1
                  }
                }
              ]
            }
            """.trimIndent(),
        )

        val result = ManifestRunner.run(manifest)

        assertTrue(result.passed, result.messages.joinToString())
    }

    @Test
    fun `runs manifests with predefined world state`() {
        val dir = Files.createTempDirectory("dps-world-manifest")
        val pack = Path.of("../core/src/test/resources/packs/counter").toAbsolutePath().normalize().toString().replace("\\", "\\\\")
        val manifest = dir.resolve("world.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.1.2",
              "packs": ["$pack"],
              "world": {
                "blocks": [
                  { "pos": [0, 64, 0], "id": "minecraft:chest", "nbt": { "Items": [] } }
                ],
                "entities": [
                  { "type": "minecraft:pig", "pos": [1, 64, 0], "tags": ["fixture"] }
                ],
                "players": [
                  { "name": "Alex", "position": [2, 65, 3], "xp": 5 }
                ],
                "scores": [
                  { "target": "#fixture", "objective": "ready", "value": 1 }
                ],
                "storage": {
                  "demo:env": { "ready": true }
                }
              },
              "steps": [],
              "assertions": [
                { "block": { "pos": [0, 64, 0], "id": "minecraft:chest" } },
                { "entityCount": { "type": "minecraft:pig", "tag": "fixture", "equals": 1 } },
                { "player": { "name": "Alex", "xp": 5, "position": [2, 65, 3] } },
                { "score": { "target": "#fixture", "objective": "ready", "equals": 1 } },
                { "storage": { "id": "demo:env", "path": "ready", "equals": true } }
              ]
            }
            """.trimIndent(),
        )

        val result = ManifestRunner.run(manifest)

        assertTrue(result.passed, result.messages.joinToString())
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
    fun `runs manifests against 1_20_4 datapacks`() {
        val dir = Files.createTempDirectory("dps-1204-manifest")
        val pack = writePack(dir.resolve("pack"), packFormat = "26", functionDir = "functions", scoreTarget = "#legacy", scoreValue = 4)
        val packPath = pack.toAbsolutePath().normalize().toString().replace("\\", "\\\\")
        val manifest = dir.resolve("legacy.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "1.20.4",
              "packs": ["$packPath"],
              "steps": [
                { "load": true }
              ],
              "assertions": [
                { "score": { "target": "#legacy", "objective": "runs", "equals": 4 } }
              ]
            }
            """.trimIndent(),
        )

        val result = ManifestRunner.run(manifest)

        assertTrue(result.passed, result.messages.joinToString())
    }

    @Test
    fun `runs one manifest across multiple version-specific packs`() {
        val dir = Files.createTempDirectory("dps-matrix-manifest")
        val pack1204 = writePack(dir.resolve("pack-1204"), packFormat = "26", functionDir = "functions", scoreTarget = "#matrix", scoreValue = 6)
        val pack2612 = writePack(dir.resolve("pack-2612"), packFormat = "101.1", functionDir = "function", scoreTarget = "#matrix", scoreValue = 6)
        val pack262 = writePack(dir.resolve("pack-262"), packFormat = "107.1", functionDir = "function", scoreTarget = "#matrix", scoreValue = 6)
        val manifest = dir.resolve("matrix.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "versions": ["1.20.4", "26.1.2", "26.2"],
              "packs": {
                "1.20.4": ["${pack1204.toEscapedPath()}"],
                "26.1.2": ["${pack2612.toEscapedPath()}"],
                "26.2": ["${pack262.toEscapedPath()}"]
              },
              "steps": [
                { "load": true }
              ],
              "assertions": [
                { "score": { "target": "#matrix", "objective": "runs", "equals": 6 } }
              ]
            }
            """.trimIndent(),
        )

        val result = ManifestRunner.run(manifest)

        assertTrue(result.passed, result.messages.joinToString())
        assertEquals(listOf("1.20.4", "26.1.2", "26.2"), result.attempts.map { it.version })
        assertTrue(result.attempts.all { it.passed })
    }

    private fun writePack(root: Path, packFormat: String, functionDir: String, scoreTarget: String, scoreValue: Int): Path {
        Files.createDirectories(root)
        Files.writeString(
            root.resolve("pack.mcmeta"),
            """
            {
              "pack": {
                "pack_format": $packFormat,
                "description": "temporary test pack"
              }
            }
            """.trimIndent(),
        )
        val functionRoot = root.resolve("data").resolve("demo").resolve(functionDir)
        Files.createDirectories(functionRoot)
        Files.writeString(
            functionRoot.resolve("load.mcfunction"),
            """
            scoreboard objectives add runs dummy
            scoreboard players set $scoreTarget runs $scoreValue
            """.trimIndent(),
        )
        val tagRoot = root.resolve("data").resolve("minecraft").resolve("tags").resolve(functionDir)
        Files.createDirectories(tagRoot)
        Files.writeString(tagRoot.resolve("load.json"), """{"values":["demo:load"]}""")
        return root
    }

    private fun Path.toEscapedPath(): String =
        toAbsolutePath().normalize().toString().replace("\\", "\\\\")

    @Test
    fun `discovers manifests in directories`() {
        val manifests = ManifestRunner.discover(listOf(Path.of("src/test/resources/cases")))

        assertEquals(listOf(Path.of("src/test/resources/cases/counter.dps.json").toAbsolutePath().normalize()), manifests)
    }
}
