import { nowIso, type ChatError, type Message, type MessageStatus } from '../../domain'

/**
 * Explanation stored on a message that was left streaming when the app closed.
 *
 * The partial content is never removed. The message status becomes `cancelled`
 * and this note is attached through the normalized error field so the chat
 * screen can explain why the response stopped.
 */
export const INTERRUPTION_NOTE =
  'The app closed before this response finished. Partial content is preserved.'

/** Minimal message persistence surface used by startup recovery. */
export interface RecoveryMessageStore {
  listByStatus(
    status: MessageStatus,
    options?: { conversationIds?: readonly string[] },
  ): Promise<Message[]>
  appendMessage(message: Message): Promise<unknown>
}

export interface RecoveryServiceOptions {
  messages: RecoveryMessageStore
  nowIso?: () => string
  /** Optional bound for the scan. Omit to recover every conversation. */
  conversationIds?: readonly string[]
}

export interface RecoveryFailure {
  message: Message
  error: unknown
}

export interface RecoveryResult {
  recovered: Message[]
  failed: RecoveryFailure[]
}

/** Narrow surface used by the startup provider and tests. */
export interface RecoveryServiceLike {
  recoverInterruptedMessages(): Promise<RecoveryResult>
}

function interruptionError(message: Message, at: string): ChatError {
  return {
    code: 'cancelled',
    message: INTERRUPTION_NOTE,
    providerId: message.providerId,
    status: null,
    retryable: false,
    retryAfterSeconds: null,
    requestId: null,
    details: null,
    at,
  }
}

/**
 * Mark messages left in `streaming` as cancelled on startup.
 *
 * The scan is intentionally a single pass over the messages store because the
 * v1 schema has no status index. Each recovered message keeps its id, sequence,
 * parts, and timestamps; only the terminal status, finish reason, error note,
 * and `updatedAt` change. Re-running recovery is safe because the second pass
 * finds no streaming messages.
 */
export class RecoveryService {
  private readonly messages: RecoveryMessageStore
  private readonly nowIso: () => string
  private readonly conversationIds: readonly string[] | undefined

  constructor(options: RecoveryServiceOptions) {
    this.messages = options.messages
    this.nowIso = options.nowIso ?? nowIso
    this.conversationIds = options.conversationIds
  }

  async recoverInterruptedMessages(): Promise<RecoveryResult> {
    const streaming = await this.messages.listByStatus(
      'streaming',
      this.conversationIds === undefined ? {} : { conversationIds: this.conversationIds },
    )
    const recovered: Message[] = []
    const failed: RecoveryFailure[] = []

    for (const message of streaming) {
      const at = this.nowIso()
      const updated: Message = {
        ...message,
        status: 'cancelled',
        finishReason: 'cancelled',
        error: interruptionError(message, at),
        updatedAt: at,
      }

      try {
        await this.messages.appendMessage(updated)
        recovered.push(updated)
      } catch (error) {
        failed.push({ message, error })
      }
    }

    return { recovered, failed }
  }
}
