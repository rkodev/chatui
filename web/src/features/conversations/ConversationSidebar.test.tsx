import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { Conversation } from '../../domain'
import { createTestConversation } from '../providers/testSupport'
import { ConversationSidebar, type ConversationSidebarProps } from './ConversationSidebar'

const now = new Date(2026, 10, 3, 12, 0, 0)

function at(daysAgo: number, hour = 9): string {
  return new Date(2026, 10, 3 - daysAgo, hour, 0, 0).toISOString()
}

function conversation(id: string, daysAgo: number, title = id): Conversation {
  return createTestConversation({
    id,
    title,
    lastMessageAt: at(daysAgo),
    updatedAt: at(daysAgo),
  })
}

const today = conversation('today', 0, 'Conversation A')
const yesterday = conversation('yesterday', 1, 'Conversation B')
const week = conversation('week', 3, 'Conversation C')
const month = conversation('month', 10, 'Conversation D')
const older = conversation('older', 40, 'Conversation E')

function renderSidebar(overrides: Partial<ConversationSidebarProps> = {}) {
  const props: ConversationSidebarProps = {
    conversations: [today, yesterday, week, month, older],
    activeConversationId: null,
    status: 'ready',
    error: null,
    online: true,
    pendingDelete: null,
    onNewChat: vi.fn(),
    onSelect: vi.fn(),
    onRename: vi.fn(async () => undefined),
    onConfirmDelete: vi.fn(),
    onUndoDelete: vi.fn(),
    now,
    ...overrides,
  }

  render(<ConversationSidebar {...props} />)
  return props
}

describe('ConversationSidebar', () => {
  it('renders the required date groups in order', () => {
    renderSidebar()

    const headings = screen
      .getAllByRole('heading', { level: 3 })
      .map((heading) => heading.textContent)

    expect(headings).toEqual(['Today', 'Yesterday', 'Previous 7 days', 'Previous 30 days', 'Older'])
  })

  it('renders conversation titles and timestamps', () => {
    renderSidebar()

    expect(screen.getByText('Conversation A')).toBeInTheDocument()
    expect(screen.getByText('Conversation E')).toBeInTheDocument()
    expect(screen.getAllByRole('time').length).toBe(5)
  })

  it('creates a new chat from the primary action', async () => {
    const user = userEvent.setup()
    const props = renderSidebar()

    await user.click(screen.getByRole('button', { name: 'New chat' }))

    expect(props.onNewChat).toHaveBeenCalledTimes(1)
  })

  it('selects a conversation and marks the active row', async () => {
    const user = userEvent.setup()
    const props = renderSidebar({ activeConversationId: 'today' })

    const button = screen.getByRole('button', { name: /^Conversation A/ })
    expect(button).toHaveAttribute('aria-current', 'page')

    await user.click(button)

    expect(props.onSelect).toHaveBeenCalledWith(today)
  })

  it('requires confirmation before deleting a conversation', async () => {
    const user = userEvent.setup()
    const props = renderSidebar()

    await user.click(screen.getByRole('button', { name: 'Actions for Conversation A' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Delete' }))

    expect(await screen.findByRole('dialog', { name: 'Delete conversation?' })).toBeInTheDocument()
    expect(props.onConfirmDelete).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: 'Delete' }))

    expect(props.onConfirmDelete).toHaveBeenCalledWith(today)
  })

  it('shows the undo toast for a buffered delete', async () => {
    const user = userEvent.setup()
    const props = renderSidebar({ pendingDelete: today })

    expect(screen.getByRole('status')).toHaveTextContent('Deleted “Conversation A”.')

    await user.click(screen.getByRole('button', { name: 'Undo' }))

    expect(props.onUndoDelete).toHaveBeenCalledTimes(1)
  })

  it('renames a conversation through the dialog', async () => {
    const user = userEvent.setup()
    const props = renderSidebar()

    await user.click(screen.getByRole('button', { name: 'Actions for Conversation A' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Rename' }))

    const input = await screen.findByLabelText('Title')
    await user.clear(input)
    await user.type(input, 'Renamed conversation')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(props.onRename).toHaveBeenCalledWith(today, 'Renamed conversation'))
  })

  it('keeps history readable while offline and explains that sending is disabled', () => {
    renderSidebar({ online: false })

    expect(screen.getByRole('status')).toHaveTextContent(/offline/i)
    expect(screen.getByText('Conversation A')).toBeInTheDocument()
  })

  it('does not add search, pin, or archive controls', () => {
    renderSidebar()

    expect(screen.queryByText(/search/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/pin/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/archive/i)).not.toBeInTheDocument()
  })
})
