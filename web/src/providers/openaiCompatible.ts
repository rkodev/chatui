import type {
  ChatError,
  ChatErrorCode,
  ChatParams,
  ContentPart,
  FinishReason,
  Provider,
  Usage,
} from '../domain'
import { catalogDefaultsForPreset, mapAnthropicModels, mapGeminiModels, mapOpenAIModels } from './openaiCatalog'
import {
  getDefaultCatalogCache,
  isCatalogFresh,
  mapOpenRouterKey,
  mapOpenRouterModels,
  type CatalogCache,
  type CatalogResult,
  type CatalogSnapshot,
  type ModelInfo,
  type ProviderKeyInfo,
} from './openrouterCatalog'
import {
  RETRY_MAX_ATTEMPTS,
  retryDelayFor,
  sleep,
  type RetryPolicy,
} from './retry'
import {
  SseAbortError,
  SseBufferOverflowError,
  SseTransportError,
  readSse,
} from './sse'

/** Authentication styles supported by the shared provider contracts. */
export type ProviderAuthType = 'bearer' | 'x-api-key' | 'query-key' | 'none'

/** Model discovery formats understood by the OpenAI-compatible transport. */
export type ModelDiscoveryFormat =
  | 'openrouter'
  | 'openai'
  | 'anthropic'
  | 'gemini'
  | 'ollama-tags'

export interface ProviderAuthConfig {
  type: ProviderAuthType
  headerName?: string
  scheme?: string
  queryParam?: string
}

export interface ProviderModelDiscovery {
  path: string
  format: ModelDiscoveryFormat
  public: boolean
  cacheTtlSeconds: number
}

export interface ProviderKeyValidation {
  method: 'GET'
  path: string
}

/**
 * Provider-specific copy used by the transport.
 *
 * OpenRouter overrides these strings so its M1 messages stay byte-for-byte
 * identical. Generic OpenAI-compatible presets use the provider-neutral
 * defaults below.
 */
export interface ProviderMessages {
  missingKey: string
  cancelled: string
  network: string
  invalidJson: string
  invalidModels: string
  invalidKey: string
  emptyStream: string
  invalidStreamJson: string
  streamError: string
  bufferOverflow: string
  interrupted: string
  unknown: string
  invalidRequest: string
  defaultHttp: (status: number) => string
  status: Record<number, string>
}

export type ExtraMode = 'curated' | 'all'
export type ReasoningStyle = 'openrouter' | 'openai'

/**
 * Everything the OpenAI-compatible transport needs to talk to one provider.
 *
 * The preset is data, not code. OpenRouter is one preset; every other
 * OpenAI-compatible provider is another. The transport never branches on a
 * provider name.
 */
export interface OpenAICompatiblePreset {
  id: string
  displayName: string
  kind?: Provider['kind']
  baseUrl: string
  auth: ProviderAuthConfig
  defaultHeaders: Record<string, string>
  attributionHeaders: Record<string, string>
  modelDiscovery: ProviderModelDiscovery
  keyValidation: ProviderKeyValidation | null
  /** Namespace under `params.extra` that belongs to this preset. */
  extraNamespace?: string
  /** `curated` only forwards declared fields; `all` forwards every safe key. */
  extraMode?: ExtraMode
  extraPassthroughFields?: readonly string[]
  reasoningStyle?: ReasoningStyle
  messages?: Partial<ProviderMessages>
}

/** Attribution values resolved from runtime config. Never personal data. */
export interface Attribution {
  origin: string
  title: string
}

/** Backwards-compatible alias for the OpenRouter attribution shape. */
export type OpenRouterAttribution = Attribution

/**
 * Preset runtime context.
 *
 * It carries only the provider id, base URL, auth style, non-secret headers,
 * and the API key. The key is never logged or placed in a URL.
 */
export interface ProviderContext {
  provider: Pick<Provider, 'id' | 'baseUrl'>
  apiKey: string | null
  fetch: typeof fetch
  preset?: OpenAICompatiblePreset
  auth?: ProviderAuthConfig
  headers?: Record<string, string>
  attribution?: Attribution
}

export interface ListModelsOptions {
  cache?: CatalogCache
  now?: () => number
  forceRefresh?: boolean
  preset?: OpenAICompatiblePreset
}

/** Normalized stream event from spec section 7.2. */
export type StreamEvent =
  | { type: 'start'; responseId: string | null }
  | { type: 'text-delta'; text: string }
  | { type: 'reasoning-delta'; text: string }
  | {
      type: 'tool-call-delta'
      index: number
      id: string | null
      name: string | null
      argumentsDelta: string
    }
  | { type: 'usage'; usage: Usage }
  | { type: 'finish'; reason: FinishReason }
  | { type: 'error'; error: ChatError }

/** One normalized chat message. */
export interface NormalizedChatMessage {
  role: 'user' | 'assistant' | 'tool'
  parts: ContentPart[]
}

/** Normalized chat request from spec section 7.3. */
export interface NormalizedChatRequest {
  model: string
  system: string | null
  messages: NormalizedChatMessage[]
  params: ChatParams
}

export interface StreamChatOptions {
  /**
   * Model parameters advertised by the catalog. Optional controls are only
   * serialized when they appear here. Defaults to the common OpenAI-compatible
   * set so a caller without catalog metadata still gets sampling controls.
   */
  supportedParameters?: string[]
  /** Retry timing overrides. */
  retry?: RetryPolicy
  /** Injectable clock for deterministic `ChatError.at` values in tests. */
  now?: () => string
  /** Preset override. Falls back to `context.preset`. */
  preset?: OpenAICompatiblePreset
}

interface ProviderRequestErrorOptions {
  cause?: unknown
  providerId?: string | null
  status?: number | null
  retryable?: boolean
  retryAfterSeconds?: number | null
  requestId?: string | null
  details?: Record<string, unknown> | null
}

/** Normalized provider request failure. */
export class ProviderRequestError extends Error {
  readonly code: ChatErrorCode
  readonly providerId: string | null
  readonly status: number | null
  readonly retryable: boolean
  readonly retryAfterSeconds: number | null
  readonly requestId: string | null
  readonly details: Record<string, unknown> | null

  constructor(code: ChatErrorCode, message: string, options: ProviderRequestErrorOptions = {}) {
    super(message, options.cause === undefined ? undefined : { cause: options.cause })
    this.name = 'ProviderRequestError'
    this.code = code
    this.providerId = options.providerId ?? null
    this.status = options.status ?? null
    this.retryable = options.retryable ?? false
    this.retryAfterSeconds = options.retryAfterSeconds ?? null
    this.requestId = options.requestId ?? null
    this.details = options.details ?? null
  }
}

/** Convert a normalized request failure into the persisted `ChatError` shape. */
export function toChatError(
  error: ProviderRequestError,
  at: string = new Date().toISOString(),
): ChatError {
  return {
    code: error.code,
    message: error.message,
    providerId: error.providerId,
    status: error.status,
    retryable: error.retryable,
    retryAfterSeconds: error.retryAfterSeconds,
    requestId: error.requestId,
    details: error.details,
    at,
  }
}

/** Parameters that are safe to serialize when the catalog does not say otherwise. */
export const DEFAULT_SUPPORTED_PARAMETERS: readonly string[] = [
  'temperature',
  'top_p',
  'max_tokens',
  'stop',
  'stream',
  'tools',
  'tool_choice',
]

const PROTECTED_ADVANCED_FIELDS = ['model', 'messages', 'stream', 'stream_options'] as const

const DEFAULT_MESSAGES: ProviderMessages = {
  missingKey: 'An API key is required.',
  cancelled: 'The provider request was cancelled.',
  network: 'Could not reach the provider. Check your connection and base URL.',
  invalidJson: 'The provider returned invalid JSON.',
  invalidModels: 'The provider /models response was not valid.',
  invalidKey: 'The provider key response was not valid.',
  emptyStream: 'The provider returned an empty stream.',
  invalidStreamJson: 'The provider stream contained invalid JSON.',
  streamError: 'The provider stream returned an error.',
  bufferOverflow: 'The provider stream exceeded the buffer limit.',
  interrupted: 'The provider stream was interrupted.',
  unknown: 'The provider request failed.',
  invalidRequest: 'The chat request was invalid.',
  defaultHttp: (status) => `The provider request failed with HTTP ${status}`,
  status: {
    401: 'Authentication failed. Check your API key.',
    402: 'The provider reports insufficient credits or an exhausted quota.',
    403: 'The provider blocked the request.',
    404: 'The requested model was not found.',
    429: 'Rate limit exceeded. Please retry later.',
    503: 'The provider is temporarily unavailable.',
  },
}

function messagesFor(preset: OpenAICompatiblePreset): ProviderMessages {
  return { ...DEFAULT_MESSAGES, ...preset.messages }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function joinUrl(baseUrl: string, path: string): string {
  if (/^https?:\/\//i.test(path)) {
    return path
  }

  return `${baseUrl.replace(/\/+$/, '')}/${path.replace(/^\/+/, '')}`
}

function redactSecret(message: string, secret: string | null): string {
  if (!secret) {
    return message
  }

  return message.split(secret).join('[redacted]')
}

function numberOrNull(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function contentToText(content: unknown): string {
  if (typeof content === 'string') {
    return content
  }

  if (Array.isArray(content)) {
    return content
      .map((part) => {
        if (typeof part === 'string') {
          return part
        }
        if (isRecord(part) && typeof part.text === 'string') {
          return part.text
        }
        return ''
      })
      .join('')
  }

  return ''
}

function reasoningToText(delta: Record<string, unknown>): string {
  const details = delta.reasoning_details

  if (Array.isArray(details)) {
    const text = details
      .map((part) => (isRecord(part) && typeof part.text === 'string' ? part.text : ''))
      .join('')

    if (text !== '') {
      return text
    }
  }

  if (typeof delta.reasoning === 'string') {
    return delta.reasoning
  }

  // DeepSeek and several OpenAI-compatible providers use this field.
  return typeof delta.reasoning_content === 'string' ? delta.reasoning_content : ''
}

function mapFinishReason(reason: string): FinishReason {
  switch (reason) {
    case 'stop':
    case 'length':
    case 'content_filter':
    case 'tool_calls':
      return reason
    default:
      return 'unknown'
  }
}

function normalizeUsage(raw: Record<string, unknown>): Usage | null {
  const inputTokens = numberOrNull(raw.prompt_tokens)
  const outputTokens = numberOrNull(raw.completion_tokens)
  const totalTokens =
    numberOrNull(raw.total_tokens) ??
    (inputTokens !== null && outputTokens !== null ? inputTokens + outputTokens : null)
  const costUsd = numberOrNull(raw.cost)

  if (
    inputTokens === null &&
    outputTokens === null &&
    totalTokens === null &&
    costUsd === null
  ) {
    return null
  }

  return {
    inputTokens,
    outputTokens,
    totalTokens,
    costUsd,
    estimated: false,
  }
}

/**
 * Estimate usage when the provider does not report it.
 *
 * The adapter never emits an estimated usage event on its own because the
 * shared expected vectors omit usage for streams that do not report it.
 * Callers that need a fallback can use this helper and set `estimated: true`.
 */
export function estimateUsage(text: string, reasoning = ''): Usage {
  const inputTokens = Math.ceil(text.length / 4)
  const outputTokens = Math.ceil((text.length + reasoning.length) / 4)

  return {
    inputTokens,
    outputTokens,
    totalTokens: inputTokens + outputTokens,
    costUsd: null,
    estimated: true,
  }
}

function messageContent(parts: ContentPart[]): string {
  return parts
    .map((part) => (part.type === 'text' ? part.text : ''))
    .join('')
}

function protectedAdvancedFields(extra: unknown): string[] {
  if (!isRecord(extra)) {
    return []
  }

  return PROTECTED_ADVANCED_FIELDS.filter((field) => field in extra).sort()
}

function resolvePreset(
  context: ProviderContext,
  preset?: OpenAICompatiblePreset,
): OpenAICompatiblePreset {
  const resolved = preset ?? context.preset

  if (!resolved) {
    throw new ProviderRequestError('invalid_request', 'A provider preset is required.')
  }

  return resolved
}

function resolveAuth(
  context: ProviderContext,
  preset: OpenAICompatiblePreset,
): ProviderAuthConfig {
  return context.auth ?? preset.auth
}

function requiresKey(auth: ProviderAuthConfig): boolean {
  return auth.type === 'bearer' || auth.type === 'x-api-key' || auth.type === 'query-key'
}

function applyAuth(headers: Headers, auth: ProviderAuthConfig, apiKey: string): void {
  switch (auth.type) {
    case 'bearer':
      headers.set(auth.headerName ?? 'Authorization', `${auth.scheme ?? 'Bearer'} ${apiKey}`)
      return
    case 'x-api-key':
      headers.set(auth.headerName ?? 'x-api-key', apiKey)
      return
    case 'none':
      return
    case 'query-key':
      // Keys must never appear in URLs or logs.
      throw new ProviderRequestError(
        'invalid_request',
        'API keys are never placed in URLs. Use a header-based auth style.',
      )
  }
}

function resolveAttributionValue(value: string, attribution?: Attribution): string | null {
  if (value === 'app.origin') {
    return attribution?.origin ?? null
  }

  if (value === 'app.name') {
    return attribution?.title ?? null
  }

  return value
}

function buildHeaders(
  context: ProviderContext,
  preset: OpenAICompatiblePreset,
  includeAuth: boolean,
  accept: string,
): Headers {
  const headers = new Headers()
  headers.set('Accept', accept)

  for (const [name, value] of Object.entries(preset.defaultHeaders)) {
    headers.set(name, value)
  }

  for (const [name, value] of Object.entries(context.headers ?? {})) {
    headers.set(name, value)
  }

  if (includeAuth && context.apiKey) {
    applyAuth(headers, resolveAuth(context, preset), context.apiKey)
  }

  for (const [name, value] of Object.entries(preset.attributionHeaders)) {
    const resolved = resolveAttributionValue(value, context.attribution)
    if (resolved !== null) {
      headers.set(name, resolved)
    }
  }

  return headers
}

/** Build the streaming request headers, including preset auth and attribution. */
export function buildStreamHeaders(
  context: ProviderContext,
  preset?: OpenAICompatiblePreset,
): Headers {
  const resolved = resolvePreset(context, preset)
  const headers = buildHeaders(context, resolved, true, 'text/event-stream')
  headers.set('Content-Type', 'application/json')
  return headers
}

/**
 * Build the exact OpenAI-compatible `POST /chat/completions` body.
 *
 * The function is deliberately pure so tests can assert the serialized JSON
 * against the shared request vectors. It throws an `invalid_request` error
 * before any network call when advanced JSON tries to override a protected
 * field.
 */
export function buildChatRequestBody(
  request: NormalizedChatRequest,
  preset: OpenAICompatiblePreset,
  supportedParameters: readonly string[] = DEFAULT_SUPPORTED_PARAMETERS,
): Record<string, unknown> {
  const supported = new Set(supportedParameters)
  const namespace = preset.extraNamespace ?? preset.id
  const extra = request.params.extra?.[namespace]
  const protectedFields = protectedAdvancedFields(extra)

  if (protectedFields.length > 0) {
    throw new ProviderRequestError(
      'invalid_request',
      `Advanced JSON cannot override protected fields: ${protectedFields.join(', ')}.`,
      {
        details: { protectedFields },
      },
    )
  }

  const messages: Array<{ role: string; content: string }> = []

  if (request.system !== null) {
    messages.push({ role: 'system', content: request.system })
  }

  for (const message of request.messages) {
    messages.push({ role: message.role, content: messageContent(message.parts) })
  }

  const body: Record<string, unknown> = {
    model: request.model,
    messages,
    stream: true,
    stream_options: { include_usage: true },
  }

  if (request.params.temperature !== null && supported.has('temperature')) {
    body.temperature = request.params.temperature
  }

  if (request.params.topP !== null && supported.has('top_p')) {
    body.top_p = request.params.topP
  }

  if (request.params.maxOutputTokens !== null && supported.has('max_tokens')) {
    body.max_tokens = request.params.maxOutputTokens
  }

  if (request.params.stop.length > 0 && supported.has('stop')) {
    body.stop = request.params.stop
  }

  if (request.params.reasoningEffort !== null && supported.has('reasoning')) {
    if ((preset.reasoningStyle ?? 'openai') === 'openrouter') {
      body.reasoning = { effort: request.params.reasoningEffort }
    } else {
      body.reasoning_effort = request.params.reasoningEffort
    }
  }

  if (isRecord(extra)) {
    if ((preset.extraMode ?? 'all') === 'curated') {
      for (const field of preset.extraPassthroughFields ?? []) {
        if (!(field in extra)) {
          continue
        }

        if (field === 'reasoning') {
          if (!supported.has('reasoning') || !isRecord(extra.reasoning)) {
            continue
          }

          const existing = isRecord(body.reasoning) ? body.reasoning : {}
          body.reasoning = { ...existing, ...extra.reasoning }
          continue
        }

        body[field] = extra[field]
      }
    } else {
      for (const [field, value] of Object.entries(extra)) {
        if ((PROTECTED_ADVANCED_FIELDS as readonly string[]).includes(field)) {
          continue
        }

        body[field] = value
      }
    }
  }

  return body
}

function statusToCode(status: number, message: string): ChatErrorCode {
  if (status === 401) {
    return 'auth'
  }
  if (status === 402) {
    return 'quota'
  }
  if (status === 403) {
    return /moderation|policy|content/i.test(message) ? 'content_filter' : 'permission'
  }
  if (status === 404) {
    return 'model_not_found'
  }
  if (status === 429) {
    return 'rate_limit'
  }
  if (status === 413) {
    return 'context_length'
  }
  if (status === 400) {
    if (/content filter|content_filter|content_policy|safety system|moderation/i.test(message)) {
      return 'content_filter'
    }

    return /context length|context_length|maximum context|too many tokens/i.test(message)
      ? 'context_length'
      : 'invalid_request'
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

function metadataDetails(
  metadata: Record<string, unknown> | null,
  retryAfterSeconds: number | null,
): Record<string, unknown> | null {
  const details: Record<string, unknown> = {}

  if (metadata && typeof metadata.provider_name === 'string') {
    details.providerName = metadata.provider_name
  }

  if (metadata && typeof metadata.raw === 'string') {
    details.raw = metadata.raw
  }

  if (retryAfterSeconds !== null) {
    details.retryAfterSeconds = retryAfterSeconds
  }

  return Object.keys(details).length > 0 ? details : null
}

function defaultMessage(status: number, preset: OpenAICompatiblePreset): string {
  const messages = messagesFor(preset)
  return messages.status[status] ?? messages.defaultHttp(status)
}

async function readErrorMessage(
  response: Response,
  preset: OpenAICompatiblePreset,
): Promise<string> {
  let text: string

  try {
    text = await response.text()
  } catch {
    return defaultMessage(response.status, preset)
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

  return defaultMessage(response.status, preset)
}

async function requestJson(
  context: ProviderContext,
  preset: OpenAICompatiblePreset,
  path: string,
  options: { includeAuth: boolean; signal: AbortSignal },
): Promise<unknown> {
  const messages = messagesFor(preset)
  const url = joinUrl(context.provider.baseUrl, path)
  const fetchImpl = context.fetch
  const headers = buildHeaders(context, preset, options.includeAuth, 'application/json')
  let response: Response

  try {
    response = await fetchImpl(url, {
      method: 'GET',
      headers,
      signal: options.signal,
    })
  } catch (error) {
    if (options.signal.aborted) {
      throw new ProviderRequestError('cancelled', messages.cancelled, {
        providerId: context.provider.id,
        cause: error,
      })
    }

    throw new ProviderRequestError('network', messages.network, {
      providerId: context.provider.id,
      retryable: true,
      cause: error,
    })
  }

  if (!response.ok) {
    const message = redactSecret(await readErrorMessage(response, preset), context.apiKey)
    throw new ProviderRequestError(statusToCode(response.status, message), message, {
      providerId: context.provider.id,
      status: response.status,
      retryable: isRetryableStatus(response.status),
      retryAfterSeconds: parseRetryAfter(response.headers.get('Retry-After')),
    })
  }

  try {
    return await response.json()
  } catch (error) {
    throw new ProviderRequestError('parse', messages.invalidJson, {
      providerId: context.provider.id,
      status: response.status,
      cause: error,
    })
  }
}

function mapModels(payload: unknown, preset: OpenAICompatiblePreset): ModelInfo[] {
  switch (preset.modelDiscovery.format) {
    case 'openrouter':
      return mapOpenRouterModels(payload)
    case 'openai':
      return mapOpenAIModels(payload, catalogDefaultsForPreset(preset.id))
    case 'anthropic':
      return mapAnthropicModels(payload, catalogDefaultsForPreset(preset.id))
    case 'gemini':
      return mapGeminiModels(payload, catalogDefaultsForPreset(preset.id))
    default:
      throw new Error(`Unsupported model discovery format: ${preset.modelDiscovery.format}`)
  }
}

function emptyKeyInfo(): ProviderKeyInfo {
  return {
    label: null,
    usageUsd: null,
    limitUsd: null,
    limitRemainingUsd: null,
    isFreeTier: null,
    rateLimit: null,
  }
}

/**
 * Validate a provider key.
 *
 * OpenRouter uses `GET /key` and maps the rich key metadata. Other
 * OpenAI-compatible presets validate by loading `GET /models` and return a
 * generic key result. The key is sent only in a header, never in the URL.
 */
export async function validateKey(
  context: ProviderContext,
  signal: AbortSignal,
): Promise<ProviderKeyInfo> {
  const preset = resolvePreset(context)
  const messages = messagesFor(preset)
  const auth = resolveAuth(context, preset)

  if (preset.keyValidation === null) {
    return emptyKeyInfo()
  }

  if (requiresKey(auth) && !context.apiKey) {
    throw new ProviderRequestError('auth', messages.missingKey, {
      providerId: context.provider.id,
    })
  }

  const payload = await requestJson(context, preset, preset.keyValidation.path, {
    includeAuth: true,
    signal,
  })

  try {
    if (preset.modelDiscovery.format === 'openrouter') {
      return mapOpenRouterKey(payload)
    }

    mapModels(payload, preset)
    return emptyKeyInfo()
  } catch (error) {
    throw new ProviderRequestError('parse', messages.invalidKey, {
      providerId: context.provider.id,
      cause: error,
    })
  }
}

async function fetchModels(
  context: ProviderContext,
  preset: OpenAICompatiblePreset,
  signal: AbortSignal,
): Promise<ModelInfo[]> {
  const messages = messagesFor(preset)
  const payload = await requestJson(context, preset, preset.modelDiscovery.path, {
    includeAuth: !preset.modelDiscovery.public,
    signal,
  })

  try {
    return mapModels(payload, preset)
  } catch (error) {
    throw new ProviderRequestError('parse', messages.invalidModels, {
      providerId: context.provider.id,
      cause: error,
    })
  }
}

/** Stable cache key for one provider's catalog. */
export function catalogCacheKey(providerId: string): string {
  return `chatui.catalog.${providerId}.v1`
}

/**
 * Load a provider catalog with the preset's cache TTL and last-known-good
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
  const preset = resolvePreset(context, options.preset)
  const cache = options.cache ?? getDefaultCatalogCache()
  const now = options.now ?? Date.now
  const cacheKey = catalogCacheKey(context.provider.id)
  const ttlMs = preset.modelDiscovery.cacheTtlSeconds * 1000

  let cached: CatalogSnapshot | null
  try {
    cached = await cache.read(cacheKey)
  } catch {
    cached = null
  }

  if (cached && !options.forceRefresh && isCatalogFresh(cached, now(), ttlMs)) {
    return {
      models: cached.models,
      fetchedAt: cached.fetchedAt,
      fromCache: true,
      stale: false,
      refreshError: null,
    }
  }

  try {
    const models = await fetchModels(context, preset, signal)
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

function httpError(
  response: Response,
  context: ProviderContext,
  preset: OpenAICompatiblePreset,
): Promise<ProviderRequestError> {
  return (async () => {
    let payload: unknown = null

    try {
      const text = await response.text()
      if (text.trim() !== '') {
        payload = JSON.parse(text)
      }
    } catch {
      payload = null
    }

    const envelope = isRecord(payload) && isRecord(payload.error) ? payload.error : null
    const rawMessage =
      envelope && typeof envelope.message === 'string'
        ? envelope.message
        : isRecord(payload) && typeof payload.message === 'string'
          ? payload.message
          : defaultMessage(response.status, preset)
    const message = redactSecret(rawMessage, context.apiKey)
    const code = statusToCode(response.status, message)
    const metadata = envelope && isRecord(envelope.metadata) ? envelope.metadata : null
    const retryAfterSeconds =
      parseRetryAfter(response.headers.get('Retry-After')) ??
      (metadata ? numberOrNull(metadata.retry_after_seconds) : null)
    const requestId =
      response.headers.get('x-request-id') ??
      (envelope && typeof envelope.request_id === 'string' ? envelope.request_id : null)

    return new ProviderRequestError(code, message, {
      providerId: context.provider.id,
      status: response.status,
      retryable: isRetryableStatus(response.status),
      retryAfterSeconds,
      requestId,
      details: metadataDetails(metadata, retryAfterSeconds),
    })
  })()
}

function streamStatus(envelope: Record<string, unknown>): number | null {
  const code = numberOrNull(envelope.code)
  if (code !== null) {
    return code
  }

  const status = numberOrNull(envelope.status)
  if (status !== null) {
    return status
  }

  if (typeof envelope.type === 'string') {
    switch (envelope.type) {
      case 'server_error':
        return 500
      case 'rate_limit_error':
        return 429
      case 'invalid_request_error':
        return 400
      case 'authentication_error':
        return 401
      case 'permission_error':
        return 403
      case 'not_found_error':
        return 404
      default:
        return null
    }
  }

  return null
}

function streamError(
  envelope: Record<string, unknown>,
  context: ProviderContext,
  preset: OpenAICompatiblePreset,
  responseId: string | null,
): ProviderRequestError {
  const messages = messagesFor(preset)
  const status = streamStatus(envelope)
  const rawMessage =
    typeof envelope.message === 'string' ? envelope.message : messages.streamError
  const message = redactSecret(rawMessage, context.apiKey)
  const code = status !== null ? statusToCode(status, message) : 'provider'
  const metadata = isRecord(envelope.metadata) ? envelope.metadata : null

  return new ProviderRequestError(code, message, {
    providerId: context.provider.id,
    status,
    retryable: false,
    requestId: responseId,
    details: metadataDetails(metadata, null),
  })
}

function cancelledError(
  context: ProviderContext,
  preset: OpenAICompatiblePreset,
  cause?: unknown,
): ProviderRequestError {
  return new ProviderRequestError('cancelled', messagesFor(preset).cancelled, {
    providerId: context.provider.id,
    cause,
  })
}

interface StreamMapperOptions {
  context: ProviderContext
  preset: OpenAICompatiblePreset
  emit: (event: StreamEvent) => void
}

class StreamMapper {
  private readonly context: ProviderContext
  private readonly preset: OpenAICompatiblePreset
  private readonly messages: ProviderMessages
  private readonly emit: (event: StreamEvent) => void
  private started = false
  private finished = false
  private responseId: string | null = null
  private error: ProviderRequestError | null = null
  private readonly emittedReasoning = new Set<string>()

  constructor(options: StreamMapperOptions) {
    this.context = options.context
    this.preset = options.preset
    this.messages = messagesFor(options.preset)
    this.emit = options.emit
  }

  getError(): ProviderRequestError | null {
    return this.error
  }

  handleData(data: string): void {
    if (this.error !== null || data === '[DONE]') {
      return
    }

    let payload: unknown

    try {
      payload = JSON.parse(data)
    } catch {
      this.fail(
        new ProviderRequestError('parse', this.messages.invalidStreamJson, {
          providerId: this.context.provider.id,
        }),
      )
      return
    }

    if (!isRecord(payload)) {
      return
    }

    if (typeof payload.id === 'string') {
      this.responseId = payload.id
    }

    if (isRecord(payload.error)) {
      this.fail(streamError(payload.error, this.context, this.preset, this.responseId))
      return
    }

    this.ensureStarted()

    const choices = Array.isArray(payload.choices) ? payload.choices : []

    if (choices.length > 0 && isRecord(choices[0])) {
      const choice = choices[0]
      const delta = isRecord(choice.delta) ? choice.delta : null

      if (delta) {
        const text = contentToText(delta.content)
        if (text !== '') {
          this.emit({ type: 'text-delta', text })
        }

        const reasoning = reasoningToText(delta)
        const duplicateOpenRouter =
          this.preset.reasoningStyle === 'openrouter' && this.emittedReasoning.has(reasoning)

        if (reasoning !== '' && !duplicateOpenRouter) {
          // OpenRouter may repeat the same reasoning text in both `reasoning`
          // and `reasoning_details`; its M1 behavior emits it once. Other
          // OpenAI-compatible providers stream independent chunks.
          if (this.preset.reasoningStyle === 'openrouter') {
            this.emittedReasoning.add(reasoning)
          }

          this.emit({ type: 'reasoning-delta', text: reasoning })
        }
      }

      if (typeof choice.finish_reason === 'string') {
        this.emit({ type: 'finish', reason: mapFinishReason(choice.finish_reason) })
        this.finished = true
      }
    }

    if (isRecord(payload.usage)) {
      const usage = normalizeUsage(payload.usage)
      if (usage !== null) {
        this.emit({ type: 'usage', usage })
      }
    }
  }

  finish(): void {
    if (this.error !== null || !this.started || this.finished) {
      return
    }

    this.emit({ type: 'finish', reason: 'unknown' })
    this.finished = true
  }

  private ensureStarted(): void {
    if (this.started) {
      return
    }

    this.started = true
    this.emit({ type: 'start', responseId: this.responseId })
  }

  private fail(error: ProviderRequestError): void {
    this.error = error
  }
}

async function attemptStream(
  context: ProviderContext,
  preset: OpenAICompatiblePreset,
  body: Record<string, unknown>,
  signal: AbortSignal,
  emit: (event: StreamEvent) => void,
): Promise<ProviderRequestError | null> {
  const messages = messagesFor(preset)
  const url = joinUrl(context.provider.baseUrl, '/chat/completions')
  const fetchImpl = context.fetch
  const headers = buildStreamHeaders(context, preset)
  let response: Response

  try {
    response = await fetchImpl(url, {
      method: 'POST',
      headers,
      body: JSON.stringify(body),
      signal,
    })
  } catch (error) {
    if (signal.aborted) {
      throw cancelledError(context, preset, error)
    }

    throw new ProviderRequestError('network', messages.network, {
      providerId: context.provider.id,
      retryable: true,
      cause: error,
    })
  }

  if (!response.ok) {
    throw await httpError(response, context, preset)
  }

  if (!response.body) {
    throw new ProviderRequestError('provider', messages.emptyStream, {
      providerId: context.provider.id,
      status: response.status,
    })
  }

  const mapper = new StreamMapper({ context, preset, emit })

  try {
    await readSse(response, {
      onEvent: (data) => mapper.handleData(data),
      signal,
    })
  } catch (error) {
    if (error instanceof SseAbortError || signal.aborted) {
      throw cancelledError(context, preset, error)
    }

    if (error instanceof SseBufferOverflowError) {
      throw new ProviderRequestError('provider', messages.bufferOverflow, {
        providerId: context.provider.id,
        cause: error,
      })
    }

    if (error instanceof SseTransportError) {
      throw new ProviderRequestError('network', messages.interrupted, {
        providerId: context.provider.id,
        retryable: true,
        cause: error,
      })
    }

    throw error
  }

  mapper.finish()
  return mapper.getError()
}

function normalizeThrown(
  error: unknown,
  context: ProviderContext,
  preset: OpenAICompatiblePreset,
  signal: AbortSignal,
  receivedOutput: boolean,
): ProviderRequestError {
  if (signal.aborted) {
    return cancelledError(context, preset, error)
  }

  if (error instanceof ProviderRequestError) {
    if (error.code === 'network' && receivedOutput) {
      return new ProviderRequestError('network', error.message, {
        providerId: error.providerId,
        status: error.status,
        retryable: false,
        retryAfterSeconds: error.retryAfterSeconds,
        requestId: error.requestId,
        details: error.details,
        cause: error,
      })
    }

    return error
  }

  return new ProviderRequestError('unknown', messagesFor(preset).unknown, {
    providerId: context.provider.id,
    cause: error,
  })
}

/**
 * Stream an OpenAI-compatible chat completion.
 *
 * Events are emitted in the order defined by the shared expected vectors:
 * `start`, content/reasoning deltas, `finish`, then `usage`. Terminal failures
 * are emitted as a single `error` event and the promise resolves; the adapter
 * does not throw for provider, network, or cancellation failures.
 *
 * Retries happen only before any text or reasoning delta has been emitted. A
 * provider `Retry-After` value overrides the local jittered backoff.
 */
export async function streamChat(
  context: ProviderContext,
  request: NormalizedChatRequest,
  signal: AbortSignal,
  onEvent: (event: StreamEvent) => void,
  options: StreamChatOptions = {},
): Promise<void> {
  const now = options.now ?? (() => new Date().toISOString())
  let preset: OpenAICompatiblePreset

  try {
    preset = resolvePreset(context, options.preset)
  } catch (error) {
    const normalized =
      error instanceof ProviderRequestError
        ? error
        : new ProviderRequestError('invalid_request', 'The chat request was invalid.', {
            providerId: context.provider.id,
            cause: error,
          })
    onEvent({ type: 'error', error: toChatError(normalized, now()) })
    return
  }

  const retry = options.retry ?? {}
  const maxAttempts = Math.max(
    1,
    Math.min(retry.maxAttempts ?? RETRY_MAX_ATTEMPTS, RETRY_MAX_ATTEMPTS),
  )
  let startEmitted = false
  let receivedText = false
  let receivedReasoning = false

  const emit = (event: StreamEvent): void => {
    if (event.type === 'start') {
      if (startEmitted) {
        return
      }
      startEmitted = true
    }

    if (event.type === 'text-delta') {
      receivedText = true
    }

    if (event.type === 'reasoning-delta') {
      receivedReasoning = true
    }

    onEvent(event)
  }

  const emitError = (error: ProviderRequestError): void => {
    emit({ type: 'error', error: toChatError(error, now()) })
  }

  let body: Record<string, unknown>

  try {
    body = buildChatRequestBody(request, preset, options.supportedParameters)
  } catch (error) {
    emitError(
      error instanceof ProviderRequestError
        ? error
        : new ProviderRequestError('invalid_request', messagesFor(preset).invalidRequest, {
            providerId: context.provider.id,
            cause: error,
          }),
    )
    return
  }

  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
    if (signal.aborted) {
      emitError(cancelledError(context, preset))
      return
    }

    try {
      const error = await attemptStream(context, preset, body, signal, emit)

      if (error !== null) {
        emitError(error)
      }

      return
    } catch (thrown) {
      const normalized = normalizeThrown(
        thrown,
        context,
        preset,
        signal,
        receivedText || receivedReasoning,
      )

      if (normalized.code === 'cancelled') {
        emitError(normalized)
        return
      }

      const canRetry =
        normalized.retryable &&
        !receivedText &&
        !receivedReasoning &&
        attempt < maxAttempts

      if (!canRetry) {
        emitError(normalized)
        return
      }

      try {
        await sleep(retryDelayFor(normalized.retryAfterSeconds, attempt, retry), signal)
      } catch {
        emitError(cancelledError(context, preset))
        return
      }
    }
  }
}

/**
 * Create the adapter surface for one OpenAI-compatible preset.
 *
 * The returned object satisfies the shared `ProviderAdapter` shape used by the
 * provider and model services.
 */
export function createOpenAICompatibleAdapter(preset: OpenAICompatiblePreset) {
  return {
    kind: 'openai-compatible' as const,
    validateKey: (context: ProviderContext, signal: AbortSignal) =>
      validateKey({ ...context, preset }, signal),
    listModels: (
      context: ProviderContext,
      signal: AbortSignal,
      options: ListModelsOptions = {},
    ) => listModels({ ...context, preset }, signal, { ...options, preset }),
    streamChat: (
      context: ProviderContext,
      request: NormalizedChatRequest,
      signal: AbortSignal,
      onEvent: (event: StreamEvent) => void,
      options: StreamChatOptions = {},
    ) => streamChat({ ...context, preset }, request, signal, onEvent, { ...options, preset }),
  }
}
