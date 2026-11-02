import type { Message } from '../../domain'
import './providers.css'

export interface MessageModelSnapshotProps {
  message: Pick<Message, 'providerId' | 'modelId' | 'modelLabel'>
}

/**
 * Assistant-message provider/model snapshot.
 *
 * Snapshots are written when the message is appended and never rewritten when
 * the conversation switches models later.
 */
export function MessageModelSnapshot({ message }: MessageModelSnapshotProps) {
  if (!message.modelLabel && !message.modelId) {
    return null
  }

  return (
    <p className="message-model-snapshot">
      <span>Model: {message.modelLabel ?? message.modelId}</span>
      {message.providerId && <span>Provider: {message.providerId}</span>}
    </p>
  )
}
