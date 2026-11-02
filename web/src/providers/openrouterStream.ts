import type {
  ChatError,
  ChatErrorCode,
  ChatParams,
  ContentPart,
  FinishReason,
  Usage,
} from '../domain'
import type { ProviderContext } from './openrouter'
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
}

interface OpenRouterRequestErrorOptions {
  cause?: unknown
  providerId?: string | null
  status?: number | null
  retryable?: boolean
  retryAfterSeconds?: number | null
  requestId?: string | null
  details?: Record<string, unknown> | null
}

/** Normalized OpenRouter request failure. */
export class OpenRouterRequestError extends Error {
  readonly code: ChatErrorCode
  readonly providerId: string | null
  readonly status: number | null
  readonly retryable: boolean
  readonly retryAfterSeconds: number | null
  readonly requestId: string | null
  readonly details: Record<string, unknown> | null

  constructor(code: ChatErrorCode, message: string, options: OpenRouterRequestErrorOptions = {}) {
    super(message, options.cause === undefined ? undefined : { cause: options.cause })
    this.name = 'OpenRouterRequestError'
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
  error: OpenRouterRequestError,
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

const ADVANCED_PASSTHROUGH_FIELDS = ['provider', 'models', 'route', 'reasoning'] as const

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function joinUrl(baseUrl: string, path: string): string {
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

  return typeof delta.reasoning === 'string' ? delta.reasoning : ''
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

/**
 * Build the exact OpenRouter `POST /chat/completions` body.
 *
 * The function is deliberately pure so tests can assert the serialized JSON
 * against the shared request vectors. It throws an `invalid_request` error
 * before any network call when advanced JSON tries to override a protected
 * field.
 */
export function buildChatRequestBody(
  request: NormalizedChatRequest,
  supportedParameters: readonly string[] = DEFAULT_SUPPORTED_PARAMETERS,
): Record<string, unknown> {
  const supported = new Set(supportedParameters)
  const extra = request.params.extra?.openrouter
  const protectedFields = protectedAdvancedFields(extra)

  if (protectedFields.length > 0) {
    throw new OpenRouterRequestError(
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
    body.reasoning = { effort: request.params.reasoningEffort }
  }

  if (isRecord(extra)) {
    for (const field of ADVANCED_PASSTHROUGH_FIELDS) {
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
  }

  return body
}

/** Build the streaming request headers, including attribution. */
export function buildStreamHeaders(context: ProviderContext): Headers {
  const headers = new Headers()
  headers.set('Accept', 'text/event-stream')
  headers.set('Content-Type', 'application/json')

  if (context.apiKey) {
    headers.set('Authorization', `Bearer ${context.apiKey}`)
  }

  if (context.attribution) {
    headers.set('HTTP-Referer', context.attribution.origin)
    headers.set('X-Title', context.attribution.title)
  }

  return headers
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

function defaultMessage(status: number): string {
  switch (status) {
    case 401:
      return 'Authentication failed. Check your OpenRouter API key.'
    case 402:
      return 'Insufficient credits. Add more credits to continue.'
    case 403:
      return 'OpenRouter blocked the request.'
    case 404:
      return 'The requested model was not found.'
    case 429:
      return 'Rate limit exceeded. Please retry later.'
    case 503:
      return 'No available upstream provider could serve the request.'
    default:
      return `OpenRouter request failed with HTTP ${status}`
  }
}

async function httpError(
  response: Response,
  context: ProviderContext,
): Promise<OpenRouterRequestError> {
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
        : defaultMessage(response.status)
  const message = redactSecret(rawMessage, context.apiKey)
  const code = statusToCode(response.status, message)
  const metadata = envelope && isRecord(envelope.metadata) ? envelope.metadata : null
  const retryAfterSeconds =
    parseRetryAfter(response.headers.get('Retry-After')) ??
    (metadata ? numberOrNull(metadata.retry_after_seconds) : null)
  const requestId =
    response.headers.get('x-request-id') ??
    (envelope && typeof envelope.request_id === 'string' ? envelope.request_id : null)

  return new OpenRouterRequestError(code, message, {
    providerId: context.provider.id,
    status: response.status,
    retryable: isRetryableStatus(response.status),
    retryAfterSeconds,
    requestId,
    details: metadataDetails(metadata, retryAfterSeconds),
  })
}

function streamError(
  envelope: Record<string, unknown>,
  context: ProviderContext,
  responseId: string | null,
): OpenRouterRequestError {
  const status = numberOrNull(envelope.code)
  const rawMessage =
    typeof envelope.message === 'string'
      ? envelope.message
      : 'OpenRouter stream returned an error.'
  const message = redactSecret(rawMessage, context.apiKey)
  const code = status !== null ? statusToCode(status, message) : 'provider'
  const metadata = isRecord(envelope.metadata) ? envelope.metadata : null

  return new OpenRouterRequestError(code, message, {
    providerId: context.provider.id,
    status,
    retryable: false,
    requestId: responseId,
    details: metadataDetails(metadata, null),
  })
}

function cancelledError(context: ProviderContext, cause?: unknown): OpenRouterRequestError {
  return new OpenRouterRequestError('cancelled', 'OpenRouter request was cancelled.', {
    providerId: context.provider.id,
    cause,
  })
}

interface StreamMapperOptions {
  context: ProviderContext
  emit: (event: StreamEvent) => void
}

class StreamMapper {
  private readonly context: ProviderContext
  private readonly emit: (event: StreamEvent) => void
  private started = false
  private finished = false
  private responseId: string | null = null
  private error: OpenRouterRequestError | null = null
  private readonly emittedReasoning = new Set<string>()

  constructor(options: StreamMapperOptions) {
    this.context = options.context
    this.emit = options.emit
  }

  getError(): OpenRouterRequestError | null {
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
        new OpenRouterRequestError('parse', 'OpenRouter stream contained invalid JSON.', {
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

    this.ensureStarted()

    if (isRecord(payload.error)) {
      this.fail(streamError(payload.error, this.context, this.responseId))
      return
    }

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
        if (reasoning !== '' && !this.emittedReasoning.has(reasoning)) {
          this.emittedReasoning.add(reasoning)
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

  private fail(error: OpenRouterRequestError): void {
    this.error = error
  }
}

async function attemptStream(
  context: ProviderContext,
  body: Record<string, unknown>,
  signal: AbortSignal,
  emit: (event: StreamEvent) => void,
): Promise<OpenRouterRequestError | null> {
  const url = joinUrl(context.provider.baseUrl, '/chat/completions')
  let response: Response

  try {
    response = await context.fetch(url, {
      method: 'POST',
      headers: buildStreamHeaders(context),
      body: JSON.stringify(body),
      signal,
    })
  } catch (error) {
    if (signal.aborted) {
      throw cancelledError(context, error)
    }

    throw new OpenRouterRequestError('network', 'Could not reach OpenRouter.', {
      providerId: context.provider.id,
      retryable: true,
      cause: error,
    })
  }

  if (!response.ok) {
    throw await httpError(response, context)
  }

  if (!response.body) {
    throw new OpenRouterRequestError('provider', 'OpenRouter returned an empty stream.', {
      providerId: context.provider.id,
      status: response.status,
    })
  }

  const mapper = new StreamMapper({ context, emit })

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
      throw new OpenRouterRequestError('provider', 'The OpenRouter stream exceeded the buffer limit.', {
        providerId: context.provider.id,
        cause: error,
      })
    }

    if (error instanceof SseTransportError) {
      throw new OpenRouterRequestError('network', 'The OpenRouter stream was interrupted.', {
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
  signal: AbortSignal,
  receivedOutput: boolean,
): OpenRouterRequestError {
  if (signal.aborted) {
    return cancelledError(context, error)
  }

  if (error instanceof OpenRouterRequestError) {
    if (error.code === 'network' && receivedOutput) {
      return new OpenRouterRequestError('network', error.message, {
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

  return new OpenRouterRequestError('unknown', 'OpenRouter request failed.', {
    providerId: context.provider.id,
    cause: error,
  })
}

/**
 * Stream an OpenRouter chat completion.
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

  const emitError = (error: OpenRouterRequestError): void => {
    emit({ type: 'error', error: toChatError(error, now()) })
  }

  let body: Record<string, unknown>

  try {
    body = buildChatRequestBody(request, options.supportedParameters)
  } catch (error) {
    emitError(
      error instanceof OpenRouterRequestError
        ? error
        : new OpenRouterRequestError('invalid_request', 'The chat request was invalid.', {
            providerId: context.provider.id,
            cause: error,
          }),
    )
    return
  }

  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
    if (signal.aborted) {
      emitError(cancelledError(context))
      return
    }

    try {
      const error = await attemptStream(context, body, signal, emit)

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
        emitError(cancelledError(context))
        return
      }
    }
  }
}
