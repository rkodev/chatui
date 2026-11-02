import { describe, expect, it } from 'vitest'
import { createTestConversation } from '../providers/testSupport'
import { buildChatRequest } from './chatRequest'
import { createTestMessage } from './testSupport'

describe('buildChatRequest', () => {
  it('sends history in sequence order and skips empty or deleted messages', () => {
    const conversation = createTestConversation({ systemPrompt: 'Be concise.' })
    const messages = [
      createTestMessage({ id: 'm3', sequence: 3, role: 'assistant', parts: [{ type: 'text', text: 'Hi' }] }),
      createTestMessage({ id: 'm1', sequence: 1, role: 'user', parts: [{ type: 'text', text: 'Hello' }] }),
      createTestMessage({ id: 'm2', sequence: 2, role: 'assistant', parts: [] }),
      createTestMessage({ id: 'm4', sequence: 4, role: 'user', parts: [{ type: 'text', text: 'Deleted' }], deletedAt: '2026-01-01T00:00:00.000Z' }),
      createTestMessage({ id: 'm5', sequence: 5, role: 'system', parts: [{ type: 'text', text: 'System' }] }),
    ]

    const request = buildChatRequest(conversation, 'openai/gpt-4o-mini', messages)

    expect(request.model).toBe('openai/gpt-4o-mini')
    expect(request.system).toBe('Be concise.')
    expect(request.messages).toEqual([
      { role: 'user', parts: [{ type: 'text', text: 'Hello' }] },
      { role: 'assistant', parts: [{ type: 'text', text: 'Hi' }] },
    ])
    expect(request.params).toBe(conversation.params)
  })

  it('omits a blank system prompt and reasoning parts', () => {
    const conversation = createTestConversation({ systemPrompt: '   ' })
    const messages = [
      createTestMessage({
        sequence: 1,
        role: 'assistant',
        parts: [
          { type: 'reasoning', text: 'private reasoning' },
          { type: 'text', text: 'Visible answer' },
        ],
      }),
    ]

    const request = buildChatRequest(conversation, 'openai/gpt-4o-mini', messages)

    expect(request.system).toBeNull()
    expect(request.messages).toEqual([
      { role: 'assistant', parts: [{ type: 'text', text: 'Visible answer' }] },
    ])
  })
})
