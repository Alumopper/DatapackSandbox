package moe.afox.dpsandbox.core

import com.google.gson.JsonObject

internal object FunctionMacroExpander {
    private val argumentPattern = Regex("\\$\\(([^)]+)\\)")

    /** Resolve all substitutions before executing the first line. */
    fun instantiate(profile: VersionProfile, function: DatapackFunction, arguments: JsonObject?): DatapackFunction {
        if (!function.hasMacros) return function
        return function.copy(
            lines =
                function.lines.map { line ->
                    if (line.command.startsWith('$')) line.copy(command = expand(profile, function.id, line, arguments)) else line
                },
        )
    }

    fun expand(
        profile: VersionProfile,
        functionId: ResourceLocation,
        line: FunctionLine,
        arguments: JsonObject?,
    ): String {
        if (!line.command.startsWith('$')) return line.command
        val values =
            arguments ?: throw SandboxException(
                DiagnosticCode.COMMAND_ERROR,
                "Function '$functionId' requires macro arguments",
                line.location,
                profile.id,
            )
        return argumentPattern.replace(line.command.drop(1)) { match ->
            val name = match.groupValues[1]
            val value =
                values.get(name) ?: throw SandboxException(
                    DiagnosticCode.COMMAND_ERROR,
                    "Function '$functionId' macro argument '$name' is missing",
                    line.location,
                    profile.id,
                )
            if (value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else JsonValues.renderCompact(value)
        }
    }
}
