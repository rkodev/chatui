import { describe, expect, it } from 'vitest'
import {
  buildCanonicalRequest,
  deriveSigningKey,
  encodeRfc3986,
  formatAmzDate,
  sha256Hex,
  signRequest,
} from './sigv4'

const AWS_CREDENTIALS = {
  accessKeyId: 'AKIAIOSFODNN7EXAMPLE',
  secretAccessKey: 'wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY',
}

const AWS_DATE = new Date('2013-05-24T00:00:00Z')

describe('SigV4', () => {
  it('matches the AWS documentation GET object known-answer vector', async () => {
    const signed = await signRequest({
      method: 'GET',
      url: 'https://examplebucket.s3.amazonaws.com/test.txt',
      region: 'us-east-1',
      credentials: AWS_CREDENTIALS,
      headers: { range: 'bytes=0-9' },
      date: AWS_DATE,
    })

    expect(signed.headers['x-amz-date']).toBe('20130524T000000Z')
    expect(signed.headers['x-amz-content-sha256']).toBe(
      'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
    )
    expect(signed.headers.authorization).toBe(
      'AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, ' +
        'SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, ' +
        'Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41',
    )
  })

  it('matches the AWS documentation PUT object known-answer vector', async () => {
    const payload = new TextEncoder().encode('Welcome to Amazon S3.')
    const signed = await signRequest({
      method: 'PUT',
      url: 'https://examplebucket.s3.amazonaws.com/test%24file.text',
      region: 'us-east-1',
      credentials: AWS_CREDENTIALS,
      headers: {
        date: 'Fri, 24 May 2013 00:00:00 GMT',
        'x-amz-storage-class': 'REDUCED_REDUNDANCY',
      },
      payload,
      date: AWS_DATE,
    })

    expect(signed.headers['x-amz-content-sha256']).toBe(
      '44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072',
    )
    expect(signed.headers.authorization).toBe(
      'AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, ' +
        'SignedHeaders=date;host;x-amz-content-sha256;x-amz-date;x-amz-storage-class, ' +
        'Signature=98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd',
    )
  })

  it('sorts query parameters and encodes RFC 3986 characters', async () => {
    const { canonicalRequest } = buildCanonicalRequest({
      method: 'GET',
      url: 'https://examplebucket.s3.amazonaws.com/?prefix=a%2Fb&list-type=2&max-keys=10',
      headers: { host: 'examplebucket.s3.amazonaws.com' },
      payloadHash: await sha256Hex(''),
    })

    expect(canonicalRequest.split('\n')[2]).toBe('list-type=2&max-keys=10&prefix=a%2Fb')
    expect(encodeRfc3986("a b!'()*")).toBe('a%20b%21%27%28%29%2A')
  })

  it('adds a session token header when credentials include one', async () => {
    const signed = await signRequest({
      method: 'HEAD',
      url: 'https://examplebucket.s3.amazonaws.com/',
      region: 'us-east-1',
      credentials: { ...AWS_CREDENTIALS, sessionToken: 'session-token' },
      date: AWS_DATE,
    })

    expect(signed.headers['x-amz-security-token']).toBe('session-token')
    expect(signed.headers.authorization).toContain('x-amz-security-token')
  })

  it('formats dates and derives a stable signing key', async () => {
    expect(formatAmzDate(AWS_DATE)).toBe('20130524T000000Z')
    const key = await deriveSigningKey(
      AWS_CREDENTIALS.secretAccessKey,
      '20130524',
      'us-east-1',
      's3',
    )
    expect(key).toHaveLength(32)
  })
})
