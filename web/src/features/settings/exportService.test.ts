import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import type { Conversation, Message, Model, Provider } from '../../domain'
import type { Attachment } from '../../domain'
import type { ExportSnapshot } from '../../storage'
import {
  EXPORT_PLAINTEXT_WARNING,
  ExportService,
  type AttachmentByteReader,
  type ExportReaderLike,
} from './exportService'
import { DEFAULT_SETTINGS } from './settingsService'

const testDir = dirname(fileURLToPath(import.meta.url))
const schema = JSON.parse(
  readFileSync(resolve(testDir, '../../../../contracts/schema/chat-export.schema.json'), 'utf8'),
) as Record<string, unknown>

type SchemaNode = Record<string, unknown>

function isType(value: unknown, type: string): boolean {
  switch (type) {
    case 'null':
      return value === null
    case 'string':
      return typeof value === 'string'
    case 'number':
      return typeof value === 'number' && Number.isFinite(value)
    case 'integer':
      return typeof value === 'number' && Number.isInteger(value)
    case 'boolean':
      return typeof value === 'boolean'
    case 'object':
      return typeof value === 'object' && value !== null && !Array.isArray(value)
    case 'array':
      return Array.isArray(value)
    default:
      return true
  }
}

function resolveRef(ref: string, root: SchemaNode): SchemaNode {
  if (!ref.startsWith('#/')) {
    throw new Error(`Unsupported schema ref ${ref}`)
  }

  let node: unknown = root
  for (const segment of ref.slice(2).split('/')) {
    node = (node as SchemaNode)[segment]
  }

  return node as SchemaNode
}

function validate(value: unknown, node: SchemaNode, root: SchemaNode, path = '$'): string[] {
  if (typeof node.$ref === 'string') {
    return validate(value, resolveRef(node.$ref, root), root, path)
  }

  if (Array.isArray(node.oneOf)) {
    const matches = (node.oneOf as SchemaNode[]).filter(
      (branch) => validate(value, branch, root, path).length === 0,
    ).length
    return matches === 1 ? [] : [`${path}: expected exactly one oneOf branch, got ${matches}`]
  }

  if (Array.isArray(node.anyOf)) {
    const matches = (node.anyOf as SchemaNode[]).some(
      (branch) => validate(value, branch, root, path).length === 0,
    )
    return matches ? [] : [`${path}: no anyOf branch matched`]
  }

  if ('const' in node && value !== node.const) {
    return [`${path}: expected const ${String(node.const)}`]
  }

  if (Array.isArray(node.enum) && !node.enum.some((item) => item === value)) {
    return [`${path}: not one of enum values`]
  }

  if (node.type !== undefined) {
    const types = Array.isArray(node.type) ? (node.type as string[]) : [node.type as string]
    if (!types.some((type) => isType(value, type))) {
      return [`${path}: expected type ${types.join('|')}`]
    }
  }

  if (value === null || value === undefined) {
    return []
  }

  const errors: string[] = []

  if (typeof value === 'string') {
    if (typeof node.minLength === 'number' && value.length < node.minLength) {
      errors.push(`${path}: shorter than minLength`)
    }
    if (typeof node.pattern === 'string' && !new RegExp(node.pattern).test(value)) {
      errors.push(`${path}: does not match pattern`)
    }
    if (node.format === 'date-time' && !Number.isFinite(Date.parse(value))) {
      errors.push(`${path}: invalid date-time`)
    }
  }

  if (typeof value === 'number') {
    if (typeof node.minimum === 'number' && value < node.minimum) {
      errors.push(`${path}: below minimum`)
    }
    if (typeof node.maximum === 'number' && value > node.maximum) {
      errors.push(`${path}: above maximum`)
    }
  }

  if (Array.isArray(value)) {
    if (node.items !== undefined) {
      value.forEach((item, index) => {
        errors.push(...validate(item, node.items as SchemaNode, root, `${path}/${index}`))
      })
    }
    if (node.uniqueItems === true) {
      const encoded = value.map((item) => JSON.stringify(item))
      if (new Set(encoded).size !== encoded.length) {
        errors.push(`${path}: duplicate array items`)
      }
    }
  }

  if (typeof value === 'object' && !Array.isArray(value)) {
    const object = value as Record<string, unknown>
    const properties = (node.properties ?? {}) as Record<string, SchemaNode>

    for (const required of (node.required ?? []) as string[]) {
      if (!(required in object)) {
        errors.push(`${path}: missing required property ${required}`)
      }
    }

    for (const [key, child] of Object.entries(object)) {
      if (properties[key] !== undefined) {
        errors.push(...validate(child, properties[key], root, `${path}/${key}`))
      } else if (node.additionalProperties === false) {
        errors.push(`${path}: unexpected property ${key}`)
      } else if (typeof node.additionalProperties === 'object') {
        errors.push(
          ...validate(child, node.additionalProperties as SchemaNode, root, `${path}/${key}`),
        )
      }
    }
  }

  return errors
}

const now = '2026-11-04T12:00:00.000Z'

const provider: Provider = {
  id: 'provider-openai',
  presetId: 'openai',
  label: 'OpenAI',
  kind: 'openai-compatible',
  baseUrl: 'https://api.openai.com/v1',
  authType: 'bearer',
  secretId: 'secret-1',
  headers: {},
  enabled: true,
  browserCors: 'unknown',
  metadata: null,
  createdAt: now,
  updatedAt: now,
}

const model: Model = {
  id: 'model-1',
  providerId: provider.id,
  remoteId: 'gpt-4o-mini',
  label: 'GPT-4o mini',
  contextWindow: null,
  maxOutputTokens: null,
  capabilities: ['text', 'streaming'],
  pricing: null,
  canonicalSlug: null,
  inputModalities: ['text'],
  outputModalities: ['text'],
  supportedParameters: ['temperature'],
  metadata: null,
  hidden: false,
  pinned: false,
  source: 'discovered',
  createdAt: now,
  updatedAt: now,
}

const conversation: Conversation = {
  id: 'conv-1',
  title: 'Export me',
  systemPrompt: 'Be concise.',
  providerId: provider.id,
  modelId: model.id,
  modelLabel: model.label,
  params: {
    temperature: null,
    topP: null,
    maxOutputTokens: 256,
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
  messageCount: 2,
  lastError: null,
  storageKey: '0123456789abcdef0123456789abcdef',
  revision: 1,
  sync: null,
}

function message(sequence: number, overrides: Partial<Message> = {}): Message {
  return {
    id: `msg-${sequence}`,
    conversationId: conversation.id,
    sequence,
    role: sequence === 0 ? 'user' : 'assistant',
    parts:
      sequence === 0
        ? [
            { type: 'text', text: 'Describe this.' },
            {
              type: 'image',
              attachmentId: 'att-image',
              mimeType: 'image/png',
              alt: 'A pixel',
            },
          ]
        : [{ type: 'text', text: 'It is a pixel.' }],
    status: 'complete',
    providerId: sequence === 0 ? null : provider.id,
    modelId: sequence === 0 ? null : model.id,
    modelLabel: sequence === 0 ? null : model.label,
    usage: null,
    finishReason: 'stop',
    error: null,
    latencyMs: null,
    firstTokenMs: null,
    createdAt: now,
    updatedAt: now,
    deletedAt: null,
    ...overrides,
  }
}

const attachments: Attachment[] = [
  {
    id: 'att-image',
    messageId: 'msg-0',
    kind: 'image',
    mimeType: 'image/png',
    name: 'pixel.png',
    size: 5,
    sha256: 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
    storageKey: 'attachments/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
    width: 1,
    height: 1,
    createdAt: now,
  },
]

function snapshot(): ExportSnapshot {
  return {
    conversations: [structuredClone(conversation)],
    messages: [message(0), message(1)],
    attachments: structuredClone(attachments),
    providers: [structuredClone(provider)],
    models: [structuredClone(model)],
    settings: structuredClone(DEFAULT_SETTINGS),
  }
}

function fakeReader(data: ExportSnapshot = snapshot()): ExportReaderLike {
  return {
    listConversations: async () => structuredClone(data.conversations),
    readAll: async () => structuredClone(data),
    readConversation: async (id) =>
      data.conversations.some((item) => item.id === id) ? structuredClone(data) : null,
  }
}

function bytesReader(value = 'hello'): AttachmentByteReader {
  return {
    get: async () => new TextEncoder().encode(value),
  }
}

describe('export schema validation helper', () => {
  it('loads the contract schema and finds the export fixture valid', () => {
    const fixture = JSON.parse(
      readFileSync(
        resolve(testDir, '../../../../contracts/fixtures/expected/chatui-export.json'),
        'utf8',
      ),
    )
    expect(validate(fixture, schema, schema)).toEqual([])
  })
})

describe('ExportService', () => {
  it('produces an archive that validates against the contract schema', async () => {
    const service = new ExportService({
      reader: fakeReader(),
      now: () => now,
      appVersion: '0.1.0',
    })

    const artifact = await service.exportAll()
    const parsed = JSON.parse(artifact.content)

    expect(artifact.filename).toBe('chatui-export-2026-11-04T12-00-00-000Z.json')
    expect(parsed.format).toBe('chatui.export')
    expect(validate(parsed, schema, schema)).toEqual([])
  })

  it('excludes attachment bytes unless requested', async () => {
    const service = new ExportService({
      reader: fakeReader(),
      attachmentBytes: bytesReader(),
      now: () => now,
    })

    const without = JSON.parse((await service.exportAll()).content)
    expect(without.attachments[0]).not.toHaveProperty('dataBase64')

    const withBytes = JSON.parse(
      (await service.exportAll({ includeAttachmentBytes: true })).content,
    )
    expect(withBytes.attachments[0].dataBase64).toBe('aGVsbG8=')
    expect(validate(withBytes, schema, schema)).toEqual([])
  })

  it('preserves IDs, message order, snapshots, and attachment references', async () => {
    const service = new ExportService({ reader: fakeReader(), now: () => now })
    const artifact = await service.exportConversation('conv-1')

    const parsed = JSON.parse(artifact.content)
    expect(parsed.conversations.map((item: Conversation) => item.id)).toEqual(['conv-1'])
    expect(parsed.messages.map((item: Message) => item.id)).toEqual(['msg-0', 'msg-1'])
    expect(parsed.messages.map((item: Message) => item.sequence)).toEqual([0, 1])
    expect(parsed.conversations[0].modelLabel).toBe('GPT-4o mini')
    expect(parsed.messages[1].modelId).toBe('model-1')
    expect(parsed.messages[0].parts[1].attachmentId).toBe('att-image')
    expect(parsed.attachments.map((item: { id: string }) => item.id)).toEqual(['att-image'])
    expect(validate(parsed, schema, schema)).toEqual([])
  })

  it('formats Markdown in sequence order with attachment references', async () => {
    const service = new ExportService({ reader: fakeReader(), now: () => now })
    const artifact = await service.exportConversation('conv-1', { format: 'markdown' })

    expect(artifact.mimeType).toBe('text/markdown')
    expect(artifact.filename).toBe('chatui-export-me-conv-1.md')
    expect(artifact.content).toContain('# Export me')
    expect(artifact.content).toContain('## System prompt\n\nBe concise.')
    expect(artifact.content.indexOf('Describe this.')).toBeLessThan(
      artifact.content.indexOf('It is a pixel.'),
    )
    expect(artifact.content).toContain('pixel.png (image/png, 5 bytes)')
    expect(artifact.content).toContain('attachment att-image')
    expect(artifact.content).toContain('GPT-4o mini (`model-1`)')
    expect(artifact.content).not.toContain('aGVsbG8=')
  })

  it('round-trips through JSON without losing data', async () => {
    const service = new ExportService({ reader: fakeReader(), now: () => now })
    const artifact = await service.exportAll()
    const parsed = JSON.parse(artifact.content)

    expect(JSON.parse(JSON.stringify(parsed))).toEqual(parsed)
    expect(JSON.stringify(parsed)).not.toContain('secret-1-ciphertext')
    expect(validate(parsed, schema, schema)).toEqual([])
  })

  it('rejects an unknown conversation', async () => {
    const service = new ExportService({ reader: fakeReader(), now: () => now })
    await expect(service.exportConversation('missing')).rejects.toThrow('was not found')
  })

  it('requires a byte reader only when attachment bytes are requested', async () => {
    const service = new ExportService({ reader: fakeReader(), now: () => now })
    await expect(service.exportAll({ includeAttachmentBytes: true })).rejects.toThrow(
      'no attachment reader',
    )
  })

  it('states the plaintext warning', () => {
    expect(EXPORT_PLAINTEXT_WARNING).toMatch(/plaintext/i)
  })
})
