import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'

export default defineConfig({
  root: fileURLToPath(new URL('./fixture', import.meta.url)),
  plugins: [vue()],
  worker: { format: 'es' },
  server: {
    host: '127.0.0.1',
    port: 14173,
    strictPort: true,
  },
})
