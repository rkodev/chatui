import { useCallback, useEffect, useRef, useState } from 'react'
import type { Message } from '../../domain'
import {
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogTitle,
} from '../../ui/primitives'
import { copyText } from './clipboard'
import { messageText } from './messageText'
import './chat.css'

export interface MessageActionsProps {
  message: Message
  canRegenerate?: boolean
  canEditLastUser?: boolean
  onRegenerate?: (message: Message) => void
  onEditLastUser?: (message: Message, text: string) => void
}

/**
 * Hover/focus message actions.
 *
 * Copy is local to the component. Regenerate and edit are only offered when
 * the parent marks the message as the last assistant or last user message.
 * Editing always opens a confirmation dialog because it truncates every later
 * message in the conversation.
 */
export function MessageActions({
  message,
  canRegenerate = false,
  canEditLastUser = false,
  onRegenerate,
  onEditLastUser,
}: MessageActionsProps) {
  const [copied, setCopied] = useState(false)
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState('')
  const copyTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(
    () => () => {
      if (copyTimer.current !== null) {
        clearTimeout(copyTimer.current)
      }
    },
    [],
  )

  const handleCopy = useCallback(async () => {
    const copiedOk = await copyText(messageText(message))

    if (!copiedOk) {
      return
    }

    setCopied(true)

    if (copyTimer.current !== null) {
      clearTimeout(copyTimer.current)
    }

    copyTimer.current = setTimeout(() => {
      setCopied(false)
      copyTimer.current = null
    }, 1500)
  }, [message])

  const openEdit = useCallback(() => {
    setDraft(messageText(message))
    setEditing(true)
  }, [message])

  const confirmEdit = useCallback(() => {
    const text = draft.trim()

    if (text === '') {
      return
    }

    setEditing(false)
    onEditLastUser?.(message, text)
  }, [draft, message, onEditLastUser])

  return (
    <div className="message__actions" role="group" aria-label="Message actions">
      <Button variant="ghost" size="sm" onClick={handleCopy} aria-label="Copy message">
        {copied ? 'Copied' : 'Copy'}
      </Button>

      {canRegenerate && onRegenerate && (
        <Button
          variant="ghost"
          size="sm"
          onClick={() => onRegenerate(message)}
          aria-label="Regenerate response"
        >
          Regenerate
        </Button>
      )}

      {canEditLastUser && onEditLastUser && (
        <Button variant="ghost" size="sm" onClick={openEdit} aria-label="Edit last message">
          Edit
        </Button>
      )}

      <Dialog open={editing} onOpenChange={setEditing}>
        <DialogContent className="message-actions__dialog">
          <DialogTitle>Edit last message</DialogTitle>
          <DialogDescription>
            Editing this message deletes every later message in this conversation. This cannot be
            undone.
          </DialogDescription>
          <textarea
            className="message-actions__textarea"
            aria-label="Message text"
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
          />
          <div className="message-actions__dialog-actions">
            <Button variant="secondary" onClick={() => setEditing(false)}>
              Cancel
            </Button>
            <Button variant="danger" onClick={confirmEdit} disabled={draft.trim() === ''}>
              Edit and resend
            </Button>
          </div>
        </DialogContent>
      </Dialog>
    </div>
  )
}
