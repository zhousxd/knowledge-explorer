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
  test: { environment: 'jsdom' }
});
