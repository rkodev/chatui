import { describe, expect, it } from 'vitest'
import type { ChatError } from '../../domain'
import { describeChatError } from './chatErrorCopy'

function chatError(overrides: Partial<ChatError>): ChatError {
  return {
    code: 'network',
    message: 'Could not reach the provider.',
    providerId: 'openai',
    status: null,
    retryable: false,
    retryAfterSeconds: null,
    requestId: null,
    details: null,
    at: '2026-01-01T00:00:00.000Z',
    ...overrides,
  }
}

describe('describeChatError', () => {
  it('uses the provider-specific CORS copy from the normalized error', () => {
    const copy = describeChatError(
      chatError({
        code: 'cors',
        message:
          'Could not reach OpenAI. The browser blocked the request, which may be a CORS restriction. Try the Android app or a self-hosted proxy.',
      }),
    )

    expect(copy.title).toBe('Could not reach the provider')
    expect(copy.message).toContain('OpenAI')
    expect(copy.message).toContain('Android app')
    expect(copy.message).toContain('self-hosted proxy')
  })

  it('keeps generic network copy distinct from CORS', () => {
    const copy = describeChatError(chatError({ code: 'network' }))

    expect(copy.message).toContain('browser CORS restriction')
    expect(copy.message).toContain('Android app')
  })
})
