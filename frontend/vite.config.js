import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    host: '0.0.0.0',
    port: 5173,
    proxy: {
      // 指向 8081 的 spring-boot:run 实例：它从 target/classes 跑，
      // 改后端代码 devtools 会自动热重启，不用每次重新打 jar
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true
      },
      // 数据同步走 WebSocket（后端 @ServerEndpoint("/websocket/{type}")）。
      // 开发态 5173 必须显式开 ws 才能把 upgrade 请求转到 8081；
      // 生产同源，前端按 location 直接拼 ws(s):// 地址，不经这里。
      '/websocket': {
        target: 'ws://localhost:8081',
        ws: true,
        changeOrigin: true
      }
    }
  }
})
