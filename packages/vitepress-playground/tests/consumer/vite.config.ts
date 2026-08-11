import { defineConfig } from 'vite'
import { fileURLToPath } from 'node:url'

const consumerRoot = fileURLToPath(new URL('./', import.meta.url))

export default defineConfig({
  root: fileURLToPath(new URL('./fixture', import.meta.url)),
  cacheDir: fileURLToPath(new URL('./node_modules/.vite', import.meta.url)),
  base: '/datapack-index/',
  optimizeDeps: {
    // Vite 5 relocates import.meta.url during dependency optimization but
    // does not copy the packaged Worker next to the generated deps chunk.
    exclude: ['@datapack-sandbox/vitepress-playground'],
  },
  worker: { format: 'es' },
  server: {
    host: '127.0.0.1',
    port: Number(process.env.DPS_PLAYGROUND_CONSUMER_E2E_PORT ?? 14174),
    strictPort: true,
    fs: { allow: [consumerRoot] },
  },
})
