import { fileURLToPath } from 'node:url'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// Provider presets are imported from the repository contracts tree, which sits
// outside the Vite root (`web/`). Allow the dev server to read that directory.
const repoRoot = fileURLToPath(new URL('..', import.meta.url))

export default defineConfig({
  plugins: [react()],
  // History routing relies on the SPA fallback, which is Vite's default app type.
  appType: 'spa',
  server: {
    port: 5173,
    fs: {
      allow: [repoRoot],
    },
  },
  preview: {
    port: 4173,
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    restoreMocks: true,
  },
})
