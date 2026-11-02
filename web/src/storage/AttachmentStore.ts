import type { IDBPDatabase } from 'idb'
import { nowIso } from '../domain'
import { detectCapabilities, type PlatformCapabilities } from '../platform/capabilities'
import {
  createOpfsAttachmentFileSystem,
  getOpfsRoot,
  isNotFoundError,
  toArrayBuffer,
  type AttachmentFileSystem,
} from '../platform/files'
import type { AttachmentBlobRecord, ChatUiDB } from './db'

export const ATTACHMENT_STORAGE_PREFIX = 'attachments/'

export class AttachmentNotFoundError extends Error {
  readonly storageKey: string

  constructor(storageKey: string) {
    super(`Attachment bytes not found: ${storageKey}`)
    this.name = 'AttachmentNotFoundError'
    this.storageKey = storageKey
  }
}

export class InvalidStorageKeyError extends Error {
  readonly storageKey: string

  constructor(storageKey: string) {
    super(`Invalid attachment storage key: ${storageKey}`)
    this.name = 'InvalidStorageKeyError'
    this.storageKey = storageKey
  }
}

export interface AttachmentStoreOptions {
  db: IDBPDatabase<ChatUiDB>
  /**
   * Override capability detection. Tests use this to force the IndexedDB
   * fallback without touching the real `navigator.storage` API.
   */
  capabilities?: PlatformCapabilities
  /**
   * Inject a fake OPFS implementation. `null` disables OPFS even when the
   * capability result says it is available.
   */
  fileSystem?: AttachmentFileSystem | null
  now?: () => string
}

export function storageKeyFor(sha256: string): string {
  if (sha256.length === 0 || sha256.includes('/')) {
    throw new InvalidStorageKeyError(`${ATTACHMENT_STORAGE_PREFIX}${sha256}`)
  }
  return `${ATTACHMENT_STORAGE_PREFIX}${sha256}`
}

export function sha256FromStorageKey(storageKey: string): string {
  if (!storageKey.startsWith(ATTACHMENT_STORAGE_PREFIX)) {
    throw new InvalidStorageKeyError(storageKey)
  }

  const sha256 = storageKey.slice(ATTACHMENT_STORAGE_PREFIX.length)
  if (sha256.length === 0 || sha256.includes('/')) {
    throw new InvalidStorageKeyError(storageKey)
  }
  return sha256
}

/**
 * Content-addressed attachment byte store.
 *
 * OPFS is the primary backend. When OPFS is unavailable, or an OPFS operation
 * fails, the same bytes are read from and written to the `attachmentBlobs`
 * IndexedDB store keyed by SHA-256. No provider upload or network call happens
 * here.
 */
export class AttachmentStore {
  private readonly db: IDBPDatabase<ChatUiDB>
  private readonly capabilities: PlatformCapabilities | undefined
  private readonly injectedFileSystem: AttachmentFileSystem | null | undefined
  private readonly now: () => string
  private fileSystemPromise: Promise<AttachmentFileSystem | null> | undefined

  constructor(options: AttachmentStoreOptions) {
    this.db = options.db
    this.capabilities = options.capabilities
    this.injectedFileSystem = options.fileSystem
    this.now = options.now ?? nowIso
  }

  private async fileSystem(): Promise<AttachmentFileSystem | null> {
    if (this.injectedFileSystem !== undefined) {
      return this.injectedFileSystem
    }

    if (this.fileSystemPromise === undefined) {
      this.fileSystemPromise = this.openFileSystem()
    }

    return this.fileSystemPromise
  }

  private async openFileSystem(): Promise<AttachmentFileSystem | null> {
    const capabilities = this.capabilities ?? (await detectCapabilities())
    if (!capabilities.opfs) {
      return null
    }

    try {
      return createOpfsAttachmentFileSystem(await getOpfsRoot())
    } catch {
      return null
    }
  }

  /**
   * Store bytes under `attachments/<sha256>`. Identical bytes deduplicate
   * because the key is derived from the caller-provided SHA-256.
   */
  async put(bytes: Uint8Array, sha256: string): Promise<string> {
    const storageKey = storageKeyFor(sha256)
    const fileSystem = await this.fileSystem()

    if (fileSystem !== null) {
      try {
        if (await fileSystem.exists(sha256)) {
          return storageKey
        }
        await fileSystem.write(sha256, bytes)
        return storageKey
      } catch {
        // Fall through to the IndexedDB Blob fallback.
      }
    }

    const existing = await this.db.get('attachmentBlobs', sha256)
    if (existing !== undefined) {
      return storageKey
    }

    const record: AttachmentBlobRecord = {
      sha256,
      blob: new Blob([toArrayBuffer(bytes)]),
      size: bytes.byteLength,
      createdAt: this.now(),
    }
    await this.db.put('attachmentBlobs', record)
    return storageKey
  }

  /**
   * Read bytes for a storage key. OPFS is tried first; a missing or failing
   * OPFS read falls back to IndexedDB. A key absent from both backends throws
   * `AttachmentNotFoundError` instead of returning empty bytes.
   */
  async get(storageKey: string): Promise<Uint8Array> {
    const sha256 = sha256FromStorageKey(storageKey)
    const fileSystem = await this.fileSystem()

    if (fileSystem !== null) {
      try {
        return await fileSystem.read(sha256)
      } catch {
        // Fall through to the IndexedDB Blob fallback.
      }
    }

    const record = await this.db.get('attachmentBlobs', sha256)
    if (record !== undefined) {
      return new Uint8Array(await record.blob.arrayBuffer())
    }

    throw new AttachmentNotFoundError(storageKey)
  }

  /**
   * Delete bytes from both backends. A key absent from both throws
   * `AttachmentNotFoundError`; a non-missing OPFS error is surfaced when the
   * fallback also has nothing to delete.
   */
  async delete(storageKey: string): Promise<void> {
    const sha256 = sha256FromStorageKey(storageKey)
    const fileSystem = await this.fileSystem()
    let removed = false
    let opfsError: unknown

    if (fileSystem !== null) {
      try {
        await fileSystem.delete(sha256)
        removed = true
      } catch (error) {
        if (!isNotFoundError(error)) {
          opfsError = error
        }
      }
    }

    const record = await this.db.get('attachmentBlobs', sha256)
    if (record !== undefined) {
      await this.db.delete('attachmentBlobs', sha256)
      removed = true
    }

    if (!removed) {
      if (opfsError !== undefined) {
        throw opfsError
      }
      throw new AttachmentNotFoundError(storageKey)
    }
  }

  /**
   * Remove every OPFS file and fallback Blob whose SHA-256 is not in the
   * caller-provided referenced set. Returns the removed storage keys.
   */
  async cleanupOrphans(referencedSha256: Set<string>): Promise<string[]> {
    const removed = new Set<string>()
    const fileSystem = await this.fileSystem()

    if (fileSystem !== null) {
      try {
        for (const name of await fileSystem.list()) {
          if (!referencedSha256.has(name)) {
            await fileSystem.delete(name)
            removed.add(storageKeyFor(name))
          }
        }
      } catch {
        // OPFS cleanup is best-effort; the fallback store is still cleaned.
      }
    }

    const fallbackKeys = await this.db.getAllKeys('attachmentBlobs')
    for (const sha256 of fallbackKeys) {
      if (!referencedSha256.has(sha256)) {
        await this.db.delete('attachmentBlobs', sha256)
        removed.add(storageKeyFor(sha256))
      }
    }

    return [...removed]
  }
}
