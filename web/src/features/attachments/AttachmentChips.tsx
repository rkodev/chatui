import { useContext, useEffect, useState } from 'react'
import type { ContentPart } from '../../domain'
import { formatAttachmentSize } from './attachmentRules'
import { attachmentViewsFromParts } from './attachmentViews'
import { ChatServiceContext } from '../chat/chatServiceContext'
import './attachments.css'

export interface AttachmentView {
  id: string
  kind: 'image' | 'file'
  name: string
  mimeType: string
  size: number | null
}

export interface AttachmentChipsProps {
  attachments: readonly AttachmentView[]
  onRemove?: (id: string) => void
  label?: string
}

function AttachmentThumbnail({ attachment }: { attachment: AttachmentView }) {
  const service = useContext(ChatServiceContext)
  const [url, setUrl] = useState<string | null>(null)

  useEffect(() => {
    if (attachment.kind !== 'image') {
      return
    }

    if (!service || typeof service.getAttachmentDataUrl !== 'function') {
      return
    }

    let cancelled = false

    service.getAttachmentDataUrl(attachment.id).then(
      (value) => {
        if (!cancelled) {
          setUrl(value)
        }
      },
      () => {
        // A missing thumbnail must never hide the attachment chip.
      },
    )

    return () => {
      cancelled = true
    }
  }, [attachment.id, attachment.kind, service])

  if (attachment.kind !== 'image' || url === null) {
    return (
      <span className="attachment-chip__icon" aria-hidden="true">
        {attachment.kind === 'image' ? 'Image' : 'File'}
      </span>
    )
  }

  return (
    <img
      className="attachment-chip__thumbnail"
      src={url}
      alt={attachment.name}
      loading="lazy"
      referrerPolicy="no-referrer"
    />
  )
}

export function AttachmentChips({
  attachments,
  onRemove,
  label = 'Attachments',
}: AttachmentChipsProps) {
  if (attachments.length === 0) {
    return null
  }

  return (
    <ul className="attachment-chips" aria-label={label}>
      {attachments.map((attachment) => (
        <li key={attachment.id} className="attachment-chip" data-kind={attachment.kind}>
          <AttachmentThumbnail attachment={attachment} />
          <span className="attachment-chip__name" title={attachment.name}>
            {attachment.name}
          </span>
          {attachment.size !== null && (
            <span className="attachment-chip__size">{formatAttachmentSize(attachment.size)}</span>
          )}
          {onRemove && (
            <button
              type="button"
              className="attachment-chip__remove"
              onClick={() => onRemove(attachment.id)}
              aria-label={`Remove attachment ${attachment.name}`}
            >
              Remove
            </button>
          )}
        </li>
      ))}
    </ul>
  )
}

export function MessageAttachments({ parts }: { parts: readonly ContentPart[] }) {
  const attachments = attachmentViewsFromParts(parts)

  if (attachments.length === 0) {
    return null
  }

  return (
    <div className="message__attachments">
      <AttachmentChips attachments={attachments} label="Message attachments" />
    </div>
  )
}
