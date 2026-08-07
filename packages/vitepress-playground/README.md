# @datapack-sandbox/vitepress-playground

Persistent MCFunction notebook cells that execute entirely in an isolated browser Worker. The Worker runs the same `:core` command engine as the JVM build, compiled to an ES module with TeaVM; no Java or WebSocket service is required at runtime.

```ts
import DpsPlayground from '@datapack-sandbox/vitepress-playground'
import '@datapack-sandbox/vitepress-playground/style.css'
```

```vue
<DpsPlayground
  :notebook="{
    version: '26.2',
    cells: [
      { type: 'markdown', source: '# Try it' },
      { type: 'code', source: 'setblock 0 0 2 minecraft:stone' },
    ],
  }"
/>
```

For a single cell plus its execution result, use the lightweight entry:

```vue
<script setup>
import { ref } from 'vue'
import DpsCell from '@datapack-sandbox/vitepress-playground/cell'

const source = ref('say embedded example')
</script>

<template>
  <DpsCell
    v-model="source"
    version="26.2"
    :dependencies="[
      { kind: 'datapack', url: '/examples/base.zip' },
      { kind: 'resource-pack', url: '/examples/assets.zip' },
    ]"
  />
</template>
```

The compact header provides Run, Render, Save point/Return, Add frame/Export GIF, and Reset example. Successful runs capture GIF frames by default. Named checkpoints are reusable and restore the complete modeled world while keeping declared dependencies loaded.

Use `sandbox-id` to share one page-local world without manually creating a controller. Repeating the
same ID is intentional: every editor keeps its own source and output, while commands, rendering,
checkpoints, imports, and viewport state use one Worker and are mutually serialized. Components
without `sandbox-id` always own separate, unique sandboxes.

```vue
<DpsCell v-model="setupSource" sandbox-id="demo-world" />
<DpsCell v-model="querySource" sandbox-id="demo-world" :viewport="true" />
```

The first component mounted for an ID initializes its Minecraft version, preset, and dependencies.
All components using that ID must use the same version. An explicit `session` takes precedence over
`sandbox-id`.

The package provides execution, completion, diagnostics, persistent state, reusable checkpoints, deterministic animated GIF export, in-memory file/ZIP imports, presets with optional SHA-256 verification, interruption/watchdog recovery, and approximate clean-room rendering through transferable buffers. Display entities share their normalized transformations, billboard modes, item-definition/model lookup, readable text styling, lighting controls, and tick interpolation with the JVM renderer. Import a matching client JAR or resource pack for referenced visual assets; rendering deliberately keeps `visualParity: false`.

Execution summaries expose an expandable, readable command-output list in addition to the raw structured result. Hold <kbd>Ctrl</kbd> (or <kbd>⌘</kbd>) and click an imported function id to open its effective source without leaving the cell; nested calls build a breadcrumb stack, and **Back** or <kbd>Alt</kbd>+<kbd>←</kbd> returns to the caller.

See the repository's `docs/playground.md` for the component API, Worker protocol, limits, imports, presets, rendering boundary, and static deployment guidance.

## Realtime viewport

`DpsViewport` is a lazily loaded WebGL2 view over the same Worker world. Share a
`PlaygroundSessionController` when a notebook, cell, and viewport must observe the same commands,
imports, checkpoints, and ticks:

```vue
<script setup lang="ts">
import { DpsPlayground, PlaygroundSessionController } from '@datapack-sandbox/vitepress-playground'

const notebook = { version: '26.2', cells: [{ type: 'code', source: 'setblock 0 0 0 minecraft:stone' }] }
const session = new PlaygroundSessionController({ notebook })
</script>

<template>
  <DpsPlayground :notebook="notebook" :session="session" :viewport="{ tickRate: 20 }" />
</template>
```

Defaults are 60 FPS, 20 TPS, autoplay off, input player `Steve`, keyboard/touch and pointer lock
enabled, 70° field of view, movement speed 6, mouse sensitivity 0.12, and a dynamic 0.5–2 pixel ratio. The toolbar settings panel adjusts sensitivity, speed, and field of view. Input is observable
but does not simulate vanilla physics, collision, entity AI, or redstone. WebGL context loss pauses
playback and rebuilds GPU resources; PNG/GIF export always stays on the software renderer.
