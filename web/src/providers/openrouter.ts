import {
  getDefaultCatalogCache,
  isCatalogFresh,
  mapOpenRouterKey,
  mapOpenRouterModels,
  openRouterCatalogCacheKey,
  type CatalogResult,
  type ModelInfo,
  type ProviderKeyInfo,
} from './openrouterCatalog'
import {
  ProviderRequestError,
  loadCatalog as loadGenericCatalog,
  listModels as listGenericModels,
  refreshCatalog as refreshGenericCatalog,
  validateKey as validateGenericKey,
  type Attribution,
  type ListModelsOptions,
  type ProviderContext,
} from './openaiCompatible'
import { OPENROUTER_PRESET } from './openrouterPreset'
import { streamChat } from './openrouterStream'
import {
  loadCatalog as loadAnthropicCatalog,
  listModels as listAnthropicModels,
  refreshCatalog as refreshAnthropicCatalog,
  validateKey as validateAnthropicKey,
} from './anthropic'
import {
  loadCatalog as loadGeminiCatalog,
  listModels as listGeminiModels,
  refreshCatalog as refreshGeminiCatalog,
  validateKey as validateGeminiKey,
} from './gemini'
import {
  loadCatalog as loadOllamaCatalog,
  listModels as listOllamaModels,
  refreshCatalog as refreshOllamaCatalog,
  validateKey as validateOllamaKey,
} from './ollama'

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
export type {
  Attribution,
  ListModelsOptions,
  OpenAICompatiblePreset,
  ProviderContext,
} from './openaiCompatible'

/** Attribution headers supplied by runtime config. */
export type OpenRouterAttribution = Attribution

function withPreset(context: ProviderContext): ProviderContext {
  return context.preset ? context : { ...context, preset: OPENROUTER_PRESET }
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
  if (context.preset?.kind === 'anthropic') {
    return validateAnthropicKey(context, signal)
  }

  if (context.preset?.kind === 'google-gemini') {
    return validateGeminiKey(context, signal)
  }

  if (context.preset?.modelDiscovery.format === 'ollama-tags') {
    return validateOllamaKey(context, signal)
  }

  return validateGenericKey(withPreset(context), signal)
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
  if (context.preset?.kind === 'anthropic') {
    return loadAnthropicCatalog(context, signal, {
      ...options,
      preset: options.preset ?? context.preset,
    })
  }

  if (context.preset?.kind === 'google-gemini') {
    return loadGeminiCatalog(context, signal, {
      ...options,
      preset: options.preset ?? context.preset,
    })
  }

  if (context.preset?.modelDiscovery.format === 'ollama-tags') {
    return loadOllamaCatalog(context, signal, {
      ...options,
      preset: options.preset ?? context.preset,
    })
  }

  return loadGenericCatalog(withPreset(context), signal, {
    ...options,
    preset: options.preset ?? context.preset ?? OPENROUTER_PRESET,
  })
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
  if (context.preset?.kind === 'anthropic') {
    return listAnthropicModels(context, signal, {
      ...options,
      preset: options.preset ?? context.preset,
    })
  }

  if (context.preset?.kind === 'google-gemini') {
    return listGeminiModels(context, signal, {
      ...options,
      preset: options.preset ?? context.preset,
    })
  }

  if (context.preset?.modelDiscovery.format === 'ollama-tags') {
    return listOllamaModels(context, signal, {
      ...options,
      preset: options.preset ?? context.preset,
    })
  }

  return listGenericModels(withPreset(context), signal, {
    ...options,
    preset: options.preset ?? context.preset ?? OPENROUTER_PRESET,
  })
}

/** Force a catalog refresh while preserving last-known-good behavior. */
export async function refreshCatalog(
  context: ProviderContext,
  signal: AbortSignal,
  options: Omit<ListModelsOptions, 'forceRefresh'> = {},
): Promise<CatalogResult> {
  if (context.preset?.kind === 'anthropic') {
    return refreshAnthropicCatalog(context, signal, {
      ...options,
      preset: options.preset ?? context.preset,
    })
  }

  if (context.preset?.kind === 'google-gemini') {
    return refreshGeminiCatalog(context, signal, {
      ...options,
      preset: options.preset ?? context.preset,
    })
  }

  if (context.preset?.modelDiscovery.format === 'ollama-tags') {
    return refreshOllamaCatalog(context, signal, {
      ...options,
      preset: options.preset ?? context.preset,
    })
  }

  return refreshGenericCatalog(withPreset(context), signal, {
    ...options,
    preset: options.preset ?? context.preset ?? OPENROUTER_PRESET,
  })
}

/** OpenRouter adapter methods implemented by this task. */
export const openRouterAdapter = {
  kind: 'openai-compatible' as const,
  validateKey,
  listModels,
  streamChat,
}

// Keep the OpenRouter-specific catalog helpers available to existing callers.
export {
  getDefaultCatalogCache,
  isCatalogFresh,
  mapOpenRouterKey,
  mapOpenRouterModels,
  openRouterCatalogCacheKey,
  ProviderRequestError,
}
