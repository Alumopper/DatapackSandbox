package moe.afox.dpsandbox.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CommandExecuteAndSelectorTest {
    @Test
    fun `execute conditions cover predicate dimension biome and loaded state`() {
        val pack = writePredicatePack(Files.createTempDirectory("dps-execute-conditions-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))

        sandbox.executeCommand("scoreboard objectives add checks dummy")
        sandbox.executeCommand("execute if dimension minecraft:overworld run scoreboard players add #pass checks 1")
        sandbox.executeCommand(
            "execute in minecraft:the_nether if dimension minecraft:the_nether run scoreboard players add #pass checks 1",
        )
        sandbox.executeCommand(
            "execute in minecraft:the_nether unless dimension minecraft:overworld run scoreboard players add #pass checks 1",
        )
        sandbox.executeCommand("forceload add 0 0")
        sandbox.executeCommand("execute if loaded 1 64 1 run scoreboard players add #pass checks 1")
        sandbox.executeCommand("execute unless loaded 32 64 32 run scoreboard players add #pass checks 1")
        sandbox.executeCommand("fillbiome 0 64 0 0 64 0 minecraft:forest")
        sandbox.executeCommand("setblock 1 64 0 minecraft:stone[mode=debug]")
        sandbox.executeCommand("execute if biome 0 64 0 minecraft:forest run scoreboard players add #pass checks 1")
        sandbox.executeCommand("execute unless biome 0 64 0 minecraft:desert run scoreboard players add #pass checks 1")
        sandbox.executeCommand("execute as Steve if predicate demo:is_player run scoreboard players add #pass checks 1")
        sandbox.executeCommand("execute in minecraft:the_nether if predicate demo:in_nether run scoreboard players add #pass checks 1")
        sandbox.executeCommand("execute positioned 0 64 0 if predicate demo:in_forest run scoreboard players add #pass checks 1")
        sandbox.executeCommand("execute positioned 1 64 0 if predicate demo:debug_stone run scoreboard players add #pass checks 1")
        sandbox.executeCommand("execute positioned 8 64 8 if predicate demo:void_air run scoreboard players add #pass checks 1")
        sandbox.executeCommand("execute unless predicate demo:false run scoreboard players add #pass checks 1")
        sandbox.executeCommand("execute if dimension minecraft:the_nether run scoreboard players add #fail checks 1")
        sandbox.executeCommand("execute if loaded 32 64 32 run scoreboard players add #fail checks 1")
        sandbox.executeCommand("execute if biome 0 64 0 minecraft:desert run scoreboard players add #fail checks 1")
        sandbox.executeCommand("execute if predicate demo:in_nether run scoreboard players add #fail checks 1")
        sandbox.executeCommand("execute positioned 0 64 0 if predicate demo:in_desert run scoreboard players add #fail checks 1")
        sandbox.executeCommand("execute positioned 1 64 0 if predicate demo:debug_dirt run scoreboard players add #fail checks 1")
        sandbox.executeCommand("execute if predicate demo:false run scoreboard players add #fail checks 1")

        assertEquals(13, sandbox.world.getScore("#pass", "checks"))
        assertEquals(0, sandbox.world.getScore("#fail", "checks"))
    }

    @Test
    fun `selector options filter by name distance volume and sort`() {
        val pack = writePredicatePack(Files.createTempDirectory("dps-selector-predicate-pack"))
        val sandbox =
            createFunctionSandboxFromString(
                version = "26.2",
                packs = listOf(pack),
                functionText = "",
                functionId = "demo:empty",
            )
        val alex = sandbox.createPlayer("Alex")
        val blair = sandbox.createPlayer("Blair")
        alex.position = Position(0.0, 64.0, 0.0)
        blair.position = Position(8.0, 64.0, 0.0)
        alex.advancementProgress[ResourceLocation.parse("demo:selector_complete")] =
            AdvancementProgress(mutableMapOf("done" to true))
        alex.advancementProgress[ResourceLocation.parse("demo:selector_partial")] =
            AdvancementProgress(mutableMapOf("visible" to true, "hidden" to false))

        sandbox.executeCommand("""summon minecraft:pig 1 64 1 {Tags:["near"],Health:7f}""")
        sandbox.executeCommand("""summon minecraft:pig 9 64 0 {Tags:["far"],Health:3f}""")
        sandbox.executeCommand("""execute in minecraft:the_nether run summon minecraft:pig 4 64 4 {Tags:["nether_predicate"],Health:5f}""")
        sandbox.executeCommand("""summon minecraft:cow 2 64 0 {Tags:["near"]}""")
        sandbox.executeCommand("scoreboard objectives add selector dummy")
        sandbox.executeCommand("scoreboard objectives add selector_scores dummy")
        sandbox.executeCommand("scoreboard objectives add selector_other dummy")
        sandbox.executeCommand("gamemode creative Alex")
        sandbox.executeCommand("gamemode adventure Blair")
        sandbox.executeCommand("experience set Alex 5 levels")
        sandbox.executeCommand("experience set Blair 2 levels")
        sandbox.executeCommand("team add red")
        sandbox.executeCommand("team add blue")
        sandbox.executeCommand("team join red Alex")
        sandbox.executeCommand("team join blue Blair")
        sandbox.executeCommand("scoreboard players set Alex selector_scores 5")
        sandbox.executeCommand("scoreboard players set Alex selector_other 3")
        sandbox.executeCommand("scoreboard players set Blair selector_scores 2")
        sandbox.executeCommand("scoreboard players set Blair selector_other 3")
        sandbox.executeCommand("""execute as @e[type=minecraft:pig,tag=near,limit=1] run scoreboard players set @s selector_scores 7""")
        sandbox.executeCommand("""execute as @e[type=minecraft:pig,tag=far,limit=1] run scoreboard players set @s selector_scores 1""")
        sandbox.executeCommand("""rotate @e[type=minecraft:pig,tag=near,limit=1] 30 10""")
        sandbox.executeCommand("""rotate @e[type=minecraft:pig,tag=far,limit=1] -90 -20""")
        sandbox.executeCommand("""tag @e[type=minecraft:pig,x=0,y=64,z=0,dx=2,dy=0,dz=2] add boxed""")
        sandbox.executeCommand("""execute if entity @e[tag=boxed,limit=1] run scoreboard players add #boxed selector 1""")
        sandbox.executeCommand(
            """execute if entity @e[type=minecraft:pig,x=0,y=64,z=0,distance=..2,limit=1] run scoreboard players add #near selector 1""",
        )
        sandbox.executeCommand(
            """execute if entity @a[name=Alex,x=0,y=64,z=0,distance=..1,limit=1] run scoreboard players add #name selector 1""",
        )
        sandbox.executeCommand(
            """execute unless entity @a[name=!Alex,x=0,y=64,z=0,distance=..1] run scoreboard players add #name selector 1""",
        )
        sandbox.executeCommand(
            """execute if entity @a[gamemode=creative,scores={selector_scores=5..,selector_other=3}] run scoreboard players add #score selector 1""",
        )
        sandbox.executeCommand(
            """execute if entity @a[gamemode=!spectator,scores={selector_scores=2..5,selector_other=3},limit=2] run scoreboard players add #score selector 1""",
        )
        sandbox.executeCommand(
            """execute if entity @e[type=minecraft:pig,scores={selector_scores=..1},limit=1] run scoreboard players add #score selector 1""",
        )
        sandbox.executeCommand(
            """execute unless entity @e[type=minecraft:pig,scores={selector_scores=2..6}] run scoreboard players add #score selector 1""",
        )
        sandbox.executeCommand(
            """execute if entity @e[type=minecraft:pig,nbt={Health:7f},limit=1] run scoreboard players add #nbt selector 1""",
        )
        sandbox.executeCommand(
            """execute if entity @e[type=minecraft:pig,nbt=!{Health:7f},limit=1] run scoreboard players add #nbt selector 1""",
        )
        sandbox.executeCommand(
            """execute unless entity @e[type=minecraft:pig,nbt={Health:9f}] run scoreboard players add #nbt selector 1""",
        )
        sandbox.executeCommand(
            """execute if entity @a[name=Alex,predicate=demo:is_player] run scoreboard players add #predicate selector 1""",
        )
        sandbox.executeCommand(
            """execute if entity @e[type=minecraft:pig,predicate=!demo:is_player,limit=1] run scoreboard players add #predicate selector 1""",
        )
        sandbox.executeCommand(
            """execute unless entity @a[name=Alex,predicate=demo:false] run scoreboard players add #predicate selector 1""",
        )
        sandbox.executeCommand(
            """execute if entity @e[type=minecraft:pig,tag=nether_predicate,predicate=demo:in_nether,limit=1] run scoreboard players add #predicate selector 1""",
        )
        sandbox.executeCommand(
            """execute if entity @e[type=minecraft:pig,tag=nether_predicate,predicate=demo:at_four,limit=1] run scoreboard players add #predicate selector 1""",
        )
        sandbox.executeCommand(
            """execute if entity @a[name=Alex,advancements={demo:selector_complete=true}] run scoreboard players add #adv selector 1""",
        )
        sandbox.executeCommand(
            """execute if entity @a[name=Alex,advancements={demo:selector_partial={visible=true,hidden=false}}] run scoreboard players add #adv selector 1""",
        )
        sandbox.executeCommand(
            """execute if entity @a[name=Blair,advancements={demo:selector_complete=false}] run scoreboard players add #adv selector 1""",
        )
        sandbox.executeCommand(
            """execute unless entity @a[name=Alex,advancements={demo:selector_complete=false}] run scoreboard players add #adv selector 1""",
        )
        sandbox.executeCommand("""execute if entity @a[team=red,level=5] run scoreboard players add #teamlevel selector 1""")
        sandbox.executeCommand("""execute if entity @a[team=!red,level=..2] run scoreboard players add #teamlevel selector 1""")
        sandbox.executeCommand("""execute if entity @a[team=!,limit=2] run scoreboard players add #teamlevel selector 1""")
        sandbox.executeCommand("""execute unless entity @a[name=!Steve,team=] run scoreboard players add #teamlevel selector 1""")
        sandbox.executeCommand(
            """execute if entity @e[type=minecraft:pig,y_rotation=-100..-80,x_rotation=-25..-15] run scoreboard players add #rotation selector 1""",
        )
        sandbox.executeCommand(
            """execute if entity @e[type=minecraft:pig,y_rotation=30,x_rotation=10] run scoreboard players add #rotation selector 1""",
        )
        sandbox.executeCommand(
            """execute unless entity @e[type=minecraft:pig,y_rotation=31,x_rotation=10] run scoreboard players add #rotation selector 1""",
        )
        sandbox.executeCommand("""tag @e[type=minecraft:pig,x=0,y=64,z=0,sort=furthest,limit=1] add furthest""")
        sandbox.executeCommand("""tag @e[type=minecraft:pig,x=0,y=64,z=0,sort=nearest,limit=1] add nearest""")
        sandbox.executeCommand("""execute if entity @e[tag=furthest,tag=far] run scoreboard players add #sort selector 1""")
        sandbox.executeCommand("""execute if entity @e[tag=nearest,tag=near] run scoreboard players add #sort selector 1""")

        assertEquals(1, sandbox.world.getScore("#boxed", "selector"))
        assertEquals(1, sandbox.world.getScore("#near", "selector"))
        assertEquals(2, sandbox.world.getScore("#name", "selector"))
        assertEquals(4, sandbox.world.getScore("#score", "selector"))
        assertEquals(3, sandbox.world.getScore("#nbt", "selector"))
        assertEquals(5, sandbox.world.getScore("#predicate", "selector"))
        assertEquals(4, sandbox.world.getScore("#adv", "selector"))
        assertEquals(4, sandbox.world.getScore("#teamlevel", "selector"))
        assertEquals(3, sandbox.world.getScore("#rotation", "selector"))
        assertEquals(2, sandbox.world.getScore("#sort", "selector"))

        val invalidDistance =
            assertFailsWith<SandboxException> {
                sandbox.executeCommand("""execute if entity @e[distance=-1] run say invalid""")
            }
        assertEquals(DiagnosticCode.INPUT_FORMAT, invalidDistance.code)

        val invalidScoreRange =
            assertFailsWith<SandboxException> {
                sandbox.executeCommand("""execute if entity @a[scores={selector_scores=5..2}] run say invalid""")
            }
        assertEquals(DiagnosticCode.INPUT_FORMAT, invalidScoreRange.code)

        val invalidNbt =
            assertFailsWith<SandboxException> {
                sandbox.executeCommand("""execute if entity @e[nbt=1] run say invalid""")
            }
        assertEquals(DiagnosticCode.INPUT_FORMAT, invalidNbt.code)

        val invalidAdvancement =
            assertFailsWith<SandboxException> {
                sandbox.executeCommand("""execute if entity @a[advancements={demo:selector_complete=maybe}] run say invalid""")
            }
        assertEquals(DiagnosticCode.INPUT_FORMAT, invalidAdvancement.code)

        val invalidRotationRange =
            assertFailsWith<SandboxException> {
                sandbox.executeCommand("""execute if entity @e[x_rotation=10..-10] run say invalid""")
            }
        assertEquals(DiagnosticCode.INPUT_FORMAT, invalidRotationRange.code)
    }

    @Test
    fun `execute as preserves position while at and positioned as move context`() {
        val sandbox =
            createFunctionSandboxFromString(
                version = "26.2",
                functionText = "",
                functionId = "demo:empty",
            )

        sandbox.executeCommand("""execute in minecraft:the_nether run summon minecraft:pig 5 0 0 {Tags:["anchor"]}""")
        sandbox.executeCommand("""execute as @e[tag=anchor,limit=1] run summon minecraft:marker ~1 ~0 ~0 {Tags:["from_as"]}""")
        sandbox.executeCommand("""execute at @e[tag=anchor,limit=1] run summon minecraft:marker ~1 ~0 ~0 {Tags:["from_at"]}""")
        sandbox.executeCommand(
            """execute positioned as @e[tag=anchor,limit=1] run summon minecraft:marker ~2 ~0 ~0 {Tags:["from_positioned_as"]}""",
        )

        val fromAs = sandbox.world.entities.single { "from_as" in it.tags }
        val fromAt = sandbox.world.entities.single { "from_at" in it.tags }
        val fromPositionedAs = sandbox.world.entities.single { "from_positioned_as" in it.tags }
        assertEquals(Position(1.0, 0.0, 0.0), fromAs.position)
        assertEquals(ResourceLocation.parse("minecraft:overworld"), fromAs.dimension)
        assertEquals(Position(6.0, 0.0, 0.0), fromAt.position)
        assertEquals(ResourceLocation.parse("minecraft:the_nether"), fromAt.dimension)
        assertEquals(Position(7.0, 0.0, 0.0), fromPositionedAs.position)
        assertEquals(ResourceLocation.parse("minecraft:overworld"), fromPositionedAs.dimension)
    }

    @Test
    fun `execute align floors selected axes and rejects invalid swizzles`() {
        val sandbox =
            createFunctionSandboxFromString(
                version = "26.2",
                functionText = "",
                functionId = "demo:empty",
            )

        sandbox.executeCommand("""execute positioned 1.9 2.9 -1.2 align xz run summon minecraft:marker ~ ~ ~ {Tags:["aligned"]}""")

        val aligned = sandbox.world.entities.single { "aligned" in it.tags }
        assertEquals(Position(1.0, 2.9, -2.0), aligned.position)

        val invalidAxis =
            assertFailsWith<SandboxException> {
                sandbox.executeCommand("execute align xq run say invalid")
            }
        assertEquals(DiagnosticCode.INPUT_FORMAT, invalidAxis.code)

        val duplicateAxis =
            assertFailsWith<SandboxException> {
                sandbox.executeCommand("execute align xx run say invalid")
            }
        assertEquals(DiagnosticCode.INPUT_FORMAT, duplicateAxis.code)
    }

    @Test
    fun `execute rotated controls relative teleport rotation`() {
        val sandbox =
            createFunctionSandboxFromString(
                version = "26.2",
                functionText = "",
                functionId = "demo:empty",
            )

        sandbox.executeCommand("""summon minecraft:pig 0 0 0 {Tags:["anchor"]}""")
        sandbox.executeCommand("""execute as @e[tag=anchor,limit=1] rotated 90 15 run tp @s ~ ~ ~ ~ ~""")

        val anchor = sandbox.world.entities.single { "anchor" in it.tags }
        assertEquals(90.0, anchor.yaw)
        assertEquals(15.0, anchor.pitch)

        sandbox.executeCommand("""rotate @e[tag=anchor,limit=1] 30 5""")
        sandbox.executeCommand("""execute as @e[tag=anchor,limit=1] rotated as @e[tag=anchor,limit=1] run tp @s ~ ~ ~ ~10 ~-2""")

        assertEquals(40.0, anchor.yaw)
        assertEquals(3.0, anchor.pitch)
        val rotateOutput = sandbox.world.outputs.single { it.command == "rotate" }
        val rotatePayload = rotateOutput.payload?.asJsonObject ?: error("missing rotate payload")
        val rotated = rotatePayload.getAsJsonArray("targets")[0].asJsonObject
        assertEquals("1", rotateOutput.text)
        assertEquals(listOf(anchor.uuid), rotateOutput.targets)
        assertEquals(30.0, rotatePayload.get("yaw").asDouble)
        assertEquals(5.0, rotatePayload.get("pitch").asDouble)
        assertEquals(90.0, rotated.get("beforeYaw").asDouble)
        assertEquals(15.0, rotated.get("beforePitch").asDouble)
        assertEquals(30.0, rotated.get("afterYaw").asDouble)
        assertEquals(5.0, rotated.get("afterPitch").asDouble)
    }

    @Test
    fun `execute facing controls relative teleport rotation`() {
        val sandbox =
            createFunctionSandboxFromString(
                version = "26.2",
                functionText = "",
                functionId = "demo:empty",
            )

        sandbox.executeCommand("""summon minecraft:pig 0 0 0 {Tags:["anchor"]}""")
        sandbox.executeCommand("""summon minecraft:marker 0 0 1 {Tags:["target"]}""")

        val anchor = sandbox.world.entities.single { "anchor" in it.tags }
        sandbox.executeCommand("""execute as @e[tag=anchor,limit=1] positioned 0 0 0 facing 1 0 0 run tp @s ~ ~ ~ ~ ~""")
        assertEquals(-90.0, anchor.yaw, 0.0001)
        assertEquals(0.0, anchor.pitch, 0.0001)

        sandbox.executeCommand("""execute as @e[tag=anchor,limit=1] positioned 0 0 0 facing 0 1 0 run tp @s ~ ~ ~ ~ ~""")
        assertEquals(0.0, anchor.yaw, 0.0001)
        assertEquals(-90.0, anchor.pitch, 0.0001)

        sandbox.executeCommand(
            """execute as @e[tag=anchor,limit=1] positioned 0 0 0 facing entity @e[tag=target,limit=1] feet run tp @s ~ ~ ~ ~ ~""",
        )
        assertEquals(0.0, anchor.yaw, 0.0001)
        assertEquals(0.0, anchor.pitch, 0.0001)
    }

    @Test
    fun `teleporting to an entity copies destination rotation`() {
        val sandbox =
            createFunctionSandboxFromString(
                version = "26.2",
                functionText = "",
                functionId = "demo:empty",
            )

        sandbox.executeCommand("""summon minecraft:pig 0 0 0 {Tags:["traveler"]}""")
        sandbox.executeCommand("""execute in minecraft:the_nether run summon minecraft:marker 4 2 1 {Tags:["destination"]}""")
        sandbox.executeCommand("""rotate @e[tag=destination,limit=1] -45 12""")
        sandbox.executeCommand("""tp @e[tag=traveler,limit=1] @e[tag=destination,limit=1]""")

        val traveler = sandbox.world.entities.single { "traveler" in it.tags }
        assertEquals(Position(4.0, 2.0, 1.0), traveler.position)
        assertEquals(ResourceLocation.parse("minecraft:the_nether"), traveler.dimension)
        assertEquals(-45.0, traveler.yaw)
        assertEquals(12.0, traveler.pitch)
    }

    @Test
    fun `teleport supports local coordinates from execution rotation`() {
        val sandbox =
            createFunctionSandboxFromString(
                version = "26.2",
                functionText = "",
                functionId = "demo:empty",
            )

        sandbox.executeCommand("""summon minecraft:pig 10 64 10 {Tags:["traveler"]}""")
        val traveler = sandbox.world.entities.single { "traveler" in it.tags }

        sandbox.executeCommand("""execute as @e[tag=traveler,limit=1] rotated 0 0 run tp ^ ^ ^2""")
        assertEquals(10.0, traveler.position.x, 0.0001)
        assertEquals(64.0, traveler.position.y, 0.0001)
        assertEquals(12.0, traveler.position.z, 0.0001)

        sandbox.executeCommand("""execute as @e[tag=traveler,limit=1] rotated -90 0 run tp ^ ^ ^3""")
        assertEquals(13.0, traveler.position.x, 0.0001)
        assertEquals(64.0, traveler.position.y, 0.0001)
        assertEquals(12.0, traveler.position.z, 0.0001)

        sandbox.executeCommand("""execute positioned 10 64 10 rotated 0 0 run tp @e[tag=traveler,limit=1] ^1 ^2 ^3""")
        assertEquals(11.0, traveler.position.x, 0.0001)
        assertEquals(66.0, traveler.position.y, 0.0001)
        assertEquals(13.0, traveler.position.z, 0.0001)

        val error =
            assertFailsWith<SandboxException> {
                sandbox.executeCommand("""tp @e[tag=traveler,limit=1] ^ ~ ^""")
            }
        assertEquals(DiagnosticCode.INPUT_FORMAT, error.code)
    }

    @Test
    fun `execute anchored changes local coordinate base`() {
        val sandbox =
            createFunctionSandboxFromString(
                version = "26.2",
                functionText = "",
                functionId = "demo:empty",
            )

        sandbox.executeCommand("""summon minecraft:pig 0 64 0 {Tags:["anchor"]}""")
        val anchor = sandbox.world.entities.single { "anchor" in it.tags }

        sandbox.executeCommand("""execute as @e[tag=anchor,limit=1] anchored eyes run tp ^ ^ ^1""")
        assertEquals(Position(0.0, 65.0, 1.0), anchor.position)

        sandbox.executeCommand("""tp @e[tag=anchor,limit=1] 0 64 0""")
        sandbox.executeCommand("""execute as @e[tag=anchor,limit=1] anchored feet run tp ^ ^ ^1""")
        assertEquals(Position(0.0, 64.0, 1.0), anchor.position)

        sandbox.executeCommand("""tp @e[tag=anchor,limit=1] 0 64 0""")
        sandbox.executeCommand("""execute as @e[tag=anchor,limit=1] at @s anchored eyes run setblock ^ ^ ^ minecraft:stone""")
        sandbox.executeCommand("""execute as @e[tag=anchor,limit=1] at @s anchored feet run setblock ^ ^ ^ minecraft:dirt""")
        assertEquals(ResourceLocation.parse("minecraft:stone"), sandbox.world.requireBlock(BlockPos(0, 65, 0)).id)
        assertEquals(ResourceLocation.parse("minecraft:dirt"), sandbox.world.requireBlock(BlockPos(0, 64, 0)).id)

        val error =
            assertFailsWith<SandboxException> {
                sandbox.executeCommand("""execute anchored head run say invalid""")
            }
        assertEquals(DiagnosticCode.INPUT_FORMAT, error.code)
    }

    @Test
    fun `teleport facing rotates moved entities toward positions and anchors`() {
        val sandbox =
            createFunctionSandboxFromString(
                version = "26.2",
                functionText = "",
                functionId = "demo:empty",
            )

        sandbox.executeCommand("""summon minecraft:pig 3 0 0 {Tags:["traveler"]}""")
        sandbox.executeCommand("""summon minecraft:marker 0 0 1 {Tags:["look_at"]}""")

        val traveler = sandbox.world.entities.single { "traveler" in it.tags }
        sandbox.executeCommand("""tp @e[tag=traveler,limit=1] 0 0 0 facing 1 0 0""")
        assertEquals(Position(0.0, 0.0, 0.0), traveler.position)
        assertEquals(-90.0, traveler.yaw, 0.0001)
        assertEquals(0.0, traveler.pitch, 0.0001)

        sandbox.executeCommand("""tp @e[tag=traveler,limit=1] 0 0 0 facing entity @e[tag=look_at,limit=1] eyes""")
        assertEquals(0.0, traveler.yaw, 0.0001)
        assertEquals(-45.0, traveler.pitch, 0.0001)

        sandbox.executeCommand("""execute positioned 0 0 0 rotated -90 0 run tp @e[tag=traveler,limit=1] 0 0 0 facing ^ ^ ^1""")
        assertEquals(-90.0, traveler.yaw, 0.0001)
        assertEquals(0.0, traveler.pitch, 0.0001)
    }

    @Test
    fun `block position commands support local coordinates from execution rotation`() {
        val sandbox =
            createFunctionSandboxFromString(
                version = "26.2",
                functionText = "",
                functionId = "demo:empty",
            )

        sandbox.executeCommand("scoreboard objectives add checks dummy")
        sandbox.executeCommand("""execute positioned 10 64 10 rotated -90 0 run setblock ^ ^ ^1 minecraft:stone""")
        assertEquals(ResourceLocation.parse("minecraft:stone"), sandbox.world.requireBlock(BlockPos(11, 64, 10)).id)

        sandbox.executeCommand("""execute positioned 10 64 10 rotated 0 0 run fill ^ ^ ^1 ^1 ^ ^1 minecraft:dirt""")
        assertEquals(ResourceLocation.parse("minecraft:dirt"), sandbox.world.requireBlock(BlockPos(10, 64, 11)).id)
        assertEquals(ResourceLocation.parse("minecraft:dirt"), sandbox.world.requireBlock(BlockPos(11, 64, 11)).id)
        val fillOutput = sandbox.world.outputs.single { it.command == "fill" }
        val fillPayload = fillOutput.payload?.asJsonObject ?: error("missing fill payload")
        assertEquals("2", fillOutput.text)
        assertEquals(listOf("10 64 11", "11 64 11"), fillOutput.targets)
        assertEquals(2, fillPayload.get("volume").asInt)
        assertEquals(2, fillPayload.get("changed").asInt)
        assertEquals("replace", fillPayload.get("mode").asString)
        assertEquals("minecraft:dirt", fillPayload.getAsJsonObject("block").get("id").asString)
        assertEquals("10 64 11", fillPayload.getAsJsonArray("positions")[0].asString)

        sandbox.executeCommand(
            """execute positioned 10 64 10 rotated 0 0 if block ^ ^ ^1 minecraft:dirt run scoreboard players add #local checks 1""",
        )
        sandbox.executeCommand(
            """execute positioned 10 64 10 rotated 0 0 unless block ^ ^ ^1 minecraft:stone run scoreboard players add #local checks 1""",
        )
        assertEquals(2, sandbox.world.getScore("#local", "checks"))
    }

    @Test
    fun `execute blocks condition compares sparse block regions`() {
        val sandbox = createSandbox("26.1.2", listOf(fixturePack()))

        sandbox.executeCommand("scoreboard objectives add checks dummy")
        sandbox.executeCommand("setblock 0 64 0 minecraft:stone")
        sandbox.executeCommand("setblock 1 64 0 minecraft:chest[facing=north]")
        sandbox.executeCommand("setblock 4 64 0 minecraft:stone")
        sandbox.executeCommand("setblock 5 64 0 minecraft:chest[facing=north]")
        sandbox.executeCommand("setblock 6 64 0 minecraft:dirt")
        sandbox.executeCommand("execute if blocks 0 64 0 1 64 0 4 64 0 all run scoreboard players add #pass checks 1")
        sandbox.executeCommand("execute if blocks 0 64 0 2 64 0 4 64 0 masked run scoreboard players add #pass checks 1")
        sandbox.executeCommand("execute unless blocks 0 64 0 2 64 0 4 64 0 all run scoreboard players add #pass checks 1")
        sandbox.executeCommand("execute if blocks 0 64 0 2 64 0 4 64 0 all run scoreboard players add #fail checks 1")

        assertEquals(3, sandbox.world.getScore("#pass", "checks"))
        assertEquals(0, sandbox.world.getScore("#fail", "checks"))
    }

    @Test
    fun `execute function condition uses function return values and tags`() {
        val pack = writeExecuteFunctionPack(Files.createTempDirectory("dps-execute-function-pack"))
        val sandbox = createSandbox("26.2", listOf(pack))

        sandbox.executeCommand("scoreboard objectives add checks dummy")
        sandbox.executeCommand("execute if function demo:pass run scoreboard players add #pass checks 1")
        sandbox.executeCommand("execute unless function demo:fail run scoreboard players add #pass checks 1")
        sandbox.executeCommand("execute if function #demo:group run scoreboard players add #pass checks 1")
        sandbox.executeCommand("execute if function demo:fail run scoreboard players add #fail checks 1")
        sandbox.executeCommand("execute unless function demo:pass run scoreboard players add #fail checks 1")
        sandbox.executeCommand("execute store success score #function_pass checks run function demo:pass")
        sandbox.executeCommand("execute store success score #function_fail checks run function demo:fail")
        sandbox.executeCommand("execute store result score #return checks run function demo:return_run")
        sandbox.executeCommand("execute store result score #explicit checks run function demo:return_after_output")

        assertEquals(3, sandbox.world.getScore("#pass", "checks"))
        assertEquals(0, sandbox.world.getScore("#fail", "checks"))
        assertEquals(1, sandbox.world.getScore("#function_pass", "checks"))
        assertEquals(0, sandbox.world.getScore("#function_fail", "checks"))
        assertEquals(44, sandbox.world.getScore("#condition", "checks"))
        assertEquals(4, sandbox.world.getScore("#return", "checks"))
        assertEquals(4, sandbox.world.getScore("#explicit", "checks"))
    }
}
