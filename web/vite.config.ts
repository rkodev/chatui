import { createHash } from 'node:crypto'
import { readdir, writeFile } from 'node:fs/promises'
import { join, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import react from '@vitejs/plugin-react'
import type { Plugin, ResolvedConfig } from 'vite'
import { defineConfig } from 'vitest/config'
import {
  CACHE_NAME_PREFIX,
  filterPrecacheEntries,
} from './src/platform/pwa/cachePolicy.ts'
import { createServiceWorkerSource } from './src/platform/pwa/serviceWorkerSource.ts'

// Provider presets are imported from the repository contracts tree, which sits
// outside the Vite root (`web/`). Allow the dev server to read that directory.
const repoRoot = fileURLToPath(new URL('..', import.meta.url))

async function collectFiles(directory: string): Promise<string[]> {
  const entries = await readdir(directory, { withFileTypes: true })
  const files: string[] = []

  for (const entry of entries) {
    const path = join(directory, entry.name)

    if (entry.isDirectory()) {
      files.push(...(await collectFiles(path)))
    } else if (entry.isFile()) {
      files.push(path)
    }
  }

  return files
}

/**
 * Emit the hand-written service worker after the production bundle is written.
 *
 * The precache list is discovered from the built output and filtered through
 * the shared allowlist, so `config.json`, API paths, and anything user-specific
 * can never be precached. The cache name includes a hash of the precache list,
 * which changes whenever the shell or a hashed asset changes.
 *
 * The plugin is build-only; development never emits or registers a worker.
 */
function pwaServiceWorkerPlugin(): Plugin {
  let config: ResolvedConfig

  return {
    name: 'chatui-pwa-service-worker',
    apply: 'build',
    configResolved(resolved) {
      config = resolved
    },
    async closeBundle() {
      const outDir = resolve(config.root, config.build.outDir)
      const files = await collectFiles(outDir)
      const entries = files.map((file) => {
        const path = relative(outDir, file).split(sep).join('/')
        return `/${path}`
      })
      const precache = filterPrecacheEntries(entries)
      const version = createHash('sha256').update(precache.join('\n')).digest('hex').slice(0, 12)
      const source = createServiceWorkerSource({
        cacheName: `${CACHE_NAME_PREFIX}${version}`,
        precache,
      })

      await writeFile(join(outDir, 'sw.js'), source)
    },
  }
}

export default defineConfig({
  plugins: [react(), pwaServiceWorkerPlugin()],
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
