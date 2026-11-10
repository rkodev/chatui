import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { Message } from '../../domain'
import { MessageItem } from './MessageItem'
import { messageItemPropsEqual } from './messageItemProps'
import { createTestMessage } from './testSupport'

describe('MessageItem', () => {
  it('treats an unchanged message revision as equal', () => {
    const message = createTestMessage()

    expect(
      messageItemPropsEqual({ message, showTokenUsage: false }, { message, showTokenUsage: false }),
    ).toBe(true)
  })

  it('treats a changed content revision as different', () => {
    const message = createTestMessage()
    const updated: Message = {
      ...message,
      parts: [{ type: 'text', text: 'Updated' }],
      updatedAt: '2026-01-01T00:00:01.000Z',
    }

    expect(
      messageItemPropsEqual(
        { message, showTokenUsage: false },
        { message: updated, showTokenUsage: false },
      ),
    ).toBe(false)

    const { rerender } = render(<MessageItem message={message} showTokenUsage={false} />)
    rerender(<MessageItem message={updated} showTokenUsage={false} />)

    expect(screen.getByText('Updated')).toBeInTheDocument()
  })

  it('renders an inline error card with retry and details', async () => {
    const user = userEvent.setup()
    const onRetry = vi.fn()
    const message = createTestMessage({
      role: 'assistant',
      status: 'error',
      parts: [{ type: 'text', text: 'Partial answer' }],
      error: {
        code: 'rate_limit',
        message: 'raw provider message',
        providerId: 'provider-1',
        status: 429,
        retryable: true,
        retryAfterSeconds: 30,
        requestId: 'req-1',
        details: { error: 'raw provider message' },
        at: '2026-01-01T00:00:00.000Z',
      },
    })

    render(<MessageItem message={message} showTokenUsage={false} onRetry={onRetry} />)

    expect(screen.getByText('Rate limited')).toBeInTheDocument()
    expect(screen.getByText(/rate limiting requests/i)).toBeInTheDocument()
    expect(screen.getByText('Partial answer')).toBeInTheDocument()
    expect(screen.getByText('Details')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Retry response' }))
    expect(onRetry).toHaveBeenCalledWith(message)
  })

  it('exposes response text for the copy-last-response shortcut', () => {
    const message = createTestMessage({
      role: 'assistant',
      parts: [
        { type: 'text', text: 'First paragraph' },
        { type: 'text', text: 'Second paragraph' },
      ],
    })

    render(<MessageItem message={message} showTokenUsage={false} />)

    expect(screen.getByRole('article', { name: 'Assistant message' })).toHaveAttribute(
      'data-message-text',
      'First paragraph\n\nSecond paragraph',
    )
  })

  it('renders cancelled and interrupted states', () => {
    const cancelled = createTestMessage({ status: 'cancelled' })
    const { rerender } = render(<MessageItem message={cancelled} showTokenUsage={false} />)

    expect(screen.getByText(/response cancelled/i)).toBeInTheDocument()

    const interrupted = createTestMessage({ status: 'streaming' })
    rerender(<MessageItem message={interrupted} showTokenUsage={false} />)

    expect(screen.getByText(/interrupted/i)).toBeInTheDocument()
  })

  it('renders attachment chips without a "No content" placeholder', () => {
    const message = createTestMessage({
      parts: [
        { type: 'text', text: 'See attached' },
        { type: 'image', attachmentId: 'image-1', mimeType: 'image/png', alt: 'pixel.png' },
        { type: 'file', attachmentId: 'file-1', mimeType: 'text/plain', name: 'notes.txt' },
      ],
    })

    render(<MessageItem message={message} showTokenUsage={false} />)

    expect(screen.getByText('See attached')).toBeInTheDocument()
    expect(screen.getByText('pixel.png')).toBeInTheDocument()
    expect(screen.getByText('notes.txt')).toBeInTheDocument()
    expect(screen.queryByText('No content.')).not.toBeInTheDocument()
  })

  it('renders an image-only message without the empty placeholder', () => {
    const message = createTestMessage({
      parts: [{ type: 'image', attachmentId: 'image-1', mimeType: 'image/png', alt: 'pixel.png' }],
    })

    render(<MessageItem message={message} showTokenUsage={false} />)

    expect(screen.getByText('pixel.png')).toBeInTheDocument()
    expect(screen.queryByText('No content.')).not.toBeInTheDocument()
  })

  it('shows a typing indicator only for the active stream', () => {
    const streaming = createTestMessage({ status: 'streaming', parts: [] })
    const { rerender } = render(
      <MessageItem message={streaming} showTokenUsage={false} isActiveStream />,
    )

    expect(screen.getByLabelText('Assistant is typing')).toBeInTheDocument()
    expect(screen.queryByText(/interrupted/i)).not.toBeInTheDocument()

    rerender(<MessageItem message={streaming} showTokenUsage={false} />)

    expect(screen.queryByLabelText('Assistant is typing')).not.toBeInTheDocument()
    expect(screen.getByText(/interrupted/i)).toBeInTheDocument()
  })

  it('renders Markdown and exposes last-message actions', async () => {
    const user = userEvent.setup()
    const onRegenerate = vi.fn()
    const message = createTestMessage({
      role: 'assistant',
      parts: [{ type: 'text', text: '**bold** answer' }],
    })

    render(
      <MessageItem
        message={message}
        showTokenUsage={false}
        canRegenerate
        onRegenerate={onRegenerate}
      />,
    )

    expect(screen.getByText('bold').tagName).toBe('STRONG')

    await user.click(screen.getByRole('button', { name: 'Regenerate response' }))

    expect(onRegenerate).toHaveBeenCalledWith(message)
  })

  it('renders reasoning separately without replaying it in the answer', () => {
    const message = createTestMessage({
      role: 'assistant',
      parts: [
        { type: 'reasoning', text: 'internal step one' },
        { type: 'text', text: 'final answer' },
      ],
    })
    const { container } = render(<MessageItem message={message} showTokenUsage={false} />)

    expect(screen.getByText('Reasoning')).toBeInTheDocument()
    expect(screen.getByText('internal step one')).toBeInTheDocument()
    expect(screen.getByText('final answer')).toBeInTheDocument()

    const reasoning = container.querySelector('.message__reasoning')
    const answer = container.querySelector('.message__markdown')

    expect(reasoning).toHaveTextContent('internal step one')
    expect(reasoning).not.toHaveTextContent('final answer')
    expect(answer).toHaveTextContent('final answer')
    expect(answer).not.toHaveTextContent('internal step one')
  })

  it('passes the remote-image setting through to the Markdown renderer', () => {
    const message = createTestMessage({
      role: 'assistant',
      parts: [{ type: 'text', text: '![Diagram](https://example.com/diagram.png)' }],
    })
    const { container, rerender } = render(<MessageItem message={message} showTokenUsage={false} />)

    expect(container.querySelector('img')).toBeNull()

    rerender(<MessageItem message={message} showTokenUsage={false} loadRemoteImages />)

    expect(container.querySelector('img')).toHaveAttribute('src', 'https://example.com/diagram.png')
  })

  it('invalidates memoization when the remote-image setting changes', () => {
    const message = createTestMessage()

    expect(
      messageItemPropsEqual(
        { message, showTokenUsage: false, loadRemoteImages: false },
        { message, showTokenUsage: false, loadRemoteImages: true },
      ),
    ).toBe(false)
  })

  it('shows usage only when enabled', () => {
    const message = createTestMessage({
      role: 'assistant',
      usage: {
        inputTokens: 10,
        outputTokens: 20,
        totalTokens: 30,
        costUsd: 0.001,
        estimated: false,
      },
    })
    const { rerender } = render(<MessageItem message={message} showTokenUsage />)

    expect(screen.getByText('10 in · 20 out · 30 total · $0.001')).toBeInTheDocument()

    rerender(<MessageItem message={message} showTokenUsage={false} />)

    expect(screen.queryByText(/10 in/)).not.toBeInTheDocument()
  })
})
