package moe.afox.dpsandbox.cli

import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.SandboxException
import java.nio.charset.StandardCharsets

object ManifestSchema {
    const val RESOURCE_NAME = "dps-manifest.schema.json"

    fun readText(): String {
        val stream = ManifestSchema::class.java.classLoader.getResourceAsStream(RESOURCE_NAME)
            ?: throw SandboxException(DiagnosticCode.INPUT_FORMAT, "Bundled manifest schema resource is missing: $RESOURCE_NAME")
        return stream.use { input -> String(input.readAllBytes(), StandardCharsets.UTF_8) }
    }
}
