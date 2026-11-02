/**
 * Minimal OPFS abstraction for attachment bytes.
 *
 * The interfaces intentionally describe only the subset of the File System
 * Access API this app uses. Tests inject a fake directory handle, while
 * production passes the real handle returned by `navigator.storage`.
 */
export const ATTACHMENTS_DIRECTORY = 'attachments'

export interface WritableFileLike {
  write(data: BufferSource | Blob | string): Promise<void>
  close(): Promise<void>
}

export interface FileHandleLike {
  getFile(): Promise<Blob>
  createWritable(): Promise<WritableFileLike>
}

export interface DirectoryHandleLike {
  getDirectoryHandle(name: string, options?: { create?: boolean }): Promise<DirectoryHandleLike>
  getFileHandle(name: string, options?: { create?: boolean }): Promise<FileHandleLike>
  removeEntry(name: string, options?: { recursive?: boolean }): Promise<void>
  /**
   * Optional because the DOM typings do not always include the async iterable
   * mixin. Real OPFS handles implement it; a fake must implement it to list.
   */
  keys?(): AsyncIterableIterator<string>
}

export interface AttachmentFileSystem {
  write(name: string, bytes: Uint8Array): Promise<void>
  read(name: string): Promise<Uint8Array>
  delete(name: string): Promise<void>
  list(): Promise<string[]>
  exists(name: string): Promise<boolean>
}

/**
 * Copy a byte view into a plain `ArrayBuffer`. The DOM typings reject
 * `Uint8Array<ArrayBufferLike>` as a `BufferSource`/`BlobPart` because it may
 * be backed by a `SharedArrayBuffer`.
 */
export function toArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const copy = new Uint8Array(bytes.byteLength)
  copy.set(bytes)
  return copy.buffer
}

export function isNotFoundError(error: unknown): boolean {
  return (
    typeof error === 'object' &&
    error !== null &&
    'name' in error &&
    (error as { name?: unknown }).name === 'NotFoundError'
  )
}

export function notFound(name: string): Error {
  return new DOMException(`OPFS entry not found: ${name}`, 'NotFoundError')
}

/**
 * Attachment files live under a single `attachments` directory. The directory
 * is created lazily on the first write and never created by read-only calls.
 */
export class OpfsAttachmentFileSystem implements AttachmentFileSystem {
  private readonly root: DirectoryHandleLike

  constructor(root: DirectoryHandleLike) {
    this.root = root
  }

  private async directory(create: boolean): Promise<DirectoryHandleLike> {
    return this.root.getDirectoryHandle(ATTACHMENTS_DIRECTORY, { create })
  }

  async write(name: string, bytes: Uint8Array): Promise<void> {
    const directory = await this.directory(true)
    const handle = await directory.getFileHandle(name, { create: true })
    const writable = await handle.createWritable()

    try {
      await writable.write(toArrayBuffer(bytes))
    } finally {
      await writable.close()
    }
  }

  async read(name: string): Promise<Uint8Array> {
    const directory = await this.directory(false)
    const handle = await directory.getFileHandle(name)
    const file = await handle.getFile()
    return new Uint8Array(await file.arrayBuffer())
  }

  async delete(name: string): Promise<void> {
    const directory = await this.directory(false)
    await directory.removeEntry(name)
  }

  async list(): Promise<string[]> {
    let directory: DirectoryHandleLike

    try {
      directory = await this.directory(false)
    } catch (error) {
      if (isNotFoundError(error)) {
        return []
      }
      throw error
    }

    if (typeof directory.keys !== 'function') {
      throw new Error('OPFS directory listing is not supported by this handle')
    }

    const names: string[] = []
    for await (const name of directory.keys()) {
      names.push(name)
    }
    return names
  }

  async exists(name: string): Promise<boolean> {
    let directory: DirectoryHandleLike

    try {
      directory = await this.directory(false)
    } catch (error) {
      if (isNotFoundError(error)) {
        return false
      }
      throw error
    }

    try {
      await directory.getFileHandle(name)
      return true
    } catch (error) {
      if (isNotFoundError(error)) {
        return false
      }
      throw error
    }
  }
}

export function createOpfsAttachmentFileSystem(root: DirectoryHandleLike): AttachmentFileSystem {
  return new OpfsAttachmentFileSystem(root)
}

/**
 * Open the origin private file system. Throws when OPFS is unavailable; callers
 * that need a soft check should use `detectCapabilities()` first.
 */
export async function getOpfsRoot(): Promise<DirectoryHandleLike> {
  if (typeof navigator === 'undefined' || typeof navigator.storage?.getDirectory !== 'function') {
    throw new Error('OPFS is not available in this environment')
  }

  return navigator.storage.getDirectory()
}
