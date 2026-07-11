# Getting Started

This guide gets you to the first useful run: choose the right entry point, execute a minimal test, and know where to look when it fails. Detailed parameters and runtime boundaries live in the reference pages.

## Choose an Entry Point

| Goal | Recommended entry point | Read next |
| --- | --- | --- |
| Write IDE or CI unit tests for datapack behavior | `SandboxQuickTest` | [Code Test API](/en/guide/code-test-api) |
| Check a full datapack with resources, commands, and assertions | `.dps.json` manifest | [Resource Formats](/en/resources/resource-formats) |
| Run one function or command and inspect output or snapshots | CLI / REPL | [Command Support](/en/runtime/command-support) |
| Verify player interaction triggers advancements, predicates, or function chains | player event | [Player Events](/en/runtime/player-events) |
| Debug pack format, version profile, or command support differences | version profile + warning | [Version Profiles](/en/resources/version-profile) |

If you already have a JVM test suite, start with `SandboxQuickTest.singleFunctionText(...)`. It needs no complete datapack directory and confirms the runtime, version profile, and assertion chain in a few lines.

To try the CLI immediately, build the fat jar and open the interactive REPL:

```powershell
.\gradlew.bat :cli:fatJar
java -jar .\cli\build\libs\datapack-sandbox-cli.jar repl --version 26.2 --pack .\my-pack
```

## Minimal Dependency

JVM projects should depend on the `core` artifact, not the CLI fat jar.

```kotlin
repositories {
    mavenCentral()
    maven("https://libraries.minecraft.net")
}

dependencies {
    testImplementation("moe.afox.dpsandbox:core:1.0.0")
}
```

The project is built with a Java 25 toolchain. If Gradle reports a toolchain or classfile-version error, check the local JDK before debugging the datapack.

## First Test

```kotlin
import moe.afox.dpsandbox.core.SandboxQuickTest

class MyDatapackTest {
    @Test
    fun scoreboardCanBeAsserted() {
        SandboxQuickTest.singleFunctionText(
            """
            scoreboard objectives add runs dummy
            scoreboard players set #unit runs 1
            """.trimIndent(),
            version = "26.2",
        )
            .function()
            .assertScore("#unit", "runs", 1)
            .requirePassed()
    }
}
```

This example:

1. Creates a temporary datapack with a single `.mcfunction`.
2. Uses the `26.2` version profile for pack metadata and resource formats.
3. Runs the default function.
4. Asserts that `#unit runs` equals `1`.

Use `report()` instead of `requirePassed()` when you want to inspect failures, output events, traces, and snapshot diffs without throwing immediately.

## From One Function to a Full Pack

After the first test passes, split coverage into three layers:

| Layer | Purpose | Typical assertions |
| --- | --- | --- |
| Single-function tests | Validate a local command sequence | score, storage, output, trace |
| Fixture-world tests | Validate players, blocks, entities, and predefined world state | player, block, entity, world |
| Manifest regression tests | Validate full-pack loading and observable behavior | command status, resource loading, snapshot diff |

These layers are complementary. Single-function tests are fastest to diagnose, manifest tests cover the most surface area, and fixture tests are useful for integration behavior between them.

## Suggested Test Layout

```text
src/test/kotlin/
  mypack/
    AdvancementTest.kt
    LootTableTest.kt
    RuntimeSmokeTest.kt

src/test/resources/
  datapacks/
    mypack/
      pack.mcmeta
      data/...
  worlds/
    minimal-player.json
```

If you test generated datapacks, write generated output to `build/generated-datapacks/...` and load that directory from QuickTest or a manifest. When CI fails, keep the generated directory as an artifact.

## Treat Format Mismatches as Warnings First

Datapack Sandbox uses version profiles to evaluate `pack_format`, `supported_formats`, `min_format`, and `max_format`. A mismatch should usually be a warning while the sandbox can continue, because developers often want to validate command and resource behavior before finalizing metadata.

Format values may be integers or arrays:

| Syntax | Meaning |
| --- | --- |
| `94` | `94` |
| `[94]` | `94` |
| `[104, 1]` | `104.1` |

If you see a message such as `expected 107.1`, first verify that the requested `version` is correct, then check whether `pack.mcmeta` really needs an updated format.

## Next Steps

- For test design, read [Testing Patterns](/en/guide/testing-patterns).
- For confusing failures, read [Troubleshooting](/en/guide/troubleshooting).
- For the full API surface, read [Code Test API](/en/guide/code-test-api).
- For command coverage, read [Command Support](/en/runtime/command-support).
