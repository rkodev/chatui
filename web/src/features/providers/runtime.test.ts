import { describe, expect, it } from 'vitest'
import { buildProviderContext } from './runtime'
import { createTestProvider } from './testSupport'

const attribution = { origin: 'https://chat.example.com', title: 'ChatUI' }

describe('buildProviderContext', () => {
  it('carries the OpenRouter preset, auth, headers, and key', () => {
    const provider = createTestProvider()
    const context = buildProviderContext(provider, { attribution }, 'sk-test-key')

    expect(context.provider).toEqual({
      id: provider.id,
      baseUrl: provider.baseUrl,
    })
    expect(context.apiKey).toBe('sk-test-key')
    expect(context.preset?.id).toBe('openrouter')
    expect(context.auth).toEqual({
      type: 'bearer',
      headerName: 'Authorization',
      scheme: 'Bearer',
    })
    expect(context.headers).toEqual({})
    expect(context.attribution).toEqual(attribution)
    expect(JSON.stringify(context.headers)).not.toContain('sk-test-key')
  })

  it('uses the provider auth type and merges non-secret provider headers', () => {
    const provider = createTestProvider({
      presetId: 'openai',
      label: 'OpenAI',
      baseUrl: 'https://api.openai.com/v1',
      authType: 'x-api-key',
      headers: { 'X-Organization': 'org-test' },
    })
    const context = buildProviderContext(provider, {}, 'sk-test-key')

    expect(context.preset?.id).toBe('openai')
    expect(context.auth).toEqual({ type: 'x-api-key', headerName: 'x-api-key' })
    expect(context.headers).toEqual({ 'X-Organization': 'org-test' })
    expect(context.attribution).toBeUndefined()
  })

  it('keeps an unknown preset usable with the provider record fields', () => {
    const provider = createTestProvider({
      presetId: 'unknown',
      baseUrl: 'https://example.com/v1',
      authType: 'none',
      headers: { 'X-Custom': 'value' },
    })
    const context = buildProviderContext(provider, {}, null)

    expect(context.preset).toBeUndefined()
    expect(context.auth).toEqual({ type: 'none' })
    expect(context.headers).toEqual({ 'X-Custom': 'value' })
    expect(context.apiKey).toBeNull()
  })
})
