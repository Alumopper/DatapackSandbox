import { createApp } from 'vue'
import { strToU8, zipSync } from 'fflate'
import DpsCell from '../../../src/DpsCell.vue'
import DpsPlayground from '../../../src/DpsPlayground.vue'
import '../../../src/style.css'

const query = new URLSearchParams(location.search)
const offline = query.has('offline')
const dark = query.has('dark')
const version = query.get('version') ?? '26.2'
const renderWidth = Number(query.get('width') ?? 320)
const renderHeight = Number(query.get('height') ?? 180)
const animationWidth = Number(query.get('animationWidth') ?? Math.min(renderWidth, 480))
const animationHeight = Number(query.get('animationHeight') ?? Math.max(16, Math.round(renderHeight * animationWidth / renderWidth)))
const animationDelayMs = Number(query.get('animationDelayMs') ?? 250)
const captureOnExecute = query.get('captureOnExecute') !== 'false'
const viewport = query.has('viewport') ? { autoplay: false, tickRate: 20, pointerLock: true } : false
const dependencySources = query.has('dependencies')
  ? [
      {
        kind: 'datapack' as const,
        name: 'dependency-data.zip',
        url: archiveUrl({
          'pack.mcmeta': strToU8('{"pack":{"pack_format":107.1,"description":"cell dependency"}}'),
          'data/demo/function/dependency.mcfunction': strToU8('setblock 0 0 2 minecraft:stone'),
        }),
      },
      {
        kind: 'resource-pack' as const,
        name: 'dependency-assets.zip',
        url: archiveUrl({
          'pack.mcmeta': strToU8('{"pack":{"pack_format":82,"description":"cell resource dependency"}}'),
        }),
      },
    ]
  : []
if (query.has('cell')) {
  createApp(DpsCell, {
    modelValue: query.has('dependencies') ? 'function demo:dependency' : 'setblock 0 0 2 minecraft:stone',
    version,
    theme: dark ? 'dark' : 'auto',
    workerUrl: offline ? '/missing-playground-worker.js' : undefined,
    siteId: 'playwright-cell-smoke',
    dependencies: dependencySources,
    viewport,
  }).mount('#app')
} else {
  createApp(DpsPlayground, {
    workerUrl: offline ? '/missing-playground-worker.js' : undefined,
    notebook: {
      version,
      cells: [
        { type: 'markdown', source: '# Browser smoke test' },
        { id: 'stone', type: 'code', source: 'setblock 0 0 2 minecraft:stone' },
      ],
    },
    render: { auto: true, width: renderWidth, height: renderHeight },
    animation: { width: animationWidth, height: animationHeight, delayMs: animationDelayMs, repeat: 0, captureOnExecute },
    theme: dark ? 'dark' : 'auto',
    siteId: 'playwright-smoke',
    viewport,
  }).mount('#app')
}

function archiveUrl(files: Record<string, Uint8Array>): string {
  const archive = zipSync(files)
  const bytes = new Uint8Array(archive.byteLength)
  bytes.set(archive)
  return URL.createObjectURL(new Blob([bytes.buffer], { type: 'application/zip' }))
}
