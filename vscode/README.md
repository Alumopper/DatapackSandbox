# Datapack Sandbox for VS Code

Run and inspect Minecraft Java datapacks without leaving the editor.

Published by **Alumopper** as `Alumopper.datapack-sandbox-vscode`.

## Features

- Run the current `.mcfunction` file in the sandbox.
- Run `.dps.json` manifests from commands, code lenses, and Test Explorer.
- Start and stop a persistent sandbox directly from the Command Palette.
- Choose temporary or active sandbox profiles in Test Explorer; regular Run and Debug remain temporary by default.
- Trace-debug `.mcfunction` and manifest runs, stopping at the first breakpoint instead of the first command.
- Expand nested trace, diagnostic, output, and snapshot JSON directly in the Debug sidebar.
- Interact with the active long-lived sandbox from a webview panel.
- Get sandbox-aware completions and non-mutating command checks in the panel command input.
- Browse loaded resources, request command completions, and generate starter manifests.
- Inspect outputs, traces, snapshot diffs, diagnostics, and final state.

## Requirements

- VS Code 1.95 or newer.
- Java 25 available as `java`, or configured with `datapackSandbox.javaPath`.

The distributable VSIX includes the Datapack Sandbox CLI jar. You do not need to clone this repository or build Gradle projects after installation.

## Install a VSIX

1. Open the Extensions view in VS Code.
2. Choose **Views and More Actions > Install from VSIX...**.
3. Select `datapack-sandbox-vscode.vsix`.
4. Reload VS Code when prompted.

You can also install it from a terminal:

```text
code --install-extension datapack-sandbox-vscode.vsix
```

## Quick Start

1. Click **DPS** in the status bar.
2. Start a persistent sandbox with the desired Minecraft profile and datapack paths.
3. Enter commands with sandbox-aware completion and non-mutating live checks.
4. Inspect outputs, traces, resources, snapshots, entities, and diagnostics in the panel.

Normal Run and Debug commands use isolated temporary sandboxes by default. Test Explorer also provides explicit temporary and active sandbox profiles.

## Settings

- `datapackSandbox.javaPath`: Java 25 executable.
- `datapackSandbox.defaultVersion`: default Minecraft profile.
- `datapackSandbox.packPaths`: extra datapack directories or zip files.
- `datapackSandbox.defaultExecutionTarget`: `temporary` by default, or `active` for persistent execution.
- `datapackSandbox.cliJarPath`: optional custom CLI JAR; empty uses the bundled CLI.

## Development

Run `npm install` and `npm test` inside this directory. To create a distributable package, build `:cli:fatJar` from the repository root and run `npm run package` here.
