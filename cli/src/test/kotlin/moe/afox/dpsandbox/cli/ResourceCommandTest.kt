package moe.afox.dpsandbox.cli

import com.google.gson.JsonParser
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResourceCommandTest : RunCommandTestSupport() {
    @Test
    fun `benchmark reports built in scenarios`() {
        val output =
            captureStdout {
                main(arrayOf("benchmark", "--version", "26.2", "--scale", "4"))
            }

        assertTrue("benchmark version=26.2 scale=4" in output, output)
        assertTrue("scoreboard elapsedMs=" in output, output)
        assertTrue("storage elapsedMs=" in output, output)
        assertTrue("function-chain elapsedMs=" in output, output)
        assertTrue("manifest-batch elapsedMs=" in output, output)
    }

    @Test
    fun `benchmark writes json report to file`() {
        val reportFile = Files.createTempFile("dps-benchmark", ".json")
        val output =
            captureStdout {
                main(arrayOf("benchmark", "--version", "26.2", "--scale", "3", "--json", "--output", reportFile.toString()))
            }
        val json = JsonParser.parseString(Files.readString(reportFile)).asJsonObject
        val scenarios = json.getAsJsonArray("scenarios").map { it.asJsonObject }.associateBy { it.get("name").asString }

        assertTrue("benchmark output written: $reportFile" in output, output)
        assertEquals("26.2", json.get("version").asString)
        assertEquals(3, json.get("scale").asInt)
        assertEquals(3, scenarios.getValue("scoreboard").get("scores").asInt)
        assertEquals(3, scenarios.getValue("storage").get("entries").asInt)
        assertEquals(3, scenarios.getValue("function-chain").get("depth").asInt)
        assertEquals(1, scenarios.getValue("manifest-batch").get("assertions").asInt)
    }

    @Test
    fun `resources list reports behavior levels`() {
        val output =
            captureStdout {
                main(arrayOf("resources"))
            }

        assertTrue("function modeled - mcfunction execution" in output, output)
        assertTrue("banner_pattern modeled - item component registry JSON metadata exposed by item command outputs" in output, output)
        assertTrue("painting_variant modeled - entity variant JSON metadata exposed by the summon command" in output, output)
        assertTrue("chat_type modeled - chat type JSON metadata exposed by modeled chat commands" in output, output)
        assertTrue("damage_type modeled - damage type JSON metadata exposed by the damage command" in output, output)
        assertTrue("dimension modeled - dimension JSON metadata exposed by dimension-aware command outputs" in output, output)
        assertTrue("dimension_type modeled - dimension type JSON metadata exposed through dimension resources" in output, output)
        assertTrue("enchantment modeled - enchantment JSON metadata exposed by the enchant command" in output, output)
        assertTrue("equipment_asset modeled - equipment asset JSON metadata exposed by item command outputs" in output, output)
        assertTrue("instrument modeled - instrument JSON metadata exposed by item command outputs" in output, output)
        assertTrue("jukebox_song modeled - jukebox song JSON metadata exposed by item command outputs" in output, output)
        assertTrue("trim_material modeled - armor trim material JSON metadata exposed by item command outputs" in output, output)
        assertTrue("trim_pattern modeled - armor trim pattern JSON metadata exposed by item command outputs" in output, output)
        assertTrue("wolf_sound_variant modeled - wolf sound variant JSON metadata exposed by the summon command" in output, output)
        assertTrue("wolf_variant modeled - entity variant JSON metadata exposed by the summon command" in output, output)
        assertTrue("tag/<registry> observed-noop - general tags" in output, output)
        assertTrue(
            "worldgen/placed_feature modeled - placed feature JSON resolving configured simple_block/block_column/disk/vegetation_patch/tree/basalt_columns/delta_feature/lake/spring_feature/block_pile/glowstone_blob/forest_rock/netherrack_replace_blobs/chorus_plant/replace_single_block/replace_blob/selector/random_patch/flower/ore" in
                output,
            output,
        )
        assertTrue(
            "worldgen/processor_list modeled - block_ignore, protected_blocks, jigsaw_replacement, capped, nop, and rule processors with block/tag predicates" in
                output,
            output,
        )
        assertTrue("worldgen/structure modeled - sandbox structure JSON and binary structure NBT palette blocks/entities" in output, output)
        assertTrue(
            "worldgen/template_pool modeled - single/legacy/list/feature pool elements, fallback pools, and deterministic connector expansion" in
                output,
            output,
        )
    }

    @Test
    fun `resources render markdown docs table`() {
        val output =
            captureStdout {
                main(arrayOf("resources", "--docs"))
            }

        assertTrue("| Resource | Behavior | Runtime/debug surface |" in output, output)
        assertTrue(
            "| `banner_pattern` | `modeled` | item component registry JSON metadata exposed by item command outputs |" in output,
            output,
        )
        assertTrue("| `cat_variant` | `modeled` | entity variant JSON metadata exposed by the summon command |" in output, output)
        assertTrue("| `chat_type` | `modeled` | chat type JSON metadata exposed by modeled chat commands |" in output, output)
        assertTrue("| `damage_type` | `modeled` | damage type JSON metadata exposed by the damage command |" in output, output)
        assertTrue("| `dimension` | `modeled` | dimension JSON metadata exposed by dimension-aware command outputs |" in output, output)
        assertTrue("| `dimension_type` | `modeled` | dimension type JSON metadata exposed through dimension resources |" in output, output)
        assertTrue("| `enchantment` | `modeled` | enchantment JSON metadata exposed by the enchant command |" in output, output)
        assertTrue("| `equipment_asset` | `modeled` | equipment asset JSON metadata exposed by item command outputs |" in output, output)
        assertTrue("| `instrument` | `modeled` | instrument JSON metadata exposed by item command outputs |" in output, output)
        assertTrue("| `jukebox_song` | `modeled` | jukebox song JSON metadata exposed by item command outputs |" in output, output)
        assertTrue("| `painting_variant` | `modeled` | entity variant JSON metadata exposed by the summon command |" in output, output)
        assertTrue("| `trim_material` | `modeled` | armor trim material JSON metadata exposed by item command outputs |" in output, output)
        assertTrue("| `trim_pattern` | `modeled` | armor trim pattern JSON metadata exposed by item command outputs |" in output, output)
        assertTrue(
            "| `wolf_sound_variant` | `modeled` | wolf sound variant JSON metadata exposed by the summon command |" in output,
            output,
        )
        assertTrue("| `function` | `modeled` | mcfunction execution" in output, output)
        assertTrue(
            "| `worldgen/configured_feature` | `modeled` | simple_block, block_column, disk, vegetation_patch, tree, basalt_columns, delta_feature, lake, spring_feature, block_pile, glowstone_blob, forest_rock, netherrack_replace_blobs, chorus_plant, replace_single_block, replace_blob, selector, random_patch, flower, and ore feature JSON consumed by place feature |" in
                output,
            output,
        )
        assertTrue(
            "| `worldgen/processor_list` | `modeled` | block_ignore, protected_blocks, jigsaw_replacement, capped, nop, and rule processors with block/tag predicates consumed by sandbox structure placement |" in
                output,
            output,
        )
        assertTrue(
            "| `worldgen/structure` | `modeled` | sandbox structure JSON and binary structure NBT palette blocks/entities" in output,
            output,
        )
        assertTrue(
            "| `worldgen/template_pool` | `modeled` | single/legacy/list/feature pool elements, fallback pools, and deterministic connector expansion consumed by sandbox place jigsaw |" in
                output,
            output,
        )
    }

    @Test
    fun `resources render localized markdown docs table`() {
        val output =
            captureStdout {
                main(arrayOf("resources", "--docs", "--locale", "zh-CN"))
            }

        assertTrue("| 资源 | 行为等级 | 运行时 / debug 表面 |" in output, output)
        assertTrue("| `banner_pattern` | `modeled` | item 输出会暴露 banner pattern JSON 元数据。" in output, output)
        assertTrue("| `cat_variant` | `modeled` | summon 命令会暴露实体 variant JSON 元数据。" in output, output)
        assertTrue("| `chat_type` | `modeled` | 聊天命令会暴露 chat type JSON 元数据。" in output, output)
        assertTrue("| `damage_type` | `modeled` | damage 命令会暴露 damage type JSON 元数据。" in output, output)
        assertTrue("| `dimension` | `modeled` | 维度感知命令输出会暴露 dimension JSON 元数据。" in output, output)
        assertTrue("| `dimension_type` | `modeled` | dimension 资源会暴露关联的 dimension type JSON 元数据。" in output, output)
        assertTrue("| `enchantment` | `modeled` | enchant 命令会暴露 enchantment JSON 元数据。" in output, output)
        assertTrue("| `equipment_asset` | `modeled` | item 输出会暴露 equipment asset JSON 元数据。" in output, output)
        assertTrue("| `instrument` | `modeled` | item 输出会暴露 instrument JSON 元数据。" in output, output)
        assertTrue("| `jukebox_song` | `modeled` | item 输出会暴露 jukebox song JSON 元数据。" in output, output)
        assertTrue("| `painting_variant` | `modeled` | summon 命令会暴露实体 variant JSON 元数据。" in output, output)
        assertTrue("| `trim_material` | `modeled` | item 输出会暴露 armor trim material JSON 元数据。" in output, output)
        assertTrue("| `trim_pattern` | `modeled` | item 输出会暴露 armor trim pattern JSON 元数据。" in output, output)
        assertTrue("| `wolf_sound_variant` | `modeled` | summon wolf 会暴露 wolf sound variant JSON 元数据。" in output, output)
        assertTrue("| `function` | `modeled` | mcfunction 执行、trace source location 和缺失引用检查。" in output, output)
        assertTrue(
            "| `worldgen/placed_feature` | `modeled` | placed feature 会解析 configured simple_block/block_column/disk/vegetation_patch/tree/basalt_columns/delta_feature/lake/spring_feature/block_pile/glowstone_blob/forest_rock/netherrack_replace_blobs/chorus_plant/replace_single_block/replace_blob/selector/random_patch/flower/ore 资源，供 place feature 使用。" in
                output,
            output,
        )
        assertTrue(
            "| `worldgen/processor_list` | `modeled` | block_ignore、protected_blocks、jigsaw_replacement、capped、nop 和带 block/tag 谓词的 rule processor 可被沙盒结构放置消费。" in
                output,
            output,
        )
        assertTrue(
            "| `worldgen/structure` | `modeled` | 沙盒结构 JSON 的 blocks/entities 与 palette-style blocks 可被 place structure/template 展开。" in
                output,
            output,
        )
        assertTrue(
            "| `worldgen/template_pool` | `modeled` | single/legacy/list/feature pool element、fallback pool 和确定性 jigsaw connector 可被 place jigsaw 展开。" in
                output,
            output,
        )
    }

    @Test
    fun `resources write markdown docs table to file`() {
        val reportFile = Files.createTempFile("dps-resources-docs", ".md")
        val output =
            captureStdout {
                main(arrayOf("resources", "--docs", "--output", reportFile.toString()))
            }
        val report = Files.readString(reportFile)

        assertTrue("resources output written: $reportFile" in output, output)
        assertTrue("| Resource | Behavior | Runtime/debug surface |" in report, report)
        assertTrue("| `damage_type` | `modeled` | damage type JSON metadata exposed by the damage command |" in report, report)
    }

    @Test
    fun `resources check markdown docs table in file`() {
        val docsFile = Files.createTempFile("dps-resources-check", ".md")
        val docs =
            captureStdout {
                main(arrayOf("resources", "--docs"))
            }
        Files.writeString(docsFile, docs)

        val output =
            captureStdout {
                main(arrayOf("resources", "--check", docsFile.toString()))
            }

        assertTrue("resources docs cover catalog: $docsFile" in output, output)
    }

    @Test
    fun `resources check localized markdown docs table in file`() {
        val docsFile = Files.createTempFile("dps-resources-zh-check", ".md")
        val docs =
            captureStdout {
                main(arrayOf("resources", "--docs", "--locale", "zh-CN"))
            }
        Files.writeString(docsFile, docs)

        val output =
            captureStdout {
                main(arrayOf("resources", "--check", docsFile.toString(), "--locale", "zh-CN"))
            }

        assertTrue("resources docs cover catalog: $docsFile" in output, output)
    }

    @Test
    fun `resources check fails when docs are stale`() {
        val docsFile = Files.createTempFile("dps-resources-stale", ".md")
        Files.writeString(
            docsFile,
            "| Resource | Behavior |${System.lineSeparator()}|---|---|${System.lineSeparator()}| `function` | `modeled` |",
        )

        val result = runCliProcess("resources", "--check", docsFile.toString())

        assertEquals(ExitCodes.INPUT_FORMAT, result.exitCode, result.output)
        assertTrue("resources docs are out of date: $docsFile" in result.output, result.output)
        assertTrue("loot_table (modeled)" in result.output, result.output)
    }

    @Test
    fun `resources render catalog json`() {
        val output =
            captureStdout {
                main(arrayOf("resources", "--json"))
            }
        val json = JsonParser.parseString(output).asJsonObject
        val resources = json.getAsJsonArray("resources").map { it.asJsonObject }.associateBy { it.get("type").asString }

        assertEquals("modeled", resources.getValue("function").get("behavior").asString)
        assertEquals("modeled", resources.getValue("banner_pattern").get("behavior").asString)
        assertEquals("modeled", resources.getValue("cat_variant").get("behavior").asString)
        assertEquals("modeled", resources.getValue("chat_type").get("behavior").asString)
        assertEquals("modeled", resources.getValue("damage_type").get("behavior").asString)
        assertEquals("modeled", resources.getValue("dimension").get("behavior").asString)
        assertEquals("modeled", resources.getValue("dimension_type").get("behavior").asString)
        assertEquals("modeled", resources.getValue("enchantment").get("behavior").asString)
        assertEquals("modeled", resources.getValue("equipment_asset").get("behavior").asString)
        assertEquals("modeled", resources.getValue("instrument").get("behavior").asString)
        assertEquals("modeled", resources.getValue("jukebox_song").get("behavior").asString)
        assertEquals("modeled", resources.getValue("painting_variant").get("behavior").asString)
        assertEquals("modeled", resources.getValue("trim_material").get("behavior").asString)
        assertEquals("modeled", resources.getValue("trim_pattern").get("behavior").asString)
        assertEquals("modeled", resources.getValue("wolf_sound_variant").get("behavior").asString)
        assertEquals("modeled", resources.getValue("wolf_variant").get("behavior").asString)
        assertEquals("observed-noop", resources.getValue("tag/<registry>").get("behavior").asString)
        assertEquals("modeled", resources.getValue("worldgen/configured_feature").get("behavior").asString)
        assertEquals("modeled", resources.getValue("worldgen/placed_feature").get("behavior").asString)
        assertEquals("modeled", resources.getValue("worldgen/processor_list").get("behavior").asString)
        assertEquals("modeled", resources.getValue("worldgen/structure").get("behavior").asString)
        assertEquals("modeled", resources.getValue("worldgen/template_pool").get("behavior").asString)
    }

    @Test
    fun `resources lists profile registry entries`() {
        val output =
            captureStdout {
                main(arrayOf("resources", "--version", "26.2", "--registry", "--registry-group", "damage_types"))
            }

        assertTrue("registry version=26.2 groups=12 selected=1 source=profile:26.2" in output, output)
        assertTrue("registry damage_types count=5 source=profile:26.2" in output, output)
        assertTrue("registry damage_types minecraft:generic source=profile:26.2" in output, output)
    }

    @Test
    fun `resources renders profile registry json`() {
        val output =
            captureStdout {
                main(arrayOf("resources", "--version", "26.2", "--registry", "--registry-group", "loot_conditions", "--json"))
            }
        val json = JsonParser.parseString(output).asJsonObject
        val registry = json.getAsJsonArray("registries").single().asJsonObject
        val entries = registry.getAsJsonArray("entries").map { it.asString }

        assertEquals("26.2", json.get("version").asString)
        assertEquals("profile:26.2", json.get("source").asString)
        assertEquals("loot_conditions", json.getAsJsonObject("filters").get("registryGroup").asString)
        assertEquals("loot_conditions", registry.get("group").asString)
        assertTrue("minecraft:random_chance" in entries, entries.toString())
    }

    @Test
    fun `resources lists loaded pack index with filters`() {
        val pack = writeDependencyPack(Files.createTempDirectory("dps-resources-pack"), "main", "say indexed")

        val output =
            captureStdout {
                main(
                    arrayOf(
                        "resources",
                        "--version",
                        "26.2",
                        "--pack",
                        pack.toString(),
                        "--type",
                        "function",
                        "--namespace",
                        "demo",
                    ),
                )
            }

        assertTrue("resources version=26.2 packs=1 resourceIndex=1 active=1 overridden=0 selected=1" in output, output)
        assertTrue("resource function demo:main modeled active" in output, output)
        assertTrue("file=" in output, output)
    }

    @Test
    fun `resources writes loaded pack index json`() {
        val pack = writeDependencyPack(Files.createTempDirectory("dps-resources-json-pack"), "main", "say indexed")
        val reportFile = Files.createTempFile("dps-resources-loaded", ".json")
        val output =
            captureStdout {
                main(
                    arrayOf(
                        "resources",
                        "--version",
                        "26.2",
                        "--pack",
                        pack.toString(),
                        "--json",
                        "--output",
                        reportFile.toString(),
                    ),
                )
            }
        val json = JsonParser.parseString(Files.readString(reportFile)).asJsonObject
        val resources = json.getAsJsonArray("resources").map { it.asJsonObject }

        assertTrue("resources output written: $reportFile" in output, output)
        assertEquals("26.2", json.get("version").asString)
        assertEquals(1, json.getAsJsonObject("summary").get("resourceIndex").asInt)
        assertEquals("function", resources.single().get("type").asString)
        assertEquals("demo:main", resources.single().get("id").asString)
        assertEquals(true, resources.single().get("active").asBoolean)
    }

    @Test
    fun `resources can filter overridden pack entries`() {
        val first = writeDependencyPack(Files.createTempDirectory("dps-resources-first"), "main", "say first")
        val second = writeDependencyPack(Files.createTempDirectory("dps-resources-second"), "main", "say second")

        val output =
            captureStdout {
                main(
                    arrayOf(
                        "resources",
                        "--version",
                        "26.2",
                        "--pack",
                        first.toString(),
                        "--pack",
                        second.toString(),
                        "--overridden-only",
                    ),
                )
            }

        assertTrue("resources version=26.2 packs=2 resourceIndex=2 active=1 overridden=1 selected=1" in output, output)
        assertTrue("resource function demo:main modeled overridden" in output, output)
        assertTrue("overriddenBy=" in output, output)
    }

    @Test
    fun `resources can filter loaded pack index by id source pack and order`() {
        val first = writeDependencyPack(Files.createTempDirectory("dps-resources-source-first"), "main", "say first")
        val second = writeDependencyPack(Files.createTempDirectory("dps-resources-source-second"), "main", "say second")

        val output =
            captureStdout {
                main(
                    arrayOf(
                        "resources",
                        "--version",
                        "26.2",
                        "--pack",
                        first.toString(),
                        "--pack",
                        second.toString(),
                        "--id",
                        "demo:main",
                        "--source-pack",
                        second.toString(),
                        "--order-min",
                        "1",
                        "--order-max",
                        "1",
                    ),
                )
            }

        assertTrue("resources version=26.2 packs=2 resourceIndex=2 active=1 overridden=1 selected=1" in output, output)
        assertTrue("resource function demo:main modeled active pack=${second.toAbsolutePath().normalize()}" in output, output)
        assertTrue("overrides=" in output, output)
    }

    @Test
    fun `run targets interaction entities from player event shorthand`() {
        val eventTrace = Files.createTempFile("dps-interaction-event", ".jsonl")

        val output =
            captureStdout {
                main(
                    arrayOf(
                        "run",
                        "--version",
                        "26.2",
                        "--command",
                        "summon minecraft:interaction 0 0 0 {Tags:[button],width:2f,height:1f,response:true}",
                        "--event",
                        "player Steve entity_interacted @e[tag=button,limit=1]",
                        "--event-trace-file",
                        eventTrace.toString(),
                    ),
                )
            }

        val trace = JsonParser.parseString(Files.readString(eventTrace).trim()).asJsonObject
        assertTrue("OK version=26.2" in output, output)
        assertEquals("minecraft:interaction", trace.get("entity").asString)
        assertEquals("@e[tag=button,limit=1]", trace.get("target").asString)
        assertTrue(trace.get("targetUuid").asString.isNotBlank())
        assertTrue(trace.get("response").asBoolean)
    }
}
