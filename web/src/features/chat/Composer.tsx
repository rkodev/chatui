import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type KeyboardEvent,
} from 'react'
import { useDraftStore } from '../../app/stores'
import { Button } from '../../ui/primitives'
import { resizeComposerTextarea } from './composerTextarea'
import { useOnlineStatus } from './useOnlineStatus'
import type { ChatSession } from './useChatSession'
import './chat.css'

export interface ComposerProps {
  session: ChatSession
}

/**
 * Chat composer with per-conversation drafts, send-key modes, and stop control.
 *
 * Drafts live in the shared Zustand draft store keyed by conversation ID, so a
 * route change restores the text for the conversation being opened. The
 * textarea is focused when the conversation changes. Sending clears the draft
 * immediately; a failed send restores it and returns focus to the composer.
 */
export function Composer({ session }: ComposerProps) {
  const { conversationId, sendKey, isStreaming, availability, status } = session
  const draft = useDraftStore((state) => state.drafts[conversationId] ?? '')
  const setDraft = useDraftStore((state) => state.setDraft)
  const clearDraft = useDraftStore((state) => state.clearDraft)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const previousConversationId = useRef<string | null>(null)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const online = useOnlineStatus()

  const noProvider =
    availability !== null && !availability.ok && availability.reason === 'no_provider'
  const modelUnavailable =
    availability !== null && !availability.ok && availability.reason === 'model_unavailable'
  const composerDisabled = !online || noProvider || modelUnavailable || status !== 'ready'
  const sendDisabled = composerDisabled || isStreaming || isSubmitting || draft.trim() === ''

  useLayoutEffect(() => {
    const element = textareaRef.current

    if (element) {
      resizeComposerTextarea(element)
    }
  }, [draft])

  useEffect(() => {
    if (previousConversationId.current === conversationId) {
      return
    }

    previousConversationId.current = conversationId

    const element = textareaRef.current

    if (element && !element.disabled) {
      element.focus()
    }
  }, [conversationId, composerDisabled])

  const handleSend = useCallback(async () => {
    if (sendDisabled) {
      return
    }

    const text = draft
    const trimmed = text.trim()

    if (trimmed === '') {
      return
    }

    setIsSubmitting(true)
    clearDraft(conversationId)

    try {
      const sent = await session.send(trimmed)

      if (!sent) {
        setDraft(conversationId, text)
        textareaRef.current?.focus()
      }
    } catch {
      setDraft(conversationId, text)
      textareaRef.current?.focus()
    } finally {
      setIsSubmitting(false)
    }
  }, [clearDraft, conversationId, draft, sendDisabled, session, setDraft])

  const handleStop = useCallback(() => {
    void session.cancel()
  }, [session])

  const handleKeyDown = useCallback(
    (event: KeyboardEvent<HTMLTextAreaElement>) => {
      if (event.key !== 'Enter' || event.nativeEvent.isComposing) {
        return
      }

      // Shift+Enter always inserts a newline.
      if (event.shiftKey) {
        return
      }

      const modifier = event.ctrlKey || event.metaKey
      // In `enter` mode plain Enter sends and Mod+Enter also sends. In
      // `mod-enter` mode only Mod+Enter sends; plain Enter inserts a newline.
      const shouldSend = sendKey === 'enter' || modifier

      if (!shouldSend || sendDisabled) {
        return
      }

      event.preventDefault()
      void handleSend()
    },
    [handleSend, sendDisabled, sendKey],
  )

  return (
    <div className="composer">
      {!online && (
        <p className="composer__notice" role="status">
          You are offline. Sending is disabled.
        </p>
      )}

      <div className="composer__field">
        <label className="sr-only" htmlFor="composer-input">
          Message
        </label>
        <textarea
          ref={textareaRef}
          id="composer-input"
          className="composer__textarea"
          rows={1}
          value={draft}
          placeholder="Send a message…"
          disabled={composerDisabled}
          onChange={(event) => setDraft(conversationId, event.target.value)}
          onKeyDown={handleKeyDown}
        />

        {isStreaming ? (
          <Button
            variant="secondary"
            size="sm"
            className="composer__action"
            onClick={handleStop}
            aria-label="Stop response"
          >
            Stop
          </Button>
        ) : (
          <Button
            size="sm"
            className="composer__action"
            onClick={() => void handleSend()}
            disabled={sendDisabled}
            aria-label="Send message"
          >
            Send
          </Button>
        )}
      </div>
    </div>
  )
}
