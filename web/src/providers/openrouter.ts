import type { ChatErrorCode, Provider } from '../domain'
import {
  getDefaultCatalogCache,
  isCatalogFresh,
  mapOpenRouterKey,
  mapOpenRouterModels,
  openRouterCatalogCacheKey,
  type CatalogCache,
  type CatalogResult,
  type CatalogSnapshot,
  type ModelInfo,
  type ProviderKeyInfo,
} from './openrouterCatalog'
import { OpenRouterRequestError, streamChat } from './openrouterStream'

export type {
  CatalogCache,
  CatalogResult,
  CatalogSnapshot,
  ModelInfo,
  ProviderKeyInfo,
} from './openrouterCatalog'
export { OpenRouterRequestError, streamChat } from './openrouterStream'
export type {
  NormalizedChatMessage,
  NormalizedChatRequest,
  StreamChatOptions,
  StreamEvent,
} from './openrouterStream'

/** Attribution headers supplied by runtime config. */
export interface OpenRouterAttribution {
  origin: string
  title: string
}

/**
 * Everything the OpenRouter adapter needs for one request.
 *
 * `apiKey` is supplied by the caller from the secret store and must never be
 * logged. `attribution` comes from runtime config and must not contain personal
 * data.
 */
export interface ProviderContext {
  provider: Pick<Provider, 'id' | 'baseUrl'>
  apiKey: string | null
  fetch: typeof fetch
  attribution?: OpenRouterAttribution
}

export interface ListModelsOptions {
  cache?: CatalogCache
  now?: () => number
  forceRefresh?: boolean
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function joinUrl(baseUrl: string, path: string): string {
  return `${baseUrl.replace(/\/+$/, '')}/${path.replace(/^\/+/, '')}`
}

function buildHeaders(context: ProviderContext, includeAuth: boolean): Headers {
  const headers = new Headers()
  headers.set('Accept', 'application/json')

  if (includeAuth && context.apiKey) {
    headers.set('Authorization', `Bearer ${context.apiKey}`)
  }

  if (context.attribution) {
    headers.set('HTTP-Referer', context.attribution.origin)
    headers.set('X-Title', context.attribution.title)
  }

  return headers
}

function redactSecret(message: string, secret: string | null): string {
  if (!secret) {
    return message
  }

  return message.split(secret).join('[redacted]')
}

function statusToCode(status: number): ChatErrorCode {
  if (status === 401) {
    return 'auth'
  }
  if (status === 402) {
    return 'quota'
  }
  if (status === 403) {
    return 'permission'
  }
  if (status === 404) {
    return 'model_not_found'
  }
  if (status === 429) {
    return 'rate_limit'
  }
  return 'provider'
}

function isRetryableStatus(status: number): boolean {
  return status === 429 || status >= 500
}

function parseRetryAfter(value: string | null): number | null {
  if (value === null || value.trim() === '') {
    return null
  }

  const seconds = Number(value)
  return Number.isFinite(seconds) && seconds >= 0 ? seconds : null
}

async function readErrorMessage(response: Response): Promise<string> {
  let text: string

  try {
    text = await response.text()
  } catch {
    return `OpenRouter request failed with HTTP ${response.status}`
  }

  if (text.trim() !== '') {
    try {
      const parsed: unknown = JSON.parse(text)
      if (isRecord(parsed) && isRecord(parsed.error) && typeof parsed.error.message === 'string') {
        return parsed.error.message
      }
      if (isRecord(parsed) && typeof parsed.message === 'string') {
        return parsed.message
      }
    } catch {
      // Fall through to the bounded raw text.
    }

    return text.slice(0, 500)
  }

  return `OpenRouter request failed with HTTP ${response.status}`
}

async function requestJson(
  context: ProviderContext,
  path: string,
  options: { includeAuth: boolean; signal: AbortSignal },
): Promise<unknown> {
  const url = joinUrl(context.provider.baseUrl, path)
  let response: Response

  try {
    response = await context.fetch(url, {
      method: 'GET',
      headers: buildHeaders(context, options.includeAuth),
      signal: options.signal,
    })
  } catch (error) {
    if (options.signal.aborted) {
      throw new OpenRouterRequestError('cancelled', 'OpenRouter request was cancelled.', {
        providerId: context.provider.id,
        cause: error,
      })
    }

    throw new OpenRouterRequestError('network', 'Could not reach OpenRouter.', {
      providerId: context.provider.id,
      retryable: true,
      cause: error,
    })
  }

  if (!response.ok) {
    const message = redactSecret(await readErrorMessage(response), context.apiKey)
    throw new OpenRouterRequestError(statusToCode(response.status), message, {
      providerId: context.provider.id,
      status: response.status,
      retryable: isRetryableStatus(response.status),
      retryAfterSeconds: parseRetryAfter(response.headers.get('Retry-After')),
    })
  }

  try {
    return await response.json()
  } catch (error) {
    throw new OpenRouterRequestError('parse', 'OpenRouter returned invalid JSON.', {
      providerId: context.provider.id,
      status: response.status,
      cause: error,
    })
  }
}

/**
 * Validate an OpenRouter key with `GET /key`.
 *
 * The key is sent only in the `Authorization` header. It is never placed in the
 * URL, and provider error messages are redacted before they reach the caller.
 */
export async function validateKey(
  context: ProviderContext,
  signal: AbortSignal,
): Promise<ProviderKeyInfo> {
  if (!context.apiKey) {
    throw new OpenRouterRequestError('auth', 'An OpenRouter API key is required.', {
      providerId: context.provider.id,
    })
  }

  const payload = await requestJson(context, '/key', {
    includeAuth: true,
    signal,
  })

  try {
    return mapOpenRouterKey(payload)
  } catch (error) {
    throw new OpenRouterRequestError('parse', 'OpenRouter /key response was not valid.', {
      providerId: context.provider.id,
      cause: error,
    })
  }
}

async function fetchOpenRouterModels(
  context: ProviderContext,
  signal: AbortSignal,
): Promise<ModelInfo[]> {
  const payload = await requestJson(context, '/models', {
    includeAuth: false,
    signal,
  })

  try {
    return mapOpenRouterModels(payload)
  } catch (error) {
    throw new OpenRouterRequestError('parse', 'OpenRouter /models response was not valid.', {
      providerId: context.provider.id,
      cause: error,
    })
  }
}

/**
 * Load the OpenRouter catalog with a 24-hour cache and last-known-good
 * fallback.
 *
 * A fresh cache entry is returned without a network request. `forceRefresh`
 * bypasses the TTL. When a refresh fails, the last successful snapshot is
 * returned with `stale: true` and `refreshError` set. If no snapshot exists,
 * the refresh error is thrown.
 */
export async function loadCatalog(
  context: ProviderContext,
  signal: AbortSignal,
  options: ListModelsOptions = {},
): Promise<CatalogResult> {
  const cache = options.cache ?? getDefaultCatalogCache()
  const now = options.now ?? Date.now
  const cacheKey = openRouterCatalogCacheKey(context.provider.id)

  let cached: CatalogSnapshot | null
  try {
    cached = await cache.read(cacheKey)
  } catch {
    cached = null
  }

  if (cached && !options.forceRefresh && isCatalogFresh(cached, now())) {
    return {
      models: cached.models,
      fetchedAt: cached.fetchedAt,
      fromCache: true,
      stale: false,
      refreshError: null,
    }
  }

  try {
    const models = await fetchOpenRouterModels(context, signal)
    const snapshot: CatalogSnapshot = { fetchedAt: now(), models }

    try {
      await cache.write(cacheKey, snapshot)
    } catch {
      // A cache write failure must not discard a successful refresh.
    }

    return {
      models,
      fetchedAt: snapshot.fetchedAt,
      fromCache: false,
      stale: false,
      refreshError: null,
    }
  } catch (error) {
    const refreshError = error instanceof Error ? error : new Error(String(error))

    if (signal.aborted) {
      throw refreshError
    }

    if (cached) {
      return {
        models: cached.models,
        fetchedAt: cached.fetchedAt,
        fromCache: true,
        stale: true,
        refreshError,
      }
    }

    throw refreshError
  }
}

/**
 * Adapter-compatible catalog method.
 *
 * Returns the normalized models. Use `loadCatalog` when the caller needs the
 * cache/staleness/refresh-error flags.
 */
export async function listModels(
  context: ProviderContext,
  signal: AbortSignal,
  options: ListModelsOptions = {},
): Promise<ModelInfo[]> {
  const result = await loadCatalog(context, signal, options)
  return result.models
}

/** Force a catalog refresh while preserving last-known-good behavior. */
export async function refreshCatalog(
  context: ProviderContext,
  signal: AbortSignal,
  options: Omit<ListModelsOptions, 'forceRefresh'> = {},
): Promise<CatalogResult> {
  return loadCatalog(context, signal, { ...options, forceRefresh: true })
}

/** OpenRouter adapter methods implemented by this task. */
export const openRouterAdapter = {
  kind: 'openai-compatible' as const,
  validateKey,
  listModels,
  streamChat,
}
