package net.datapacksandbox.core

data class VersionProfile(
    val id: String,
    val javaMajor: Int,
    val dataVersion: Int? = null,
    val description: String,
    val registryView: RegistryView = RegistryView.vanilla2612,
)

object VersionProfiles {
    val minecraft2612 = VersionProfile(
        id = "26.1.2",
        javaMajor = 25,
        dataVersion = null,
        description = "Minecraft Java 26.1.2 clean-room sandbox profile",
    )

    val default: VersionProfile = minecraft2612
    val all: List<VersionProfile> = listOf(minecraft2612)

    fun get(id: String): VersionProfile =
        all.firstOrNull { it.id == id }
            ?: throw SandboxException(
                code = DiagnosticCode.INPUT_FORMAT,
                message = "Unknown version profile '$id'. Available profiles: ${all.joinToString { it.id }}",
                version = id,
            )
}
