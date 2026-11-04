import type { Message } from '../../domain'

/** Exact text content of a message, with text parts separated by blank lines. */
export function messageText(message: Message): string {
  return message.parts
    .filter((part): part is Extract<Message['parts'][number], { type: 'text' }> => part.type === 'text')
    .map((part) => part.text)
    .join('\n\n')
}
