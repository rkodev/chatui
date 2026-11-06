import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type DragEvent,
  type ClipboardEvent,
  type KeyboardEvent,
} from 'react'
import { useDraftStore } from '../../app/stores'
import type { Attachment } from '../../domain'
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogTitle,
  Button,
} from '../../ui/primitives'
import {
  AttachmentChips,
  attachmentSupport,
  SUPPORTED_TEXT_EXTENSIONS,
  type AttachmentView,
} from '../attachments'
import { resizeComposerTextarea } from './composerTextarea'
import { useOnlineStatus } from './useOnlineStatus'
import type { ChatSession } from './useChatSession'
import './chat.css'

export interface ComposerProps {
  session: ChatSession
}

const ATTACHMENT_ACCEPT = [
  'image/png',
  'image/jpeg',
  'image/webp',
  'image/gif',
  ...SUPPORTED_TEXT_EXTENSIONS.map((extension) => `.${extension}`),
].join(',')

function isImageFile(file: File): boolean {
  return file.type.startsWith('image/')
}

function clipboardImages(data: DataTransfer | null): File[] {
  if (!data) {
    return []
  }

  const files: File[] = []

  for (const item of Array.from(data.items)) {
    if (item.kind === 'file' && item.type.startsWith('image/')) {
      const file = item.getAsFile()

      if (file) {
        files.push(file)
      }
    }
  }

  if (files.length === 0) {
    files.push(...Array.from(data.files).filter(isImageFile))
  }

  return files
}

function toAttachmentViews(attachments: readonly Attachment[]): AttachmentView[] {
  return attachments.map((attachment) => ({
    id: attachment.id,
    kind: attachment.kind,
    name: attachment.name,
    mimeType: attachment.mimeType,
    size: attachment.size,
  }))
}

/**
 * Chat composer with per-conversation drafts, send-key modes, attachments, and
 * a stop control.
 *
 * Drafts live in the shared Zustand draft store keyed by conversation ID, so a
 * route change restores the text for the conversation being opened. Attachment
 * chips live in the chat session and are cleared only after a successful send.
 */
export function Composer({ session }: ComposerProps) {
  const {
    conversationId,
    sendKey,
    isStreaming,
    availability,
    status,
    attachments,
    addAttachments,
    removeAttachment,
  } = session
  const draft = useDraftStore((state) => state.drafts[conversationId] ?? '')
  const setDraft = useDraftStore((state) => state.setDraft)
  const clearDraft = useDraftStore((state) => state.clearDraft)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const previousConversationId = useRef<string | null>(null)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [isAdding, setIsAdding] = useState(false)
  const [isDragging, setIsDragging] = useState(false)
  const [attachmentError, setAttachmentError] = useState<string | null>(null)
  const [confirmUnsupported, setConfirmUnsupported] = useState(false)
  const online = useOnlineStatus()

  const noProvider =
    availability !== null && !availability.ok && availability.reason === 'no_provider'
  const modelUnavailable =
    availability !== null && !availability.ok && availability.reason === 'model_unavailable'
  const composerDisabled = !online || noProvider || modelUnavailable || status !== 'ready'
  const model = availability?.ok ? availability.model : null
  const support = attachmentSupport(attachments, model)
  const unsupported = attachments.filter(
    (attachment) =>
      (attachment.kind === 'image' && !support.images) ||
      (attachment.kind === 'file' && !support.files),
  )
  const sendDisabled =
    composerDisabled ||
    isStreaming ||
    isSubmitting ||
    isAdding ||
    (draft.trim() === '' && attachments.length === 0)

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

  const addFiles = useCallback(
    async (files: readonly File[]) => {
      if (files.length === 0 || composerDisabled) {
        return
      }

      setIsAdding(true)
      setAttachmentError(null)

      try {
        const result = await addAttachments(files)

        if (result.errors.length > 0) {
          setAttachmentError(result.errors.map((error) => error.message).join(' '))
        }
      } catch (cause) {
        setAttachmentError(
          cause instanceof Error ? cause.message : 'The attachment could not be added.',
        )
      } finally {
        setIsAdding(false)
      }
    },
    [addAttachments, composerDisabled],
  )

  const sendWith = useCallback(
    async (pending: readonly Attachment[]) => {
      const text = draft
      const trimmed = text.trim()

      if (trimmed === '' && pending.length === 0) {
        return
      }

      setIsSubmitting(true)
      clearDraft(conversationId)

      try {
        const sent =
          pending.length === 0 ? await session.send(trimmed) : await session.send(trimmed, pending)

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
    },
    [clearDraft, conversationId, draft, session, setDraft],
  )

  const handleSend = useCallback(async () => {
    if (sendDisabled) {
      return
    }

    if (unsupported.length > 0) {
      setConfirmUnsupported(true)
      return
    }

    await sendWith(attachments)
  }, [attachments, sendDisabled, sendWith, unsupported.length])

  const handleRemoveUnsupportedAndSend = useCallback(async () => {
    setConfirmUnsupported(false)

    for (const attachment of unsupported) {
      await removeAttachment(attachment.id)
    }

    const supported = attachments.filter(
      (attachment) => !unsupported.some((candidate) => candidate.id === attachment.id),
    )
    await sendWith(supported)
  }, [attachments, removeAttachment, sendWith, unsupported])

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

  const handlePaste = useCallback(
    (event: ClipboardEvent<HTMLTextAreaElement>) => {
      const images = clipboardImages(event.clipboardData)

      if (images.length === 0) {
        return
      }

      event.preventDefault()
      void addFiles(images)
    },
    [addFiles],
  )

  const handleDrop = useCallback(
    (event: DragEvent<HTMLDivElement>) => {
      event.preventDefault()
      setIsDragging(false)
      const files = Array.from(event.dataTransfer.files)

      if (files.length > 0) {
        void addFiles(files)
      }
    },
    [addFiles],
  )

  const handleDragOver = useCallback((event: DragEvent<HTMLDivElement>) => {
    event.preventDefault()
    setIsDragging(true)
  }, [])

  const handleDragLeave = useCallback((event: DragEvent<HTMLDivElement>) => {
    if (event.currentTarget.contains(event.relatedTarget as Node | null)) {
      return
    }

    setIsDragging(false)
  }, [])

  return (
    <div
      className={`composer composer__dropzone${isDragging ? ' composer__dropzone--active' : ''}`}
      onDrop={handleDrop}
      onDragOver={handleDragOver}
      onDragLeave={handleDragLeave}
      data-testid="composer-dropzone"
    >
      {!online && (
        <p className="composer__notice" role="status">
          You are offline. Sending is disabled.
        </p>
      )}

      {support.message && (
        <p className="composer__notice" role="status">
          {support.message}
        </p>
      )}

      {attachmentError && (
        <p className="composer__attachment-error" role="alert">
          {attachmentError}
        </p>
      )}

      <AttachmentChips
        attachments={toAttachmentViews(attachments)}
        onRemove={(id) => void removeAttachment(id)}
      />

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
          onPaste={handlePaste}
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

      <div className="composer__attachment-actions">
        <input
          ref={fileInputRef}
          type="file"
          multiple
          accept={ATTACHMENT_ACCEPT}
          className="sr-only"
          aria-label="Attachment files"
          onChange={(event) => {
            const files = Array.from(event.target.files ?? [])
            event.target.value = ''
            void addFiles(files)
          }}
        />
        <Button
          variant="secondary"
          size="sm"
          onClick={() => fileInputRef.current?.click()}
          disabled={composerDisabled || isStreaming || isAdding}
          aria-label="Attach files"
        >
          {isAdding ? 'Adding…' : 'Attach files'}
        </Button>
        <span className="composer__hint">
          Images are downscaled to 2048 px before sending. Files stay on this device.
        </span>
      </div>

      <Dialog open={confirmUnsupported} onOpenChange={setConfirmUnsupported}>
        <DialogContent>
          <DialogTitle>Unsupported attachments</DialogTitle>
          <DialogDescription>
            {support.message} Remove them to send this message, or cancel and choose another model.
          </DialogDescription>
          <div className="composer__dialog-actions">
            <DialogClose asChild>
              <Button variant="secondary" size="sm">
                Cancel
              </Button>
            </DialogClose>
            <Button size="sm" onClick={() => void handleRemoveUnsupportedAndSend()}>
              Remove and send
            </Button>
          </div>
        </DialogContent>
      </Dialog>
    </div>
  )
}
