package moe.afox.dpsandbox.cli

import com.google.gson.JsonParser
import moe.afox.dpsandbox.core.BlockPos
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManifestEventAndDiagnosticsTest : ManifestRunnerTestSupport() {
    @Test
    fun `runs keyboard input events from manifests`() {
        val dir = Files.createTempDirectory("dps-input-manifest")
        val pack =
            Path
                .of("../core/src/test/resources/packs/counter")
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
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
        val packPath =
            pack
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
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
                {
                  "eventTrace": {
                    "player": "Steve",
                    "type": "damage",
                    "success": true,
                    "advancement": "demo:fall_damage",
                    "criterion": "fell",
                    "damageSource": "minecraft:fall",
                    "amount": 4.5,
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
    fun `runs manifest event trace assertions for failed advancement criteria`() {
        val dir = Files.createTempDirectory("dps-event-failure-manifest")
        val pack = dir.resolve("pack")
        val advancementRoot = pack.resolve("data").resolve("demo").resolve("advancement")
        Files.createDirectories(advancementRoot)
        Files.writeString(
            pack.resolve("pack.mcmeta"),
            """
            {
              "pack": {
                "pack_format": 107.1,
                "description": "event failure fixture"
              }
            }
            """.trimIndent(),
        )
        Files.writeString(
            advancementRoot.resolve("place_diamond.json"),
            """
            {
              "criteria": {
                "place_diamond": {
                  "trigger": "minecraft:placed_block",
                  "conditions": {
                    "block": "minecraft:diamond_block"
                  }
                }
              }
            }
            """.trimIndent(),
        )
        val manifest = dir.resolve("event-failure.dps.json")
        val packPath =
            pack
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.2",
              "packs": ["$packPath"],
              "steps": [
                { "event": { "player": "Steve", "type": "block_placed", "block": "minecraft:stone", "blockPos": [2, 64, 3] } }
              ],
              "assertions": [
                { "advancement": { "player": "Steve", "id": "demo:place_diamond", "criterion": "place_diamond", "criterionDone": false } },
                {
                  "eventTrace": {
                    "player": "Steve",
                    "type": "block_placed",
                    "success": true,
                    "blockX": 2,
                    "blockY": 64,
                    "blockZ": 3,
                    "failedAdvancement": "demo:place_diamond",
                    "failedCriterion": "place_diamond",
                    "failureContains": "block expected minecraft:diamond_block",
                    "count": 1
                  }
                }
              ]
            }
            """.trimIndent(),
        )

        val schemaFailures = ManifestSchemaValidator.validate(JsonParser.parseString(Files.readString(manifest)))
        val result = ManifestRunner.run(manifest)

        assertTrue(schemaFailures.isEmpty(), schemaFailures.joinToString())
        assertTrue(result.passed, result.messages.joinToString())
        assertEquals(BlockPos(2, 64, 3), result.eventTraces.single().blockPos)
        assertTrue(
            "minecraft:stone" in
                result.eventTraces
                    .single()
                    .advancementFailures
                    .single()
                    .reason,
        )
    }

    @Test
    fun `adds snapshot diff to failing manifest reports`() {
        val dir = Files.createTempDirectory("dps-diff-manifest")
        val pack =
            Path
                .of("../core/src/test/resources/packs/counter")
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
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
        assertTrue("assertion 1 (/assertions/0/score):" in messages, messages)
        assertTrue("score #manifest_diff runs expected 3 but was 2" in messages, messages)
        assertTrue("snapshot diff:" in messages, messages)
        assertTrue("+ /scores/runs =" in messages, messages)
        assertTrue("\"#manifest_diff\": 2" in messages, messages)
    }

    @Test
    fun `runs generated command text and asserts trace and items`() {
        val dir = Files.createTempDirectory("dps-generator-manifest")
        val pack =
            Path
                .of("../core/src/test/resources/packs/counter")
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
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
                {
                  "trace": {
                    "contains": "give Steve",
                    "fileContains": "generated.mcfunction",
                    "success": true,
                    "outputContains": "3",
                    "outputTarget": "Steve",
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
    fun `exports manifest snapshots and traces and resets world state`() {
        val dir = Files.createTempDirectory("dps-debug-step-manifest")
        val pack =
            Path
                .of("../core/src/test/resources/packs/counter")
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
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
        val pack =
            Path
                .of("../core/src/test/resources/packs/counter")
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
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
    fun `diagnostic assertion failures include actual diagnostic candidates`() {
        val dir = Files.createTempDirectory("dps-diagnostic-failure-manifest")
        val pack =
            Path
                .of("../core/src/test/resources/packs/counter")
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
        val manifest = dir.resolve("diagnostic-failure.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.1.2",
              "packs": ["$pack"],
              "steps": [
                { "command": "scoreboard players set #bad missing 1", "allowFailure": true }
              ],
              "assertions": [
                { "diagnostic": { "code": "VERSION_MISMATCH", "count": 1 } }
              ]
            }
            """.trimIndent(),
        )

        val result = ManifestRunner.run(manifest)
        val messages = result.messages.joinToString("\n")

        assertFalse(result.passed)
        assertTrue("assertion 1 (/assertions/0/diagnostic):" in messages, messages)
        assertTrue("actual diagnostics:" in messages, messages)
        assertTrue("step=1" in messages, messages)
        assertTrue("code=COMMAND_ERROR" in messages, messages)
        assertTrue("command=scoreboard players set #bad missing 1" in messages, messages)
        assertTrue("Unknown scoreboard objective 'missing'" in messages, messages)
    }

    @Test
    fun `asserts manifest snapshot diffs`() {
        val dir = Files.createTempDirectory("dps-snapshot-diff-manifest")
        val pack =
            Path
                .of("../core/src/test/resources/packs/counter")
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
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
    fun `asserts final manifest snapshots against golden files`() {
        val dir = Files.createTempDirectory("dps-snapshot-golden-manifest")
        val pack =
            Path
                .of("../core/src/test/resources/packs/counter")
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
        val manifest = dir.resolve("snapshot-golden.dps.json")
        Files.writeString(dir.resolve("expected-score.json"), "7")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.1.2",
              "packs": ["$pack"],
              "steps": [
                { "command": "scoreboard objectives add runs dummy" },
                { "command": "scoreboard players set #golden runs 7" }
              ],
              "assertions": [
                { "snapshot": { "path": "scores.runs.#golden", "equalsFile": "expected-score.json" } },
                { "snapshot": { "path": "scores.runs.#golden", "equals": 7 } },
                { "snapshot": { "path": "scores.runs", "exists": true } },
                { "snapshot": { "path": "scores.runs.#missing", "missing": true } }
              ]
            }
            """.trimIndent(),
        )

        val result = ManifestRunner.run(manifest)

        assertTrue(result.passed, result.messages.joinToString())
    }

    @Test
    fun `snapshot diff assertion failures include actual diff candidates`() {
        val dir = Files.createTempDirectory("dps-snapshot-diff-failure-manifest")
        val pack =
            Path
                .of("../core/src/test/resources/packs/counter")
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
        val manifest = dir.resolve("snapshot-diff-failure.dps.json")
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
                { "snapshotDiff": { "path": "/storage/demo:missing", "kind": "added" } }
              ]
            }
            """.trimIndent(),
        )

        val result = ManifestRunner.run(manifest)
        val messages = result.messages.joinToString("\n")

        assertFalse(result.passed)
        assertTrue("assertion 1 (/assertions/0/snapshotDiff):" in messages, messages)
        assertTrue("actual snapshot diffs:" in messages, messages)
        assertTrue("+ /scores/runs =" in messages, messages)
        assertTrue("\"#diff\": 3" in messages, messages)
    }
}
