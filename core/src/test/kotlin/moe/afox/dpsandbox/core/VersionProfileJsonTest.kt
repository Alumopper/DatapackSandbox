package moe.afox.dpsandbox.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VersionProfileJsonTest {
    @Test
    fun `renders profile list as json`() {
        val json = VersionProfileJson.profiles(listOf(VersionProfiles.get("26.2")))
        val profile = json.getAsJsonArray("profiles")[0].asJsonObject

        assertEquals(VersionProfiles.default.id, json.get("default").asString)
        assertEquals("26.2", profile.get("id").asString)
        assertEquals(25, profile.get("javaMajor").asInt)
        assertEquals("107.1", profile.get("dataPackFormat").asString)
        assertEquals("26.2:26.2", profile.get("nbtSchema").asString)
        assertTrue(profile.getAsJsonArray("commandRoots").map { it.asString }.contains("transfer"))
        assertTrue(profile.getAsJsonObject("registryCounts").get("items").asInt > 0)
        assertTrue(
            profile
                .getAsJsonObject("registries")
                .getAsJsonArray("damage_types")
                .map { it.asString }
                .contains("minecraft:generic"),
        )
    }

    @Test
    fun `renders profile diff as json`() {
        val diff =
            VersionProfileJson.diff(
                VersionProfileDiffs.diff(
                    VersionProfiles.get("1.20.4"),
                    VersionProfiles.get("26.2"),
                ),
            )

        assertEquals("1.20.4", diff.get("from").asString)
        assertEquals("26.2", diff.get("to").asString)
        assertEquals("1.20.4:1.20.4", diff.getAsJsonObject("nbtSchema").get("from").asString)
        assertEquals("26.2:26.2", diff.getAsJsonObject("nbtSchema").get("to").asString)
        assertTrue(
            diff
                .getAsJsonObject("commandRoots")
                .getAsJsonArray("added")
                .map { it.asString }
                .contains("transfer"),
        )
    }
}
