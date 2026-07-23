package moe.afox.dpsandbox.engine

/** Normalizes untrusted browser import paths into a portable `/` separated form. */
object VirtualPath {
    fun normalize(raw: String): String {
        require(raw.isNotBlank()) { "Imported path must not be blank" }
        require('\u0000' !in raw) { "Imported path contains a NUL byte" }
        val portable = raw.replace('\\', '/')
        require(!portable.startsWith('/')) { "Absolute imported paths are not allowed: $raw" }
        require(!DRIVE_PREFIX.matches(portable)) { "Drive-qualified imported paths are not allowed: $raw" }
        val parts = portable.split('/').filter(String::isNotEmpty)
        require(parts.isNotEmpty()) { "Imported path must name a file" }
        require(parts.none { it == "." || it == ".." }) { "Imported path traversal is not allowed: $raw" }
        require(parts.none { it.any(Char::isISOControl) }) { "Imported path contains control characters: $raw" }
        return parts.joinToString("/")
    }

    fun validateUnique(paths: Iterable<String>): List<String> {
        val seen = mutableSetOf<String>()
        return paths.map { raw ->
            normalize(raw).also { normalized ->
                require(seen.add(normalized)) { "Duplicate imported path: $normalized" }
            }
        }
    }

    private val DRIVE_PREFIX = Regex("^[A-Za-z]:($|/.*)")
}
