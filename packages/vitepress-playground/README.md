# @datapack-sandbox/vitepress-playground

Persistent MCFunction notebook cells that execute entirely in an isolated browser Worker. No Java or WebSocket service is required.

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

The package provides execution, completion, diagnostics, persistent state, reusable checkpoints, deterministic animated GIF export, in-memory file/ZIP imports, presets with optional SHA-256 verification, interruption/watchdog recovery, and approximate clean-room rendering through transferable buffers. Display entities share their normalized transformations, billboard modes, item-definition/model lookup, readable text styling, lighting controls, and tick interpolation with the JVM renderer. Import a matching client JAR or resource pack for referenced visual assets; rendering deliberately keeps `visualParity: false`.

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
enabled, 70° field of view, movement speed 6, and a dynamic 0.5–2 pixel ratio. Input is observable
but does not simulate vanilla physics, collision, entity AI, or redstone. WebGL context loss pauses
playback and rebuilds GPU resources; PNG/GIF export always stays on the software renderer.
