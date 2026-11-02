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
 * Provisional sync metadata.
 *
 * The spec references `SyncMeta` and `syncState` without defining their final
 * shape. M0 only needs a documented placeholder so `Conversation.sync` has a
 * type; no sync behavior is implemented. M5 owns the final shape, including
 * conflict resolution, tombstones, and remote revision semantics.
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
}
