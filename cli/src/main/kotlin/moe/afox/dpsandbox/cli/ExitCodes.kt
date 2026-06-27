package moe.afox.dpsandbox.cli

import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.SandboxException

object ExitCodes {
    const val OK = 0
    const val ASSERTION_FAILED = 1
    const val INPUT_FORMAT = 2
    const val UNSUPPORTED_OR_VERSION = 3

    fun forException(error: SandboxException): Int =
        when (error.code) {
            DiagnosticCode.ASSERTION_FAILED -> ASSERTION_FAILED
            DiagnosticCode.INPUT_FORMAT -> INPUT_FORMAT
            DiagnosticCode.UNSUPPORTED_FEATURE,
            DiagnosticCode.VERSION_MISMATCH,
            DiagnosticCode.RESOURCE_NOT_FOUND,
            DiagnosticCode.COMMAND_ERROR,
            DiagnosticCode.MISSING_CONTEXT -> UNSUPPORTED_OR_VERSION
        }
}
