package moe.afox.dpsandbox.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManifestAssertionTest : ManifestRunnerTestSupport() {
    @Test
    fun `runs score min max assertions`() {
        val dir = Files.createTempDirectory("dps-score-range-manifest")
        val pack =
            Path
                .of("../core/src/test/resources/packs/counter")
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
        val manifest = dir.resolve("score-range.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.1.2",
              "packs": ["$pack"],
              "steps": [
                { "command": "scoreboard objectives add runs dummy" },
                { "command": "scoreboard players set #range runs 5" }
              ],
              "assertions": [
                { "score": { "target": "#range", "objective": "runs", "min": 4, "max": 6 } },
                { "score": { "target": "#range", "objective": "runs", "min": 6 } }
              ]
            }
            """.trimIndent(),
        )

        val result = ManifestRunner.run(manifest)

        assertFalse(result.passed)
        assertTrue(result.messages.any { "expected >= 6 but was 5" in it }, result.messages.joinToString())
    }

    @Test
    fun `runs entity count min max assertions`() {
        val dir = Files.createTempDirectory("dps-entity-count-range-manifest")
        val pack =
            Path
                .of("../core/src/test/resources/packs/counter")
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
        val manifest = dir.resolve("entity-count-range.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.1.2",
              "packs": ["$pack"],
              "world": {
                "entities": [
                  { "type": "minecraft:pig", "tags": ["range"] },
                  { "type": "minecraft:pig", "tags": ["range"] }
                ]
              },
              "steps": [],
              "assertions": [
                { "entityCount": { "type": "minecraft:pig", "tag": "range", "min": 2, "max": 3 } },
                { "entityCount": { "type": "minecraft:pig", "tag": "range", "max": 1 } }
              ]
            }
            """.trimIndent(),
        )

        val result = ManifestRunner.run(manifest)

        assertFalse(result.passed)
        assertTrue(
            result.messages.any {
                "entityCount type=minecraft:pig, tag=range expected <= 1 but was 2" in it
            },
            result.messages.joinToString(),
        )
    }

    @Test
    fun `runs storage exists and missing assertions`() {
        val dir = Files.createTempDirectory("dps-storage-existence-manifest")
        val pack =
            Path
                .of("../core/src/test/resources/packs/counter")
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
        val manifest = dir.resolve("storage-existence.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.1.2",
              "packs": ["$pack"],
              "world": {
                "storage": {
                  "demo:env": { "ready": true }
                }
              },
              "steps": [],
              "assertions": [
                { "storage": { "id": "demo:env", "exists": true } },
                { "storage": { "id": "demo:env", "path": "ready", "exists": true } },
                { "storage": { "id": "demo:env", "path": "absent", "missing": true } },
                { "storage": { "id": "demo:env", "path": "ready", "equals": true } },
                { "storage": { "id": "demo:env", "path": "ready", "missing": true } }
              ]
            }
            """.trimIndent(),
        )

        val result = ManifestRunner.run(manifest)

        assertFalse(result.passed)
        assertTrue(result.messages.any { "storage demo:env ready expected missing but was true" in it }, result.messages.joinToString())
    }

    @Test
    fun `runs path contains and regex assertions`() {
        val dir = Files.createTempDirectory("dps-path-matches-manifest")
        val pack =
            Path
                .of("../core/src/test/resources/packs/counter")
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
        val manifest = dir.resolve("path-matches.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.1.2",
              "packs": ["$pack"],
              "world": {
                "storage": {
                  "demo:env": { "label": "generated-case-42" }
                },
                "blocks": [
                  { "pos": [0, 64, 0], "id": "minecraft:chest", "nbt": { "Items": [], "CustomName": "{\"text\":\"Regex Chest\"}" } }
                ],
                "players": [
                  {
                    "name": "Alex",
                    "inventory": [
                      {
                        "id": "minecraft:stick",
                        "components": { "demo": { "name": "Generated Stick" } },
                        "nbt": { "tag": { "name": "NBT Stick 42" } }
                      }
                    ]
                  }
                ]
              },
              "steps": [],
              "assertions": [
                { "storage": { "id": "demo:env", "path": "label", "contains": "case", "matches": "case-\\d+" } },
                { "player": { "name": "Alex", "nbt": { "path": "Name", "matches": "^Alex$" } } },
                { "block": { "pos": [0, 64, 0], "nbt": { "path": "CustomName", "contains": "Regex Chest", "matches": "Regex\\s+Chest" } } },
                { "item": { "player": "Alex", "id": "minecraft:stick", "components": { "path": "demo.name", "contains": "Generated", "matches": "Stick$" } } },
                { "item": { "player": "Alex", "id": "minecraft:stick", "nbt": { "path": "tag.name", "contains": "NBT", "matches": "Stick\\s+42" } } },
                { "item": { "player": "Alex", "id": "minecraft:stick", "nbt": { "path": "tag.missing", "missing": true } } }
              ]
            }
            """.trimIndent(),
        )

        val result = ManifestRunner.run(manifest)

        assertTrue(result.passed, result.messages.joinToString())
    }

    @Test
    fun `runs player existence assertions`() {
        val dir = Files.createTempDirectory("dps-player-existence-manifest")
        val pack =
            Path
                .of("../core/src/test/resources/packs/counter")
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
        val manifest = dir.resolve("player-existence.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.1.2",
              "packs": ["$pack"],
              "world": {
                "players": [
                  { "name": "Alex" }
                ]
              },
              "steps": [],
              "assertions": [
                { "player": { "name": "Steve", "exists": false } },
                { "player": { "name": "Alex", "exists": false } }
              ]
            }
            """.trimIndent(),
        )

        val result = ManifestRunner.run(manifest)

        assertFalse(result.passed)
        assertTrue(result.messages.any { "player Alex expected missing but exists" in it }, result.messages.joinToString())
    }

    @Test
    fun `runs item count range assertions`() {
        val dir = Files.createTempDirectory("dps-item-count-range-manifest")
        val pack =
            Path
                .of("../core/src/test/resources/packs/counter")
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
        val manifest = dir.resolve("item-count-range.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.1.2",
              "packs": ["$pack"],
              "world": {
                "players": [
                  {
                    "name": "Alex",
                    "inventory": [
                      { "id": "minecraft:stick", "count": 2 }
                    ]
                  }
                ]
              },
              "steps": [],
              "assertions": [
                { "item": { "player": "Alex", "id": "minecraft:stick", "minCount": 1, "maxCount": 3 } },
                { "item": { "player": "Alex", "id": "minecraft:stick", "minCount": 3 } }
              ]
            }
            """.trimIndent(),
        )

        val result = ManifestRunner.run(manifest)

        assertFalse(result.passed)
        assertTrue(
            result.messages.any { "item for player Alex expected id=minecraft:stick, minCount=3" in it },
            result.messages.joinToString(),
        )
    }

    @Test
    fun `runs block nbt existence assertions`() {
        val dir = Files.createTempDirectory("dps-block-nbt-manifest")
        val pack =
            Path
                .of("../core/src/test/resources/packs/counter")
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
        val manifest = dir.resolve("block-nbt.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.1.2",
              "packs": ["$pack"],
              "world": {
                "blocks": [
                  { "pos": [0, 64, 0], "id": "minecraft:chest", "nbt": { "Items": [] } }
                ]
              },
              "steps": [],
              "assertions": [
                { "block": { "pos": [0, 64, 0], "id": "minecraft:chest", "nbt": { "path": "Items", "exists": true, "equals": [] } } },
                { "block": { "pos": [0, 64, 0], "nbt": { "path": "Missing", "exists": true } } }
              ]
            }
            """.trimIndent(),
        )

        val result = ManifestRunner.run(manifest)

        assertFalse(result.passed)
        assertTrue(
            result.messages.any { "block 0 64 0 nbt Missing exists expected true but was false" in it },
            result.messages.joinToString(),
        )
    }

    @Test
    fun `runs manifest world spawn angle assertions`() {
        val dir = Files.createTempDirectory("dps-world-spawn-angle-manifest")
        val pack =
            Path
                .of("../core/src/test/resources/packs/counter")
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
        val manifest = dir.resolve("world-spawn-angle.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.1.2",
              "packs": ["$pack"],
              "world": {
                "worldSpawn": { "pos": [4, 70, 5], "angle": 90, "forced": true }
              },
              "steps": [],
              "assertions": [
                { "world": { "worldSpawn": { "angle": 45, "forced": false } } }
              ]
            }
            """.trimIndent(),
        )

        val result = ManifestRunner.run(manifest)

        assertFalse(result.passed)
        assertTrue(result.messages.any { "world spawn angle expected 45.0 but was 90.0" in it }, result.messages.joinToString())
        assertTrue(result.messages.any { "world spawn forced expected false but was true" in it }, result.messages.joinToString())
    }

    @Test
    fun `runs manifest world border assertions`() {
        val dir = Files.createTempDirectory("dps-world-border-manifest")
        val pack =
            Path
                .of("../core/src/test/resources/packs/counter")
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
        val manifest = dir.resolve("world-border.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.1.2",
              "packs": ["$pack"],
              "world": {
                "worldBorder": {
                  "centerX": 5,
                  "centerZ": -6,
                  "size": 100,
                  "warningDistance": 8
                }
              },
              "steps": [],
              "assertions": [
                {
                  "world": {
                    "worldBorder": {
                      "centerX": 4,
                      "centerZ": -7,
                      "size": 90,
                      "warningDistance": 9
                    }
                  }
                }
              ]
            }
            """.trimIndent(),
        )

        val result = ManifestRunner.run(manifest)

        assertFalse(result.passed)
        assertTrue(result.messages.any { "world border centerX expected 4.0 but was 5.0" in it }, result.messages.joinToString())
        assertTrue(result.messages.any { "world border centerZ expected -7.0 but was -6.0" in it }, result.messages.joinToString())
        assertTrue(result.messages.any { "world border size expected 90.0 but was 100.0" in it }, result.messages.joinToString())
        assertTrue(result.messages.any { "world border warningDistance expected 9 but was 8" in it }, result.messages.joinToString())
    }

    @Test
    fun `runs manifest player spawn detail assertions`() {
        val dir = Files.createTempDirectory("dps-player-spawn-detail-manifest")
        val pack =
            Path
                .of("../core/src/test/resources/packs/counter")
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
        val manifest = dir.resolve("player-spawn-detail.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.1.2",
              "packs": ["$pack"],
              "world": {
                "players": [
                  {
                    "name": "Alex",
                    "spawn": { "pos": [2, 66, 3], "angle": 90, "forced": true }
                  }
                ]
              },
              "steps": [],
              "assertions": [
                {
                  "player": {
                    "name": "Alex",
                    "spawn": { "angle": 45, "forced": false }
                  }
                }
              ]
            }
            """.trimIndent(),
        )

        val result = ManifestRunner.run(manifest)

        assertFalse(result.passed)
        assertTrue(result.messages.any { "player Alex spawn angle expected 45.0 but was 90.0" in it }, result.messages.joinToString())
        assertTrue(result.messages.any { "player Alex spawn forced expected false but was true" in it }, result.messages.joinToString())
    }

    @Test
    fun `runs manifests with includes and source relative steps`() {
        val dir = Files.createTempDirectory("dps-include-manifest")
        val common = dir.resolve("common")
        val generated = common.resolve("generated")
        Files.createDirectories(generated)
        Files.writeString(
            generated.resolve("common.mcfunction"),
            """
            scoreboard objectives add runs dummy
            scoreboard players set #included runs 2
            """.trimIndent(),
        )
        val pack =
            Path
                .of("../core/src/test/resources/packs/counter")
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
        Files.writeString(
            common.resolve("base.dps.json"),
            """
            {
              "version": "26.1.2",
              "seed": 88,
              "packs": ["$pack"],
              "steps": [
                { "mcfunction": "generated/common.mcfunction" }
              ],
              "assertions": [
                { "world": { "seed": 88 } },
                { "score": { "target": "#included", "objective": "runs", "equals": 2 } }
              ]
            }
            """.trimIndent(),
        )
        val manifest = dir.resolve("include.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "include": "common/base.dps.json",
              "steps": [
                { "command": "scoreboard players set #main runs 3" }
              ],
              "assertions": [
                { "score": { "target": "#main", "objective": "runs", "equals": 3 } }
              ]
            }
            """.trimIndent(),
        )

        val result = ManifestRunner.run(manifest)

        assertTrue(result.passed, result.messages.joinToString())
    }

    @Test
    fun `included assertion failures include source manifest path`() {
        val dir = Files.createTempDirectory("dps-include-assertion-source")
        val common = dir.resolve("common")
        val generated = common.resolve("generated")
        Files.createDirectories(generated)
        Files.writeString(
            generated.resolve("common.mcfunction"),
            """
            scoreboard objectives add runs dummy
            scoreboard players set #included runs 2
            """.trimIndent(),
        )
        val pack =
            Path
                .of("../core/src/test/resources/packs/counter")
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
        val commonManifest = common.resolve("base.dps.json")
        Files.writeString(
            commonManifest,
            """
            {
              "version": "26.1.2",
              "packs": ["$pack"],
              "steps": [
                { "mcfunction": "generated/common.mcfunction" }
              ],
              "assertions": [
                { "score": { "target": "#included", "objective": "runs", "equals": 9 } }
              ]
            }
            """.trimIndent(),
        )
        val manifest = dir.resolve("include.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "include": "common/base.dps.json"
            }
            """.trimIndent(),
        )

        val result = ManifestRunner.run(manifest)
        val messages = result.messages.joinToString("\n")
        val sourcePointer = "${commonManifest.toAbsolutePath().normalize()}/assertions/0/score"

        assertFalse(result.passed)
        assertTrue(sourcePointer in messages, messages)
        assertTrue("score #included runs expected 9 but was 2" in messages, messages)
    }

    @Test
    fun `included pack defaults merge before local version packs`() {
        val dir = Files.createTempDirectory("dps-include-pack-merge")
        val common = dir.resolve("common")
        Files.createDirectories(common)
        writeNamedFunctionPack(dir.resolve("common-pack"), "common", "#common", 4)
        writeNamedFunctionPack(dir.resolve("case-pack"), "case", "#case", 7)
        Files.writeString(
            common.resolve("base.dps.json"),
            """
            {
              "version": "26.2",
              "packs": {
                "default": ["../common-pack"]
              }
            }
            """.trimIndent(),
        )
        val manifest = dir.resolve("case.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "include": "common/base.dps.json",
              "packs": {
                "26.2": ["case-pack"]
              },
              "steps": [
                { "command": "scoreboard objectives add runs dummy" },
                { "command": "function demo:common" },
                { "command": "function demo:case" }
              ],
              "assertions": [
                { "score": { "target": "#common", "objective": "runs", "equals": 4 } },
                { "score": { "target": "#case", "objective": "runs", "equals": 7 } }
              ]
            }
            """.trimIndent(),
        )

        val result = ManifestRunner.run(manifest)

        assertTrue(result.passed, result.messages.joinToString())
        assertEquals(
            listOf(dir.resolve("common-pack").toAbsolutePath().normalize(), dir.resolve("case-pack").toAbsolutePath().normalize()),
            result.attempts.single().packs,
        )
    }

    @Test
    fun `manifests can fail on missing resource references`() {
        val dir = Files.createTempDirectory("dps-missing-resource-manifest")
        val pack = writeMissingReferencePack(dir.resolve("pack"))
        val manifest = dir.resolve("missing-resource.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.2",
              "packs": ["${pack.toEscapedPath()}"],
              "failOnMissingResources": true,
              "steps": [],
              "assertions": []
            }
            """.trimIndent(),
        )

        val result = ManifestRunner.run(manifest)

        assertFalse(result.passed)
        assertTrue(
            result.messages.any { "missing-reference #minecraft:load -> function demo:missing_load" in it },
            result.messages.joinToString(),
        )
        assertTrue(
            result.messages.any { "missing-reference advancement demo:child parent -> advancement demo:missing_parent" in it },
            result.messages.joinToString(),
        )
        assertTrue(
            result.messages.any { "missing-reference predicate demo:uses_missing reference -> predicate demo:missing_predicate" in it },
            result.messages.joinToString(),
        )
        assertTrue(
            result.messages.any { "missing-reference loot_table demo:outer entry -> loot_table demo:missing_nested" in it },
            result.messages.joinToString(),
        )
        assertTrue(
            result.messages.any { "missing-reference loot_table demo:outer conditions -> predicate demo:missing_loot_condition" in it },
            result.messages.joinToString(),
        )
        assertTrue(
            result.messages.any {
                "missing-reference item_modifier demo:conditional conditions -> predicate demo:missing_item_modifier_condition" in
                    it
            },
            result.messages.joinToString(),
        )
        assertEquals(
            6,
            result.attempts
                .single()
                .resourceSummary
                ?.missingReferences
                ?.size,
        )
    }

    @Test
    fun `runs manifests against 1_20_4 datapacks`() {
        val dir = Files.createTempDirectory("dps-1204-manifest")
        val pack = writePack(dir.resolve("pack"), packFormat = "26", functionDir = "functions", scoreTarget = "#legacy", scoreValue = 4)
        val packPath =
            pack
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
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
        val pack1204 =
            writePack(dir.resolve("pack-1204"), packFormat = "26", functionDir = "functions", scoreTarget = "#matrix", scoreValue = 6)
        val pack2612 =
            writePack(dir.resolve("pack-2612"), packFormat = "101.1", functionDir = "function", scoreTarget = "#matrix", scoreValue = 6)
        val pack262 =
            writePack(dir.resolve("pack-262"), packFormat = "107.1", functionDir = "function", scoreTarget = "#matrix", scoreValue = 6)
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

    @Test
    fun `discovers manifests in directories`() {
        val manifests = ManifestRunner.discover(listOf(Path.of("src/test/resources/cases")))

        assertEquals(listOf(Path.of("src/test/resources/cases/counter.dps.json").toAbsolutePath().normalize()), manifests)
    }
}
