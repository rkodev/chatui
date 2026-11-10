/**
 * Cache allowlist shared by the generated service worker and the app tests.
 *
 * The service worker may only store the app shell and hashed static assets.
 * API requests, runtime configuration, and anything user-specific must never
 * enter the Cache API. Keeping the policy in one module lets the build-time
 * worker generator and the tests agree on the same rules.
 */

/** Exact same-origin paths that are safe to precache. */
export const CACHEABLE_EXACT_PATHS = [
  '/index.html',
  '/manifest.webmanifest',
  '/favicon.ico',
] as const

/** Same-origin path prefixes that contain only build output or icons. */
export const CACHEABLE_PATH_PREFIXES = ['/assets/', '/icons/'] as const

/** Paths that must always bypass the Cache API. */
export const NEVER_CACHE_PATHS = ['/config.json', '/api/'] as const

/** Cache name prefix. The build appends a content hash. */
export const CACHE_NAME_PREFIX = 'chatui-shell-'

/** The cached document used as the offline navigation fallback. */
export const SHELL_PATH = '/index.html'

/**
 * Return true when a pathname must never be read from or written to the Cache
 * API. This is checked before the navigation and asset branches so a request
 * such as `/api/chat` or `/config.json` always reaches the network.
 */
export function isNeverCachePath(pathname: string): boolean {
  return NEVER_CACHE_PATHS.some(
    (path) => pathname === path || pathname.startsWith(path),
  )
}

/**
 * Return true when a same-origin pathname is part of the app shell or a hashed
 * static asset. The check is intentionally an allowlist: unknown paths pass
 * through to the network and are never cached.
 */
export function isCacheablePath(pathname: string): boolean {
  if (isNeverCachePath(pathname)) {
    return false
  }

  return (
    (CACHEABLE_EXACT_PATHS as readonly string[]).includes(pathname) ||
    CACHEABLE_PATH_PREFIXES.some((prefix) => pathname.startsWith(prefix))
  )
}

/**
 * Normalize and filter a precache list.
 *
 * Entries may be absolute URLs or root-relative paths. The result is a sorted,
 * de-duplicated list of root-relative paths that satisfy the allowlist. This is
 * the only list the generated service worker precaches.
 */
export function filterPrecacheEntries(entries: readonly string[]): string[] {
  const seen = new Set<string>()
  const result: string[] = []

  for (const entry of entries) {
    let pathname: string

    try {
      pathname = entry.startsWith('http') ? new URL(entry).pathname : entry
    } catch {
      continue
    }

    if (!pathname.startsWith('/') || !isCacheablePath(pathname)) {
      continue
    }

    if (seen.has(pathname)) {
      continue
    }

    seen.add(pathname)
    result.push(pathname)
  }

  return result.sort()
}
