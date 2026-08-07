package moe.afox.dpsandbox.core

internal fun uuidFromLongs(
    mostSignificantBits: Long,
    leastSignificantBits: Long,
): String {
    val most =
        java.lang.Long
            .toHexString(mostSignificantBits)
            .padStart(16, '0')
    val least =
        java.lang.Long
            .toHexString(leastSignificantBits)
            .padStart(16, '0')
    return buildString(36) {
        append(most, 0, 8)
        append('-')
        append(most, 8, 12)
        append('-')
        append(most, 12, 16)
        append('-')
        append(least, 0, 4)
        append('-')
        append(least, 4, 16)
    }
}

/**
 * Normalizes the five-part UUID spelling accepted by Java's UUID parser without
 * depending on platform-specific `java.util.UUID.fromString` behavior. Mojang
 * commands and generated datapacks commonly use compact zero UUIDs such as
 * `0-0-0-0-0` for scratch entities.
 */
internal fun normalizeUuid(raw: String): String? {
    // Reject before split(): a command token can be user-controlled, and a
    // multi-megabyte string of hyphens would otherwise allocate one part per byte.
    if (raw.length !in MIN_UUID_TEXT_LENGTH..MAX_UUID_TEXT_LENGTH) return null
    val widths = intArrayOf(8, 4, 4, 4, 12)
    val parts = raw.split('-')
    if (parts.size != widths.size) return null
    val normalized =
        parts.mapIndexed { index, part ->
            if (part.isEmpty() || part.length > widths[index] || part.any { it.digitToIntOrNull(16) == null }) return null
            part.lowercase().padStart(widths[index], '0')
        }
    return normalized.joinToString("-")
}

internal fun uuidIntArray(raw: String): IntArray? {
    if (raw.length !in COMPACT_UUID_LENGTH..MAX_UUID_TEXT_LENGTH) return null
    val compact = raw.replace("-", "")
    if (compact.length != 32 || compact.any { it.digitToIntOrNull(16) == null }) return null
    return IntArray(4) { index -> compact.substring(index * 8, index * 8 + 8).toLong(16).toInt() }
}

private const val MIN_UUID_TEXT_LENGTH = 9
private const val COMPACT_UUID_LENGTH = 32
private const val MAX_UUID_TEXT_LENGTH = 36
