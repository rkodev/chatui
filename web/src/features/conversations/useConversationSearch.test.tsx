import { act, renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { ConversationSearchResult } from '../../storage'
import { createTestConversation } from '../providers/testSupport'
import { createFakeConversationHistoryService } from './testSupport'
import { useConversationSearch } from './useConversationSearch'

const conversation = createTestConversation({
  id: 'a',
  title: 'Conversation A',
  lastMessageAt: '2026-11-03T10:00:00.000Z',
  updatedAt: '2026-11-03T10:00:00.000Z',
})

function result(query: string, snippet = 'alpha context'): ConversationSearchResult {
  return {
    query,
    groups: [
      {
        conversation,
        titleMatch: false,
        messageMatches: [
          {
            messageId: `message-${query}`,
            sequence: 1,
            role: 'user',
            snippet,
          },
        ],
      },
    ],
    truncated: false,
    scannedConversations: 1,
    scannedMessages: 1,
  }
}

function renderSearch(
  query: string,
  service: ReturnType<typeof createFakeConversationHistoryService>,
  debounceMs = 0,
) {
  return renderHook(
    ({ currentQuery }) => useConversationSearch({ service, query: currentQuery, debounceMs }),
    { initialProps: { currentQuery: query } },
  )
}

describe('useConversationSearch', () => {
  it('stays idle for an empty query without touching storage', () => {
    const searchConversations = vi.fn()
    const service = createFakeConversationHistoryService({ searchConversations })
    const { result: state } = renderSearch('   ', service)

    expect(state.current.status).toBe('idle')
    expect(state.current.groups).toEqual([])
    expect(searchConversations).not.toHaveBeenCalled()
  })

  it('debounces rapid query changes to the latest query only', async () => {
    const searchConversations = vi.fn(async (query: string) => result(query))
    const service = createFakeConversationHistoryService({ searchConversations })
    const { result: state, rerender } = renderSearch('a', service, 30)

    expect(state.current.status).toBe('loading')

    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 10))
    })
    rerender({ currentQuery: 'ab' })

    await waitFor(() => expect(searchConversations).toHaveBeenCalledTimes(1))
    expect(searchConversations).toHaveBeenCalledWith('ab', {})
  })

  it('ignores a stale response after a newer query starts', async () => {
    let resolveFirst: ((value: ConversationSearchResult) => void) | null = null
    const first = new Promise<ConversationSearchResult>((resolve) => {
      resolveFirst = resolve
    })
    const searchConversations = vi.fn((query: string) =>
      query === 'first' ? first : Promise.resolve(result('second', 'second context')),
    )
    const service = createFakeConversationHistoryService({ searchConversations })
    const { result: state, rerender } = renderSearch('first', service)

    await waitFor(() => expect(searchConversations).toHaveBeenCalledWith('first', {}))

    rerender({ currentQuery: 'second' })

    await waitFor(() => expect(state.current.status).toBe('ready'))
    expect(state.current.query).toBe('second')
    expect(state.current.groups[0].messageMatches[0].snippet).toBe('second context')

    await act(async () => {
      resolveFirst?.(result('first', 'first context'))
      await first
    })

    expect(state.current.query).toBe('second')
    expect(state.current.groups[0].messageMatches[0].snippet).toBe('second context')
  })

  it('returns a ready state with grouped results', async () => {
    const service = createFakeConversationHistoryService({
      searchConversations: async (query: string) => result(query),
    })
    const { result: state } = renderSearch('alpha', service)

    await waitFor(() => expect(state.current.status).toBe('ready'))
    expect(state.current.query).toBe('alpha')
    expect(state.current.groups).toHaveLength(1)
    expect(state.current.groups[0].conversation.id).toBe('a')
  })

  it('returns an error state when the search rejects', async () => {
    const service = createFakeConversationHistoryService({
      searchConversations: async () => {
        throw new Error('Storage unavailable')
      },
    })
    const { result: state } = renderSearch('alpha', service)

    await waitFor(() => expect(state.current.status).toBe('error'))
    expect(state.current.error).toBe('Storage unavailable')
    expect(state.current.groups).toEqual([])
  })

  it('clears back to idle when the query is emptied', async () => {
    const service = createFakeConversationHistoryService({
      searchConversations: async (query: string) => result(query),
    })
    const { result: state, rerender } = renderSearch('alpha', service)

    await waitFor(() => expect(state.current.status).toBe('ready'))

    rerender({ currentQuery: '' })

    expect(state.current.status).toBe('idle')
    expect(state.current.groups).toEqual([])
  })
})
