import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useDraftStore } from '../../app/stores'
import {
  createTestConversation,
  createTestModel,
  createTestProvider,
} from '../providers/testSupport'
import { Composer } from './Composer'
import { MAX_COMPOSER_LINES } from './composerTextarea'
import { createTestAttachment, createTestChatSession } from './testSupport'
import type { ChatSession } from './useChatSession'

function renderComposer(session: ChatSession) {
  return render(<Composer session={session} />)
}

function setOnline(value: boolean) {
  Object.defineProperty(navigator, 'onLine', {
    configurable: true,
    get: () => value,
  })
}

beforeEach(() => {
  useDraftStore.setState({ drafts: {} })
  setOnline(true)
})

afterEach(() => {
  setOnline(true)
})

describe('Composer', () => {
  it('grows to eight lines and then scrolls internally', async () => {
    const user = userEvent.setup()
    renderComposer(createTestChatSession())

    const textarea = screen.getByLabelText('Message') as HTMLTextAreaElement
    textarea.style.lineHeight = '20px'
    textarea.style.padding = '0px'
    textarea.style.border = '0px'

    Object.defineProperty(textarea, 'scrollHeight', { configurable: true, value: 400 })
    await user.type(textarea, 'a')

    expect(textarea.style.height).toBe(`${20 * MAX_COMPOSER_LINES}px`)
    expect(textarea.style.overflowY).toBe('auto')

    Object.defineProperty(textarea, 'scrollHeight', { configurable: true, value: 40 })
    await user.type(textarea, 'b')

    expect(textarea.style.height).toBe('40px')
    expect(textarea.style.overflowY).toBe('hidden')
  })

  it('sends on Enter when sendKey is enter', async () => {
    const user = userEvent.setup()
    const send = vi.fn(async () => true)
    renderComposer(createTestChatSession({ send }))

    await user.type(screen.getByLabelText('Message'), 'Hello{Enter}')

    await waitFor(() => expect(send).toHaveBeenCalledWith('Hello'))
  })

  it('inserts a newline on Shift+Enter', async () => {
    const user = userEvent.setup()
    const send = vi.fn(async () => true)
    renderComposer(createTestChatSession({ send }))

    const textarea = screen.getByLabelText('Message')
    await user.type(textarea, 'Hello')
    await user.keyboard('{Shift>}{Enter}{/Shift}')

    expect(textarea).toHaveValue('Hello\n')
    expect(send).not.toHaveBeenCalled()
  })

  it('sends only on Mod+Enter when sendKey is mod-enter', async () => {
    const user = userEvent.setup()
    const send = vi.fn(async () => true)
    renderComposer(createTestChatSession({ send, sendKey: 'mod-enter' }))

    const textarea = screen.getByLabelText('Message')
    await user.type(textarea, 'Hello')
    await user.keyboard('{Enter}')

    expect(textarea).toHaveValue('Hello\n')
    expect(send).not.toHaveBeenCalled()

    await user.keyboard('{Control>}{Enter}{/Control}')

    await waitFor(() => expect(send).toHaveBeenCalledWith('Hello'))
  })

  it('disables send when the draft is empty', () => {
    renderComposer(createTestChatSession())

    expect(screen.getByRole('button', { name: 'Send message' })).toBeDisabled()
  })

  it('shows stop instead of send while streaming and cancels the stream', async () => {
    const user = userEvent.setup()
    const cancel = vi.fn(async () => undefined)
    renderComposer(createTestChatSession({ isStreaming: true, cancel }))

    expect(screen.queryByRole('button', { name: 'Send message' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Stop response' }))

    expect(cancel).toHaveBeenCalledTimes(1)
  })

  it('disables the composer while offline and re-enables it on the online event', async () => {
    const user = userEvent.setup()
    setOnline(false)
    renderComposer(createTestChatSession())

    const textarea = screen.getByLabelText('Message')
    expect(textarea).toBeDisabled()
    expect(screen.getByRole('status')).toHaveTextContent(/offline/i)

    setOnline(true)
    act(() => {
      window.dispatchEvent(new Event('online'))
    })

    expect(textarea).toBeEnabled()
    await user.type(textarea, 'Hello')
    expect(screen.getByRole('button', { name: 'Send message' })).toBeEnabled()
  })

  it('disables the composer when no provider is configured', () => {
    renderComposer(
      createTestChatSession({
        availability: {
          ok: false,
          reason: 'no_provider',
          message: 'No provider is configured.',
        },
      }),
    )

    expect(screen.getByLabelText('Message')).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Send message' })).toBeDisabled()
  })

  it('disables the composer when the model is unavailable', () => {
    renderComposer(
      createTestChatSession({
        availability: {
          ok: false,
          reason: 'model_unavailable',
          message: 'Model model-1 is unavailable.',
        },
      }),
    )

    expect(screen.getByLabelText('Message')).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Send message' })).toBeDisabled()
  })

  it('keeps drafts per conversation and restores them on switch', async () => {
    const user = userEvent.setup()
    const first = createTestChatSession()
    const secondConversation = createTestConversation({ id: 'conversation-2' })
    const second = createTestChatSession({
      conversationId: secondConversation.id,
      conversation: secondConversation,
    })
    const { rerender } = renderComposer(first)

    await user.type(screen.getByLabelText('Message'), 'draft A')

    rerender(<Composer session={second} />)
    expect(screen.getByLabelText('Message')).toHaveValue('')
    expect(screen.getByLabelText('Message')).toHaveFocus()

    await user.type(screen.getByLabelText('Message'), 'draft B')

    rerender(<Composer session={first} />)
    expect(screen.getByLabelText('Message')).toHaveValue('draft A')
    expect(screen.getByLabelText('Message')).toHaveFocus()
  })

  it('preserves the typed text and focus when sending fails', async () => {
    const user = userEvent.setup()
    const send = vi.fn(async () => false)
    renderComposer(createTestChatSession({ send }))

    const textarea = screen.getByLabelText('Message')
    await user.type(textarea, 'Keep me')
    await user.click(screen.getByRole('button', { name: 'Send message' }))

    await waitFor(() => expect(send).toHaveBeenCalledWith('Keep me'))
    await waitFor(() => expect(textarea).toHaveValue('Keep me'))
    expect(textarea).toHaveFocus()
  })

  it('preserves the typed text and focus when sending throws', async () => {
    const user = userEvent.setup()
    const send = vi.fn(async () => {
      throw new Error('Network down')
    })
    renderComposer(createTestChatSession({ send }))

    const textarea = screen.getByLabelText('Message')
    await user.type(textarea, 'Keep me')
    await user.click(screen.getByRole('button', { name: 'Send message' }))

    await waitFor(() => expect(send).toHaveBeenCalledWith('Keep me'))
    await waitFor(() => expect(textarea).toHaveValue('Keep me'))
    expect(textarea).toHaveFocus()
  })

  it('clears the draft after a successful send', async () => {
    const user = userEvent.setup()
    const send = vi.fn(async () => true)
    renderComposer(createTestChatSession({ send }))

    const textarea = screen.getByLabelText('Message')
    await user.type(textarea, 'Hello')
    await user.click(screen.getByRole('button', { name: 'Send message' }))

    await waitFor(() => expect(send).toHaveBeenCalledWith('Hello'))
    await waitFor(() => expect(textarea).toHaveValue(''))
  })

  it('adds supported files through the picker', async () => {
    const user = userEvent.setup()
    const addAttachments = vi.fn(async () => ({
      attachments: [createTestAttachment()],
      errors: [],
    }))
    renderComposer(createTestChatSession({ addAttachments }))
    const input = screen.getByLabelText('Attachment files') as HTMLInputElement
    const file = new File(['hello'], 'notes.txt', { type: 'text/plain' })

    await user.upload(input, file)

    await waitFor(() => expect(addAttachments).toHaveBeenCalledWith([file]))
  })

  it('adds dropped files and pasted clipboard images', async () => {
    const addAttachments = vi.fn(async () => ({ attachments: [], errors: [] }))
    renderComposer(createTestChatSession({ addAttachments }))
    const dropzone = screen.getByTestId('composer-dropzone')
    const textarea = screen.getByLabelText('Message')
    const dropped = new File(['hello'], 'notes.md', { type: 'text/markdown' })
    const pasted = new File(['image'], 'clip.png', { type: 'image/png' })

    fireEvent.drop(dropzone, { dataTransfer: { files: [dropped] } })
    await waitFor(() => expect(addAttachments).toHaveBeenCalledWith([dropped]))

    const item = {
      kind: 'file',
      type: 'image/png',
      getAsFile: () => pasted,
    }
    fireEvent.paste(textarea, {
      clipboardData: { items: [item], files: [pasted] },
    })

    await waitFor(() => expect(addAttachments).toHaveBeenCalledWith([pasted]))
  })

  it('shows attachment chips with size and removes them before send', async () => {
    const user = userEvent.setup()
    const attachment = createTestAttachment({ name: 'notes.txt', kind: 'file', size: 2048 })
    const removeAttachment = vi.fn(async () => undefined)
    renderComposer(createTestChatSession({ attachments: [attachment], removeAttachment }))

    expect(screen.getByText('notes.txt')).toBeInTheDocument()
    expect(screen.getByText('2.0 KB')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Remove attachment notes.txt' }))

    expect(removeAttachment).toHaveBeenCalledWith(attachment.id)
  })

  it('sends text and attachments together', async () => {
    const user = userEvent.setup()
    const attachment = createTestAttachment({ kind: 'file', name: 'notes.txt' })
    const send = vi.fn(async () => true)
    renderComposer(createTestChatSession({ attachments: [attachment], send }))

    await user.type(screen.getByLabelText('Message'), 'Hello')
    await user.click(screen.getByRole('button', { name: 'Send message' }))

    await waitFor(() => expect(send).toHaveBeenCalledWith('Hello', [attachment]))
  })

  it('warns and confirms before sending unsupported image attachments', async () => {
    const user = userEvent.setup()
    const attachment = createTestAttachment({ kind: 'image', name: 'photo.png' })
    const removeAttachment = vi.fn(async () => undefined)
    const send = vi.fn(async () => true)
    renderComposer(
      createTestChatSession({
        attachments: [attachment],
        availability: {
          ok: true,
          model: createTestModel({ inputModalities: ['text'] }),
          provider: createTestProvider(),
        },
        removeAttachment,
        send,
      }),
    )

    expect(screen.getByRole('status')).toHaveTextContent(/cannot receive images/i)

    await user.type(screen.getByLabelText('Message'), 'What is this?')
    await user.click(screen.getByRole('button', { name: 'Send message' }))

    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(send).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: 'Remove and send' }))

    await waitFor(() => expect(removeAttachment).toHaveBeenCalledWith(attachment.id))
    await waitFor(() => expect(send).toHaveBeenCalledWith('What is this?'))
  })

  it('rejects an invalid file without corrupting the composer state', async () => {
    const user = userEvent.setup()
    const addAttachments = vi.fn(async () => ({
      attachments: [],
      errors: [
        {
          fileName: 'bad.exe',
          code: 'unsupported_type' as const,
          message: 'bad.exe is not supported.',
        },
      ],
    }))
    renderComposer(createTestChatSession({ addAttachments }))

    const input = screen.getByLabelText('Attachment files') as HTMLInputElement
    fireEvent.change(input, {
      target: { files: [new File(['x'], 'bad.exe', { type: 'application/x-msdownload' })] },
    })

    expect(await screen.findByRole('alert')).toHaveTextContent('bad.exe is not supported.')
    await user.type(screen.getByLabelText('Message'), 'Still here')
    expect(screen.getByLabelText('Message')).toHaveValue('Still here')
  })
})
