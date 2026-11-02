import { act, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createTestConversation } from '../providers/testSupport'
import { MessageList } from './MessageList'
import { createStreamingState, useStreamingStore } from './streamingStore'
import { createTestMessage } from './testSupport'

const conversation = createTestConversation()

function renderList(overrides: Partial<Parameters<typeof MessageList>[0]> = {}) {
  return render(
    <MessageList
      conversation={conversation}
      messages={[]}
      streamingMessageId={null}
      isStreaming={false}
      nextCursor={null}
      loadingOlder={false}
      onLoadOlder={() => undefined}
      showTokenUsage={false}
      {...overrides}
    />,
  )
}

afterEach(() => {
  useStreamingStore.getState().clearAll()
})

describe('MessageList', () => {
  it('renders history in sequence order with stable keys', () => {
    const first = createTestMessage({
      id: 'm1',
      sequence: 1,
      role: 'user',
      parts: [{ type: 'text', text: 'First' }],
    })
    const second = createTestMessage({
      id: 'm2',
      sequence: 2,
      role: 'assistant',
      parts: [{ type: 'text', text: 'Second' }],
    })

    renderList({ messages: [first, second] })

    const articles = screen.getAllByRole('article')
    expect(articles).toHaveLength(2)
    expect(articles[0]).toHaveTextContent('First')
    expect(articles[1]).toHaveTextContent('Second')
    expect(articles[0]).toHaveAttribute('data-role', 'user')
    expect(articles[1]).toHaveAttribute('data-role', 'assistant')
  })

  it('loads older messages from the top control', async () => {
    const user = userEvent.setup()
    const onLoadOlder = vi.fn()

    renderList({ nextCursor: 1, onLoadOlder })

    await user.click(screen.getByRole('button', { name: 'Load older messages' }))

    expect(onLoadOlder).toHaveBeenCalledTimes(1)
  })

  it('renders the isolated streaming row and updates it without replacing history', () => {
    const history = createTestMessage({
      id: 'm1',
      sequence: 1,
      role: 'user',
      parts: [{ type: 'text', text: 'History' }],
    })
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

    renderList({
      messages: [history],
      streamingMessageId: 'stream-1',
      isStreaming: true,
    })

    expect(screen.getByText('History')).toBeInTheDocument()
    expect(screen.getByText('Hello')).toBeInTheDocument()

    act(() => {
      useStreamingStore.getState().setStream({ ...initial, text: 'Hello world' })
    })

    expect(screen.getByText('Hello world')).toBeInTheDocument()
    expect(screen.getByText('History')).toBeInTheDocument()
  })

  it('hides the streaming row once the persisted message is present', () => {
    const persisted = createTestMessage({
      id: 'stream-1',
      sequence: 2,
      role: 'assistant',
      parts: [{ type: 'text', text: 'Persisted answer' }],
    })
    const done = {
      ...createStreamingState({
        conversationId: conversation.id,
        messageId: 'stream-1',
        startedAt: 1,
      }),
      started: true,
      text: 'Persisted answer',
      status: 'complete' as const,
      done: true,
    }

    act(() => {
      useStreamingStore.getState().setStream(done)
    })

    renderList({
      messages: [persisted],
      streamingMessageId: 'stream-1',
      isStreaming: false,
    })

    expect(screen.getAllByText('Persisted answer')).toHaveLength(1)
  })

  it('auto-scrolls only when the user is near the bottom', () => {
    const first = createTestMessage({
      id: 'm1',
      sequence: 1,
      role: 'user',
      parts: [{ type: 'text', text: 'First' }],
    })
    const second = createTestMessage({
      id: 'm2',
      sequence: 2,
      role: 'assistant',
      parts: [{ type: 'text', text: 'Second' }],
    })
    const { rerender } = renderList({ messages: [first] })
    const list = screen.getByRole('log')

    Object.defineProperty(list, 'clientHeight', { configurable: true, value: 100 })
    Object.defineProperty(list, 'scrollHeight', { configurable: true, value: 500 })

    list.scrollTop = 0
    fireEvent.scroll(list)
    rerender(
      <MessageList
        conversation={conversation}
        messages={[first, second]}
        streamingMessageId={null}
        isStreaming={false}
        nextCursor={null}
        loadingOlder={false}
        onLoadOlder={() => undefined}
        showTokenUsage={false}
      />,
    )
    expect(list.scrollTop).toBe(0)

    list.scrollTop = 450
    fireEvent.scroll(list)
    rerender(
      <MessageList
        conversation={conversation}
        messages={[first, second]}
        streamingMessageId={null}
        isStreaming={false}
        nextCursor={null}
        loadingOlder={false}
        onLoadOlder={() => undefined}
        showTokenUsage={false}
      />,
    )
    expect(list.scrollTop).toBe(500)
  })
})
