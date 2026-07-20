package moe.afox.dpsandbox.cli

import moe.afox.dpsandbox.core.BlockPos
import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.PlayerEvent
import moe.afox.dpsandbox.core.PlayerEvents
import moe.afox.dpsandbox.core.SandboxException

internal fun parsePlayerEventText(
    raw: String,
    label: String,
): PlayerEvent {
    val args = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return parsePlayerEventArgs(args, label)
}

internal fun parsePlayerEventArgs(
    args: List<String>,
    label: String,
): PlayerEvent {
    if (args.getOrNull(0) != "player" || args.size < 3) {
        throw SandboxException(DiagnosticCode.INPUT_FORMAT, playerEventUsage(label))
    }
    val playerName = args[1].trim()
    val eventType = args[2].trim()
    if (playerName.isEmpty() || eventType.isEmpty()) {
        throw SandboxException(DiagnosticCode.INPUT_FORMAT, playerEventUsage(label))
    }
    val normalizedType = eventType.replace('-', '_')
    val (detail, blockPos) = parsePlayerEventTail(normalizedType, args, label)
    return try {
        PlayerEvents.shorthand(playerName, normalizedType, args.getOrNull(3), detail).copy(blockPos = blockPos)
    } catch (error: IllegalArgumentException) {
        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label invalid player event: ${error.message}", cause = error)
    }
}

private fun parsePlayerEventTail(
    type: String,
    args: List<String>,
    label: String,
): Pair<String?, BlockPos?> {
    val tail = args.drop(4)
    if (!type.contains("block")) {
        if (tail.size > 1) throw SandboxException(DiagnosticCode.INPUT_FORMAT, playerEventUsage(label))
        return args.getOrNull(4) to null
    }
    if (tail.isEmpty()) return null to null
    parseBlockPos(tail, label)?.let { return null to it }
    if (tail.size == 1) return tail.single() to null
    throw SandboxException(DiagnosticCode.INPUT_FORMAT, playerEventUsage(label))
}

private fun parseBlockPos(
    tail: List<String>,
    label: String,
): BlockPos? {
    if (tail.size == 3 && tail.all { it.toIntOrNull() != null }) {
        return BlockPos(tail[0].toInt(), tail[1].toInt(), tail[2].toInt())
    }
    if (tail.size != 1) return null

    val token = tail.single()
    val value =
        when {
            token.startsWith("@") -> token.removePrefix("@")
            token.startsWith("pos=") -> token.removePrefix("pos=")
            token.startsWith("blockPos=") -> token.removePrefix("blockPos=")
            token.startsWith("block_pos=") -> token.removePrefix("block_pos=")
            token.startsWith("block-pos=") -> token.removePrefix("block-pos=")
            "," in token -> token
            else -> return null
        }
    val pieces = value.split(",")
    if (pieces.size != 3 || pieces.any { it.toIntOrNull() == null }) {
        throw SandboxException(DiagnosticCode.INPUT_FORMAT, "$label block position must be x,y,z or three integer tokens")
    }
    return BlockPos(pieces[0].toInt(), pieces[1].toInt(), pieces[2].toInt())
}

private fun playerEventUsage(label: String): String = "Usage: $label player <name> <type> [id] [detail/action|x y z|pos=x,y,z]"
