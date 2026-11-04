import { useState, type FormEvent } from 'react'
import type { Conversation } from '../../domain'
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

export interface ConversationSidebarProps {
  conversations: Conversation[]
  activeConversationId: string | null
  status: 'loading' | 'ready' | 'error'
  error: string | null
  online: boolean
  pendingDelete: Conversation | null
  onNewChat: () => void
  onSelect: (conversation: Conversation) => void
  onRename: (conversation: Conversation, title: string) => Promise<void> | void
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
  onNewChat,
  onSelect,
  onRename,
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
  const groups = groupConversationsByDate(conversations, now)

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

  return (
    <>
      <aside
        className="conversation-sidebar"
        data-open={isOpen ? 'true' : 'false'}
        aria-label="Conversation history"
      >
        <div className="conversation-sidebar__header">
          <h2 className="conversation-sidebar__title">Chats</h2>
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

        {status === 'loading' && (
          <p className="conversation-sidebar__status" role="status">
            Loading conversations…
          </p>
        )}

        {status === 'ready' && conversations.length === 0 && (
          <p className="conversation-sidebar__empty">No conversations yet.</p>
        )}

        {status === 'ready' &&
          groups.map((group) => (
            <section
              key={group.key}
              className="conversation-sidebar__group"
              aria-labelledby={`history-group-${group.key}`}
            >
              <h3 id={`history-group-${group.key}`} className="conversation-sidebar__group-title">
                {group.label}
              </h3>
              <ul className="conversation-sidebar__list">
                {group.conversations.map((conversation) => {
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
                        </span>
                        <time
                          className="conversation-sidebar__item-time"
                          dateTime={conversation.lastMessageAt}
                        >
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
                          <MenuItem onSelect={() => openRename(conversation)}>Rename</MenuItem>
                          <MenuItem onSelect={() => setDeleteTarget(conversation)}>Delete</MenuItem>
                        </MenuContent>
                      </Menu>
                    </li>
                  )
                })}
              </ul>
            </section>
          ))}

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
