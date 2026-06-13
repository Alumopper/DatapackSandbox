package moe.afox.dpsandbox.core

private val namespacePattern = Regex("[a-z0-9_.-]+")
private val pathPattern = Regex("[a-z0-9_./-]+")

data class ResourceLocation(val namespace: String, val path: String) : Comparable<ResourceLocation> {
    init {
        require(namespacePattern.matches(namespace)) { "Invalid namespace: $namespace" }
        require(pathPattern.matches(path)) { "Invalid resource path: $path" }
    }

    override fun toString(): String = "$namespace:$path"

    override fun compareTo(other: ResourceLocation): Int =
        compareValuesBy(this, other, ResourceLocation::namespace, ResourceLocation::path)

    companion object {
        fun parse(value: String, defaultNamespace: String = "minecraft"): ResourceLocation {
            val trimmed = value.trim()
            val split = trimmed.split(":", limit = 2)
            val namespace = if (split.size == 2 && split[0].isNotEmpty()) split[0] else defaultNamespace
            val path = if (split.size == 2) split[1] else split[0]
            if (path.isEmpty()) {
                throw SandboxException(
                    code = DiagnosticCode.INPUT_FORMAT,
                    message = "Resource location path is empty: '$value'",
                )
            }
            return try {
                ResourceLocation(namespace, path)
            } catch (error: IllegalArgumentException) {
                throw SandboxException(
                    code = DiagnosticCode.INPUT_FORMAT,
                    message = error.message ?: "Invalid resource location: '$value'",
                )
            }
        }
    }
}
