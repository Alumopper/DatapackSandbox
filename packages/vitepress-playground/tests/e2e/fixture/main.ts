import { createApp } from 'vue'
import DpsPlayground from '../../../src/DpsPlayground.vue'
import '../../../src/style.css'

const offline = new URLSearchParams(location.search).has('offline')
const dark = new URLSearchParams(location.search).has('dark')
createApp(DpsPlayground, {
  apiUrl: offline ? 'http://127.0.0.1:9' : 'http://127.0.0.1:18080',
  notebook: {
    version: '26.2',
    cells: [
      { type: 'markdown', source: '# Browser smoke test' },
      { id: 'stone', type: 'code', source: 'setblock 0 0 2 minecraft:stone' },
    ],
  },
  render: { auto: true, width: 320, height: 180 },
  theme: dark ? 'dark' : 'auto',
  siteId: 'playwright-smoke',
}).mount('#app')
