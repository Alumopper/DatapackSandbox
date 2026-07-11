# VS Code Extension

Datapack Sandbox for VS Code brings running, testing, trace debugging, and sandbox inspection into the editor. It is published by **Alumopper** under the extension ID `Alumopper.datapack-sandbox-vscode`.

## Install

The extension requires VS Code 1.95 or newer and Java 25. The distributable VSIX bundles the Datapack Sandbox CLI, so users do not need to clone the repository or run Gradle.

Choose **Install from VSIX...** in the Extensions view and select:

```text
datapack-sandbox-vscode.vsix
```

Or install it from a terminal:

```powershell
code --install-extension .\datapack-sandbox-vscode.vsix
```

Set `datapackSandbox.javaPath` when Java 25 is not available on `PATH`.

## Quick Start

1. Open a workspace containing a datapack.
2. Click **DPS** in the status bar.
3. Confirm the Minecraft profile and datapack paths, then choose **Start sandbox**.
4. Enter a command. The panel provides completions based on the active sandbox and checks the command in an isolated copy that does not mutate the active world.
5. Use the Inspector to browse output, traces, snapshots, resources, players, entities, scores, and diagnostics.

Use `↑` and `↓` to move through suggestions and `Tab` or `Enter` to accept one. Inspector JSON is expandable, and traces with source locations can open the corresponding `.mcfunction` line.

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
| `datapackSandbox.defaultVersion` | `26.2` | Default Minecraft profile |
| `datapackSandbox.packPaths` | `[]` | Extra datapack directories or zip files |
| `datapackSandbox.strict` | `false` | Enable strict Run/Debug checks |
| `datapackSandbox.defaultExecutionTarget` | `temporary` | Default Run/Debug target |
| `datapackSandbox.cliJarPath` | empty | Custom CLI JAR; empty uses the bundled CLI |

## Troubleshooting

### The status bar says Stopped

Only the persistent sandbox is stopped. Temporary Run, Debug, and tests still work. Click DPS and start a sandbox to use the panel, active test profiles, and state-aware completions.

### Completions do not appear

Panel and `.mcfunction` state-aware completions require an active sandbox. Start one and ensure the panel operation is set to **Command**.

### Java fails to start

Run `java -version` and confirm Java 25, or point `datapackSandbox.javaPath` to the correct executable. Startup errors are written to the **Datapack Sandbox** output channel.

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
