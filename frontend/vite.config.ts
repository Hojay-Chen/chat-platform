/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  test: {
    // 五种 Surface 的测试跑在 node + renderToStaticMarkup 上, 不需要 jsdom:
    // 要断言的是"渲染出了什么结构", 而不是"点下去发生了什么" —— 后者是浏览器的事,
    // 而这里真正想钉住的是 SurfaceHost 对 manifest 的解释, 那是一段纯函数式的逻辑。
    environment: 'node',
    include: ['src/**/*.test.{ts,tsx}'],
  },
  server: {
    host: '0.0.0.0',
    port: 5173,
    proxy: {
      // G5 —— 双服务分流。顺序敏感: /agent/api 必须在 /api 之前,
      // 否则伴侣域请求会被默认规则抢去 8081。物理 URL 前缀由
      // src/api/client.ts 的 route() 决定(与生产 nginx G7 同一套规则):
      //   /agent/api/... → 8091(仿真 Agent 平台: 伴侣 CRUD/记忆/关系/生活/…)
      //   /api/...       → 8081(聊天平台: auth/会话/消息/事件流/应用)
      '/agent/api': {
        target: 'http://127.0.0.1:8091',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/agent/, ''),
      },
      '/api': {
        target: 'http://127.0.0.1:8081',
        changeOrigin: true,
      },
    },
  },
})
