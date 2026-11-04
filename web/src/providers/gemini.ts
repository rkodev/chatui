import type { ChatErrorCode, FinishReason, Usage } from '../domain'
import { mapGeminiModels } from './openaiCatalog'
import {
  getDefaultCatalogCache,
  isCatalogFresh,
  type CatalogCache,
  type CatalogResult,
  type CatalogSnapshot,
  type ModelInfo,
  type ProviderKeyInfo,
} from './openrouterCatalog'
import {
  ProviderRequestError,
  catalogCacheKey,
  toChatError,
  type ListModelsOptions,
  type NormalizedChatRequest,
  type ProviderContext,
  type StreamChatOptions,
  type StreamEvent,
} from './openaiCompatible'
import { RETRY_MAX_ATTEMPTS, retryDelayFor, sleep, type RetryPolicy } from './retry'
import {
  SseAbortError,
  SseBufferOverflowError,
  SseTransportError,
  readSse,
} from './sse'

/** Attachments resolved to bytes by the caller before serialization. */
export interface GeminiAttachment {
  mimeType: string
  name?: string
  dataBase64: string
}

/**
 * Gemini chat request.
 *
 * The shared normalized request keeps attachment bytes out of `messages`; the
 * Gemini API needs the base64 payload inline, so callers attach the resolved
 * bytes here.
 */
export interface GeminiChatRequest extends NormalizedChatRequest {
  attachments?: Record<string, GeminiAttachment>
}

/** Gemini's supported sampling controls for the phase-1 surface. */
export const DEFAULT_GEMINI_SUPPORTED_PARAMETERS: readonly string[] = [
  'temperature',
  'top_p',
  'max_tokens',
  'stop',
  'stream',
]

/** Namespace that owns Gemini-only advanced JSON. */
export const GEMINI_EXTRA_NAMESPACE = 'google'

const PROTECTED_ADVANCED_FIELDS = ['model', 'messages', 'stream', 'stream_options'] as const
const DEFAULT_MODEL_DISCOVERY_PATH = '/models'
const DEFAULT_CATALOG_TTL_SECONDS = 86400

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

function authHeaderName(context: ProviderContext): string {
  return context.preset?.auth.headerName ?? 'x-goog-api-key'
}

/**
 * Build the Gemini request headers.
 *
 * The key travels only in `x-goog-api-key`. Gemini's query-string key form is
 * never used because URLs end up in logs and error reports.
 */
export function buildGeminiHeaders(context: ProviderContext): Headers {
  const headers = new Headers()

  headers.set('Accept', 'text/event-stream')
  headers.set('content-type', 'application/json')

  for (const [name, value] of Object.entries(context.preset?.defaultHeaders ?? {})) {
    headers.set(name, value)
  }

  for (const [name, value] of Object.entries(context.headers ?? {})) {
    headers.set(name, value)
  }

  if (context.apiKey) {
    headers.set(authHeaderName(context), context.apiKey)
  }

  return headers
}

/** Normalize a model id for the Gemini path segment. */
export function geminiModelPath(model: string): string {
  const trimmed = model.trim().replace(/^models\//, '')
  return encodeURIComponent(trimmed)
}

/** Build `POST /models/{model}:streamGenerateContent?alt=sse`. */
export function buildGeminiChatUrl(baseUrl: string, model: string): string {
  return joinUrl(
    baseUrl,
    `models/${geminiModelPath(model)}:streamGenerateContent?alt=sse`,
  )
}

function decodeBase64Utf8(dataBase64: string): string {
  const binary = atob(dataBase64)
  const bytes = new Uint8Array(binary.length)

  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index)
  }

  return new TextDecoder().decode(bytes)
}

function protectedAdvancedFields(extra: unknown): string[] {
  if (!isRecord(extra)) {
    return []
  }

  return PROTECTED_ADVANCED_FIELDS.filter((field) => field in extra).sort()
}

function contentParts(
  request: GeminiChatRequest,
  message: GeminiChatRequest['messages'][number],
): Array<Record<string, unknown>> {
  const parts: Array<Record<string, unknown>> = []
  const attachments = request.attachments ?? {}

  for (const part of message.parts) {
    switch (part.type) {
      case 'text':
        if (part.text !== '') {
          parts.push({ text: part.text })
        }
        break
      case 'image': {
        const attachment = attachments[part.attachmentId]
        if (!attachment) {
          throw new ProviderRequestError(
            'invalid_request',
            `Missing attachment ${part.attachmentId} for the Gemini request.`,
          )
        }

        parts.push({
          inlineData: {
            mimeType: attachment.mimeType || part.mimeType,
            data: attachment.dataBase64,
          },
        })
        break
      }
      case 'file': {
        const attachment = attachments[part.attachmentId]
        if (!attachment) {
          throw new ProviderRequestError(
            'invalid_request',
            `Missing attachment ${part.attachmentId} for the Gemini request.`,
          )
        }

        const name = attachment.name ?? part.name
        const body = /^text\//i.test(attachment.mimeType || part.mimeType)
          ? decodeBase64Utf8(attachment.dataBase64)
          : ''

        parts.push({ text: `\n\n[File: ${name}]\n${body}` })
        break
      }
      default:
        // Reasoning, tool calls, and tool results are not replayed in phase 1.
        break
    }
  }

  return parts
}

/**
 * Build the exact Gemini `streamGenerateContent` body.
 *
 * `contents` never carries the system prompt; Gemini has a dedicated
 * `systemInstruction` field. Advanced JSON under `params.extra.google` cannot
 * override protected fields.
 */
export function buildGeminiRequestBody(
  request: GeminiChatRequest,
  supportedParameters: readonly string[] = DEFAULT_GEMINI_SUPPORTED_PARAMETERS,
): Record<string, unknown> {
  const supported = new Set(supportedParameters)
  const extra = request.params.extra?.[GEMINI_EXTRA_NAMESPACE]
  const protectedFields = protectedAdvancedFields(extra)

  if (protectedFields.length > 0) {
    throw new ProviderRequestError(
      'invalid_request',
      `Advanced JSON cannot override protected fields: ${protectedFields.join(', ')}.`,
      { details: { protectedFields } },
    )
  }

  const body: Record<string, unknown> = {
    contents: request.messages.map((message) => ({
      role: message.role === 'assistant' ? 'model' : 'user',
      parts: contentParts(request, message),
    })),
  }

  if (request.system !== null) {
    body.systemInstruction = { parts: [{ text: request.system }] }
  }

  const generationConfig: Record<string, unknown> = {}

  if (request.params.maxOutputTokens !== null) {
    generationConfig.maxOutputTokens = request.params.maxOutputTokens
  }

  if (request.params.temperature !== null && supported.has('temperature')) {
    generationConfig.temperature = request.params.temperature
  }

  if (request.params.topP !== null && supported.has('top_p')) {
    generationConfig.topP = request.params.topP
  }

  if (request.params.stop.length > 0 && supported.has('stop')) {
    generationConfig.stopSequences = request.params.stop
  }

  if (Object.keys(generationConfig).length > 0) {
    body.generationConfig = generationConfig
  }

  if (isRecord(extra)) {
    for (const [field, value] of Object.entries(extra)) {
      if ((PROTECTED_ADVANCED_FIELDS as readonly string[]).includes(field)) {
        continue
      }

      body[field] = value
    }
  }

  return body
}

/** Map a Gemini `finishReason` to the normalized reason. */
export function mapGeminiFinishReason(reason: unknown): FinishReason {
  switch (reason) {
    case 'STOP':
      return 'stop'
    case 'MAX_TOKENS':
      return 'length'
    case 'SAFETY':
    case 'RECITATION':
      return 'content_filter'
    default:
      return 'unknown'
  }
}

function statusToCode(status: number, message: string): ChatErrorCode {
  if (status === 401) {
    return 'auth'
  }
  if (status === 402) {
    return 'quota'
  }
  if (status === 403) {
    return /content filter|safety|policy|blocked|moderation/i.test(message)
      ? 'content_filter'
      : 'permission'
  }
  if (status === 404) {
    return 'model_not_found'
  }
  if (status === 413) {
    return 'context_length'
  }
  if (status === 429) {
    return 'rate_limit'
  }
  if (status === 400) {
    if (/api[ _-]?key|api_key_invalid|credential/i.test(message)) {
      return 'auth'
    }

    if (/content filter|content_filter|safety|policy|blocked|moderation/i.test(message)) {
      return 'content_filter'
    }

    return /context length|context_length|token count|maximum number of tokens|too many tokens|too long/i.test(
      message,
    )
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

function errorEnvelope(payload: unknown): Record<string, unknown> | null {
  if (!isRecord(payload)) {
    return null
  }

  return isRecord(payload.error) ? payload.error : payload
}

function errorMessage(
  payload: unknown,
  fallback: string,
  context: ProviderContext,
): string {
  const envelope = errorEnvelope(payload)
  const raw =
    envelope && typeof envelope.message === 'string' ? envelope.message : fallback

  return redactSecret(raw, context.apiKey)
}

async function httpError(
  response: Response,
  context: ProviderContext,
): Promise<ProviderRequestError> {
  let payload: unknown = null

  try {
    const text = await response.text()
    if (text.trim() !== '') {
      payload = JSON.parse(text)
    }
  } catch {
    payload = null
  }

  const envelope = errorEnvelope(payload)
  const message = errorMessage(
    payload,
    `The Gemini request failed with HTTP ${response.status}`,
    context,
  )
  const retryAfterSeconds = parseRetryAfter(response.headers.get('Retry-After'))
  const requestId =
    response.headers.get('x-goog-request-id') ??
    response.headers.get('x-request-id') ??
    (envelope && typeof envelope.request_id === 'string' ? envelope.request_id : null)

  return new ProviderRequestError(statusToCode(response.status, message), message, {
    providerId: context.provider.id,
    status: response.status,
    retryable: isRetryableStatus(response.status),
    retryAfterSeconds,
    requestId,
    details: retryAfterSeconds === null ? null : { retryAfterSeconds },
  })
}

function streamStatus(envelope: Record<string, unknown>): number | null {
  const code = numberOrNull(envelope.code)
  if (code !== null) {
    return code
  }

  if (typeof envelope.status === 'string') {
    switch (envelope.status) {
      case 'RESOURCE_EXHAUSTED':
        return 429
      case 'INTERNAL':
      case 'UNAVAILABLE':
        return 500
      case 'INVALID_ARGUMENT':
        return 400
      case 'UNAUTHENTICATED':
        return 401
      case 'PERMISSION_DENIED':
        return 403
      case 'NOT_FOUND':
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
): ProviderRequestError {
  const status = streamStatus(envelope)
  const message = errorMessage(
    envelope,
    'The Gemini stream returned an error.',
    context,
  )
  const code = status !== null ? statusToCode(status, message) : 'provider'

  return new ProviderRequestError(code, message, {
    providerId: context.provider.id,
    status,
    retryable: false,
  })
}

function cancelledError(context: ProviderContext, cause?: unknown): ProviderRequestError {
  return new ProviderRequestError('cancelled', 'The Gemini request was cancelled.', {
    providerId: context.provider.id,
    cause,
  })
}

interface StreamState {
  responseId: string | null
  inputTokens: number | null
  outputTokens: number | null
  totalTokens: number | null
  usageEmitted: boolean
  finishReason: FinishReason | null
}

export class GeminiStreamMapper {
  private readonly context: ProviderContext
  private readonly emit: (event: StreamEvent) => void
  private readonly state: StreamState = {
    responseId: null,
    inputTokens: null,
    outputTokens: null,
    totalTokens: null,
    usageEmitted: false,
    finishReason: null,
  }

  private started = false
  private finished = false
  private error: ProviderRequestError | null = null

  constructor(options: { context: ProviderContext; emit: (event: StreamEvent) => void }) {
    this.context = options.context
    this.emit = options.emit
  }

  getError(): ProviderRequestError | null {
    return this.error
  }

  handleData(data: string): void {
    if (this.error !== null) {
      return
    }

    let payload: unknown

    try {
      payload = JSON.parse(data)
    } catch {
      this.error = new ProviderRequestError(
        'parse',
        'The Gemini stream contained invalid JSON.',
        { providerId: this.context.provider.id },
      )
      return
    }

    if (!isRecord(payload)) {
      return
    }

    if (isRecord(payload.error)) {
      this.error = streamError(payload.error, this.context)
      return
    }

    if (typeof payload.responseId === 'string') {
      this.state.responseId = payload.responseId
    }

    if (!this.started) {
      this.started = true
      this.emit({ type: 'start', responseId: this.state.responseId })
    }

    this.handleUsageMetadata(payload.usageMetadata)

    const candidates = Array.isArray(payload.candidates) ? payload.candidates : []

    for (const candidate of candidates) {
      if (!isRecord(candidate)) {
        continue
      }

      this.handleCandidate(candidate)
    }
  }

  finish(): void {
    if (this.error !== null || this.finished || !this.started) {
      return
    }

    this.emitFinish(this.state.finishReason ?? 'unknown')
    this.emitUsage()
  }

  private handleCandidate(candidate: Record<string, unknown>): void {
    const content = isRecord(candidate.content) ? candidate.content : null
    const parts = content && Array.isArray(content.parts) ? content.parts : []

    for (const part of parts) {
      if (!isRecord(part) || typeof part.text !== 'string' || part.text === '') {
        continue
      }

      if (part.thought === true) {
        this.emit({ type: 'reasoning-delta', text: part.text })
      } else {
        this.emit({ type: 'text-delta', text: part.text })
      }
    }

    if (
      !this.finished &&
      typeof candidate.finishReason === 'string' &&
      candidate.finishReason !== ''
    ) {
      this.state.finishReason = mapGeminiFinishReason(candidate.finishReason)
      this.emitFinish(this.state.finishReason)
      this.emitUsage()
    }
  }

  private handleUsageMetadata(value: unknown): void {
    if (!isRecord(value)) {
      return
    }

    const input = numberOrNull(value.promptTokenCount)
    const output = numberOrNull(value.candidatesTokenCount)
    const total = numberOrNull(value.totalTokenCount)

    if (input !== null) {
      this.state.inputTokens = input
    }

    if (output !== null) {
      this.state.outputTokens = output
    }

    if (total !== null) {
      this.state.totalTokens = total
    }
  }

  private usage(): Usage | null {
    const { inputTokens, outputTokens } = this.state

    if (inputTokens === null && outputTokens === null) {
      return null
    }

    const totalTokens =
      this.state.totalTokens ??
      (inputTokens !== null && outputTokens !== null ? inputTokens + outputTokens : null)

    return {
      inputTokens,
      outputTokens,
      totalTokens,
      costUsd: null,
      estimated: false,
    }
  }

  private emitUsage(): void {
    if (this.state.usageEmitted) {
      return
    }

    const usage = this.usage()
    if (usage !== null) {
      this.state.usageEmitted = true
      this.emit({ type: 'usage', usage })
    }
  }

  private emitFinish(reason: FinishReason): void {
    if (this.finished) {
      return
    }

    this.emit({ type: 'finish', reason })
    this.finished = true
  }
}

function normalizeThrown(
  error: unknown,
  context: ProviderContext,
  signal: AbortSignal,
  receivedOutput: boolean,
): ProviderRequestError {
  if (signal.aborted) {
    return cancelledError(context, error)
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

  return new ProviderRequestError('unknown', 'The Gemini request failed.', {
    providerId: context.provider.id,
    cause: error,
  })
}

/**
 * Stream a Gemini `generateContent` completion.
 *
 * Events follow the shared order: `start`, text/reasoning deltas, `finish`,
 * `usage`, or a single terminal `error`. The contract fixture puts `finish`
 * before `usage`, so this adapter flushes usage at finish time.
 */
export async function streamGeminiChat(
  context: ProviderContext,
  request: GeminiChatRequest,
  signal: AbortSignal,
  onEvent: (event: StreamEvent) => void,
  options: StreamChatOptions = {},
): Promise<void> {
  const now = options.now ?? (() => new Date().toISOString())
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

  try {
    // Validate serialization before spending a request.
    buildGeminiRequestBody(
      request,
      options.supportedParameters ?? DEFAULT_GEMINI_SUPPORTED_PARAMETERS,
    )
  } catch (error) {
    emitError(
      error instanceof ProviderRequestError
        ? error
        : new ProviderRequestError('invalid_request', 'The chat request was invalid.', {
            providerId: context.provider.id,
            cause: error,
          }),
    )
    return
  }

  const localRetry: RetryPolicy = retry

  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
    if (signal.aborted) {
      emitError(cancelledError(context))
      return
    }

    try {
      const error = await attemptStreamWithOptions(
        context,
        request,
        options,
        signal,
        emit,
      )

      if (error !== null) {
        emitError(error)
      }

      return
    } catch (thrown) {
      const normalized = normalizeThrown(
        thrown,
        context,
        signal,
        receivedText || receivedReasoning,
      )

      if (normalized.code === 'cancelled') {
        emitError(normalized)
        return
      }

      const canRetry =
        normalized.retryable && !receivedText && !receivedReasoning && attempt < maxAttempts

      if (!canRetry) {
        emitError(normalized)
        return
      }

      try {
        await sleep(retryDelayFor(normalized.retryAfterSeconds, attempt, localRetry), signal)
      } catch {
        emitError(cancelledError(context))
        return
      }
    }
  }
}

async function attemptStreamWithOptions(
  context: ProviderContext,
  request: GeminiChatRequest,
  options: StreamChatOptions,
  signal: AbortSignal,
  emit: (event: StreamEvent) => void,
): Promise<ProviderRequestError | null> {
  const supported = options.supportedParameters ?? DEFAULT_GEMINI_SUPPORTED_PARAMETERS
  const headers = buildGeminiHeaders(context)
  const body = buildGeminiRequestBody(request, supported)
  const url = buildGeminiChatUrl(context.provider.baseUrl, request.model)
  let response: Response

  try {
    response = await context.fetch(url, {
      method: 'POST',
      headers,
      body: JSON.stringify(body),
      signal,
    })
  } catch (error) {
    if (signal.aborted) {
      throw cancelledError(context, error)
    }

    throw new ProviderRequestError(
      'network',
      'Could not reach Gemini. Check your connection and base URL.',
      { providerId: context.provider.id, retryable: true, cause: error },
    )
  }

  if (!response.ok) {
    throw await httpError(response, context)
  }

  if (!response.body) {
    throw new ProviderRequestError('provider', 'Gemini returned an empty stream.', {
      providerId: context.provider.id,
      status: response.status,
    })
  }

  const mapper = new GeminiStreamMapper({ context, emit })

  try {
    await readSse(response, {
      onEvent: (data) => mapper.handleData(data),
      signal,
    })
  } catch (error) {
    if (error instanceof SseAbortError || signal.aborted) {
      throw cancelledError(context, error)
    }

    if (error instanceof SseBufferOverflowError) {
      throw new ProviderRequestError(
        'provider',
        'The Gemini stream exceeded the buffer limit.',
        { providerId: context.provider.id, cause: error },
      )
    }

    if (error instanceof SseTransportError) {
      throw new ProviderRequestError('network', 'The Gemini stream was interrupted.', {
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

/** Re-export the shared Gemini catalog mapper for adapter callers. */
export { mapGeminiModels }

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

function modelsUrl(context: ProviderContext): string {
  return joinUrl(
    context.provider.baseUrl,
    context.preset?.modelDiscovery.path ?? DEFAULT_MODEL_DISCOVERY_PATH,
  )
}

function requireKey(context: ProviderContext): void {
  if (!context.apiKey) {
    throw new ProviderRequestError('auth', 'A Google Gemini API key is required.', {
      providerId: context.provider.id,
    })
  }
}

async function fetchGeminiModels(
  context: ProviderContext,
  signal: AbortSignal,
): Promise<ModelInfo[]> {
  requireKey(context)

  let response: Response

  try {
    response = await context.fetch(modelsUrl(context), {
      method: 'GET',
      headers: buildGeminiHeaders(context),
      signal,
    })
  } catch (error) {
    if (signal.aborted) {
      throw cancelledError(context, error)
    }

    throw new ProviderRequestError(
      'network',
      'Could not reach Gemini. Check your connection and base URL.',
      { providerId: context.provider.id, retryable: true, cause: error },
    )
  }

  if (!response.ok) {
    throw await httpError(response, context)
  }

  let payload: unknown

  try {
    payload = await response.json()
  } catch (error) {
    throw new ProviderRequestError('parse', 'Gemini returned invalid JSON.', {
      providerId: context.provider.id,
      status: response.status,
      cause: error,
    })
  }

  try {
    return mapGeminiModels(payload)
  } catch (error) {
    throw new ProviderRequestError('parse', 'Gemini /models response was not valid.', {
      providerId: context.provider.id,
      status: response.status,
      cause: error,
    })
  }
}

/** Validate a Gemini key with `GET /models`; the key stays in a header. */
export async function validateKey(
  context: ProviderContext,
  signal: AbortSignal,
): Promise<ProviderKeyInfo> {
  await fetchGeminiModels(context, signal)
  return emptyKeyInfo()
}

/**
 * Load the Gemini catalog with the shared cache and last-known-good rules.
 */
export async function loadCatalog(
  context: ProviderContext,
  signal: AbortSignal,
  options: ListModelsOptions = {},
): Promise<CatalogResult> {
  const cache: CatalogCache = options.cache ?? getDefaultCatalogCache()
  const now = options.now ?? Date.now
  const cacheKey = catalogCacheKey(context.provider.id)
  const ttlSeconds =
    context.preset?.modelDiscovery.cacheTtlSeconds ?? DEFAULT_CATALOG_TTL_SECONDS
  const ttlMs = ttlSeconds * 1000

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
    const models = await fetchGeminiModels(context, signal)
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

/** Adapter-compatible catalog method. */
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

/** Adapter surface for the Google Gemini preset. */
export const geminiAdapter = {
  kind: 'google-gemini' as const,
  validateKey,
  listModels,
  streamChat: streamGeminiChat,
}
