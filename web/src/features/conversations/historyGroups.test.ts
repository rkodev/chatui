import { describe, expect, it } from 'vitest'
import { createTestConversation } from '../providers/testSupport'
import {
  groupConversationsByDate,
  historyGroupForDate,
  HISTORY_GROUP_LABELS,
} from './historyGroups'

const now = new Date(2026, 10, 3, 12, 0, 0)

function at(daysAgo: number, hour = 9): string {
  return new Date(2026, 10, 3 - daysAgo, hour, 0, 0).toISOString()
}

function conversation(id: string, daysAgo: number, title = id) {
  return createTestConversation({
    id,
    title,
    lastMessageAt: at(daysAgo),
    updatedAt: at(daysAgo),
  })
}

describe('historyGroupForDate', () => {
  it.each([
    [0, 'today'],
    [1, 'yesterday'],
    [2, 'previous7Days'],
    [7, 'previous7Days'],
    [8, 'previous30Days'],
    [30, 'previous30Days'],
    [31, 'older'],
    [400, 'older'],
  ] as const)('maps %i days ago to %s', (daysAgo, expected) => {
    expect(historyGroupForDate(at(daysAgo), now)).toBe(expected)
  })

  it('treats future timestamps as today', () => {
    expect(historyGroupForDate(at(-2), now)).toBe('today')
  })

  it('treats an invalid timestamp as older', () => {
    expect(historyGroupForDate('not-a-date', now)).toBe('older')
  })
})

describe('groupConversationsByDate', () => {
  it('returns the required buckets in display order and omits empty groups', () => {
    const groups = groupConversationsByDate(
      [
        conversation('today-1', 0),
        conversation('yesterday-1', 1),
        conversation('week-1', 3),
        conversation('month-1', 10),
        conversation('older-1', 40),
      ],
      now,
    )

    expect(groups.map((group) => group.key)).toEqual([
      'today',
      'yesterday',
      'previous7Days',
      'previous30Days',
      'older',
    ])
    expect(groups.map((group) => group.label)).toEqual([
      HISTORY_GROUP_LABELS.today,
      HISTORY_GROUP_LABELS.yesterday,
      HISTORY_GROUP_LABELS.previous7Days,
      HISTORY_GROUP_LABELS.previous30Days,
      HISTORY_GROUP_LABELS.older,
    ])
  })

  it('omits buckets with no conversations', () => {
    const groups = groupConversationsByDate([conversation('today-1', 0)], now)

    expect(groups).toHaveLength(1)
    expect(groups[0].key).toBe('today')
  })

  it('sorts conversations newest-first inside a bucket', () => {
    const groups = groupConversationsByDate(
      [conversation('morning', 0, 'Morning'), conversation('evening', 0, 'Evening')].map(
        (item, index) => ({
          ...item,
          lastMessageAt: new Date(2026, 10, 3, index === 0 ? 8 : 20, 0, 0).toISOString(),
        }),
      ),
      now,
    )

    expect(groups[0].conversations.map((item) => item.id)).toEqual(['evening', 'morning'])
  })

  it('falls back to updatedAt when lastMessageAt is malformed', () => {
    const groups = groupConversationsByDate(
      [
        createTestConversation({
          id: 'fallback',
          lastMessageAt: 'not-a-date',
          updatedAt: at(1),
        }),
      ],
      now,
    )

    expect(groups[0].key).toBe('yesterday')
  })

  it('does not mutate the input array', () => {
    const input = [conversation('a', 0), conversation('b', 1)]
    const copy = [...input]

    groupConversationsByDate(input, now)

    expect(input).toEqual(copy)
  })
})
