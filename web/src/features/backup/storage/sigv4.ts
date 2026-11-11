/**
 * Minimal AWS Signature Version 4 signer for S3-compatible object storage.
 *
 * This module deliberately has no cloud SDK dependency. It implements the
 * canonical request, string to sign, signing key, and `Authorization` header
 * with WebCrypto so the web client can talk directly to a bucket.
 */

export const SIGV4_ALGORITHM = 'AWS4-HMAC-SHA256'
export const SIGV4_SERVICE = 's3'
export const SIGV4_TERMINATOR = 'aws4_request'

export type SigV4Method = 'GET' | 'PUT' | 'HEAD' | 'DELETE'

export interface SigV4Credentials {
  accessKeyId: string
  secretAccessKey: string
  sessionToken?: string
}

export interface SigV4SignOptions {
  method: SigV4Method
  url: string
  region: string
  credentials: SigV4Credentials
  /** Extra request headers. Header names are matched case-insensitively. */
  headers?: Record<string, string>
  /** Request body. Omit for GET, HEAD, and DELETE. */
  payload?: Uint8Array | string
  service?: string
  /** Fixed clock for tests. Defaults to the current time. */
  date?: Date
}

export interface SignedRequest {
  method: SigV4Method
  url: string
  /** Lowercase header names plus `authorization`. */
  headers: Record<string, string>
}

function subtle(): SubtleCrypto {
  const value = globalThis.crypto?.subtle
  if (value === undefined) {
    throw new Error('WebCrypto is not available in this runtime')
  }
  return value
}

function toArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const copy = new Uint8Array(bytes.byteLength)
  copy.set(bytes)
  return copy.buffer
}

function toBytes(data: Uint8Array | string): Uint8Array {
  return typeof data === 'string' ? new TextEncoder().encode(data) : data
}

function toHex(bytes: Uint8Array): string {
  let output = ''
  for (const byte of bytes) {
    output += byte.toString(16).padStart(2, '0')
  }
  return output
}

/** RFC 3986 encoding as required by SigV4. `encodeURIComponent` leaves `!'()*`. */
export function encodeRfc3986(value: string): string {
  return encodeURIComponent(value).replace(
    /[!'()*]/gu,
    (character) => `%${character.charCodeAt(0).toString(16).toUpperCase()}`,
  )
}

export async function sha256Hex(data: Uint8Array | string): Promise<string> {
  const digest = await subtle().digest('SHA-256', toArrayBuffer(toBytes(data)))
  return toHex(new Uint8Array(digest))
}

async function hmacSha256(key: Uint8Array, data: string): Promise<Uint8Array> {
  const cryptoKey = await subtle().importKey(
    'raw',
    toArrayBuffer(key),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign'],
  )
  const signature = await subtle().sign(
    'HMAC',
    cryptoKey,
    toArrayBuffer(new TextEncoder().encode(data)),
  )
  return new Uint8Array(signature)
}

/** `YYYYMMDDTHHMMSSZ` without milliseconds. */
export function formatAmzDate(date: Date): string {
  const iso = date.toISOString()
  return `${iso.slice(0, 4)}${iso.slice(5, 7)}${iso.slice(8, 10)}T${iso.slice(11, 13)}${iso.slice(
    14,
    16,
  )}${iso.slice(17, 19)}Z`
}

export async function deriveSigningKey(
  secretAccessKey: string,
  dateStamp: string,
  region: string,
  service: string = SIGV4_SERVICE,
): Promise<Uint8Array> {
  const kDate = await hmacSha256(new TextEncoder().encode(`AWS4${secretAccessKey}`), dateStamp)
  const kRegion = await hmacSha256(kDate, region)
  const kService = await hmacSha256(kRegion, service)
  return hmacSha256(kService, SIGV4_TERMINATOR)
}

function canonicalQuery(searchParams: URLSearchParams): string {
  const pairs = [...searchParams.entries()].map(
    ([key, value]) => [encodeRfc3986(key), encodeRfc3986(value)] as const,
  )
  pairs.sort((left, right) => {
    if (left[0] !== right[0]) {
      return left[0] < right[0] ? -1 : 1
    }
    if (left[1] !== right[1]) {
      return left[1] < right[1] ? -1 : 1
    }
    return 0
  })
  return pairs.map(([key, value]) => `${key}=${value}`).join('&')
}

function normalizeHeaderValue(value: string): string {
  return value.trim().replace(/\s+/gu, ' ')
}

function canonicalHeaders(headers: Record<string, string>): {
  canonical: string
  signedHeaders: string
} {
  const names = Object.keys(headers).sort()
  const canonical = names
    .map((name) => `${name}:${normalizeHeaderValue(headers[name])}\n`)
    .join('')
  return { canonical, signedHeaders: names.join(';') }
}

export interface CanonicalRequestInput {
  method: SigV4Method
  url: string
  headers: Record<string, string>
  payloadHash: string
}

export function buildCanonicalRequest({
  method,
  url,
  headers,
  payloadHash,
}: CanonicalRequestInput): { canonicalRequest: string; signedHeaders: string } {
  const parsed = new URL(url)
  const canonicalUri = parsed.pathname === '' ? '/' : parsed.pathname
  const query = canonicalQuery(parsed.searchParams)
  const { canonical, signedHeaders } = canonicalHeaders(headers)

  const canonicalRequest = [
    method,
    canonicalUri,
    query,
    canonical,
    signedHeaders,
    payloadHash,
  ].join('\n')

  return { canonicalRequest, signedHeaders }
}

export function buildStringToSign(
  amzDate: string,
  scope: string,
  canonicalRequestHash: string,
): string {
  return [SIGV4_ALGORITHM, amzDate, scope, canonicalRequestHash].join('\n')
}

/**
 * Sign a request and return the URL, method, and headers to pass to `fetch`.
 *
 * The returned headers include `host`, `x-amz-date`, `x-amz-content-sha256`,
 * an optional `x-amz-security-token`, and `authorization`.
 */
export async function signRequest(options: SigV4SignOptions): Promise<SignedRequest> {
  const { method, url, region, credentials } = options
  if (credentials.accessKeyId === '' || credentials.secretAccessKey === '') {
    throw new Error('SigV4 requires an access key id and secret access key')
  }
  if (region === '') {
    throw new Error('SigV4 requires a region')
  }

  const service = options.service ?? SIGV4_SERVICE
  const date = options.date ?? new Date()
  const amzDate = formatAmzDate(date)
  const dateStamp = amzDate.slice(0, 8)
  const payloadHash = await sha256Hex(options.payload ?? new Uint8Array(0))

  const parsed = new URL(url)
  const headers: Record<string, string> = {}
  for (const [name, value] of Object.entries(options.headers ?? {})) {
    headers[name.toLowerCase()] = value
  }
  headers.host = parsed.host
  headers['x-amz-content-sha256'] = payloadHash
  headers['x-amz-date'] = amzDate
  if (credentials.sessionToken !== undefined && credentials.sessionToken !== '') {
    headers['x-amz-security-token'] = credentials.sessionToken
  }

  const { canonicalRequest, signedHeaders } = buildCanonicalRequest({
    method,
    url,
    headers,
    payloadHash,
  })
  const scope = `${dateStamp}/${region}/${service}/${SIGV4_TERMINATOR}`
  const stringToSign = buildStringToSign(
    amzDate,
    scope,
    await sha256Hex(canonicalRequest),
  )
  const signingKey = await deriveSigningKey(credentials.secretAccessKey, dateStamp, region, service)
  const signature = toHex(await hmacSha256(signingKey, stringToSign))

  headers.authorization =
    `${SIGV4_ALGORITHM} Credential=${credentials.accessKeyId}/${scope}, ` +
    `SignedHeaders=${signedHeaders}, Signature=${signature}`

  return { method, url, headers }
}
