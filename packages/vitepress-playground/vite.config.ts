import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'node:path'

export default defineConfig({
  plugins: [vue()],
  build: {
    lib: {
      entry: resolve(__dirname, 'src/index.ts'),
      formats: ['es'],
      fileName: 'index',
    },
    rollupOptions: {
      external: (id) => id === 'vue' || id === 'markdown-it' || id === 'codemirror' || id.startsWith('@codemirror/') || id.startsWith('@lezer/'),
      output: {
        assetFileNames: (asset) => asset.name?.endsWith('.css') ? 'style.css' : '[name][extname]',
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./tests/setup.ts'],
  },
})
