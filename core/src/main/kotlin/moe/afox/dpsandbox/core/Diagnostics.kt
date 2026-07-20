package moe.afox.dpsandbox.core

enum class DiagnosticCode {
    INPUT_FORMAT,
    VERSION_MISMATCH,
    RESOURCE_NOT_FOUND,
    UNSUPPORTED_FEATURE,
    COMMAND_ERROR,
    EXECUTION_INTERRUPTED,
    ASSERTION_FAILED,
    MISSING_CONTEXT,
}

data class SourceLocation(
    val file: String? = null,
    val line: Int? = null,
    val command: String? = null,
) {
    override fun toString(): String =
        buildString {
            if (file != null) append(file)
            if (line != null) append(":").append(line)
            if (command != null) append(" -> ").append(command)
        }.ifBlank { "<unknown>" }
}

open class SandboxException(
    val code: DiagnosticCode,
    override val message: String,
    val location: SourceLocation? = null,
    val version: String? = null,
    val command: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    fun render(): String =
        buildString {
            append(code).append(": ").append(message)
            if (version != null) append(System.lineSeparator()).append("version: ").append(version)
            if (location != null) append(System.lineSeparator()).append("at ").append(location)
            if (command != null && location?.command == null) {
                append(System.lineSeparator()).append("command: ").append(command)
            }
        }
}

fun unsupportedFeature(
    message: String,
    version: String? = null,
    location: SourceLocation? = null,
    command: String? = null,
): Nothing =
    throw SandboxException(
        code = DiagnosticCode.UNSUPPORTED_FEATURE,
        message = message,
        location = location,
        version = version,
        command = command,
    )

fun commandError(
    message: String,
    location: SourceLocation? = null,
    command: String? = null,
): Nothing =
    throw SandboxException(
        code = DiagnosticCode.COMMAND_ERROR,
        message = message,
        location = location,
        command = command,
    )
