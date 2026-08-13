# Interactive Playground

## When to use this page

Use `@datapack-sandbox/vitepress-playground` to embed an executable MCFunction notebook or a single command cell in VitePress. Execution, completion, diagnostics, imported files, world state, and approximate rendering stay in a browser Worker; no Java service is deployed.

[[playground-demo]]

## Prerequisites

Target browsers need module Workers, transferable `ArrayBuffer`, Blob URLs, Web Crypto, and `createImageBitmap`/OffscreenCanvas for rendering.

```bash
npm install @datapack-sandbox/vitepress-playground
```

Exclude the package from Vite's development pre-bundle so the Worker URL remains relative to its published `dist` module:

```ts
import { defineConfig } from 'vitepress'

export default defineConfig({
  vite: {
    optimizeDeps: {
      exclude: ['@datapack-sandbox/vitepress-playground'],
    },
  },
})
```

## Minimal runnable example

```vue
<script setup lang="ts">
import DpsPlayground from '@datapack-sandbox/vitepress-playground'
import '@datapack-sandbox/vitepress-playground/style.css'

const notebook = {
  version: '26.2',
  cells: [
    { type: 'markdown', source: '# Persistent local world' },
    { id: 'setup', type: 'code', source: 'setblock 0 0 2 minecraft:stone' },
  ],
}
</script>

<template>
  <DpsPlayground :notebook="notebook" :render="{ auto: true }" />
</template>
```

The component is SSR-safe and creates its isolated module Worker only after browser mount. Refreshing or unmounting destroys the in-memory session; imported content is neither uploaded nor persisted to IndexedDB/OPFS.

## Full capabilities

### Single-cell embed

Use the `/cell` entry for one editable example. It retains completion, diagnostics, keyboard execution, rendering, checkpoints, and GIF export while omitting the notebook toolbar and Markdown cells.

[[cell-demo]]

```vue
<script setup lang="ts">
import { ref } from 'vue'
import DpsCell from '@datapack-sandbox/vitepress-playground/cell'
import '@datapack-sandbox/vitepress-playground/style.css'

const source = ref('say embedded example')
</script>

<template>
  <DpsCell v-model="source" version="26.2" />
</template>
```

### Presets, dependencies, and imports

`presets` registers static ZIPs by id with optional `sha256` verification. `dependencies` load in declaration order before `ready`, with later packs overriding earlier ones. Built-in pickers and drag-and-drop accept datapacks, resource packs, client JARs, and world directories or ZIPs.

Every path is normalized to `/`. Absolute paths, drive paths, `..`, control characters, duplicate entries, and archives over budget are rejected. A Minecraft client JAR is read only for models and textures; its classes are never executed.

### Supply client assets manually

The Web renderer does not bundle or download Minecraft client assets, consume a server/JVM filesystem path, or automatically inspect the browser machine's `.minecraft` directory. For matching-version models and textures, click **Import files** in the component (or drop a file) and select the local client JAR; `.jar` files are inferred as `client-jar`. The import lives only in the current Worker session, so select it again after a page refresh or session disposal.

When you own the session, explicitly pass the bytes from a browser `File`:

```ts
await session.connect()
await session.importArchive(
  'client-jar',
  clientJar.name,
  await clientJar.arrayBuffer(),
)
```

`dependencies` declares datapacks and resource packs only; it cannot implicitly load a client JAR. The Worker extracts only `assets/` entries from that JAR and never executes class files. See [Playground API Reference](/en/reference/playground-api#client-asset-import) for the complete types and budgets.

### Shared worlds and the realtime viewport

Components on one page can share a serialized Worker session by using the same `sandbox-id`:

```vue
<DpsCell v-model="builder" sandbox-id="tutorial-world" />
<DpsCell v-model="inspector" sandbox-id="tutorial-world" :viewport="true" />
```

Each editor keeps independent source, diagnostics, and output, while commands, imports, checkpoints, resets, and viewport state target the same world. Alternatively create a `PlaygroundSessionController` and connect `DpsPlayground`, `DpsCell`, or a standalone `DpsViewport`. Realtime frames use a lazy WebGL2 chunk; static PNG and GIF output continue through the shared software renderer.

### Static deployment

```bash
npm ci
npm run docs:build
```

Deploy the generated VitePress directory. Content-hashed Worker and profile assets can use long-lived immutable caching while HTML uses normal revalidation.

## Component API

The former props, events, shared-session, and error-code catalog has moved to the [Playground API Reference](/en/reference/playground-api). This heading remains so existing section links lead to the new authoritative entry.

## Worker protocol

Worker requests, events, transferable binary responses, lifecycle, and stable error codes are now maintained in the [Playground API Reference](/en/reference/playground-api#worker-protocol).

## Limitations

- Default budgets protect browser stability; they are not a security boundary for untrusted code.
- Execution cooperatively interrupts only at MCFunction command boundaries. Changes from completed commands are not rolled back.
- A watchdog timeout terminates the Worker and ends in-flight requests with `SESSION_LOST`; the old world is not falsely restored.
- Rendering is deterministic clean-room approximation with `visualParity: false`, not pixel-identical vanilla output.
- Without an imported client JAR, the Web renderer uses built-in fallbacks; it does not obtain client assets from the notebook `version`.
- A custom `worker-url` must be same-origin or served with response headers suitable for a module Worker.

## Related pages

- [Playground API Reference](/en/reference/playground-api)
- [Playground CSS Customization](/en/guide/playground-styling)
- [Rendering, Animation, and Realtime Viewports](/en/guide/rendering-notebook)
- [Serve JSONL Protocol](/en/reference/serve-jsonl)
