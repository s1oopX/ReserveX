import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'node:path'

// 08 §4.5:构建产物 dist/ 由 frontend-build 一次性服务拷进 frontend-dist 卷,
// Caddy 只读该卷。**dist 不入 git**(.gitignore),但**必须入镜像** —— 二者不矛盾:
// 镜像里的 dist 是 npm run build 在 builder 阶段现产的。
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { '@': path.resolve(__dirname, './src') },
  },
  server: {
    port: 5173,
    // 本地开发直连后端;生产走 Caddy 反代(08 §五),两处前缀必须都是 /api,
    // 否则"本地能跑、容器里 404"
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
  build: {
    outDir: 'dist',
    // 关掉 sourcemap:产物进公网卷,map 会把源码连注释一起暴露
    sourcemap: false,
    chunkSizeWarningLimit: 900,
  },
})
