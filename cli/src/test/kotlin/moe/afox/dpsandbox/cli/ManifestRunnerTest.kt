package moe.afox.dpsandbox.cli

import com.google.gson.JsonParser
import moe.afox.dpsandbox.core.ResourceLocation
import moe.afox.dpsandbox.core.createSandbox
import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ManifestRunnerTest {
    @Test
    fun `manifest schema parses as json`() {
        val schema = JsonParser.parseString(Files.readString(Path.of("../docs/dps-manifest.schema.json"))).asJsonObject

        assertEquals("Datapack Sandbox manifest", schema.get("title").asString)
    }

    @Test
    fun `manifest schema documents includes`() {
        val schema = JsonParser.parseString(Files.readString(Path.of("../docs/dps-manifest.schema.json"))).asJsonObject
        val include = schema.getAsJsonObject("properties").getAsJsonObject("include")
        val includeVariants = include.getAsJsonArray("oneOf")
        val requiredAlternatives = schema.getAsJsonArray("anyOf").map {
            it.asJsonObject.getAsJsonArray("required").single().asString
        }

        assertEquals("string", includeVariants[0].asJsonObject.get("type").asString)
        assertEquals("#/\$defs/stringArray", includeVariants[1].asJsonObject.get("\$ref").asString)
        assertTrue(requiredAlternatives.containsAll(listOf("packs", "include")))
    }

    @Test
    fun `manifest schema documents world fixture references`() {
        val schema = JsonParser.parseString(Files.readString(Path.of("../docs/dps-manifest.schema.json"))).asJsonObject
        val worldProperties = schema.getAsJsonObject("\$defs")
            .getAsJsonObject("worldFixture")
            .getAsJsonObject("properties")
        val worldAssertionProperties = schema.getAsJsonObject("\$defs")
            .getAsJsonObject("worldAssertion")
            .getAsJsonObject("properties")
        val rootProperties = schema.getAsJsonObject("properties")

        listOf("extends", "fixture", "fixtures").forEach { name ->
            val variants = worldProperties.getAsJsonObject(name).getAsJsonArray("oneOf")
            assertEquals("string", variants[0].asJsonObject.get("type").asString)
            assertEquals("#/\$defs/stringArray", variants[1].asJsonObject.get("\$ref").asString)
        }
        assertEquals("integer", rootProperties.getAsJsonObject("seed").get("type").asString)
        assertEquals("boolean", rootProperties.getAsJsonObject("failOnMissingResources").get("type").asString)
        assertEquals(
            "#/\$defs/worldBorder",
            worldProperties.getAsJsonObject("worldBorder").get("\$ref").asString,
        )
        assertEquals(
            "#/\$defs/worldBorder",
            worldAssertionProperties.getAsJsonObject("worldBorder").get("\$ref").asString,
        )
    }

    @Test
    fun `manifest schema documents player event fields`() {
        val schema = JsonParser.parseString(Files.readString(Path.of("../docs/dps-manifest.schema.json"))).asJsonObject
        val defs = schema.getAsJsonObject("\$defs")
        val event = defs.getAsJsonObject("eventStep")
        val properties = event.getAsJsonObject("properties")
        val stepEvent = defs.getAsJsonObject("step")
            .getAsJsonObject("properties")
            .getAsJsonObject("event")
        val assertionEventTrace = defs.getAsJsonObject("assertion")
            .getAsJsonObject("properties")
            .getAsJsonObject("eventTrace")

        assertEquals("#/\$defs/eventStep", stepEvent.get("\$ref").asString)
        assertEquals("#/\$defs/eventTraceAssertion", assertionEventTrace.get("\$ref").asString)
        assertTrue(event.getAsJsonArray("required").map { it.asString }.containsAll(listOf("player", "type")))
        assertEquals("string", properties.getAsJsonObject("damageSource").get("type").asString)
        assertEquals("number", properties.getAsJsonObject("amount").get("type").asString)
        assertEquals("string", properties.getAsJsonObject("mouseButton").get("type").asString)
        assertEquals(
            "boolean",
            defs.getAsJsonObject("eventTraceAssertion")
                .getAsJsonObject("properties")
                .getAsJsonObject("success")
                .get("type")
                .asString,
        )
    }

    @Test
    fun `manifest schema documents snapshot trace and reset steps`() {
        val schema = JsonParser.parseString(Files.readString(Path.of("../docs/dps-manifest.schema.json"))).asJsonObject
        val defs = schema.getAsJsonObject("\$defs")
        val step = defs.getAsJsonObject("step")
        val stepProperties = step.getAsJsonObject("properties")
        val stepRequired = step.getAsJsonArray("oneOf").map {
            it.asJsonObject.getAsJsonArray("required").single().asString
        }
        val stepOutput = defs.getAsJsonObject("stepOutput")
        val stepOutputVariants = stepOutput.getAsJsonArray("oneOf")
        val outputObjectProperties = stepOutputVariants[2].asJsonObject.getAsJsonObject("properties")

        assertTrue(stepRequired.containsAll(listOf("snapshot", "trace", "reset")))
        assertEquals("#/\$defs/stepOutput", stepProperties.getAsJsonObject("snapshot").get("\$ref").asString)
        assertEquals("#/\$defs/stepOutput", stepProperties.getAsJsonObject("trace").get("\$ref").asString)
        assertEquals("boolean", stepProperties.getAsJsonObject("reset").get("type").asString)
        assertEquals("boolean", stepProperties.getAsJsonObject("allowFailure").get("type").asString)
        assertEquals("boolean", stepOutputVariants[0].asJsonObject.get("type").asString)
        assertEquals("string", stepOutputVariants[1].asJsonObject.get("type").asString)
        assertEquals("string", outputObjectProperties.getAsJsonObject("file").get("type").asString)
        assertEquals("boolean", outputObjectProperties.getAsJsonObject("output").get("type").asString)
    }

    @Test
    fun `manifest schema documents diagnostic assertions`() {
        val schema = JsonParser.parseString(Files.readString(Path.of("../docs/dps-manifest.schema.json"))).asJsonObject
        val defs = schema.getAsJsonObject("\$defs")
        val assertion = defs.getAsJsonObject("assertion")
        val assertionRequired = assertion.getAsJsonArray("oneOf").map {
            it.asJsonObject.getAsJsonArray("required").single().asString
        }
        val diagnosticRef = assertion.getAsJsonObject("properties").getAsJsonObject("diagnostic")
        val diagnosticProperties = defs.getAsJsonObject("diagnosticAssertion").getAsJsonObject("properties")
        val snapshotDiffRef = assertion.getAsJsonObject("properties").getAsJsonObject("snapshotDiff")
        val snapshotDiffProperties = defs.getAsJsonObject("snapshotDiffAssertion").getAsJsonObject("properties")

        assertTrue("diagnostic" in assertionRequired)
        assertTrue("snapshotDiff" in assertionRequired)
        assertEquals("#/\$defs/diagnosticAssertion", diagnosticRef.get("\$ref").asString)
        assertEquals("#/\$defs/snapshotDiffAssertion", snapshotDiffRef.get("\$ref").asString)
        assertEquals("integer", diagnosticProperties.getAsJsonObject("step").get("type").asString)
        assertEquals("string", diagnosticProperties.getAsJsonObject("code").get("type").asString)
        assertEquals("string", diagnosticProperties.getAsJsonObject("contains").get("type").asString)
        assertEquals("integer", diagnosticProperties.getAsJsonObject("count").get("type").asString)
        assertEquals("string", snapshotDiffProperties.getAsJsonObject("path").get("type").asString)
        assertEquals("string", snapshotDiffProperties.getAsJsonObject("kind").get("type").asString)
        assertEquals("integer", snapshotDiffProperties.getAsJsonObject("count").get("type").asString)
    }

    @Test
    fun `manifest schema documents predicate loot and advancement assertions`() {
        val schema = JsonParser.parseString(Files.readString(Path.of("../docs/dps-manifest.schema.json"))).asJsonObject
        val defs = schema.getAsJsonObject("\$defs")
        val stepProperties = defs.getAsJsonObject("step").getAsJsonObject("properties")
        val assertionProperties = defs.getAsJsonObject("assertion").getAsJsonObject("properties")
        val advancement = defs.getAsJsonObject("advancementAssertion")
        val advancementProperties = advancement.getAsJsonObject("properties")
        val predicate = defs.getAsJsonObject("predicateAssertion")
        val predicateProperties = predicate.getAsJsonObject("properties")
        val lootRequest = defs.getAsJsonObject("lootRequest")
        val lootRequestProperties = lootRequest.getAsJsonObject("properties")
        val loot = defs.getAsJsonObject("lootAssertion")
        val lootProperties = loot.getAsJsonObject("properties")

        assertEquals("#/\$defs/lootRequest", stepProperties.getAsJsonObject("loot").get("\$ref").asString)
        assertEquals("#/\$defs/advancementAssertion", assertionProperties.getAsJsonObject("advancement").get("\$ref").asString)
        assertEquals("#/\$defs/predicateAssertion", assertionProperties.getAsJsonObject("predicate").get("\$ref").asString)
        assertEquals("#/\$defs/lootAssertion", assertionProperties.getAsJsonObject("loot").get("\$ref").asString)
        assertTrue(advancement.getAsJsonArray("required").map { it.asString }.containsAll(listOf("player", "id")))
        assertEquals("boolean", advancementProperties.getAsJsonObject("done").get("type").asString)
        assertEquals("string", advancementProperties.getAsJsonObject("criterion").get("type").asString)
        assertEquals("boolean", advancementProperties.getAsJsonObject("criterionDone").get("type").asString)
        assertTrue(predicate.getAsJsonArray("required").map { it.asString }.contains("id"))
        assertEquals("string", predicateProperties.getAsJsonObject("player").get("type").asString)
        assertEquals("boolean", predicateProperties.getAsJsonObject("equals").get("type").asString)
        assertTrue(lootRequest.getAsJsonArray("required").map { it.asString }.contains("table"))
        assertEquals("integer", lootRequestProperties.getAsJsonObject("seed").get("type").asString)
        assertTrue(loot.getAsJsonArray("required").map { it.asString }.contains("table"))
        assertEquals("integer", lootProperties.getAsJsonObject("seed").get("type").asString)
        assertEquals("integer", lootProperties.getAsJsonObject("count").get("type").asString)
        assertEquals(0, lootProperties.getAsJsonObject("count").get("minimum").asInt)
        assertEquals("string", lootProperties.getAsJsonObject("item").get("type").asString)
    }

    @Test
    fun `manifest schema documents output assertion order`() {
        val schema = JsonParser.parseString(Files.readString(Path.of("../docs/dps-manifest.schema.json"))).asJsonObject
        val defs = schema.getAsJsonObject("\$defs")
        val outputRef = defs.getAsJsonObject("assertion")
            .getAsJsonObject("properties")
            .getAsJsonObject("output")
        val outputProperties = defs.getAsJsonObject("outputAssertion").getAsJsonObject("properties")

        assertEquals("#/\$defs/outputAssertion", outputRef.get("\$ref").asString)
        assertEquals("integer", outputProperties.getAsJsonObject("order").get("type").asString)
        assertEquals(1, outputProperties.getAsJsonObject("order").get("minimum").asInt)
        assertEquals("string", outputProperties.getAsJsonObject("normalizedText").get("type").asString)
        assertEquals("string", outputProperties.getAsJsonObject("normalizedContains").get("type").asString)
        val segmentProperties = outputProperties.getAsJsonObject("segment").getAsJsonObject("properties")
        assertEquals("string", segmentProperties.getAsJsonObject("normalizedText").get("type").asString)
    }

    @Test
    fun `manifest schema documents score min max assertions`() {
        val schema = JsonParser.parseString(Files.readString(Path.of("../docs/dps-manifest.schema.json"))).asJsonObject
        val scoreAssertion = schema.getAsJsonObject("\$defs").getAsJsonObject("scoreAssertion")
        val properties = scoreAssertion.getAsJsonObject("properties")
        val requiredAlternatives = scoreAssertion.getAsJsonArray("anyOf").map {
            it.asJsonObject.getAsJsonArray("required").single().asString
        }

        assertTrue(requiredAlternatives.containsAll(listOf("equals", "min", "max")))
        assertEquals("integer", properties.getAsJsonObject("min").get("type").asString)
        assertEquals("integer", properties.getAsJsonObject("max").get("type").asString)
    }

    @Test
    fun `manifest schema documents entity count range assertions`() {
        val schema = JsonParser.parseString(Files.readString(Path.of("../docs/dps-manifest.schema.json"))).asJsonObject
        val defs = schema.getAsJsonObject("\$defs")
        val assertionRef = defs.getAsJsonObject("assertion")
            .getAsJsonObject("properties")
            .getAsJsonObject("entityCount")
        val entityCountAssertion = defs.getAsJsonObject("entityCountAssertion")
        val properties = entityCountAssertion.getAsJsonObject("properties")
        val requiredAlternatives = entityCountAssertion.getAsJsonArray("anyOf").map {
            it.asJsonObject.getAsJsonArray("required").single().asString
        }

        assertEquals("#/\$defs/entityCountAssertion", assertionRef.get("\$ref").asString)
        assertTrue(requiredAlternatives.containsAll(listOf("equals", "min", "max")))
        assertEquals("integer", properties.getAsJsonObject("min").get("type").asString)
        assertEquals("integer", properties.getAsJsonObject("max").get("type").asString)
    }

    @Test
    fun `manifest schema documents storage existence assertions`() {
        val schema = JsonParser.parseString(Files.readString(Path.of("../docs/dps-manifest.schema.json"))).asJsonObject
        val defs = schema.getAsJsonObject("\$defs")
        val assertionRef = defs.getAsJsonObject("assertion")
            .getAsJsonObject("properties")
            .getAsJsonObject("storage")
        val storageAssertion = defs.getAsJsonObject("storageAssertion")
        val properties = storageAssertion.getAsJsonObject("properties")
        val requiredAlternatives = storageAssertion.getAsJsonArray("anyOf").map {
            it.asJsonObject.getAsJsonArray("required").single().asString
        }

        assertEquals("#/\$defs/storageAssertion", assertionRef.get("\$ref").asString)
        assertTrue(requiredAlternatives.containsAll(listOf("equals", "exists", "missing")))
        assertEquals("string", properties.getAsJsonObject("id").get("type").asString)
        assertEquals("string", properties.getAsJsonObject("path").get("type").asString)
        assertEquals("boolean", properties.getAsJsonObject("exists").get("type").asString)
        assertEquals("boolean", properties.getAsJsonObject("missing").get("type").asString)
    }

    @Test
    fun `manifest schema documents player existence assertions`() {
        val schema = JsonParser.parseString(Files.readString(Path.of("../docs/dps-manifest.schema.json"))).asJsonObject
        val properties = schema.getAsJsonObject("\$defs")
            .getAsJsonObject("playerAssertion")
            .getAsJsonObject("properties")

        assertEquals("boolean", properties.getAsJsonObject("exists").get("type").asString)
    }

    @Test
    fun `manifest schema documents item count range assertions`() {
        val schema = JsonParser.parseString(Files.readString(Path.of("../docs/dps-manifest.schema.json"))).asJsonObject
        val properties = schema.getAsJsonObject("\$defs")
            .getAsJsonObject("itemAssertion")
            .getAsJsonObject("properties")

        assertEquals("integer", properties.getAsJsonObject("minCount").get("type").asString)
        assertEquals("integer", properties.getAsJsonObject("maxCount").get("type").asString)
        assertEquals(0, properties.getAsJsonObject("minCount").get("minimum").asInt)
        assertEquals(0, properties.getAsJsonObject("maxCount").get("minimum").asInt)
    }

    @Test
    fun `manifest schema documents block nbt assertions`() {
        val schema = JsonParser.parseString(Files.readString(Path.of("../docs/dps-manifest.schema.json"))).asJsonObject
        val defs = schema.getAsJsonObject("\$defs")
        val assertionRef = defs.getAsJsonObject("assertion")
            .getAsJsonObject("properties")
            .getAsJsonObject("block")
        val properties = defs.getAsJsonObject("blockAssertion").getAsJsonObject("properties")

        assertEquals("#/\$defs/blockAssertion", assertionRef.get("\$ref").asString)
        assertEquals("#/\$defs/blockPos", properties.getAsJsonObject("pos").get("\$ref").asString)
        assertEquals("string", properties.getAsJsonObject("id").get("type").asString)
        assertEquals("boolean", properties.getAsJsonObject("exists").get("type").asString)
        assertEquals("#/\$defs/pathExpectation", properties.getAsJsonObject("nbt").get("\$ref").asString)
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
    fun `runs all example manifests`() {
        val manifests = ManifestRunner.discover(listOf(Path.of("../examples")))
        val exampleKinds = manifests.map { it.parent.fileName.toString() }.toSet()

        assertTrue(exampleKinds.containsAll(listOf("full-stack", "single-function", "generator-output", "multi-version")))

        val results = manifests.map { ManifestRunner.run(it) }
        val failures = results.flatMap { result -> result.messages.map { "${result.path}: $it" } }
        assertTrue(results.all { it.passed }, failures.joinToString())
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
                { "command": "tellraw Steve {\"text\":\"gold\",\"color\":\"yellow\"}" },
                { "command": "tellraw Steve {\"text\":\"hello     generated     output\"}" }
              ],
              "assertions": [
                {
                  "output": {
                    "command": "say",
                    "channel": "chat",
                    "target": "Steve",
                    "contains": "hello from manifest",
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
                    "segment": {
                      "normalizedText": "hello generated output"
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
              "steps": [],
              "assertions": [
                {
                  "world": {
                    "seed": 123,
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
                    "spawn": { "pos": [2, 66, 3], "dimension": "minecraft:overworld", "angle": 90, "forced": true }
                  }
                },
                { "item": { "player": "Alex", "id": "minecraft:stick", "count": 2, "minCount": 1, "maxCount": 3 } },
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
    fun `manifest top-level seed sets default world seed`() {
        val dir = Files.createTempDirectory("dps-manifest-seed")
        val pack = Path.of("../core/src/test/resources/packs/counter").toAbsolutePath().normalize().toString().replace("\\", "\\\\")
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
            sandbox.generateLoot(
                ResourceLocation.parse("demo:choice"),
                ResourceLocation.parse("minecraft:empty"),
                seed = seed,
            ).items.single().id.toString()

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
        val pack = Path.of("../core/src/test/resources/packs/counter").toAbsolutePath().normalize().toString().replace("\\", "\\\\")
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
                { "advancement": { "player": "Steve", "id": "demo:fall_damage", "criterion": "fell", "criterionDone": true } },
                { "eventTrace": { "player": "Steve", "type": "damage", "success": true, "advancement": "demo:fall_damage", "criterion": "fell", "count": 1 } }
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
        assertTrue("assertion 1 (/assertions/0):" in messages, messages)
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
                { "item": { "player": "Steve", "id": "minecraft:apple", "minCount": 2, "maxCount": 4 } },
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
    fun `exports manifest snapshots and traces and resets world state`() {
        val dir = Files.createTempDirectory("dps-debug-step-manifest")
        val pack = Path.of("../core/src/test/resources/packs/counter").toAbsolutePath().normalize().toString().replace("\\", "\\\\")
        val manifest = dir.resolve("debug-steps.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.1.2",
              "seed": 44,
              "packs": ["$pack"],
              "steps": [
                { "command": "scoreboard objectives add runs dummy" },
                { "command": "scoreboard players set #before_reset runs 2" },
                { "snapshot": "artifacts/before.json" },
                { "trace": "artifacts/before.jsonl" },
                { "reset": true },
                { "command": "list" },
                { "snapshot": { "output": true } },
                { "trace": { "output": true } },
                { "player": { "name": "Alex", "xp": 7 } }
              ],
              "assertions": [
                { "score": { "target": "#before_reset", "objective": "runs", "equals": 0 } },
                { "world": { "seed": 44 } },
                { "player": { "name": "Steve" } },
                { "player": { "name": "Alex", "xp": 7 } },
                { "trace": { "root": "list", "success": true, "count": 1 } },
                { "output": { "command": "manifest snapshot", "channel": "debug", "contains": "\"Steve\"", "count": 1 } },
                { "output": { "command": "manifest trace", "channel": "debug", "text": "1", "count": 1 } }
              ]
            }
            """.trimIndent(),
        )

        val result = ManifestRunner.run(manifest)

        assertTrue(result.passed, result.messages.joinToString())
        val snapshot = Files.readString(dir.resolve("artifacts/before.json"))
        val trace = Files.readString(dir.resolve("artifacts/before.jsonl"))
        assertTrue("\"#before_reset\": 2" in snapshot, snapshot)
        assertTrue("\"command\": \"scoreboard players set #before_reset runs 2\"" in trace, trace)
        assertTrue(result.outputs.any { it.command == "manifest snapshot" && it.channel == "debug" && "\"Steve\"" in it.text })
        assertTrue(result.outputs.any { it.command == "manifest trace" && it.channel == "debug" && it.text == "1" })
    }

    @Test
    fun `allows expected manifest diagnostics to be asserted`() {
        val dir = Files.createTempDirectory("dps-diagnostic-manifest")
        val pack = Path.of("../core/src/test/resources/packs/counter").toAbsolutePath().normalize().toString().replace("\\", "\\\\")
        val manifest = dir.resolve("diagnostic.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.1.2",
              "packs": ["$pack"],
              "steps": [
                { "command": "scoreboard players set #bad missing 1", "allowFailure": true },
                { "command": "say after expected diagnostic" }
              ],
              "assertions": [
                {
                  "diagnostic": {
                    "step": 1,
                    "code": "COMMAND_ERROR",
                    "command": "scoreboard players set #bad missing 1",
                    "contains": "Unknown scoreboard objective 'missing'",
                    "count": 1
                  }
                },
                { "trace": { "command": "scoreboard players set #bad missing 1", "success": false, "count": 1 } },
                { "trace": { "command": "say after expected diagnostic", "success": true, "count": 1 } },
                { "output": { "command": "say", "contains": "after expected diagnostic", "count": 1 } }
              ]
            }
            """.trimIndent(),
        )

        val result = ManifestRunner.run(manifest)

        assertTrue(result.passed, result.messages.joinToString())
    }

    @Test
    fun `asserts manifest snapshot diffs`() {
        val dir = Files.createTempDirectory("dps-snapshot-diff-manifest")
        val pack = Path.of("../core/src/test/resources/packs/counter").toAbsolutePath().normalize().toString().replace("\\", "\\\\")
        val manifest = dir.resolve("snapshot-diff.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.1.2",
              "packs": ["$pack"],
              "steps": [
                { "command": "scoreboard objectives add runs dummy" },
                { "command": "scoreboard players set #diff runs 3" }
              ],
              "assertions": [
                { "snapshotDiff": { "path": "/scores/runs", "kind": "added", "after": { "#diff": 3 }, "count": 1 } },
                { "snapshotDiff": { "contains": "\"#diff\": 3", "count": 1 } }
              ]
            }
            """.trimIndent(),
        )

        val result = ManifestRunner.run(manifest)

        assertTrue(result.passed, result.messages.joinToString())
    }

    @Test
    fun `runs score min max assertions`() {
        val dir = Files.createTempDirectory("dps-score-range-manifest")
        val pack = Path.of("../core/src/test/resources/packs/counter").toAbsolutePath().normalize().toString().replace("\\", "\\\\")
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
        val pack = Path.of("../core/src/test/resources/packs/counter").toAbsolutePath().normalize().toString().replace("\\", "\\\\")
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
        assertTrue(result.messages.any { "entityCount type=minecraft:pig, tag=range expected <= 1 but was 2" in it }, result.messages.joinToString())
    }

    @Test
    fun `runs storage exists and missing assertions`() {
        val dir = Files.createTempDirectory("dps-storage-existence-manifest")
        val pack = Path.of("../core/src/test/resources/packs/counter").toAbsolutePath().normalize().toString().replace("\\", "\\\\")
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
    fun `runs player existence assertions`() {
        val dir = Files.createTempDirectory("dps-player-existence-manifest")
        val pack = Path.of("../core/src/test/resources/packs/counter").toAbsolutePath().normalize().toString().replace("\\", "\\\\")
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
        val pack = Path.of("../core/src/test/resources/packs/counter").toAbsolutePath().normalize().toString().replace("\\", "\\\\")
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
        assertTrue(result.messages.any { "item for player Alex expected id=minecraft:stick, minCount=3" in it }, result.messages.joinToString())
    }

    @Test
    fun `runs block nbt existence assertions`() {
        val dir = Files.createTempDirectory("dps-block-nbt-manifest")
        val pack = Path.of("../core/src/test/resources/packs/counter").toAbsolutePath().normalize().toString().replace("\\", "\\\\")
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
        assertTrue(result.messages.any { "block 0 64 0 nbt Missing exists expected true but was false" in it }, result.messages.joinToString())
    }

    @Test
    fun `runs manifest world spawn angle assertions`() {
        val dir = Files.createTempDirectory("dps-world-spawn-angle-manifest")
        val pack = Path.of("../core/src/test/resources/packs/counter").toAbsolutePath().normalize().toString().replace("\\", "\\\\")
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
        val pack = Path.of("../core/src/test/resources/packs/counter").toAbsolutePath().normalize().toString().replace("\\", "\\\\")
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
        val pack = Path.of("../core/src/test/resources/packs/counter").toAbsolutePath().normalize().toString().replace("\\", "\\\\")
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
        val pack = Path.of("../core/src/test/resources/packs/counter").toAbsolutePath().normalize().toString().replace("\\", "\\\\")
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
        assertEquals(4, result.attempts.single().resourceSummary?.missingReferences?.size)
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

    private fun writeWeightedLootPack(root: Path): Path {
        Files.createDirectories(root)
        Files.writeString(
            root.resolve("pack.mcmeta"),
            """
            {
              "pack": {
                "pack_format": 107.1,
                "description": "weighted loot seed test"
              }
            }
            """.trimIndent(),
        )
        val lootRoot = root.resolve("data").resolve("demo").resolve("loot_table")
        Files.createDirectories(lootRoot)
        Files.writeString(
            lootRoot.resolve("choice.json"),
            """
            {
              "pools": [
                {
                  "rolls": 1,
                  "entries": [
                    { "type": "minecraft:item", "name": "minecraft:diamond", "weight": 1 },
                    { "type": "minecraft:item", "name": "minecraft:emerald", "weight": 1 }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        return root
    }

    private fun writeMissingReferencePack(root: Path): Path {
        Files.createDirectories(root)
        Files.writeString(
            root.resolve("pack.mcmeta"),
            """
            {
              "pack": {
                "pack_format": 107.1,
                "description": "missing resource reference test"
              }
            }
            """.trimIndent(),
        )
        val tagRoot = root.resolve("data").resolve("minecraft").resolve("tags").resolve("function")
        Files.createDirectories(tagRoot)
        Files.writeString(tagRoot.resolve("load.json"), """{"values":["demo:missing_load"]}""")

        val advancementRoot = root.resolve("data").resolve("demo").resolve("advancement")
        Files.createDirectories(advancementRoot)
        Files.writeString(
            advancementRoot.resolve("child.json"),
            """
            {
              "parent": "demo:missing_parent",
              "criteria": {
                "tick": {
                  "trigger": "minecraft:tick"
                }
              }
            }
            """.trimIndent(),
        )

        val predicateRoot = root.resolve("data").resolve("demo").resolve("predicate")
        Files.createDirectories(predicateRoot)
        Files.writeString(
            predicateRoot.resolve("uses_missing.json"),
            """
            {
              "condition": "minecraft:reference",
              "name": "demo:missing_predicate"
            }
            """.trimIndent(),
        )

        val lootRoot = root.resolve("data").resolve("demo").resolve("loot_table")
        Files.createDirectories(lootRoot)
        Files.writeString(
            lootRoot.resolve("outer.json"),
            """
            {
              "pools": [
                {
                  "rolls": 1,
                  "entries": [
                    {
                      "type": "minecraft:loot_table",
                      "value": "demo:missing_nested"
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
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
