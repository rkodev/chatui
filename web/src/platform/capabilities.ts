/**
 * Platform capability detection. Keep this module free of storage imports so
 * callers can probe the browser without opening IndexedDB or OPFS.
 */
export interface PlatformCapabilities {
  /** True when the origin private file system can be opened. */
  readonly opfs: boolean
}

/** Shared immutable result for browsers without OPFS. */
export const NO_PLATFORM_CAPABILITIES: PlatformCapabilities = Object.freeze({ opfs: false })

/**
 * Detect OPFS support without throwing. A missing API, a rejected
 * `getDirectory()` call, or a non-browser environment all resolve to
 * `{ opfs: false }` so callers can fall back deterministically.
 */
export async function detectCapabilities(): Promise<PlatformCapabilities> {
  if (typeof navigator === 'undefined' || typeof navigator.storage?.getDirectory !== 'function') {
    return NO_PLATFORM_CAPABILITIES
  }

  try {
    await navigator.storage.getDirectory()
    return { opfs: true }
  } catch {
    return NO_PLATFORM_CAPABILITIES
  }
}
