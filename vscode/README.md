# Datapack Sandbox for VS Code

Edit, validate, run, and inspect Minecraft Java datapacks without leaving the editor.

Published by **Alumopper** as [`Alumopper.datapack-sandbox-vscode`](https://marketplace.visualstudio.com/items?itemName=Alumopper.datapack-sandbox-vscode). Current release: `0.4.1`.

## Features

- Highlight `.mcfunction` comments, command roots, selectors, coordinates, resource locations, SNBT/JSON punctuation, strings, and macros as soon as a file opens.
- Validate whole functions against the configured or pack-inferred DSB Minecraft profile without requiring the persistent sandbox to be started.
- Complete commands and loaded resources, show command/selector/function hovers, and navigate literal `function` references to workspace definitions.
- Run the current `.mcfunction` file in the sandbox.
- Run `.dps.json` manifests from commands, code lenses, and Test Explorer.
- Start and stop a persistent sandbox directly from the Command Palette.
- Choose any built-in profile reported by the bundled CLI in the panel or startup picker.
- Choose temporary or active sandbox profiles in Test Explorer; regular Run and Debug remain temporary by default.
- Trace-debug `.mcfunction` and manifest runs, stopping at the first breakpoint instead of the first command.
- Expand nested trace, diagnostic, output, and snapshot JSON directly in the Debug sidebar.
- Interact with the active long-lived sandbox from a webview panel.
- Get sandbox-aware completions and non-mutating command checks in the panel command input.
- Continue `.mcfunction` completion automatically across inserted spaces and structured syntax, with editable `{}`/`[]` placeholders.
- Ctrl+click file-backed datapack resource IDs to open their active definitions.
- Save, restore, list, and delete reusable in-memory world checkpoints.
- Inspect and reset accumulated line/function coverage with resource-id filters and thresholds.
- Render the active world to PNG with the bundled JVM renderer and optional local resource assets.
- Inspect rich command output, player-event traces, and incrementally paged execution history.
- Open the effective loaded source for functions, including synthetic and ZIP-backed functions.
- Interrupt long-running JAR executions at command boundaries.
- Browse loaded resources, request command completions, and generate starter manifests.
- Inspect outputs, traces, snapshot diffs, diagnostics, and final state.
- Get structured startup and operation errors with actionable hints, plus readable execution summaries.

## Requirements

- VS Code 1.95 or newer.
- Java 25 available as `java`, or configured with `datapackSandbox.javaPath`.

The distributable VSIX includes the Datapack Sandbox CLI JAR. Runtime execution, checkpoints, validation, and rendering continue to use that JVM JAR; the extension does not switch to the browser runtime. You do not need to clone this repository or build Gradle projects after installation.

## Install

Install from VS Code Marketplace or run:

```text
code --install-extension Alumopper.datapack-sandbox-vscode
```

Marketplace installation is recommended and receives normal extension updates.

For an offline or pinned installation:

1. Open the Extensions view in VS Code.
2. Choose **Views and More Actions > Install from VSIX...**.
3. Select `datapack-sandbox-vscode.vsix`.
4. Reload VS Code when prompted.

You can also install it from a terminal:

```text
code --install-extension datapack-sandbox-vscode.vsix
```

## Quick Start

1. Open a datapack folder and any `.mcfunction` file. Highlighting is immediate; JVM-backed diagnostics and IntelliSense initialize in the background.
2. Use completion, hover a command/selector/function ID, or press Go to Definition on a literal `function demo:path` reference.
3. Click **DPS** in the status bar when you also need a persistent executable world.
4. Inspect rich outputs, command and player-event traces, resources, snapshots, entities, and diagnostics in the panel.
5. Use **Save point**/**Return** for reusable world checkpoints, or **Render PNG** for a JAR-rendered frame.

Normal Run and Debug commands use isolated temporary sandboxes by default. Test Explorer also provides explicit temporary and active sandbox profiles.

## Settings

- `datapackSandbox.javaPath`: Java 25 executable.
- `datapackSandbox.defaultVersion`: optional profile override; empty follows the bundled CLI default.
- `datapackSandbox.packPaths`: extra datapack directories or zip files.
- `datapackSandbox.language.enabled`: JVM-backed completion, hover, navigation, and validation; syntax highlighting does not require it.
- `datapackSandbox.language.diagnostics`: live whole-document command diagnostics.
- `datapackSandbox.language.diagnosticDelay`: validation debounce in milliseconds.
- `datapackSandbox.defaultPlayerName`: player created for a new active sandbox; empty creates none.
- `datapackSandbox.defaultExecutionTarget`: `temporary` by default, or `active` for persistent execution.
- `datapackSandbox.cliJarPath`: optional custom CLI JAR; empty uses the bundled CLI.
- `datapackSandbox.coverage.*`: thresholds plus resource-id include/exclude globs for active-sandbox coverage.
- `datapackSandbox.render.*`: PNG dimensions, cameras, overlays, local Minecraft assets, resource packs, player skins, and strict asset handling. Client resources are always configured manually and are never auto-discovered.

## Development

Run `npm install` and `npm test` inside this directory. To create a distributable package, build `:cli:fatJar` from the repository root and run `npm run package` here.
