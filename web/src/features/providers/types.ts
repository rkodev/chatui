import type {
  InputModality,
  Model,
  ModelCapability,
  ModelPricing,
  OutputModality,
  Provider,
  ProviderAuthType,
} from '../../domain'
import type {
  CatalogCache,
  ListModelsOptions,
  ModelInfo,
  OpenRouterAttribution,
  ProviderContext,
  ProviderKeyInfo,
} from '../../providers/openrouter'
import type { SecretMode } from '../../storage/SecretStore'

/**
 * Minimal provider persistence surface used by the services.
 *
 * `ProviderRepository` satisfies this interface. Tests can inject a fake
 * without opening IndexedDB.
 */
export interface ProviderStore {
  create(provider: Provider): Promise<Provider>
  get(id: string): Promise<Provider | null>
  list(options?: { enabled?: boolean }): Promise<Provider[]>
  update(id: string, patch: Partial<Omit<Provider, 'id' | 'createdAt'>>): Promise<Provider>
  delete(id: string): Promise<void>
}

/** Minimal model persistence surface used by the services. */
export interface ModelStore {
  create(model: Model): Promise<Model>
  get(id: string): Promise<Model | null>
  list(options?: { providerId?: string }): Promise<Model[]>
  update(id: string, patch: Partial<Omit<Model, 'id' | 'createdAt'>>): Promise<Model>
  delete(id: string): Promise<void>
}

/** Minimal secret-store surface. Plaintext never leaves this boundary. */
export interface SecretStoreLike {
  setSecret(id: string, value: string, mode: SecretMode): Promise<void>
  getSecret(id: string): Promise<string | null>
  deleteSecret(id: string): Promise<void>
}

/**
 * Adapter surface used by the provider and model services.
 *
 * `openRouterAdapter` satisfies this interface. Tests can inject a fake that
 * returns fixture data without a network request.
 */
export interface ProviderAdapter {
  validateKey(context: ProviderContext, signal: AbortSignal): Promise<ProviderKeyInfo>
  listModels(
    context: ProviderContext,
    signal: AbortSignal,
    options?: ListModelsOptions,
  ): Promise<ModelInfo[]>
}

/** Runtime dependencies shared by provider and model services. */
export interface ProviderRuntime {
  fetch?: typeof fetch
  attribution?: OpenRouterAttribution
  catalogCache?: CatalogCache
  now?: () => number
}

export interface ProviderServiceOptions extends ProviderRuntime {
  providers: ProviderStore
  secrets: SecretStoreLike
  adapter: ProviderAdapter
  nowIso?: () => string
  newId?: () => string
  /** Runtime allow-list from `/config.json`. Defaults to the M2 preset list. */
  enabledPresetIds?: readonly string[]
  /** Whether Custom provider creation is enabled in this deployment. */
  allowCustomProviders?: boolean
}

export interface ModelServiceOptions extends ProviderRuntime {
  providers: ProviderStore
  models: ModelStore
  adapter: ProviderAdapter
  nowIso?: () => string
  newId?: () => string
}

export interface CreateOpenRouterProviderInput {
  apiKey: string
  label?: string
  /** Defaults to true, matching the spec's convenience default. */
  rememberKeys?: boolean
}

/** Create a provider from any enabled OpenAI-compatible contract preset. */
export interface CreatePresetProviderInput {
  presetId: string
  apiKey?: string
  label?: string
  /** Defaults to true, matching the spec's convenience default. */
  rememberKeys?: boolean
  /** Optional non-secret headers. Authentication headers are rejected. */
  headers?: Record<string, string>
}

/**
 * Create a Custom OpenAI-compatible provider.
 *
 * The base URL and auth style are required. The key is written only to the
 * secret store; `headers` may contain non-secret headers only.
 */
export interface CreateCustomProviderInput {
  label?: string
  baseUrl: string
  authType: ProviderAuthType
  apiKey?: string
  /** Defaults to true, matching the spec's convenience default. */
  rememberKeys?: boolean
  /** Optional non-secret headers. Authentication headers are rejected. */
  headers?: Record<string, string>
}

/**
 * Non-secret `/key` metadata cached on the provider record.
 *
 * The key itself is never part of this shape. `validatedAt` records when the
 * metadata was last refreshed.
 */
export interface ProviderKeyMetadata extends ProviderKeyInfo {
  validatedAt: string
}

export interface DefaultModelSelection {
  modelId: string | null
  remoteId: string | null
  notice: string | null
}

export interface RefreshModelsResult {
  models: Model[]
  created: number
  updated: number
  /** True when discovery failed and cached or fallback models were used. */
  degraded?: boolean
  /** Non-blocking notice for the UI. Never blocks sending. */
  notice?: string | null
  /** Discovery failure retained for diagnostics; never rendered raw. */
  refreshError?: Error | null
}

export interface ManualModelInput {
  remoteId: string
  label?: string
  contextWindow?: number | null
  maxOutputTokens?: number | null
  capabilities?: ModelCapability[]
  pricing?: ModelPricing | null
  canonicalSlug?: string | null
  inputModalities?: InputModality[]
  outputModalities?: OutputModality[]
  supportedParameters?: string[]
  metadata?: Record<string, unknown> | null
}

export type ProviderServiceErrorCode = 'not_found' | 'missing_key' | 'invalid_input' | 'storage'

/**
 * Service-level failure that is safe to show to the UI.
 *
 * Adapter failures (`OpenRouterRequestError`) propagate unchanged so callers
 * can map their `ChatErrorCode` to actionable copy. This error covers missing
 * records, missing keys, and invalid service input.
 */
export class ProviderServiceError extends Error {
  readonly code: ProviderServiceErrorCode
  readonly providerId: string | null

  constructor(
    code: ProviderServiceErrorCode,
    message: string,
    providerId: string | null = null,
    options: { cause?: unknown } = {},
  ) {
    super(message, options.cause === undefined ? undefined : { cause: options.cause })
    this.name = 'ProviderServiceError'
    this.code = code
    this.providerId = providerId
  }
}
