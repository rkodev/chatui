import { describe, expect, it } from 'vitest'
import {
  CACHEABLE_EXACT_PATHS,
  CACHEABLE_PATH_PREFIXES,
  NEVER_CACHE_PATHS,
  filterPrecacheEntries,
  isCacheablePath,
  isNeverCachePath,
} from './cachePolicy'

describe('cachePolicy', () => {
  it('allows only the app shell, manifest, icons, and hashed assets', () => {
    for (const path of CACHEABLE_EXACT_PATHS) {
      expect(isCacheablePath(path), path).toBe(true)
    }

    for (const prefix of CACHEABLE_PATH_PREFIXES) {
      expect(isCacheablePath(`${prefix}example-1234.js`), prefix).toBe(true)
    }

    expect(isCacheablePath('/')).toBe(false)
    expect(isCacheablePath('/settings')).toBe(false)
    expect(isCacheablePath('/api/chat')).toBe(false)
    expect(isCacheablePath('/config.json')).toBe(false)
    expect(isCacheablePath('/user/export.json')).toBe(false)
  })

  it('never caches runtime config or API paths', () => {
    for (const path of NEVER_CACHE_PATHS) {
      expect(isNeverCachePath(path), path).toBe(true)
      expect(isCacheablePath(path), path).toBe(false)
    }

    expect(isNeverCachePath('/config.json')).toBe(true)
    expect(isNeverCachePath('/api/v1/chat/completions')).toBe(true)
    expect(isNeverCachePath('/apix')).toBe(false)
  })

  it('filters precache entries to sorted, unique allowlisted paths', () => {
    const entries = [
      'https://chat.example.com/assets/app-9f8e.js',
      '/assets/app-9f8e.js',
      '/assets/app-9f8e.js',
      '/index.html',
      '/manifest.webmanifest',
      '/icons/icon-192.png',
      '/config.json',
      '/api/chat',
      '/user/data.json',
      '/',
      'not-a-path',
      '/assets/vendor-2b3c.css',
    ]

    expect(filterPrecacheEntries(entries)).toEqual([
      '/assets/app-9f8e.js',
      '/assets/vendor-2b3c.css',
      '/icons/icon-192.png',
      '/index.html',
      '/manifest.webmanifest',
    ])
  })

  it('drops malformed absolute URLs instead of throwing', () => {
    expect(filterPrecacheEntries(['http://[invalid', '/index.html'])).toEqual(['/index.html'])
  })
})
