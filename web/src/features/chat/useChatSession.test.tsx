import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  createTestConversation,
  createTestModel,
  createTestProvider,
} from '../providers/testSupport'
import { ChatServiceContext } from './chatServiceContext'
import type { ChatServiceLike } from './chatService'
import { createStreamingState, useStreamingStore } from './streamingStore'
import { createFakeChatService, createTestAttachment, createTestMessage } from './testSupport'
import { useChatSession } from './useChatSession'

function wrapperFor(service: ChatServiceLike) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <ChatServiceContext.Provider value={service}>{children}</ChatServiceContext.Provider>
  }
}

afterEach(() => {
  useStreamingStore.getState().clearAll()
})

describe('useChatSession', () => {
  it('loads the conversation, newest messages, availability, and usage setting', async () => {
    const conversation = createTestConversation()
    const model = createTestModel()
    const provider = createTestProvider()
    const message = createTestMessage()
    const service = createFakeChatService({
      getConversation: async () => conversation,
      listMessages: async () => ({ items: [message], nextCursor: null }),
      getAvailability: async () => ({ ok: true, model, provider }),
      getShowTokenUsage: async () => false,
      getSendKey: async () => 'mod-enter',
      getLoadRemoteImages: async () => true,
    })

    const { result } = renderHook(() => useChatSession(conversation.id), {
      wrapper: wrapperFor(service),
    })

    await waitFor(() => expect(result.current.status).toBe('ready'))

    expect(result.current.conversation).toEqual(conversation)
    expect(result.current.messages).toEqual([message])
    expect(result.current.availability).toEqual({ ok: true, model, provider })
    expect(result.current.showTokenUsage).toBe(false)
    expect(result.current.sendKey).toBe('mod-enter')
    expect(result.current.loadRemoteImages).toBe(true)
  })

  it('announces start, complete, and error but not token updates', async () => {
    const conversation = createTestConversation()
    const service = createFakeChatService({
      getConversation: async () => conversation,
      listMessages: async () => ({ items: [], nextCursor: null }),
    })

    const { result } = renderHook(() => useChatSession(conversation.id), {
      wrapper: wrapperFor(service),
    })

    await waitFor(() => expect(result.current.status).toBe('ready'))

    const initial = {
      ...createStreamingState({
        conversationId: conversation.id,
        messageId: 'stream-1',
        startedAt: 1,
      }),
      started: true,
      text: 'Hello',
    }

    act(() => {
      useStreamingStore.getState().setStream(initial)
    })

    expect(result.current.announcement).toBe('Response started')

    act(() => {
      useStreamingStore.getState().setStream({ ...initial, text: 'Hello world' })
    })

    expect(result.current.announcement).toBe('Response started')

    act(() => {
      useStreamingStore.getState().setStream({
        ...initial,
        text: 'Hello world',
        status: 'complete',
        done: true,
      })
    })

    expect(result.current.announcement).toBe('Response complete')

    act(() => {
      useStreamingStore.getState().setStream({
        ...initial,
        status: 'error',
        done: true,
        error: {
          code: 'network',
          message: 'network failed',
          providerId: 'provider-1',
          status: null,
          retryable: true,
          retryAfterSeconds: null,
          requestId: null,
          details: null,
          at: '2026-01-01T00:00:00.000Z',
        },
      })
    })

    expect(result.current.announcement).toContain('Response failed.')
    expect(result.current.announcement).toContain('could not reach the provider')
  })

  it('sends an optimistic user message through the service', async () => {
    const conversation = createTestConversation()
    const send = vi.fn(async () => ({
      ok: true as const,
      message: createTestMessage({ id: 'assistant-1', sequence: 2, role: 'assistant' }),
    }))
    const service = createFakeChatService({
      getConversation: async () => conversation,
      listMessages: async () => ({ items: [], nextCursor: null }),
      send,
    })

    const { result } = renderHook(() => useChatSession(conversation.id), {
      wrapper: wrapperFor(service),
    })

    await waitFor(() => expect(result.current.status).toBe('ready'))

    await act(async () => {
      await result.current.send('Hello')
    })

    expect(send).toHaveBeenCalledWith(
      conversation,
      expect.objectContaining({
        role: 'user',
        parts: [{ type: 'text', text: 'Hello' }],
      }),
    )
  })

  it('adds attachments and includes them in the optimistic user message', async () => {
    const conversation = createTestConversation()
    const attachment = createTestAttachment({
      kind: 'file',
      name: 'notes.txt',
      mimeType: 'text/plain',
    })
    const addAttachments = vi.fn(async () => ({ attachments: [attachment], errors: [] }))
    const send = vi.fn(async () => ({
      ok: true as const,
      message: createTestMessage({ id: 'assistant-1', sequence: 2, role: 'assistant' }),
    }))
    const service = createFakeChatService({
      getConversation: async () => conversation,
      listMessages: async () => ({ items: [], nextCursor: null }),
      addAttachments,
      send,
    })
    const { result } = renderHook(() => useChatSession(conversation.id), {
      wrapper: wrapperFor(service),
    })

    await waitFor(() => expect(result.current.status).toBe('ready'))

    await act(async () => {
      await result.current.addAttachments([
        new File(['notes'], 'notes.txt', { type: 'text/plain' }),
      ])
    })

    expect(result.current.attachments).toEqual([attachment])

    await act(async () => {
      await result.current.send('Read this', [attachment])
    })

    expect(send).toHaveBeenCalledWith(
      conversation,
      expect.objectContaining({
        parts: [
          { type: 'text', text: 'Read this' },
          {
            type: 'file',
            attachmentId: attachment.id,
            mimeType: 'text/plain',
            name: 'notes.txt',
          },
        ],
      }),
    )
    expect(result.current.attachments).toEqual([])
  })

  it('surfaces a send failure', async () => {
    const conversation = createTestConversation()
    const service = createFakeChatService({
      getConversation: async () => conversation,
      listMessages: async () => ({ items: [], nextCursor: null }),
      send: async () => ({
        ok: false,
        reason: 'no_key',
        message: 'The API key for this provider is unavailable.',
      }),
    })

    const { result } = renderHook(() => useChatSession(conversation.id), {
      wrapper: wrapperFor(service),
    })

    await waitFor(() => expect(result.current.status).toBe('ready'))

    await act(async () => {
      await result.current.send('Hello')
    })

    expect(result.current.error).toBe('The API key for this provider is unavailable.')
  })

  it('clears a stale completed stream when no run is active', async () => {
    const conversation = createTestConversation()
    const service = createFakeChatService({
      getConversation: async () => conversation,
      listMessages: async () => ({ items: [], nextCursor: null }),
      isStreaming: () => false,
    })

    act(() => {
      useStreamingStore.getState().setStream({
        ...createStreamingState({
          conversationId: conversation.id,
          messageId: 'stream-1',
          startedAt: 1,
        }),
        status: 'complete',
        done: true,
      })
    })

    const { result } = renderHook(() => useChatSession(conversation.id), {
      wrapper: wrapperFor(service),
    })

    await waitFor(() => expect(result.current.status).toBe('ready'))

    expect(useStreamingStore.getState().streams[conversation.id]).toBeUndefined()
  })
})
