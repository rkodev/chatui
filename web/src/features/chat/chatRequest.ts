import type { ContentPart, Conversation, Message } from '../../domain'
import type { NormalizedChatMessage, NormalizedChatRequest } from '../../providers/openrouterStream'

function partsFor(message: Message): ContentPart[] {
  const parts: ContentPart[] = []

  for (const part of message.parts) {
    if (part.type === 'text') {
      if (part.text !== '') {
        parts.push(part)
      }
    } else if (part.type === 'image' || part.type === 'file') {
      parts.push(part)
    }
  }

  return parts
}

function toNormalizedMessage(message: Message): NormalizedChatMessage | null {
  if (message.deletedAt !== null) {
    return null
  }

  if (message.role !== 'user' && message.role !== 'assistant' && message.role !== 'tool') {
    return null
  }

  const parts = partsFor(message)

  if (parts.length === 0) {
    return null
  }

  return {
    role: message.role,
    parts,
  }
}

/** Attachment ids referenced by a message's image and file parts. */
export function attachmentIdsForMessage(message: Message): string[] {
  return message.parts
    .filter((part) => part.type === 'image' || part.type === 'file')
    .map((part) => part.attachmentId)
}

/**
 * Build the normalized request for the next assistant response.
 *
 * History is sent in sequence order. Empty assistant placeholders, deleted
 * messages, and system messages are skipped; the conversation system prompt is
 * sent separately. Reasoning parts are presentation-only and are not replayed
 * to the provider. Image and file parts keep their attachment ids so the chat
 * service can resolve bytes into a provider-specific request.
 */
export function buildChatRequest(
  conversation: Conversation,
  modelRemoteId: string,
  messages: readonly Message[],
): NormalizedChatRequest {
  const normalized = [...messages]
    .filter((message) => message.conversationId === conversation.id)
    .sort((left, right) => left.sequence - right.sequence)
    .map(toNormalizedMessage)
    .filter((message): message is NormalizedChatMessage => message !== null)

  return {
    model: modelRemoteId,
    system: conversation.systemPrompt.trim() === '' ? null : conversation.systemPrompt,
    messages: normalized,
    params: conversation.params,
  }
}
