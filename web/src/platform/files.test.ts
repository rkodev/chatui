import { afterEach, describe, expect, it, vi } from 'vitest'
import { ATTACHMENTS_DIRECTORY, createOpfsAttachmentFileSystem, getOpfsRoot } from './files'
import { createFakeOpfsRoot } from './testing/fakeOpfs'

const bytes = (value: string): Uint8Array => new TextEncoder().encode(value)

function expectBytes(actual: Uint8Array, expected: Uint8Array): void {
  expect(Array.from(actual)).toEqual(Array.from(expected))
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('OpfsAttachmentFileSystem', () => {
  it('writes, reads, lists, and deletes files under the attachments directory', async () => {
    const root = createFakeOpfsRoot()
    const fileSystem = createOpfsAttachmentFileSystem(root)

    await fileSystem.write('one', bytes('first'))
    await fileSystem.write('two', bytes('second'))

    expect(root.directories.has(ATTACHMENTS_DIRECTORY)).toBe(true)
    expectBytes(await fileSystem.read('one'), bytes('first'))
    await expect(fileSystem.exists('one')).resolves.toBe(true)
    await expect(fileSystem.exists('missing')).resolves.toBe(false)
    await expect(fileSystem.list()).resolves.toEqual(['one', 'two'])

    await fileSystem.delete('one')
    await expect(fileSystem.exists('one')).resolves.toBe(false)
    await expect(fileSystem.list()).resolves.toEqual(['two'])
  })

  it('returns an empty list before the attachments directory exists', async () => {
    const fileSystem = createOpfsAttachmentFileSystem(createFakeOpfsRoot())

    await expect(fileSystem.list()).resolves.toEqual([])
    await expect(fileSystem.exists('missing')).resolves.toBe(false)
  })

  it('reports missing reads and deletes explicitly', async () => {
    const fileSystem = createOpfsAttachmentFileSystem(createFakeOpfsRoot())

    await expect(fileSystem.read('missing')).rejects.toMatchObject({ name: 'NotFoundError' })
    await expect(fileSystem.delete('missing')).rejects.toMatchObject({ name: 'NotFoundError' })
  })

  it('overwrites an existing file on write', async () => {
    const root = createFakeOpfsRoot()
    const fileSystem = createOpfsAttachmentFileSystem(root)

    await fileSystem.write('same', bytes('old'))
    await fileSystem.write('same', bytes('new'))

    expectBytes(await fileSystem.read('same'), bytes('new'))
  })
})

describe('getOpfsRoot', () => {
  it('throws when OPFS is unavailable', async () => {
    vi.stubGlobal('navigator', { storage: {} })

    await expect(getOpfsRoot()).rejects.toThrow('OPFS is not available')
  })

  it('returns the navigator directory handle when available', async () => {
    const root = createFakeOpfsRoot()
    const getDirectory = vi.fn().mockResolvedValue(root)
    vi.stubGlobal('navigator', { storage: { getDirectory } })

    await expect(getOpfsRoot()).resolves.toBe(root)
  })
})
