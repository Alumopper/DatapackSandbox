package moe.afox.dpsandbox.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VersionProfileDiffTest {
    @Test
    fun `reports profile differences for legacy to latest versions`() {
        val diff =
            VersionProfileDiffs.diff(
                VersionProfiles.get("1.20.4"),
                VersionProfiles.get("26.2"),
            )

        assertEquals(ValueChange(17, 25), diff.javaMajor)
        assertEquals(ValueChange("26", "107.1"), diff.dataPackFormat)
        assertEquals(ValueChange("1.20.4:1.20.4", "26.2:26.2"), diff.nbtSchema)
        assertTrue("function" in diff.resourceDirectories.getValue("functions").added)
        assertTrue("transfer" in diff.commandRoots.added)

        val rendered = VersionProfileDiffs.render(diff)
        assertTrue("profile diff 1.20.4 -> 26.2" in rendered, rendered)
        assertTrue("pack_format: 26 -> 107.1" in rendered, rendered)
        assertTrue("nbt_schema: 1.20.4:1.20.4 -> 26.2:26.2" in rendered, rendered)
        assertTrue("command_roots: added=transfer" in rendered, rendered)
    }
}
