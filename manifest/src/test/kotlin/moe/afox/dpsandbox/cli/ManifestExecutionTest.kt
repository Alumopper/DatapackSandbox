package moe.afox.dpsandbox.cli

import com.google.gson.JsonParser
import moe.afox.dpsandbox.core.ChunkPos
import moe.afox.dpsandbox.core.ResourceLocation
import moe.afox.dpsandbox.core.createSandbox
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ManifestExecutionTest : ManifestRunnerTestSupport() {
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
    fun `runs all example manifests`() {
        val manifests = ManifestRunner.discover(listOf(Path.of("../examples")))
        val exampleKinds = manifests.map { it.parent.fileName.toString() }.toSet()

        assertTrue(
            exampleKinds.containsAll(
                listOf("full-stack", "player-events", "single-function", "generator-output", "generator-template", "multi-version"),
            ),
        )

        val results = manifests.map { ManifestRunner.run(it) }
        val failures = results.flatMap { result -> result.messages.map { "${result.path}: $it" } }
        assertTrue(results.all { it.passed }, failures.joinToString())
    }

    @Test
    fun `runs manifest scoreboard UI assertions`() {
        val dir = Files.createTempDirectory("dps-scoreboard-ui-manifest")
        val pack =
            Path
                .of("../core/src/test/resources/packs/counter")
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
        val manifest = dir.resolve("scoreboard-ui.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.1.2",
              "packs": ["$pack"],
              "steps": [
                { "command": "scoreboard objectives add health dummy" },
                { "command": "scoreboard objectives modify health displayname Health Points" },
                { "command": "scoreboard objectives modify health rendertype hearts" },
                { "command": "scoreboard objectives modify health displayautoupdate false" },
                { "command": "scoreboard objectives setdisplay sidebar.team.red health" }
              ],
              "assertions": [
                {
                  "scoreboardObjective": {
                    "name": "health",
                    "criteria": "dummy",
                    "displayName": "Health Points",
                    "renderType": "hearts",
                    "displayAutoUpdate": false
                  }
                },
                {
                  "scoreboardObjective": {
                    "name": "missing",
                    "exists": false
                  }
                },
                {
                  "scoreboardDisplay": {
                    "slot": "sidebar.team.red",
                    "objective": "health"
                  }
                },
                {
                  "scoreboardDisplay": {
                    "slot": "list",
                    "exists": false
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
    fun `runs manifest gamerule assertions`() {
        val dir = Files.createTempDirectory("dps-gamerule-manifest")
        val pack =
            Path
                .of("../core/src/test/resources/packs/counter")
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
        val manifest = dir.resolve("gamerule.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.1.2",
              "packs": ["$pack"],
              "steps": [
                { "command": "gamerule doDaylightCycle false" },
                { "command": "gamerule maxEntityCramming 0" }
              ],
              "assertions": [
                {
                  "gamerule": {
                    "name": "doDaylightCycle",
                    "value": "false"
                  }
                },
                {
                  "gamerule": {
                    "name": "maxEntityCramming",
                    "value": "0"
                  }
                },
                {
                  "gamerule": {
                    "name": "missingRule",
                    "exists": false
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
    fun `runs manifest random sequence and forced chunk assertions`() {
        val dir = Files.createTempDirectory("dps-world-state-manifest")
        val pack =
            Path
                .of("../core/src/test/resources/packs/counter")
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
        val manifest = dir.resolve("world-state.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.1.2",
              "packs": ["$pack"],
              "world": {
                "randomSequences": { "demo:seq": 42 },
                "forcedChunks": [[0, 0]]
              },
              "assertions": [
                {
                  "randomSequence": {
                    "name": "demo:seq",
                    "state": 42
                  }
                },
                {
                  "randomSequence": {
                    "name": "demo:missing",
                    "exists": false
                  }
                },
                {
                  "forcedChunk": {
                    "x": 0,
                    "z": 0
                  }
                },
                {
                  "forcedChunk": {
                    "x": 1,
                    "z": 1,
                    "exists": false
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
    fun `runs manifest output assertions`() {
        val dir = Files.createTempDirectory("dps-output-manifest")
        val pack =
            Path
                .of("../examples/full-stack/pack")
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
        val manifest = dir.resolve("outputs.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.2",
              "packs": ["$pack"],
              "steps": [
                { "command": "say hello from manifest" },
                { "command": "tellraw Steve {\"text\":\"gold\",\"color\":\"yellow\"}" },
                { "command": "tellraw Steve {\"text\":\"hello     generated     output\"}" },
                { "command": "place structure demo:ruin 1 64 2" }
              ],
              "assertions": [
                {
                  "output": {
                    "command": "say",
                    "channel": "chat",
                    "target": "Steve",
                    "contains": "hello from manifest",
                    "matches": "hello\\s+from\\s+manifest",
                    "normalizedMatches": "hello from manifest",
                    "order": 1,
                    "count": 1
                  }
                },
                {
                  "output": {
                    "command": "tellraw",
                    "text": "gold",
                    "order": 2,
                    "segment": {
                      "text": "gold",
                      "color": "yellow"
                    },
                    "count": 1
                  }
                },
                {
                  "output": {
                    "command": "tellraw",
                    "normalizedText": "hello generated output",
                    "normalizedContains": "generated output",
                    "normalizedMatches": "hello generated output",
                    "segment": {
                      "matches": "hello\\s+generated\\s+output",
                      "normalizedText": "hello generated output",
                      "normalizedMatches": "generated output"
                    },
                    "count": 1
                  }
                },
                {
                  "output": {
                    "command": "place structure",
                    "channel": "worldgen",
                    "payloadPath": "placed",
                    "payloadEquals": false,
                    "order": 4,
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
        val pack =
            Path
                .of("../core/src/test/resources/packs/counter")
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
        val manifest = dir.resolve("world.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.1.2",
              "packs": ["$pack"],
              "world": {
                "seed": 123,
                "randomSequences": { "demo:seq": 42 },
                "difficulty": "hard",
                "defaultGameMode": "creative",
                "worldSpawn": { "pos": [4, 70, 5], "angle": 90, "forced": true },
                "worldBorder": {
                  "centerX": 5,
                  "centerZ": -6,
                  "size": 100,
                  "targetSize": 120,
                  "lerpTimeSeconds": 30,
                  "damageBuffer": 3,
                  "damageAmount": 0.5,
                  "warningDistance": 8,
                  "warningTime": 20
                },
                "forcedChunks": [[0, 0]],
                "biomes": [
                  { "pos": [0, 64, 0], "id": "minecraft:plains" }
                ],
                "blocks": [
                  { "pos": [0, 64, 0], "id": "minecraft:chest", "nbt": { "Items": [] } }
                ],
                "entities": [
                  {
                    "type": "minecraft:pig",
                    "uuid": "00000000-0000-0000-0000-000000000101",
                    "pos": [1, 64, 0],
                    "dimension": "minecraft:the_nether",
                    "health": 8.0,
                    "vehicle": "00000000-0000-0000-0000-000000000102",
                    "tags": ["fixture"],
                    "equipment": {
                      "weapon.mainhand": {
                        "id": "minecraft:iron_sword",
                        "count": 1,
                        "components": { "custom": { "fixture": true } },
                        "nbt": { "tag": { "level": 4 } }
                      }
                    },
                    "effects": [
                      { "id": "minecraft:strength", "duration": 80, "amplifier": 2, "hideParticles": true }
                    ],
                    "attributes": {
                      "minecraft:max_health": 12.0
                    }
                  },
                  {
                    "type": "minecraft:cow",
                    "uuid": "00000000-0000-0000-0000-000000000102",
                    "pos": [1, 64, 1],
                    "tags": ["fixture_vehicle"],
                    "passengers": ["00000000-0000-0000-0000-000000000101"]
                  }
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
                    "enderItems": [
                      { "id": "minecraft:ender_pearl", "count": 4 }
                    ],
                    "recipes": ["minecraft:bread"],
                    "stats": { "minecraft:jump": 3 },
                    "effects": [
                      { "id": "minecraft:speed", "duration": 40, "amplifier": 1 }
                    ],
                    "spawn": { "pos": [2, 66, 3], "dimension": "minecraft:overworld", "angle": 90, "forced": true }
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
              "steps": [
                { "player": { "name": "Builder" } }
              ],
              "assertions": [
                {
                  "world": {
                    "seed": 123,
                    "randomSequences": { "demo:seq": 42 },
                    "difficulty": "hard",
                    "defaultGameMode": "creative",
                    "worldSpawn": { "pos": [4, 70, 5], "dimension": "minecraft:overworld", "angle": 90, "forced": true },
                    "worldBorder": {
                      "centerX": 5,
                      "centerZ": -6,
                      "size": 100,
                      "targetSize": 120,
                      "lerpTimeSeconds": 30,
                      "damageBuffer": 3,
                      "damageAmount": 0.5,
                      "warningDistance": 8,
                      "warningTime": 20
                    },
                    "forcedChunk": [0, 0],
                    "biome": { "pos": [0, 64, 0], "id": "minecraft:plains" }
                  }
                },
                { "block": { "pos": [0, 64, 0], "id": "minecraft:chest", "nbt": { "path": "Items", "exists": true, "equals": [] } } },
                { "entityCount": { "type": "minecraft:pig", "tag": "fixture", "dimension": "minecraft:the_nether", "equals": 1 } },
                {
                  "entity": {
                    "type": "minecraft:pig",
                    "tag": "fixture",
                    "uuid": "00000000-0000-0000-0000-000000000101",
                    "position": [1, 64, 0],
                    "dimension": "minecraft:the_nether",
                    "health": 8.0,
                    "vehicle": "00000000-0000-0000-0000-000000000102",
                    "nbt": { "path": "Health", "equals": 8.0 },
                    "count": 1,
                    "equipment": {
                      "slot": "weapon.mainhand",
                      "id": "minecraft:iron_sword",
                      "components": { "path": "custom.fixture", "equals": true },
                      "nbt": { "path": "tag.level", "equals": 4 }
                    }
                  }
                },
                {
                  "entity": {
                    "type": "minecraft:cow",
                    "tag": "fixture_vehicle",
                    "uuid": "00000000-0000-0000-0000-000000000102",
                    "passenger": "00000000-0000-0000-0000-000000000101",
                    "passengerCount": 1
                  }
                },
                {
                  "entity": {
                    "type": "minecraft:pig",
                    "tag": "fixture",
                    "dimension": "minecraft:the_nether",
                    "effect": { "id": "minecraft:strength", "duration": 80, "amplifier": 2, "hideParticles": true }
                  }
                },
                {
                  "entity": {
                    "type": "minecraft:pig",
                    "tag": "fixture",
                    "dimension": "minecraft:the_nether",
                    "attribute": { "id": "minecraft:max_health", "equals": 12.0 }
                  }
                },
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
                    "enderItemCount": 1,
                    "recipe": "minecraft:bread",
                    "effect": "minecraft:speed",
                    "stat": { "id": "minecraft:jump", "equals": 3 },
                    "nbt": { "path": "EnderItems[0].id", "equals": "minecraft:ender_pearl" },
                    "spawn": { "pos": [2, 66, 3], "dimension": "minecraft:overworld", "angle": 90, "forced": true }
                  }
                },
                { "item": { "player": "Alex", "id": "minecraft:stick", "count": 2, "minCount": 1, "maxCount": 3 } },
                { "item": { "player": "Alex", "container": "enderItems", "id": "minecraft:ender_pearl", "count": 4 } },
                {
                  "team": {
                    "name": "red",
                    "member": "Alex",
                    "memberCount": 1,
                    "option": { "name": "color", "equals": "red" }
                  }
                },
                { "bossbar": { "id": "demo:bar", "name": "Demo", "value": 3, "max": 10, "player": "Alex" } },
                { "player": { "name": "Builder", "gameMode": "creative" } },
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
    fun `runs manifests with predefined advancement progress`() {
        val dir = Files.createTempDirectory("dps-advancement-progress-manifest")
        val pack =
            Path
                .of("../examples/full-stack/pack")
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
        val manifest = dir.resolve("advancement-progress.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.2",
              "packs": ["$pack"],
              "world": {
                "players": [
                  {
                    "name": "Steve",
                    "advancements": {
                      "demo:use_carrot": {
                        "use_carrot": true
                      }
                    }
                  }
                ]
              },
              "assertions": [
                { "advancement": { "player": "Steve", "id": "demo:use_carrot", "done": true, "criterion": "use_carrot", "criterionDone": true } }
              ]
            }
            """.trimIndent(),
        )

        val schemaFailures = ManifestSchemaValidator.validate(JsonParser.parseString(Files.readString(manifest)))
        val result = ManifestRunner.run(manifest)

        assertTrue(schemaFailures.isEmpty(), schemaFailures.joinToString())
        assertTrue(result.passed, result.messages.joinToString())
    }

    @Test
    fun `manifest scheduled assertions inspect queue and due ticks`() {
        val dir = Files.createTempDirectory("dps-scheduled-manifest")
        val pack =
            writeSchedulePack(dir.resolve("pack"))
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
        val pendingManifest = dir.resolve("scheduled-pending.dps.json")
        Files.writeString(
            pendingManifest,
            """
            {
              "version": "26.2",
              "packs": ["$pack"],
              "steps": [
                { "function": "demo:main" }
              ],
              "assertions": [
                { "scheduled": { "id": "demo:later", "dueTick": 5, "count": 2 } },
                { "scheduled": { "id": "demo:missing", "exists": false } }
              ]
            }
            """.trimIndent(),
        )
        val finishedManifest = dir.resolve("scheduled-finished.dps.json")
        Files.writeString(
            finishedManifest,
            """
            {
              "version": "26.2",
              "packs": ["$pack"],
              "steps": [
                { "function": "demo:main" },
                { "ticks": 5 }
              ],
              "assertions": [
                { "scheduled": { "id": "demo:later", "exists": false } },
                { "score": { "target": "#later", "objective": "runs", "equals": 2 } }
              ]
            }
            """.trimIndent(),
        )
        val mismatchManifest = dir.resolve("scheduled-mismatch.dps.json")
        Files.writeString(
            mismatchManifest,
            """
            {
              "version": "26.2",
              "packs": ["$pack"],
              "steps": [
                { "function": "demo:main" }
              ],
              "assertions": [
                { "scheduled": { "id": "demo:later", "dueTick": 6 } }
              ]
            }
            """.trimIndent(),
        )

        val schemaFailures = ManifestSchemaValidator.validate(JsonParser.parseString(Files.readString(pendingManifest)))
        val pending = ManifestRunner.run(pendingManifest)
        val finished = ManifestRunner.run(finishedManifest)
        val mismatch = ManifestRunner.run(mismatchManifest)

        assertTrue(schemaFailures.isEmpty(), schemaFailures.joinToString())
        assertTrue(pending.passed, pending.messages.joinToString())
        assertTrue(finished.passed, finished.messages.joinToString())
        assertFalse(mismatch.passed)
        val messages = mismatch.messages.joinToString("\n")
        assertTrue("assertion 1 (/assertions/0/scheduled):" in messages, messages)
        assertTrue("scheduled function demo:later expected dueTick 6" in messages, messages)
        assertTrue("actual scheduled functions: demo:later@5" in messages, messages)
    }

    @Test
    fun `manifest top-level seed sets default world seed`() {
        val dir = Files.createTempDirectory("dps-manifest-seed")
        val pack =
            Path
                .of("../core/src/test/resources/packs/counter")
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
        val manifest = dir.resolve("seed.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.1.2",
              "seed": 321,
              "packs": ["$pack"],
              "assertions": [
                { "world": { "seed": 321 } }
              ]
            }
            """.trimIndent(),
        )

        val result = ManifestRunner.run(manifest)

        assertTrue(result.passed, result.messages.joinToString())
    }

    @Test
    fun `manifest top-level seed is default for loot assertions`() {
        val dir = Files.createTempDirectory("dps-manifest-loot-seed")
        val pack = writeWeightedLootPack(dir.resolve("pack"))
        val sandbox = createSandbox("26.2", listOf(pack))

        fun itemFor(seed: Long): String =
            sandbox
                .generateLoot(
                    ResourceLocation.parse("demo:choice"),
                    ResourceLocation.parse("minecraft:empty"),
                    seed = seed,
                ).items
                .single()
                .id
                .toString()

        val defaultItem = itemFor(0)
        val manifestSeed = (1L..200L).first { itemFor(it) != defaultItem }
        val expectedItem = itemFor(manifestSeed)
        assertNotEquals(defaultItem, expectedItem)

        val manifest = dir.resolve("loot-seed.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.2",
              "seed": $manifestSeed,
              "packs": ["${pack.toEscapedPath()}"],
              "assertions": [
                { "loot": { "table": "demo:choice", "context": "minecraft:empty", "item": "$expectedItem", "count": 1 } }
              ]
            }
            """.trimIndent(),
        )

        val result = ManifestRunner.run(manifest)

        assertTrue(result.passed, result.messages.joinToString())
    }

    @Test
    fun `runs manifests with referenced world fixtures and local overrides`() {
        val dir = Files.createTempDirectory("dps-world-fixture-manifest")
        val fixtures = dir.resolve("fixtures")
        Files.createDirectories(fixtures)
        Files.writeString(
            fixtures.resolve("base-world.json"),
            """
            {
              "seed": 10,
              "players": [
                {
                  "name": "Alex",
                  "xp": 1,
                  "inventory": [
                    { "id": "minecraft:stick", "count": 1 }
                  ]
                }
              ],
              "blocks": [
                { "pos": [2, 64, 2], "id": "minecraft:stone" }
              ],
              "scores": [
                { "target": "#fixture", "objective": "ready", "value": 1 }
              ],
              "storage": {
                "demo:env": { "from": "base" }
              }
            }
            """.trimIndent(),
        )
        Files.writeString(
            fixtures.resolve("wrapped-world.json"),
            """
            {
              "world": {
                "difficulty": "hard",
                "players": [
                  { "name": "Builder", "xp": 2 }
                ]
              }
            }
            """.trimIndent(),
        )
        val pack =
            Path
                .of("../core/src/test/resources/packs/counter")
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
        val manifest = dir.resolve("world-fixture.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.1.2",
              "packs": ["$pack"],
              "world": {
                "fixtures": ["fixtures/base-world.json"],
                "extends": "fixtures/wrapped-world.json",
                "seed": 99,
                "players": [
                  {
                    "name": "Alex",
                    "xp": 5,
                    "inventory": [
                      { "id": "minecraft:apple", "count": 3 }
                    ]
                  }
                ],
                "scores": [
                  { "target": "#fixture", "objective": "ready", "value": 7 }
                ]
              },
              "steps": [],
              "assertions": [
                { "world": { "seed": 99, "difficulty": "hard" } },
                { "block": { "pos": [2, 64, 2], "id": "minecraft:stone" } },
                { "player": { "name": "Alex", "xp": 5, "inventoryCount": 1 } },
                { "item": { "player": "Alex", "id": "minecraft:apple", "count": 3 } },
                { "item": { "player": "Alex", "id": "minecraft:stick", "exists": false } },
                { "player": { "name": "Builder", "xp": 2 } },
                { "score": { "target": "#fixture", "objective": "ready", "equals": 7 } },
                { "storage": { "id": "demo:env", "path": "from", "equals": "base" } }
              ]
            }
            """.trimIndent(),
        )

        val schemaFailures = ManifestSchemaValidator.validate(JsonParser.parseString(Files.readString(manifest)))
        val result = ManifestRunner.run(manifest)

        assertTrue(schemaFailures.isEmpty(), schemaFailures.joinToString())
        assertTrue(result.passed, result.messages.joinToString())
    }

    @Test
    fun `runs manifests with world region fixtures`() {
        val dir = Files.createTempDirectory("dps-world-region-manifest")
        val pack =
            Path
                .of("../core/src/test/resources/packs/counter")
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
        val manifest = dir.resolve("world-region.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.1.2",
              "packs": ["$pack"],
              "world": {
                "regions": [
                  { "from": [0, 64, 0], "to": [1, 64, 1], "id": "minecraft:stone" }
                ],
                "blocks": [
                  { "pos": [1, 64, 1], "id": "minecraft:diamond_ore" }
                ]
              },
              "assertions": [
                { "block": { "pos": [0, 64, 0], "id": "minecraft:stone" } },
                { "block": { "pos": [1, 64, 0], "id": "minecraft:stone" } },
                { "block": { "pos": [0, 64, 1], "id": "minecraft:stone" } },
                { "block": { "pos": [1, 64, 1], "id": "minecraft:diamond_ore" } }
              ]
            }
            """.trimIndent(),
        )

        val schemaFailures = ManifestSchemaValidator.validate(JsonParser.parseString(Files.readString(manifest)))
        val result = ManifestRunner.run(manifest)

        assertTrue(schemaFailures.isEmpty(), schemaFailures.joinToString())
        assertTrue(result.passed, result.messages.joinToString())
    }

    @Test
    fun `runs manifests with world save imports by block range`() {
        val dir = Files.createTempDirectory("dps-world-save-manifest")
        val save = dir.resolve("save")
        writeManifestRegion(save.resolve("region"), ChunkPos(0, 0), manifestBlockChunkNbt())
        writeManifestRegion(save.resolve("entities"), ChunkPos(0, 0), manifestEntityChunkNbt())
        val pack =
            Path
                .of("../core/src/test/resources/packs/counter")
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
        val manifest = dir.resolve("world-save.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.1.2",
              "packs": ["$pack"],
              "world": {
                "save": {
                  "path": "${save.toEscapedPath()}",
                  "from": [0, 64, 0],
                  "to": [0, 64, 0]
                }
              },
              "assertions": [
                { "block": { "pos": [0, 64, 0], "id": "minecraft:stone" } },
                { "entity": { "type": "minecraft:pig", "tag": "imported", "position": [2.0, 64.0, 2.0] } }
              ]
            }
            """.trimIndent(),
        )

        val schemaFailures = ManifestSchemaValidator.validate(JsonParser.parseString(Files.readString(manifest)))
        val result = ManifestRunner.run(manifest)

        assertTrue(schemaFailures.isEmpty(), schemaFailures.joinToString())
        assertTrue(result.passed, result.messages.joinToString())
    }

    @Test
    fun `runs manifests with world structure fixtures`() {
        val dir = Files.createTempDirectory("dps-world-structure-manifest")
        val pack =
            Path
                .of("../core/src/test/resources/packs/counter")
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
        val manifest = dir.resolve("world-structure.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.1.2",
              "packs": ["$pack"],
              "world": {
                "structures": [
                  {
                    "origin": [10, 64, 10],
                    "blocks": [
                      { "offset": [0, 0, 0], "id": "minecraft:stone" },
                      { "offset": [1, 0, 0], "id": "minecraft:chest", "nbt": { "Items": [] } }
                    ],
                    "entities": [
                      {
                        "offset": [0.5, 1.0, 0.5],
                        "type": "minecraft:pig",
                        "tags": ["structure_fixture"],
                        "health": 6.0
                      }
                    ]
                  }
                ]
              },
              "assertions": [
                { "block": { "pos": [10, 64, 10], "id": "minecraft:stone" } },
                { "block": { "pos": [11, 64, 10], "id": "minecraft:chest", "nbt": { "path": "Items", "equals": [] } } },
                { "entity": { "type": "minecraft:pig", "tag": "structure_fixture", "position": [10.5, 65.0, 10.5], "health": 6.0 } }
              ]
            }
            """.trimIndent(),
        )

        val schemaFailures = ManifestSchemaValidator.validate(JsonParser.parseString(Files.readString(manifest)))
        val result = ManifestRunner.run(manifest)

        assertTrue(schemaFailures.isEmpty(), schemaFailures.joinToString())
        assertTrue(result.passed, result.messages.joinToString())
    }
}
