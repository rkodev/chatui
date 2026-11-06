import { describe, expect, it } from 'vitest'
import type { ChatParams } from '../../domain'
import {
  extraNamespaceForPreset,
  parseProviderExtras,
  pruneExtrasForPreset,
  readProviderExtras,
  stringifyProviderExtras,
  unsupportedParameterNote,
  unsupportedParameters,
  withProviderExtras,
} from './parameterSupport'

const params: ChatParams = {
  temperature: 0.7,
  topP: 0.9,
  maxOutputTokens: 512,
  stop: ['END'],
  reasoningEffort: 'high',
  streaming: true,
  extra: {},
}

describe('parameter support', () => {
  it('uses the provider preset id as the shared extras namespace', () => {
    expect(extraNamespaceForPreset('openrouter')).toBe('openrouter')
    expect(extraNamespaceForPreset('google')).toBe('google')
    expect(extraNamespaceForPreset('custom')).toBe('custom')
  })

  it('reads and writes one provider namespace without touching others', () => {
    const first = withProviderExtras(params, 'openrouter', { provider: { sort: 'price' } })
    const second = withProviderExtras(first, 'anthropic', { metadata: { user_id: 'abc' } })

    expect(readProviderExtras(second, 'openrouter')).toEqual({ provider: { sort: 'price' } })
    expect(readProviderExtras(second, 'anthropic')).toEqual({ metadata: { user_id: 'abc' } })
    expect(params.extra).toEqual({})
  })

  it('removes a namespace when its extras become empty', () => {
    const withExtras = withProviderExtras(params, 'openrouter', { provider: {} })
    expect(withProviderExtras(withExtras, 'openrouter', {}).extra).toEqual({})
  })

  it('serializes only the selected namespace', () => {
    const conversationParams = withProviderExtras(
      withProviderExtras(params, 'openrouter', { route: 'fallback' }),
      'anthropic',
      { beta: true },
    )

    expect(JSON.parse(stringifyProviderExtras(conversationParams, 'openrouter'))).toEqual({
      route: 'fallback',
    })
    expect(stringifyProviderExtras(params, 'openrouter')).toBe('')
  })

  it('prunes extras that belong to a different provider on model switch', () => {
    const extras = { openrouter: { route: 'fallback' }, anthropic: { beta: true } }
    expect(pruneExtrasForPreset(extras, 'anthropic')).toEqual({ anthropic: { beta: true } })
    expect(pruneExtrasForPreset(extras, 'google')).toEqual({})
  })

  it('parses empty and object JSON', () => {
    expect(parseProviderExtras('   ')).toEqual({ ok: true, value: {} })
    expect(parseProviderExtras('{"provider":{"sort":"price"}}')).toEqual({
      ok: true,
      value: { provider: { sort: 'price' } },
    })
  })

  it('rejects invalid JSON and non-object roots', () => {
    expect(parseProviderExtras('{nope')).toMatchObject({
      ok: false,
      message: 'Provider extras must be valid JSON.',
    })
    expect(parseProviderExtras('[1,2]')).toMatchObject({
      ok: false,
      message: 'Provider extras must be a JSON object.',
    })
  })

  it('rejects protected fields', () => {
    expect(
      parseProviderExtras('{"model":"other","stream":false,"route":"fallback"}'),
    ).toMatchObject({
      ok: false,
      message: 'Provider extras cannot override protected fields: model, stream.',
    })
  })

  it('lists unsupported values but leaves them in the stored parameters', () => {
    expect(
      unsupportedParameters(params, ['temperature', 'max_tokens']).map((item) => item.key),
    ).toEqual(['topP', 'stop', 'reasoningEffort'])
    expect(unsupportedParameterNote(params, ['temperature', 'max_tokens'])).toBe(
      'This model ignores these values, so they are not sent: Top P, Stop sequences, Reasoning effort.',
    )
    expect(unsupportedParameterNote(params, [])).toContain('Temperature')
    expect(params.temperature).toBe(0.7)
  })

  it('returns no note when every populated value is supported', () => {
    expect(
      unsupportedParameterNote(params, ['temperature', 'top_p', 'max_tokens', 'stop', 'reasoning']),
    ).toBeNull()
  })
})
