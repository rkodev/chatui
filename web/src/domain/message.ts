import type { ChatError } from './errors'

/**
 * Message, content, attachment, and usage entities from spec section 6.2.
 */
export type MessageRole = 'system' | 'user' | 'assistant' | 'tool'

export type MessageStatus = 'pending' | 'streaming' | 'complete' | 'cancelled' | 'error'

export type FinishReason =
  'stop' | 'length' | 'content_filter' | 'tool_calls' | 'cancelled' | 'error' | 'unknown'

export type ContentPart =
  | { type: 'text'; text: string }
  | { type: 'image'; attachmentId: string; mimeType: string; alt: string }
  | { type: 'file'; attachmentId: string; mimeType: string; name: string }
  | { type: 'reasoning'; text: string }
  | { type: 'tool-call'; id: string; name: string; argumentsJson: string }
  | { type: 'tool-result'; toolCallId: string; content: string }

export interface Attachment {
  id: string
  messageId: string | null
  kind: 'image' | 'file'
  mimeType: string
  name: string
  size: number
  sha256: string
  storageKey: string
  width: number | null
  height: number | null
  createdAt: string
}

export interface Usage {
  inputTokens: number | null
  outputTokens: number | null
  totalTokens: number | null
  costUsd: number | null
  estimated: boolean
}

export interface Message {
  id: string
  conversationId: string
  sequence: number
  role: MessageRole
  parts: ContentPart[]
  status: MessageStatus
  providerId: string | null
  modelId: string | null
  modelLabel: string | null
  usage: Usage | null
  finishReason: FinishReason | null
  error: ChatError | null
  latencyMs: number | null
  firstTokenMs: number | null
  createdAt: string
  updatedAt: string
  deletedAt: string | null
}
