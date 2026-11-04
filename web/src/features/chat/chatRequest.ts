import type { Conversation, Message } from '../../domain'
import type {
  NormalizedChatMessage,
  NormalizedChatRequest,
} from '../../providers/openrouterStream'

function textFor(message: Message): string {
  return message.parts
    .map((part) => (part.type === 'text' ? part.text : ''))
    .filter((text) => text !== '')
    .join('\n')
}

function toNormalizedMessage(message: Message): NormalizedChatMessage | null {
  if (message.deletedAt !== null) {
    return null
  }

  if (message.role !== 'user' && message.role !== 'assistant' && message.role !== 'tool') {
    return null
  }

  const text = textFor(message)

  if (text.trim() === '') {
    return null
  }

  return {
    role: message.role,
    parts: [{ type: 'text', text }],
  }
}

/**
 * Build the normalized request for the next assistant response.
 *
 * History is sent in sequence order. Empty assistant placeholders, deleted
 * messages, and system messages are skipped; the conversation system prompt is
 * sent separately. Reasoning parts are presentation-only and are not replayed
 * to the provider.
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
