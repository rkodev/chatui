import { describe, expect, it, vi } from 'vitest'
import { S3StorageAdapter, type S3StorageConfig } from './s3'
import { sha256Hex } from './sigv4'

const FIXED_DATE = new Date('2026-01-01T00:00:00Z')

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
  overrides: Partial<S3StorageConfig> = {},
): S3StorageAdapter {
  return new S3StorageAdapter({
    endpoint: 'https://s3.example.test',
    bucket: 'chatui-backup',
    region: 'us-east-1',
    accessKeyId: 'test-access-key',
    secretAccessKey: 'test-secret-key',
    forcePathStyle: true,
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

describe('S3StorageAdapter', () => {
  it('GETs an object and returns bytes, ETag, and Last-Modified', async () => {
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
    expect(url).toBe('https://s3.example.test/chatui-backup/manifest.enc')
    expect(init.method).toBe('GET')
    expect(headersAt(fetchMock).authorization).toContain('AWS4-HMAC-SHA256')
    expect(headersAt(fetchMock)['x-amz-content-sha256']).toBe(await sha256Hex(''))
  })

  it('PUTs encrypted bytes with If-Match and captures the ETag', async () => {
    const fetchMock = vi.fn(
      async () => new Response(null, { status: 200, headers: { etag: '"etag-2"' } }),
    )
    const adapter = makeAdapter(fetchMock)

    const object = await adapter.putObject('manifest.enc', ENCRYPTED_BYTES, {
      ifMatch: '"etag-1"',
      contentType: 'application/octet-stream',
    })

    expect(object.etag).toBe('"etag-2"')
    expect(object.bytes).toBe(ENCRYPTED_BYTES)

    const [url, init] = requestAt(fetchMock)
    expect(url).toBe('https://s3.example.test/chatui-backup/manifest.enc')
    expect(init.method).toBe('PUT')
    expect(init.body).toBeInstanceOf(ArrayBuffer)
    expect(new Uint8Array(init.body as ArrayBuffer)).toEqual(ENCRYPTED_BYTES)
    expect(headersAt(fetchMock)['if-match']).toBe('"etag-1"')
    expect(headersAt(fetchMock)['content-type']).toBe('application/octet-stream')
  })

  it('PUTs with If-None-Match for create-only writes', async () => {
    const fetchMock = vi.fn(
      async () => new Response(null, { status: 200, headers: { etag: '"etag-new"' } }),
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
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 200 }))
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
    const fetchMock = vi.fn(async () => new Response(null, { status: 204 }))
    const adapter = makeAdapter(fetchMock)

    await expect(adapter.deleteObject('manifest.enc')).resolves.toBeUndefined()
    expect(requestAt(fetchMock)[1].method).toBe('DELETE')
  })

  it('lists objects, strips the configured prefix, and returns a continuation token', async () => {
    const xml = `<?xml version="1.0" encoding="UTF-8"?>
<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
  <Name>chatui-backup</Name>
  <Prefix>chatui/conversations/</Prefix>
  <KeyCount>2</KeyCount>
  <MaxKeys>1000</MaxKeys>
  <IsTruncated>true</IsTruncated>
  <Contents>
    <Key>chatui/manifest.enc</Key>
    <LastModified>2026-01-01T00:00:00.000Z</LastModified>
    <ETag>&quot;etag-a&quot;</ETag>
    <Size>32</Size>
    <StorageClass>STANDARD</StorageClass>
  </Contents>
  <Contents>
    <Key>chatui/conversations/0123456789abcdef0123456789abcdef.enc</Key>
    <LastModified>2026-01-01T00:00:00.000Z</LastModified>
    <ETag>&quot;etag-b&quot;</ETag>
    <Size>64</Size>
    <StorageClass>STANDARD</StorageClass>
  </Contents>
  <NextContinuationToken>next-token</NextContinuationToken>
</ListBucketResult>`
    const fetchMock = vi.fn(async () => new Response(xml, { status: 200 }))
    const adapter = makeAdapter(fetchMock, { prefix: 'chatui' })

    const result = await adapter.listObjects({ prefix: 'conversations/', maxKeys: 1000 })

    expect(result.objects.map((object) => object.key)).toEqual([
      'manifest.enc',
      'conversations/0123456789abcdef0123456789abcdef.enc',
    ])
    expect(result.objects[0].etag).toBe('"etag-a"')
    expect(result.objects[0].bytes).toHaveLength(0)
    expect(result.nextContinuationToken).toBe('next-token')

    const [url, init] = requestAt(fetchMock)
    const parsed = new URL(url)
    expect(init.method).toBe('GET')
    expect(parsed.pathname).toBe('/chatui-backup')
    expect(parsed.searchParams.get('list-type')).toBe('2')
    expect(parsed.searchParams.get('prefix')).toBe('chatui/conversations/')
    expect(parsed.searchParams.get('max-keys')).toBe('1000')
  })

  it('uses virtual-hosted-style URLs for AWS endpoints by default', async () => {
    const fetchMock = vi.fn(async () => new Response(ENCRYPTED_BYTES, { status: 200 }))
    const adapter = makeAdapter(fetchMock, {
      endpoint: 'https://s3.us-east-1.amazonaws.com',
      forcePathStyle: undefined,
    })

    await adapter.getObject('manifest.enc')

    expect(requestAt(fetchMock)[0]).toBe(
      'https://chatui-backup.s3.us-east-1.amazonaws.com/manifest.enc',
    )
  })

  it('applies a configured key prefix to object URLs', async () => {
    const fetchMock = vi.fn(async () => new Response(ENCRYPTED_BYTES, { status: 200 }))
    const adapter = makeAdapter(fetchMock, { prefix: 'chatui/backups' })

    const object = await adapter.getObject('manifest.enc')

    expect(object.key).toBe('manifest.enc')
    expect(requestAt(fetchMock)[0]).toBe(
      'https://s3.example.test/chatui-backup/chatui/backups/manifest.enc',
    )
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
    expect(url).toBe('https://s3.example.test/chatui-backup')
    expect(init.method).toBe('HEAD')
  })

  it('testConnection maps a rejected fetch to a network error', async () => {
    const fetchMock = vi.fn(async () => {
      throw new TypeError('Failed to fetch')
    })
    const adapter = makeAdapter(fetchMock)

    await expect(adapter.testConnection()).rejects.toMatchObject({
      name: 'StorageError',
      code: 'network',
    })
  })
})
