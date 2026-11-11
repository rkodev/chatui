import { describe, expect, it, vi } from 'vitest'
import { AzureBlobStorageAdapter, type AzureBlobStorageConfig } from './azure'

const FIXED_DATE = new Date('2026-01-01T00:00:00Z')
const ACCOUNT_KEY = btoa('test-account-key')

/**
 * Synthetic envelope-shaped ciphertext. Tests never pass plaintext to the
 * adapter; these bytes only exercise the transport boundary.
 */
const ENCRYPTED_BYTES = new Uint8Array([
  0x43, 0x48, 0x41, 0x54, 0x55, 0x49, 0x31, 0x0a, 0x00, 0x00, 0x00, 0x02, 0x7b, 0x7d, 0x01, 0x02,
  0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0xaa, 0xbb, 0xcc, 0xdd,
])

function makeAdapter(
  fetchMock: ReturnType<typeof vi.fn>,
  overrides: Partial<AzureBlobStorageConfig> = {},
): AzureBlobStorageAdapter {
  return new AzureBlobStorageAdapter({
    accountName: 'chatuiaccount',
    accountKey: ACCOUNT_KEY,
    container: 'chatui-backup',
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

describe('AzureBlobStorageAdapter', () => {
  it('GETs an object with a Shared Key signature and returns metadata', async () => {
    const fetchMock = vi.fn(
      async () =>
        new Response(ENCRYPTED_BYTES, {
          status: 200,
          headers: {
            etag: '"etag-1"',
            'last-modified': 'Wed, 01 Jan 2026 00:00:00 GMT',
          },
        }),
    )
    const adapter = makeAdapter(fetchMock)

    const object = await adapter.getObject('manifest.enc')

    expect(object.key).toBe('manifest.enc')
    expect(object.bytes).toEqual(ENCRYPTED_BYTES)
    expect(object.etag).toBe('"etag-1"')
    expect(object.lastModified).toBe('Wed, 01 Jan 2026 00:00:00 GMT')

    const [url, init] = requestAt(fetchMock)
    expect(url).toBe(
      'https://chatuiaccount.blob.core.windows.net/chatui-backup/manifest.enc',
    )
    expect(init.method).toBe('GET')
    expect(headersAt(fetchMock).authorization).toMatch(
      /^SharedKey chatuiaccount:.+/u,
    )
    expect(headersAt(fetchMock)['x-ms-version']).toBe('2021-12-02')
    expect(headersAt(fetchMock)['x-ms-date']).toBe(FIXED_DATE.toUTCString())
  })

  it('PUTs encrypted bytes as a block blob with If-Match and captures the ETag', async () => {
    const fetchMock = vi.fn(
      async () => new Response(null, { status: 201, headers: { etag: '"etag-2"' } }),
    )
    const adapter = makeAdapter(fetchMock)

    const object = await adapter.putObject('manifest.enc', ENCRYPTED_BYTES, {
      ifMatch: '"etag-1"',
      contentType: 'application/octet-stream',
    })

    expect(object.etag).toBe('"etag-2"')
    expect(object.bytes).toBe(ENCRYPTED_BYTES)

    const [, init] = requestAt(fetchMock)
    expect(init.method).toBe('PUT')
    expect(init.body).toBeInstanceOf(ArrayBuffer)
    expect(new Uint8Array(init.body as ArrayBuffer)).toEqual(ENCRYPTED_BYTES)
    expect(headersAt(fetchMock)['if-match']).toBe('"etag-1"')
    expect(headersAt(fetchMock)['x-ms-blob-type']).toBe('BlockBlob')
    expect(headersAt(fetchMock)['content-type']).toBe('application/octet-stream')
  })

  it('PUTs with If-None-Match for create-only writes', async () => {
    const fetchMock = vi.fn(
      async () => new Response(null, { status: 201, headers: { etag: '"etag-new"' } }),
    )
    const adapter = makeAdapter(fetchMock)

    await adapter.putObject('manifest.enc', ENCRYPTED_BYTES, { ifNoneMatch: '*' })

    expect(headersAt(fetchMock)['if-none-match']).toBe('*')
  })

  it('maps a conditional write failure to a conflict error', async () => {
    const fetchMock = vi.fn(async () => new Response('Precondition Failed', { status: 412 }))
    const adapter = makeAdapter(fetchMock)

    await expect(
      adapter.putObject('manifest.enc', ENCRYPTED_BYTES, { ifMatch: '"stale"' }),
    ).rejects.toMatchObject({ name: 'StorageError', code: 'conflict', status: 412 })
  })

  it('re-reads after a write when the PUT response omits an ETag', async () => {
    const fetchMock = vi.fn()
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 201 }))
    fetchMock.mockResolvedValueOnce(
      new Response(null, { status: 200, headers: { etag: '"etag-after"' } }),
    )
    const adapter = makeAdapter(fetchMock)

    const object = await adapter.putObject('manifest.enc', ENCRYPTED_BYTES)

    expect(object.etag).toBe('"etag-after"')
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(requestAt(fetchMock, 1)[1].method).toBe('HEAD')
  })

  it('HEADs an object without downloading bytes', async () => {
    const fetchMock = vi.fn(
      async () => new Response(null, { status: 200, headers: { etag: '"etag-3"' } }),
    )
    const adapter = makeAdapter(fetchMock)

    const object = await adapter.headObject('manifest.enc')

    expect(object.key).toBe('manifest.enc')
    expect(object.bytes).toHaveLength(0)
    expect(object.etag).toBe('"etag-3"')
    expect(requestAt(fetchMock)[1].method).toBe('HEAD')
  })

  it('DELETEs an object', async () => {
    const fetchMock = vi.fn(async () => new Response(null, { status: 202 }))
    const adapter = makeAdapter(fetchMock)

    await expect(adapter.deleteObject('manifest.enc')).resolves.toBeUndefined()
    expect(requestAt(fetchMock)[1].method).toBe('DELETE')
  })

  it('lists blobs, strips the configured prefix, and returns a continuation token', async () => {
    const xml = `<?xml version="1.0" encoding="UTF-8"?>
<EnumerationResults ServiceEndpoint="https://chatuiaccount.blob.core.windows.net/" ContainerName="chatui-backup">
  <Prefix>chatui/conversations/</Prefix>
  <Blobs>
    <Blob>
      <Name>chatui/manifest.enc</Name>
      <Properties>
        <Last-Modified>Wed, 01 Jan 2026 00:00:00 GMT</Last-Modified>
        <Etag>0x8DETAGA</Etag>
      </Properties>
    </Blob>
    <Blob>
      <Name>chatui/conversations/0123456789abcdef0123456789abcdef.enc</Name>
      <Properties>
        <Last-Modified>Wed, 01 Jan 2026 00:00:00 GMT</Last-Modified>
        <Etag>0x8DETAGB</Etag>
      </Properties>
    </Blob>
  </Blobs>
  <NextMarker>next-marker</NextMarker>
</EnumerationResults>`
    const fetchMock = vi.fn(async () => new Response(xml, { status: 200 }))
    const adapter = makeAdapter(fetchMock, { prefix: 'chatui' })

    const result = await adapter.listObjects({ prefix: 'conversations/', maxKeys: 100 })

    expect(result.objects.map((object) => object.key)).toEqual([
      'manifest.enc',
      'conversations/0123456789abcdef0123456789abcdef.enc',
    ])
    expect(result.objects[0].etag).toBe('0x8DETAGA')
    expect(result.nextContinuationToken).toBe('next-marker')

    const [url, init] = requestAt(fetchMock)
    const parsed = new URL(url)
    expect(init.method).toBe('GET')
    expect(parsed.pathname).toBe('/chatui-backup')
    expect(parsed.searchParams.get('restype')).toBe('container')
    expect(parsed.searchParams.get('comp')).toBe('list')
    expect(parsed.searchParams.get('prefix')).toBe('chatui/conversations/')
    expect(parsed.searchParams.get('maxresults')).toBe('100')
  })

  it('uses a SAS token without a Shared Key signature', async () => {
    const fetchMock = vi.fn(async () => new Response(ENCRYPTED_BYTES, { status: 200 }))
    const adapter = makeAdapter(fetchMock, {
      accountKey: undefined,
      sasToken: '?sv=2021-12-02&sig=abc',
    })

    await adapter.getObject('manifest.enc')

    const [url] = requestAt(fetchMock)
    expect(url).toContain('sv=2021-12-02')
    expect(url).toContain('sig=abc')
    expect(headersAt(fetchMock).authorization).toBeUndefined()
  })

  it('rejects a non-byte body before sending a request', async () => {
    const fetchMock = vi.fn(async () => new Response(null, { status: 201 }))
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

    const [url, init] = requestAt(fetchMock)
    expect(url).toContain('/chatui-backup?restype=container')
    expect(init.method).toBe('HEAD')
  })
})
