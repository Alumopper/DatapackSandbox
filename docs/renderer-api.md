# Renderer JVM API Reference

## When to use this page

Depend on `renderer` when a JVM application must turn the current `DatapackSandbox` world into a PNG, record a GIF, or compile an immutable scene for a moving camera. The renderer is useful for documentation, CI artifacts, editor previews, and live JVM viewports; it is not intended as a vanilla screenshot comparator.

## Prerequisites

```kotlin
dependencies {
    implementation("moe.afox.dpsandbox:core:1.1.0")
    implementation("moe.afox.dpsandbox:renderer:1.1.0")
}
```

The JVM runtime and renderer require Java 25.

::: warning Client assets are always supplied manually
Datapack Sandbox does **not** bundle, download, locate, or infer Minecraft client assets from `sandbox.profile`. The host application must explicitly pass a client JAR, an extracted `assets/` directory, resource packs, and player skins. Obtain those files through a source you are authorized to use, and match them to the profile being rendered.

If you omit them, rendering still succeeds with deterministic procedural fallbacks. That is useful for structure debugging, but it is not evidence that real textures were loaded.
:::

## Minimal runnable example

```kotlin
import moe.afox.dpsandbox.core.createFunctionSandboxFromString
import moe.afox.dpsandbox.render.RenderAssets
import moe.afox.dpsandbox.render.RenderCamera
import moe.afox.dpsandbox.render.RenderRequest
import moe.afox.dpsandbox.render.SandboxRenderer
import java.nio.file.Path

val sandbox = createFunctionSandboxFromString(
    version = "26.2",
    functionText = "setblock 0 64 0 minecraft:diamond_block",
)
sandbox.runFunction("sandbox:main")

val clientAssets = Path.of(
    requireNotNull(System.getenv("DPS_CLIENT_JAR")) {
        "Set DPS_CLIENT_JAR to a matching Minecraft client JAR or assets directory"
    },
)
val renderer = SandboxRenderer(
    RenderAssets(minecraftAssets = clientAssets),
)
val frame = renderer.render(
    sandbox,
    RenderRequest(width = 960, height = 540, camera = RenderCamera.Auto),
)

frame.writePng(Path.of("build/render/world.png"))
println("sources=${frame.metadata.assetSources}")
println("diagnostics=${frame.metadata.diagnostics}")
```

`writePng` creates missing parent directories. `pngBytes()` returns a defensive copy when a database, HTTP response, or custom encoder needs the bytes instead.

## Configure asset resolution

```kotlin
val assets = RenderAssets(
    minecraftAssets = Path.of("D:/Minecraft/versions/26.2/26.2.jar"),
    resourcePacks = listOf(
        Path.of("D:/packs/base-visuals.zip"),
        Path.of("D:/packs/project-overrides"),
    ),
    playerSkins = mapOf(
        "Alex" to Path.of("D:/skins/alex.png"),
    ),
)
```

| Input | Accepted shape | Resolution behavior |
| --- | --- | --- |
| `minecraftAssets` | Client JAR/ZIP, extracted root, or the `assets/` directory itself | Base source for vanilla models, blockstates, textures, and metadata |
| `resourcePacks` | ZIPs or directories | Applied in list order; later entries override earlier entries and vanilla assets |
| `playerSkins` | Player name to readable PNG path | Name matching is case-insensitive; missing skins use a procedural player texture |

Every supplied path must exist when `RenderAssets` is constructed. A directory may be the extracted root containing `assets/` or the `assets/` directory itself. ZIP/JAR entries are resolved below `assets/<namespace>/...`; parent traversal is rejected and encoded file sizes are bounded.

Resource packs are override layers, not replacements for the vanilla base. If a pack model references a vanilla parent or texture, also pass `minecraftAssets`. Reuse one `SandboxRenderer` with an unchanged asset set to benefit from its shared asset-byte cache. Recreate it after changing files when the application needs a fresh asset view.

## Select a camera

| Camera | Behavior | Failure case |
| --- | --- | --- |
| `RenderCamera.Auto` | Deterministically frames visible scene bounds | Falls back to a stable overview for an empty scene |
| `RenderCamera.Player(name)` | Uses the player's eye position and rotation | Named player must exist |
| `RenderCamera.Entity(uuid)` | Uses the entity position and rotation | UUID must resolve in the world |
| `RenderCamera.Fixed(...)` | Uses explicit position, yaw, pitch, and dimension | Caller owns framing and dimension correctness |

```kotlin
import moe.afox.dpsandbox.core.Position

val request = RenderRequest(
    camera = RenderCamera.Fixed(
        position = Position(8.0, 70.0, 8.0),
        yaw = 135.0,
        pitch = 25.0,
        dimension = "minecraft:overworld",
    ),
)
```

Only objects in the camera dimension are considered visible.

## RenderRequest reference

| Field | Default | Constraint / purpose |
| --- | --- | --- |
| `width`, `height` | `1280 × 720` | Each 64–8192; total at most 16 × 1024 × 1024 pixels |
| `camera` | `Auto` | One of the camera strategies above |
| `fieldOfViewDegrees` | `70.0` | 10–150 degrees |
| `nearPlane` | `0.05` | Positive and finite |
| `renderDistance` | `128.0` | Finite and greater than the near plane |
| `transparentBackground` | `false` | Keeps the background alpha channel transparent |
| `showHud` | `false` | Draws the renderer's compact HUD |
| `showDebugOverlay` | `false` | Adds debug information to the frame |
| `strictAssets` | `false` | Turns missing/invalid assets into exceptions instead of warnings/fallbacks |

Invalid geometric request values fail immediately with `IllegalArgumentException`. Missing files passed to `RenderAssets` fail with `SandboxException(INPUT_FORMAT)`. In strict mode, a missing resource becomes `RESOURCE_NOT_FOUND` and invalid asset data becomes `INPUT_FORMAT`.

## Read frame metadata

`RenderedFrame.metadata` records enough context to explain an image:

- dimensions, camera description, and dimension;
- visible block/entity and triangle counts;
- normalized asset source descriptions;
- structured render diagnostics with severity, code, message, and optional resource;
- world capture, asset resolution, scene build, rasterization, PNG encoding, and total timings in nanoseconds;
- `lightingModel = "approximate"` and `visualParity = false`.

Diagnostics are separate from `sandbox.world.outputs`; rendering must not modify the sandbox diagnostic stream or any modeled state. The renderer compares a snapshot before and after capture and fails if its own operation changed the world.

## Record GIF animations

Use one renderer so all frames resolve the same asset stack:

```kotlin
import moe.afox.dpsandbox.render.SandboxGifRecorder

val recorder = SandboxGifRecorder(
    renderer = renderer,
    request = RenderRequest(width = 480, height = 270),
    frameDelayMillis = 100,
    maximumFrames = 120,
)

repeat(20) {
    sandbox.runTicks(1)
    recorder.capture(sandbox)
}
recorder.export(repeat = 0).writeGif(Path.of("build/render/timeline.gif"))
```

All frames must have the same dimensions. Frame delay is 10–655,350 ms, `maximumFrames` is 1–1000, and the recorder default is 120. `repeat = 0` is passed to the GIF encoder as continuous looping. `export` does not clear frames; use `clear()` when starting a new capture.

For an already-rendered collection, call `encodeGif(List<GifAnimationFrame>, repeat)` directly. `RenderedAnimation` exposes defensive bytes plus width, height, frame count, total duration, and repeat metadata.

## Build a realtime JVM viewport

Static rendering captures, resolves, builds, rasterizes, and encodes for every call. A moving camera should compile geometry once:

```kotlin
import moe.afox.dpsandbox.render.SandboxRealtimeRenderer

val realtime = SandboxRealtimeRenderer(assets)
val scene = realtime.compile(sandbox)
var camera = scene.suggestedCamera

val liveFrame = realtime.render(
    scene = scene,
    camera = camera,
    width = 960,
    height = 540,
    fieldOfViewDegrees = 70.0,
    renderDistance = 128.0,
    showHud = true,
)
println("triangles=${liveFrame.triangles}, renderNanos=${liveFrame.renderNanos}")
```

`compile` captures all visible world geometry without camera-frustum culling and returns an immutable `CompiledRealtimeScene` with a suggested camera, bounds, visible counts, and triangle count. Repeated `render` calls move the camera over that captured scene and return an unencoded ARGB `BufferedImage`. Compile again after the sandbox changes.

`compileGpu` produces transferable-style vertex, index, material, and texture-atlas buffers for a JVM GPU backend. Datapack Sandbox supplies scene buffers, not a windowing toolkit or a complete OpenGL/Vulkan render loop; the host owns device creation, input, presentation, and resource disposal.

## Asset diagnostics checklist

When a frame shows fallback colors:

1. Confirm `metadata.assetSources` lists the expected client JAR/directory and resource packs.
2. Confirm the client asset version matches `sandbox.profile.id`.
3. Keep `minecraftAssets` beneath custom packs so their vanilla parents and textures resolve.
4. Inspect each diagnostic `code` and `resource`; do not judge only the final PNG.
5. Turn on `strictAssets` in CI after the local asset set is complete.

## Limitations

- The renderer is a clean-room approximation. Lighting is approximate and `visualParity` is deliberately false.
- Procedural fallbacks make missing assets visible and deterministic; they do not reproduce vanilla art.
- Image memory grows with resolution; GIF memory also grows with frame count, and compiled scenes grow with geometry/texture data.
- The host is responsible for asset acquisition, licensing, version matching, storage, and access control.
- Do not mutate the same sandbox concurrently while a frame or scene is being captured.

## Related pages

- [Rendering and Live Viewports](/en/guide/rendering-notebook)
- [Core API](/en/reference/core-api)
- [Serve JSONL](/en/reference/serve-jsonl)
- [Playground API](/en/reference/playground-api)
- [Reports and Observability](/en/reference/reports-observability)
