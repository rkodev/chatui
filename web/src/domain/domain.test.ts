import { describe, expect, it } from 'vitest'
import {
  isIsoUtc,
  isUuidV7,
  newId,
  nowIso,
  type Attachment,
  type BackupConfig,
  type ChatError,
  type ChatErrorCode,
  type ChatParams,
  type ContentPart,
  type Conversation,
  type FinishReason,
  type Message,
  type MessageRole,
  type MessageStatus,
  type Model,
  type Provider,
  type Settings,
  type Usage,
} from './index'

const now = nowIso()

const provider: Provider = {
  id: newId(),
  presetId: 'openrouter',
  label: 'OpenRouter',
  kind: 'openai-compatible',
  baseUrl: 'https://openrouter.ai/api/v1',
  authType: 'bearer',
  secretId: null,
  headers: {},
  enabled: true,
  browserCors: 'unknown',
  metadata: null,
  createdAt: now,
  updatedAt: now,
}

const model: Model = {
  id: newId(),
  providerId: provider.id,
  remoteId: 'openrouter/auto',
  label: 'Auto',
  contextWindow: 128000,
  maxOutputTokens: 4096,
  capabilities: ['text', 'streaming'],
  pricing: { inputPerMTok: 1, outputPerMTok: 2, currency: 'USD' },
  canonicalSlug: 'openrouter/auto',
  inputModalities: ['text'],
  outputModalities: ['text'],
  supportedParameters: ['temperature'],
  metadata: null,
  hidden: false,
  pinned: false,
  source: 'preset',
  createdAt: now,
  updatedAt: now,
}

const params: ChatParams = {
  temperature: 0.7,
  topP: null,
  maxOutputTokens: null,
  stop: [],
  reasoningEffort: null,
  streaming: true,
  extra: {},
}

const parts: ContentPart[] = [
  { type: 'text', text: 'Hello' },
  { type: 'image', attachmentId: newId(), mimeType: 'image/png', alt: 'A diagram' },
  { type: 'file', attachmentId: newId(), mimeType: 'text/plain', name: 'notes.txt' },
  { type: 'reasoning', text: 'Thinking' },
  { type: 'tool-call', id: 'call-1', name: 'lookup', argumentsJson: '{}' },
  { type: 'tool-result', toolCallId: 'call-1', content: 'result' },
]

const usage: Usage = {
  inputTokens: 10,
  outputTokens: 20,
  totalTokens: 30,
  costUsd: null,
  estimated: false,
}

const chatError: ChatError = {
  code: 'network',
  message: 'Could not reach the provider.',
  providerId: provider.id,
  status: null,
  retryable: true,
  retryAfterSeconds: null,
  requestId: null,
  details: null,
  at: now,
}

const message: Message = {
  id: newId(),
  conversationId: newId(),
  sequence: 1,
  role: 'assistant',
  parts,
  status: 'complete',
  providerId: provider.id,
  modelId: model.id,
  modelLabel: model.label,
  usage,
  finishReason: 'stop',
  error: null,
  latencyMs: 1200,
  firstTokenMs: 300,
  createdAt: now,
  updatedAt: now,
  deletedAt: null,
}

const attachment: Attachment = {
  id: newId(),
  messageId: message.id,
  kind: 'file',
  mimeType: 'text/plain',
  name: 'notes.txt',
  size: 12,
  sha256: 'a'.repeat(64),
  storageKey: 'attachments/' + 'a'.repeat(64),
  width: null,
  height: null,
  createdAt: now,
}

const conversation: Conversation = {
  id: message.conversationId,
  title: 'Fixture conversation',
  systemPrompt: '',
  providerId: provider.id,
  modelId: model.id,
  modelLabel: model.label,
  params,
  pinned: false,
  archived: false,
  temporary: false,
  createdAt: now,
  updatedAt: now,
  lastMessageAt: now,
  messageCount: 1,
  lastError: chatError,
  storageKey: '0123456789abcdef0123456789abcdef',
  revision: 1,
  sync: null,
}

const backup: BackupConfig = {
  enabled: false,
  backend: 's3',
  endpoint: null,
  region: null,
  bucket: 'chatui-backups',
  prefix: 'devices/',
  credentialSecretId: null,
  encryption: 'aes-256-gcm',
  kdf: 'pbkdf2-sha256',
  kdfSalt: null,
  lastSyncAt: null,
  lastManifestEtag: null,
  deviceId: newId(),
}

const settings: Settings = {
  theme: 'system',
  density: 'comfortable',
  chatFontSize: 'md',
  sendKey: 'enter',
  defaultProviderId: provider.id,
  defaultModelId: model.id,
  defaultSystemPrompt: '',
  defaultParams: params,
  rememberKeysOnWeb: false,
  showTokenUsage: true,
  loadRemoteImages: false,
  lastActiveConversationId: conversation.id,
  backup,
  onboardingCompleted: false,
}

describe('domain fixtures', () => {
  it('covers every content part variant', () => {
    expect(parts.map((part) => part.type)).toEqual([
      'text',
      'image',
      'file',
      'reasoning',
      'tool-call',
      'tool-result',
    ])
  })

  it('covers every chat error code', () => {
    const codes: ChatErrorCode[] = [
      'auth',
      'permission',
      'rate_limit',
      'quota',
      'model_not_found',
      'context_length',
      'content_filter',
      'invalid_request',
      'network',
      'cors',
      'timeout',
      'cancelled',
      'provider',
      'parse',
      'storage',
      'unknown',
    ]

    expect(codes).toHaveLength(16)
  })

  it('covers message and finish reason unions', () => {
    const roles: MessageRole[] = ['system', 'user', 'assistant', 'tool']
    const statuses: MessageStatus[] = ['pending', 'streaming', 'complete', 'cancelled', 'error']
    const reasons: FinishReason[] = [
      'stop',
      'length',
      'content_filter',
      'tool_calls',
      'cancelled',
      'error',
      'unknown',
    ]

    expect([roles.length, statuses.length, reasons.length]).toEqual([4, 5, 7])
  })

  it('keeps entity IDs and timestamps valid', () => {
    expect(isUuidV7(provider.id)).toBe(true)
    expect(isUuidV7(model.id)).toBe(true)
    expect(isUuidV7(conversation.id)).toBe(true)
    expect(isUuidV7(message.id)).toBe(true)
    expect(isUuidV7(attachment.id)).toBe(true)
    expect(isIsoUtc(provider.createdAt)).toBe(true)
    expect(isIsoUtc(conversation.updatedAt)).toBe(true)
    expect(isIsoUtc(message.createdAt)).toBe(true)
    expect(isIsoUtc(chatError.at)).toBe(true)
  })

  it('links fixtures through the documented relationships', () => {
    expect(model.providerId).toBe(provider.id)
    expect(conversation.providerId).toBe(provider.id)
    expect(conversation.modelId).toBe(model.id)
    expect(message.conversationId).toBe(conversation.id)
    expect(message.parts).toBe(parts)
    expect(attachment.messageId).toBe(message.id)
    expect(settings.defaultParams).toBe(params)
    expect(settings.backup).toBe(backup)
    expect(conversation.sync).toBeNull()
    expect(usage.estimated).toBe(false)
  })
})
