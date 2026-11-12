/**
 * Google Cloud Storage adapter.
 *
 * Uses a service account JSON key to sign a JWT, exchanges it for an OAuth2
 * access token, and then calls the GCS JSON API directly from the browser.
 * There is no server proxy and no cloud SDK. The adapter only accepts
 * `Uint8Array` bodies, so callers must encrypt before writing.
 */

import {
  base64UrlEncode,
  base64UrlEncodeString,
  delay,
  ensureOk,
  errorFromResponse,
  fullKey,
  fullPrefix,
  headerValue,
  logicalKey,
  normalizePrefix,
  pemToDer,
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

export interface GcsServiceAccount {
  client_email: string
  private_key: string
  token_uri?: string
}

export interface GcsStorageConfig {
  bucket: string
  /** Service account JSON, either as a string or a parsed object. */
  serviceAccountJson: string | GcsServiceAccount
  prefix?: string
  /** Defaults to `https://storage.googleapis.com`. */
  endpoint?: string
  /** Injectable fetch for tests. Defaults to `globalThis.fetch`. */
  fetch?: typeof fetch
  /** Injectable clock for tests. */
  now?: () => Date
  /** Delay between read-after-write retries. Defaults to 100 ms. */
  retryDelayMs?: number
}

interface GcsObjectMetadata {
  name?: string
  generation?: string
  etag?: string
  updated?: string
  size?: string
}

const DEFAULT_TOKEN_URI = 'https://oauth2.googleapis.com/token'
const GCS_SCOPE = 'https://www.googleapis.com/auth/devstorage.read_write'
const READ_AFTER_WRITE_ATTEMPTS = 3

function parseServiceAccount(
  value: string | GcsServiceAccount,
): GcsServiceAccount {
  const parsed = typeof value === 'string' ? (JSON.parse(value) as unknown) : value
  if (parsed === null || typeof parsed !== 'object') {
    throw new Error('GCS service account JSON must be an object')
  }
  const account = parsed as Partial<GcsServiceAccount>
  if (typeof account.client_email !== 'string' || account.client_email === '') {
    throw new Error('GCS service account JSON is missing client_email')
  }
  if (typeof account.private_key !== 'string' || account.private_key === '') {
    throw new Error('GCS service account JSON is missing private_key')
  }
  return {
    client_email: account.client_email,
    private_key: account.private_key,
    token_uri: account.token_uri,
  }
}

function metadataEtag(metadata: GcsObjectMetadata): string | undefined {
  if (metadata.generation !== undefined && metadata.generation !== '') {
    return metadata.generation
  }
  return metadata.etag
}

export class GcsStorageAdapter implements StorageAdapter {
  private readonly bucket: string
  private readonly serviceAccount: GcsServiceAccount
  private readonly prefix: string
  private readonly endpoint: URL
  private readonly fetchImpl: typeof fetch
  private readonly now: () => Date
  private readonly retryDelayMs: number
  private accessToken: string | null = null
  private accessTokenExpiresAt = 0

  constructor(config: GcsStorageConfig) {
    if (config.bucket.trim() === '') {
      throw new Error('GCS bucket must not be empty')
    }

    const fetchImpl = config.fetch ?? globalThis.fetch
    if (typeof fetchImpl !== 'function') {
      throw new Error('fetch is not available in this runtime')
    }

    const endpoint = new URL(config.endpoint ?? 'https://storage.googleapis.com')
    if (endpoint.protocol !== 'https:' && endpoint.protocol !== 'http:') {
      throw new Error('GCS endpoint must use http or https')
    }

    this.bucket = config.bucket
    this.serviceAccount = parseServiceAccount(config.serviceAccountJson)
    this.prefix = normalizePrefix(config.prefix)
    this.endpoint = endpoint
    this.fetchImpl = fetchImpl
    this.now = config.now ?? (() => new Date())
    this.retryDelayMs = config.retryDelayMs ?? 100
  }

  async testConnection(): Promise<void> {
    const url = this.listUrl()
    url.searchParams.set('maxResults', '1')
    const response = await this.send('GET', url)
    await ensureOk(response, 'LIST bucket')
  }

  async getObject(key: string): Promise<StorageObject> {
    const url = this.objectUrl(key)
    url.searchParams.set('alt', 'media')
    const response = await this.send('GET', url)
    await ensureOk(response, `GET ${key}`)
    return {
      key,
      bytes: new Uint8Array(await response.arrayBuffer()),
      etag: headerValue(response, 'x-goog-generation') ?? headerValue(response, 'etag'),
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

    const url = this.uploadUrl()
    url.searchParams.set('uploadType', 'media')
    url.searchParams.set('name', fullKey(this.prefix, key))
    if (options.ifMatch !== undefined) {
      url.searchParams.set('ifGenerationMatch', options.ifMatch)
    }
    if (options.ifNoneMatch !== undefined) {
      if (options.ifNoneMatch === '*') {
        url.searchParams.set('ifGenerationMatch', '0')
      } else {
        url.searchParams.set('ifGenerationNotMatch', options.ifNoneMatch)
      }
    }

    const response = await this.send('POST', url, {
      headers: { 'content-type': options.contentType ?? 'application/octet-stream' },
      payload: bytes,
    })
    await ensureOk(response, `PUT ${key}`)

    const metadata = (await response.json()) as GcsObjectMetadata
    const etag = metadataEtag(metadata)
    if (etag !== undefined) {
      return { key, bytes, etag, lastModified: metadata.updated }
    }

    try {
      const head = await this.headAfterWrite(key)
      return { key, bytes, etag: head.etag, lastModified: head.lastModified }
    } catch (error) {
      if (error instanceof StorageError && error.code === 'not_found') {
        return { key, bytes }
      }
      throw error
    }
  }

  async headObject(key: string): Promise<StorageObject> {
    const response = await this.send('GET', this.objectUrl(key))
    await ensureOk(response, `HEAD ${key}`)
    const metadata = (await response.json()) as GcsObjectMetadata
    return {
      key,
      bytes: new Uint8Array(0),
      etag: metadataEtag(metadata),
      lastModified: metadata.updated,
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
    const url = this.listUrl()
    const prefix = fullPrefix(this.prefix, options.prefix)
    if (prefix !== '') {
      url.searchParams.set('prefix', prefix)
    }
    if (options.continuationToken !== undefined) {
      url.searchParams.set('pageToken', options.continuationToken)
    }
    if (options.maxKeys !== undefined) {
      url.searchParams.set('maxResults', String(options.maxKeys))
    }

    const response = await this.send('GET', url)
    await ensureOk(response, 'LIST bucket')
    const body = (await response.json()) as {
      items?: GcsObjectMetadata[]
      nextPageToken?: string
    }

    const objects = (body.items ?? []).map((item) => {
      if (item.name === undefined) {
        throw new StorageError('server', 'List response entry is missing name')
      }
      return {
        key: logicalKey(this.prefix, item.name),
        bytes: new Uint8Array(0),
        etag: metadataEtag(item),
        lastModified: item.updated,
      }
    })

    return body.nextPageToken === undefined
      ? { objects }
      : { objects, nextContinuationToken: body.nextPageToken }
  }

  private async send(
    method: string,
    url: URL,
    options: { headers?: Record<string, string>; payload?: Uint8Array } = {},
  ): Promise<Response> {
    const token = await this.getAccessToken()
    const headers: Record<string, string> = {
      ...options.headers,
      authorization: `Bearer ${token}`,
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

  private async getAccessToken(): Promise<string> {
    const nowMs = this.now().getTime()
    if (this.accessToken !== null && this.accessTokenExpiresAt > nowMs + 60_000) {
      return this.accessToken
    }

    const issuedAt = Math.floor(nowMs / 1000)
    const header = base64UrlEncodeString(JSON.stringify({ alg: 'RS256', typ: 'JWT' }))
    const claims = base64UrlEncodeString(
      JSON.stringify({
        iss: this.serviceAccount.client_email,
        scope: GCS_SCOPE,
        aud: this.serviceAccount.token_uri ?? DEFAULT_TOKEN_URI,
        iat: issuedAt,
        exp: issuedAt + 3600,
      }),
    )
    const signingInput = `${header}.${claims}`

    let signature: Uint8Array
    try {
      const key = await globalThis.crypto.subtle.importKey(
        'pkcs8',
        toArrayBuffer(pemToDer(this.serviceAccount.private_key)),
        { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
        false,
        ['sign'],
      )
      signature = new Uint8Array(
        await globalThis.crypto.subtle.sign(
          'RSASSA-PKCS1-v1_5',
          key,
          toArrayBuffer(utf8(signingInput)),
        ),
      )
    } catch (error) {
      throw new StorageError('auth', 'Failed to sign GCS service account JWT', {
        cause: error,
      })
    }

    const assertion = `${signingInput}.${base64UrlEncode(signature)}`
    const tokenUri = this.serviceAccount.token_uri ?? DEFAULT_TOKEN_URI
    const body = new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
      assertion,
    })

    let response: Response
    try {
      response = await this.fetchImpl(tokenUri, {
        method: 'POST',
        headers: { 'content-type': 'application/x-www-form-urlencoded' },
        body: body.toString(),
      })
    } catch (error) {
      throw new StorageError('network', 'GCS OAuth token exchange failed', { cause: error })
    }

    if (!response.ok) {
      const error = await errorFromResponse(response, 'GCS OAuth token exchange')
      if (response.status === 400 || response.status === 401) {
        throw new StorageError('auth', error.message, { status: response.status })
      }
      throw error
    }

    const token = (await response.json()) as {
      access_token?: string
      expires_in?: number
    }
    if (typeof token.access_token !== 'string' || token.access_token === '') {
      throw new StorageError('auth', 'GCS OAuth token response is missing access_token')
    }

    this.accessToken = token.access_token
    this.accessTokenExpiresAt = nowMs + (token.expires_in ?? 3600) * 1000
    return token.access_token
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

  private basePath(): string {
    const path = this.endpoint.pathname.replace(/\/+$/u, '')
    return path === '/' ? '' : path
  }

  private listUrl(): URL {
    const url = new URL(this.endpoint.toString())
    url.pathname = `${this.basePath()}/storage/v1/b/${encodeURIComponent(this.bucket)}/o`
    return url
  }

  private objectUrl(key: string): URL {
    const url = new URL(this.endpoint.toString())
    url.pathname = `${this.basePath()}/storage/v1/b/${encodeURIComponent(
      this.bucket,
    )}/o/${encodeURIComponent(fullKey(this.prefix, key))}`
    return url
  }

  private uploadUrl(): URL {
    const url = new URL(this.endpoint.toString())
    url.pathname = `${this.basePath()}/upload/storage/v1/b/${encodeURIComponent(
      this.bucket,
    )}/o`
    return url
  }
}
