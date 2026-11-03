import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { MessageActions } from './MessageActions'
import { messageText } from './messageText'
import { createTestMessage } from './testSupport'

const writeText = vi.fn()

describe('MessageActions', () => {
  it('copies the exact message text', async () => {
    const user = userEvent.setup()
    writeText.mockReset()
    writeText.mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    })
    const message = createTestMessage({
      parts: [
        { type: 'text', text: 'First paragraph' },
        { type: 'text', text: 'Second paragraph' },
      ],
    })

    render(<MessageActions message={message} />)

    await user.click(screen.getByRole('button', { name: 'Copy message' }))

    expect(writeText).toHaveBeenCalledWith('First paragraph\n\nSecond paragraph')
    expect(messageText(message)).toBe('First paragraph\n\nSecond paragraph')
  })

  it('regenerates only when the parent marks the message as the last assistant', async () => {
    const user = userEvent.setup()
    const onRegenerate = vi.fn()
    const message = createTestMessage({ role: 'assistant' })

    const { rerender } = render(<MessageActions message={message} />)

    expect(screen.queryByRole('button', { name: 'Regenerate response' })).not.toBeInTheDocument()

    rerender(
      <MessageActions message={message} canRegenerate onRegenerate={onRegenerate} />,
    )

    await user.click(screen.getByRole('button', { name: 'Regenerate response' }))

    expect(onRegenerate).toHaveBeenCalledWith(message)
  })

  it('confirms truncation before editing the last user message', async () => {
    const user = userEvent.setup()
    const onEditLastUser = vi.fn()
    const message = createTestMessage({
      role: 'user',
      parts: [{ type: 'text', text: 'Original question' }],
    })

    render(
      <MessageActions
        message={message}
        canEditLastUser
        onEditLastUser={onEditLastUser}
      />,
    )

    await user.click(screen.getByRole('button', { name: 'Edit last message' }))

    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByText(/deletes every later message/i)).toBeInTheDocument()

    const textarea = screen.getByRole('textbox', { name: 'Message text' })
    expect(textarea).toHaveValue('Original question')

    await user.clear(textarea)
    await user.type(textarea, 'Updated question')
    await user.click(screen.getByRole('button', { name: 'Edit and resend' }))

    expect(onEditLastUser).toHaveBeenCalledWith(message, 'Updated question')
  })

  it('does not edit when the confirmation is cancelled', async () => {
    const user = userEvent.setup()
    const onEditLastUser = vi.fn()
    const message = createTestMessage({ role: 'user' })

    render(
      <MessageActions
        message={message}
        canEditLastUser
        onEditLastUser={onEditLastUser}
      />,
    )

    await user.click(screen.getByRole('button', { name: 'Edit last message' }))
    await user.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(onEditLastUser).not.toHaveBeenCalled()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})
