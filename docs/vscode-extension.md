# VS Code Extension

## When to use this page

Use the extension for highlighting, diagnostics, completion, hover, and function navigation as soon as a datapack opens, and to run `.mcfunction` files, execute `.dps.json` manifests, set trace breakpoints, or inspect an active world without leaving the editor.

## Prerequisites

You need VS Code 1.95 or newer and Java 25. The distributable VSIX already bundles the CLI JAR.

## Minimal runnable example

Install `Alumopper.datapack-sandbox-vscode` from Marketplace, open a datapack folder containing `pack.mcmeta`, and open any `.mcfunction`. Highlighting appears immediately and the JVM language session loads the pack in the background; **Start sandbox** is not required.

## Full capabilities

Datapack Sandbox for VS Code brings running, testing, trace debugging, and sandbox inspection into the editor.

## `.mcfunction` language support

The extension has two language layers. A TextMate grammar immediately recognizes comments, command roots, commands after `execute ... run`, selectors, coordinates, resource locations, strings, numbers, SNBT/JSON punctuation, and function macros. A separate DSB JVM `serve` session then supplies profile-aware diagnostics, completion, and hover. Highlighting does not wait for Java, and the smart language session is independent of the persistent sandbox shown in the status bar.

This provides the same category of editing experience as Spyglass, but it neither embeds nor proxies the Spyglass language server. DSB uses its own command catalog, completion engine, datapack loader, and `checkCommands` validation, keeping editor results aligned with the Minecraft profiles, behavior levels, and resource priority that the DSB JVM can actually execute. The repository's `@spyglassmc/mcdoc` dependency remains limited to build-time vanilla NBT schema generation.

For an open `.mcfunction`, the extension provides:

- Debounced whole-document validation with Problems attached to physical lines. Backslash continuations are joined with the same rules used by the runtime.
- Completion for command roots, subcommands, selectors, blocks, items, entity types, functions, and resources loaded from the current pack. Accepting an item that inserts a space, `:`, `=`, `{`, or `[` immediately opens the next completion stage; empty `{}`/`[]` templates leave the caret inside. Suggestions identify the DSB behavior level and active profile.
- Command usage/profile hover, selector semantics, and datapack-resource resolution hover.
- **Go to Definition** (including Ctrl+click) for literal resource IDs resolved by the active resource index: functions/tags, loot tables, predicates, advancements, recipes, item modifiers, and other file-backed datapack resources in directory packs.
- An error and preferred quick fix for a leading `/`, which is not valid in an `.mcfunction` command line. Macro commands still receive lexical support and navigation, but semantic validation skips lines containing `$(...)` because call arguments are unavailable in the editor.

The language session first honors `datapackSandbox.defaultVersion`. When it is empty, the extension reads the nearest pack's `pack.mcmeta`, matches `pack_format` to the newest compatible built-in profile, and otherwise falls back to the CLI's canonical default. Saving, creating, deleting, or renaming a function or datapack JSON resource reloads the existing JVM session and refreshes its resource index; changing `pack.mcmeta` rebuilds the session because the profile may have changed. ZIP packs participate in completion and validation, but definition navigation cannot open entries inside a ZIP.

## Install

The extension requires VS Code 1.95 or newer and Java 25. Marketplace release `0.4.1` and the distributable VSIX bundle the Datapack Sandbox CLI JAR, so users do not need to clone the repository, run Gradle, or configure `cliJarPath`. All sandbox execution, validation, checkpoints, and rendering remain in the JVM JAR; the extension does not use the browser runtime.

Install the public Marketplace extension:

```powershell
code --install-extension Alumopper.datapack-sandbox-vscode
```

Or open [Datapack Sandbox in VS Code Marketplace](https://marketplace.visualstudio.com/items?itemName=Alumopper.datapack-sandbox-vscode) and choose **Install**. Marketplace installation receives normal VS Code extension updates.

For an offline or pinned installation, download the VSIX from the matching [GitHub release](https://github.com/Alumopper/DatapackSandbox/releases/tag/1.1.0), choose **Install from VSIX...**, and select:

```text
datapack-sandbox-vscode.vsix
```

The equivalent offline terminal command is:

```powershell
code --install-extension .\datapack-sandbox-vscode.vsix
```

Set `datapackSandbox.javaPath` when Java 25 is not available on `PATH`.

## Quick Start

1. Open a datapack workspace and a `.mcfunction`; confirm highlighting, Problems, completion, and hover.
2. Click **DPS** in the status bar when you need an executable persistent world.
3. Confirm the Minecraft profile and datapack paths, then choose **Start sandbox**.
4. Enter a command. The panel provides completions based on the active sandbox and checks the command in an isolated copy that does not mutate the active world.
5. Use the Inspector to browse coverage, rich output, command and player-event traces, snapshots, resources, players, entities, scores, and diagnostics. Event history is fetched incrementally from the JAR.
6. Use **Save point** and **Return** for reusable world checkpoints, **Render PNG** for the JVM renderer, **Reset coverage** to begin a new measurement window, and **Interrupt** to stop a long execution at a command boundary.

Use `↑` and `↓` to move through suggestions and `Tab` or `Enter` to accept one. Inspector JSON is expandable, and traces with source locations can open the corresponding `.mcfunction` line.

The profile selector is populated from the bundled CLI's canonical profile registry. Starting a sandbox from the Command Palette presents the same dynamically reported list.

## Use the Jupyter Notebook kernel in VS Code

Install the Microsoft **Python** and **Jupyter** extensions in addition to this
extension. Select the Python interpreter where the Datapack Sandbox wheel and
kernelspec were installed, open `examples/jupyter/datapack-sandbox-demo.ipynb`,
and choose **Datapack Sandbox (MCFunction)** from **Select Kernel**. The
Notebook kernel keeps one sandbox world across cells and displays an inline PNG
after each successful MCF cell. Spyglass-style editing and diagnostics apply to
standalone `.mcfunction` files; they are not injected into Notebook cells.

## Temporary and Active Sandboxes

| Mode | Best for | State lifetime |
| --- | --- | --- |
| Temporary sandbox | Normal Run, Debug, and isolated tests | New sandbox for every execution |
| Active sandbox | Interactive commands and shared-world debugging | Preserved until stopped or reset |

Normal **Run** and **Debug** use temporary sandboxes by default. Set `datapackSandbox.defaultExecutionTarget` to `active` only when persistent state is preferred.

Use these Command Palette entries to control the active sandbox:

- `Datapack Sandbox: Start Sandbox`
- `Datapack Sandbox: Stop Sandbox`
- `Datapack Sandbox: Open Sandbox Panel`
- `Datapack Sandbox: Run Current Mcfunction in Active Sandbox`
- `Datapack Sandbox: Debug Current File in Active Sandbox`
- `Datapack Sandbox: Save Checkpoint`
- `Datapack Sandbox: Restore Checkpoint`
- `Datapack Sandbox: Render Active Sandbox to PNG`
- `Datapack Sandbox: Show Active Sandbox Coverage`
- `Datapack Sandbox: Reset Active Sandbox Coverage`
- `Datapack Sandbox: Open Loaded Function Source`
- `Datapack Sandbox: Interrupt Active Execution`

The panel displays resolved output text segments and structured payloads. Function commands in trace data can open the effective source selected by datapack priority, including a read-only document for ZIP-backed or synthetic functions.

## Run and Test

Run an open `.mcfunction` from editor actions, CodeLens, or the Command Palette. Open a `.dps.json` manifest to run regular or strict checks.

Test Explorer discovers `**/*.dps.json` and provides four profiles:

- Run in Temporary Sandbox (default)
- Run Strict in Temporary Sandbox
- Run in Active Sandbox
- Run Strict in Active Sandbox

Active profiles preserve world changes in execution order. Prefer the default temporary profile for fully isolated regression tests.

## Trace Debugging

Set breakpoints in a `.mcfunction` and launch **Datapack Sandbox Trace Debug**. The adapter runs to the first breakpoint by default instead of pausing unconditionally on line one.

Trace and Final State scopes are expandable objects containing outputs, diagnostics, snapshot diffs, entities, players, scores, storage, and resource state.

To stop on the first trace event, set `stopOnEntry` explicitly:

```json
{
  "type": "datapack-sandbox",
  "request": "launch",
  "name": "Datapack Sandbox Trace",
  "program": "${file}",
  "sandbox": "temporary",
  "stopOnEntry": true
}
```

## Settings

| Setting | Default | Purpose |
| --- | --- | --- |
| `datapackSandbox.javaPath` | `java` | Java 25 executable |
| `datapackSandbox.defaultVersion` | empty | Optional profile override; empty follows the bundled CLI's canonical default |
| `datapackSandbox.packPaths` | `[]` | Extra datapack directories or zip files |
| `datapackSandbox.language.enabled` | `true` | Isolated JVM language diagnostics, completion, hover, and navigation; TextMate highlighting remains when disabled |
| `datapackSandbox.language.diagnostics` | `true` | Whole-document profile-aware checks for open `.mcfunction` files |
| `datapackSandbox.language.diagnosticDelay` | `300` | Debounce in milliseconds before validation starts |
| `datapackSandbox.defaultPlayerName` | `Steve` | Player created in a new active sandbox; empty creates none |
| `datapackSandbox.strict` | `false` | Enable strict Run/Debug checks |
| `datapackSandbox.defaultExecutionTarget` | `temporary` | Default Run/Debug target |
| `datapackSandbox.cliJarPath` | empty | Custom CLI JAR; empty uses the bundled CLI |
| `datapackSandbox.coverage.*` | `0`, `0`, `[]`, `[]` | Line/function thresholds and resource-id include/exclude globs |
| `datapackSandbox.render.width` / `.height` | `960` / `540` | PNG dimensions used by the JAR renderer |
| `datapackSandbox.render.fieldOfView` | `70` | Render camera field of view |
| `datapackSandbox.render.distance` | `128` | Modeled render distance in blocks |
| `datapackSandbox.render.minecraftAssetsPath` | empty | Manually selected local Minecraft client assets or JAR |
| `datapackSandbox.render.resourcePackPaths` | `[]` | Additional local resource packs |
| `datapackSandbox.render.playerSkins` | `{}` | Player names mapped to local PNG skin paths |
| `datapackSandbox.render.camera*` | auto | Player, entity UUID, or fixed position/yaw/pitch/dimension camera |
| `datapackSandbox.render.transparentBackground` / `.showHud` / `.showDebugOverlay` | `false` | Optional frame layers |
| `datapackSandbox.render.strictAssets` | `false` | Fail when configured render assets cannot be resolved |

The extension never searches Minecraft installations or launcher metadata for client resources. Configure `render.minecraftAssetsPath`, resource packs, and skins yourself. These paths are sent only to the local JVM renderer; without them, the modeled fallback assets remain available.

## Troubleshooting

### The status bar says Stopped

Only the persistent sandbox is stopped. Temporary Run, Debug, tests, and standalone `.mcfunction` language support still work. Click DPS and start a sandbox to additionally use the panel and active test profiles.

### Completions do not appear

Standalone `.mcfunction` completion does not require an active sandbox. Confirm the language mode is **Minecraft Function**, `datapackSandbox.language.enabled` is `true`, Java 25 is available, and inspect the **Datapack Sandbox** output channel for language-session startup errors. Only panel completion requires an active sandbox and the **Command** operation.

### Java fails to start

Run `java -version` and confirm Java 25, or point `datapackSandbox.javaPath` to the correct executable. Startup errors are written to the **Datapack Sandbox** output channel.

The error panel distinguishes a missing Java executable, missing CLI JAR, startup timeout, version mismatch, missing resource, interruption, and command failure. When Serve returns a partial execution, it also shows completed command boundaries plus retained output, trace, player-event trace, and state-change counts.

### Debugging does not stop at a breakpoint

Verify that the breakpoint is on a command that produces a trace and that `program` points to the intended `.mcfunction` or `.dps.json`. `stopOnEntry` defaults to `false`.

## Develop and Package

The extension source lives in `vscode/`:

```powershell
.\gradlew.bat :cli:fatJar
cd vscode
npm install
npm test
npm run package
```

The output is `build/datapack-sandbox-vscode.vsix`, and its publisher should be **Alumopper**.

## Limitations

TextMate highlighting works offline; profile-aware diagnostics, completion, hover, and indexed resource navigation are a frontend for the JVM CLI and require local Java 25. Definition navigation opens file-backed resources in directory packs, not ZIP entries or built-in registry values, and semantic diagnostics skip macro lines without call arguments. Notebook cells do not automatically receive the standalone `.mcfunction` language features.

## Related pages

- [Installation and Downloads](/en/workflows/installation)
- [Jupyter Notebook](/en/integrations/jupyter)
- [CLI Reference](/en/reference/cli)
- [Serve JSONL Protocol](/en/reference/serve-jsonl)
