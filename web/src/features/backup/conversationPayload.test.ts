import { describe, expect, it } from 'vitest'
import type { Attachment, Conversation, Message } from '../../domain'
import {
  ConversationPayloadError,
  buildConversationPayload,
  parseConversationPayload,
} from './conversationPayload'

const now = '2026-01-01T00:00:00.000Z'

function conversation(overrides: Partial<Conversation> = {}): Conversation {
  return {
    id: 'conv-1',
    title: 'Conversation',
    systemPrompt: 'Be concise.',
    providerId: 'provider-1',
    modelId: 'model-1',
    modelLabel: 'Model',
    params: {
      temperature: 0.2,
      topP: null,
      maxOutputTokens: 256,
      stop: ['END'],
      reasoningEffort: null,
      streaming: true,
      extra: { openrouter: { provider: { sort: 'price' } } },
    },
    pinned: false,
    archived: false,
    temporary: false,
    createdAt: now,
    updatedAt: now,
    lastMessageAt: now,
    messageCount: 2,
    lastError: null,
    sync: null,
    storageKey: '0123456789abcdef0123456789abcdef',
    revision: 4,
    ...overrides,
  }
}

function message(sequence: number, overrides: Partial<Message> = {}): Message {
  return {
    id: `msg-${sequence}`,
    conversationId: 'conv-1',
    sequence,
    role: sequence === 0 ? 'user' : 'assistant',
    parts:
      sequence === 0
        ? [
            { type: 'text', text: 'Describe this.' },
            {
              type: 'image',
              attachmentId: 'att-1',
              mimeType: 'image/png',
              alt: 'A pixel',
            },
          ]
        : [{ type: 'text', text: 'It is a pixel.' }],
    status: 'complete',
    providerId: 'provider-1',
    modelId: 'model-1',
    modelLabel: 'Model',
    usage: {
      inputTokens: 10,
      outputTokens: 5,
      totalTokens: 15,
      costUsd: 0.001,
      estimated: false,
    },
    finishReason: 'stop',
    error: null,
    latencyMs: 100,
    firstTokenMs: 20,
    createdAt: now,
    updatedAt: now,
    deletedAt: null,
    ...overrides,
  }
}

function attachment(overrides: Partial<Attachment> = {}): Attachment {
  return {
    id: 'att-1',
    messageId: 'msg-0',
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

describe('conversation payload', () => {
  it('builds a self-contained payload without secrets or transport-only fields', () => {
    const payload = buildConversationPayload(
      conversation(),
      [message(1), message(0)],
      [attachment()],
    )

    expect(payload).toMatchObject({
      id: 'conv-1',
      revision: 4,
      storageKey: '0123456789abcdef0123456789abcdef',
      messages: [
        { id: 'msg-0', sequence: 0 },
        { id: 'msg-1', sequence: 1 },
      ],
      attachments: [
        {
          id: 'att-1',
          sha256: 'a'.repeat(64),
          storageKey: 'a'.repeat(64),
        },
      ],
    })

    const serialized = JSON.stringify(payload)
    expect(serialized).not.toContain('secret')
    expect(serialized).not.toContain('apiKey')
    expect(serialized).not.toContain('providerId":null')
  })

  it('round-trips through parseConversationPayload', () => {
    const payload = buildConversationPayload(conversation(), [message(0)], [attachment()])
    const parsed = parseConversationPayload(new TextEncoder().encode(JSON.stringify(payload)))

    expect(parsed).toEqual(payload)
  })

  it('rejects malformed payloads', () => {
    const payload = buildConversationPayload(conversation(), [message(0)], [attachment()])

    expect(() =>
      parseConversationPayload(
        JSON.stringify({
          ...payload,
          storageKey: 'not-hex',
        }),
      ),
    ).toThrowError(expect.objectContaining({ code: 'invalid-payload' }))
  })

  it('exposes a typed error class', () => {
    expect(new ConversationPayloadError('invalid-payload', 'bad')).toBeInstanceOf(Error)
  })
})
