import { fileURLToPath, URL } from 'node:url';
import vue from '@vitejs/plugin-vue';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@ke/shared': fileURLToPath(new URL('../../packages/shared/src/index.ts', import.meta.url)) }
  },
  test: { environment: 'jsdom' }
});
