/**
 * S3-compatible storage adapter.
 *
 * Supports AWS S3, Contabo Object Storage, Cloudflare R2, MinIO, and
 * Backblaze B2 through a custom endpoint. Requests are signed with SigV4 and
 * sent directly from the browser; there is no server proxy.
 *
 * The adapter only accepts `Uint8Array` bodies. Callers must encrypt with the
 * backup envelope codec before writing.
 */

import { encodeRfc3986, signRequest, type SigV4Credentials, type SigV4Method } from './sigv4'
import {
  StorageError,
  type ListObjectsOptions,
  type ListObjectsResult,
  type PutObjectOptions,
  type StorageAdapter,
  type StorageErrorCode,
  type StorageObject,
} from './types'

export interface S3StorageConfig {
  /** Service endpoint, for example `https://s3.us-east-1.amazonaws.com`. */
  endpoint: string
  bucket: string
  region: string
  accessKeyId: string
  secretAccessKey: string
  sessionToken?: string
  /** Optional key prefix, for example `chatui/backups`. */
  prefix?: string
  /**
   * Use `endpoint/bucket/key` instead of `bucket.endpoint/key`.
   * Defaults to path-style for non-AWS endpoints.
   */
  forcePathStyle?: boolean
  /** Injectable fetch for tests. Defaults to `globalThis.fetch`. */
  fetch?: typeof fetch
  /** Injectable clock for tests. */
  now?: () => Date
  /** Delay between read-after-write retries. Defaults to 100 ms. */
  retryDelayMs?: number
}

const READ_AFTER_WRITE_ATTEMPTS = 3

function isAwsEndpoint(hostname: string): boolean {
  return (
    hostname === 's3.amazonaws.com' ||
    /^s3[.-][a-z0-9-]+\.amazonaws\.com$/u.test(hostname) ||
    hostname.endsWith('.amazonaws.com')
  )
}

function encodeKeyPath(key: string): string {
  return key
    .split('/')
    .map((segment) => encodeRfc3986(segment))
    .join('/')
}

function toArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const copy = new Uint8Array(bytes.byteLength)
  copy.set(bytes)
  return copy.buffer
}

function headerValue(response: Response, name: string): string | undefined {
  const value = response.headers.get(name)
  return value === null || value === '' ? undefined : value
}

function statusToCode(status: number): StorageErrorCode {
  if (status === 401) {
    return 'auth'
  }
  if (status === 403) {
    return 'permission'
  }
  if (status === 404) {
    return 'not_found'
  }
  if (status === 409 || status === 412) {
    return 'conflict'
  }
  return 'server'
}

function childText(parent: Element, tag: string): string | undefined {
  const element = parent.getElementsByTagName(tag)[0]
  const text = element?.textContent?.trim()
  return text === undefined || text === '' ? undefined : text
}

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve, milliseconds)
  })
}

export class S3StorageAdapter implements StorageAdapter {
  private readonly endpoint: URL
  private readonly bucket: string
  private readonly region: string
  private readonly credentials: SigV4Credentials
  private readonly prefix: string
  private readonly forcePathStyle: boolean
  private readonly fetchImpl: typeof fetch
  private readonly now: () => Date
  private readonly retryDelayMs: number

  constructor(config: S3StorageConfig) {
    const endpoint = new URL(config.endpoint)
    if (endpoint.protocol !== 'https:' && endpoint.protocol !== 'http:') {
      throw new Error('S3 endpoint must use http or https')
    }
    if (config.bucket.trim() === '') {
      throw new Error('S3 bucket must not be empty')
    }
    if (config.region.trim() === '') {
      throw new Error('S3 region must not be empty')
    }
    if (config.accessKeyId === '' || config.secretAccessKey === '') {
      throw new Error('S3 access key id and secret access key are required')
    }

    const fetchImpl = config.fetch ?? globalThis.fetch
    if (typeof fetchImpl !== 'function') {
      throw new Error('fetch is not available in this runtime')
    }

    this.endpoint = endpoint
    this.bucket = config.bucket
    this.region = config.region
    this.credentials = {
      accessKeyId: config.accessKeyId,
      secretAccessKey: config.secretAccessKey,
      sessionToken: config.sessionToken,
    }
    this.prefix = (config.prefix ?? '').replace(/^\/+|\/+$/gu, '')
    this.forcePathStyle = config.forcePathStyle ?? !isAwsEndpoint(endpoint.hostname)
    this.fetchImpl = fetchImpl
    this.now = config.now ?? (() => new Date())
    this.retryDelayMs = config.retryDelayMs ?? 100
  }

  async testConnection(): Promise<void> {
    const response = await this.send('HEAD', this.bucketUrl())
    await this.ensureOk(response, 'HEAD bucket')
  }

  async getObject(key: string): Promise<StorageObject> {
    const response = await this.send('GET', this.objectUrl(key))
    await this.ensureOk(response, `GET ${key}`)
    const bytes = new Uint8Array(await response.arrayBuffer())
    return {
      key,
      bytes,
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

    const response = await this.send('PUT', this.objectUrl(key), { headers, payload: bytes })
    await this.ensureOk(response, `PUT ${key}`)

    const etag = headerValue(response, 'etag')
    const lastModified = headerValue(response, 'last-modified')
    if (etag !== undefined) {
      return { key, bytes, etag, lastModified }
    }

    // Some providers omit ETag on PUT. Re-read conservatively so callers can
    // use the value for the next conditional manifest rewrite.
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
    await this.ensureOk(response, `HEAD ${key}`)
    return {
      key,
      bytes: new Uint8Array(0),
      etag: headerValue(response, 'etag'),
      lastModified: headerValue(response, 'last-modified'),
    }
  }

  async deleteObject(key: string): Promise<void> {
    const response = await this.send('DELETE', this.objectUrl(key))
    await this.ensureOk(response, `DELETE ${key}`)
  }

  async listObjects(options: ListObjectsOptions = {}): Promise<ListObjectsResult> {
    const url = this.bucketUrl()
    url.searchParams.set('list-type', '2')

    const prefix = this.fullPrefix(options.prefix)
    if (prefix !== '') {
      url.searchParams.set('prefix', prefix)
    }
    if (options.continuationToken !== undefined) {
      url.searchParams.set('continuation-token', options.continuationToken)
    }
    if (options.maxKeys !== undefined) {
      url.searchParams.set('max-keys', String(options.maxKeys))
    }

    const response = await this.send('GET', url)
    await this.ensureOk(response, 'LIST bucket')
    return this.parseListResponse(await response.text())
  }

  private async send(
    method: SigV4Method,
    url: URL,
    options: { headers?: Record<string, string>; payload?: Uint8Array } = {},
  ): Promise<Response> {
    const signed = await signRequest({
      method,
      url: url.toString(),
      region: this.region,
      credentials: this.credentials,
      headers: options.headers,
      payload: options.payload,
      date: this.now(),
    })

    try {
      return await this.fetchImpl(signed.url, {
        method,
        headers: signed.headers,
        body: options.payload === undefined ? undefined : toArrayBuffer(options.payload),
      })
    } catch (error) {
      throw new StorageError('network', `Network request failed for ${method} ${url.pathname}`, {
        cause: error,
      })
    }
  }

  private async ensureOk(response: Response, action: string): Promise<void> {
    if (response.ok) {
      return
    }
    throw await this.errorFromResponse(response, action)
  }

  private async errorFromResponse(response: Response, action: string): Promise<StorageError> {
    let detail = ''
    try {
      detail = (await response.text()).trim().slice(0, 500)
    } catch {
      // A failed body read must not hide the HTTP status.
    }
    const suffix = detail === '' ? '' : `: ${detail}`
    return new StorageError(
      statusToCode(response.status),
      `${action} failed with HTTP ${response.status}${suffix}`,
      { status: response.status },
    )
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

    const objects = Array.from(document.getElementsByTagName('Contents')).map((node) => {
      const key = childText(node, 'Key')
      if (key === undefined) {
        throw new StorageError('server', 'List response entry is missing Key')
      }
      return {
        key: this.logicalKey(key),
        bytes: new Uint8Array(0),
        etag: childText(node, 'ETag'),
        lastModified: childText(node, 'LastModified'),
      }
    })

    const next = childText(document.documentElement, 'NextContinuationToken')
    return next === undefined ? { objects } : { objects, nextContinuationToken: next }
  }

  private basePath(): string {
    const path = this.endpoint.pathname.replace(/\/+$/u, '')
    return path === '/' ? '' : path
  }

  private bucketPath(): string {
    return `${this.basePath()}/${this.bucket}`
  }

  private bucketUrl(): URL {
    const url = new URL(this.endpoint.toString())
    if (this.forcePathStyle) {
      url.pathname = this.bucketPath()
    } else {
      url.host = `${this.bucket}.${url.host}`
      url.pathname = this.basePath() === '' ? '/' : this.basePath()
    }
    return url
  }

  private objectUrl(key: string): URL {
    const url = new URL(this.endpoint.toString())
    const encodedKey = encodeKeyPath(this.fullKey(key))
    if (this.forcePathStyle) {
      url.pathname = `${this.bucketPath()}/${encodedKey}`
    } else {
      url.host = `${this.bucket}.${url.host}`
      const base = this.basePath()
      url.pathname = base === '' ? `/${encodedKey}` : `${base}/${encodedKey}`
    }
    return url
  }

  private fullKey(key: string): string {
    const normalized = key.replace(/^\/+/u, '')
    if (normalized === '') {
      throw new StorageError('server', 'Object key must not be empty')
    }
    return this.prefix === '' ? normalized : `${this.prefix}/${normalized}`
  }

  private fullPrefix(prefix: string | undefined): string {
    const extra = (prefix ?? '').replace(/^\/+/u, '')
    if (this.prefix === '') {
      return extra
    }
    return extra === '' ? `${this.prefix}/` : `${this.prefix}/${extra}`
  }

  private logicalKey(actualKey: string): string {
    if (this.prefix === '') {
      return actualKey
    }
    const prefix = `${this.prefix}/`
    return actualKey.startsWith(prefix) ? actualKey.slice(prefix.length) : actualKey
  }
}
