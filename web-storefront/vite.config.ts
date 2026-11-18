import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'
import { join } from 'node:path'
const runRoot = process.env.ZHICAI_RUN_ROOT ?? fileURLToPath(new URL('../.local', import.meta.url))
export default defineConfig({
  plugins: [vue()], publicDir: '../web-shared/public',
  resolve: { alias: { '@zhicai/shared': fileURLToPath(new URL('../web-shared/src', import.meta.url)) } },
  cacheDir: join(runRoot, 'vite-cache/storefront'),
  build: { outDir: join(runRoot, 'frontend/web-storefront/dist'), emptyOutDir: false },
  server: { proxy: { '/api': 'http://127.0.0.1:8080' } },
})
