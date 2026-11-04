import type { Message } from '../../domain'

export interface MessageItemProps {
  message: Message
  showTokenUsage: boolean
  /** Global remote-image policy from settings; defaults to blocked. */
  loadRemoteImages?: boolean
  onRetry?: (message: Message) => void
  onRegenerate?: (message: Message) => void
  onEditLastUser?: (message: Message, text: string) => void
  canRegenerate?: boolean
  canEditLastUser?: boolean
  /** True only for the row backed by the active streaming store. */
  isActiveStream?: boolean
}

/**
 * Memoization comparator keyed by message id plus the content revision fields
 * that can change after persistence.
 */
export function messageItemPropsEqual(previous: MessageItemProps, next: MessageItemProps): boolean {
  return (
    previous.message.id === next.message.id &&
    previous.message.updatedAt === next.message.updatedAt &&
    previous.message.status === next.message.status &&
    previous.message.error === next.message.error &&
    previous.message.usage === next.message.usage &&
    previous.message.parts === next.message.parts &&
    previous.showTokenUsage === next.showTokenUsage &&
    previous.loadRemoteImages === next.loadRemoteImages &&
    previous.onRetry === next.onRetry &&
    previous.onRegenerate === next.onRegenerate &&
    previous.onEditLastUser === next.onEditLastUser &&
    previous.canRegenerate === next.canRegenerate &&
    previous.canEditLastUser === next.canEditLastUser &&
    previous.isActiveStream === next.isActiveStream
  )
}
