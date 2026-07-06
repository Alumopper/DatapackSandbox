package moe.afox.dpsandbox.core

object VersionProfileDocs {
    fun renderMarkdownTable(profiles: List<VersionProfile> = VersionProfiles.all, locale: String = "en"): String {
        val zh = locale.isZhCnDocsLocale()
        val directoryHeader = if (zh) "资源目录" else "Resource directories"
        return buildString {
            appendLine("| Profile | Java | Data version | Data pack format | NBT schema | $directoryHeader |")
            appendLine("|---|---:|---:|---:|---|---|")
            profiles.forEach { profile ->
                appendLine(
                    "| `${profile.id}` | ${profile.javaMajor} | ${profile.dataVersion ?: ""} | " +
                        "${profile.dataPackFormat} | `${NbtSchemas.schemaSummary(profile)}` | " +
                        "${resourceDirectorySummary(profile, locale)} |",
                )
            }
        }.trimEnd()
    }

    fun resourceDirectorySummary(profile: VersionProfile): String =
        resourceDirectorySummary(profile, locale = "en")

    fun resourceDirectorySummary(profile: VersionProfile, locale: String): String {
        val zh = locale.isZhCnDocsLocale()
        val separator = if (zh) "、" else ", "
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

        val primaryText = primary.joinToString(separator) { "`$it`" }
        return if (aliases.isEmpty()) {
            primaryText
        } else if (zh) {
            "$primaryText，允许旧别名 ${aliases.joinToString(separator) { "`$it`" }}"
        } else {
            "$primaryText with legacy aliases ${aliases.joinToString(", ") { "`$it`" }}"
        }
    }

    private fun String.isZhCnDocsLocale(): Boolean =
        lowercase().replace('_', '-') in setOf("zh", "zh-cn", "zh-hans", "zh-hans-cn")
}
