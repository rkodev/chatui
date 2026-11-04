import { act, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createTestConversation } from '../providers/testSupport'
import { MessageList } from './MessageList'
import { createStreamingState, useStreamingStore } from './streamingStore'
import { createTestMessage } from './testSupport'

const { renderCounts } = vi.hoisted(() => ({
  renderCounts: new Map<string, number>(),
}))

vi.mock('./MessageItem', () => ({
  MessageItem: ({ message }: { message: { id: string; parts: Array<{ text?: string }> } }) => {
    renderCounts.set(message.id, (renderCounts.get(message.id) ?? 0) + 1)
    return (
      <div data-testid={`message-${message.id}`}>
        {message.parts.map((part) => part.text ?? '').join('')}
      </div>
    )
  },
}))

const conversation = createTestConversation()

afterEach(() => {
  useStreamingStore.getState().clearAll()
  renderCounts.clear()
})

describe('MessageList streaming isolation', () => {
  it('does not re-render history when streaming text updates', () => {
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

    render(
      <MessageList
        conversation={conversation}
        messages={[history]}
        streamingMessageId="stream-1"
        isStreaming
        nextCursor={null}
        loadingOlder={false}
        onLoadOlder={() => undefined}
        showTokenUsage={false}
      />,
    )

    expect(screen.getByTestId('message-m1')).toHaveTextContent('History')
    expect(screen.getByTestId('message-stream-1')).toHaveTextContent('Hello')
    expect(renderCounts.get('m1')).toBe(1)
    expect(renderCounts.get('stream-1')).toBe(1)

    act(() => {
      useStreamingStore.getState().setStream({ ...initial, text: 'Hello world' })
    })

    expect(screen.getByTestId('message-stream-1')).toHaveTextContent('Hello world')
    expect(renderCounts.get('m1')).toBe(1)
    expect(renderCounts.get('stream-1')).toBe(2)
  })
})
