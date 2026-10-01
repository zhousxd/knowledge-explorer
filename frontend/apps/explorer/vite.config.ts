import { fileURLToPath, URL } from 'node:url';
import vue from '@vitejs/plugin-vue';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: [
      { find: '@ke/shared/src', replacement: fileURLToPath(new URL('../../packages/shared/src', import.meta.url)) },
      { find: '@ke/shared', replacement: fileURLToPath(new URL('../../packages/shared/src/index.ts', import.meta.url)) }
    ]
  },
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      // 免登录分享端点(不在 /api 前缀下):GET /s/{token} permitAll + POST /s/{token}/continue。
      // bypass:浏览器对 /s/{token} 的文档导航(Accept 含 text/html)回 SPA 由前端路由接管,
      // 否则会被转发到 Spring 拿到 JSON/406;fetch 的 API 请求(Accept: application/json)继续代理
      // —— 与生产 nginx 必须做的 Accept 区分同构(记 Phase 8 部署清单)。
      '/s': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        bypass: (req) =>
          req.headers.accept && String(req.headers.accept).includes('text/html') ? '/index.html' : undefined
      }
    }
  },
  test: { environment: 'jsdom', setupFiles: ['./src/__tests__/setup.ts'] }
});
