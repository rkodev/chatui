import { act, renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { MessagePage } from '../../storage'
import { useConversationMessages } from './useConversationMessages'
import { createTestMessage } from './testSupport'

function page(items: ReturnType<typeof createTestMessage>[], nextCursor: number | null): MessagePage {
  return { items, nextCursor }
}

describe('useConversationMessages', () => {
  it('loads the newest page and exposes the older cursor', async () => {
    const newest = createTestMessage({ id: 'm2', sequence: 2 })
    const service = {
      listMessages: vi.fn(async () => page([newest], 2)),
    }

    const { result } = renderHook(() =>
      useConversationMessages({ conversationId: 'conversation-1', service }),
    )

    await waitFor(() => expect(result.current.status).toBe('ready'))
    expect(result.current.messages).toEqual([newest])
    expect(result.current.nextCursor).toBe(2)
  })

  it('prepends older messages without duplicating or reordering', async () => {
    const newest = createTestMessage({ id: 'm3', sequence: 3 })
    const older = createTestMessage({ id: 'm1', sequence: 1 })
    const duplicate = createTestMessage({ id: 'm3', sequence: 3 })
    const service = {
      listMessages: vi
        .fn()
        .mockResolvedValueOnce(page([newest], 3))
        .mockResolvedValueOnce(page([older, duplicate], null)),
    }

    const { result } = renderHook(() =>
      useConversationMessages({ conversationId: 'conversation-1', service }),
    )

    await waitFor(() => expect(result.current.status).toBe('ready'))
    await act(async () => {
      await result.current.loadOlder()
    })

    expect(result.current.messages.map((message) => message.id)).toEqual(['m1', 'm3'])
    expect(result.current.nextCursor).toBeNull()
    expect(service.listMessages).toHaveBeenLastCalledWith('conversation-1', {
      limit: 50,
      beforeSequence: 3,
    })
  })

  it('reports a load failure', async () => {
    const service = {
      listMessages: vi.fn(async () => {
        throw new Error('IndexedDB unavailable')
      }),
    }

    const { result } = renderHook(() =>
      useConversationMessages({ conversationId: 'conversation-1', service }),
    )

    await waitFor(() => expect(result.current.status).toBe('error'))
    expect(result.current.error).toBe('IndexedDB unavailable')
  })

  it('adds an optimistic message in sequence order', async () => {
    const service = {
      listMessages: vi.fn(async () => page([], null)),
    }

    const { result } = renderHook(() =>
      useConversationMessages({ conversationId: 'conversation-1', service }),
    )

    await waitFor(() => expect(result.current.status).toBe('ready'))

    act(() => {
      result.current.appendOptimistic(createTestMessage({ id: 'm1', sequence: 1 }))
    })

    expect(result.current.messages.map((message) => message.id)).toEqual(['m1'])
  })
})
