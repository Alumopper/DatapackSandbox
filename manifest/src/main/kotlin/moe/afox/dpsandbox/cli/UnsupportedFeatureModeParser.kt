package moe.afox.dpsandbox.cli

import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.SandboxException
import moe.afox.dpsandbox.core.UnsupportedFeatureMode

fun unsupportedFeatureMode(value: String): UnsupportedFeatureMode =
    when (value.lowercase()) {
        "warn", "warning" -> UnsupportedFeatureMode.WARN
        "ignore", "silent" -> UnsupportedFeatureMode.IGNORE
        "error", "strict" -> UnsupportedFeatureMode.ERROR
        else -> throw SandboxException(
            DiagnosticCode.INPUT_FORMAT,
            "Unsupported --unsupported mode '$value'; expected warn, ignore, or error",
        )
    }
