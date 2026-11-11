import { z } from 'zod'
import type {
  Attachment,
  ChatParams,
  Conversation,
  Message,
  Model,
  Provider,
  Settings,
} from '../../domain'
import { bytesToBase64 } from '../../platform/crypto'
import type { ExportSnapshot } from '../../storage'

export const EXPORT_FORMAT = 'chatui.export'
export const EXPORT_FORMAT_VERSION = 1
export const EXPORT_APP_VERSION = '0.1.0'
export const EXPORT_PLAINTEXT_WARNING =
  'Exports contain plaintext conversations, prompts, and attachment metadata. Anyone with the file can read them. Store exports somewhere you trust.'

export interface ExportAttachment {
  id: string
  name: string
  mimeType: string
  sha256: string
  size: number
  dataBase64?: string
}

export interface ChatExport {
  format: typeof EXPORT_FORMAT
  formatVersion: number
  exportedAt: string
  appVersion: string
  conversations: Conversation[]
  messages: Message[]
  attachments: ExportAttachment[]
  providers: Provider[]
  models: Model[]
  settings: Settings
}

export interface ExportOptions {
  /** Include attachment bytes as base64. Defaults to false. */
  includeAttachmentBytes?: boolean
  /** Deterministic timestamp for tests. */
  exportedAt?: string
  /** Application version written to the archive. */
  appVersion?: string
}

export interface ConversationExportOptions extends ExportOptions {
  format?: 'json' | 'markdown'
}

export interface ExportArtifact {
  filename: string
  mimeType: string
  content: string
}

export interface AttachmentByteReader {
  get(storageKey: string): Promise<Uint8Array>
}

export interface ExportReaderLike {
  listConversations(): Promise<Conversation[]>
  readAll(): Promise<ExportSnapshot>
  readConversation(conversationId: string): Promise<ExportSnapshot | null>
}

export interface ExportServiceOptions {
  reader: ExportReaderLike
  attachmentBytes?: AttachmentByteReader
  now?: () => string
  appVersion?: string
}

export interface ExportServiceLike {
  listConversations(): Promise<Conversation[]>
  exportAll(options?: ExportOptions): Promise<ExportArtifact>
  exportConversation(
    conversationId: string,
    options?: ConversationExportOptions,
  ): Promise<ExportArtifact>
}

const isoDate = z.string().refine((value) => Number.isFinite(Date.parse(value)), {
  message: 'Expected an ISO date-time string',
})

const chatParamsSchema = z
  .object({
    temperature: z.number().min(0).max(2).nullable(),
    topP: z.number().min(0).max(1).nullable(),
    maxOutputTokens: z.number().int().min(1).nullable(),
    stop: z.array(z.string()),
    reasoningEffort: z.enum(['low', 'medium', 'high']).nullable(),
    streaming: z.boolean(),
    extra: z.record(z.string(), z.unknown()),
  })
  .strict()

const usageSchema = z
  .object({
    inputTokens: z.number().int().min(0).nullable(),
    outputTokens: z.number().int().min(0).nullable(),
    totalTokens: z.number().int().min(0).nullable(),
    costUsd: z.number().min(0).nullable(),
    estimated: z.boolean(),
  })
  .strict()

const chatErrorSchema = z
  .object({
    code: z.enum([
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
    ]),
    message: z.string(),
    providerId: z.string().nullable(),
    status: z.number().int().nullable(),
    retryable: z.boolean(),
    retryAfterSeconds: z.number().min(0).nullable(),
    requestId: z.string().nullable(),
    details: z.record(z.string(), z.unknown()).nullable(),
    at: isoDate,
  })
  .strict()

const contentPartSchema = z.union([
  z.object({ type: z.literal('text'), text: z.string() }).strict(),
  z
    .object({
      type: z.literal('image'),
      attachmentId: z.string().min(1),
      mimeType: z.string().min(1),
      alt: z.string(),
    })
    .strict(),
  z
    .object({
      type: z.literal('file'),
      attachmentId: z.string().min(1),
      mimeType: z.string().min(1),
      name: z.string().min(1),
    })
    .strict(),
  z.object({ type: z.literal('reasoning'), text: z.string() }).strict(),
  z
    .object({
      type: z.literal('tool-call'),
      id: z.string().min(1),
      name: z.string().min(1),
      argumentsJson: z.string(),
    })
    .strict(),
  z
    .object({
      type: z.literal('tool-result'),
      toolCallId: z.string().min(1),
      content: z.string(),
    })
    .strict(),
])

const syncSchema = z.record(z.string(), z.unknown()).nullable()

const conversationSchema = z
  .object({
    id: z.string().min(1),
    title: z.string(),
    systemPrompt: z.string(),
    providerId: z.string().min(1),
    modelId: z.string().min(1),
    modelLabel: z.string(),
    params: chatParamsSchema,
    pinned: z.boolean(),
    archived: z.boolean(),
    temporary: z.boolean(),
    createdAt: isoDate,
    updatedAt: isoDate,
    lastMessageAt: isoDate,
    messageCount: z.number().int().min(0),
    lastError: chatErrorSchema.nullable(),
    sync: syncSchema,
  })
  .strict()

const messageSchema = z
  .object({
    id: z.string().min(1),
    conversationId: z.string().min(1),
    sequence: z.number().int().min(0),
    role: z.enum(['system', 'user', 'assistant', 'tool']),
    parts: z.array(contentPartSchema),
    status: z.enum(['pending', 'streaming', 'complete', 'cancelled', 'error']),
    providerId: z.string().nullable(),
    modelId: z.string().nullable(),
    modelLabel: z.string().nullable(),
    usage: usageSchema.nullable(),
    finishReason: z
      .enum(['stop', 'length', 'content_filter', 'tool_calls', 'cancelled', 'error', 'unknown'])
      .nullable(),
    error: chatErrorSchema.nullable(),
    latencyMs: z.number().int().min(0).nullable(),
    firstTokenMs: z.number().int().min(0).nullable(),
    createdAt: isoDate,
    updatedAt: isoDate,
    deletedAt: isoDate.nullable(),
  })
  .strict()

const exportAttachmentSchema = z
  .object({
    id: z.string().min(1),
    name: z.string().min(1),
    mimeType: z.string().min(1),
    sha256: z.string().min(1),
    size: z.number().int().min(0),
    dataBase64: z.string().optional(),
  })
  .strict()

const providerSchema = z
  .object({
    id: z.string().min(1),
    presetId: z.string().min(1),
    label: z.string(),
    kind: z.enum(['openai-compatible', 'anthropic', 'google-gemini']),
    baseUrl: z.string().min(1),
    authType: z.enum(['bearer', 'x-api-key', 'query-key', 'none']),
    secretId: z.string().nullable(),
    headers: z.record(z.string(), z.string()),
    enabled: z.boolean(),
    browserCors: z.enum(['supported', 'unsupported', 'unknown']),
    metadata: z.record(z.string(), z.unknown()).nullable(),
    createdAt: isoDate,
    updatedAt: isoDate,
  })
  .strict()

const modelSchema = z
  .object({
    id: z.string().min(1),
    providerId: z.string().min(1),
    remoteId: z.string().min(1),
    label: z.string().min(1),
    contextWindow: z.number().int().min(1).nullable(),
    maxOutputTokens: z.number().int().min(1).nullable(),
    capabilities: z.array(z.enum(['text', 'vision', 'files', 'tools', 'reasoning', 'streaming'])),
    pricing: z
      .object({
        inputPerMTok: z.number().min(0).optional(),
        outputPerMTok: z.number().min(0).optional(),
        perRequest: z.number().min(0).optional(),
        currency: z.string().min(1).optional(),
      })
      .strict()
      .nullable(),
    canonicalSlug: z.string().nullable(),
    inputModalities: z.array(z.enum(['text', 'image', 'file', 'audio', 'video'])),
    outputModalities: z.array(z.enum(['text', 'image', 'audio'])),
    supportedParameters: z.array(z.string()),
    metadata: z.record(z.string(), z.unknown()).nullable(),
    hidden: z.boolean(),
    pinned: z.boolean(),
    source: z.enum(['discovered', 'preset', 'manual']),
    createdAt: isoDate,
    updatedAt: isoDate,
  })
  .strict()

const backupConfigSchema = z
  .object({
    enabled: z.boolean(),
    backend: z.enum(['s3', 'azure-blob', 'gcs', 'webdav']),
    endpoint: z.string().nullable(),
    region: z.string().nullable(),
    bucket: z.string(),
    prefix: z.string(),
    credentialSecretId: z.string().nullable(),
    encryption: z.literal('aes-256-gcm'),
    kdf: z.enum(['pbkdf2-sha256', 'argon2id']),
    kdfSalt: z.string().nullable(),
    lastSyncAt: isoDate.nullable(),
    lastManifestEtag: z.string().nullable(),
    deviceId: z.string().min(1),
  })
  .strict()

const settingsSchema = z
  .object({
    theme: z.enum(['system', 'light', 'dark']),
    density: z.enum(['comfortable', 'compact']),
    chatFontSize: z.enum(['sm', 'md', 'lg']),
    sendKey: z.enum(['enter', 'mod-enter']),
    defaultProviderId: z.string().nullable(),
    defaultModelId: z.string().nullable(),
    defaultSystemPrompt: z.string(),
    defaultParams: chatParamsSchema,
    rememberKeysOnWeb: z.boolean(),
    showTokenUsage: z.boolean(),
    loadRemoteImages: z.boolean(),
    lastActiveConversationId: z.string().nullable(),
    backup: backupConfigSchema.nullable(),
    onboardingCompleted: z.boolean(),
  })
  .strict()

/** Runtime mirror of `contracts/schema/chat-export.schema.json`. */
export const chatExportSchema = z
  .object({
    format: z.literal(EXPORT_FORMAT),
    formatVersion: z.number().int().min(1),
    exportedAt: isoDate,
    appVersion: z.string().regex(/^[0-9]+\.[0-9]+\.[0-9]+([+-].*)?$/),
    conversations: z.array(conversationSchema),
    messages: z.array(messageSchema),
    attachments: z.array(exportAttachmentSchema),
    providers: z.array(providerSchema),
    models: z.array(modelSchema),
    settings: settingsSchema,
  })
  .strict()

export function validateChatExport(value: unknown): ChatExport {
  return chatExportSchema.parse(value) as ChatExport
}

function cloneParams(params: ChatParams): ChatParams {
  return {
    ...params,
    stop: [...params.stop],
    extra: structuredClone(params.extra),
  }
}

function cloneConversation(conversation: Conversation): Conversation {
  const clone: Conversation = { ...conversation, params: cloneParams(conversation.params) }
  // Sync-only fields are not part of the export contract.
  delete (clone as Partial<Conversation>).storageKey
  delete (clone as Partial<Conversation>).revision
  return clone
}

function cloneMessage(message: Message): Message {
  return { ...message, parts: structuredClone(message.parts) }
}

function toExportAttachment(
  attachment: Attachment,
  includeBytes: boolean,
  bytes: AttachmentByteReader | undefined,
): Promise<ExportAttachment> {
  const exported: ExportAttachment = {
    id: attachment.id,
    name: attachment.name,
    mimeType: attachment.mimeType,
    sha256: attachment.sha256,
    size: attachment.size,
  }

  if (!includeBytes) {
    return Promise.resolve(exported)
  }

  if (!bytes) {
    return Promise.reject(
      new Error('Attachment bytes were requested but no attachment reader is available.'),
    )
  }

  return bytes.get(attachment.storageKey).then((data) => ({
    ...exported,
    dataBase64: bytesToBase64(data),
  }))
}

export async function buildChatExport(
  snapshot: ExportSnapshot,
  options: ExportOptions = {},
  bytes?: AttachmentByteReader,
): Promise<ChatExport> {
  const includeBytes = options.includeAttachmentBytes ?? false
  const archivedAt = options.exportedAt ?? new Date().toISOString()

  const archive: ChatExport = {
    format: EXPORT_FORMAT,
    formatVersion: EXPORT_FORMAT_VERSION,
    exportedAt: archivedAt,
    appVersion: options.appVersion ?? EXPORT_APP_VERSION,
    conversations: snapshot.conversations.map(cloneConversation),
    messages: snapshot.messages.map(cloneMessage),
    attachments: await Promise.all(
      snapshot.attachments.map((attachment) => toExportAttachment(attachment, includeBytes, bytes)),
    ),
    providers: snapshot.providers.map((provider) => ({ ...provider })),
    models: snapshot.models.map((model) => ({
      ...model,
      capabilities: [...model.capabilities],
      inputModalities: [...model.inputModalities],
      outputModalities: [...model.outputModalities],
      supportedParameters: [...model.supportedParameters],
      pricing: model.pricing ? { ...model.pricing } : null,
    })),
    settings: {
      ...snapshot.settings,
      defaultParams: cloneParams(snapshot.settings.defaultParams),
    },
  }

  return validateChatExport(archive)
}

function slugify(value: string): string {
  const slug = value
    .normalize('NFKD')
    .replace(/[^a-zA-Z0-9]+/gu, '-')
    .replace(/^-+|-+$/gu, '')
    .toLowerCase()
    .slice(0, 48)

  return slug === '' ? 'conversation' : slug
}

function timestampForFilename(iso: string): string {
  return iso.replace(/[:.]/gu, '-')
}

export function exportFilename(exportedAt: string): string {
  return `chatui-export-${timestampForFilename(exportedAt)}.json`
}

export function conversationFilename(
  conversation: Conversation,
  format: 'json' | 'markdown',
): string {
  const extension = format === 'json' ? 'json' : 'md'
  return `chatui-${slugify(conversation.title)}-${conversation.id}.${extension}`
}

function attachmentFor(snapshot: ExportSnapshot, attachmentId: string): Attachment | undefined {
  return snapshot.attachments.find((attachment) => attachment.id === attachmentId)
}

function renderPart(snapshot: ExportSnapshot, part: Message['parts'][number]): string {
  switch (part.type) {
    case 'text':
      return part.text
    case 'reasoning':
      return `_Reasoning:_\n\n${part.text}`
    case 'tool-call':
      return `_Tool call_ \`${part.name}\` (${part.id}):\n\n\`\`\`json\n${part.argumentsJson}\n\`\`\``
    case 'tool-result':
      return `_Tool result_ (${part.toolCallId}):\n\n${part.content}`
    case 'image':
    case 'file': {
      const attachment = attachmentFor(snapshot, part.attachmentId)
      const name = attachment?.name ?? (part.type === 'file' ? part.name : part.attachmentId)
      const size = attachment ? `, ${attachment.size} bytes` : ''
      const label = part.type === 'image' ? 'Image' : 'File'
      const alt = part.type === 'image' && part.alt ? ` — ${part.alt}` : ''
      return `_[${label}: ${name} (${part.mimeType}${size}), attachment ${part.attachmentId}]_${alt}`
    }
  }
}

/**
 * Human-readable per-conversation export.
 *
 * The message order follows `sequence`, and every content part keeps its
 * original wording. Attachment references stay inline so a reader can match
 * them to the attachment array emitted by the JSON export.
 */
export function formatConversationMarkdown(snapshot: ExportSnapshot): string {
  const conversation = snapshot.conversations[0]

  if (!conversation) {
    throw new Error('A conversation snapshot is required for Markdown export.')
  }

  const lines: string[] = [
    `# ${conversation.title}`,
    '',
    `- Conversation ID: \`${conversation.id}\``,
    `- Provider: ${conversation.providerId}`,
    `- Model: ${conversation.modelLabel} (\`${conversation.modelId}\`)`,
    `- Created: ${conversation.createdAt}`,
    `- Updated: ${conversation.updatedAt}`,
    `- Last message: ${conversation.lastMessageAt}`,
    `- Messages: ${conversation.messageCount}`,
    `- Pinned: ${conversation.pinned ? 'yes' : 'no'}`,
    `- Archived: ${conversation.archived ? 'yes' : 'no'}`,
    `- Temporary: ${conversation.temporary ? 'yes' : 'no'}`,
    '',
  ]

  if (conversation.systemPrompt.trim() !== '') {
    lines.push('## System prompt', '', conversation.systemPrompt, '')
  }

  lines.push('## Messages', '')

  for (const message of snapshot.messages) {
    const snapshotLabel =
      message.modelLabel === null
        ? ''
        : ` — ${message.modelLabel}${message.modelId === null ? '' : ` (\`${message.modelId}\`)`}`
    lines.push(
      `### ${message.sequence + 1}. ${message.role} — ${message.createdAt}${snapshotLabel}`,
      '',
    )

    for (const part of message.parts) {
      lines.push(renderPart(snapshot, part), '')
    }

    if (message.usage) {
      lines.push(
        `_Usage: ${message.usage.inputTokens ?? '?'} in / ${message.usage.outputTokens ?? '?'} out / ${message.usage.totalTokens ?? '?'} total${message.usage.estimated ? ' (estimated)' : ''}_`,
        '',
      )
    }

    if (message.error) {
      lines.push(`_Error (${message.error.code}): ${message.error.message}_`, '')
    }
  }

  return `${lines.join('\n').trimEnd()}\n`
}

/**
 * Build per-conversation and full `chatui.export` archives.
 *
 * The service formats data only; it does not write files. UI callers pass the
 * returned artifact to `downloadTextFile`, which keeps download behavior easy
 * to test.
 */
export class ExportService implements ExportServiceLike {
  private readonly reader: ExportReaderLike
  private readonly attachmentBytes: AttachmentByteReader | undefined
  private readonly now: () => string
  private readonly appVersion: string

  constructor(options: ExportServiceOptions) {
    this.reader = options.reader
    this.attachmentBytes = options.attachmentBytes
    this.now = options.now ?? (() => new Date().toISOString())
    this.appVersion = options.appVersion ?? EXPORT_APP_VERSION
  }

  listConversations(): Promise<Conversation[]> {
    return this.reader.listConversations()
  }

  async exportAll(options: ExportOptions = {}): Promise<ExportArtifact> {
    const exportedAt = options.exportedAt ?? this.now()
    const snapshot = await this.reader.readAll()

    const archive = await buildChatExport(
      snapshot,
      { ...options, exportedAt, appVersion: options.appVersion ?? this.appVersion },
      this.attachmentBytes,
    )

    return {
      filename: exportFilename(exportedAt),
      mimeType: 'application/json',
      content: `${JSON.stringify(archive, null, 2)}\n`,
    }
  }

  async exportConversation(
    conversationId: string,
    options: ConversationExportOptions = {},
  ): Promise<ExportArtifact> {
    const snapshot = await this.reader.readConversation(conversationId)

    if (!snapshot) {
      throw new Error(`Conversation ${conversationId} was not found.`)
    }

    const conversation = snapshot.conversations[0]
    const format = options.format ?? 'json'
    const exportedAt = options.exportedAt ?? this.now()

    if (format === 'markdown') {
      return {
        filename: conversationFilename(conversation, 'markdown'),
        mimeType: 'text/markdown',
        content: formatConversationMarkdown(snapshot),
      }
    }

    const archive = await buildChatExport(
      snapshot,
      { ...options, exportedAt, appVersion: options.appVersion ?? this.appVersion },
      this.attachmentBytes,
    )

    return {
      filename: conversationFilename(conversation, 'json'),
      mimeType: 'application/json',
      content: `${JSON.stringify(archive, null, 2)}\n`,
    }
  }
}
