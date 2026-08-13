# Rendering, Animation, and Realtime Viewports

## When to use this page

Use this page when a test needs a PNG, a reproducible GIF, or an interactive view of a persistent sandbox. The renderer reads the explicit state in `SandboxWorld`; it starts neither a Minecraft client nor a vanilla server.

## Prerequisites

Prepare the standalone JAR as described in [Install and Obtain](/en/workflows/installation). Client assets must be supplied explicitly: Datapack Sandbox does not bundle or download them, scan `.minecraft`, or choose a client JAR from `--version`. Rendering still works without external assets by emitting diagnostics and using deterministic fallbacks. For matching-version vanilla visuals, provide a local client JAR or directory containing `assets/`; resource packs are optional override layers.

## Minimal runnable example

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar run `
  --version 26.2 `
  --command "setblock 0 64 2 minecraft:stone" `
  --screenshot-file build/state.png
```

Rendering can be combined with snapshot, trace, output, assertion, and run-report artifacts.

## Full capabilities

### PNG and asset layers

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

`--minecraft-assets` accepts the client JAR or directory containing `assets/` that you specify; classes are never loaded. Repeated `--resource-pack` values layer in declaration order. If a pack inherits vanilla parent models or references vanilla textures, also supply the base client assets. A camera can follow a player or entity or use fixed coordinates, with controls for FOV, clipping distance, transparent background, HUD, and debug overlay. `--require-render-assets` fails on missing assets, while `--strict` also treats missing or invalid assets as errors.

### GIF and the JVM API

`SandboxGifRecorder` captures ordered RGBA frames from one sandbox and exports a deterministic GIF. See the [Renderer JVM API Reference](/en/reference/renderer-api) for dependencies and the complete `RenderAssets`, `RenderRequest`, camera, frame, and GIF types.

```kotlin
val gif = SandboxGifRecorder(
    renderer = renderer,
    request = RenderRequest(width = 480, height = 270),
    frameDelayMillis = 200,
)
gif.capture(sandbox)
sandbox.executeCommand("setblock 1 0 2 minecraft:diamond_block")
gif.capture(sandbox)
gif.export().writeGif(Path.of("branch.gif"))
```

Rendering first copies an immutable `WorldView`, so success or failure does not mutate the sandbox. The GIF encoder is shared by the JVM and browser Worker; identical frames, delays, and repeat settings produce identical bytes.

### JVM realtime viewport

```powershell
java -jar cli/build/libs/datapack-sandbox-cli.jar viewport `
  --version 26.2 `
  --minecraft-assets "D:\.minecraft\versions\26.2\26.2.jar" `
  --command "setblock 0 0 2 minecraft:stone"
```

The viewport uses GLFW/OpenGL 3.3. Click the scene to capture the pointer, fly with WASD, move vertically with Space/Shift, adjust speed with the wheel, and press Escape to release. Its toolbar provides play/pause, one-tick stepping, reframing, checkpoints, reset, and high-quality PNG export. Press `T`, `/`, or **Command** for a console with completion and non-mutating checks.

Commands, input, checkpoints, and 20 TPS world changes are serialized in one JVM session. Camera frames update matrices and GPU buffers without rerunning commands. Realtime frames use OpenGL, while **Export PNG** always uses the full software-rendering path.

### Jupyter Kernel

The former Jupyter installation, `%dps` magics, persistent-session, configuration-precedence, interrupt, and recovery sections have moved to the dedicated [Jupyter Guide](/en/integrations/jupyter). This heading remains so existing section links still reach the authoritative entry.

### Serve and browser integrations

Editor processes can obtain a Base64 PNG and metadata through the JSONL [`render` method](/en/reference/serve-jsonl#render), explicitly passing a host path in each request's `minecraftAssets`. A browser cannot consume that JVM path or automatically read the local `.minecraft` directory. Import a client JAR through the component's picker/drop target or call [`importArchive('client-jar', ...)`](/en/reference/playground-api#client-asset-import) before showing the WebGL2 viewport or exporting software-rendered PNG/GIF output.

## Limitations

- Output only represents blocks, entities, players, time, and weather already present in the sparse world model. Terrain generation, particles, redstone updates, entity AI, the vanilla light map, and post-processing are not inferred.
- Metadata always reports `visualParity: false` and `lightingModel: approximate`. Do not describe the result as a pixel-identical vanilla screenshot.
- Custom font-provider stacks, multi-layer or special item models, glow outlines, and other unmodeled client effects are outside the parity boundary.
- PNG dimensions are 64–8192 per side with a 16,777,216 total-pixel cap; Serve responses also have a 16 MiB encoded-size limit.
- The JVM viewport is keyboard-and-mouse only. The browser viewport has separate touch controls.

## Related pages

- [Renderer JVM API Reference](/en/reference/renderer-api)
- [Jupyter Guide](/en/integrations/jupyter)
- [CLI Reference](/en/reference/cli)
- [Playground API Reference](/en/reference/playground-api)
- [Reports and Observability](/en/reference/reports-observability)
