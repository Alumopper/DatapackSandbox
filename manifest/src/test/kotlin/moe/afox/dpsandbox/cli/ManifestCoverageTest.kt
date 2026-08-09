package moe.afox.dpsandbox.cli

import moe.afox.dpsandbox.core.DiagnosticCode
import moe.afox.dpsandbox.core.SandboxException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManifestCoverageTest : ManifestRunnerTestSupport() {
    @Test
    fun `manifest coverage thresholds fail with line and function details`() {
        val dir = Files.createTempDirectory("dps-manifest-coverage")
        val pack =
            writeCoveragePack(
                dir.resolve("pack"),
                mapOf("main" to "say first\nsay second", "unused" to "say unused"),
            )
        val manifest = dir.resolve("coverage.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.2",
              "packs": ["${jsonPath(pack)}"],
              "coverage": {
                "minimumLine": 70,
                "minimumFunction": 60,
                "include": "demo:*"
              },
              "steps": [
                { "function": "demo:main" }
              ],
              "assertions": []
            }
            """.trimIndent(),
        )

        val result = ManifestRunner.run(manifest)

        assertFalse(result.passed)
        assertTrue(result.messages.any { "66.67%" in it && "2/3 lines" in it }, result.messages.toString())
        assertTrue(result.messages.any { "50.00%" in it && "1/2 functions" in it }, result.messages.toString())
        val coverage = result.attempts.single().coverage ?: error("missing coverage report")
        assertEquals(2, coverage.coveredLines)
        assertEquals(3, coverage.totalLines)
        assertEquals(1, coverage.coveredFunctions)
        assertEquals(2, coverage.totalFunctions)
        assertEquals(listOf("demo:main", "demo:unused"), coverage.functions.map { it.id.toString() })
    }

    @Test
    fun `manifest coverage include and exclude select active functions`() {
        val dir = Files.createTempDirectory("dps-manifest-coverage-filter")
        val pack =
            writeCoveragePack(
                dir.resolve("pack"),
                mapOf(
                    "main" to "say covered",
                    "generated_helper" to "say generated",
                    "unused" to "say unused",
                ),
            )
        val manifest = dir.resolve("coverage-filter.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.2",
              "packs": ["${jsonPath(pack)}"],
              "coverage": {
                "minimumLine": 100,
                "minimumFunction": 100,
                "include": ["demo:main", "demo:generated_*"],
                "exclude": "demo:generated_*"
              },
              "steps": [
                { "function": "demo:main" }
              ],
              "assertions": []
            }
            """.trimIndent(),
        )

        val result = ManifestRunner.run(manifest)

        assertTrue(result.passed, result.messages.toString())
        val coverage = result.attempts.single().coverage ?: error("missing coverage report")
        assertEquals(listOf("demo:main"), coverage.functions.map { it.id.toString() })
        assertEquals(100.0, coverage.linePercentage)
        assertEquals(100.0, coverage.functionPercentage)
    }

    @Test
    fun `included manifest provides default coverage requirements`() {
        val dir = Files.createTempDirectory("dps-manifest-coverage-include")
        val pack = writeCoveragePack(dir.resolve("pack"), mapOf("main" to "say covered", "unused" to "say unused"))
        val common = dir.resolve("common.dps.json")
        Files.writeString(
            common,
            """
            {
              "version": "26.2",
              "packs": ["${jsonPath(pack)}"],
              "coverage": { "minimumLine": 100, "include": "demo:*" }
            }
            """.trimIndent(),
        )
        val manifest = dir.resolve("case.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "include": "common.dps.json",
              "steps": [{ "function": "demo:main" }],
              "assertions": []
            }
            """.trimIndent(),
        )

        val result = ManifestRunner.run(manifest)

        assertFalse(result.passed)
        assertTrue(result.messages.any { "50.00%" in it && "1/2 lines" in it }, result.messages.toString())
    }

    @Test
    fun `manifest rejects invalid coverage values without schema validation`() {
        val dir = Files.createTempDirectory("dps-manifest-invalid-coverage")
        val pack = writeCoveragePack(dir.resolve("pack"), mapOf("main" to "say covered"))
        val manifest = dir.resolve("invalid.dps.json")
        Files.writeString(
            manifest,
            """
            {
              "version": "26.2",
              "packs": ["${jsonPath(pack)}"],
              "coverage": { "minimumLine": 101 },
              "steps": [{ "function": "demo:main" }]
            }
            """.trimIndent(),
        )

        val error = assertFailsWith<SandboxException> { ManifestRunner.run(manifest) }
        assertEquals(DiagnosticCode.INPUT_FORMAT, error.code)
        assertTrue("minimumLinePercentage must be between 0 and 100" in error.message, error.message)
    }

    private fun writeCoveragePack(
        root: Path,
        functions: Map<String, String>,
    ): Path {
        Files.createDirectories(root)
        Files.writeString(
            root.resolve("pack.mcmeta"),
            """
            {
              "pack": {
                "pack_format": 107.1,
                "description": "coverage test pack"
              }
            }
            """.trimIndent(),
        )
        val functionRoot = root.resolve("data").resolve("demo").resolve("function")
        Files.createDirectories(functionRoot)
        functions.forEach { (name, body) -> Files.writeString(functionRoot.resolve("$name.mcfunction"), body) }
        return root
    }

    private fun jsonPath(path: Path): String =
        path
            .toAbsolutePath()
            .normalize()
            .toString()
            .replace("\\", "\\\\")
}
