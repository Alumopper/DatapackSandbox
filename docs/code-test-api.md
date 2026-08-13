# QuickTest Code Testing Overview

## When to use this page

Use `testkit` when datapack behavior belongs in an existing Kotlin/Java test suite, or when you want a fluent API for single functions, complete packs, and version matrices. This page covers dependencies, entry points, lifecycle, and reports; fixtures and assertions now have dedicated references.

## Prerequisites

The runtime uses Java 25. JVM tests depend on `testkit`, which brings in `core` transitively. Do not depend on the CLI fat jar.

```kotlin
repositories {
    maven("https://nexus.mcfpp.top/repository/maven-releases/")
    mavenCentral()
    maven("https://libraries.minecraft.net")
}

dependencies {
    testImplementation("moe.afox.dpsandbox:testkit:1.1.0")
}
```

## Minimal runnable example

```kotlin
import moe.afox.dpsandbox.core.SandboxQuickTest

@Test
fun generatedFunctionKeepsItsContract() {
    SandboxQuickTest.singleFunctionText(
        """
        scoreboard objectives add runs dummy
        scoreboard players set #quick runs 1
        say quicktest ready
        """.trimIndent(),
        version = "26.2",
    )
        .function()
        .assertScore("#quick", "runs", 1)
        .assertOutput(command = "say", contains = "quicktest ready", count = 1)
        .requirePassed()
}
```

## Full capabilities

### API entry points

| Entry | Use |
| --- | --- |
| `SandboxQuickTest.create(packs, version, ...)` | Complete datapacks |
| `singleFunction(file, version, ...)` | One `.mcfunction` file |
| `singleFunctionText(text, version, ...)` | In-memory generated command text |
| `functions(functionSources, version, ...)` | Multiple file/text functions plus dependency packs |
| `matrix(packsByVersion, ...)` | Multi-profile matrix |

The default profile is the latest built-in profile, currently `26.2`, but reproducible tests should pass a version explicitly. Complete packs load in list order; later packs and synthetic functions have higher priority.

### Lifecycle

Fixture → behavior → assertion → report is the recommended order. Behavior methods `load()`, `ticks(count)`, `function(id)`, `function()`, `command(text)`, and `event(...)` mutate the sandbox immediately. Assertions collect failures without interrupting the fluent chain.

### Reports

`report()` returns `SandboxQuickTestReport` with passed, failures, outputs, traces, playerEventTraces, snapshotDiffs, resourceSummary, and snapshot. `requirePassed()` throws `SandboxQuickTestAssertionError` on failure for JUnit/Kotlin test integration. `coverageReport()` and `assertCoverage()` expose accumulated line/function coverage.

For live chat debugging, call `printChatOutput()` before behavior methods. It prints only future chat-channel events while recording and assertions remain active.

## Migrated legacy sections

### Predefined World State

The former world fixture, structure, entity, player, and Java-save import catalog moved to [QuickTest Fixtures](/en/reference/quicktest-fixtures). This heading remains so old deep links still lead to the authoritative page.

### Assertion Semantics

The former score/storage/world/entity/output/trace/event-trace/snapshot/coverage catalog moved to [QuickTest Assertions](/en/reference/quicktest-assertions).

### Lower-Level API

Use [Core API Embedding](/en/reference/core-api) when you need to own `DatapackSandbox` directly.

## Limitations

- QuickTest and Core use a sparse world model and cannot replace every vanilla-server integration test.
- A scenario is mutable and should not be shared across test threads.
- `api/testkit.api` and `api/core.api` remain the public ABI drift baselines; the project does not generate a Dokka site.

## Related pages

- [QuickTest Fixtures](/en/reference/quicktest-fixtures)
- [QuickTest Assertions](/en/reference/quicktest-assertions)
- [Core API](/en/reference/core-api)
- [Testing Patterns](/en/guide/testing-patterns)
