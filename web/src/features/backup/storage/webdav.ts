/**
 * WebDAV adapter.
 *
 * Uses HTTP Basic authentication and the WebDAV methods PUT/GET/HEAD/DELETE
 * plus PROPFIND for listing. WebDAV servers may be eventually consistent, so
 * writes are re-read conservatively. The adapter only accepts `Uint8Array`
 * bodies, so callers must encrypt before writing.
 */

import {
  bytesToBase64,
  delay,
  encodeKeyPath,
  ensureOk,
  fullKey,
  headerValue,
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

export interface WebDavStorageConfig {
  url: string
  username: string
  password: string
  prefix?: string
  /** Injectable fetch for tests. Defaults to `globalThis.fetch`. */
  fetch?: typeof fetch
  /** Delay between read-after-write retries. Defaults to 100 ms. */
  retryDelayMs?: number
}

const READ_AFTER_WRITE_ATTEMPTS = 3
const PROPFIND_BODY = `<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
    <d:resourcetype/>
    <d:getetag/>
    <d:getlastmodified/>
  </d:prop>
</d:propfind>`

function childText(parent: Element, namespace: string, tag: string): string | undefined {
  const element = parent.getElementsByTagNameNS(namespace, tag)[0]
  const text = element?.textContent?.trim()
  return text === undefined || text === '' ? undefined : text
}

export class WebDavStorageAdapter implements StorageAdapter {
  private readonly baseUrl: URL
  private readonly authorization: string
  private readonly prefix: string
  private readonly fetchImpl: typeof fetch
  private readonly retryDelayMs: number

  constructor(config: WebDavStorageConfig) {
    if (config.url.trim() === '') {
      throw new Error('WebDAV URL must not be empty')
    }
    if (config.username === '') {
      throw new Error('WebDAV username must not be empty')
    }
    if (config.password === '') {
      throw new Error('WebDAV password must not be empty')
    }

    const fetchImpl = config.fetch ?? globalThis.fetch
    if (typeof fetchImpl !== 'function') {
      throw new Error('fetch is not available in this runtime')
    }

    const baseUrl = new URL(config.url)
    if (baseUrl.protocol !== 'https:' && baseUrl.protocol !== 'http:') {
      throw new Error('WebDAV URL must use http or https')
    }
    if (!baseUrl.pathname.endsWith('/')) {
      baseUrl.pathname = `${baseUrl.pathname}/`
    }

    this.baseUrl = baseUrl
    this.authorization = `Basic ${bytesToBase64(utf8(`${config.username}:${config.password}`))}`
    this.prefix = normalizePrefix(config.prefix)
    this.fetchImpl = fetchImpl
    this.retryDelayMs = config.retryDelayMs ?? 100
  }

  async testConnection(): Promise<void> {
    const response = await this.send('PROPFIND', this.baseUrl, {
      headers: { depth: '0', 'content-type': 'application/xml; charset=utf-8' },
      payload: utf8(PROPFIND_BODY),
    })
    await ensureOk(response, 'PROPFIND root')
  }

  async getObject(key: string): Promise<StorageObject> {
    const response = await this.send('GET', this.objectUrl(key))
    await ensureOk(response, `GET ${key}`)
    return {
      key,
      bytes: new Uint8Array(await response.arrayBuffer()),
      etag: headerValue(response, 'etag'),
      lastModified: headerValue(response, 'last-modified'),
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

    const headers: Record<string, string> = {
      'content-type': options.contentType ?? 'application/octet-stream',
    }
    if (options.ifMatch !== undefined) {
      headers['if-match'] = options.ifMatch
    }
    if (options.ifNoneMatch !== undefined) {
      headers['if-none-match'] = options.ifNoneMatch
    }

    const response = await this.send('PUT', this.objectUrl(key), {
      headers,
      payload: bytes,
    })
    await ensureOk(response, `PUT ${key}`)

    const etag = headerValue(response, 'etag')
    const lastModified = headerValue(response, 'last-modified')
    if (etag !== undefined) {
      return { key, bytes, etag, lastModified }
    }

    try {
      const head = await this.headAfterWrite(key)
      return { key, bytes, etag: head.etag, lastModified: head.lastModified ?? lastModified }
    } catch (error) {
      if (error instanceof StorageError && error.code === 'not_found') {
        return { key, bytes, lastModified }
      }
      throw error
    }
  }

  async headObject(key: string): Promise<StorageObject> {
    const response = await this.send('HEAD', this.objectUrl(key))
    await ensureOk(response, `HEAD ${key}`)
    return {
      key,
      bytes: new Uint8Array(0),
      etag: headerValue(response, 'etag'),
      lastModified: headerValue(response, 'last-modified'),
    }
  }

  async deleteObject(key: string): Promise<void> {
    const response = await this.send('DELETE', this.objectUrl(key))
    if (response.status === 404) {
      return
    }
    await ensureOk(response, `DELETE ${key}`)
  }

  async listObjects(options: ListObjectsOptions = {}): Promise<ListObjectsResult> {
    const response = await this.send('PROPFIND', this.baseUrl, {
      headers: { depth: '1', 'content-type': 'application/xml; charset=utf-8' },
      payload: utf8(PROPFIND_BODY),
    })
    await ensureOk(response, 'PROPFIND root')

    const prefix = (options.prefix ?? '').replace(/^\/+/u, '')
    const objects = this.parseListResponse(await response.text()).filter(
      (object) => prefix === '' || object.key.startsWith(prefix),
    )
    return { objects }
  }

  private async send(
    method: string,
    url: URL,
    options: { headers?: Record<string, string>; payload?: Uint8Array } = {},
  ): Promise<Response> {
    const headers: Record<string, string> = {
      ...options.headers,
      authorization: this.authorization,
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

  private async headAfterWrite(key: string): Promise<StorageObject> {
    let lastError: unknown
    for (let attempt = 0; attempt < READ_AFTER_WRITE_ATTEMPTS; attempt += 1) {
      try {
        return await this.headObject(key)
      } catch (error) {
        lastError = error
        if (!(error instanceof StorageError) || error.code !== 'not_found') {
          throw error
        }
        if (attempt < READ_AFTER_WRITE_ATTEMPTS - 1 && this.retryDelayMs > 0) {
          await delay(this.retryDelayMs * (attempt + 1))
        }
      }
    }
    throw lastError
  }

  private parseListResponse(xml: string): StorageObject[] {
    const document = new DOMParser().parseFromString(xml, 'application/xml')
    if (document.getElementsByTagName('parsererror').length > 0) {
      throw new StorageError('server', 'PROPFIND response is not valid XML')
    }

    const basePath = this.baseUrl.pathname
    const objects: StorageObject[] = []
    for (const response of Array.from(
      document.getElementsByTagNameNS('DAV:', 'response'),
    )) {
      const href = childText(response, 'DAV:', 'href')
      if (href === undefined) {
        continue
      }

      const resourceType = response.getElementsByTagNameNS('DAV:', 'resourcetype')[0]
      if (resourceType?.getElementsByTagNameNS('DAV:', 'collection').length) {
        continue
      }

      let path: string
      try {
        path = decodeURIComponent(new URL(href, this.baseUrl).pathname)
      } catch {
        continue
      }
      if (!path.startsWith(basePath)) {
        continue
      }
      const relative = path.slice(basePath.length).replace(/^\/+/u, '')
      if (relative === '') {
        continue
      }

      objects.push({
        key: logicalKey(this.prefix, relative),
        bytes: new Uint8Array(0),
        etag: childText(response, 'DAV:', 'getetag'),
        lastModified: childText(response, 'DAV:', 'getlastmodified'),
      })
    }
    return objects
  }

  private objectUrl(key: string): URL {
    return new URL(encodeKeyPath(fullKey(this.prefix, key)), this.baseUrl)
  }
}
