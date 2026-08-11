import { createApp } from 'vue'
import DpsPlayground from '@datapack-sandbox/vitepress-playground'
import '@datapack-sandbox/vitepress-playground/style.css'

createApp(DpsPlayground, {
  notebook: {
    version: '26.2',
    cells: [
      { id: 'packaged', type: 'code', source: 'say packaged Worker is ready' },
    ],
  },
  siteId: 'packaged-consumer-smoke',
  animation: { captureOnExecute: false },
}).mount('#app')
