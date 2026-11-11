/**
 * Google Drive adapter.
 *
 * Uses an OAuth access token and the Drive v3 REST API. Drive is not object
 * storage: it has no ETag/`If-Match` preconditions. Conditional writes are
 * therefore handled conservatively by comparing the file `version` before a
 * write and re-reading metadata after a write. The adapter only accepts
 * `Uint8Array` bodies, so callers must encrypt before writing.
 */

import {
  concatBytes,
  delay,
  ensureOk,
  fullKey,
  fullPrefix,
  logicalKey,
  normalizePrefix,
  toArrayBuffer,
  utf8,
} from './http'
import {
  StorageError,
  type ListObjectsOptions,
  type ListObjectsResult,
  type PutObjectOptions,
  type StorageAdapter,
  type StorageObject,
} from './types'

export interface GoogleDriveStorageConfig {
  accessToken: string
  /** Folder id, or `appDataFolder` for the hidden app data folder. */
  folderId?: string
  prefix?: string
  /** Defaults to `https://www.googleapis.com`. */
  endpoint?: string
  /** Injectable fetch for tests. Defaults to `globalThis.fetch`. */
  fetch?: typeof fetch
  /** Injectable clock for tests. */
  now?: () => Date
  /** Delay between read-after-write retries. Defaults to 100 ms. */
  retryDelayMs?: number
}

interface DriveFile {
  id: string
  name: string
  version?: string
  modifiedTime?: string
  md5Checksum?: string
  size?: string
}

const FILE_FIELDS = 'id,name,version,modifiedTime,md5Checksum,size'
const READ_AFTER_WRITE_ATTEMPTS = 3

function escapeQuery(value: string): string {
  return value.replace(/\\/gu, '\\\\').replace(/'/gu, "\\'")
}

export class GoogleDriveStorageAdapter implements StorageAdapter {
  private readonly accessToken: string
  private readonly folderId: string
  private readonly prefix: string
  private readonly endpoint: URL
  private readonly fetchImpl: typeof fetch
  private readonly now: () => Date
  private readonly retryDelayMs: number

  constructor(config: GoogleDriveStorageConfig) {
    if (config.accessToken.trim() === '') {
      throw new Error('Google Drive access token must not be empty')
    }

    const fetchImpl = config.fetch ?? globalThis.fetch
    if (typeof fetchImpl !== 'function') {
      throw new Error('fetch is not available in this runtime')
    }

    const endpoint = new URL(config.endpoint ?? 'https://www.googleapis.com')
    if (endpoint.protocol !== 'https:' && endpoint.protocol !== 'http:') {
      throw new Error('Google Drive endpoint must use http or https')
    }

    this.accessToken = config.accessToken
    this.folderId = config.folderId ?? 'appDataFolder'
    this.prefix = normalizePrefix(config.prefix)
    this.endpoint = endpoint
    this.fetchImpl = fetchImpl
    this.now = config.now ?? (() => new Date())
    this.retryDelayMs = config.retryDelayMs ?? 100
  }

  async testConnection(): Promise<void> {
    const url = this.apiUrl('/drive/v3/about')
    url.searchParams.set('fields', 'user')
    const response = await this.send('GET', url)
    await ensureOk(response, 'Google Drive about')
  }

  async getObject(key: string): Promise<StorageObject> {
    const file = await this.findFile(key)
    if (file === null) {
      throw new StorageError('not_found', `GET ${key} failed: file not found`, { status: 404 })
    }

    const url = this.apiUrl(`/drive/v3/files/${encodeURIComponent(file.id)}`)
    url.searchParams.set('alt', 'media')
    const response = await this.send('GET', url)
    await ensureOk(response, `GET ${key}`)
    return {
      key,
      bytes: new Uint8Array(await response.arrayBuffer()),
      etag: file.version,
      lastModified: file.modifiedTime,
    }
  }

  async putObject(
    key: string,
    bytes: Uint8Array,
    options: PutObjectOptions = {},
  ): Promise<StorageObject> {
    if (!(bytes instanceof Uint8Array)) {
      throw new StorageError('server', 'putObject requires encrypted bytes')
    }
    if (options.ifMatch !== undefined && options.ifNoneMatch !== undefined) {
      throw new StorageError('conflict', 'Use either ifMatch or ifNoneMatch, not both')
    }

    const existing = await this.findFile(key)
    if (options.ifMatch !== undefined) {
      if (existing === null || existing.version !== options.ifMatch) {
        throw new StorageError('conflict', `PUT ${key} failed: version conflict`, {
          status: 409,
        })
      }
    }
    if (options.ifNoneMatch !== undefined && existing !== null) {
      throw new StorageError('conflict', `PUT ${key} failed: file already exists`, {
        status: 409,
      })
    }

    const contentType = options.contentType ?? 'application/octet-stream'
    if (existing === null) {
      await this.createFile(key, bytes, contentType)
    } else {
      await this.updateFile(existing.id, bytes, contentType)
    }

    const written = await this.readAfterWrite(key)
    return {
      key,
      bytes,
      etag: written.version,
      lastModified: written.modifiedTime,
    }
  }

  async headObject(key: string): Promise<StorageObject> {
    const file = await this.findFile(key)
    if (file === null) {
      throw new StorageError('not_found', `HEAD ${key} failed: file not found`, {
        status: 404,
      })
    }
    return {
      key,
      bytes: new Uint8Array(0),
      etag: file.version,
      lastModified: file.modifiedTime,
    }
  }

  async deleteObject(key: string): Promise<void> {
    const file = await this.findFile(key)
    if (file === null) {
      return
    }

    const url = this.apiUrl(`/drive/v3/files/${encodeURIComponent(file.id)}`)
    const response = await this.send('DELETE', url)
    if (response.status === 404) {
      return
    }
    await ensureOk(response, `DELETE ${key}`)
  }

  async listObjects(options: ListObjectsOptions = {}): Promise<ListObjectsResult> {
    const url = this.apiUrl('/drive/v3/files')
    url.searchParams.set('q', this.folderQuery())
    url.searchParams.set('fields', `nextPageToken,files(${FILE_FIELDS})`)
    url.searchParams.set('spaces', this.folderId === 'appDataFolder' ? 'appDataFolder' : 'drive')
    url.searchParams.set('pageSize', String(Math.min(options.maxKeys ?? 1000, 1000)))
    if (options.continuationToken !== undefined) {
      url.searchParams.set('pageToken', options.continuationToken)
    }

    const response = await this.send('GET', url)
    await ensureOk(response, 'LIST folder')
    const body = (await response.json()) as {
      files?: DriveFile[]
      nextPageToken?: string
    }

    const prefix = fullPrefix(this.prefix, options.prefix)
    const objects = (body.files ?? [])
      .filter((file) => prefix === '' || file.name.startsWith(prefix))
      .map((file) => ({
        key: logicalKey(this.prefix, file.name),
        bytes: new Uint8Array(0),
        etag: file.version,
        lastModified: file.modifiedTime,
      }))

    return body.nextPageToken === undefined
      ? { objects }
      : { objects, nextContinuationToken: body.nextPageToken }
  }

  private async findFile(key: string): Promise<DriveFile | null> {
    const url = this.apiUrl('/drive/v3/files')
    url.searchParams.set(
      'q',
      `name = '${escapeQuery(fullKey(this.prefix, key))}' and ${this.folderQuery()}`,
    )
    url.searchParams.set('fields', `files(${FILE_FIELDS})`)
    url.searchParams.set('spaces', this.folderId === 'appDataFolder' ? 'appDataFolder' : 'drive')

    const response = await this.send('GET', url)
    await ensureOk(response, `FIND ${key}`)
    const body = (await response.json()) as { files?: DriveFile[] }
    return body.files?.[0] ?? null
  }

  private async createFile(
    key: string,
    bytes: Uint8Array,
    contentType: string,
  ): Promise<void> {
    const boundary = `chatui-${this.now().getTime()}-${Math.random().toString(16).slice(2)}`
    const metadata = JSON.stringify({
      name: fullKey(this.prefix, key),
      parents: [this.folderId],
    })
    const body = concatBytes(
      utf8(
        `--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${metadata}\r\n--${boundary}\r\nContent-Type: ${contentType}\r\n\r\n`,
      ),
      bytes,
      utf8(`\r\n--${boundary}--\r\n`),
    )

    const url = this.uploadUrl('/upload/drive/v3/files')
    url.searchParams.set('uploadType', 'multipart')
    url.searchParams.set('fields', FILE_FIELDS)
    const response = await this.send('POST', url, {
      headers: { 'content-type': `multipart/related; boundary=${boundary}` },
      payload: body,
    })
    await ensureOk(response, `PUT ${key}`)
  }

  private async updateFile(
    fileId: string,
    bytes: Uint8Array,
    contentType: string,
  ): Promise<void> {
    const url = this.uploadUrl(`/upload/drive/v3/files/${encodeURIComponent(fileId)}`)
    url.searchParams.set('uploadType', 'media')
    url.searchParams.set('fields', FILE_FIELDS)
    const response = await this.send('PATCH', url, {
      headers: { 'content-type': contentType },
      payload: bytes,
    })
    await ensureOk(response, `PUT ${fileId}`)
  }

  private async readAfterWrite(key: string): Promise<DriveFile> {
    let lastError: unknown
    for (let attempt = 0; attempt < READ_AFTER_WRITE_ATTEMPTS; attempt += 1) {
      try {
        const file = await this.findFile(key)
        if (file !== null) {
          return file
        }
        lastError = new StorageError('not_found', `PUT ${key} failed: file not found`, {
          status: 404,
        })
      } catch (error) {
        lastError = error
        if (!(error instanceof StorageError) || error.code !== 'not_found') {
          throw error
        }
      }
      if (attempt < READ_AFTER_WRITE_ATTEMPTS - 1 && this.retryDelayMs > 0) {
        await delay(this.retryDelayMs * (attempt + 1))
      }
    }
    throw lastError
  }

  private folderQuery(): string {
    return `'${escapeQuery(this.folderId)}' in parents and trashed = false`
  }

  private async send(
    method: string,
    url: URL,
    options: { headers?: Record<string, string>; payload?: Uint8Array } = {},
  ): Promise<Response> {
    const headers: Record<string, string> = {
      ...options.headers,
      authorization: `Bearer ${this.accessToken}`,
    }

    try {
      return await this.fetchImpl(url.toString(), {
        method,
        headers,
        body: options.payload === undefined ? undefined : toArrayBuffer(options.payload),
      })
    } catch (error) {
      throw new StorageError('network', `Network request failed for ${method} ${url.pathname}`, {
        cause: error,
      })
    }
  }

  private basePath(): string {
    const path = this.endpoint.pathname.replace(/\/+$/u, '')
    return path === '/' ? '' : path
  }

  private apiUrl(path: string): URL {
    const url = new URL(this.endpoint.toString())
    url.pathname = `${this.basePath()}${path}`
    return url
  }

  private uploadUrl(path: string): URL {
    return this.apiUrl(path)
  }
}
