import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 同源部署：开发时 /api 代理到后端 8080，生产由 nginx 托管 dist 并反代 /api。
// 这样后端不需要任何 CORS 配置，SSE 也不会经过跨域链路。
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: false,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: false,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
  },
})
