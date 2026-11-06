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
const conversationsWithoutOlder = [today, yesterday, week, month]

function renderSidebar(overrides: Partial<ConversationSidebarProps> = {}) {
  const props: ConversationSidebarProps = {
    conversations: [today, yesterday, week, month, older],
    activeConversationId: null,
    status: 'ready',
    error: null,
    online: true,
    pendingDelete: null,
    search: {
      query: '',
      status: 'idle',
      groups: [],
      error: null,
      truncated: false,
      onQueryChange: vi.fn(),
    },
    view: 'active',
    onViewChange: vi.fn(),
    onNewChat: vi.fn(),
    onSelect: vi.fn(),
    onRename: vi.fn(async () => undefined),
    onTogglePin: vi.fn(),
    onToggleArchive: vi.fn(),
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

  it('adds search, archive navigation, and pin/archive actions', async () => {
    const user = userEvent.setup()
    const props = renderSidebar()

    expect(screen.getByRole('searchbox', { name: 'Search conversations' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Archived' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Actions for Conversation A' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Pin' }))
    expect(props.onTogglePin).toHaveBeenCalledWith(today)

    await user.click(screen.getByRole('button', { name: 'Actions for Conversation A' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Archive' }))
    expect(props.onToggleArchive).toHaveBeenCalledWith(today)

    await user.click(screen.getByRole('button', { name: 'Archived' }))
    expect(props.onViewChange).toHaveBeenCalledWith('archived')
  })

  it('renders pinned conversations first in their own group', () => {
    const pinnedOlder = { ...older, pinned: true }
    renderSidebar({ conversations: [pinnedOlder, ...conversationsWithoutOlder] })

    const headings = screen
      .getAllByRole('heading', { level: 3 })
      .map((heading) => heading.textContent)

    expect(headings[0]).toBe('Pinned')
    expect(screen.getAllByText('Pinned')).toHaveLength(2)
    expect(screen.getByRole('button', { name: /^Conversation E/ })).toBeInTheDocument()
  })

  it('shows pin and archive state in the actions menu', async () => {
    const user = userEvent.setup()
    const props = renderSidebar({
      conversations: [{ ...today, pinned: true, archived: true }],
    })

    await user.click(screen.getByRole('button', { name: 'Actions for Conversation A' }))

    await user.click(await screen.findByRole('menuitem', { name: 'Unpin' }))
    expect(props.onTogglePin).toHaveBeenCalledWith({ ...today, pinned: true, archived: true })

    await user.click(screen.getByRole('button', { name: 'Actions for Conversation A' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Unarchive' }))
    expect(props.onToggleArchive).toHaveBeenCalledWith({
      ...today,
      pinned: true,
      archived: true,
    })
  })

  it('renders the archive view with its own heading, navigation, and empty state', () => {
    renderSidebar({ view: 'archived', conversations: [] })

    expect(screen.getByRole('heading', { name: 'Archived' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Back to chats' })).toBeInTheDocument()
    expect(screen.getByText('No archived conversations.')).toBeInTheDocument()
  })

  it('keeps the active empty state separate from the archive empty state', () => {
    renderSidebar({ view: 'active', conversations: [] })

    expect(screen.getByText('No conversations yet.')).toBeInTheDocument()
    expect(screen.queryByText('No archived conversations.')).not.toBeInTheDocument()
  })

  it('reports search query changes and clears on Escape', async () => {
    const user = userEvent.setup()
    const onQueryChange = vi.fn()
    renderSidebar({
      search: {
        query: 'alpha',
        status: 'idle',
        groups: [],
        error: null,
        truncated: false,
        onQueryChange,
      },
    })

    const input = screen.getByRole('searchbox', { name: 'Search conversations' })
    await user.type(input, 'b')
    expect(onQueryChange).toHaveBeenCalled()

    await user.keyboard('{Escape}')
    expect(onQueryChange).toHaveBeenLastCalledWith('')
  })

  it('shows a loading state while searching', () => {
    renderSidebar({
      search: {
        query: 'alpha',
        status: 'loading',
        groups: [],
        error: null,
        truncated: false,
        onQueryChange: vi.fn(),
      },
    })

    expect(screen.getByRole('status')).toHaveTextContent('Searching conversations…')
    expect(screen.queryByText('Conversation A')).not.toBeInTheDocument()
  })

  it('shows an empty search state', () => {
    renderSidebar({
      search: {
        query: 'zzz',
        status: 'ready',
        groups: [],
        error: null,
        truncated: false,
        onQueryChange: vi.fn(),
      },
    })

    expect(screen.getByRole('status')).toHaveTextContent('No conversations match “zzz”.')
  })

  it('shows a search error state', () => {
    renderSidebar({
      search: {
        query: 'alpha',
        status: 'error',
        groups: [],
        error: 'Storage is unavailable.',
        truncated: false,
        onQueryChange: vi.fn(),
      },
    })

    expect(screen.getByRole('alert')).toHaveTextContent(
      'Could not search conversations: Storage is unavailable.',
    )
  })

  it('groups title and message matches, shows context, and selects a result', async () => {
    const user = userEvent.setup()
    const props = renderSidebar({
      search: {
        query: 'alpha',
        status: 'ready',
        groups: [
          {
            conversation: today,
            titleMatch: true,
            messageMatches: [
              {
                messageId: 'message-1',
                sequence: 2,
                role: 'user',
                snippet: '…context around alpha in a message…',
              },
            ],
          },
        ],
        error: null,
        truncated: true,
        onQueryChange: vi.fn(),
      },
    })

    expect(screen.getByText('Title match')).toBeInTheDocument()
    expect(screen.getByText('…context around alpha in a message…')).toBeInTheDocument()
    expect(screen.getByText(/Showing the most recent matches/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Conversation A' }))
    expect(props.onSelect).toHaveBeenCalledWith(today)

    await user.click(screen.getByRole('button', { name: /context around alpha/ }))
    expect(props.onSelect).toHaveBeenCalledTimes(2)
  })
})
