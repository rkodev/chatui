import { beforeAll, describe, expect, it, vi } from 'vitest'
import { bytesToBase64 } from './http'
import { GcsStorageAdapter, type GcsStorageConfig } from './gcs'

const FIXED_DATE = new Date('2026-01-01T00:00:00Z')
const TOKEN_URI = 'https://oauth.example.test/token'

/**
 * Synthetic envelope-shaped ciphertext. Tests never pass plaintext to the
 * adapter; these bytes only exercise the transport boundary.
 */
const ENCRYPTED_BYTES = new Uint8Array([
  0x43, 0x48, 0x41, 0x54, 0x55, 0x49, 0x31, 0x0a, 0x00, 0x00, 0x00, 0x02, 0x7b, 0x7d, 0x01, 0x02,
  0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0xaa, 0xbb, 0xcc, 0xdd,
])

let serviceAccountJson = ''

beforeAll(async () => {
  const pair = (await globalThis.crypto.subtle.generateKey(
    {
      name: 'RSASSA-PKCS1-v1_5',
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: 'SHA-256',
    },
    true,
    ['sign', 'verify'],
  )) as CryptoKeyPair
  const pkcs8 = new Uint8Array(
    await globalThis.crypto.subtle.exportKey('pkcs8', pair.privateKey),
  )
  const body = bytesToBase64(pkcs8).match(/.{1,64}/gu)?.join('\n') ?? ''
  serviceAccountJson = JSON.stringify({
    client_email: 'backup@example.test',
    private_key: `-----BEGIN PRIVATE KEY-----\n${body}\n-----END PRIVATE KEY-----\n`,
    token_uri: TOKEN_URI,
  })
})

function tokenResponse(): Response {
  return new Response(JSON.stringify({ access_token: 'token-1', expires_in: 3600 }), {
    status: 200,
    headers: { 'content-type': 'application/json' },
  })
}

function makeFetch(
  handler: (url: string, init: RequestInit) => Response | Promise<Response>,
): ReturnType<typeof vi.fn> {
  return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input.toString()
    if (url === TOKEN_URI) {
      return tokenResponse()
    }
    return handler(url, init ?? {})
  })
}

function makeAdapter(
  fetchMock: ReturnType<typeof vi.fn>,
  overrides: Partial<GcsStorageConfig> = {},
): GcsStorageAdapter {
  return new GcsStorageAdapter({
    bucket: 'chatui-backup',
    serviceAccountJson,
    endpoint: 'https://gcs.example.test',
    fetch: fetchMock as unknown as typeof fetch,
    now: () => FIXED_DATE,
    retryDelayMs: 0,
    ...overrides,
  })
}

function requestAt(fetchMock: ReturnType<typeof vi.fn>, index = 0): [string, RequestInit] {
  return fetchMock.mock.calls[index] as unknown as [string, RequestInit]
}

function headersAt(fetchMock: ReturnType<typeof vi.fn>, index = 0): Record<string, string> {
  return requestAt(fetchMock, index)[1].headers as Record<string, string>
}

function decodeBase64Url(value: string): string {
  const base64 = value.replace(/-/gu, '+').replace(/_/gu, '/')
  return atob(base64.padEnd(Math.ceil(base64.length / 4) * 4, '='))
}

describe('GcsStorageAdapter', () => {
  it('exchanges a signed JWT for an access token and GETs an object', async () => {
    const fetchMock = makeFetch(
      async () =>
        new Response(ENCRYPTED_BYTES, {
          status: 200,
          headers: {
            'x-goog-generation': '123',
            'last-modified': 'Wed, 01 Jan 2026 00:00:00 GMT',
          },
        }),
    )
    const adapter = makeAdapter(fetchMock)

    const object = await adapter.getObject('manifest.enc')

    expect(object.key).toBe('manifest.enc')
    expect(object.bytes).toEqual(ENCRYPTED_BYTES)
    expect(object.etag).toBe('123')
    expect(object.lastModified).toBe('Wed, 01 Jan 2026 00:00:00 GMT')

    const [tokenUrl, tokenInit] = requestAt(fetchMock)
    expect(tokenUrl).toBe(TOKEN_URI)
    expect(tokenInit.method).toBe('POST')
    const tokenBody = new URLSearchParams(tokenInit.body as string)
    expect(tokenBody.get('grant_type')).toBe(
      'urn:ietf:params:oauth:grant-type:jwt-bearer',
    )
    const assertion = tokenBody.get('assertion') ?? ''
    const [header, claims] = assertion.split('.')
    expect(JSON.parse(decodeBase64Url(header))).toEqual({
      alg: 'RS256',
      typ: 'JWT',
    })
    const payload = JSON.parse(decodeBase64Url(claims)) as Record<string, unknown>
    expect(payload.iss).toBe('backup@example.test')
    expect(payload.aud).toBe(TOKEN_URI)
    expect(payload.scope).toBe('https://www.googleapis.com/auth/devstorage.read_write')

    const [url, init] = requestAt(fetchMock, 1)
    expect(url).toBe(
      'https://gcs.example.test/storage/v1/b/chatui-backup/o/manifest.enc?alt=media',
    )
    expect(init.method).toBe('GET')
    expect(headersAt(fetchMock, 1).authorization).toBe('Bearer token-1')
  })

  it('PUTs encrypted bytes with a generation precondition and captures the generation', async () => {
    const fetchMock = makeFetch(
      async () =>
        new Response(JSON.stringify({ generation: '124', updated: '2026-01-01T00:00:00Z' }), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        }),
    )
    const adapter = makeAdapter(fetchMock)

    const object = await adapter.putObject('manifest.enc', ENCRYPTED_BYTES, {
      ifMatch: '123',
      contentType: 'application/octet-stream',
    })

    expect(object.etag).toBe('124')
    expect(object.bytes).toBe(ENCRYPTED_BYTES)

    const [url, init] = requestAt(fetchMock, 1)
    const parsed = new URL(url)
    expect(parsed.pathname).toBe('/upload/storage/v1/b/chatui-backup/o')
    expect(parsed.searchParams.get('uploadType')).toBe('media')
    expect(parsed.searchParams.get('name')).toBe('manifest.enc')
    expect(parsed.searchParams.get('ifGenerationMatch')).toBe('123')
    expect(init.method).toBe('POST')
    expect(init.body).toBeInstanceOf(ArrayBuffer)
    expect(new Uint8Array(init.body as ArrayBuffer)).toEqual(ENCRYPTED_BYTES)
    expect(headersAt(fetchMock, 1)['content-type']).toBe('application/octet-stream')
  })

  it('uses ifGenerationMatch=0 for create-only writes', async () => {
    const fetchMock = makeFetch(
      async () =>
        new Response(JSON.stringify({ generation: '1' }), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        }),
    )
    const adapter = makeAdapter(fetchMock)

    await adapter.putObject('manifest.enc', ENCRYPTED_BYTES, { ifNoneMatch: '*' })

    const parsed = new URL(requestAt(fetchMock, 1)[0])
    expect(parsed.searchParams.get('ifGenerationMatch')).toBe('0')
  })

  it('maps a conditional write failure to a conflict error', async () => {
    const fetchMock = makeFetch(
      async () => new Response('Precondition Failed', { status: 412 }),
    )
    const adapter = makeAdapter(fetchMock)

    await expect(
      adapter.putObject('manifest.enc', ENCRYPTED_BYTES, { ifMatch: 'stale' }),
    ).rejects.toMatchObject({ name: 'StorageError', code: 'conflict', status: 412 })
  })

  it('HEADs an object through the metadata endpoint', async () => {
    const fetchMock = makeFetch(
      async () =>
        new Response(JSON.stringify({ generation: '123', updated: '2026-01-01T00:00:00Z' }), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        }),
    )
    const adapter = makeAdapter(fetchMock)

    const object = await adapter.headObject('manifest.enc')

    expect(object.key).toBe('manifest.enc')
    expect(object.bytes).toHaveLength(0)
    expect(object.etag).toBe('123')
    expect(requestAt(fetchMock, 1)[1].method).toBe('GET')
    expect(requestAt(fetchMock, 1)[0]).not.toContain('alt=media')
  })

  it('DELETEs an object and treats a missing object as success', async () => {
    const fetchMock = makeFetch(async () => new Response(null, { status: 204 }))
    const adapter = makeAdapter(fetchMock)

    await expect(adapter.deleteObject('manifest.enc')).resolves.toBeUndefined()
    expect(requestAt(fetchMock, 1)[1].method).toBe('DELETE')

    const missing = makeFetch(async () => new Response(null, { status: 404 }))
    await expect(makeAdapter(missing).deleteObject('manifest.enc')).resolves.toBeUndefined()
  })

  it('lists objects, strips the configured prefix, and returns a page token', async () => {
    const fetchMock = makeFetch(
      async () =>
        new Response(
          JSON.stringify({
            items: [
              { name: 'chatui/manifest.enc', generation: '1', updated: '2026-01-01T00:00:00Z' },
              {
                name: 'chatui/conversations/0123456789abcdef0123456789abcdef.enc',
                generation: '2',
                updated: '2026-01-01T00:00:00Z',
              },
            ],
            nextPageToken: 'next-token',
          }),
          { status: 200, headers: { 'content-type': 'application/json' } },
        ),
    )
    const adapter = makeAdapter(fetchMock, { prefix: 'chatui' })

    const result = await adapter.listObjects({ prefix: 'conversations/', maxKeys: 100 })

    expect(result.objects.map((object) => object.key)).toEqual([
      'manifest.enc',
      'conversations/0123456789abcdef0123456789abcdef.enc',
    ])
    expect(result.objects[0].etag).toBe('1')
    expect(result.nextContinuationToken).toBe('next-token')

    const parsed = new URL(requestAt(fetchMock, 1)[0])
    expect(parsed.pathname).toBe('/storage/v1/b/chatui-backup/o')
    expect(parsed.searchParams.get('prefix')).toBe('chatui/conversations/')
    expect(parsed.searchParams.get('maxResults')).toBe('100')
  })

  it('rejects a non-byte body before sending a request', async () => {
    const fetchMock = makeFetch(async () => new Response(null, { status: 200 }))
    const adapter = makeAdapter(fetchMock)

    await expect(
      adapter.putObject('manifest.enc', 'plaintext' as unknown as Uint8Array),
    ).rejects.toMatchObject({ name: 'StorageError', code: 'server' })
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('maps network failures to a typed network error', async () => {
    const fetchMock = vi.fn(async () => {
      throw new TypeError('Failed to fetch')
    })
    const adapter = makeAdapter(fetchMock)

    await expect(adapter.getObject('manifest.enc')).rejects.toMatchObject({
      name: 'StorageError',
      code: 'network',
    })
  })

  it.each([
    [200, undefined],
    [401, 'auth'],
    [403, 'permission'],
    [404, 'not_found'],
    [500, 'server'],
  ])('testConnection maps HTTP %i to %s', async (status, code) => {
    const fetchMock = makeFetch(async () => new Response(null, { status }))
    const adapter = makeAdapter(fetchMock)

    if (code === undefined) {
      await expect(adapter.testConnection()).resolves.toBeUndefined()
    } else {
      await expect(adapter.testConnection()).rejects.toMatchObject({
        name: 'StorageError',
        code,
        status,
      })
    }

    const parsed = new URL(requestAt(fetchMock, 1)[0])
    expect(parsed.pathname).toBe('/storage/v1/b/chatui-backup/o')
    expect(parsed.searchParams.get('maxResults')).toBe('1')
  })
})
