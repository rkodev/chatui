/**
 * Azure Blob Storage adapter.
 *
 * Supports Shared Key authentication with an account name and key, or a SAS
 * token. Requests are sent directly from the browser; there is no server
 * proxy. The adapter only accepts `Uint8Array` bodies, so callers must encrypt
 * with the backup envelope codec before writing.
 */

import {
  base64ToBytes,
  bytesToBase64,
  delay,
  encodeKeyPath,
  ensureOk,
  fullKey,
  fullPrefix,
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

export interface AzureBlobStorageConfig {
  accountName: string
  /** Base64-encoded account key. Mutually exclusive with `sasToken`. */
  accountKey?: string
  /** SAS token, with or without a leading `?`. */
  sasToken?: string
  container: string
  /** Defaults to `https://{accountName}.blob.core.windows.net`. */
  endpoint?: string
  prefix?: string
  /** Injectable fetch for tests. Defaults to `globalThis.fetch`. */
  fetch?: typeof fetch
  /** Injectable clock for tests. */
  now?: () => Date
  /** Delay between read-after-write retries. Defaults to 100 ms. */
  retryDelayMs?: number
}

const AZURE_VERSION = '2021-12-02'
const READ_AFTER_WRITE_ATTEMPTS = 3

function childText(parent: Element, tag: string): string | undefined {
  const element = parent.getElementsByTagName(tag)[0]
  const text = element?.textContent?.trim()
  return text === undefined || text === '' ? undefined : text
}

function canonicalizedHeaders(headers: Record<string, string>): string {
  return Object.entries(headers)
    .filter(([name]) => name.toLowerCase().startsWith('x-ms-'))
    .map(([name, value]) => [name.toLowerCase(), value.trim()] as const)
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([name, value]) => `${name}:${value}\n`)
    .join('')
}

function canonicalizedResource(accountName: string, url: URL): string {
  const path = decodeURIComponent(url.pathname)
  const parameters = new Map<string, string[]>()
  for (const [name, value] of url.searchParams.entries()) {
    const key = name.toLowerCase()
    const values = parameters.get(key) ?? []
    values.push(value)
    parameters.set(key, values)
  }

  let resource = `/${accountName}${path}`
  for (const name of [...parameters.keys()].sort()) {
    const values = (parameters.get(name) ?? []).slice().sort()
    resource += `\n${name}:${values.join(',')}`
  }
  return resource
}

function stringToSign(
  method: string,
  url: URL,
  headers: Record<string, string>,
  contentLength: string,
  accountName: string,
): string {
  const contentEncoding = headers['content-encoding'] ?? ''
  const contentLanguage = headers['content-language'] ?? ''
  const contentMd5 = headers['content-md5'] ?? ''
  const contentType = headers['content-type'] ?? ''
  const date = headers['date'] ?? ''
  const ifModifiedSince = headers['if-modified-since'] ?? ''
  const ifMatch = headers['if-match'] ?? ''
  const ifNoneMatch = headers['if-none-match'] ?? ''
  const ifUnmodifiedSince = headers['if-unmodified-since'] ?? ''
  const range = headers['range'] ?? ''

  return [
    method,
    contentEncoding,
    contentLanguage,
    contentLength,
    contentMd5,
    contentType,
    date,
    ifModifiedSince,
    ifMatch,
    ifNoneMatch,
    ifUnmodifiedSince,
    range,
    canonicalizedHeaders(headers),
    canonicalizedResource(accountName, url),
  ].join('\n')
}

async function signSharedKey(
  accountName: string,
  accountKey: string,
  method: string,
  url: URL,
  headers: Record<string, string>,
  contentLength: string,
): Promise<string> {
  const keyBytes = base64ToBytes(accountKey)
  const key = await globalThis.crypto.subtle.importKey(
    'raw',
    toArrayBuffer(keyBytes),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign'],
  )
  const signature = await globalThis.crypto.subtle.sign(
    'HMAC',
    key,
    toArrayBuffer(utf8(stringToSign(method, url, headers, contentLength, accountName))),
  )
  return bytesToBase64(new Uint8Array(signature))
}

export class AzureBlobStorageAdapter implements StorageAdapter {
  private readonly accountName: string
  private readonly accountKey: string | null
  private readonly sasToken: string | null
  private readonly container: string
  private readonly endpoint: URL
  private readonly prefix: string
  private readonly fetchImpl: typeof fetch
  private readonly now: () => Date
  private readonly retryDelayMs: number

  constructor(config: AzureBlobStorageConfig) {
    if (config.accountName.trim() === '') {
      throw new Error('Azure account name must not be empty')
    }
    if (config.container.trim() === '') {
      throw new Error('Azure container must not be empty')
    }
    if ((config.accountKey ?? '') === '' && (config.sasToken ?? '') === '') {
      throw new Error('Azure account key or SAS token is required')
    }

    const fetchImpl = config.fetch ?? globalThis.fetch
    if (typeof fetchImpl !== 'function') {
      throw new Error('fetch is not available in this runtime')
    }

    const endpoint = new URL(
      config.endpoint ?? `https://${config.accountName}.blob.core.windows.net`,
    )
    if (endpoint.protocol !== 'https:' && endpoint.protocol !== 'http:') {
      throw new Error('Azure endpoint must use http or https')
    }

    this.accountName = config.accountName
    this.accountKey = (config.accountKey ?? '') === '' ? null : (config.accountKey ?? null)
    this.sasToken =
      this.accountKey !== null || (config.sasToken ?? '') === ''
        ? null
        : (config.sasToken ?? '').replace(/^\?/u, '')
    this.container = config.container
    this.endpoint = endpoint
    this.prefix = normalizePrefix(config.prefix)
    this.fetchImpl = fetchImpl
    this.now = config.now ?? (() => new Date())
    this.retryDelayMs = config.retryDelayMs ?? 100
  }

  async testConnection(): Promise<void> {
    const url = this.containerUrl()
    url.searchParams.set('restype', 'container')
    const response = await this.send('HEAD', url)
    await ensureOk(response, 'HEAD container')
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
      'x-ms-blob-type': 'BlockBlob',
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
    await ensureOk(response, `DELETE ${key}`)
  }

  async listObjects(options: ListObjectsOptions = {}): Promise<ListObjectsResult> {
    const url = this.containerUrl()
    url.searchParams.set('restype', 'container')
    url.searchParams.set('comp', 'list')

    const prefix = fullPrefix(this.prefix, options.prefix)
    if (prefix !== '') {
      url.searchParams.set('prefix', prefix)
    }
    if (options.continuationToken !== undefined) {
      url.searchParams.set('marker', options.continuationToken)
    }
    if (options.maxKeys !== undefined) {
      url.searchParams.set('maxresults', String(options.maxKeys))
    }

    const response = await this.send('GET', url)
    await ensureOk(response, 'LIST container')
    return this.parseListResponse(await response.text())
  }

  private async send(
    method: string,
    url: URL,
    options: { headers?: Record<string, string>; payload?: Uint8Array } = {},
  ): Promise<Response> {
    const headers: Record<string, string> = {
      ...options.headers,
      'x-ms-date': this.now().toUTCString(),
      'x-ms-version': AZURE_VERSION,
    }

    if (this.accountKey !== null) {
      const contentLength =
        options.payload === undefined ? '' : String(options.payload.byteLength)
      const signature = await signSharedKey(
        this.accountName,
        this.accountKey,
        method,
        url,
        headers,
        contentLength,
      )
      headers.authorization = `SharedKey ${this.accountName}:${signature}`
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

  private parseListResponse(xml: string): ListObjectsResult {
    const document = new DOMParser().parseFromString(xml, 'application/xml')
    if (document.getElementsByTagName('parsererror').length > 0) {
      throw new StorageError('server', 'List response is not valid XML')
    }

    const objects = Array.from(document.getElementsByTagName('Blob')).map((node) => {
      const name = childText(node, 'Name')
      if (name === undefined) {
        throw new StorageError('server', 'List response entry is missing Name')
      }
      return {
        key: logicalKey(this.prefix, name),
        bytes: new Uint8Array(0),
        etag: childText(node, 'Etag') ?? childText(node, 'ETag'),
        lastModified: childText(node, 'Last-Modified'),
      }
    })

    const next = childText(document.documentElement, 'NextMarker')
    return next === undefined ? { objects } : { objects, nextContinuationToken: next }
  }

  private basePath(): string {
    const path = this.endpoint.pathname.replace(/\/+$/u, '')
    return path === '/' ? '' : path
  }

  private containerUrl(): URL {
    const url = new URL(this.endpoint.toString())
    url.pathname = `${this.basePath()}/${this.container}`
    if (this.sasToken !== null) {
      url.search = this.sasToken
    }
    return url
  }

  private objectUrl(key: string): URL {
    const url = this.containerUrl()
    url.pathname = `${url.pathname}/${encodeKeyPath(fullKey(this.prefix, key))}`
    return url
  }
}
