import { describe, expect, it, vi } from 'vitest'
import {
  GoogleDriveStorageAdapter,
  type GoogleDriveStorageConfig,
} from './google-drive'

const FIXED_DATE = new Date('2026-01-01T00:00:00Z')

/**
 * Synthetic envelope-shaped ciphertext. Tests never pass plaintext to the
 * adapter; these bytes only exercise the transport boundary.
 */
const ENCRYPTED_BYTES = new Uint8Array([
  0x43, 0x48, 0x41, 0x54, 0x55, 0x49, 0x31, 0x0a, 0x00, 0x00, 0x00, 0x02, 0x7b, 0x7d, 0x01, 0x02,
  0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0xaa, 0xbb, 0xcc, 0xdd,
])

function json(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { 'content-type': 'application/json' },
  })
}

function makeAdapter(
  fetchMock: ReturnType<typeof vi.fn>,
  overrides: Partial<GoogleDriveStorageConfig> = {},
): GoogleDriveStorageAdapter {
  return new GoogleDriveStorageAdapter({
    accessToken: 'drive-token',
    folderId: 'folder-1',
    endpoint: 'https://drive.example.test',
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

describe('GoogleDriveStorageAdapter', () => {
  it('finds a file and GETs its encrypted bytes', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input))
      if (url.pathname === '/drive/v3/files' && init?.method === 'GET') {
        return json({
          files: [
            {
              id: 'file-1',
              name: 'manifest.enc',
              version: 'v1',
              modifiedTime: '2026-01-01T00:00:00Z',
            },
          ],
        })
      }
      if (url.pathname === '/drive/v3/files/file-1' && url.searchParams.get('alt') === 'media') {
        return new Response(ENCRYPTED_BYTES, { status: 200 })
      }
      throw new Error(`unexpected request ${url.toString()}`)
    })
    const adapter = makeAdapter(fetchMock)

    const object = await adapter.getObject('manifest.enc')

    expect(object.key).toBe('manifest.enc')
    expect(object.bytes).toEqual(ENCRYPTED_BYTES)
    expect(object.etag).toBe('v1')
    expect(object.lastModified).toBe('2026-01-01T00:00:00Z')

    const [findUrl, findInit] = requestAt(fetchMock)
    expect(findUrl).toContain('/drive/v3/files?')
    expect(findInit.method).toBe('GET')
    expect(headersAt(fetchMock).authorization).toBe('Bearer drive-token')
    expect(new URL(findUrl).searchParams.get('q')).toContain("'folder-1' in parents")

    const [mediaUrl, mediaInit] = requestAt(fetchMock, 1)
    expect(mediaUrl).toContain('/drive/v3/files/file-1?alt=media')
    expect(mediaInit.method).toBe('GET')
  })

  it('creates a file with a multipart upload and re-reads metadata', async () => {
    let findCalls = 0
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input))
      if (url.pathname === '/drive/v3/files' && init?.method === 'GET') {
        findCalls += 1
        return json({
          files:
            findCalls === 1
              ? []
              : [
                  {
                    id: 'file-2',
                    name: 'manifest.enc',
                    version: 'v1',
                    modifiedTime: '2026-01-01T00:00:00Z',
                  },
                ],
        })
      }
      if (url.pathname === '/upload/drive/v3/files' && init?.method === 'POST') {
        return json({ id: 'file-2', name: 'manifest.enc', version: 'v1' })
      }
      throw new Error(`unexpected request ${url.toString()}`)
    })
    const adapter = makeAdapter(fetchMock)

    const object = await adapter.putObject('manifest.enc', ENCRYPTED_BYTES, {
      contentType: 'application/octet-stream',
    })

    expect(object.etag).toBe('v1')
    expect(object.bytes).toBe(ENCRYPTED_BYTES)

    const [url, init] = requestAt(fetchMock, 1)
    const parsed = new URL(url)
    expect(parsed.pathname).toBe('/upload/drive/v3/files')
    expect(parsed.searchParams.get('uploadType')).toBe('multipart')
    expect(init.method).toBe('POST')
    expect(headersAt(fetchMock, 1)['content-type']).toContain('multipart/related')
    const body = new Uint8Array(init.body as ArrayBuffer)
    const text = new TextDecoder().decode(body)
    expect(text).toContain('"name":"manifest.enc"')
    expect(text).toContain('"parents":["folder-1"]')
    expect(text).toContain('Content-Type: application/octet-stream')
    expect(body).toEqual(expect.any(Uint8Array))
  })

  it('updates an existing file with a media upload and If-Match version check', async () => {
    let findCalls = 0
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input))
      if (url.pathname === '/drive/v3/files' && init?.method === 'GET') {
        findCalls += 1
        return json({
          files: [
            {
              id: 'file-1',
              name: 'manifest.enc',
              version: findCalls === 1 ? 'v1' : 'v2',
              modifiedTime: '2026-01-01T00:00:00Z',
            },
          ],
        })
      }
      if (url.pathname === '/upload/drive/v3/files/file-1' && init?.method === 'PATCH') {
        return json({ id: 'file-1', name: 'manifest.enc', version: 'v2' })
      }
      throw new Error(`unexpected request ${url.toString()}`)
    })
    const adapter = makeAdapter(fetchMock)

    const object = await adapter.putObject('manifest.enc', ENCRYPTED_BYTES, {
      ifMatch: 'v1',
    })

    expect(object.etag).toBe('v2')
    const [url, init] = requestAt(fetchMock, 1)
    expect(new URL(url).pathname).toBe('/upload/drive/v3/files/file-1')
    expect(init.method).toBe('PATCH')
    expect(new Uint8Array(init.body as ArrayBuffer)).toEqual(ENCRYPTED_BYTES)
  })

  it('maps a stale If-Match version to a conflict without writing', async () => {
    const fetchMock = vi.fn(async () =>
      json({
        files: [
          {
            id: 'file-1',
            name: 'manifest.enc',
            version: 'v1',
            modifiedTime: '2026-01-01T00:00:00Z',
          },
        ],
      }),
    )
    const adapter = makeAdapter(fetchMock)

    await expect(
      adapter.putObject('manifest.enc', ENCRYPTED_BYTES, { ifMatch: 'stale' }),
    ).rejects.toMatchObject({ name: 'StorageError', code: 'conflict', status: 409 })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('maps If-None-Match to a conflict when the file already exists', async () => {
    const fetchMock = vi.fn(async () =>
      json({
        files: [
          {
            id: 'file-1',
            name: 'manifest.enc',
            version: 'v1',
            modifiedTime: '2026-01-01T00:00:00Z',
          },
        ],
      }),
    )
    const adapter = makeAdapter(fetchMock)

    await expect(
      adapter.putObject('manifest.enc', ENCRYPTED_BYTES, { ifNoneMatch: '*' }),
    ).rejects.toMatchObject({ name: 'StorageError', code: 'conflict', status: 409 })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('HEADs a file through metadata without downloading bytes', async () => {
    const fetchMock = vi.fn(async () =>
      json({
        files: [
          {
            id: 'file-1',
            name: 'manifest.enc',
            version: 'v1',
            modifiedTime: '2026-01-01T00:00:00Z',
          },
        ],
      }),
    )
    const adapter = makeAdapter(fetchMock)

    const object = await adapter.headObject('manifest.enc')

    expect(object.key).toBe('manifest.enc')
    expect(object.bytes).toHaveLength(0)
    expect(object.etag).toBe('v1')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('DELETEs a file and treats a missing file as success', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input))
      if (url.pathname === '/drive/v3/files' && init?.method === 'GET') {
        return json({
          files: [
            {
              id: 'file-1',
              name: 'manifest.enc',
              version: 'v1',
              modifiedTime: '2026-01-01T00:00:00Z',
            },
          ],
        })
      }
      if (url.pathname === '/drive/v3/files/file-1' && init?.method === 'DELETE') {
        return new Response(null, { status: 204 })
      }
      throw new Error(`unexpected request ${url.toString()}`)
    })
    const adapter = makeAdapter(fetchMock)

    await expect(adapter.deleteObject('manifest.enc')).resolves.toBeUndefined()
    expect(requestAt(fetchMock, 1)[1].method).toBe('DELETE')

    const missing = vi.fn(async () => json({ files: [] }))
    await expect(makeAdapter(missing).deleteObject('manifest.enc')).resolves.toBeUndefined()
  })

  it('lists files, filters by prefix, and returns a page token', async () => {
    const fetchMock = vi.fn(async () =>
      json({
        files: [
          {
            id: 'file-1',
            name: 'chatui/manifest.enc',
            version: 'v1',
            modifiedTime: '2026-01-01T00:00:00Z',
          },
          {
            id: 'file-2',
            name: 'chatui/conversations/0123456789abcdef0123456789abcdef.enc',
            version: 'v2',
            modifiedTime: '2026-01-01T00:00:00Z',
          },
          {
            id: 'file-3',
            name: 'other/ignored.enc',
            version: 'v3',
            modifiedTime: '2026-01-01T00:00:00Z',
          },
        ],
        nextPageToken: 'next-token',
      }),
    )
    const adapter = makeAdapter(fetchMock, { prefix: 'chatui' })

    const result = await adapter.listObjects({ prefix: 'conversations/', maxKeys: 100 })

    expect(result.objects.map((object) => object.key)).toEqual([
      'conversations/0123456789abcdef0123456789abcdef.enc',
    ])
    expect(result.objects[0].etag).toBe('v2')
    expect(result.nextContinuationToken).toBe('next-token')

    const parsed = new URL(requestAt(fetchMock)[0])
    expect(parsed.pathname).toBe('/drive/v3/files')
    expect(parsed.searchParams.get('pageSize')).toBe('100')
    expect(parsed.searchParams.get('q')).toContain("'folder-1' in parents")
  })

  it('rejects a non-byte body before sending a request', async () => {
    const fetchMock = vi.fn(async () => json({ files: [] }))
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
    const fetchMock = vi.fn(async () => new Response(null, { status }))
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

    const parsed = new URL(requestAt(fetchMock)[0])
    expect(parsed.pathname).toBe('/drive/v3/about')
    expect(parsed.searchParams.get('fields')).toBe('user')
  })
})
