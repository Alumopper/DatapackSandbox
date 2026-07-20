package moe.afox.dpsandbox.cli

import com.google.gson.JsonParser
import moe.afox.dpsandbox.core.VersionProfileDocs
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliCatalogCommandTest : RunCommandTestSupport() {
    @Test
    fun `version lists supported datapack formats`() {
        val output =
            captureStdout {
                main(arrayOf("version"))
            }

        assertTrue("1.20.4 java=17 pack_format=26 data=3700" in output, output)
        assertTrue("26.1.2 java=25 pack_format=101.1" in output, output)
        assertTrue("26.2 java=25 pack_format=107.1 data=4903 default" in output, output)
    }

    @Test
    fun `version reports profile diffs`() {
        val output =
            captureStdout {
                main(arrayOf("version", "1.20.4", "26.2"))
            }

        assertTrue("profile diff 1.20.4 -> 26.2" in output, output)
        assertTrue("java: 17 -> 25" in output, output)
        assertTrue("pack_format: 26 -> 107.1" in output, output)
        assertTrue("nbt_schema: 1.20.4:1.20.4 -> 26.2:26.2" in output, output)
        assertTrue("command_roots: added=transfer" in output, output)
    }

    @Test
    fun `version renders markdown docs table`() {
        val output =
            captureStdout {
                main(arrayOf("version", "--docs"))
            }

        assertTrue("| Profile | Java | Data version | Data pack format | NBT schema | Resource directories |" in output, output)
        assertTrue(
            "| `1.20.4` | 17 | 3700 | 26 | `1.20.4:1.20.4` | `functions`, `loot_tables`, `predicates`, `advancements` |" in output,
            output,
        )
        assertTrue("| `26.2` | 25 | 4903 | 107.1 | `26.2:26.2` |" in output, output)
    }

    @Test
    fun `version writes markdown docs table to file`() {
        val reportFile = Files.createTempFile("dps-version-docs", ".md")
        val output =
            captureStdout {
                main(arrayOf("version", "--docs", "--output", reportFile.toString()))
            }
        val report = Files.readString(reportFile)

        assertTrue("version output written: $reportFile" in output, output)
        assertTrue("| Profile | Java | Data version | Data pack format | NBT schema | Resource directories |" in report, report)
        assertTrue("| `26.2` | 25 | 4903 | 107.1 | `26.2:26.2` |" in report, report)
    }

    @Test
    fun `version renders localized markdown docs table`() {
        val output =
            captureStdout {
                main(arrayOf("version", "--docs", "--locale", "zh-CN"))
            }

        assertTrue("| Profile | Java | Data version | Data pack format | NBT schema | 资源目录 |" in output, output)
        assertTrue("`function`、`loot_table`、`predicate`、`advancement`，允许旧别名" in output, output)
    }

    @Test
    fun `version checks markdown docs table in file`() {
        val docsFile = Files.createTempFile("dps-version-docs-check", ".md")
        Files.writeString(
            docsFile,
            """
            # Generated docs

            ${VersionProfileDocs.renderMarkdownTable()}
            """.trimIndent(),
        )

        val output =
            captureStdout {
                main(arrayOf("version", "--docs", "--check", docsFile.toString()))
            }

        assertTrue("version docs up to date: $docsFile" in output, output)
    }

    @Test
    fun `version checks localized markdown docs table in file`() {
        val docsFile = Files.createTempFile("dps-version-docs-zh-check", ".md")
        Files.writeString(
            docsFile,
            """
            # 中文文档

            ${VersionProfileDocs.renderMarkdownTable(locale = "zh-CN")}
            """.trimIndent(),
        )

        val output =
            captureStdout {
                main(arrayOf("version", "--docs", "--locale", "zh-CN", "--check", docsFile.toString()))
            }

        assertTrue("version docs up to date: $docsFile" in output, output)
    }

    @Test
    fun `version docs check fails when table is stale`() {
        val docsFile = Files.createTempFile("dps-version-docs-stale", ".md")
        Files.writeString(docsFile, "# stale docs${System.lineSeparator()}")

        val result = runCliProcess("version", "--docs", "--check", docsFile.toString())

        assertEquals(ExitCodes.INPUT_FORMAT, result.exitCode, result.output)
        assertTrue("version docs are out of date: $docsFile" in result.output, result.output)
    }

    @Test
    fun `version renders profile list json`() {
        val output =
            captureStdout {
                main(arrayOf("version", "--json"))
            }
        val json = JsonParser.parseString(output).asJsonObject
        val latest = json.getAsJsonArray("profiles").last().asJsonObject

        assertEquals("26.2", json.get("default").asString)
        assertEquals("26.2", latest.get("id").asString)
        assertEquals("26.2:26.2", latest.get("nbtSchema").asString)
        assertTrue(latest.getAsJsonArray("commandRoots").map { it.asString }.contains("transfer"))
    }

    @Test
    fun `version renders profile diff json`() {
        val output =
            captureStdout {
                main(arrayOf("version", "--json", "1.20.4", "26.2"))
            }
        val json = JsonParser.parseString(output).asJsonObject

        assertEquals("1.20.4", json.get("from").asString)
        assertEquals("26.2", json.get("to").asString)
        assertEquals("1.20.4:1.20.4", json.getAsJsonObject("nbtSchema").get("from").asString)
        assertEquals("26.2:26.2", json.getAsJsonObject("nbtSchema").get("to").asString)
        assertTrue(
            json
                .getAsJsonObject("commandRoots")
                .getAsJsonArray("added")
                .map { it.asString }
                .contains("transfer"),
        )
    }

    @Test
    fun `version writes profile diff json to file`() {
        val reportFile = Files.createTempFile("dps-version-diff", ".json")
        val output =
            captureStdout {
                main(arrayOf("version", "--json", "--output", reportFile.toString(), "1.20.4", "26.2"))
            }
        val json = JsonParser.parseString(Files.readString(reportFile)).asJsonObject

        assertTrue("version output written: $reportFile" in output, output)
        assertEquals("1.20.4", json.get("from").asString)
        assertEquals("26.2", json.get("to").asString)
        assertEquals("26.2:26.2", json.getAsJsonObject("nbtSchema").get("to").asString)
    }

    @Test
    fun `commands list reports behavior levels`() {
        val output =
            captureStdout {
                main(arrayOf("commands"))
            }

        assertTrue("advancement modeled - grant, revoke, or test advancement progress" in output, output)
        assertTrue("place modeled - place modeled structure, template, jigsaw, and feature resources" in output, output)
        assertTrue("ban observed-noop - record a server ban request" in output, output)
        assertTrue("list modeled - report sandbox players" in output, output)
        assertTrue("locate modeled - report deterministic void-world locate results" in output, output)
        assertTrue("return modeled - stop the current function" in output, output)
    }

    @Test
    fun `commands render markdown docs table`() {
        val output =
            captureStdout {
                main(arrayOf("commands", "--docs"))
            }

        assertTrue("| Command | Behavior | Description |" in output, output)
        assertTrue("| `advancement` | `modeled` | grant, revoke, or test advancement progress |" in output, output)
        assertTrue("| `place` | `modeled` | place modeled structure, template, jigsaw, and feature resources |" in output, output)
    }

    @Test
    fun `commands check markdown docs table in file`() {
        val docsFile = Files.createTempFile("dps-commands-check", ".md")
        val docs =
            captureStdout {
                main(arrayOf("commands", "--docs"))
            }
        Files.writeString(docsFile, docs)

        val output =
            captureStdout {
                main(arrayOf("commands", "--check", docsFile.toString()))
            }

        assertTrue("commands docs cover catalog: $docsFile" in output, output)
    }

    @Test
    fun `commands check fails when docs are stale`() {
        val docsFile = Files.createTempFile("dps-commands-stale", ".md")
        Files.writeString(
            docsFile,
            "| Command | Behavior |${System.lineSeparator()}|---|---|${System.lineSeparator()}| `place` | `observed-noop` |",
        )

        val result = runCliProcess("commands", "--check", docsFile.toString())

        assertEquals(ExitCodes.INPUT_FORMAT, result.exitCode, result.output)
        assertTrue("commands docs are out of date: $docsFile" in result.output, result.output)
        assertTrue("advancement (modeled)" in result.output, result.output)
    }

    @Test
    fun `commands write markdown docs table to file`() {
        val reportFile = Files.createTempFile("dps-commands-docs", ".md")
        val output =
            captureStdout {
                main(arrayOf("commands", "--docs", "--output", reportFile.toString()))
            }
        val report = Files.readString(reportFile)

        assertTrue("commands output written: $reportFile" in output, output)
        assertTrue("| Command | Behavior | Description |" in report, report)
        assertTrue("| `place` | `modeled` | place modeled structure, template, jigsaw, and feature resources |" in report, report)
    }

    @Test
    fun `commands render catalog json`() {
        val output =
            captureStdout {
                main(arrayOf("commands", "--json", "--version", "26.2"))
            }
        val json = JsonParser.parseString(output).asJsonObject
        val commands = json.getAsJsonArray("commands").map { it.asJsonObject }.associateBy { it.get("command").asString }

        assertEquals("26.2", json.get("version").asString)
        assertEquals("modeled", commands.getValue("advancement").get("behavior").asString)
        assertEquals("modeled", commands.getValue("place").get("behavior").asString)
        assertEquals("observed-noop", commands.getValue("ban").get("behavior").asString)
        assertEquals("modeled", commands.getValue("list").get("behavior").asString)
        assertEquals("modeled", commands.getValue("locate").get("behavior").asString)
        assertEquals("modeled", commands.getValue("return").get("behavior").asString)
    }

    @Test
    fun `commands write catalog json to file`() {
        val reportFile = Files.createTempFile("dps-commands", ".json")
        val output =
            captureStdout {
                main(arrayOf("commands", "--json", "--output", reportFile.toString(), "--version", "26.2"))
            }
        val json = JsonParser.parseString(Files.readString(reportFile)).asJsonObject
        val commands = json.getAsJsonArray("commands").map { it.asJsonObject }.associateBy { it.get("command").asString }

        assertTrue("commands output written: $reportFile" in output, output)
        assertEquals("26.2", json.get("version").asString)
        assertEquals("modeled", commands.getValue("place").get("behavior").asString)
        assertEquals("observed-noop", commands.getValue("ban").get("behavior").asString)
    }

    @Test
    fun `diff reports field level json changes`() {
        val before = Files.createTempFile("dps-diff-before", ".json")
        val after = Files.createTempFile("dps-diff-after", ".json")
        Files.writeString(before, """{"scores":{"runs":{"#one":1}}}""")
        Files.writeString(after, """{"scores":{"runs":{"#one":3,"#two":2}}}""")

        val output =
            captureStdout {
                main(arrayOf("diff", before.toString(), after.toString()))
            }

        assertTrue("~ /scores/runs/#one: 1 -> 3" in output, output)
        assertTrue("+ /scores/runs/#two = 2" in output, output)
    }

    @Test
    fun `diff writes json report to file`() {
        val before = Files.createTempFile("dps-diff-json-before", ".json")
        val after = Files.createTempFile("dps-diff-json-after", ".json")
        val reportFile = Files.createTempFile("dps-diff-report", ".json")
        Files.writeString(before, """{"storage":{"demo:env":{"ready":false}}}""")
        Files.writeString(after, """{"storage":{"demo:env":{"ready":true}}}""")

        val output =
            captureStdout {
                main(arrayOf("diff", "--json", "--output", reportFile.toString(), before.toString(), after.toString()))
            }
        val report = JsonParser.parseString(Files.readString(reportFile)).asJsonArray
        val entry = report.single().asJsonObject

        assertTrue("diff output written: $reportFile" in output, output)
        assertEquals("/storage/demo:env/ready", entry.get("path").asString)
        assertEquals("changed", entry.get("kind").asString)
        assertEquals(false, entry.get("before").asBoolean)
        assertEquals(true, entry.get("after").asBoolean)
    }

    @Test
    fun `diff check exits when json differs`() {
        val before = Files.createTempFile("dps-diff-check-before", ".json")
        val after = Files.createTempFile("dps-diff-check-after", ".json")
        Files.writeString(before, """{"scores":{"runs":{"#one":1}}}""")
        Files.writeString(after, """{"scores":{"runs":{"#one":2}}}""")

        val result = runCliProcess("diff", "--check", before.toString(), after.toString())

        assertEquals(ExitCodes.ASSERTION_FAILED, result.exitCode, result.output)
        assertTrue("~ /scores/runs/#one: 1 -> 2" in result.output, result.output)
    }

    @Test
    fun `diff can compare snapshots extracted from reports`() {
        val before = Files.createTempFile("dps-diff-report-before", ".json")
        val after = Files.createTempFile("dps-diff-report-after", ".json")
        Files.writeString(before, """{"passed":true,"snapshot":{"scores":{"runs":{"#one":1}}}}""")
        Files.writeString(after, """{"passed":true,"snapshot":{"scores":{"runs":{"#one":1,"#two":2}}}}""")

        val output =
            captureStdout {
                main(arrayOf("diff", "--snapshot", before.toString(), after.toString()))
            }

        assertTrue("+ /scores/runs/#two = 2" in output, output)
        assertTrue("passed" !in output, output)
    }

    @Test
    fun `diff exports manifest replay scripts for external differential runs`() {
        val dir = Files.createTempDirectory("dps-diff-script")
        val pack = dir.resolve("pack")
        Files.createDirectories(pack)
        Files.writeString(pack.resolve("pack.mcmeta"), """{"pack":{"pack_format":107.1,"description":"external diff script test"}}""")
        val packPath =
            pack
                .toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "\\\\")
        val generated = dir.resolve("generated.mcfunction")
        Files.writeString(
            generated,
            """
            scoreboard players add #generator runs 2
            say generated file
            """.trimIndent(),
        )
        val manifest = dir.resolve("external.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.2",
              "packs": ["$packPath"],
              "world": {
                "scores": [
                  { "target": "#fixture", "objective": "runs", "value": 1 }
                ]
              },
              "steps": [
                { "load": true },
                { "ticks": 2 },
                {
                  "commands": [
                    "/scoreboard objectives add runs dummy",
                    "scoreboard players set #generator runs 1"
                  ],
                  "source": "<generator:setup>"
                },
                {
                  "functionText": "say inline output\n# generator comment",
                  "source": "<generator:inline>"
                },
                { "mcfunction": "generated.mcfunction" },
                { "event": { "player": "Steve", "type": "key_input", "key": "key.jump", "action": "press" } },
                { "player": { "name": "Alex" } },
                { "block": { "pos": [1, 64, 2], "id": "minecraft:stone" } },
                { "snapshot": true },
                { "trace": true },
                { "reset": true }
              ]
            }
            """.trimIndent(),
        )
        val script = Files.createTempFile("dps-external-replay", ".mcfunction")

        val output =
            captureStdout {
                main(arrayOf("diff", "--script", "--output", script.toString(), manifest.toString()))
            }

        assertTrue("diff script written: $script" in output, output)
        val content = Files.readString(script)
        assertTrue("# manifest: ${manifest.toAbsolutePath().normalize()}" in content, content)
        assertTrue("function #minecraft:load" in content, content)
        assertEquals(2, Regex("function #minecraft:tick").findAll(content).count(), content)
        assertTrue("# source: <generator:setup>" in content, content)
        assertTrue("scoreboard objectives add runs dummy" in content, content)
        assertTrue("scoreboard players set #generator runs 1" in content, content)
        assertTrue("# source: <generator:inline>" in content, content)
        assertTrue("say inline output" in content, content)
        assertTrue("# generator comment" in content, content)
        assertTrue("# source: ${generated.toAbsolutePath().normalize()}" in content, content)
        assertTrue("say generated file" in content, content)
        assertTrue("# sandbox event step:" in content, content)
        assertTrue("key.jump" in content, content)
        assertTrue("# sandbox player fixture step:" in content, content)
        assertTrue("# sandbox block fixture step:" in content, content)
        assertTrue("# sandbox snapshot artifact step" in content, content)
        assertTrue("# sandbox trace artifact step" in content, content)
        assertTrue("# sandbox reset-world step; recreate a fresh external world before continuing" in content, content)
    }
}
