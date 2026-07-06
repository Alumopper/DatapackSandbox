package moe.afox.dpsandbox.cli

object ResourceSummaryRenderer {
    fun render(version: String, summary: ManifestResourceSummary): List<String> =
        buildList {
            add(
                "resources $version " +
                    "functions=${summary.functions} " +
                    "lootTables=${summary.lootTables} " +
                    "predicates=${summary.predicates} " +
                    "advancements=${summary.advancements} " +
                    "recipes=${summary.recipes} " +
                    "itemModifiers=${summary.itemModifiers} " +
                    "tags=${summary.tags} " +
                    "rawKinds=${summary.rawResourceKinds} " +
                    "rawResources=${summary.rawResources} " +
                    "resourceIndex=${summary.resourceIndex} " +
                    "active=${summary.activeResources} " +
                    "overridden=${summary.overriddenResources}",
            )
            summary.overlays.forEach { entry ->
                val state = if (entry.active) "active" else "overridden"
                val overlay = listOfNotNull(
                    entry.overrides?.let { "overrides=$it" },
                    entry.overriddenBy?.let { "overriddenBy=$it" },
                ).joinToString(separator = " ").takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
                add("overlay ${entry.type} ${entry.id} ${entry.behaviorLevel.id} $state pack=${entry.pack} file=${entry.file}$overlay")
            }
            summary.missingReferences.forEach { reference ->
                add("missing-reference ${reference.source} -> ${reference.type} ${reference.id}")
            }
        }

    fun print(version: String, summary: ManifestResourceSummary) {
        render(version, summary).forEach(::println)
    }
}
