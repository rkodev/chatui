import { z } from 'zod'
import type { Attachment, ChatParams, Conversation, Message, Usage } from '../../domain'

export interface ConversationPayloadAttachment {
  id: string
  sha256: string
  mimeType: string
  name: string
  size: number
  storageKey: string
}

export interface ConversationPayloadMessage {
  id: string
  sequence: number
  role: Message['role']
  parts: Message['parts']
  status: Message['status']
  usage: Usage | null
  createdAt: string
  updatedAt: string
  deletedAt: string | null
}

export interface ConversationPayload {
  id: string
  title: string
  systemPrompt: string
  providerId: string
  modelId: string
  modelLabel: string
  params: ChatParams
  pinned: boolean
  archived: boolean
  temporary: boolean
  createdAt: string
  updatedAt: string
  lastMessageAt: string
  revision: number
  storageKey: string
  messages: ConversationPayloadMessage[]
  attachments: ConversationPayloadAttachment[]
}

export type ConversationPayloadErrorCode = 'invalid-payload'

export class ConversationPayloadError extends Error {
  readonly code: ConversationPayloadErrorCode

  constructor(code: ConversationPayloadErrorCode, message: string) {
    super(message)
    this.name = 'ConversationPayloadError'
    this.code = code
  }
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

const messageSchema = z
  .object({
    id: z.string().min(1),
    sequence: z.number().int().min(0),
    role: z.enum(['system', 'user', 'assistant', 'tool']),
    parts: z.array(contentPartSchema),
    status: z.enum(['pending', 'streaming', 'complete', 'cancelled', 'error']),
    usage: usageSchema.nullable(),
    createdAt: isoDate,
    updatedAt: isoDate,
    deletedAt: isoDate.nullable(),
  })
  .strict()

const attachmentReferenceSchema = z
  .object({
    id: z.string().min(1),
    sha256: z.string().regex(/^[0-9a-f]{64}$/u),
    mimeType: z.string().min(1),
    name: z.string().min(1),
    size: z.number().int().min(0),
    storageKey: z.string().regex(/^[0-9a-f]{64}$/u),
  })
  .strict()

const conversationPayloadSchema = z
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
    revision: z.number().int().min(1),
    storageKey: z.string().regex(/^[0-9a-f]{32}$/u),
    messages: z.array(messageSchema),
    attachments: z.array(attachmentReferenceSchema),
  })
  .strict()

function cloneParams(params: ChatParams): ChatParams {
  return {
    ...params,
    stop: [...params.stop],
    extra: structuredClone(params.extra),
  }
}

function cloneUsage(usage: Usage | null): Usage | null {
  return usage ? { ...usage } : null
}

function toPayloadMessage(message: Message): ConversationPayloadMessage {
  return {
    id: message.id,
    sequence: message.sequence,
    role: message.role,
    parts: structuredClone(message.parts),
    status: message.status,
    usage: cloneUsage(message.usage),
    createdAt: message.createdAt,
    updatedAt: message.updatedAt,
    deletedAt: message.deletedAt,
  }
}

function toPayloadAttachment(attachment: Attachment): ConversationPayloadAttachment {
  return {
    id: attachment.id,
    sha256: attachment.sha256,
    mimeType: attachment.mimeType,
    name: attachment.name,
    size: attachment.size,
    storageKey: attachment.sha256,
  }
}

/**
 * Build a self-contained conversation payload.
 *
 * The payload contains conversation metadata, every message, and attachment
 * references only. Provider credentials, secret IDs, and attachment bytes are
 * deliberately excluded.
 */
export function buildConversationPayload(
  conversation: Conversation,
  messages: readonly Message[],
  attachments: readonly Attachment[],
): ConversationPayload {
  if (!/^[0-9a-f]{32}$/u.test(conversation.storageKey)) {
    throw new Error(`Conversation ${conversation.id} has an invalid storageKey`)
  }
  if (!Number.isInteger(conversation.revision) || conversation.revision < 1) {
    throw new Error(`Conversation ${conversation.id} has an invalid revision`)
  }

  return {
    id: conversation.id,
    title: conversation.title,
    systemPrompt: conversation.systemPrompt,
    providerId: conversation.providerId,
    modelId: conversation.modelId,
    modelLabel: conversation.modelLabel,
    params: cloneParams(conversation.params),
    pinned: conversation.pinned,
    archived: conversation.archived,
    temporary: conversation.temporary,
    createdAt: conversation.createdAt,
    updatedAt: conversation.updatedAt,
    lastMessageAt: conversation.lastMessageAt,
    revision: conversation.revision,
    storageKey: conversation.storageKey,
    messages: [...messages]
      .sort((left, right) => left.sequence - right.sequence || left.id.localeCompare(right.id))
      .map(toPayloadMessage),
    attachments: [...attachments]
      .sort((left, right) => left.id.localeCompare(right.id))
      .map(toPayloadAttachment),
  }
}

/**
 * Parse and validate a conversation payload.
 */
export function parseConversationPayload(bytes: Uint8Array | string): ConversationPayload {
  let text: string
  if (typeof bytes === 'string') {
    text = bytes
  } else {
    try {
      text = new TextDecoder('utf-8', { fatal: true }).decode(bytes)
    } catch {
      throw new ConversationPayloadError('invalid-payload', 'Payload is not valid UTF-8')
    }
  }

  let value: unknown
  try {
    value = JSON.parse(text)
  } catch {
    throw new ConversationPayloadError('invalid-payload', 'Payload is not valid JSON')
  }

  const parsed = conversationPayloadSchema.safeParse(value)
  if (!parsed.success) {
    throw new ConversationPayloadError('invalid-payload', parsed.error.message)
  }

  return parsed.data
}
