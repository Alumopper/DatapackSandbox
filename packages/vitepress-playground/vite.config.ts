import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'node:path'

export default defineConfig({
  base: './',
  plugins: [vue()],
  worker: { format: 'es' },
  build: {
    lib: {
      entry: {
        index: resolve(__dirname, 'src/index.ts'),
        cell: resolve(__dirname, 'src/cell.ts'),
      },
      formats: ['es'],
      fileName: (_format, entryName) => `${entryName}.js`,
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
