/**
 * Deletion tombstone from spec section 6.4 and the M5 sync protocol.
 *
 * A tombstone records that a conversation (or one message) was deleted on a
 * specific device. It is retained until a remote manifest confirms the
 * deletion, so a later sync cannot resurrect deleted data.
 */
export interface Tombstone {
  /** Local tombstone identity. */
  id: string
  conversationId: string
  /** Set when only one message was deleted. */
  messageId?: string
  /** ISO 8601 UTC deletion timestamp. */
  deletedAt: string
  /** Device that performed the deletion. */
  deviceId: string
}
