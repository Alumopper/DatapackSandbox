package moe.afox.dpsandbox.core

import com.google.gson.JsonObject

internal object FunctionMacroExpander {
    private val argumentPattern = Regex("\\$\\(([^)]+)\\)")

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
