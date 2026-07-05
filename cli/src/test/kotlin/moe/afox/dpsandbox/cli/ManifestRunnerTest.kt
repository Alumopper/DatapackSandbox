package moe.afox.dpsandbox.cli

import com.google.gson.JsonParser
import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManifestRunnerTest {
    @Test
    fun `manifest schema parses as json`() {
        val schema = JsonParser.parseString(Files.readString(Path.of("../docs/dps-manifest.schema.json"))).asJsonObject

        assertEquals("Datapack Sandbox manifest", schema.get("title").asString)
    }

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
                "seed": 123,
                "difficulty": "hard",
                "defaultGameMode": "creative",
                "worldSpawn": { "pos": [4, 70, 5], "angle": 90 },
                "forcedChunks": [[0, 0]],
                "biomes": [
                  { "pos": [0, 64, 0], "id": "minecraft:plains" }
                ],
                "blocks": [
                  { "pos": [0, 64, 0], "id": "minecraft:chest", "nbt": { "Items": [] } }
                ],
                "entities": [
                  { "type": "minecraft:pig", "pos": [1, 64, 0], "tags": ["fixture"] }
                ],
                "players": [
                  {
                    "name": "Alex",
                    "position": [2, 65, 3],
                    "dimension": "minecraft:overworld",
                    "gameMode": "creative",
                    "xp": 5,
                    "health": 18.0,
                    "food": 17,
                    "selectedSlot": 0,
                    "inventory": [
                      { "id": "minecraft:stick", "count": 2 }
                    ],
                    "recipes": ["minecraft:bread"],
                    "stats": { "minecraft:jump": 3 },
                    "effects": [
                      { "id": "minecraft:speed", "duration": 40, "amplifier": 1 }
                    ],
                    "spawn": { "pos": [2, 66, 3], "dimension": "minecraft:overworld" }
                  }
                ],
                "teams": [
                  { "name": "red", "members": ["Alex"], "options": { "color": "red" } }
                ],
                "bossbars": [
                  { "id": "demo:bar", "name": "Demo", "value": 3, "max": 10, "players": ["Alex"] }
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
                {
                  "world": {
                    "seed": 123,
                    "difficulty": "hard",
                    "defaultGameMode": "creative",
                    "worldSpawn": { "pos": [4, 70, 5], "dimension": "minecraft:overworld" },
                    "forcedChunk": [0, 0],
                    "biome": { "pos": [0, 64, 0], "id": "minecraft:plains" }
                  }
                },
                { "block": { "pos": [0, 64, 0], "id": "minecraft:chest" } },
                { "entityCount": { "type": "minecraft:pig", "tag": "fixture", "equals": 1 } },
                {
                  "player": {
                    "name": "Alex",
                    "xp": 5,
                    "position": [2, 65, 3],
                    "dimension": "minecraft:overworld",
                    "gameMode": "creative",
                    "health": 18.0,
                    "food": 17,
                    "selectedSlot": 0,
                    "recipe": "minecraft:bread",
                    "effect": "minecraft:speed",
                    "stat": { "id": "minecraft:jump", "equals": 3 },
                    "spawn": { "pos": [2, 66, 3], "dimension": "minecraft:overworld" }
                  }
                },
                { "item": { "player": "Alex", "id": "minecraft:stick", "count": 2 } },
                {
                  "team": {
                    "name": "red",
                    "member": "Alex",
                    "memberCount": 1,
                    "option": { "name": "color", "equals": "red" }
                  }
                },
                { "bossbar": { "id": "demo:bar", "name": "Demo", "value": 3, "max": 10, "player": "Alex" } },
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
    fun `runs damage events from manifests`() {
        val dir = Files.createTempDirectory("dps-damage-manifest")
        val pack = dir.resolve("pack")
        val advancementRoot = pack.resolve("data").resolve("demo").resolve("advancement")
        Files.createDirectories(advancementRoot)
        Files.writeString(
            pack.resolve("pack.mcmeta"),
            """
            {
              "pack": {
                "pack_format": 107.1,
                "description": "damage event fixture"
              }
            }
            """.trimIndent(),
        )
        Files.writeString(
            advancementRoot.resolve("fall_damage.json"),
            """
            {
              "criteria": {
                "fell": {
                  "trigger": "minecraft:entity_hurt_player",
                  "conditions": {
                    "damage": {
                      "type": { "type": "minecraft:fall" },
                      "dealt": { "min": 4.0 }
                    }
                  }
                }
              }
            }
            """.trimIndent(),
        )
        val manifest = dir.resolve("damage.dps.json")
        val packPath = pack.toAbsolutePath().normalize().toString().replace("\\", "\\\\")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.2",
              "packs": ["$packPath"],
              "steps": [
                { "event": { "player": "Steve", "type": "damage", "damageSource": "minecraft:fall", "amount": 4.5 } }
              ],
              "assertions": [
                { "advancement": { "player": "Steve", "id": "demo:fall_damage", "criterion": "fell", "criterionDone": true } }
              ]
            }
            """.trimIndent(),
        )

        val result = ManifestRunner.run(manifest)

        assertTrue(result.passed, result.messages.joinToString())
    }

    @Test
    fun `adds snapshot diff to failing manifest reports`() {
        val dir = Files.createTempDirectory("dps-diff-manifest")
        val pack = Path.of("../core/src/test/resources/packs/counter").toAbsolutePath().normalize().toString().replace("\\", "\\\\")
        val manifest = dir.resolve("diff.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.1.2",
              "packs": ["$pack"],
              "steps": [
                { "command": "scoreboard objectives add runs dummy" },
                { "command": "scoreboard players set #manifest_diff runs 2" }
              ],
              "assertions": [
                { "score": { "target": "#manifest_diff", "objective": "runs", "equals": 3 } }
              ]
            }
            """.trimIndent(),
        )

        val result = ManifestRunner.run(manifest, ManifestOptions(snapshotDiffOnFail = true))

        assertFalse(result.passed)
        val messages = result.messages.joinToString("\n")
        assertTrue("score #manifest_diff runs expected 3 but was 2" in messages, messages)
        assertTrue("snapshot diff:" in messages, messages)
        assertTrue("+ /scores/runs =" in messages, messages)
        assertTrue("\"#manifest_diff\": 2" in messages, messages)
    }

    @Test
    fun `runs generated command text and asserts trace and items`() {
        val dir = Files.createTempDirectory("dps-generator-manifest")
        val pack = Path.of("../core/src/test/resources/packs/counter").toAbsolutePath().normalize().toString().replace("\\", "\\\\")
        val generated = dir.resolve("generated.mcfunction")
        Files.writeString(
            generated,
            """
            scoreboard players add #generated runs 1
            give Steve minecraft:apple 3
            """.trimIndent(),
        )
        val manifest = dir.resolve("generator.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.1.2",
              "packs": ["$pack"],
              "steps": [
                {
                  "commands": [
                    "scoreboard objectives add runs dummy",
                    "scoreboard players set #generated runs 1"
                  ],
                  "source": "<generator:commands>"
                },
                {
                  "functionText": "scoreboard players add #generated runs 2",
                  "source": "<generator:inline>"
                },
                {
                  "mcfunction": "generated.mcfunction"
                }
              ],
              "assertions": [
                { "score": { "target": "#generated", "objective": "runs", "equals": 4 } },
                { "item": { "player": "Steve", "id": "minecraft:apple", "count": 3 } },
                { "trace": { "root": "scoreboard", "count": 4 } },
                { "trace": { "contains": "give Steve", "fileContains": "generated.mcfunction", "success": true, "count": 1 } }
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
