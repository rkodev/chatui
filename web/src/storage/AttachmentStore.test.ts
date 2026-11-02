import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Blob as NodeBlob } from 'node:buffer'
import type { IDBPDatabase } from 'idb'
import { deleteDatabase, openDatabase, type ChatUiDB } from './db'
import {
  ATTACHMENT_STORAGE_PREFIX,
  AttachmentNotFoundError,
  AttachmentStore,
  InvalidStorageKeyError,
  sha256FromStorageKey,
  storageKeyFor,
} from './AttachmentStore'
import {
  ATTACHMENTS_DIRECTORY,
  createOpfsAttachmentFileSystem,
  toArrayBuffer,
  type AttachmentFileSystem,
} from '../platform/files'
import { createFakeOpfsRoot, type FakeDirectoryHandle } from '../platform/testing/fakeOpfs'

const shaA = 'a'.repeat(64)
const shaB = 'b'.repeat(64)
const shaC = 'c'.repeat(64)
const shaD = 'd'.repeat(64)
const shaE = 'e'.repeat(64)

const bytes = (value: string): Uint8Array => new TextEncoder().encode(value)

function expectBytes(actual: Uint8Array, expected: Uint8Array): void {
  expect(Array.from(actual)).toEqual(Array.from(expected))
}

function attachmentsDirectory(root: FakeDirectoryHandle): FakeDirectoryHandle {
  const directory = root.directories.get(ATTACHMENTS_DIRECTORY)
  if (directory === undefined) {
    throw new Error('attachments directory was not created')
  }
  return directory
}

function failingFileSystem(): AttachmentFileSystem {
  return {
    write: async () => {
      throw new Error('OPFS write failed')
    },
    read: async () => {
      throw new Error('OPFS read failed')
    },
    delete: async () => {
      throw new Error('OPFS delete failed')
    },
    list: async () => {
      throw new Error('OPFS list failed')
    },
    exists: async () => false,
  }
}

describe('AttachmentStore', () => {
  let db: IDBPDatabase<ChatUiDB>

  beforeEach(async () => {
    // fake-indexeddb uses Node's structuredClone, which cannot clone jsdom's
    // Blob. Use Node's Blob so the fallback record survives a round trip.
    vi.stubGlobal('Blob', NodeBlob)
    await deleteDatabase()
    db = await openDatabase()
  })

  afterEach(async () => {
    db.close()
    await deleteDatabase()
    vi.unstubAllGlobals()
  })

  it('stores bytes in OPFS and returns a content-addressed key', async () => {
    const root = createFakeOpfsRoot()
    const store = new AttachmentStore({
      db,
      capabilities: { opfs: true },
      fileSystem: createOpfsAttachmentFileSystem(root),
    })

    const key = await store.put(bytes('hello'), shaA)

    expect(key).toBe(`${ATTACHMENT_STORAGE_PREFIX}${shaA}`)
    expect(Array.from(attachmentsDirectory(root).files.get(shaA) ?? [])).toEqual(
      Array.from(bytes('hello')),
    )
    expectBytes(await store.get(key), bytes('hello'))
  })

  it('deduplicates identical bytes in OPFS', async () => {
    const root = createFakeOpfsRoot()
    const store = new AttachmentStore({
      db,
      capabilities: { opfs: true },
      fileSystem: createOpfsAttachmentFileSystem(root),
    })

    const first = await store.put(bytes('same'), shaA)
    const second = await store.put(bytes('same'), shaA)

    expect(second).toBe(first)
    expect(attachmentsDirectory(root).files.size).toBe(1)
  })

  it('falls back to IndexedDB when OPFS is unavailable', async () => {
    const store = new AttachmentStore({ db, capabilities: { opfs: false } })

    const key = await store.put(bytes('fallback'), shaB)

    expect(key).toBe(`${ATTACHMENT_STORAGE_PREFIX}${shaB}`)
    const record = await db.get('attachmentBlobs', shaB)
    expect(record?.size).toBe(bytes('fallback').byteLength)
    expectBytes(await store.get(key), bytes('fallback'))
  })

  it('deduplicates identical bytes in the IndexedDB fallback', async () => {
    const store = new AttachmentStore({ db, capabilities: { opfs: false } })

    const first = await store.put(bytes('same'), shaB)
    const second = await store.put(bytes('same'), shaB)

    expect(second).toBe(first)
    await expect(db.getAllKeys('attachmentBlobs')).resolves.toEqual([shaB])
  })

  it('falls back to IndexedDB when OPFS operations fail', async () => {
    const store = new AttachmentStore({
      db,
      capabilities: { opfs: true },
      fileSystem: failingFileSystem(),
    })

    const key = await store.put(bytes('failed opfs'), shaC)

    expectBytes(await store.get(key), bytes('failed opfs'))
    await expect(db.getAllKeys('attachmentBlobs')).resolves.toEqual([shaC])
    await expect(store.delete(key)).resolves.toBeUndefined()
    await expect(db.getAllKeys('attachmentBlobs')).resolves.toEqual([])
  })

  it('deletes bytes from OPFS', async () => {
    const root = createFakeOpfsRoot()
    const store = new AttachmentStore({
      db,
      capabilities: { opfs: true },
      fileSystem: createOpfsAttachmentFileSystem(root),
    })
    const key = await store.put(bytes('delete me'), shaA)

    await store.delete(key)

    expect(attachmentsDirectory(root).files.has(shaA)).toBe(false)
    await expect(store.get(key)).rejects.toBeInstanceOf(AttachmentNotFoundError)
  })

  it('reports missing reads and deletes explicitly', async () => {
    const store = new AttachmentStore({ db, capabilities: { opfs: false } })

    await expect(store.get(`${ATTACHMENT_STORAGE_PREFIX}missing`)).rejects.toBeInstanceOf(
      AttachmentNotFoundError,
    )
    await expect(store.delete(`${ATTACHMENT_STORAGE_PREFIX}missing`)).rejects.toBeInstanceOf(
      AttachmentNotFoundError,
    )
  })

  it('rejects malformed storage keys', async () => {
    const store = new AttachmentStore({ db, capabilities: { opfs: false } })

    await expect(store.get('not-a-storage-key')).rejects.toBeInstanceOf(InvalidStorageKeyError)
    await expect(store.put(bytes('x'), '')).rejects.toBeInstanceOf(InvalidStorageKeyError)
    expect(() => sha256FromStorageKey('attachments/')).toThrow(InvalidStorageKeyError)
    expect(() => storageKeyFor('a/b')).toThrow(InvalidStorageKeyError)
  })

  it('removes only unreferenced OPFS files and fallback blobs', async () => {
    const root = createFakeOpfsRoot()
    const store = new AttachmentStore({
      db,
      capabilities: { opfs: true },
      fileSystem: createOpfsAttachmentFileSystem(root),
    })

    await store.put(bytes('a'), shaA)
    await store.put(bytes('b'), shaB)
    await store.put(bytes('c'), shaC)
    await db.put('attachmentBlobs', {
      sha256: shaD,
      blob: new Blob([toArrayBuffer(bytes('d'))]),
      size: bytes('d').byteLength,
      createdAt: new Date().toISOString(),
    })
    await db.put('attachmentBlobs', {
      sha256: shaE,
      blob: new Blob([toArrayBuffer(bytes('e'))]),
      size: bytes('e').byteLength,
      createdAt: new Date().toISOString(),
    })

    const removed = await store.cleanupOrphans(new Set([shaA, shaD]))

    expect(removed.sort()).toEqual(
      [storageKeyFor(shaB), storageKeyFor(shaC), storageKeyFor(shaE)].sort(),
    )
    expect([...attachmentsDirectory(root).files.keys()]).toEqual([shaA])
    await expect(db.getAllKeys('attachmentBlobs')).resolves.toEqual([shaD])
  })

  it('cleans fallback blobs when OPFS is unavailable', async () => {
    const store = new AttachmentStore({ db, capabilities: { opfs: false } })

    await store.put(bytes('keep'), shaA)
    await store.put(bytes('drop'), shaB)

    const removed = await store.cleanupOrphans(new Set([shaA]))

    expect(removed).toEqual([`${ATTACHMENT_STORAGE_PREFIX}${shaB}`])
    await expect(db.getAllKeys('attachmentBlobs')).resolves.toEqual([shaA])
  })
})
