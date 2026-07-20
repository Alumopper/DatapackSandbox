package moe.afox.dpsandbox.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VersionProfileDocsTest {
    @Test
    fun `renders deterministic markdown profile table`() {
        val table =
            VersionProfileDocs.renderMarkdownTable(
                listOf(
                    VersionProfiles.get("1.20.4"),
                    VersionProfiles.get("26.2"),
                ),
            )

        assertTrue("| Profile | Java | Data version | Data pack format | NBT schema | Resource directories |" in table, table)
        assertTrue(
            "| `1.20.4` | 17 | 3700 | 26 | `1.20.4:1.20.4` | `functions`, `loot_tables`, `predicates`, `advancements` |" in table,
            table,
        )
        assertTrue(
            "| `26.2` | 25 | 4903 | 107.1 | `26.2:26.2` | `function`, `loot_table`, `predicate`, `advancement` with legacy aliases" in table,
            table,
        )
    }

    @Test
    fun `summarizes legacy aliases separately from primary directories`() {
        assertEquals(
            "`function`, `loot_table`, `predicate`, `advancement` with legacy aliases `functions`, `loot_tables`, `predicates`, `advancements`",
            VersionProfileDocs.resourceDirectorySummary(VersionProfiles.get("26.2")),
        )
    }

    @Test
    fun `renders deterministic localized markdown profile table`() {
        val table =
            VersionProfileDocs.renderMarkdownTable(
                profiles =
                    listOf(
                        VersionProfiles.get("1.20.4"),
                        VersionProfiles.get("26.2"),
                    ),
                locale = "zh-CN",
            )

        assertTrue("| Profile | Java | Data version | Data pack format | NBT schema | 资源目录 |" in table, table)
        assertTrue(
            "| `1.20.4` | 17 | 3700 | 26 | `1.20.4:1.20.4` | `functions`、`loot_tables`、`predicates`、`advancements` |" in table,
            table,
        )
        assertTrue("| `26.2` | 25 | 4903 | 107.1 | `26.2:26.2` | `function`、`loot_table`、`predicate`、`advancement`，允许旧别名" in table, table)
    }
}
