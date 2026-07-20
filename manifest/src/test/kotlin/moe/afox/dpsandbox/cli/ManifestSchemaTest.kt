package moe.afox.dpsandbox.cli

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManifestSchemaTest : ManifestRunnerTestSupport() {
    @Test
    fun `manifest schema parses as json`() {
        val schema = JsonParser.parseString(Files.readString(Path.of("../schema/manifest/dps-manifest.schema.json"))).asJsonObject

        assertEquals("Datapack Sandbox manifest", schema.get("title").asString)
    }

    @Test
    fun `manifest schema documents includes`() {
        val schema = JsonParser.parseString(Files.readString(Path.of("../schema/manifest/dps-manifest.schema.json"))).asJsonObject
        val include = schema.getAsJsonObject("properties").getAsJsonObject("include")
        val includeVariants = include.getAsJsonArray("oneOf")
        val requiredAlternatives =
            schema.getAsJsonArray("anyOf").map {
                it.asJsonObject
                    .getAsJsonArray("required")
                    .single()
                    .asString
            }

        assertEquals("string", includeVariants[0].asJsonObject.get("type").asString)
        assertEquals("#/\$defs/stringArray", includeVariants[1].asJsonObject.get("\$ref").asString)
        assertTrue(requiredAlternatives.containsAll(listOf("packs", "include")))
    }

    @Test
    fun `manifest schema documents world fixture references`() {
        val schema = JsonParser.parseString(Files.readString(Path.of("../schema/manifest/dps-manifest.schema.json"))).asJsonObject
        val worldProperties =
            schema
                .getAsJsonObject("\$defs")
                .getAsJsonObject("worldFixture")
                .getAsJsonObject("properties")
        val worldAssertionProperties =
            schema
                .getAsJsonObject("\$defs")
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
        assertEquals(
            "#/\$defs/saveImport",
            worldProperties.getAsJsonObject("save").get("\$ref").asString,
        )
        assertEquals(
            "#/\$defs/saveImport",
            worldProperties
                .getAsJsonObject("saves")
                .getAsJsonObject("items")
                .get("\$ref")
                .asString,
        )
    }

    @Test
    fun `manifest schema documents player event fields`() {
        val schema = JsonParser.parseString(Files.readString(Path.of("../schema/manifest/dps-manifest.schema.json"))).asJsonObject
        val defs = schema.getAsJsonObject("\$defs")
        val event = defs.getAsJsonObject("eventStep")
        val properties = event.getAsJsonObject("properties")
        val stepEvent =
            defs
                .getAsJsonObject("step")
                .getAsJsonObject("properties")
                .getAsJsonObject("event")
        val assertionEventTrace =
            defs
                .getAsJsonObject("assertion")
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
            defs
                .getAsJsonObject("eventTraceAssertion")
                .getAsJsonObject("properties")
                .getAsJsonObject("success")
                .get("type")
                .asString,
        )
        assertEquals(
            "string",
            defs
                .getAsJsonObject("eventTraceAssertion")
                .getAsJsonObject("properties")
                .getAsJsonObject("item")
                .get("type")
                .asString,
        )
        assertEquals(
            "number",
            defs
                .getAsJsonObject("eventTraceAssertion")
                .getAsJsonObject("properties")
                .getAsJsonObject("damageAmount")
                .get("type")
                .asString,
        )
        assertEquals(
            "string",
            defs
                .getAsJsonObject("eventTraceAssertion")
                .getAsJsonObject("properties")
                .getAsJsonObject("failedAdvancement")
                .get("type")
                .asString,
        )
        assertEquals(
            "string",
            defs
                .getAsJsonObject("eventTraceAssertion")
                .getAsJsonObject("properties")
                .getAsJsonObject("inputCode")
                .get("type")
                .asString,
        )
    }

    @Test
    fun `manifest schema documents snapshot trace and reset steps`() {
        val schema = JsonParser.parseString(Files.readString(Path.of("../schema/manifest/dps-manifest.schema.json"))).asJsonObject
        val defs = schema.getAsJsonObject("\$defs")
        val step = defs.getAsJsonObject("step")
        val stepProperties = step.getAsJsonObject("properties")
        val stepRequired =
            step.getAsJsonArray("oneOf").map {
                it.asJsonObject
                    .getAsJsonArray("required")
                    .single()
                    .asString
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
        val traceProperties = defs.getAsJsonObject("traceAssertion").getAsJsonObject("properties")
        assertEquals("string", traceProperties.getAsJsonObject("outputContains").get("type").asString)
        assertEquals("string", traceProperties.getAsJsonObject("outputTarget").get("type").asString)
    }

    @Test
    fun `manifest schema documents diagnostic assertions`() {
        val schema = JsonParser.parseString(Files.readString(Path.of("../schema/manifest/dps-manifest.schema.json"))).asJsonObject
        val defs = schema.getAsJsonObject("\$defs")
        val assertion = defs.getAsJsonObject("assertion")
        val assertionRequired =
            assertion.getAsJsonArray("oneOf").map {
                it.asJsonObject
                    .getAsJsonArray("required")
                    .single()
                    .asString
            }
        val diagnosticRef = assertion.getAsJsonObject("properties").getAsJsonObject("diagnostic")
        val diagnosticProperties = defs.getAsJsonObject("diagnosticAssertion").getAsJsonObject("properties")
        val snapshotRef = assertion.getAsJsonObject("properties").getAsJsonObject("snapshot")
        val snapshotProperties = defs.getAsJsonObject("snapshotAssertion").getAsJsonObject("properties")
        val snapshotDiffRef = assertion.getAsJsonObject("properties").getAsJsonObject("snapshotDiff")
        val snapshotDiffProperties = defs.getAsJsonObject("snapshotDiffAssertion").getAsJsonObject("properties")

        assertTrue("diagnostic" in assertionRequired)
        assertTrue("snapshot" in assertionRequired)
        assertTrue("snapshotDiff" in assertionRequired)
        assertEquals("#/\$defs/diagnosticAssertion", diagnosticRef.get("\$ref").asString)
        assertEquals("#/\$defs/snapshotAssertion", snapshotRef.get("\$ref").asString)
        assertEquals("#/\$defs/snapshotDiffAssertion", snapshotDiffRef.get("\$ref").asString)
        assertEquals("integer", diagnosticProperties.getAsJsonObject("step").get("type").asString)
        assertEquals("string", diagnosticProperties.getAsJsonObject("code").get("type").asString)
        assertEquals("string", diagnosticProperties.getAsJsonObject("contains").get("type").asString)
        assertEquals("integer", diagnosticProperties.getAsJsonObject("count").get("type").asString)
        assertEquals("string", snapshotProperties.getAsJsonObject("path").get("type").asString)
        assertEquals("string", snapshotProperties.getAsJsonObject("equalsFile").get("type").asString)
        assertEquals("boolean", snapshotProperties.getAsJsonObject("exists").get("type").asString)
        assertEquals("boolean", snapshotProperties.getAsJsonObject("missing").get("type").asString)
        assertEquals("string", snapshotDiffProperties.getAsJsonObject("path").get("type").asString)
        assertEquals("string", snapshotDiffProperties.getAsJsonObject("kind").get("type").asString)
        assertEquals("integer", snapshotDiffProperties.getAsJsonObject("count").get("type").asString)
    }

    @Test
    fun `manifest schema documents predicate loot and advancement assertions`() {
        val schema = JsonParser.parseString(Files.readString(Path.of("../schema/manifest/dps-manifest.schema.json"))).asJsonObject
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
        val schema = JsonParser.parseString(Files.readString(Path.of("../schema/manifest/dps-manifest.schema.json"))).asJsonObject
        val defs = schema.getAsJsonObject("\$defs")
        val outputRef =
            defs
                .getAsJsonObject("assertion")
                .getAsJsonObject("properties")
                .getAsJsonObject("output")
        val outputProperties = defs.getAsJsonObject("outputAssertion").getAsJsonObject("properties")

        assertEquals("#/\$defs/outputAssertion", outputRef.get("\$ref").asString)
        assertEquals("integer", outputProperties.getAsJsonObject("order").get("type").asString)
        assertEquals(1, outputProperties.getAsJsonObject("order").get("minimum").asInt)
        assertEquals("string", outputProperties.getAsJsonObject("matches").get("type").asString)
        assertEquals("string", outputProperties.getAsJsonObject("normalizedText").get("type").asString)
        assertEquals("string", outputProperties.getAsJsonObject("normalizedContains").get("type").asString)
        assertEquals("string", outputProperties.getAsJsonObject("normalizedMatches").get("type").asString)
        assertEquals("string", outputProperties.getAsJsonObject("payloadPath").get("type").asString)
        val payloadProperties = outputProperties.getAsJsonObject("payload").getAsJsonObject("properties")
        assertEquals("string", payloadProperties.getAsJsonObject("path").get("type").asString)
        val segmentProperties = outputProperties.getAsJsonObject("segment").getAsJsonObject("properties")
        assertEquals("string", segmentProperties.getAsJsonObject("matches").get("type").asString)
        assertEquals("string", segmentProperties.getAsJsonObject("normalizedText").get("type").asString)
        assertEquals("string", segmentProperties.getAsJsonObject("normalizedMatches").get("type").asString)
    }

    @Test
    fun `manifest schema documents score min max assertions`() {
        val schema = JsonParser.parseString(Files.readString(Path.of("../schema/manifest/dps-manifest.schema.json"))).asJsonObject
        val scoreAssertion = schema.getAsJsonObject("\$defs").getAsJsonObject("scoreAssertion")
        val properties = scoreAssertion.getAsJsonObject("properties")
        val requiredAlternatives =
            scoreAssertion.getAsJsonArray("anyOf").map {
                it.asJsonObject
                    .getAsJsonArray("required")
                    .single()
                    .asString
            }

        assertTrue(requiredAlternatives.containsAll(listOf("equals", "min", "max")))
        assertEquals("integer", properties.getAsJsonObject("min").get("type").asString)
        assertEquals("integer", properties.getAsJsonObject("max").get("type").asString)
    }

    @Test
    fun `manifest schema documents entity count range assertions`() {
        val schema = JsonParser.parseString(Files.readString(Path.of("../schema/manifest/dps-manifest.schema.json"))).asJsonObject
        val defs = schema.getAsJsonObject("\$defs")
        val assertionRef =
            defs
                .getAsJsonObject("assertion")
                .getAsJsonObject("properties")
                .getAsJsonObject("entityCount")
        val entityCountAssertion = defs.getAsJsonObject("entityCountAssertion")
        val properties = entityCountAssertion.getAsJsonObject("properties")
        val requiredAlternatives =
            entityCountAssertion.getAsJsonArray("anyOf").map {
                it.asJsonObject
                    .getAsJsonArray("required")
                    .single()
                    .asString
            }

        assertEquals("#/\$defs/entityCountAssertion", assertionRef.get("\$ref").asString)
        assertTrue(requiredAlternatives.containsAll(listOf("equals", "min", "max")))
        assertEquals("integer", properties.getAsJsonObject("min").get("type").asString)
        assertEquals("integer", properties.getAsJsonObject("max").get("type").asString)
    }

    @Test
    fun `manifest schema documents storage existence assertions`() {
        val schema = JsonParser.parseString(Files.readString(Path.of("../schema/manifest/dps-manifest.schema.json"))).asJsonObject
        val defs = schema.getAsJsonObject("\$defs")
        val assertionRef =
            defs
                .getAsJsonObject("assertion")
                .getAsJsonObject("properties")
                .getAsJsonObject("storage")
        val storageAssertion = defs.getAsJsonObject("storageAssertion")
        val properties = storageAssertion.getAsJsonObject("properties")
        val requiredAlternatives =
            storageAssertion.getAsJsonArray("anyOf").map {
                it.asJsonObject
                    .getAsJsonArray("required")
                    .single()
                    .asString
            }

        assertEquals("#/\$defs/storageAssertion", assertionRef.get("\$ref").asString)
        assertTrue(requiredAlternatives.containsAll(listOf("equals", "exists", "missing", "contains", "matches")))
        assertEquals("string", properties.getAsJsonObject("id").get("type").asString)
        assertEquals("string", properties.getAsJsonObject("path").get("type").asString)
        assertEquals("boolean", properties.getAsJsonObject("exists").get("type").asString)
        assertEquals("boolean", properties.getAsJsonObject("missing").get("type").asString)
        assertEquals("string", properties.getAsJsonObject("contains").get("type").asString)
        assertEquals("string", properties.getAsJsonObject("matches").get("type").asString)
    }

    @Test
    fun `manifest schema documents player existence assertions`() {
        val schema = JsonParser.parseString(Files.readString(Path.of("../schema/manifest/dps-manifest.schema.json"))).asJsonObject
        val properties =
            schema
                .getAsJsonObject("\$defs")
                .getAsJsonObject("playerAssertion")
                .getAsJsonObject("properties")

        assertEquals("boolean", properties.getAsJsonObject("exists").get("type").asString)
    }

    @Test
    fun `manifest schema documents item count range assertions`() {
        val schema = JsonParser.parseString(Files.readString(Path.of("../schema/manifest/dps-manifest.schema.json"))).asJsonObject
        val properties =
            schema
                .getAsJsonObject("\$defs")
                .getAsJsonObject("itemAssertion")
                .getAsJsonObject("properties")

        assertEquals("integer", properties.getAsJsonObject("minCount").get("type").asString)
        assertEquals("integer", properties.getAsJsonObject("maxCount").get("type").asString)
        assertEquals(0, properties.getAsJsonObject("minCount").get("minimum").asInt)
        assertEquals(0, properties.getAsJsonObject("maxCount").get("minimum").asInt)
    }

    @Test
    fun `manifest schema documents block nbt assertions`() {
        val schema = JsonParser.parseString(Files.readString(Path.of("../schema/manifest/dps-manifest.schema.json"))).asJsonObject
        val defs = schema.getAsJsonObject("\$defs")
        val assertionRef =
            defs
                .getAsJsonObject("assertion")
                .getAsJsonObject("properties")
                .getAsJsonObject("block")
        val properties = defs.getAsJsonObject("blockAssertion").getAsJsonObject("properties")

        assertEquals("#/\$defs/blockAssertion", assertionRef.get("\$ref").asString)
        assertEquals("#/\$defs/blockPos", properties.getAsJsonObject("pos").get("\$ref").asString)
        assertEquals("string", properties.getAsJsonObject("id").get("type").asString)
        assertEquals("boolean", properties.getAsJsonObject("exists").get("type").asString)
        assertEquals("#/\$defs/pathExpectation", properties.getAsJsonObject("nbt").get("\$ref").asString)
        val pathProperties = defs.getAsJsonObject("pathExpectation").getAsJsonObject("properties")
        assertEquals("boolean", pathProperties.getAsJsonObject("missing").get("type").asString)
        assertEquals("string", pathProperties.getAsJsonObject("contains").get("type").asString)
        assertEquals("string", pathProperties.getAsJsonObject("matches").get("type").asString)
    }

    @Test
    fun `manifest schema documents scheduled assertions`() {
        val schema = JsonParser.parseString(Files.readString(Path.of("../schema/manifest/dps-manifest.schema.json"))).asJsonObject
        val defs = schema.getAsJsonObject("\$defs")
        val assertion = defs.getAsJsonObject("assertion")
        val assertionRequired =
            assertion.getAsJsonArray("oneOf").map {
                it.asJsonObject
                    .getAsJsonArray("required")
                    .single()
                    .asString
            }
        val assertionRef = assertion.getAsJsonObject("properties").getAsJsonObject("scheduled")
        val properties = defs.getAsJsonObject("scheduledAssertion").getAsJsonObject("properties")

        assertTrue("scheduled" in assertionRequired)
        assertEquals("#/\$defs/scheduledAssertion", assertionRef.get("\$ref").asString)
        assertEquals("string", properties.getAsJsonObject("id").get("type").asString)
        assertEquals("integer", properties.getAsJsonObject("dueTick").get("type").asString)
        assertEquals("boolean", properties.getAsJsonObject("exists").get("type").asString)
        assertEquals("integer", properties.getAsJsonObject("count").get("type").asString)
    }

    @Test
    fun `manifest schema documents gamerule assertions`() {
        val schema = JsonParser.parseString(Files.readString(Path.of("../schema/manifest/dps-manifest.schema.json"))).asJsonObject
        val defs = schema.getAsJsonObject("\$defs")
        val assertion = defs.getAsJsonObject("assertion")
        val assertionRequired =
            assertion.getAsJsonArray("oneOf").map {
                it.asJsonObject
                    .getAsJsonArray("required")
                    .single()
                    .asString
            }
        val assertionRef = assertion.getAsJsonObject("properties").getAsJsonObject("gamerule")
        val properties = defs.getAsJsonObject("gameruleAssertion").getAsJsonObject("properties")

        assertTrue("gamerule" in assertionRequired)
        assertEquals("#/\$defs/gameruleAssertion", assertionRef.get("\$ref").asString)
        assertEquals("string", properties.getAsJsonObject("name").get("type").asString)
        assertEquals("string", properties.getAsJsonObject("value").get("type").asString)
        assertEquals("boolean", properties.getAsJsonObject("exists").get("type").asString)
    }

    @Test
    fun `manifest schema documents random sequence and forced chunk assertions`() {
        val schema = JsonParser.parseString(Files.readString(Path.of("../schema/manifest/dps-manifest.schema.json"))).asJsonObject
        val defs = schema.getAsJsonObject("\$defs")
        val assertion = defs.getAsJsonObject("assertion")
        val assertionRequired =
            assertion.getAsJsonArray("oneOf").map {
                it.asJsonObject
                    .getAsJsonArray("required")
                    .single()
                    .asString
            }
        val assertionProperties = assertion.getAsJsonObject("properties")
        val randomProperties = defs.getAsJsonObject("randomSequenceAssertion").getAsJsonObject("properties")
        val forcedChunkProperties = defs.getAsJsonObject("forcedChunkAssertion").getAsJsonObject("properties")

        assertTrue("randomSequence" in assertionRequired)
        assertTrue("forcedChunk" in assertionRequired)
        assertEquals("#/\$defs/randomSequenceAssertion", assertionProperties.getAsJsonObject("randomSequence").get("\$ref").asString)
        assertEquals("#/\$defs/forcedChunkAssertion", assertionProperties.getAsJsonObject("forcedChunk").get("\$ref").asString)
        assertEquals("string", randomProperties.getAsJsonObject("name").get("type").asString)
        assertEquals("integer", randomProperties.getAsJsonObject("state").get("type").asString)
        assertEquals("integer", forcedChunkProperties.getAsJsonObject("x").get("type").asString)
        assertEquals("integer", forcedChunkProperties.getAsJsonObject("z").get("type").asString)
        assertEquals("boolean", forcedChunkProperties.getAsJsonObject("exists").get("type").asString)
    }

    @Test
    fun `manifest schema documents scoreboard UI assertions`() {
        val schema = JsonParser.parseString(Files.readString(Path.of("../schema/manifest/dps-manifest.schema.json"))).asJsonObject
        val defs = schema.getAsJsonObject("\$defs")
        val assertion = defs.getAsJsonObject("assertion")
        val assertionRequired =
            assertion.getAsJsonArray("oneOf").map {
                it.asJsonObject
                    .getAsJsonArray("required")
                    .single()
                    .asString
            }
        val assertionProperties = assertion.getAsJsonObject("properties")
        val objectiveProperties = defs.getAsJsonObject("scoreboardObjectiveAssertion").getAsJsonObject("properties")
        val displayProperties = defs.getAsJsonObject("scoreboardDisplayAssertion").getAsJsonObject("properties")

        assertTrue("scoreboardObjective" in assertionRequired)
        assertTrue("scoreboardDisplay" in assertionRequired)
        assertEquals(
            "#/\$defs/scoreboardObjectiveAssertion",
            assertionProperties.getAsJsonObject("scoreboardObjective").get("\$ref").asString,
        )
        assertEquals("#/\$defs/scoreboardDisplayAssertion", assertionProperties.getAsJsonObject("scoreboardDisplay").get("\$ref").asString)
        assertEquals("string", objectiveProperties.getAsJsonObject("name").get("type").asString)
        assertEquals("string", objectiveProperties.getAsJsonObject("criteria").get("type").asString)
        assertEquals("string", objectiveProperties.getAsJsonObject("displayName").get("type").asString)
        assertEquals("boolean", objectiveProperties.getAsJsonObject("displayAutoUpdate").get("type").asString)
        assertEquals("string", displayProperties.getAsJsonObject("slot").get("type").asString)
        assertEquals("string", displayProperties.getAsJsonObject("objective").get("type").asString)
    }
}
