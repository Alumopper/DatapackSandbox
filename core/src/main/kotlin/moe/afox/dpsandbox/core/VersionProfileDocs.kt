package moe.afox.dpsandbox.core

object VersionProfileDocs {
    fun renderMarkdownTable(profiles: List<VersionProfile> = VersionProfiles.all): String =
        buildString {
            appendLine("| Profile | Java | Data version | Data pack format | NBT schema | Resource directories |")
            appendLine("|---|---:|---:|---:|---|---|")
            profiles.forEach { profile ->
                appendLine(
                    "| `${profile.id}` | ${profile.javaMajor} | ${profile.dataVersion ?: ""} | " +
                        "${profile.dataPackFormat} | `${NbtSchemas.schemaSummary(profile)}` | " +
                        "${resourceDirectorySummary(profile)} |",
                )
            }
        }.trimEnd()

    fun resourceDirectorySummary(profile: VersionProfile): String {
        val directories = profile.resourceDirectories
        val primary = listOf(
            directories.functions.firstOrNull(),
            directories.lootTables.firstOrNull(),
            directories.predicates.firstOrNull(),
            directories.advancements.firstOrNull(),
        ).filterNotNull()
        val aliases = listOf(
            directories.functions.drop(1),
            directories.lootTables.drop(1),
            directories.predicates.drop(1),
            directories.advancements.drop(1),
        ).flatten()

        val primaryText = primary.joinToString(", ") { "`$it`" }
        return if (aliases.isEmpty()) {
            primaryText
        } else {
            "$primaryText with legacy aliases ${aliases.joinToString(", ") { "`$it`" }}"
        }
    }
}
