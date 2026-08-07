import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'

export default defineConfig({
  root: fileURLToPath(new URL('./fixture', import.meta.url)),
  plugins: [vue()],
  worker: { format: 'es' },
  server: {
    host: '127.0.0.1',
    port: Number(process.env.DPS_PLAYGROUND_E2E_PORT ?? 14173),
    strictPort: true,
  },
})
