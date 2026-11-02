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
      messageItemPropsEqual(
        { message, showTokenUsage: false },
        { message, showTokenUsage: false },
      ),
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

  it('renders cancelled and interrupted states', () => {
    const cancelled = createTestMessage({ status: 'cancelled' })
    const { rerender } = render(<MessageItem message={cancelled} showTokenUsage={false} />)

    expect(screen.getByText(/response cancelled/i)).toBeInTheDocument()

    const interrupted = createTestMessage({ status: 'streaming' })
    rerender(<MessageItem message={interrupted} showTokenUsage={false} />)

    expect(screen.getByText(/interrupted/i)).toBeInTheDocument()
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
