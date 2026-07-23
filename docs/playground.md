# Interactive playground

`@datapack-sandbox/vitepress-playground` adds persistent MCFunction notebook cells to a VitePress page. Execution, completion, diagnostics, imported files, world state, and approximate rendering stay inside a dedicated browser Worker. No Java service, WebSocket endpoint, Docker image, or CORS allowlist is required.

[[playground-demo]]

The example above starts a new isolated Worker after the component mounts. User files are read into transferable `ArrayBuffer` values and are never uploaded or written to IndexedDB/OPFS. Refreshing the page discards the session.

## Install

```bash
npm install @datapack-sandbox/vitepress-playground
```

Register the component in a VitePress theme or import it from a client-only Vue component:

```ts
import DpsPlayground from '@datapack-sandbox/vitepress-playground'
import '@datapack-sandbox/vitepress-playground/style.css'
```

```vue
<DpsPlayground
  :notebook="{
    version: '26.2',
    cells: [
      { type: 'markdown', source: '# Persistent local world' },
      { id: 'setup', type: 'code', source: 'setblock 0 0 2 minecraft:stone' },
    ],
  }"
  :render="{ auto: true, width: 960, height: 540 }"
/>
```

The component is SSR-safe: it creates a module Worker only after browser mount. The UI entry does not statically include the Kotlin runtime; Vite emits a separate content-hashed Worker asset.

## Single-cell embed

Use the separate `cell` entry when an example only needs one editable command cell and its execution result. `DpsCell` has no notebook toolbar, interactive imports, or Markdown cells. Its compact header includes execution/render controls, a reusable state point, GIF frame capture/export, and **Reset example**.

[[cell-demo]]

```vue
<script setup lang="ts">
import { ref } from 'vue'
import DpsCell from '@datapack-sandbox/vitepress-playground/cell'
import '@datapack-sandbox/vitepress-playground/style.css'

const source = ref('say embedded example')
</script>

<template>
  <DpsCell
    v-model="source"
    version="26.2"
    :dependencies="[
      { kind: 'datapack', url: '/examples/shared-functions.zip' },
      { kind: 'resource-pack', url: '/examples/preview-assets.zip', sha256: '…' },
    ]"
  />
</template>
```

Dependencies are fetched in declaration order before `ready`; later packs override earlier packs. They support optional SHA-256 verification, remain in session memory after **Reset example**, and are reloaded automatically if the Worker is rebuilt. Automatic PNG rendering after **Run** is disabled by default, while the explicit **Render** button is always available. Opt in to automatic rendering with `:render="{ auto: true, width: 640, height: 360 }"`.

Each successful execution records a GIF frame by default. **Add frame** captures the current world without executing source; **Export GIF** downloads all recorded frames. **Save point** records the complete modeled world, outputs, and traces, while **Return** restores that point without consuming it. Datapack/resource-pack inputs and safety-budget counters are session configuration rather than checkpoint state.

Each `DpsCell` owns an isolated local Worker session, supports completion and diagnostics, and runs with <kbd>Ctrl/⌘</kbd>+<kbd>Enter</kbd>. It additionally exposes `savePoint()`, `returnToPoint()`, `captureAnimationFrame()`, and `exportGif()`, and emits `gif` and `checkpoint` alongside `ready`, `executed`, and `error`.

## Component API

| Prop | Type | Default | Meaning |
| --- | --- | --- | --- |
| `notebook` | `PlaygroundNotebook` | required | Version, ordered cells, and optional preset id. |
| `theme` | `auto \| light \| dark` | `auto` | Explicit theme or VitePress dark-mode inheritance. |
| `layout` | `notebook \| compact` | `notebook` | Full notebook or reduced spacing. |
| `read-only` | `boolean` | `false` | Prevent source edits while keeping execution available. |
| `render` | `PlaygroundRenderOptions` | auto, `960×540` | Automatic rendering and default dimensions. |
| `animation` | `PlaygroundAnimationOptions` | `480×270`, 250 ms, loop | GIF dimensions, frame delay, repeat count, and capture-on-execute behavior. |
| `checkpoint-name` | `string` | component-specific | Name used by the built-in Save point/Return controls. |
| `presets` | `Record<string, { url; sha256? }>` | `{}` | Static ZIP registry fetched lazily from same-origin or CORS-enabled URLs. |
| `allow-import` | `boolean` | `true` | Show file/folder import controls and accept drops. |
| `limits` | `PlaygroundBrowserLimits` | browser defaults | Per-instance stability budgets and watchdog timings. |
| `worker-url` | `string` | packaged asset | Override only when self-hosting the Worker artifact. |
| `site-id` | `string` | omitted | Optional embedding-site label carried in session creation. |

`ready(sessionId)` fires after the local session and optional preset are ready. `error({ code, message })` reports execution, import, integrity, and lifecycle failures.

The notebook schema remains stable:

```ts
interface PlaygroundNotebook {
  version: string
  preset?: string
  cells: Array<
    | { id?: string; type: 'markdown'; source: string }
    | { id?: string; type: 'code'; source: string }
  >
}
```

Cells execute in order against one persistent world. **Reset sandbox** creates a fresh world while preserving edited source. **Restore example** restores the original notebook, clears output, and creates a fresh world.

## Presets and imports

Register immutable preset ZIPs by id:

```vue
<DpsPlayground
  :notebook="{ version: '26.2', preset: 'starter', cells }"
  :presets="{
    starter: {
      url: '/playground-presets/starter.4f2d.zip',
      sha256: 'b4f0…64 hexadecimal characters…',
    },
  }"
/>
```

The ZIP is fetched only when selected. When `sha256` is present, the browser verifies it before transferring the archive to the Worker.

The built-in picker, directory picker, and drop target accept datapack ZIPs/directories, resource packs, client JARs, and world directories/ZIPs. Inputs that cannot be identified unambiguously display a type selector. Every virtual path is normalized to `/`; absolute paths, drive paths, `..`, control characters, and duplicate entries are rejected. Datapack functions under both current `data/<namespace>/function` and legacy `functions` directories become available to `function` commands.

Rendering uses the same perspective camera, blockstate/model baking, depth buffer, texture sampling, lighting, and fog math as the JAR fallback renderer. Imported resource packs and client JARs supply model JSON and PNG textures; only assets referenced by the current scene are decoded, and they remain in memory for the session.

`block_display`, `item_display`, and `text_display` use the same normalized
display state on JVM and Web. This includes transformations, fixed/vertical/
horizontal/center billboards, brightness and shadow controls, readable styled
text, modern item-definition lookup, generated sprite extrusion, model display
transforms, and tick/teleport interpolation. Decomposed transforms linearly
interpolate translation and scale while using normalized shortest-arc SLERP for
both quaternions; 16-number matrices retain component-wise linear interpolation.
Import the matching client JAR or resource pack when the example
depends on vanilla or custom visual assets.

## Limits and lifecycle

Defaults are stability budgets, not a browser security boundary:

| Limit | Default |
| --- | ---: |
| Cell source | 64 KiB |
| Structured output | 1 MiB |
| Commands per execution | 10,000 |
| Output events | 2,000 |
| Render size | 1,920 × 1,080 |
| Named checkpoints | 32, 8 MiB each |
| GIF recording | 120 frames, 64 MiB RGBA |
| Expanded imports | 64 MiB |
| Imported files | 16,384 |
| Request watchdog | 15 s |
| Cancellation grace | 2 s |

Execution yields at MCFunction command boundaries. **Interrupt** sets a cancellation flag, so state from completed commands remains. If a request ignores cancellation past the grace period, the client terminates and rebuilds the Worker, rejects in-flight work with `SESSION_LOST`, and creates a clean session automatically.

Each component owns exactly one Worker. Multiple components never share world state. Unmounting terminates the Worker and revokes every render Blob URL.

## Worker protocol

`PlaygroundWorkerClient` replaces the removed WebSocket `PlaygroundClient` export. It preserves request ids, request names, event names, and stable error objects. Supported requests are:

- `session.create`, `session.reset`, `session.interrupt`, `session.close`, and `session.import`
- `session.checkpoint.save`, `.restore`, `.delete`, and `.list`
- `cell.execute`, `cell.complete`, `cell.check`, and `cell.render`
- `animation.capture`, `animation.export`, and `animation.clear`

Execution emits `cell.status`, `cell.output`, `diagnostic`, `cell.render`, and `cell.error`. Render events use `bytes: ArrayBuffer` with `mimeType: image/png`; GIF exports use the same transferable shape with `mimeType: image/gif`. Neither format uses base64.

Common codes include `INVALID_REQUEST`, `PROFILE_NOT_ALLOWED`, `CELL_TOO_LARGE`, `COMMAND_LIMIT`, `OUTPUT_LIMIT`, `RENDER_SIZE_LIMIT`, `BUSY`, `INTERRUPTED`, `SESSION_LOST`, `CHECKPOINT_NOT_FOUND`, `CHECKPOINT_LIMIT`, `ANIMATION_EMPTY`, `ANIMATION_FRAME_LIMIT`, `ANIMATION_SIZE_LIMIT`, `IMPORT_PATH_INVALID`, `IMPORT_CONFLICT`, `IMPORT_FILE_LIMIT`, `IMPORT_SIZE_LIMIT`, and `PRESET_INTEGRITY_FAILED`.

## Rendering boundary

Rendering uses the project's deterministic clean-room software rasterizer. It returns RGBA-derived PNG output and metadata with `lightingModel: approximate` and `visualParity: false`. GIF frames use the shared Kotlin adaptive-palette/LZW encoder, so JVM and Web exports from identical RGBA frames are byte-for-byte consistent. Screenshots must not be described as pixel-perfect vanilla output. Custom font-provider stacks, multi-layer/special item models, glow outlines, the client light map, and post-processing remain outside the parity claim. Imported resource assets are session inputs; unsupported asset details fall back to deterministic procedural colors.

## Static deployment

Build VitePress normally:

```bash
npm ci
npm run docs:build
```

Deploy the generated static directory. Keep content-hashed Worker/profile assets cacheable with a long immutable lifetime, while the HTML entry uses normal revalidation. A custom `worker-url` must be same-origin or served with headers that allow a module Worker. No Java runtime, reverse-proxy upgrade configuration, API origin allowlist, or Docker service is involved.

Modern browsers must support module Workers, transferable `ArrayBuffer`, `createImageBitmap`/`OffscreenCanvas`, Blob URLs, and Web Crypto for optional preset integrity checks.

## Realtime WebGL viewport

Pass `viewport` to `DpsPlayground` or `DpsCell`, or mount `DpsViewport` directly. A shared
`PlaygroundSessionController` keeps all components on one Worker-owned world:

```ts
const session = new PlaygroundSessionController({ notebook })
```

Playback starts paused. It advances display interpolation and world time at 20 TPS, executes
`#minecraft:tick` plus an optional `tickFunction`, catches up at most five ticks, and pauses while
the page is hidden. Desktop controls are pointer-lock mouse look, WASD, Space, Shift, and wheel
speed; touch devices get two joysticks. Input targets `Steve` by default and is recorded in traces
and snapshots without adding vanilla physics.

The renderer is a separate lazy WebGL2 chunk. Scene revisions transfer independent static-block,
entity, index, and atlas buffers; camera movement changes uniforms only. Context loss pauses
playback and rebuilds GPU resources after restoration. Static PNG and GIF export continue through
the shared software renderer.

The controller exposes `connect`, `execute`, `reset`, `restoreExample`, checkpoint and import
methods, `play`, `pause`, `step`, `dispatchInput`, scene subscription, and `dispose`. Viewport events
are `play-state`, `camera-change`, `input`, `frame-stats`, and `context-lost`.
