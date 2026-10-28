import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/actuator': {
        target: process.env.BACKEND_URL ?? 'http://127.0.0.1:8080',
      },
    },
  },
})
