package moe.afox.dpsandbox.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VersionProfileTest {
    @Test
    fun `version profiles include the minimum supported 1_20_4 datapack version`() {
        val profile = VersionProfiles.get("1.20.4")

        assertEquals(17, profile.javaMajor)
        assertEquals(3700, profile.dataVersion)
        assertEquals("26", profile.dataPackFormat.toString())
        assertEquals(listOf("functions"), profile.resourceDirectories.functions)
    }

    @Test
    fun `version profiles include the latest 26_2 datapack version as default`() {
        val profile = VersionProfiles.get("26.2")

        assertEquals(profile, VersionProfiles.default)
        assertEquals(25, profile.javaMajor)
        assertEquals(4903, profile.dataVersion)
        assertEquals("107.1", profile.dataPackFormat.toString())
        assertEquals(listOf("function", "functions"), profile.resourceDirectories.functions)
        assertTrue(profile.commands.hasRoot("transfer"))
    }

    @Test
    fun `version profiles cover every release from 1_20_4 through 26_2`() {
        assertEquals(
            listOf(
                "1.20.4",
                "1.20.5",
                "1.20.6",
                "1.21",
                "1.21.1",
                "1.21.2",
                "1.21.3",
                "1.21.4",
                "1.21.5",
                "1.21.6",
                "1.21.7",
                "1.21.8",
                "1.21.9",
                "1.21.10",
                "1.21.11",
                "26.1",
                "26.1.1",
                "26.1.2",
                "26.2",
            ),
            VersionProfiles.all.map { it.id },
        )
        assertEquals("41", VersionProfiles.get("1.20.5").dataPackFormat.toString())
        assertEquals("94.1", VersionProfiles.get("1.21.11").dataPackFormat.toString())
        assertEquals("101.1", VersionProfiles.get("26.1").dataPackFormat.toString())
    }

    @Test
    fun `nbt schemas are exposed through version profiles`() {
        assertEquals("1.20.4:1.20.4", NbtSchemas.schemaSummary(VersionProfiles.get("1.20.4")))
        assertEquals("1.21.5:1.21.5", NbtSchemas.schemaSummary(VersionProfiles.get("1.21.5")))
        assertEquals("26.1.2:26.1.2", NbtSchemas.schemaSummary(VersionProfiles.get("26.1.2")))
        assertEquals("26.2:26.2", NbtSchemas.schemaSummary(VersionProfiles.get("26.2")))
    }

    @Test
    fun `loads 1_20_4 datapacks from legacy plural directories`() {
        val pack = writePack("1.20.4", "26", ResourceDirectoryProfile.legacyPlural)

        val sandbox = createSandbox("1.20.4", listOf(pack))
        sandbox.runLoad()

        assertEquals(4, sandbox.world.getScore("#legacy", "runs"))
        assertTrue(sandbox.snapshotString().contains("\"version\": \"1.20.4\""))
    }

    @Test
    fun `loads datapacks that declare a supported pack format range`() {
        val pack =
            writePackWithPackFields(
                name = "1.21.9-range",
                packFields =
                    """
                    "min_format": 88,
                    "max_format": 107.1
                    """.trimIndent(),
                directories = ResourceDirectoryProfile.currentWithLegacyAliases,
            )

        val sandbox = createSandbox("1.21.9", listOf(pack))
        sandbox.runLoad()

        assertEquals(4, sandbox.world.getScore("#legacy", "runs"))
    }

    @Test
    fun `loads datapacks that declare tuple pack format values`() {
        val exactPack = writePack("26.2-tuple-exact", "[107, 1]", ResourceDirectoryProfile.currentWithLegacyAliases)
        val exactSandbox = createSandbox("26.2", listOf(exactPack))
        exactSandbox.runLoad()

        assertEquals(4, exactSandbox.world.getScore("#legacy", "runs"))
        assertTrue(exactSandbox.datapack.warnings.isEmpty())

        val rangePack =
            writePackWithPackFields(
                name = "26.2-tuple-range",
                packFields =
                    """
                    "min_format": [94],
                    "max_format": [107, 1]
                    """.trimIndent(),
                directories = ResourceDirectoryProfile.currentWithLegacyAliases,
            )

        val rangeSandbox = createSandbox("26.2", listOf(rangePack))
        rangeSandbox.runLoad()

        assertEquals(4, rangeSandbox.world.getScore("#legacy", "runs"))
        assertTrue(rangeSandbox.datapack.warnings.isEmpty())
    }

    @Test
    fun `1_20_4 command execution uses profile aware block nbt schema`() {
        val pack = writePack("1.20.4-nbt", "26", ResourceDirectoryProfile.legacyPlural)
        val sandbox = createSandbox("1.20.4", listOf(pack))

        sandbox.executeCommand("""setblock 0 64 0 minecraft:chest{Items:[{Slot:0b,id:"minecraft:apple",Count:1b}]}""")
        sandbox.executeCommand("""data modify block 0 64 0 Items append value {Slot:1b,id:"minecraft:stone",Count:2b}""")

        val nbt = sandbox.world.requireBlock(BlockPos(0, 64, 0)).fullNbt(BlockPos(0, 64, 0), sandbox.profile)
        assertEquals(2, nbt.getAsJsonArray("Items").size())
        assertEquals("1.20.4:1.20.4", NbtSchemas.schemaSummary(sandbox.profile))
    }

    @Test
    fun `unsupported command roots are scoped to the active version profile`() {
        val legacy = createSandbox("1.20.4", listOf(writePack("1.20.4-commands", "26", ResourceDirectoryProfile.legacyPlural)))
        val legacyError =
            assertFailsWith<SandboxException> {
                legacy.executeCommand("transfer Steve example.org")
            }

        assertEquals(DiagnosticCode.INPUT_FORMAT, legacyError.code)
        assertTrue(legacyError.message.contains("Unknown command 'transfer'"), legacyError.message)

        val latest = createSandbox("26.2", listOf(writePack("26.2-commands", "107.1", ResourceDirectoryProfile.currentWithLegacyAliases)))
        latest.executeCommand("transfer Steve example.org")

        val output = latest.world.outputs.single()
        val payload = output.payload?.asJsonObject ?: error("missing transfer payload")
        assertEquals("transfer", output.command)
        assertEquals("debug", output.channel)
        assertEquals(listOf("Steve"), output.targets)
        assertEquals("example.org:25565", output.text)
        assertEquals("example.org", payload.get("host").asString)
        assertEquals(25565, payload.get("port").asInt)
        assertEquals("target-first", payload.get("syntax").asString)
        assertEquals(true, payload.get("noOp").asBoolean)

        val portError =
            assertFailsWith<SandboxException> {
                latest.executeCommand("transfer example.org 70000 Steve")
            }
        assertEquals(DiagnosticCode.INPUT_FORMAT, portError.code)
        assertTrue(portError.message.contains("transfer port"), portError.message)
    }

    @Test
    fun `warns for datapacks with a pack format from another version profile`() {
        val pack = writePack("wrong", "101.1", ResourceDirectoryProfile.legacyPlural)

        val sandbox = createSandbox("1.20.4", listOf(pack))
        sandbox.runLoad()

        assertEquals(4, sandbox.world.getScore("#legacy", "runs"))
        assertEquals(1, sandbox.datapack.warnings.size)
        val warning = sandbox.world.outputs.single()
        assertEquals("warning", warning.channel)
        assertTrue(warning.text.contains("expected 26"), warning.text)
        assertEquals(
            DiagnosticCode.VERSION_MISMATCH.name,
            warning.payload
                ?.asJsonObject
                ?.get("code")
                ?.asString,
        )
    }

    private fun writePack(
        name: String,
        packFormat: String,
        directories: ResourceDirectoryProfile,
    ): Path =
        writePackWithPackFields(
            name = name,
            packFields = """"pack_format": $packFormat""",
            directories = directories,
        )

    private fun writePackWithPackFields(
        name: String,
        packFields: String,
        directories: ResourceDirectoryProfile,
    ): Path {
        val root = Files.createTempDirectory("dps-$name-pack")
        Files.writeString(
            root.resolve("pack.mcmeta"),
            """
            {
              "pack": {
                $packFields,
                "description": "temporary $name pack"
              }
            }
            """.trimIndent(),
        )

        val functionRoot = root.resolve("data").resolve("demo").resolve(directories.functions.first())
        Files.createDirectories(functionRoot)
        Files.writeString(
            functionRoot.resolve("load.mcfunction"),
            """
            scoreboard objectives add runs dummy
            scoreboard players set #legacy runs 4
            """.trimIndent(),
        )

        val tagRoot =
            root
                .resolve("data")
                .resolve("minecraft")
                .resolve("tags")
                .resolve(directories.functionTags.first())
        Files.createDirectories(tagRoot)
        Files.writeString(
            tagRoot.resolve("load.json"),
            """
            {
              "values": ["demo:load"]
            }
            """.trimIndent(),
        )
        return root
    }
}
