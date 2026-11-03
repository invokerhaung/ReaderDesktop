import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const backend = 'http://127.0.0.1:1122'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': backend,
      '/healthz': backend,
      '/img': backend,
      '/media': backend,
      '/pdf': backend,
      '/epub': backend,
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
})
