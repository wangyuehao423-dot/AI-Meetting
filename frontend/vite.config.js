import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 从这里开始是新增的：前端开发服务器配置。
// 为什么需要它：main.jsx 与 nlsTranscriptionClient.js 里的请求都写成同源相对路径
// （/api/... 与 /ws/...），开发时前端跑在 5173、后端跑在 18080，必须靠代理把这两类
// 前缀转发到后端；ws: true 是给 WebSocket 用的，不写这一行 /ws/interviews/... 的
// 升级握手会被前端开发服务器自己截胡，连接直接失败。
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // REST 接口统一走 /api 前缀（与后端各 Controller 的 @RequestMapping 一致）。
      '/api': {
        target: 'http://localhost:18080',
        changeOrigin: true
      },
      // WebSocket：面试会话的实时通道（转写分片上报 + ping/pong 保活）。
      '/ws': {
        target: 'ws://localhost:18080',
        ws: true
      }
    }
  }
});
// 新增结束
