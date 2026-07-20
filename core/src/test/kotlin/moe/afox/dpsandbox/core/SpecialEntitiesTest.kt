package moe.afox.dpsandbox.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpecialEntitiesTest {
    @Test
    fun `display entities accept and expose block item and text content across supported profiles`() {
        listOf("1.20.4", "26.2").forEach { version ->
            val sandbox = emptySandbox(version)

            sandbox.executeCommand(
                """summon minecraft:block_display 1 2 3 {Tags:["block"],block_state:{Name:"minecraft:stone"},billboard:"fixed",brightness:{sky:15,block:7}}""",
            )
            sandbox.executeCommand(
                """summon minecraft:item_display 2 2 3 {Tags:["item"],item:{id:"minecraft:stone",count:1},item_display:"fixed"}""",
            )
            sandbox.executeCommand(
                """summon minecraft:text_display 3 2 3 {Tags:["text"],text:'{"text":"Hello"}',line_width:80,alignment:"center",see_through:true}""",
            )
            sandbox.executeCommand("item replace entity @e[tag=item,limit=1] inventory.0 with minecraft:apple 2")

            val block = sandbox.world.entities.single { "block" in it.tags }
            val item = sandbox.world.entities.single { "item" in it.tags }
            val text = sandbox.world.entities.single { "text" in it.tags }
            assertEquals(
                "minecraft:stone",
                block
                    .fullNbt(sandbox.profile)
                    .getAsJsonObject("block_state")
                    .get("Name")
                    .asString,
                version,
            )
            assertEquals(
                "minecraft:apple",
                item
                    .fullNbt(sandbox.profile)
                    .getAsJsonObject("item")
                    .get("id")
                    .asString,
                version,
            )
            assertEquals(
                2,
                item
                    .fullNbt(sandbox.profile)
                    .getAsJsonObject("item")
                    .get("count")
                    .asInt,
                version,
            )
            assertEquals("fixed", item.fullNbt(sandbox.profile).get("item_display").asString, version)
            assertEquals(80, text.fullNbt(sandbox.profile).get("line_width").asInt, version)
            assertTrue(text.fullNbt(sandbox.profile).get("see_through").asBoolean, version)

            val snapshot = sandbox.snapshotJson().getAsJsonArray("entities")
            val textSnapshot = snapshot.map { it.asJsonObject }.single { it.get("uuid").asString == text.uuid }
            assertEquals("text", textSnapshot.getAsJsonObject("special").get("kind").asString, version)
            assertEquals(
                "{\"text\":\"Hello\"}",
                textSnapshot
                    .getAsJsonObject("special")
                    .getAsJsonObject("content")
                    .get("text")
                    .asString,
                version,
            )
        }
    }

    @Test
    fun `display interpolation advances transformation text style and teleport pose deterministically`() {
        val sandbox = emptySandbox()
        sandbox.executeCommand(
            """summon minecraft:text_display 0 0 0 {Tags:["display"],text:"moving",interpolation_duration:4,teleport_duration:4,transformation:{translation:[0f,0f,0f],left_rotation:[0f,0f,0f,1f],scale:[1f,1f,1f],right_rotation:[0f,0f,0f,1f]},shadow_radius:0f,text_opacity:255,background:1073741824}""",
        )
        sandbox.executeCommand(
            """data merge entity @e[tag=display,limit=1] {start_interpolation:0,interpolation_duration:4,transformation:{translation:[4f,2f,0f],left_rotation:[0f,0f,0f,1f],scale:[3f,3f,3f],right_rotation:[0f,0f,0f,1f]},shadow_radius:4f,text_opacity:0,background:0}""",
        )

        sandbox.runTicks(2)

        var special = displaySpecial(sandbox)
        val halfway = special.getAsJsonObject("renderTransformation")
        assertEquals(2.0, halfway.getAsJsonArray("translation")[0].asDouble, 0.0001)
        assertEquals(1.0, halfway.getAsJsonArray("translation")[1].asDouble, 0.0001)
        assertEquals(2.0, halfway.getAsJsonArray("scale")[0].asDouble, 0.0001)
        assertEquals(2.0, special.get("shadowRadius").asDouble, 0.0001)
        assertEquals(128, special.get("textOpacity").asInt)
        assertEquals(0.5, special.get("interpolationProgress").asDouble, 0.0001)

        sandbox.executeCommand("tp @e[tag=display] 8 4 0 90 10")
        sandbox.runTicks(2)
        special = displaySpecial(sandbox)
        assertEquals(
            8.0,
            sandbox.world.entities
                .single { "display" in it.tags }
                .position.x,
        )
        assertEquals(4.0, special.getAsJsonObject("renderPosition").get("x").asDouble, 0.0001)
        assertEquals(2.0, special.getAsJsonObject("renderPosition").get("y").asDouble, 0.0001)
        assertEquals(45.0, special.getAsJsonObject("renderPosition").get("yaw").asDouble, 0.0001)
        assertEquals(0.5, special.get("teleportProgress").asDouble, 0.0001)

        sandbox.runTicks(2)
        special = displaySpecial(sandbox)
        assertEquals(4.0, special.getAsJsonObject("renderTransformation").getAsJsonArray("translation")[0].asDouble, 0.0001)
        assertEquals(8.0, special.getAsJsonObject("renderPosition").get("x").asDouble, 0.0001)
        assertEquals(1.0, special.get("interpolationProgress").asDouble, 0.0001)
        assertEquals(1.0, special.get("teleportProgress").asDouble, 0.0001)
    }

    @Test
    fun `armor stand pose flags and marker hitbox are modeled`() {
        val sandbox = emptySandbox()
        sandbox.executeCommand(
            """summon minecraft:armor_stand 0 0 0 {Tags:["posed"],Small:true,ShowArms:true,NoBasePlate:true,DisabledSlots:16,Pose:{Head:[10f,20f,30f],LeftArm:[-30f,0f,5f]}}""",
        )
        sandbox.executeCommand(
            """summon minecraft:armor_stand 1 0 0 {Tags:["marker_stand"],Marker:true,Invisible:true,Pose:{Body:[0f,45f,0f]}}""",
        )
        sandbox.executeCommand("damage @e[tag=marker_stand] 100")

        val posed = sandbox.world.entities.single { "posed" in it.tags }
        val marker = sandbox.world.entities.single { "marker_stand" in it.tags }
        assertEquals(
            20.0,
            posed
                .fullNbt()
                .getAsJsonObject("Pose")
                .getAsJsonArray("Head")[1]
                .asDouble,
        )
        assertEquals(
            -30.0,
            posed
                .fullNbt()
                .getAsJsonObject("Pose")
                .getAsJsonArray("LeftArm")[0]
                .asDouble,
        )
        assertEquals(16, posed.fullNbt().get("DisabledSlots").asInt)
        assertTrue(marker.fullNbt().get("Marker").asBoolean)
        assertTrue(marker.fullNbt().get("Invisible").asBoolean)

        val markerSpecial = entitySpecial(sandbox, marker.uuid)
        assertEquals(0.0, markerSpecial.getAsJsonObject("hitbox").get("width").asDouble)
        assertFalse(markerSpecial.getAsJsonObject("hitbox").get("attackable").asBoolean)

        val error =
            assertFailsWith<SandboxException> {
                sandbox.executeCommand("data merge entity @e[tag=posed,limit=1] {Pose:{Tail:[0f,0f,0f]}}")
            }
        assertEquals(DiagnosticCode.INPUT_FORMAT, error.code)
    }

    @Test
    fun `marker stores arbitrary custom data and remains immune to damage`() {
        val sandbox = emptySandbox()
        sandbox.executeCommand(
            """summon minecraft:marker 4 5 6 {Tags:["anchor"],data:{role:"checkpoint",nested:{stage:2},values:[1,2,3]}}""",
        )
        sandbox.executeCommand("data modify entity @e[tag=anchor,limit=1] data.nested.ready set value true")
        sandbox.executeCommand("tag @e[type=minecraft:marker,nbt={data:{nested:{stage:2,ready:true}}}] add matched")
        sandbox.executeCommand("damage @e[tag=anchor] 100")

        val marker = sandbox.world.entities.single { "anchor" in it.tags }
        assertTrue("matched" in marker.tags)
        assertEquals(
            "checkpoint",
            marker
                .fullNbt()
                .getAsJsonObject("data")
                .get("role")
                .asString,
        )
        assertTrue(
            marker
                .fullNbt()
                .getAsJsonObject("data")
                .getAsJsonObject("nested")
                .get("ready")
                .asBoolean,
        )
        assertFalse(marker.fullNbt().has("Health"))
    }

    @Test
    fun `interaction entity records right clicks and attacks and exposes execute relations`() {
        val sandbox = emptySandbox()
        val player = sandbox.world.requirePlayer("Steve")
        sandbox.executeCommand(
            """summon minecraft:interaction 0 0 0 {Tags:["button"],width:2f,height:1.5f,response:true}""",
        )

        sandbox.handlePlayerEvent(PlayerEvent("Steve", "entity_interacted", target = "@e[tag=button,limit=1]"))
        sandbox.executeCommand("execute as @e[tag=button,limit=1] on target run tag @s add clicked")
        sandbox.runTicks(3)
        sandbox.handlePlayerEvent(PlayerEvent("Steve", "entity_attacked", target = "@e[tag=button,limit=1]"))
        sandbox.executeCommand("execute as @e[tag=button,limit=1] on attacker run tag @s add attacked")

        val target = sandbox.world.entities.single { "button" in it.tags }
        val interaction = target.fullNbt().getAsJsonObject("interaction")
        val attack = target.fullNbt().getAsJsonObject("attack")
        assertEquals(0, interaction.get("timestamp").asLong)
        assertEquals(3, attack.get("timestamp").asLong)
        assertEquals(4, interaction.getAsJsonArray("player").size())
        assertEquals(interaction.getAsJsonArray("player"), attack.getAsJsonArray("player"))
        assertTrue("clicked" in player.tags)
        assertTrue("attacked" in player.tags)
        assertEquals(
            true,
            sandbox.world.playerEventTraces
                .first()
                .interactionResponse,
        )
        assertEquals(
            target.uuid,
            sandbox.world.playerEventTraces
                .first()
                .targetUuid,
        )

        val special = entitySpecial(sandbox, target.uuid)
        assertEquals(2.0, special.getAsJsonObject("hitbox").get("width").asDouble)
        assertEquals(1.5, special.getAsJsonObject("hitbox").get("height").asDouble)
        assertTrue(special.getAsJsonObject("hitbox").get("interactable").asBoolean)
    }

    @Test
    fun `zero sized interaction and non interactive special entities reject player hits`() {
        val sandbox = emptySandbox()
        sandbox.executeCommand("summon minecraft:interaction 0 0 0 {Tags:[zero],width:0f,height:1f}")
        sandbox.executeCommand("summon minecraft:block_display 0 0 0 {Tags:[display]}")

        val zero =
            assertFailsWith<SandboxException> {
                sandbox.handlePlayerEvent(PlayerEvent("Steve", "entity_interacted", target = "@e[tag=zero,limit=1]"))
            }
        assertTrue(zero.message.contains("zero-sized"))
        val display =
            assertFailsWith<SandboxException> {
                sandbox.handlePlayerEvent(PlayerEvent("Steve", "entity_attacked", target = "@e[tag=display,limit=1]"))
            }
        assertTrue(display.message.contains("no attack hitbox"))
    }

    private fun emptySandbox(version: String = "26.2"): DatapackSandbox =
        createFunctionSandboxFromString(version, "", defaultPlayerName = "Steve")

    private fun displaySpecial(sandbox: DatapackSandbox) =
        entitySpecial(
            sandbox,
            sandbox.world.entities
                .single { "display" in it.tags }
                .uuid,
        )

    private fun entitySpecial(
        sandbox: DatapackSandbox,
        uuid: String,
    ) = sandbox
        .snapshotJson()
        .getAsJsonArray("entities")
        .map { it.asJsonObject }
        .single { it.get("uuid").asString == uuid }
        .getAsJsonObject("special")
}
