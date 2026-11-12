import { describe, expect, it } from 'vitest'
import type { Attachment, Conversation } from '../../domain'
import type { ExportSnapshot } from '../../storage'
import { DEFAULT_SETTINGS } from '../settings'
import {
  MANIFEST_FORMAT_VERSION,
  ManifestError,
  buildManifest,
  mergeManifestIndexes,
  parseManifest,
  type ManifestBody,
} from './manifest'

const now = '2026-01-01T00:00:00.000Z'

function conversation(overrides: Partial<Conversation> = {}): Conversation {
  return {
    id: 'conv-1',
    title: 'Conversation',
    systemPrompt: '',
    providerId: 'provider-1',
    modelId: 'model-1',
    modelLabel: 'Model',
    params: {
      temperature: null,
      topP: null,
      maxOutputTokens: null,
      stop: [],
      reasoningEffort: null,
      streaming: true,
      extra: {},
    },
    pinned: false,
    archived: false,
    temporary: false,
    createdAt: now,
    updatedAt: now,
    lastMessageAt: now,
    messageCount: 0,
    lastError: null,
    sync: null,
    storageKey: '0123456789abcdef0123456789abcdef',
    revision: 1,
    ...overrides,
  }
}

function attachment(overrides: Partial<Attachment> = {}): Attachment {
  return {
    id: 'att-1',
    messageId: null,
    kind: 'image',
    mimeType: 'image/png',
    name: 'pixel.png',
    size: 5,
    sha256: 'a'.repeat(64),
    storageKey: `attachments/${'a'.repeat(64)}`,
    width: 1,
    height: 1,
    createdAt: now,
    ...overrides,
  }
}

function snapshot(overrides: Partial<ExportSnapshot> = {}): ExportSnapshot {
  return {
    conversations: [conversation()],
    messages: [],
    attachments: [attachment()],
    providers: [],
    models: [],
    settings: structuredClone(DEFAULT_SETTINGS),
    ...overrides,
  }
}

describe('manifest', () => {
  it('builds a schema-shaped manifest without secrets', () => {
    const manifest = buildManifest(snapshot(), {
      deviceId: 'device-a',
      createdAt: now,
      updatedAt: now,
      tombstones: [
        {
          id: 'tombstone-1',
          conversationId: 'conv-deleted',
          deletedAt: now,
          deviceId: 'device-a',
        },
      ],
    })

    expect(manifest).toMatchObject({
      formatVersion: MANIFEST_FORMAT_VERSION,
      deviceId: 'device-a',
      conversations: [
        {
          id: 'conv-1',
          storageKey: '0123456789abcdef0123456789abcdef',
          revision: 1,
          objectKey: 'conversations/0123456789abcdef0123456789abcdef.enc',
        },
      ],
      attachments: [
        {
          sha256: 'a'.repeat(64),
          objectKey: `attachments/${'a'.repeat(64)}.enc`,
        },
      ],
      tombstones: [{ conversationId: 'conv-deleted', deletedAt: now, deviceId: 'device-a' }],
    })

    const serialized = JSON.stringify(manifest)
    expect(serialized).not.toContain('secret')
    expect(serialized).not.toContain('apiKey')
  })

  it('round-trips through parseManifest', () => {
    const manifest = buildManifest(snapshot(), { deviceId: 'device-a', createdAt: now })
    const parsed = parseManifest(new TextEncoder().encode(JSON.stringify(manifest)))

    expect(parsed).toEqual(manifest)
  })

  it('rejects unknown format versions', () => {
    const manifest = buildManifest(snapshot(), { deviceId: 'device-a', createdAt: now })

    expect(() => parseManifest(JSON.stringify({ ...manifest, formatVersion: 2 }))).toThrowError(
      expect.objectContaining({ code: 'unsupported-version' }),
    )
  })

  it('rejects malformed manifests', () => {
    const manifest = buildManifest(snapshot(), { deviceId: 'device-a', createdAt: now })

    expect(() =>
      parseManifest(
        JSON.stringify({
          ...manifest,
          conversations: [{ ...manifest.conversations[0], storageKey: 'not-hex' }],
        }),
      ),
    ).toThrowError(expect.objectContaining({ code: 'invalid-manifest' }))
  })

  it('merges indexes by revision and applies tombstones', () => {
    const local = buildManifest(snapshot(), { deviceId: 'device-a', createdAt: now })
    const remote = buildManifest(
      snapshot({
        conversations: [
          conversation({ revision: 3, updatedAt: '2026-01-02T00:00:00.000Z' }),
          conversation({
            id: 'conv-deleted',
            storageKey: 'fedcba9876543210fedcba9876543210',
            updatedAt: '2026-01-01T00:00:00.000Z',
          }),
        ],
        attachments: [attachment({ sha256: 'b'.repeat(64) })],
      }),
      {
        deviceId: 'device-b',
        createdAt: now,
        tombstones: [
          {
            id: 'tombstone-1',
            conversationId: 'conv-deleted',
            deletedAt: '2026-01-02T00:00:00.000Z',
            deviceId: 'device-b',
          },
        ],
      },
    )

    const merged = mergeManifestIndexes(local, remote)

    expect(merged.conversations).toHaveLength(1)
    expect(merged.conversations[0]).toMatchObject({ id: 'conv-1', revision: 3 })
    expect(merged.attachments.map((entry) => entry.sha256)).toEqual([
      'a'.repeat(64),
      'b'.repeat(64),
    ])
    expect(merged.tombstones).toHaveLength(1)
  })

  it('exposes a typed error class', () => {
    expect(new ManifestError('invalid-manifest', 'bad')).toBeInstanceOf(Error)
  })
})

describe('manifest type guard', () => {
  it('accepts a parsed manifest body', () => {
    const manifest: ManifestBody = buildManifest(snapshot(), {
      deviceId: 'device-a',
      createdAt: now,
    })
    expect(manifest.formatVersion).toBe(1)
  })
})
