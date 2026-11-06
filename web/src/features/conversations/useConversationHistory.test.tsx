import { act, renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { createTestConversation } from '../providers/testSupport'
import { createFakeConversationHistoryService } from './testSupport'
import {
  DEFAULT_UNDO_WINDOW_MS,
  sortConversations,
  useConversationHistory,
} from './useConversationHistory'

const conversationA = createTestConversation({
  id: 'a',
  title: 'Conversation A',
  lastMessageAt: '2026-11-03T10:00:00.000Z',
  updatedAt: '2026-11-03T10:00:00.000Z',
})
const conversationB = createTestConversation({
  id: 'b',
  title: 'Conversation B',
  lastMessageAt: '2026-11-02T10:00:00.000Z',
  updatedAt: '2026-11-02T10:00:00.000Z',
})

function renderHistory(
  service: ReturnType<typeof createFakeConversationHistoryService>,
  options: Partial<Parameters<typeof useConversationHistory>[0]> = {},
) {
  return renderHook(() => useConversationHistory({ service, ...options }))
}

describe('useConversationHistory', () => {
  it('loads conversations newest-first on mount', async () => {
    const service = createFakeConversationHistoryService({
      listConversations: async () => ({ items: [conversationB, conversationA], nextCursor: null }),
    })
    const { result } = renderHistory(service)

    await waitFor(() => expect(result.current.status).toBe('ready'))

    expect(result.current.conversations.map((item) => item.id)).toEqual(['a', 'b'])
  })

  it('creates a new chat and records it as last active', async () => {
    const created = createTestConversation({ id: 'new', title: 'New chat' })
    const createConversation = vi.fn(async () => ({ ok: true as const, conversation: created }))
    const setLastActiveConversation = vi.fn(async () => undefined)
    const service = createFakeConversationHistoryService({
      listConversations: async () => ({ items: [], nextCursor: null }),
      createConversation,
      setLastActiveConversation,
    })
    const { result } = renderHistory(service)

    await waitFor(() => expect(result.current.status).toBe('ready'))

    let returned: unknown
    await act(async () => {
      returned = await result.current.createNewChat()
    })

    expect(returned).toEqual(created)
    expect(createConversation).toHaveBeenCalledTimes(1)
    expect(setLastActiveConversation).toHaveBeenCalledWith('new')
    expect(result.current.conversations.map((item) => item.id)).toEqual(['new'])
  })

  it('creates a fresh conversation even when another conversation is active', async () => {
    const created = createTestConversation({ id: 'fresh', title: 'Fresh chat' })
    const createConversation = vi.fn(async () => ({
      ok: true as const,
      conversation: created,
    }))
    const service = createFakeConversationHistoryService({
      listConversations: async () => ({ items: [conversationA], nextCursor: null }),
      createConversation,
    })
    const { result } = renderHistory(service, { activeConversationId: 'a' })

    await waitFor(() => expect(result.current.status).toBe('ready'))

    await act(async () => {
      await result.current.createNewChat()
    })

    expect(createConversation).toHaveBeenCalledTimes(1)
    expect(result.current.conversations.map((item) => item.id)).toContain('fresh')
  })

  it('reports a not-configured new chat without adding a conversation', async () => {
    const service = createFakeConversationHistoryService({
      listConversations: async () => ({ items: [], nextCursor: null }),
      createConversation: async () => ({
        ok: false,
        reason: 'not_configured',
        message: 'No provider is configured.',
      }),
    })
    const { result } = renderHistory(service)

    await waitFor(() => expect(result.current.status).toBe('ready'))

    let returned: unknown
    await act(async () => {
      returned = await result.current.createNewChat()
    })

    expect(returned).toBeNull()
    expect(result.current.error).toBe('No provider is configured.')
    expect(result.current.conversations).toEqual([])
  })

  it('buffers delete for the undo window and commits it afterwards', async () => {
    const deleteConversation = vi.fn(async () => undefined)
    const service = createFakeConversationHistoryService({
      listConversations: async () => ({ items: [conversationA], nextCursor: null }),
      deleteConversation,
    })
    const { result } = renderHistory(service, { undoWindowMs: 30 })

    await waitFor(() => expect(result.current.status).toBe('ready'))

    act(() => {
      result.current.confirmDelete(conversationA)
    })

    expect(result.current.conversations).toEqual([])
    expect(result.current.pendingDelete?.id).toBe('a')
    expect(deleteConversation).not.toHaveBeenCalled()

    await waitFor(() => expect(deleteConversation).toHaveBeenCalledWith('a'))
    expect(result.current.pendingDelete).toBeNull()
  })

  it('undo restores the conversation without deleting it', async () => {
    const deleteConversation = vi.fn(async () => undefined)
    const service = createFakeConversationHistoryService({
      listConversations: async () => ({ items: [conversationA], nextCursor: null }),
      deleteConversation,
    })
    const { result } = renderHistory(service, { undoWindowMs: 30 })

    await waitFor(() => expect(result.current.status).toBe('ready'))

    act(() => {
      result.current.confirmDelete(conversationA)
    })
    act(() => {
      result.current.undoDelete()
    })

    expect(result.current.conversations.map((item) => item.id)).toEqual(['a'])
    expect(result.current.pendingDelete).toBeNull()

    await new Promise((resolve) => setTimeout(resolve, 60))
    expect(deleteConversation).not.toHaveBeenCalled()
  })

  it('clears and restores the last-active pointer when deleting the active conversation', async () => {
    const setLastActiveConversation = vi.fn(async () => undefined)
    const service = createFakeConversationHistoryService({
      listConversations: async () => ({ items: [conversationA], nextCursor: null }),
      setLastActiveConversation,
    })
    const { result } = renderHistory(service, {
      activeConversationId: 'a',
      undoWindowMs: 30,
    })

    await waitFor(() => expect(result.current.status).toBe('ready'))

    act(() => {
      result.current.confirmDelete(conversationA)
    })
    expect(setLastActiveConversation).toHaveBeenCalledWith(null)

    act(() => {
      result.current.undoDelete()
    })
    expect(setLastActiveConversation).toHaveBeenCalledWith('a')
  })

  it('calls onDeleteCommitted after the buffered delete commits', async () => {
    const onDeleteCommitted = vi.fn()
    const deleteConversation = vi.fn(async () => undefined)
    const service = createFakeConversationHistoryService({
      listConversations: async () => ({ items: [conversationA], nextCursor: null }),
      deleteConversation,
    })
    const { result } = renderHistory(service, {
      undoWindowMs: 30,
      onDeleteCommitted,
    })

    await waitFor(() => expect(result.current.status).toBe('ready'))

    act(() => {
      result.current.confirmDelete(conversationA)
    })

    await waitFor(() => expect(onDeleteCommitted).toHaveBeenCalledWith(conversationA))
  })

  it('commits a pending delete when the hook unmounts', async () => {
    const deleteConversation = vi.fn(async () => undefined)
    const service = createFakeConversationHistoryService({
      listConversations: async () => ({ items: [conversationA], nextCursor: null }),
      deleteConversation,
    })
    const { result, unmount } = renderHistory(service, { undoWindowMs: 1000 })

    await waitFor(() => expect(result.current.status).toBe('ready'))

    act(() => {
      result.current.confirmDelete(conversationA)
    })
    unmount()

    await waitFor(() => expect(deleteConversation).toHaveBeenCalledWith('a'))
  })

  it('renames a conversation in place', async () => {
    const renameConversation = vi.fn(async (id: string, title: string) => ({
      ...conversationA,
      id,
      title,
    }))
    const service = createFakeConversationHistoryService({
      listConversations: async () => ({ items: [conversationA], nextCursor: null }),
      renameConversation,
    })
    const { result } = renderHistory(service)

    await waitFor(() => expect(result.current.status).toBe('ready'))

    await act(async () => {
      await result.current.rename('a', 'Renamed')
    })

    expect(result.current.conversations[0].title).toBe('Renamed')
  })

  it('sorts pinned conversations first across timestamps', () => {
    const pinnedOlder = { ...conversationB, pinned: true }

    expect(sortConversations([conversationA, pinnedOlder]).map((item) => item.id)).toEqual([
      'b',
      'a',
    ])
  })

  it('loads the active list with the archive filter and pinned-first order', async () => {
    const listConversations = vi.fn(async () => ({
      items: [conversationA, { ...conversationB, pinned: true }],
      nextCursor: null,
    }))
    const service = createFakeConversationHistoryService({ listConversations })
    const { result } = renderHistory(service)

    await waitFor(() => expect(result.current.status).toBe('ready'))

    expect(listConversations).toHaveBeenCalledWith({
      limit: 100,
      cursor: null,
      archived: false,
    })
    expect(result.current.conversations.map((item) => item.id)).toEqual(['b', 'a'])
  })

  it('loads the archive view with the archive filter', async () => {
    const archived = { ...conversationA, archived: true }
    const listConversations = vi.fn(async () => ({ items: [archived], nextCursor: null }))
    const service = createFakeConversationHistoryService({ listConversations })
    const { result } = renderHistory(service, { showArchived: true })

    await waitFor(() => expect(result.current.status).toBe('ready'))

    expect(listConversations).toHaveBeenCalledWith({
      limit: 100,
      cursor: null,
      archived: true,
    })
    expect(result.current.conversations.map((item) => item.id)).toEqual(['a'])
  })

  it('pins and unpins through the service and reorders immediately', async () => {
    const setConversationPinned = vi.fn(async (id: string, pinned: boolean) => ({
      ...conversationB,
      id,
      pinned,
    }))
    const service = createFakeConversationHistoryService({
      listConversations: async () => ({ items: [conversationA, conversationB], nextCursor: null }),
      setConversationPinned,
    })
    const { result } = renderHistory(service)

    await waitFor(() => expect(result.current.status).toBe('ready'))

    await act(async () => {
      await result.current.setPinned('b', true)
    })

    expect(setConversationPinned).toHaveBeenCalledWith('b', true)
    expect(result.current.conversations.map((item) => item.id)).toEqual(['b', 'a'])

    await act(async () => {
      await result.current.setPinned('b', false)
    })

    expect(result.current.conversations.map((item) => item.id)).toEqual(['a', 'b'])
  })

  it('removes an archived conversation from the active list', async () => {
    const setConversationArchived = vi.fn(async (id: string, archived: boolean) => ({
      ...conversationA,
      id,
      archived,
    }))
    const service = createFakeConversationHistoryService({
      listConversations: async () => ({ items: [conversationA, conversationB], nextCursor: null }),
      setConversationArchived,
    })
    const { result } = renderHistory(service)

    await waitFor(() => expect(result.current.status).toBe('ready'))

    await act(async () => {
      await result.current.setArchived('a', true)
    })

    expect(setConversationArchived).toHaveBeenCalledWith('a', true)
    expect(result.current.conversations.map((item) => item.id)).toEqual(['b'])
  })

  it('restores an unarchived conversation into the archive list', async () => {
    const archived = { ...conversationA, archived: true }
    const setConversationArchived = vi.fn(async (id: string, archivedFlag: boolean) => ({
      ...archived,
      id,
      archived: archivedFlag,
    }))
    const service = createFakeConversationHistoryService({
      listConversations: async () => ({ items: [archived], nextCursor: null }),
      setConversationArchived,
    })
    const { result } = renderHistory(service, { showArchived: true })

    await waitFor(() => expect(result.current.status).toBe('ready'))

    await act(async () => {
      await result.current.setArchived('a', false)
    })

    expect(result.current.conversations).toEqual([])
  })

  it('keeps the delete undo window at five seconds by default', () => {
    expect(DEFAULT_UNDO_WINDOW_MS).toBe(5000)
  })
})
