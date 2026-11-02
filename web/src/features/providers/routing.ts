import type { ChatParams } from '../../domain'

/**
 * Curated OpenRouter routing preferences.
 *
 * The values live in `conversation.params.extra.openrouter.provider`, which is
 * the same passthrough object the request builder already serializes. When
 * `enabled` is false the provider object is omitted entirely so OpenRouter
 * uses its defaults.
 */

export type RoutingSort = 'price' | 'throughput' | 'latency'

export interface RoutingPreferences {
  enabled: boolean
  allowFallbacks: boolean
  sort: RoutingSort | null
  denyDataCollection: boolean
}

export const DEFAULT_ROUTING_PREFERENCES: RoutingPreferences = {
  enabled: false,
  allowFallbacks: true,
  sort: null,
  denyDataCollection: false,
}

const ROUTING_SORTS: readonly RoutingSort[] = ['price', 'throughput', 'latency']

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isRoutingSort(value: unknown): value is RoutingSort {
  return typeof value === 'string' && (ROUTING_SORTS as readonly string[]).includes(value)
}

/** Build the OpenRouter `provider` object, or null when routing is inactive. */
export function buildProviderRouting(
  preferences: RoutingPreferences,
): Record<string, unknown> | null {
  if (!preferences.enabled) {
    return null
  }

  const provider: Record<string, unknown> = {
    allow_fallbacks: preferences.allowFallbacks,
  }

  if (preferences.sort !== null) {
    provider.sort = preferences.sort
  }

  if (preferences.denyDataCollection) {
    provider.data_collection = 'deny'
  }

  return provider
}

/** Human-readable routing summary, or null when no preference is active. */
export function routingSummary(preferences: RoutingPreferences): string | null {
  if (!preferences.enabled) {
    return null
  }

  const parts = [preferences.allowFallbacks ? 'Fallbacks on' : 'Fallbacks off']

  if (preferences.sort === 'price') {
    parts.push('cheapest provider')
  } else if (preferences.sort === 'throughput') {
    parts.push('highest throughput')
  } else if (preferences.sort === 'latency') {
    parts.push('lowest latency')
  }

  if (preferences.denyDataCollection) {
    parts.push('data collection denied')
  }

  return parts.join(', ')
}

/** Read routing preferences from conversation parameters. */
export function readRoutingPreferences(params: ChatParams): RoutingPreferences {
  const extra = params.extra?.openrouter

  if (!isRecord(extra) || !isRecord(extra.provider)) {
    return { ...DEFAULT_ROUTING_PREFERENCES }
  }

  const provider = extra.provider

  return {
    enabled: true,
    allowFallbacks: provider.allow_fallbacks !== false,
    sort: isRoutingSort(provider.sort) ? provider.sort : null,
    denyDataCollection: provider.data_collection === 'deny',
  }
}

/**
 * Return a copy of `params` with routing preferences applied.
 *
 * Other advanced OpenRouter fields (`models`, `route`, `reasoning`) are
 * preserved. Disabling routing removes only the curated `provider` object.
 */
export function withRoutingPreferences(
  params: ChatParams,
  preferences: RoutingPreferences,
): ChatParams {
  const extra = { ...params.extra }
  const openrouter = isRecord(extra.openrouter) ? { ...extra.openrouter } : {}
  const provider = buildProviderRouting(preferences)

  if (provider === null) {
    delete openrouter.provider
  } else {
    openrouter.provider = provider
  }

  if (Object.keys(openrouter).length > 0) {
    extra.openrouter = openrouter
  } else {
    delete extra.openrouter
  }

  return {
    ...params,
    stop: [...params.stop],
    extra,
  }
}
