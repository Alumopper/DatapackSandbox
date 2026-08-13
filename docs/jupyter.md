# Jupyter Integration

## When to use this page

Use the Jupyter kernel to execute native MCFunction one cell at a time, preserve world state, and interleave command output, snapshot diffs, and PNGs with analysis notes. For standalone PNGs, GIFs, or a desktop viewport, use [Rendering, Animation, and Realtime Viewports](/en/guide/rendering-notebook).

## Prerequisites

- Python 3.10 or newer and JupyterLab or another frontend that supports custom kernels.
- Java 25. A release wheel bundles a compatible CLI JAR but no Minecraft assets.
- When installing from a checkout, build `cli/build/libs/datapack-sandbox-cli.jar` first.

## Minimal runnable example

Install the release wheel and register its kernelspec:

```powershell
python -m pip install --upgrade .\datapack_sandbox_kernel-<version>-py3-none-any.whl jupyterlab
datapack-sandbox-kernel --user
python -m jupyterlab --no-browser
```

Select **Datapack Sandbox (MCFunction)**, apply configuration, then execute MCF:

```text
%dps version 26.2
%dps config autoRender true
%dps reset --apply
```

```mcfunction
scoreboard objectives add runs dummy
scoreboard players add #notebook runs 1
say notebook ready
```

Successful MCF cells display a readable summary, structured metadata, and an inline PNG by default.

## Full capabilities

### Install from a checkout

```powershell
.\gradlew.bat prepareJupyterKernel
$env:DPS_CLI_JAR = ".\cli\build\libs\datapack-sandbox-cli.jar"
python -m pip install -e ".\jupyter[test]" jupyterlab
datapack-sandbox-kernel --user
python -m jupyterlab examples/jupyter/datapack-sandbox-demo.ipynb
```

Set `DPS_JAVA` to select a Java 25 executable explicitly. Use `jupyter kernelspec list` to confirm that `datapack-sandbox` is registered. In VS Code, install Microsoft's Python and Jupyter extensions and select the same Python interpreter that owns the wheel.

### `%dps` magics

| Directive | Effect |
| --- | --- |
| `%dps version <id>` | Configure the Minecraft profile; an open world then requires reset |
| `%dps pack <path>` / `%dps packs` | Add or list datapacks |
| `%dps assets <path>` | Select a client JAR or `assets/` directory |
| `%dps resource-pack <path>` | Layer a rendering resource pack |
| `%dps skin <player> <path>` | Assign a local PNG skin to a rendered player |
| `%dps world <fixture.json>` | Apply a world fixture to the current world |
| `%dps camera <mode...>` | Select auto, player, entity UUID, or fixed-position camera |
| `%dps tick <count>` | Advance ticks |
| `%dps function <id>` | Run a loaded function |
| `%dps load` / `%dps event <event text>` | Run load functions or inject a player event |
| `%dps checkpoint [list\|save\|restore\|delete] [name]` | Manage reusable in-memory checkpoints |
| `%dps coverage [options]` / `%dps reset-coverage` | Inspect or reset accumulated line/function coverage |
| `%dps render [output.png]` | Display a PNG and optionally save it |
| `%dps snapshot` / `%dps outputs` / `%dps traces` / `%dps event-traces` | Inspect world state and execution history |
| `%dps resources` / `%dps function-source <id>` | Inspect resource priority and effective function source |
| `%dps reload [--discard-world]` / `%dps reset-world` | Reload packs or replace only the modeled world |
| `%dps config <option> <bool>` | Control automatic rendering, transparency, HUD, and debug overlay |
| `%dps status` / `%dps help` | Display session state or help |
| `%dps reset --apply` | Rebuild with pending version and pack configuration |

Control lines can share a cell with MCF. Changing `version` or `pack` never silently discards an open world; execution returns `RESET_REQUIRED` until `%dps reset --apply`. A normal cell becomes a `notebook:cell_<execution-count>` function and runs in the same persistent Serve session.

### Configuration and render caching

A project-root `.dps-kernel.json` can set `version`, `packs`, `minecraftAssets`, `resourcePacks`, `playerSkins`, `defaultPlayer`, `cameraPlayer` or `cameraEntity`, `autoRender`, `strict`, and render dimensions/FOV/distance/camera/overlay options. Precedence from highest to lowest is notebook `%dps`, project configuration, environment variables, user configuration, then built-in defaults.

Client assets are opt-in. The kernel never searches `.minecraft`, launcher profiles, or installed game versions. Set `minecraftAssets`/`DPS_MINECRAFT_ASSETS` or run `%dps assets <path>` yourself; configure resource packs and skins separately. Without them the renderer uses its modeled fallback materials and reports the asset sources and diagnostics in render metadata.

Automatic rendering reuses a PNG when the world revision, camera, assets, and settings have not changed and marks `render.reused` in metadata. A failure or interrupt can leave completed world changes, so it invalidates the cache.

### Interrupts and recovery

The kernel sends a message-mode interrupt that asks Serve to cancel at the next command boundary. `EXECUTION_INTERRUPTED` retains completed commands and their output, traces, and snapshot diffs as a partial result; it is not a transaction rollback.

If the JVM exits unexpectedly, the current cell returns `SESSION_LOST`. The kernel does not pretend to recover the previous world; fix the cause and use `%dps reset --apply` to create an explicit new one. Ordinary command errors leave the session available for later cells.

## Limitations

- Notebook frontends may not expose a custom kernel as a full language service. Protocol completion is available, while comprehensive diagnostics for standalone `.mcfunction` files remain a [VS Code Extension](/en/guide/vscode-extension) workflow.
- Rerunning a cell mutates the current world again; it does not restore the state that preceded the prior execution.
- Wheels, VSIX packages, and the standalone JAR never bundle or auto-discover Mojang client/server JARs or resource packs; they only reference user-owned local assets configured explicitly.
- Do not commit absolute local paths or large binary packs into notebooks.

## Related pages

- [Serve JSONL Protocol](/en/reference/serve-jsonl)
- [Rendering, Animation, and Realtime Viewports](/en/guide/rendering-notebook)
- [VS Code Extension](/en/guide/vscode-extension)
- [Reports and Observability](/en/reference/reports-observability)
