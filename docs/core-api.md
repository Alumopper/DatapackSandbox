# Core API Embedding Reference

## When to use this page

Depend on `core` directly when your JVM application must own a long-lived `DatapackSandbox`: for example, a custom editor, a build plugin, a test harness that does not use JUnit, or a service that needs precise control over load, tick, function, and command execution. Use [QuickTest](/en/guide/code-test-api) when its test lifecycle already fits; use the [CLI](/en/reference/cli) when process isolation and files are more useful than in-process objects.

## Prerequisites

```kotlin
dependencies {
    implementation("moe.afox.dpsandbox:core:1.1.0")
}
```

Core requires Java 25. Pick a Minecraft profile explicitly; `26.2` is the current default profile, but an integration should persist the profile used by each document or test. `api/core.api` is the checked JVM ABI baseline. It is not a generated symbol guide, so this page concentrates on the public task-level entry points.

## Minimal runnable example

```kotlin
import moe.afox.dpsandbox.core.CommandTraceMode
import moe.afox.dpsandbox.core.SandboxWorldSetup
import moe.afox.dpsandbox.core.SnapshotDiff
import moe.afox.dpsandbox.core.createFunctionSandboxFromString

val sandbox = createFunctionSandboxFromString(
    version = "26.2",
    functionText = """
        scoreboard objectives add runs dummy
        scoreboard players add #core runs 1
        say core finished
    """.trimIndent(),
)

SandboxWorldSetup()
    .block(0, 64, 0, "minecraft:stone")
    .applyTo(sandbox.world, sandbox.profile)
sandbox.world.commandTraceMode = CommandTraceMode.FULL

val before = sandbox.snapshotJson()
val result = sandbox.runFunction("sandbox:main")
val after = sandbox.snapshotJson()

check(result.success)
check(sandbox.world.getScore("#core", "runs") == 1)
val stateChanges = SnapshotDiff.stateDiff(before, after)
println("commands=${result.commandsExecuted}, changes=${stateChanges.size}")
```

The single-string factory exposes the source as `sandbox:main` unless `functionId` is overridden. The default player is `Steve`; pass `defaultPlayerName = null` when an empty player set is part of the test.

## Choose a factory

| Factory | Use it for | Important behavior |
| --- | --- | --- |
| `createSandbox(version, packs, ...)` | One or more real datapack directories/ZIPs | `packs` are loaded in pack-priority order |
| `createFunctionSandbox(version, functionSources, ...)` | Several synthetic functions without a pack tree | Accepts `FunctionSource.text` and `FunctionSource.file` |
| `createFunctionSandbox(version, packs, functionSources, ...)` | Real dependencies plus small test functions | Synthetic functions overlay the dependency packs |
| `createFunctionSandbox(version, functionFile, ...)` | One `.mcfunction` on disk | Assigns the file a temporary resource id |
| `createFunctionSandboxFromString(...)` | One focused in-memory scenario | Shortest route to a runnable sandbox |

Every factory also accepts a preconfigured `SandboxWorld`, an optional default player, an `UnsupportedFeatureMode`, and `SandboxLimits`. Factory construction loads and validates resources; create a new sandbox or use an integration-specific reload flow when pack files change.

## Prepare modeled world state

`SandboxWorldSetup` is the safest way to describe a fixture before execution. It supports time, seed and random sequences, weather and difficulty, world spawn/border, forced chunks, biomes, blocks and regions, structures, entities, players, inventory/effects/advancements, scores, storage, gamerules, teams, bossbars, and bounded save imports.

```kotlin
SandboxWorldSetup()
    .player("Alex", 0.5, 65.0, 0.5, "minecraft:overworld")
    .entity("minecraft:pig", 2.0, 64.0, 2.0, tags = listOf("test_target"))
    .block(0, 64, 0, "minecraft:chest", nbt = "{Items:[]}")
    .storage("demo:state", "{phase:1}")
    .score("Alex", "points", 5)
    .applyTo(sandbox.world, sandbox.profile)
```

Fixtures are applied to the mutable world; they do not add datapack resources. `importSave` intentionally imports selected chunks or a bounded block range, not a complete running server. Player data, lighting, POI data, and scheduled vanilla ticks are outside that import boundary. A single region fixture is capped at 32,768 blocks.

## Execution lifecycle

| Operation | Effect | Result |
| --- | --- | --- |
| `runLoad()` | Runs every function in `#minecraft:load` | Commands executed |
| `runTicks(count)` | Advances time, due schedules, `#minecraft:tick`, and player tick advancement events | Commands executed |
| `runFunction(id, context)` | Runs one loaded function, including nested calls and macros | Commands, return value, success |
| `executeCommand(text, location, context)` | Executes one raw command; a leading slash is accepted | Commands and success |
| `checkCommand` / `checkCommands` | Validates against an isolated copy of the current world | Structured validity and diagnostic data |
| `handlePlayerEvent(event)` | Applies the modeled player action and advancement criteria | Advancement updates |
| `generateLoot(...)` | Evaluates a loaded loot table with an explicit context and seed | Deterministic loot result |

`runTicks` does not simulate entity AI, physics, redstone, or block updates. It advances only the lifecycle that the clean-room runtime models. `checkCommands` uses one preview world, so a valid earlier line can prepare state for a later line, while the live world, outputs, traces, checkpoints, coverage budget, and resources remain unchanged.

### Execution context

Use `ExecutionContext` when relative coordinates, selectors, executor identity, rotation, anchor, or dimension matter:

```kotlin
import moe.afox.dpsandbox.core.ExecutionContext
import moe.afox.dpsandbox.core.Position
import moe.afox.dpsandbox.core.ResourceLocation

val alex = sandbox.world.requirePlayer("Alex")
sandbox.runFunction(
    "demo:relative",
    ExecutionContext(
        entity = alex,
        position = Position(10.0, 70.0, -4.0),
        dimension = ResourceLocation.parse("minecraft:overworld"),
        yaw = 90.0,
        pitch = 0.0,
        anchor = "eyes",
    ),
)
```

The predicate engine is injected by the sandbox. Applications normally leave `predicateEngine` unset.

## Inspect state, output, and resources

`sandbox.world` exposes modeled players, entities, blocks, scoreboards, storage, schedules, outputs, command traces, player-event traces, bossbars, gamerules, teams, random sequences, forced chunks, biomes, and world border state. Prefer its public operations such as `getScore`, `setScore`, `storage`, `block`, `createPlayer`, and `snapshot` where one exists.

`sandbox.datapack` exposes functions, loot tables, predicates, advancements, tags, raw resources, warnings, and the resource index. The resource index records active and overridden entries, which is useful when explaining pack priority.

Output events are retained in `world.outputs` and can also be streamed with `addOutputListener` / `removeOutputListener`. An output contains tick, command, channel, targets, normalized text, optional raw text, styled segments, structured payload, and command source. See [Reports and Observability](/en/reference/reports-observability) before designing a log format around it.

## Snapshots, diffs, traces, and coverage

`snapshotJson()` and `snapshotString()` return stable serialized modeled state plus the active version. Use `SnapshotDiff.diff(before, after)` for all changes or `SnapshotDiff.stateDiff(...)` when trace bookkeeping should not appear in the comparison. Diff kinds are `ADDED`, `REMOVED`, and `CHANGED`, and paths use JSON Pointer.

Set `world.commandTraceMode` to:

- `OFF` for no command events;
- the light trace mode when command/source/result is enough;
- `FULL` when each command must include output events and state diffs.

Full traces snapshot state around every command and therefore cost more memory and CPU. Coverage is cumulative across operations:

```kotlin
val coverage = sandbox.coverageReport()
println("functions=${coverage.functions.size}")
sandbox.resetCoverage()
```

## Checkpoints and cancellation

`saveCheckpoint`, `restoreCheckpoint`, `deleteCheckpoint`, and `checkpointNames` manage up to 32 named world copies. Names are 1–64 ASCII letters, digits, `.`, `_`, or `-`. Checkpoint operations are allowed only at command boundaries. They include modeled world state, but not datapack resources or monotonic execution budgets; restoring does not consume the checkpoint.

For a long operation, another control path may call `requestExecutionCancellation()`. Cancellation is cooperative at command and tick boundaries and throws `EXECUTION_INTERRUPTED`; completed commands are not rolled back. Call `clearExecutionCancellation()` before deliberately reusing a cancelled sandbox.

## Diagnostics and safety policy

Runtime failures use `SandboxException`. The public diagnostic codes distinguish input format, version mismatch, missing resources, unsupported features, command errors, interruption, assertion failure, and missing execution context. Preserve `code`, `message`, `location`, `version`, and `command` instead of flattening everything to a string.

`UnsupportedFeatureMode` controls a command root known to the selected version but not modeled completely:

- `WARN` records a warning output and continues;
- `IGNORE` continues silently;
- `ERROR` throws `UNSUPPORTED_FEATURE`.

Unknown command roots are input errors regardless of this mode. For regression tests and CI, `ERROR` usually exposes gaps earlier.

Default `SandboxLimits` are 100,000 commands, function depth 64, 100,000 ticks per `runTicks`, 100,000 retained outputs, and a 10,000,000-byte snapshot. Command count is normally lifetime-wide. Set `resetCommandBudgetPerOperation = true` only for a controlled long-lived interactive session where each top-level operation needs a fresh budget.

## Concurrency and ownership

`DatapackSandbox` and `SandboxWorld` are mutable and are not a multi-threaded server abstraction. Give each concurrent document/test its own sandbox, or serialize all mutations behind one owner. Rendering captures state without mutation, but the runtime should still not be modified concurrently while a capture is in progress.

## Limitations

- Core models datapack-visible behavior; it is not a vanilla server and does not provide networking, full world generation, chunk simulation, AI, or vanilla thread scheduling.
- Profiles describe supported version/resource/command behavior, not a bundled Minecraft server or client.
- A stable snapshot is a sandbox contract, not a vanilla level-save format.
- Integrations should use public factories and models rather than internal classes or CLI implementation details.

## Related pages

- [QuickTest Overview](/en/guide/code-test-api)
- [World Fixtures](/en/reference/quicktest-fixtures)
- [World Model](/en/runtime/world-model)
- [Command Support](/en/runtime/command-support)
- [Renderer API](/en/reference/renderer-api)
- [Reports and Observability](/en/reference/reports-observability)
