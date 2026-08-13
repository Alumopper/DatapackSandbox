# Playground API Reference

## When to use this page

Use this page after the basic [Playground embed guide](/en/guide/playground) when you need the exact Vue component boundary, a shared Worker session, direct controller/client calls, import policy, viewport events, or recoverable-error handling. The package runs the clean-room runtime locally in a browser Worker; it does not call a hosted Datapack Sandbox service.

## Prerequisites

```ts
import {
  DpsCell,
  DpsPlayground,
  DpsViewport,
  PlaygroundSessionController,
} from '@datapack-sandbox/vitepress-playground'
import '@datapack-sandbox/vitepress-playground/style.css'
```

Serve the site over HTTP(S). Module Workers do not run from `file://`. If Vite moves the package Worker into a dependency-optimizer cache without its asset, add `@datapack-sandbox/vitepress-playground` to `vite.optimizeDeps.exclude` and restart with a fresh optimizer cache.

::: warning Browser rendering also needs explicit client assets
The browser cannot use a JVM filesystem path, and the package does not scan `.minecraft` or download a client JAR from `notebook.version`. To render real Minecraft models and textures, the user or host must explicitly import a matching client JAR as local bytes. Without it, the viewport uses deterministic fallbacks.
:::

## Minimal runnable component

```vue
<script setup lang="ts">
import { ref } from 'vue'
import { DpsCell, type PlaygroundEvent } from '@datapack-sandbox/vitepress-playground'
import '@datapack-sandbox/vitepress-playground/style.css'

const source = ref('say hello from the browser')
const lastResult = ref<PlaygroundEvent>()
</script>

<template>
  <DpsCell
    v-model="source"
    version="26.2"
    sandbox-id="guide-demo"
    :render="{ auto: false, width: 960, height: 540 }"
    :allow-import="true"
    @executed="lastResult = $event"
    @error="console.error($event)"
  />
</template>
```

Use a stable `sandboxId` only for components that are intentionally meant to share one world. Generate a different id for unrelated documents on the same page.

## Choose a component

| Component | Primary use | State ownership |
| --- | --- | --- |
| `DpsCell` | One editable `.mcfunction` cell with diagnostics/results | Own session, `sandboxId` page session, or explicit `session` |
| `DpsPlayground` | Multi-cell notebook with markdown/code cells and action bar | Same three ownership modes |
| `DpsViewport` | Live scene, camera, keyboard/touch input, frame stats | Joins a session or creates one from a notebook |

`DpsCell` defaults to `version="26.2"`; `DpsPlayground` requires a `PlaygroundNotebook` whose `version` selects a packaged browser profile. A notebook cell is `{type:'markdown', source}` or `{type:'code', source}`, with an optional stable `id`.

## Component props

The components expose a deliberately shared vocabulary:

| Group | Props | Purpose |
| --- | --- | --- |
| Session | `session`, `sandboxId`, `siteId`, `workerUrl` | Explicit, page-level, or owned Worker session |
| Runtime | `version`, `notebook`, `dependencies`, `presets`, `limits` | Profile, sources, fetched packs, policy and budgets |
| Rendering | `render`, `viewport`, `animation` | Static size/auto-render, live controls, GIF capture |
| UI | `theme`, `layout`, `readOnly`, `compact`, `showDetails`, `actions` | Presentation and available actions |
| Import | `allowImport` | Enables user-driven local file/folder/archive import |
| Localization | `locale`, `labels` | Built-in `en`/`zh-CN` strings and label overrides |

`DpsPlayground` requires `notebook` and also accepts `checkpointName`. `DpsCell` requires `modelValue`, emits `update:modelValue`, and adds `cellId`/`dependencies`/`compact`. `DpsViewport` may take an explicit `session`, create its own session from `notebook`, or join `sandboxId`; viewport options control FPS/tick rate, autoplay/tick function, input player, keyboard/touch/pointer lock, toolbar, FOV, move speed, mouse sensitivity, and pixel-ratio bounds.

`actions` maps an action name to `primary`, `menu`, or `hidden`. Public actions include run/render/run-all, interrupt, save/return checkpoint, capture/export GIF, reset/restore example, imports, and restart.

## Events

All public payloads use `PlaygroundEvent` or one of the narrower exported types. Important event families are:

| Event | Meaning / useful fields |
| --- | --- |
| `ready` | The Worker session is available |
| `error` | `PlaygroundClientError` or structured error data |
| `executed` | Cell reached its terminal idle result |
| `gif` | Encoded GIF event with MIME type/bytes |
| `checkpoint` | Save/restore/delete outcome |
| `play-state` | Playing/paused simulation state |
| `camera-change` | Current position, yaw, pitch, speed, auto/manual state |
| `input` | Normalized keyboard/mouse/touch input |
| `frame-stats` | FPS, frame time, pixel ratio, triangles, revision |
| `context-lost` | WebGL context can no longer present the scene |

`DpsCell` additionally emits `update:modelValue`; `DpsCell` and `DpsPlayground` emit execution/GIF/checkpoint events appropriate to their actions. Unsubscribe application listeners when the surrounding view is destroyed.

## Share one session explicitly

The shortest page-level sharing mechanism is a common `sandboxId`. The first owner must provide a notebook/version; later joiners must use the same profile. A version mismatch is rejected instead of silently rebuilding another world under the same id.

For deterministic ownership, create a controller:

```ts
import {
  PlaygroundSessionController,
  type PlaygroundEvent,
} from '@datapack-sandbox/vitepress-playground'

const controller = new PlaygroundSessionController({
  notebook: {
    version: '26.2',
    cells: [{ id: 'main', type: 'code', source: 'say shared' }],
  },
  render: { auto: false, width: 960, height: 540 },
  limits: { maximumCommands: 10_000 },
})

const stopEvents = controller.onEvent((event: PlaygroundEvent) => {
  if (event.type === 'cell.output') console.log(event.output)
})
const stopActivity = controller.onActivity(({ busy, operation, pending }) => {
  updateToolbar({ busy, operation, pending })
})

await controller.connect()
await controller.execute('main', 'say shared')

// When the owning page is destroyed:
stopEvents()
stopActivity()
controller.dispose()
```

Pass the same controller through the `session` prop to cells, playgrounds, and viewports. The owner alone calls `dispose()`. The controller serializes exclusive mutations, pauses playback when the document becomes hidden, and keeps scene subscriptions from creating competing Worker worlds.

## Controller operations

| Group | Methods |
| --- | --- |
| Lifecycle | `connect`, `reset`, `restoreExample`, `dispose` |
| Code | `execute`, `complete`, `check`, `readFunction`, `interrupt` |
| Rendering | `render`, `subscribeScene`, `refreshScene` |
| Simulation/input | `play`, `pause`, `step`, `dispatchInput` |
| Checkpoints | `saveCheckpoint`, `restoreCheckpoint`, `deleteCheckpoint`, `listCheckpoints` |
| Animation | `captureAnimationFrame`, `exportAnimation`, `clearAnimation` |
| Imports | `importEntries`, `importArchive` |
| Subscriptions | `onEvent`, `onConnection`, `onActivity` |

`complete`, `check`, and `readFunction` are read-oriented calls. World-changing and capture operations are serialized through the controller; activity reports the active operation, cell id, and number pending. `dispatchInput` is not placed behind the exclusive queue so input can remain responsive.

## Import datapacks, packs, worlds, and client assets

With `allowImport`, users can select **Import files**, choose a folder, or drag/drop supported content. Direct integrations may transfer entries or one archive:

```ts
await controller.importArchive(
  'datapack',
  datapackFile.name,
  await datapackFile.arrayBuffer(),
)

await controller.importArchive(
  'client-jar',
  clientJar.name,
  await clientJar.arrayBuffer(),
)
```

`PlaygroundImportKind` is `datapack | resource-pack | client-jar | world`. Client JAR import is special: the archive reader keeps only supported entries below `assets/`. The selected bytes remain within the in-memory Worker session and disappear when it is disposed or rebuilt.

`PlaygroundDependencySource.kind` is only `datapack | resource-pack`. A client JAR cannot be declared as a URL dependency; it must be explicitly selected/provided as bytes. This prevents `version` from silently becoming a download instruction and makes the asset/license boundary visible to the host.

## Presets and URL dependencies

Presets are opt-in names registered by the host as `{url, sha256?}`. A notebook may refer only to a registered preset. Dependencies are explicit `{kind,url,sha256?,name?}` values loaded in order. Both fetch with same-origin credentials and are subject to import limits.

Use SHA-256 for content that influences an executable example. Integrity requires `crypto.subtle`; a mismatch is non-recoverable for that request. Keep URL allow-lists in application code—neither a notebook nor user-authored markdown should be able to nominate an arbitrary remote dependency.

## Lower-level Worker client

`PlaygroundWorkerClient` exposes `connect`, `createSession`, execution/check/completion, render, checkpoints, animation, playback/input, viewport subscription, imports, reset/interrupt, event/connection subscriptions, and `close`. Use it only when the controller's serialized ownership does not fit a custom architecture.

Requests carry generated `web-N` ids and resolve on operation-specific terminal events. The default request timeout is 15 seconds. On timeout, the client asks the Worker to interrupt; if it does not stop within the default 2-second grace period, the Worker is terminated, rebuilt, and the remembered session configuration is recreated. In-memory execution state/checkpoints/imports are not a durable recovery store—keep source documents outside the Worker.

## Browser limits

Defaults are conservative and may be lowered through `limits`:

| Limit | Default |
| --- | ---: |
| Cell source | 64 KiB |
| Combined output bytes | 1 MiB |
| Commands | 10,000 |
| Output events | 2,000 |
| Render dimensions | 1920 × 1080 (configurable only within 16–4096 per axis) |
| Checkpoints / bytes per checkpoint | 32 / 8 MiB |
| Animation frames / bytes | 120 / 64 MiB |
| Import expanded bytes / files | 64 MiB / 16,384 |

Invalid configured values fall back to defaults; imports are bounded while streaming and while expanding archives, including responses without a trustworthy `Content-Length`.

## Errors and recovery

`PlaygroundClientError` has `code`, `message`, `recoverable`, and optional `details`. Common categories:

- setup: `API_UNAVAILABLE`, `PROFILE_NOT_ALLOWED`, `SANDBOX_ID_INVALID`, `SANDBOX_VERSION_MISMATCH`, `NOTEBOOK_REQUIRED`;
- session: `SESSION_LOST`, `BUSY`, `WORKER_RUNTIME_ERROR`;
- presets/dependencies: `PRESET_NOT_ALLOWED`, `PRESET_FETCH_FAILED`, `PRESET_INTEGRITY_FAILED`, `DEPENDENCY_FETCH_FAILED`, `DEPENDENCY_INTEGRITY_FAILED`;
- imports/budgets: `IMPORT_SIZE_LIMIT`, `IMPORT_FILE_LIMIT`, `CELL_TOO_LARGE`, `RENDER_SIZE_LIMIT`, `ANIMATION_FRAME_LIMIT`.

Retry a recoverable fetch/transient error only with backoff and a user-visible state. For non-recoverable errors, disable the affected action or rebuild from the externally stored notebook. A `context-lost` viewport can be recreated without assuming the Worker world was lost; a `SESSION_LOST` error requires session reconstruction.

## Deployment and security notes

- The packaged Worker must be served with a JavaScript MIME type. Custom cross-origin `workerUrl` values require compatible CORS and CSP `worker-src` policy.
- Worker execution is local, but preset/dependency fetches are network requests and imports process untrusted archives. Keep byte/file/command limits enabled.
- Page disposal should terminate owned Workers; otherwise navigation can leave memory and simulation timers alive.
- Client assets, packs, and world data may be copyrighted or sensitive. They remain local by default, but the embedding page must not upload them without a separate explicit action.

## Limitations

- Browser profiles and the clean-room engine model a bounded datapack runtime, not a full Minecraft client/server.
- Imported client assets enable supported models/textures; they do not change `visualParity` into a vanilla guarantee.
- Worker memory is ephemeral. Persist notebook source and intentional exports outside it.
- Multiple components sharing a session observe the same mutations and must present busy/activity state coherently.

## Related pages

- [Playground Embed](/en/guide/playground)
- [Playground Styling](/en/guide/playground-styling)
- [Rendering and Live Viewports](/en/guide/rendering-notebook)
- [Renderer API](/en/reference/renderer-api)
- [Serve JSONL](/en/reference/serve-jsonl)
