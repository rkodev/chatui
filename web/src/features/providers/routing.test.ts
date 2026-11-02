import { describe, expect, it } from 'vitest'
import type { ChatParams } from '../../domain'
import { buildChatRequestBody } from '../../providers/openrouterStream'
import {
  DEFAULT_ROUTING_PREFERENCES,
  buildProviderRouting,
  readRoutingPreferences,
  routingSummary,
  withRoutingPreferences,
  type RoutingPreferences,
} from './routing'

const params: ChatParams = {
  temperature: null,
  topP: null,
  maxOutputTokens: null,
  stop: [],
  reasoningEffort: null,
  streaming: true,
  extra: {},
}

const active: RoutingPreferences = {
  enabled: true,
  allowFallbacks: true,
  sort: 'price',
  denyDataCollection: true,
}

describe('OpenRouter routing preferences', () => {
  it('omits the provider object when routing is inactive', () => {
    expect(buildProviderRouting(DEFAULT_ROUTING_PREFERENCES)).toBeNull()
    expect(routingSummary(DEFAULT_ROUTING_PREFERENCES)).toBeNull()
    expect(withRoutingPreferences(params, DEFAULT_ROUTING_PREFERENCES).extra).toEqual({})
  })

  it('serializes the curated provider object', () => {
    expect(buildProviderRouting(active)).toEqual({
      allow_fallbacks: true,
      sort: 'price',
      data_collection: 'deny',
    })
  })

  it('serializes fallbacks off and each sort value', () => {
    expect(
      buildProviderRouting({
        enabled: true,
        allowFallbacks: false,
        sort: 'throughput',
        denyDataCollection: false,
      }),
    ).toEqual({ allow_fallbacks: false, sort: 'throughput' })

    expect(
      buildProviderRouting({
        enabled: true,
        allowFallbacks: true,
        sort: 'latency',
        denyDataCollection: false,
      }),
    ).toEqual({ allow_fallbacks: true, sort: 'latency' })
  })

  it('builds a routing summary only when active', () => {
    expect(routingSummary(active)).toBe(
      'Fallbacks on, cheapest provider, data collection denied',
    )
    expect(
      routingSummary({
        enabled: true,
        allowFallbacks: false,
        sort: 'latency',
        denyDataCollection: false,
      }),
    ).toBe('Fallbacks off, lowest latency')
  })

  it('stores preferences in conversation.params.extra.openrouter.provider', () => {
    const next = withRoutingPreferences(params, active)

    expect(next.extra).toEqual({
      openrouter: {
        provider: {
          allow_fallbacks: true,
          sort: 'price',
          data_collection: 'deny',
        },
      },
    })
    expect(readRoutingPreferences(next)).toEqual(active)
  })

  it('preserves other advanced OpenRouter fields when routing changes', () => {
    const withAdvanced: ChatParams = {
      ...params,
      extra: {
        openrouter: {
          models: ['author/fallback'],
          route: 'fallback',
          reasoning: { exclude: true },
        },
      },
    }

    const next = withRoutingPreferences(withAdvanced, active)
    expect(next.extra.openrouter).toMatchObject({
      models: ['author/fallback'],
      route: 'fallback',
      reasoning: { exclude: true },
      provider: {
        allow_fallbacks: true,
        sort: 'price',
        data_collection: 'deny',
      },
    })

    const cleared = withRoutingPreferences(next, DEFAULT_ROUTING_PREFERENCES)
    expect(cleared.extra.openrouter).toEqual({
      models: ['author/fallback'],
      route: 'fallback',
      reasoning: { exclude: true },
    })
  })

  it('serializes the stored provider object into the chat request body', () => {
    const next = withRoutingPreferences(params, active)
    const body = buildChatRequestBody({
      model: 'openai/gpt-4o-mini',
      system: null,
      messages: [{ role: 'user', parts: [{ type: 'text', text: 'Hello' }] }],
      params: next,
    })

    expect(body.provider).toEqual({
      allow_fallbacks: true,
      sort: 'price',
      data_collection: 'deny',
    })
  })
})
