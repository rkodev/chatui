import { describe, expect, it } from 'vitest'
import { conversationIdFromPathname } from './routeConversationId'

describe('conversationIdFromPathname', () => {
  it('extracts the id from a chat path', () => {
    expect(conversationIdFromPathname('/chat/conversation-1')).toBe('conversation-1')
    expect(conversationIdFromPathname('/chat/conversation-1/')).toBe('conversation-1')
  })

  it('decodes an encoded id', () => {
    expect(conversationIdFromPathname('/chat/conversation%201')).toBe('conversation 1')
  })

  it('returns null for non-chat paths', () => {
    expect(conversationIdFromPathname('/')).toBeNull()
    expect(conversationIdFromPathname('/settings/providers')).toBeNull()
    expect(conversationIdFromPathname('/chat')).toBeNull()
    expect(conversationIdFromPathname('/chat/a/b')).toBeNull()
  })
})
