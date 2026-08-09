package moe.afox.dpsandbox.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatapackCoverageTest {
    @Test
    fun `records executable lines nested calls returns and repeated hits`() {
        val sandbox =
            createFunctionSandbox(
                version = "26.2",
                functionSources =
                    listOf(
                        FunctionSource.text(
                            "demo:main",
                            """

                            # setup comment
                            function demo:child
                            function demo:child
                            return 1
                            say unreachable
                            """.trimIndent(),
                        ),
                        FunctionSource.text("demo:child", "say child"),
                        FunctionSource.text("demo:unused", "say unused"),
                        FunctionSource.text("demo:empty", "# no executable commands"),
                    ),
            )
        sandbox.world.commandTraceMode = CommandTraceMode.OFF

        sandbox.runFunction("demo:main")

        val report = sandbox.coverageReport()
        assertEquals(4, report.totalFunctions)
        assertEquals(2, report.coveredFunctions)
        assertEquals(50.0, report.functionPercentage)
        assertEquals(6, report.totalLines)
        assertEquals(4, report.coveredLines)
        assertEquals(66.66666666666667, report.linePercentage)

        val main = report.functions.single { it.id.toString() == "demo:main" }
        assertEquals(1, main.invocations)
        assertEquals(listOf(3, 4, 5, 6), main.lines.map { it.line })
        assertEquals(listOf(1L, 1L, 1L, 0L), main.lines.map { it.hits })
        assertFalse(main.lines.last().covered)

        val child = report.functions.single { it.id.toString() == "demo:child" }
        assertEquals(2, child.invocations)
        assertEquals(2, child.lines.single().hits)
    }

    @Test
    fun `counts a reached command line when execution fails`() {
        val sandbox =
            createFunctionSandbox(
                version = "26.2",
                functionSources =
                    listOf(
                        FunctionSource.text(
                            "demo:failure",
                            """
                            scoreboard players set #value missing 1
                            say unreachable
                            """.trimIndent(),
                        ),
                    ),
            )

        assertFailsWith<SandboxException> { sandbox.runFunction("demo:failure") }

        val function = sandbox.coverageReport().functions.single()
        assertEquals(1, function.invocations)
        assertEquals(listOf(1L, 0L), function.lines.map { it.hits })
    }

    @Test
    fun `filters functions with resource id globs and validates thresholds`() {
        val sandbox =
            createFunctionSandbox(
                version = "26.2",
                functionSources =
                    listOf(
                        FunctionSource.text("demo:public/main", "say public"),
                        FunctionSource.text("demo:generated/helper", "say generated"),
                        FunctionSource.text("library:helper", "say library"),
                    ),
            )
        sandbox.runFunction("demo:public/main")

        val options =
            DatapackCoverageOptions(
                minimumLinePercentage = 100.0,
                minimumFunctionPercentage = 100.0,
                includes = listOf("demo:*"),
                excludes = listOf("*/generated/*", "demo:generated/*"),
            )
        val report = sandbox.coverageReport(options)

        assertEquals(listOf("demo:public/main"), report.functions.map { it.id.toString() })
        assertEquals(100.0, report.linePercentage)
        assertEquals(100.0, report.functionPercentage)
        assertTrue(report.thresholdFailures(options).isEmpty())

        val failing = sandbox.coverageReport(DatapackCoverageOptions(includes = listOf("demo:*")))
        val failures =
            failing.thresholdFailures(
                DatapackCoverageOptions(
                    minimumLinePercentage = 75.0,
                    minimumFunctionPercentage = 75.0,
                ),
            )
        assertEquals(2, failures.size)
        assertTrue(failures[0].contains("50.00%"), failures.toString())
        assertTrue(failures[1].contains("1/2 functions"), failures.toString())
    }

    @Test
    fun `empty selections are fully covered and counters can be reset`() {
        val sandbox =
            createFunctionSandbox(
                version = "26.2",
                functionSources = listOf(FunctionSource.text("demo:main", "say once")),
            )
        sandbox.runFunction("demo:main")
        sandbox.resetCoverage()

        val reset = sandbox.coverageReport()
        assertEquals(0, reset.coveredLines)
        assertEquals(0, reset.coveredFunctions)

        val empty = sandbox.coverageReport(DatapackCoverageOptions(includes = listOf("missing:*")))
        assertEquals(0, empty.totalLines)
        assertEquals(0, empty.totalFunctions)
        assertEquals(100.0, empty.linePercentage)
        assertEquals(100.0, empty.functionPercentage)
    }

    @Test
    fun `rejects invalid coverage percentages and blank patterns`() {
        assertFailsWith<IllegalArgumentException> { DatapackCoverageOptions(minimumLinePercentage = -0.1) }
        assertFailsWith<IllegalArgumentException> { DatapackCoverageOptions(minimumFunctionPercentage = 100.1) }
        assertFailsWith<IllegalArgumentException> { DatapackCoverageOptions(includes = listOf("")) }
        assertFailsWith<IllegalArgumentException> { DatapackCoverageOptions(excludes = listOf(" ")) }
    }
}
