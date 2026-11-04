import type { Conversation } from '../../domain'

/**
 * Date buckets used by the conversation history sidebar.
 *
 * The order of `HISTORY_GROUP_ORDER` is the order the sidebar renders. Empty
 * buckets are omitted by `groupConversationsByDate`.
 */
export type HistoryGroupKey = 'today' | 'yesterday' | 'previous7Days' | 'previous30Days' | 'older'

export interface HistoryGroup {
  key: HistoryGroupKey
  label: string
  conversations: Conversation[]
}

export const HISTORY_GROUP_LABELS: Record<HistoryGroupKey, string> = {
  today: 'Today',
  yesterday: 'Yesterday',
  previous7Days: 'Previous 7 days',
  previous30Days: 'Previous 30 days',
  older: 'Older',
}

export const HISTORY_GROUP_ORDER: readonly HistoryGroupKey[] = [
  'today',
  'yesterday',
  'previous7Days',
  'previous30Days',
  'older',
]

const DAY_MS = 86_400_000

/**
 * Local calendar day as a UTC epoch value.
 *
 * Using UTC components for the local year/month/day avoids DST transitions
 * changing the difference between two calendar days by an hour.
 */
function localDayValue(date: Date): number {
  return Date.UTC(date.getFullYear(), date.getMonth(), date.getDate())
}

function parseDate(value: string): Date | null {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}

/**
 * Pick the timestamp that decides a conversation's history bucket.
 *
 * `lastMessageAt` is the primary value. A malformed value falls back to
 * `updatedAt`; if both are malformed the conversation is treated as older.
 */
export function conversationHistoryDate(conversation: Conversation): Date | null {
  return parseDate(conversation.lastMessageAt) ?? parseDate(conversation.updatedAt)
}

/**
 * Return the history bucket for one timestamp relative to `now`.
 *
 * Future timestamps are treated as Today so a clock skew cannot hide a
 * conversation from the sidebar.
 */
export function historyGroupForDate(value: string | Date, now: Date = new Date()): HistoryGroupKey {
  const date = value instanceof Date ? value : parseDate(value)

  if (date === null) {
    return 'older'
  }

  const dayDifference = Math.round((localDayValue(now) - localDayValue(date)) / DAY_MS)

  if (dayDifference <= 0) {
    return 'today'
  }
  if (dayDifference === 1) {
    return 'yesterday'
  }
  if (dayDifference <= 7) {
    return 'previous7Days'
  }
  if (dayDifference <= 30) {
    return 'previous30Days'
  }

  return 'older'
}

function timestampValue(conversation: Conversation): number {
  return conversationHistoryDate(conversation)?.getTime() ?? Number.NEGATIVE_INFINITY
}

/**
 * Group conversations into the required date buckets.
 *
 * Groups are returned in display order and empty groups are omitted. Within a
 * group, conversations are newest-first by `lastMessageAt` (falling back to
 * `updatedAt`). The input array is not mutated.
 */
export function groupConversationsByDate(
  conversations: readonly Conversation[],
  now: Date = new Date(),
): HistoryGroup[] {
  const buckets = new Map<HistoryGroupKey, Conversation[]>(
    HISTORY_GROUP_ORDER.map((key) => [key, []]),
  )

  for (const conversation of conversations) {
    const date = conversationHistoryDate(conversation)
    const key = date === null ? 'older' : historyGroupForDate(date, now)
    buckets.get(key)?.push(conversation)
  }

  return HISTORY_GROUP_ORDER.map((key) => ({
    key,
    label: HISTORY_GROUP_LABELS[key],
    conversations: [...(buckets.get(key) ?? [])].sort(
      (left, right) => timestampValue(right) - timestampValue(left),
    ),
  })).filter((group) => group.conversations.length > 0)
}
