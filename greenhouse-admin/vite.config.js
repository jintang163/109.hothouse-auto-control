import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 开发态代理：前端 5173 -> 后端 8080（REST 与 WebSocket）
export default defineConfig({
  plugins: [react()],
  server: {
    host: '0.0.0.0',
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/ws': { target: 'ws://localhost:8080', ws: true, changeOrigin: true }
    }
  }
})
