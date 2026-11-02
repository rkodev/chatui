import type { DirectoryHandleLike, FileHandleLike, WritableFileLike } from '../files'
import { notFound, toArrayBuffer } from '../files'

function toBytes(data: BufferSource | Blob | string): Promise<Uint8Array> {
  if (typeof data === 'string') {
    return Promise.resolve(new TextEncoder().encode(data))
  }
  if (data instanceof Blob) {
    return data.arrayBuffer().then((buffer) => new Uint8Array(buffer))
  }
  if (data instanceof ArrayBuffer) {
    return Promise.resolve(new Uint8Array(data))
  }
  if (ArrayBuffer.isView(data)) {
    return Promise.resolve(new Uint8Array(data.buffer, data.byteOffset, data.byteLength).slice())
  }
  return Promise.reject(new Error('Unsupported fake OPFS write payload'))
}

function concat(chunks: Uint8Array[]): Uint8Array {
  const size = chunks.reduce((total, chunk) => total + chunk.byteLength, 0)
  const result = new Uint8Array(size)
  let offset = 0
  for (const chunk of chunks) {
    result.set(chunk, offset)
    offset += chunk.byteLength
  }
  return result
}

class FakeFileHandle implements FileHandleLike {
  private readonly files: Map<string, Uint8Array>
  private readonly name: string

  constructor(files: Map<string, Uint8Array>, name: string) {
    this.files = files
    this.name = name
  }

  async getFile(): Promise<Blob> {
    const bytes = this.files.get(this.name)
    if (bytes === undefined) {
      throw notFound(this.name)
    }
    return new Blob([toArrayBuffer(bytes)])
  }

  async createWritable(): Promise<WritableFileLike> {
    const chunks: Uint8Array[] = []

    return {
      write: async (data) => {
        chunks.push(await toBytes(data))
      },
      close: async () => {
        this.files.set(this.name, concat(chunks))
      },
    }
  }
}

/**
 * In-memory stand-in for an OPFS directory handle. It implements only the
 * subset used by `OpfsAttachmentFileSystem`.
 */
export class FakeDirectoryHandle implements DirectoryHandleLike {
  readonly directories = new Map<string, FakeDirectoryHandle>()
  readonly files = new Map<string, Uint8Array>()

  async getDirectoryHandle(
    name: string,
    options: { create?: boolean } = {},
  ): Promise<DirectoryHandleLike> {
    const existing = this.directories.get(name)
    if (existing !== undefined) {
      return existing
    }
    if (options.create !== true) {
      throw notFound(name)
    }

    const directory = new FakeDirectoryHandle()
    this.directories.set(name, directory)
    return directory
  }

  async getFileHandle(name: string, options: { create?: boolean } = {}): Promise<FileHandleLike> {
    if (!this.files.has(name) && options.create !== true) {
      throw notFound(name)
    }
    return new FakeFileHandle(this.files, name)
  }

  async removeEntry(name: string, options: { recursive?: boolean } = {}): Promise<void> {
    if (this.files.delete(name)) {
      return
    }

    const directory = this.directories.get(name)
    if (directory !== undefined) {
      if (options.recursive !== true) {
        throw new DOMException('Directory is not empty', 'InvalidModificationError')
      }
      this.directories.delete(name)
      return
    }

    throw notFound(name)
  }

  async *keys(): AsyncIterableIterator<string> {
    for (const name of this.files.keys()) {
      yield name
    }
  }
}

export function createFakeOpfsRoot(): FakeDirectoryHandle {
  return new FakeDirectoryHandle()
}
