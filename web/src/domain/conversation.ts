import type { ChatError } from './errors'

/**
 * Conversation entity and chat parameters from spec section 6.2.
 */
export type ReasoningEffort = 'low' | 'medium' | 'high'

export interface ChatParams {
  temperature: number | null
  topP: number | null
  maxOutputTokens: number | null
  stop: string[]
  reasoningEffort: ReasoningEffort | null
  streaming: boolean
  extra: Record<string, unknown>
}

/**
 * Local sync metadata.
 *
 * `state` tracks the local sync lifecycle, `lastSyncedAt` records the last
 * successful upload, and `remoteRevision` is an opaque remote identifier. The
 * durable merge ordering lives in `Conversation.revision`; this object is
 * bookkeeping only and never contains secrets.
 */
export interface SyncMeta {
  /** Local sync state for this conversation. */
  state: 'local' | 'pending' | 'synced' | 'conflict'
  /** Last successful sync timestamp, or null when never synced. */
  lastSyncedAt: string | null
  /** Opaque remote revision identifier, or null when never synced. */
  remoteRevision: string | null
}

export interface Conversation {
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
  messageCount: number
  lastError: ChatError | null
  sync: SyncMeta | null
  /**
   * Random 128-bit backup object key as 32 lowercase hex characters. It is
   * independent of `id` so remote object names do not leak conversation
   * identity or creation order.
   */
  storageKey: string
  /** Monotonic local revision, starting at 1 and incremented on every change. */
  revision: number
}
