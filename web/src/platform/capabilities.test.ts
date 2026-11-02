import { afterEach, describe, expect, it, vi } from 'vitest'
import { detectCapabilities, NO_PLATFORM_CAPABILITIES } from './capabilities'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('detectCapabilities', () => {
  it('reports no OPFS when navigator is unavailable', async () => {
    vi.stubGlobal('navigator', undefined)

    await expect(detectCapabilities()).resolves.toEqual(NO_PLATFORM_CAPABILITIES)
  })

  it('reports no OPFS when storage.getDirectory is missing', async () => {
    vi.stubGlobal('navigator', { storage: {} })

    await expect(detectCapabilities()).resolves.toEqual({ opfs: false })
  })

  it('reports OPFS when getDirectory resolves', async () => {
    const getDirectory = vi.fn().mockResolvedValue({})
    vi.stubGlobal('navigator', { storage: { getDirectory } })

    await expect(detectCapabilities()).resolves.toEqual({ opfs: true })
    expect(getDirectory).toHaveBeenCalledTimes(1)
  })

  it('reports no OPFS when getDirectory rejects instead of throwing', async () => {
    const getDirectory = vi.fn().mockRejectedValue(new Error('denied'))
    vi.stubGlobal('navigator', { storage: { getDirectory } })

    await expect(detectCapabilities()).resolves.toEqual({ opfs: false })
  })
})
