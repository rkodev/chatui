import { render, renderHook, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ShortcutsHelpDialog } from './ShortcutsHelpDialog'
import { isEditableTarget } from './shortcuts'
import { useGlobalShortcuts, type GlobalShortcutHandlers } from './useGlobalShortcuts'

function createHandlers(): GlobalShortcutHandlers {
  return {
    onSearch: vi.fn(),
    onNewChat: vi.fn(),
    onToggleHelp: vi.fn(),
    onCopyLastResponse: vi.fn(),
    onNavigateConversation: vi.fn(),
    onStopStreaming: vi.fn(),
  }
}

describe('isEditableTarget', () => {
  it('detects text inputs, textareas, selects, and contenteditable', () => {
    expect(isEditableTarget(document.createElement('input'))).toBe(true)
    expect(isEditableTarget(document.createElement('textarea'))).toBe(true)
    expect(isEditableTarget(document.createElement('select'))).toBe(true)

    const editable = document.createElement('div')
    editable.contentEditable = 'true'
    expect(isEditableTarget(editable)).toBe(true)

    expect(isEditableTarget(document.createElement('button'))).toBe(false)
    expect(isEditableTarget(null)).toBe(false)
  })
})

describe('useGlobalShortcuts', () => {
  it('maps the section 11.10 shortcuts', async () => {
    const user = userEvent.setup()
    const handlers = createHandlers()
    renderHook(() => useGlobalShortcuts(handlers))

    await user.keyboard('{Control>}k{/Control}')
    expect(handlers.onSearch).toHaveBeenCalledTimes(1)

    await user.keyboard('{Control>}{Shift>}o{/Shift}{/Control}')
    expect(handlers.onNewChat).toHaveBeenCalledTimes(1)

    await user.keyboard('{Control>}/{/Control}')
    expect(handlers.onToggleHelp).toHaveBeenCalledTimes(1)

    await user.keyboard('{Control>}{Shift>}c{/Shift}{/Control}')
    expect(handlers.onCopyLastResponse).toHaveBeenCalledTimes(1)

    await user.keyboard('{Alt>}{ArrowDown}{/Alt}')
    expect(handlers.onNavigateConversation).toHaveBeenCalledWith('next')

    await user.keyboard('{Alt>}{ArrowUp}{/Alt}')
    expect(handlers.onNavigateConversation).toHaveBeenCalledWith('previous')

    await user.keyboard('{Escape}')
    expect(handlers.onStopStreaming).toHaveBeenCalledTimes(1)
  })

  it('lets plain typing through while still handling modifier chords and Escape', async () => {
    const user = userEvent.setup()
    const handlers = createHandlers()
    renderHook(() => useGlobalShortcuts(handlers))

    const input = document.createElement('input')
    document.body.appendChild(input)
    input.focus()

    await user.keyboard('ok/')
    expect(handlers.onNewChat).not.toHaveBeenCalled()
    expect(handlers.onSearch).not.toHaveBeenCalled()
    expect(handlers.onToggleHelp).not.toHaveBeenCalled()
    expect(input).toHaveValue('ok/')

    await user.keyboard('{Control>}{Shift>}o{/Shift}{/Control}')
    expect(handlers.onNewChat).toHaveBeenCalledTimes(1)

    await user.keyboard('{Escape}')
    expect(handlers.onStopStreaming).toHaveBeenCalledTimes(1)

    input.remove()
  })
})

describe('ShortcutsHelpDialog', () => {
  it('lists every documented shortcut', () => {
    render(<ShortcutsHelpDialog open onOpenChange={() => {}} />)

    expect(screen.getByRole('dialog', { name: 'Keyboard shortcuts' })).toBeInTheDocument()
    expect(screen.getByText('Ctrl/Cmd + K')).toBeInTheDocument()
    expect(screen.getByText('Ctrl/Cmd + Shift + O')).toBeInTheDocument()
    expect(screen.getByText('Ctrl/Cmd + /')).toBeInTheDocument()
    expect(screen.getByText('Ctrl/Cmd + Shift + C')).toBeInTheDocument()
    expect(screen.getByText('Alt + Up/Down')).toBeInTheDocument()
  })
})
