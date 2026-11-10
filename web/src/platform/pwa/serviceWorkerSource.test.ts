import { describe, expect, it } from 'vitest'
import { createServiceWorkerSource } from './serviceWorkerSource'

const source = createServiceWorkerSource({
  cacheName: 'chatui-shell-abc123',
  precache: ['/index.html', '/assets/app-1.js', '/manifest.webmanifest'],
})

describe('createServiceWorkerSource', () => {
  it('embeds the versioned cache and filtered precache list', () => {
    expect(source).toContain('const CACHE_NAME = "chatui-shell-abc123"')
    expect(source).toContain(
      'const PRECACHE = ["/index.html","/assets/app-1.js","/manifest.webmanifest"]',
    )
  })

  it('never precaches runtime config, API paths, or user data', () => {
    const precache = source.match(/const PRECACHE = (\[[^\]]*\]);/u)?.[1]
    expect(precache).toBeDefined()
    expect(precache).not.toContain('/config.json')
    expect(precache).not.toContain('/api/')
    expect(source).toContain('const NEVER_CACHE_PATHS = ["/config.json","/api/"]')
  })

  it('serves the shell for offline navigations and bypasses uncacheable requests', () => {
    expect(source).toContain("if (request.mode === 'navigate')")
    expect(source).toContain('cache.match(SHELL)')
    expect(source).toContain('if (isNeverCachePath(url.pathname))')
    expect(source).toContain("if (request.method !== 'GET')")
    expect(source).toContain('if (url.origin !== self.location.origin)')
    expect(source).toContain('if (!isCacheablePath(url.pathname))')
  })

  it('deletes old shell caches during activation and skips waiting on message', () => {
    expect(source).toContain("key.startsWith('chatui-shell-') && key !== CACHE_NAME")
    expect(source).toContain("event.data.type === 'SKIP_WAITING'")
    expect(source).toContain('self.skipWaiting()')
  })
})
