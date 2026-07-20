# @datapack-sandbox/vitepress-playground

Persistent MCFunction notebook cells for VitePress. The component connects to a separately deployed Datapack Sandbox Playground API; it does not execute Minecraft commands in the browser.

The editor supports backend completions with <kbd>Tab</kbd> confirmation, debounced diagnostics, persistent execution state, sandbox reset, and restoring the original notebook example after editing.

```vue
<script setup>
import DpsPlayground from '@datapack-sandbox/vitepress-playground'
import '@datapack-sandbox/vitepress-playground/style.css'

const notebook = {
  version: '26.2',
  cells: [
    { type: 'markdown', source: '# Try it' },
    { type: 'code', source: 'setblock 0 0 2 minecraft:stone' },
  ],
}
</script>

<template>
  <DpsPlayground
    api-url="https://playground.example.com"
    :notebook="notebook"
    :render="{ auto: true, width: 960, height: 540 }"
  />
</template>
```

See the repository's `docs/playground.md` for the component API, notebook schema, WebSocket protocol, Docker deployment, session limits, presets, error codes, and fallback behavior.
