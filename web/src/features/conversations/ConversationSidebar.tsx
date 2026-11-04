import { useState, type FormEvent } from 'react'
import type { Conversation } from '../../domain'
import type { ConversationSearchGroup } from '../../storage'
import {
  Button,
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogTitle,
  Menu,
  MenuContent,
  MenuItem,
  MenuTrigger,
} from '../../ui/primitives'
import { formatConversationTimestamp } from './formatConversationTimestamp'
import { groupConversationsByDate } from './historyGroups'
import './conversations.css'

export interface ConversationSidebarSearchProps {
  query: string
  status: 'idle' | 'loading' | 'ready' | 'error'
  groups: ConversationSearchGroup[]
  error: string | null
  truncated: boolean
  onQueryChange: (query: string) => void
}

export type ConversationSidebarView = 'active' | 'archived'

export interface ConversationSidebarProps {
  conversations: Conversation[]
  activeConversationId: string | null
  status: 'loading' | 'ready' | 'error'
  error: string | null
  online: boolean
  pendingDelete: Conversation | null
  search: ConversationSidebarSearchProps
  view: ConversationSidebarView
  onViewChange: (view: ConversationSidebarView) => void
  onNewChat: () => void
  onSelect: (conversation: Conversation) => void
  onRename: (conversation: Conversation, title: string) => Promise<void> | void
  onTogglePin: (conversation: Conversation) => void
  onToggleArchive: (conversation: Conversation) => void
  onConfirmDelete: (conversation: Conversation) => void
  onUndoDelete: () => void
  onClose?: () => void
  isOpen?: boolean
  /** Injectable clock for deterministic grouping and timestamp tests. */
  now?: Date
}

/**
 * Conversation history sidebar.
 *
 * The component is presentational: it renders the grouped list, the new-chat
 * action, rename/delete dialogs, and the buffered-delete undo toast. Storage
 * and navigation live in `ConversationSidebarContainer`.
 */
export function ConversationSidebar({
  conversations,
  activeConversationId,
  status,
  error,
  online,
  pendingDelete,
  search,
  view,
  onViewChange,
  onNewChat,
  onSelect,
  onRename,
  onTogglePin,
  onToggleArchive,
  onConfirmDelete,
  onUndoDelete,
  onClose,
  isOpen = false,
  now = new Date(),
}: ConversationSidebarProps) {
  const [renameTarget, setRenameTarget] = useState<Conversation | null>(null)
  const [renameValue, setRenameValue] = useState('')
  const [renameError, setRenameError] = useState<string | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<Conversation | null>(null)
  const pinnedConversations = conversations.filter((conversation) => conversation.pinned)
  const unpinnedConversations = conversations.filter((conversation) => !conversation.pinned)
  const groups = groupConversationsByDate(unpinnedConversations, now)
  const searching = search.query.trim() !== ''
  const archivedView = view === 'archived'

  const openRename = (conversation: Conversation) => {
    setRenameTarget(conversation)
    setRenameValue(conversation.title)
    setRenameError(null)
  }

  const handleRename = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()

    if (!renameTarget) {
      return
    }

    const trimmed = renameValue.trim()

    if (trimmed === '') {
      setRenameError('Enter a title.')
      return
    }

    try {
      await onRename(renameTarget, trimmed)
      setRenameTarget(null)
    } catch (cause) {
      setRenameError(cause instanceof Error ? cause.message : String(cause))
    }
  }

  const handleConfirmDelete = () => {
    if (!deleteTarget) {
      return
    }

    onConfirmDelete(deleteTarget)
    setDeleteTarget(null)
  }

  const renderConversationItem = (conversation: Conversation) => {
    const active = conversation.id === activeConversationId

    return (
      <li
        key={conversation.id}
        className="conversation-sidebar__item"
        data-active={active ? 'true' : undefined}
      >
        <button
          type="button"
          className="conversation-sidebar__item-button"
          onClick={() => onSelect(conversation)}
          aria-current={active ? 'page' : undefined}
        >
          <span className="conversation-sidebar__item-title">
            {conversation.title}
            {conversation.pinned && (
              <span className="conversation-sidebar__pinned-badge">Pinned</span>
            )}
          </span>
          <time className="conversation-sidebar__item-time" dateTime={conversation.lastMessageAt}>
            {formatConversationTimestamp(conversation.lastMessageAt, now)}
          </time>
        </button>

        <Menu>
          <MenuTrigger asChild>
            <Button
              variant="ghost"
              size="sm"
              className="conversation-sidebar__item-actions"
              aria-label={`Actions for ${conversation.title}`}
            >
              ⋯
            </Button>
          </MenuTrigger>
          <MenuContent>
            <MenuItem onSelect={() => onTogglePin(conversation)}>
              {conversation.pinned ? 'Unpin' : 'Pin'}
            </MenuItem>
            <MenuItem onSelect={() => onToggleArchive(conversation)}>
              {conversation.archived ? 'Unarchive' : 'Archive'}
            </MenuItem>
            <MenuItem onSelect={() => openRename(conversation)}>Rename</MenuItem>
            <MenuItem onSelect={() => setDeleteTarget(conversation)}>Delete</MenuItem>
          </MenuContent>
        </Menu>
      </li>
    )
  }

  return (
    <>
      <aside
        className="conversation-sidebar"
        data-open={isOpen ? 'true' : 'false'}
        aria-label="Conversation history"
      >
        <div className="conversation-sidebar__header">
          <h2 className="conversation-sidebar__title">{archivedView ? 'Archived' : 'Chats'}</h2>
          {onClose && (
            <Button
              variant="ghost"
              size="sm"
              className="conversation-sidebar__close"
              onClick={onClose}
              aria-label="Close history"
            >
              ×
            </Button>
          )}
        </div>

        <Button className="conversation-sidebar__new" onClick={onNewChat}>
          New chat
        </Button>

        <Button
          variant="ghost"
          size="sm"
          className="conversation-sidebar__view-toggle"
          onClick={() => onViewChange(archivedView ? 'active' : 'archived')}
        >
          {archivedView ? 'Back to chats' : 'Archived'}
        </Button>

        <div className="conversation-sidebar__search" role="search">
          <label className="conversation-sidebar__search-label" htmlFor="conversation-search">
            Search conversations
          </label>
          <input
            id="conversation-search"
            className="conversation-sidebar__search-input"
            type="search"
            placeholder="Search conversations"
            value={search.query}
            onChange={(event) => search.onQueryChange(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Escape' && search.query !== '') {
                event.preventDefault()
                search.onQueryChange('')
              }
            }}
          />
        </div>

        {!online && (
          <p className="conversation-sidebar__offline" role="status">
            You are offline. History is available; sending is disabled.
          </p>
        )}

        {error && (
          <p className="conversation-sidebar__error" role="alert">
            {error}
          </p>
        )}

        {searching ? (
          <div className="conversation-sidebar__search-results" aria-label="Search results">
            {search.status === 'loading' && (
              <p className="conversation-sidebar__status" role="status" aria-live="polite">
                Searching conversations…
              </p>
            )}

            {search.status === 'error' && (
              <p className="conversation-sidebar__error" role="alert">
                Could not search conversations: {search.error ?? 'Unknown error'}
              </p>
            )}

            {search.status === 'ready' && search.groups.length === 0 && (
              <p className="conversation-sidebar__empty" role="status" aria-live="polite">
                No conversations match “{search.query}”.
              </p>
            )}

            {search.status === 'ready' && search.groups.length > 0 && (
              <>
                <p className="conversation-sidebar__status" role="status" aria-live="polite">
                  {search.groups.length} conversation{search.groups.length === 1 ? '' : 's'} match
                  “{search.query}”.
                </p>

                {search.groups.map((group) => {
                  const active = group.conversation.id === activeConversationId

                  return (
                    <section
                      key={group.conversation.id}
                      className="conversation-sidebar__search-group"
                      aria-labelledby={`search-group-${group.conversation.id}`}
                    >
                      <h3
                        id={`search-group-${group.conversation.id}`}
                        className="conversation-sidebar__search-group-title"
                      >
                        <button
                          type="button"
                          className="conversation-sidebar__search-title"
                          onClick={() => onSelect(group.conversation)}
                          aria-current={active ? 'page' : undefined}
                        >
                          {group.conversation.title}
                        </button>
                        {group.titleMatch && (
                          <span className="conversation-sidebar__search-badge">Title match</span>
                        )}
                      </h3>

                      {group.messageMatches.length > 0 && (
                        <ul className="conversation-sidebar__list">
                          {group.messageMatches.map((match) => (
                            <li key={match.messageId} className="conversation-sidebar__search-match">
                              <button
                                type="button"
                                className="conversation-sidebar__search-snippet"
                                onClick={() => onSelect(group.conversation)}
                              >
                                <span className="conversation-sidebar__search-snippet-role">
                                  {match.role}
                                </span>
                                <span>{match.snippet}</span>
                              </button>
                            </li>
                          ))}
                        </ul>
                      )}
                    </section>
                  )
                })}

                {search.truncated && (
                  <p className="conversation-sidebar__status">
                    Showing the most recent matches. Narrow your search to see more.
                  </p>
                )}
              </>
            )}
          </div>
        ) : (
          <>
            {status === 'loading' && (
              <p className="conversation-sidebar__status" role="status">
                Loading conversations…
              </p>
            )}

            {status === 'ready' && conversations.length === 0 && (
              <p className="conversation-sidebar__empty">
                {archivedView ? 'No archived conversations.' : 'No conversations yet.'}
              </p>
            )}

            {status === 'ready' && pinnedConversations.length > 0 && (
              <section
                className="conversation-sidebar__group"
                aria-labelledby="history-group-pinned"
              >
                <h3
                  id="history-group-pinned"
                  className="conversation-sidebar__group-title"
                >
                  Pinned
                </h3>
                <ul className="conversation-sidebar__list">
                  {pinnedConversations.map(renderConversationItem)}
                </ul>
              </section>
            )}

            {status === 'ready' &&
              groups.map((group) => (
                <section
                  key={group.key}
                  className="conversation-sidebar__group"
                  aria-labelledby={`history-group-${group.key}`}
                >
                  <h3
                    id={`history-group-${group.key}`}
                    className="conversation-sidebar__group-title"
                  >
                    {group.label}
                  </h3>
                  <ul className="conversation-sidebar__list">
                    {group.conversations.map(renderConversationItem)}
                  </ul>
                </section>
              ))}
          </>
        )}

        <Dialog
          open={renameTarget !== null}
          onOpenChange={(open) => {
            if (!open) {
              setRenameTarget(null)
            }
          }}
        >
          <DialogContent>
            <DialogTitle>Rename conversation</DialogTitle>
            <form className="conversation-dialog" onSubmit={handleRename}>
              <label className="conversation-dialog__label" htmlFor="conversation-rename-title">
                Title
              </label>
              <input
                id="conversation-rename-title"
                className="conversation-dialog__input"
                value={renameValue}
                onChange={(event) => setRenameValue(event.target.value)}
                maxLength={200}
              />
              {renameError && (
                <p className="conversation-dialog__error" role="alert">
                  {renameError}
                </p>
              )}
              <div className="conversation-dialog__actions">
                <DialogClose asChild>
                  <Button variant="secondary" type="button">
                    Cancel
                  </Button>
                </DialogClose>
                <Button type="submit" disabled={renameValue.trim() === ''}>
                  Save
                </Button>
              </div>
            </form>
          </DialogContent>
        </Dialog>

        <Dialog
          open={deleteTarget !== null}
          onOpenChange={(open) => {
            if (!open) {
              setDeleteTarget(null)
            }
          }}
        >
          <DialogContent>
            <DialogTitle>Delete conversation?</DialogTitle>
            <DialogDescription>
              “{deleteTarget?.title}” and its messages will be deleted. You can undo for 5 seconds.
            </DialogDescription>
            <div className="conversation-dialog__actions">
              <DialogClose asChild>
                <Button variant="secondary" type="button">
                  Cancel
                </Button>
              </DialogClose>
              <Button variant="danger" onClick={handleConfirmDelete}>
                Delete
              </Button>
            </div>
          </DialogContent>
        </Dialog>
      </aside>

      {pendingDelete && (
        <div className="conversation-undo-toast" role="status" aria-live="polite">
          <span className="conversation-undo-toast__message">Deleted “{pendingDelete.title}”.</span>
          <Button variant="secondary" size="sm" onClick={onUndoDelete}>
            Undo
          </Button>
        </div>
      )}
    </>
  )
}
