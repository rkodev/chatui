/**
 * Shared HTTP helpers for storage adapters.
 *
 * Adapters only ever receive opaque bytes. These helpers do not inspect or
 * transform object bodies beyond moving them into `ArrayBuffer` values for
 * `fetch`.
 */

import { StorageError, type StorageErrorCode } from './types'

export function statusToCode(status: number): StorageErrorCode {
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

export function headerValue(response: Response, name: string): string | undefined {
  const value = response.headers.get(name)
  return value === null || value === '' ? undefined : value
}

export function toArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const copy = new Uint8Array(bytes.byteLength)
  copy.set(bytes)
  return copy.buffer
}

export function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve, milliseconds)
  })
}

export async function errorFromResponse(
  response: Response,
  action: string,
): Promise<StorageError> {
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

export async function ensureOk(response: Response, action: string): Promise<void> {
  if (response.ok) {
    return
  }
  throw await errorFromResponse(response, action)
}

export function encodeKeyPath(key: string): string {
  return key
    .split('/')
    .map((segment) => encodeURIComponent(segment))
    .join('/')
}

export function normalizePrefix(prefix: string | undefined): string {
  return (prefix ?? '').replace(/^\/+|\/+$/gu, '')
}

export function fullKey(prefix: string, key: string): string {
  const normalized = key.replace(/^\/+/u, '')
  if (normalized === '') {
    throw new StorageError('server', 'Object key must not be empty')
  }
  return prefix === '' ? normalized : `${prefix}/${normalized}`
}

export function fullPrefix(prefix: string, extra: string | undefined): string {
  const normalized = (extra ?? '').replace(/^\/+/u, '')
  if (prefix === '') {
    return normalized
  }
  return normalized === '' ? `${prefix}/` : `${prefix}/${normalized}`
}

export function logicalKey(prefix: string, actualKey: string): string {
  if (prefix === '') {
    return actualKey
  }
  const withSlash = `${prefix}/`
  return actualKey.startsWith(withSlash) ? actualKey.slice(withSlash.length) : actualKey
}

export function utf8(value: string): Uint8Array {
  return new TextEncoder().encode(value)
}

export function bytesToBase64(bytes: Uint8Array): string {
  let binary = ''
  const chunkSize = 0x8000
  for (let offset = 0; offset < bytes.length; offset += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + chunkSize))
  }
  return btoa(binary)
}

export function base64ToBytes(value: string): Uint8Array {
  const binary = atob(value)
  const bytes = new Uint8Array(binary.length)
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index)
  }
  return bytes
}

export function base64UrlEncode(bytes: Uint8Array): string {
  return bytesToBase64(bytes).replace(/\+/gu, '-').replace(/\//gu, '_').replace(/=+$/u, '')
}

export function base64UrlEncodeString(value: string): string {
  return base64UrlEncode(utf8(value))
}

/** Convert a PEM-encoded PKCS#8 private key into DER bytes. */
export function pemToDer(pem: string): Uint8Array {
  const body = pem
    .replace(/-----BEGIN [^-]+-----/gu, '')
    .replace(/-----END [^-]+-----/gu, '')
    .replace(/\s+/gu, '')
  if (body === '') {
    throw new StorageError('auth', 'Private key PEM is empty')
  }
  return base64ToBytes(body)
}

export function concatBytes(...parts: Uint8Array[]): Uint8Array {
  const total = parts.reduce((sum, part) => sum + part.byteLength, 0)
  const result = new Uint8Array(total)
  let offset = 0
  for (const part of parts) {
    result.set(part, offset)
    offset += part.byteLength
  }
  return result
}
