# Rendering and Jupyter Kernel

Datapack Sandbox can render the modeled world as a headless PNG and can run
native `mcfunction` cells in a persistent Jupyter Kernel. Both surfaces use the
same clean-room JVM runtime: a notebook does not start a Minecraft server or
client.

## What the image represents

The renderer captures the explicit blocks, entities, players, time, weather,
and rotations currently stored in `SandboxWorld`. It renders a perspective
scene with a depth buffer, textured block models, transparent materials,
approximate daylight, entities, and either a player or automatic camera.

This is deliberately not a vanilla screenshot. Terrain generation, client
particles, redstone updates, entity AI, vanilla light maps, and post-processing
are not inferred when the runtime has not modeled them. Render metadata always
reports `visualParity=false` and `lightingModel=approximate`.

Players, zombies, and skeletons use recognizable segmented humanoid geometry.
Display entities are modeled explicitly on both JVM and Web: `block_display`
bakes its blockstate/model, `item_display` resolves modern item definitions,
extrudes generated-model sprite outlines, and applies model `display`
transforms, while `text_display` rasterizes readable text. The
shared path applies the 4x4 transformation, all four billboard modes, display
contexts, brightness overrides, view/culling bounds, shadows, wrapping,
alignment, background/opacity, text shadow, see-through depth behavior, and
tick-based visual/teleport interpolation. Dropped items and experience orbs
remain approximate; `marker`/`interaction` remain hidden unless debug rendering
is enabled.

Custom font-provider stacks, multi-layer/special item models, glow outlines,
the client light map, and post-processing are not a pixel-identical client
implementation. Supply a matching client JAR or resource pack for referenced
models, item definitions, font atlases, and textures; otherwise deterministic
fallback materials are used.

## Render after a CLI run

The minimum command needs no external assets. Missing models and textures use
deterministic fallback materials and emit diagnostics:

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar run `
  --version 26.2 `
  --command "setblock 0 64 2 minecraft:stone" `
  --screenshot-file build/state.png
```

Use a locally installed client JAR or extracted `assets` directory for vanilla
models and textures. Only data below `assets/` is read; classes are never
loaded or executed:

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar run `
  --pack ./my_pack `
  --function demo:main `
  --minecraft-assets "$env:APPDATA/.minecraft/versions/26.2/26.2.jar" `
  --resource-pack ./my-resource-pack.zip `
  --camera-player Steve `
  --screenshot-file build/state.png `
  --screenshot-width 1280 `
  --screenshot-height 720
```

Relevant options:

| Option | Meaning |
| --- | --- |
| `--screenshot-file <png>` | Enables rendering and writes the final state |
| `--minecraft-assets <path>` | Client JAR or directory containing `assets/` |
| `--resource-pack <path>` | Additional directory/ZIP; repeat to layer packs |
| `--camera-player <name>` | Uses the player's eye position, yaw, and pitch |
| `--camera-entity <uuid>` | Uses an entity position and rotation |
| `--camera-position <x,y,z>` | Fixed camera; combine with `--camera-yaw/--camera-pitch` |
| `--player-skin <name=png>` | Supplies a player skin; repeat for more players |
| `--screenshot-width/--screenshot-height` | Image dimensions, 64 through 8192 |
| `--screenshot-fov <degrees>` | Perspective field of view, default 70 |
| `--render-distance <blocks>` | Far clipping distance, default 128 |
| `--transparent-background` | Emits an alpha background instead of sky |
| `--render-hud` | Draws the center crosshair |
| `--render-debug` | Draws the crosshair/debug frame and simplified hidden-entity geometry |
| `--require-render-assets` | Fails instead of using procedural assets |

`--strict` also makes missing or invalid render assets fail. Screenshot output
can be combined with snapshot, trace, outputs, assertions, and run reports.

## Play in the JVM realtime viewport

The standalone JAR includes a GPU realtime viewport built on GLFW/OpenGL 3.3.
It keeps command, input, checkpoint, and 20 TPS world mutation on one serialized
JVM session, while camera frames only update matrices and draw cached GPU scene
buffers without rerunning the sandbox:

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar viewport `
  --version 26.2 `
  --minecraft-assets "D:\.minecraft\versions\26.1.2\26.1.2.jar" `
  --command "setblock 0 0 2 minecraft:stone"
```

Click the viewport to capture the mouse. Use WASD for horizontal flight,
Space/Shift for vertical flight, the wheel for speed, and Escape to release the
pointer. The toolbar provides play/pause, one-tick stepping, automatic camera
reset, a reusable checkpoint, sandbox reset, and high-quality PNG export. Press
`T`, `/`, or click **Command** to open the command console. It provides
world-aware completion and non-mutating command checks while typing; use Tab to
accept a completion, Up/Down to select one, and Enter to run. Command output,
errors, and state-change summaries remain visible in the viewport console.

The default window is 1200x720 with a larger HUD. Click **Settings** or press
F10 to adjust mouse sensitivity, flight speed, UI scale, and field of view at
runtime. Their startup values can also be set with `--mouse-sensitivity`,
`--move-speed`, `--ui-scale`, and `--field-of-view`.

The HUD bakes an antialiased OpenGL atlas from an installed system TrueType
monospace font. It does not place an opaque slab behind the toolbar, status, or
console: buttons, individual text rows, and command input use Minecraft-like
translucent black backplates. The idle console shrinks to its actual message
count and only expands for completion and check results in command mode.

The JVM viewport intentionally has no touch controls. Keyboard and mouse input
is recorded through the same `PlayerInput`/`PlayerEvent` model as other JVM
tests and does not imply vanilla physics, collision, redstone, or entity AI.
The realtime view uses OpenGL; **Export PNG** still uses the full software
renderer and remains the quality path instead of capturing the realtime frame.

## Embed the renderer in a JVM application

Depend on `renderer`; it exposes `core` transitively:

```kotlin
dependencies {
    implementation("moe.afox.dpsandbox:renderer:1.0.1")
}
```

```kotlin
val renderer = SandboxRenderer(
    RenderAssets(
        minecraftAssets = Path.of("client.jar"),
        resourcePacks = listOf(Path.of("resources.zip")),
    ),
)
val frame = renderer.render(
    sandbox,
    RenderRequest(
        width = 1280,
        height = 720,
        camera = RenderCamera.Player("Steve"),
    ),
)
frame.writePng(Path.of("state.png"))
```

Rendering first copies an immutable `WorldView`. A successful or failed render
does not change the sandbox snapshot.

Record reusable sandbox points and animated GIF frames directly on the JVM:

```kotlin
val saved = sandbox.saveCheckpoint("before-branch")

val gif = SandboxGifRecorder(
    renderer = renderer,
    request = RenderRequest(width = 480, height = 270),
    frameDelayMillis = 200,
)
gif.capture(sandbox)
sandbox.executeCommand("setblock 1 0 2 minecraft:diamond_block")
gif.capture(sandbox)
gif.export().writeGif(Path.of("branch.gif"))

sandbox.restoreCheckpoint("before-branch")
check(sandbox.snapshotString() == saved)
```

Checkpoints contain the complete modeled world, including players, entities,
scores, storage, outputs, and traces. They do not copy immutable datapack assets
or rewind monotonic execution safety budgets. The GIF encoder is shared with the
browser Worker and produces identical bytes for identical RGBA frames, delays,
and repeat settings.

## JSONL editor integration

The persistent `serve` protocol advertises the `render` capability in its hello
message. A render request returns a Base64 PNG and metadata:

```json
{"id":"frame","method":"render","params":{"cameraPlayer":"Steve","width":960,"height":540}}
```

The method also accepts `minecraftAssets`, `resourcePacks`, `fieldOfView`,
`renderDistance`, `transparentBackground`, `showHud`, `showDebugOverlay`, and
`strictAssets`. The response is capped at 16 MiB to keep JSONL messages bounded.

## Install the Jupyter Kernel from a checkout

Java 25 and Python 3.10 or newer are required:

```powershell
./gradlew.bat prepareJupyterKernel
python -m pip install -e "./jupyter[test]" jupyterlab
datapack-sandbox-kernel --user
python -m jupyterlab examples/jupyter/datapack-sandbox-demo.ipynb
```

`prepareJupyterKernel` builds the standalone CLI and places it in the Python
package. Release wheels carry that Datapack Sandbox JAR, but never carry
Minecraft assets. Set `DPS_CLI_JAR` to override the bundled JAR and `DPS_JAVA`
to select a Java 25 executable.

## Install a GitHub Release wheel

Download `datapack_sandbox_kernel-1.0.1-py3-none-any.whl` from the GitHub
release, then install it into the same Python interpreter that VS Code or
Jupyter will use:

```powershell
$py = "C:\Users\<user>\AppData\Local\Programs\Python\Python310\python.exe"
$wheel = "C:\Downloads\datapack_sandbox_kernel-1.0.1-py3-none-any.whl"
& $py -m pip install --upgrade $wheel jupyterlab
& "$([System.IO.Path]::GetDirectoryName($py))\Scripts\datapack-sandbox-kernel.exe" --user
```

Verify that the kernelspec is visible:

```powershell
& $py -c "from jupyter_client.kernelspec import KernelSpecManager; print(KernelSpecManager().find_kernel_specs())"
```

The output must contain `datapack-sandbox`. Start a standard server with
`python -m jupyterlab --no-browser`, then connect VS Code or IntelliJ IDEA to
the printed URL and select **Datapack Sandbox (MCFunction)**. The release
wheel includes the CLI JAR but no Minecraft client assets.

## Execute native MCF cells

Select **Datapack Sandbox (MCFunction)** when creating a notebook. Markdown
cells remain normal documentation; code cells contain raw commands:

```mcfunction
scoreboard objectives add points dummy
scoreboard players set Steve points 10
setblock 0 64 2 minecraft:diamond_ore
```

The next cell sees the same world:

```mcfunction
scoreboard players add Steve points 5
say state updated
```

Each successful cell publishes a readable text/HTML/Markdown summary, snapshot
diffs, and—by default—`image/png`. The structured result is kept in Jupyter
message metadata rather than rendered as a raw JSON block by VS Code. A command
failure identifies the notebook cell, MCF line, and command. The Kernel remains
usable after a normal command error.

The first configuration cell should apply pending changes before running MCF:

```text
%dps version 26.2
%dps config autoRender true
%dps reset --apply
```

Only successful MCF cells auto-render. Use `%dps render [path.png]` for an
explicit frame or to save a PNG file.

Cells use the synthetic server execution context. Player-bound behavior must be
explicit, for example:

```mcfunction
execute as Steve at @s run function demo:main
```

## Kernel controls

Control lines start with `%dps` and may share an initialization cell:

```text
%dps version 26.2
%dps pack ./my_pack
%dps assets ~/.minecraft/versions/26.2/26.2.jar
%dps camera Steve
%dps status
```

| Command | Effect |
| --- | --- |
| `%dps version <id>` | Configures the Minecraft profile |
| `%dps pack <path>` / `%dps packs` | Adds or lists datapacks |
| `%dps assets <path>` | Selects a client JAR/assets directory |
| `%dps resource-pack <path>` | Layers a rendering resource pack |
| `%dps world <fixture>` | Applies a world fixture to the current world |
| `%dps camera <player|uuid>` | Selects a player or entity render camera |
| `%dps tick <count>` | Advances ticks |
| `%dps function <id>` | Runs a loaded function |
| `%dps render [png]` | Displays and optionally saves a frame |
| `%dps snapshot` / `%dps outputs` | Displays current structured state/output |
| `%dps config autoRender <bool>` | Toggles automatic frame output |
| `%dps reset --apply` | Rebuilds the world with pending version/pack changes |
| `%dps status` / `%dps help` | Displays state or help |

Changing version or datapacks after opening a world never discards state
silently. The Kernel reports `RESET_REQUIRED` until `%dps reset --apply` is
executed. Re-running a cell executes it again; it does not restore the state
that existed before its previous execution.

Jupyter interrupt messages cooperatively stop JVM execution at the next command
or tick boundary on Windows and Linux. Commands that already completed remain
in the world; interruption is not a transaction rollback. The same Kernel and
world remain usable after a handled interrupt.

If the JVM exits unexpectedly, the current cell returns `SESSION_LOST`. The
Kernel never pretends to restore the old world; `%dps reset --apply` creates an
explicit new one. Cooperative cancellation returns `EXECUTION_INTERRUPTED` and
includes completed-command count, outputs, traces, snapshot diffs, and the last
stable state in the error details.

Automatic rendering caches the previous frame. If the world view, camera, assets,
and render settings are unchanged, the Kernel reuses the PNG and sets
`render.reused` explicitly. A failure or interrupt invalidates the cache because
earlier command boundaries may already have changed the world.

Project defaults can be stored in `.dps-kernel.json`. Precedence from highest to
lowest is: Notebook `%dps` directives, project `.dps-kernel.json`, environment
variables, user configuration, and built-in defaults. Supported fields include
`version`, `packs`, `minecraftAssets`, `resourcePacks`, `defaultPlayer`,
`cameraPlayer`, `autoRender`, `strict`, and nested render dimensions/FOV/distance.

## Validation

Run the renderer and real Jupyter client integration tests with:

```powershell
./gradlew.bat :renderer:check
./gradlew.bat :renderer:renderBenchmark
./gradlew.bat :cli:test --tests "moe.afox.dpsandbox.cli.RenderCommandTest"
./gradlew.bat jupyterKernelTest
```

The Kernel tests start the real fat JAR, connect through JSONL, launch a real
Jupyter client, execute persistent cells, decode PNG output, test completion,
recover from an MCF error, and verify shutdown.

The benchmark writes `renderer/build/reports/render-benchmark.json` with sparse
and solid-surface 16/32/64 cubes, three resolutions, 100/1,000 entities,
cold/warm assets, camera-only changes, and unchanged-world measurements. Each
entry separates world capture, asset resolution, scene building, rasterization,
and PNG encoding time. It is a regression baseline rather than a fixed latency
promise. The renderer tests also use a repository-owned synthetic golden with
structural pixel/bounds checks and a tolerant perceptual color metric; no Mojang
asset is included.

## Release assets and editor support

For a GitHub Release, publish the versioned Jupyter wheel and sdist, the VS Code
VSIX, and the standalone CLI fat JAR. The wheel and VSIX already bundle the CLI
JAR; do not upload Minecraft client/server JARs or extracted assets. Add a
`SHA256SUMS.txt` file so users can verify downloads.

The Jupyter kernel provides completion requests and readable PNG/Markdown/HTML
output, but Notebook frontends do not necessarily expose a full custom
`mcfunction` language service. The VS Code extension's completions and
diagnostics target standalone `.mcfunction` files. Use VS Code for editing and
Notebook cells for persistent execution and screenshots when a frontend treats
Notebook cells as Python or plain text.
