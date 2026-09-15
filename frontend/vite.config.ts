/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      // import.meta.dirname 而非 __dirname —— vite 8 起 configLoader 默认走原生
      // ESM, __dirname 会触发 "unsupported by configLoader: native" 警告
      '@': path.resolve(import.meta.dirname, './src'),
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
      // G8 —— 单目标。开发态与生产态(nginx)现在是同一个形状: 浏览器只跟
      // 聊天平台说话, /api/** 全部进 8081。伴侣域(记忆/关系/生活/… )与流式聊天
      // 由 8081 在服务端转给 8091 —— 8091 在这条链路上不再有浏览器可见的入口。
      //
      // 这里曾有第二条 '/agent/api' → 8091 的代理(顺序敏感, 必须在 '/api' 之前),
      // 与前端 route() 的前缀改写配套。两者一并删除: 让浏览器直连两个后端,
      // 等于把"聊天平台的界面"定义成两个后端 API 的并集, 域名的边界和代码的边界
      // 对不上; 而且按路径段判归属的做法把 conversations/first 与 .../chat 判错了服务。
      '/api': {
        target: 'http://127.0.0.1:8081',
        changeOrigin: true,
      },
    },
  },
})
