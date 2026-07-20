package moe.afox.dpsandbox.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SnapshotDiffTest {
    @Test
    fun `computes stable json pointer snapshot diffs`() {
        val before =
            JsonValues.parse(
                """
                {
                  "scores": { "runs": { "#clock": 1 } },
                  "players": ["Steve"],
                  "removed": true
                }
                """.trimIndent(),
            )
        val after =
            JsonValues.parse(
                """
                {
                  "scores": { "runs": { "#clock": 2, "#new": 3 } },
                  "players": ["Steve", "Alex"],
                  "added": "yes"
                }
                """.trimIndent(),
            )

        val diff = SnapshotDiff.diff(before, after)

        assertEquals(
            listOf("/added", "/players/1", "/removed", "/scores/runs/#clock", "/scores/runs/#new"),
            diff.map { it.path },
        )
        assertTrue(diff.any { it.render() == "~ /scores/runs/#clock: 1 -> 2" })
        assertTrue(JsonValues.render(SnapshotDiff.toJson(diff)).contains("\"kind\": \"changed\""))
    }

    @Test
    fun `state diff excludes trace metadata`() {
        val before =
            JsonValues.parse(
                """
                {
                  "scores": {},
                  "traces": [],
                  "playerEventTraces": []
                }
                """.trimIndent(),
            )
        val after =
            JsonValues.parse(
                """
                {
                  "scores": { "runs": { "#clock": 1 } },
                  "traces": [
                    { "command": "scoreboard players set #clock runs 1", "snapshotDiffs": [{ "path": "/scores/runs" }] }
                  ],
                  "playerEventTraces": [
                    { "player": "Steve", "type": "tick" }
                  ]
                }
                """.trimIndent(),
            )

        assertTrue(SnapshotDiff.diff(before, after).any { it.path.startsWith("/traces") })
        assertEquals(listOf("/scores/runs"), SnapshotDiff.stateDiff(before, after).map { it.path })
    }
}
