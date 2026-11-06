import type {
  ChatErrorCode,
  FinishReason,
  Usage,
} from '../domain'
import { mapAnthropicModels } from './openaiCatalog'
import {
  ProviderRequestError,
  listModels as listGenericModels,
  loadCatalog as loadGenericCatalog,
  refreshCatalog as refreshGenericCatalog,
  toChatError,
  validateKey as validateGenericKey,
  type ListModelsOptions,
  type NormalizedChatRequest,
  type OpenAICompatiblePreset,
  type ProviderContext,
  type StreamChatOptions,
  type StreamEvent,
} from './openaiCompatible'
import type { CatalogResult, ModelInfo, ProviderKeyInfo } from './openrouterCatalog'
import { classifyNetworkFailure, corsNetworkMessage } from './networkError'
import { getOpenAICompatiblePreset } from './presets'
import { RETRY_MAX_ATTEMPTS, retryDelayFor, sleep, type RetryPolicy } from './retry'
import {
  SseAbortError,
  SseBufferOverflowError,
  SseTransportError,
  readSse,
} from './sse'

/** Attachments resolved to bytes by the caller before serialization. */
export interface ResolvedAttachment {
  mimeType: string
  name?: string
  dataBase64: string
}

/**
 * Anthropic chat request.
 *
 * The shared normalized request keeps attachments out of `messages`; Anthropic
 * needs the base64 payload inline, so callers attach the resolved bytes here.
 */
export interface AnthropicChatRequest extends NormalizedChatRequest {
  attachments?: Record<string, ResolvedAttachment>
}

/** Anthropic's supported sampling controls for the phase-1 surface. */
export const DEFAULT_ANTHROPIC_SUPPORTED_PARAMETERS: readonly string[] = [
  'temperature',
  'top_p',
  'max_tokens',
  'stop',
  'stream',
]

/** Namespace that owns Anthropic-only advanced JSON. */
export const ANTHROPIC_EXTRA_NAMESPACE = 'anthropic'

const PROTECTED_ADVANCED_FIELDS = ['model', 'messages', 'stream', 'stream_options'] as const
const ANTHROPIC_VERSION = '2023-06-01'
const BROWSER_ACCESS_HEADER = 'anthropic-dangerous-direct-browser-access'

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function joinUrl(baseUrl: string, path: string): string {
  if (/^https?:\/\//i.test(path)) {
    return path
  }

  return `${baseUrl.replace(/\/+$/, '')}/${path.replace(/^\/+/, '')}`
}

function resolvePreset(
  context: ProviderContext,
  preset?: OpenAICompatiblePreset,
): OpenAICompatiblePreset {
  const resolved = preset ?? context.preset ?? getOpenAICompatiblePreset('anthropic')

  if (!resolved) {
    throw new ProviderRequestError('invalid_request', 'An Anthropic provider preset is required.')
  }

  return resolved
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

/**
 * Build the Anthropic Messages request headers.
 *
 * The key travels only in `x-api-key`. `anthropic-version` and the
 * browser-direct access header are web-only contract defaults and are always
 * present for this adapter.
 */
export function buildAnthropicHeaders(
  context: ProviderContext,
  preset?: OpenAICompatiblePreset,
): Headers {
  const resolved = resolvePreset(context, preset)
  const headers = new Headers()

  headers.set('Accept', 'text/event-stream')
  headers.set('content-type', 'application/json')

  for (const [name, value] of Object.entries(resolved.defaultHeaders)) {
    headers.set(name, value)
  }

  for (const [name, value] of Object.entries(context.headers ?? {})) {
    headers.set(name, value)
  }

  if (!headers.has('anthropic-version')) {
    headers.set('anthropic-version', ANTHROPIC_VERSION)
  }

  // This module is the web adapter, so the browser-direct header is required.
  headers.set(BROWSER_ACCESS_HEADER, 'true')

  const authName = resolved.auth.headerName ?? 'x-api-key'
  if (context.apiKey) {
    if (resolved.auth.type === 'bearer') {
      headers.set(authName, `${resolved.auth.scheme ?? 'Bearer'} ${context.apiKey}`)
    } else {
      headers.set(authName, context.apiKey)
    }
  }

  return headers
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

function contentBlocks(
  request: AnthropicChatRequest,
  message: AnthropicChatRequest['messages'][number],
): Array<Record<string, unknown>> {
  const blocks: Array<Record<string, unknown>> = []
  const attachments = request.attachments ?? {}

  for (const part of message.parts) {
    switch (part.type) {
      case 'text':
        if (part.text !== '') {
          blocks.push({ type: 'text', text: part.text })
        }
        break
      case 'image': {
        const attachment = attachments[part.attachmentId]
        if (!attachment) {
          throw new ProviderRequestError(
            'invalid_request',
            `Missing attachment ${part.attachmentId} for the Anthropic request.`,
          )
        }

        blocks.push({
          type: 'image',
          source: {
            type: 'base64',
            media_type: attachment.mimeType || part.mimeType,
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
            `Missing attachment ${part.attachmentId} for the Anthropic request.`,
          )
        }

        const name = attachment.name ?? part.name
        const body = /^text\//i.test(attachment.mimeType || part.mimeType)
          ? decodeBase64Utf8(attachment.dataBase64)
          : ''

        blocks.push({ type: 'text', text: `\n\n[File: ${name}]\n${body}` })
        break
      }
      default:
        // Reasoning, tool calls, and tool results are not replayed in phase 1.
        break
    }
  }

  return blocks
}

/**
 * Build the exact Anthropic `POST /messages` body.
 *
 * The system prompt is a top-level field, never a message. `max_tokens` is
 * required by Anthropic and is always sent when the caller supplied it.
 * Advanced JSON under `params.extra.anthropic` cannot override protected
 * fields.
 */
export function buildAnthropicRequestBody(
  request: AnthropicChatRequest,
  supportedParameters: readonly string[] = DEFAULT_ANTHROPIC_SUPPORTED_PARAMETERS,
): Record<string, unknown> {
  const supported = new Set(supportedParameters)
  const extra = request.params.extra?.[ANTHROPIC_EXTRA_NAMESPACE]
  const protectedFields = protectedAdvancedFields(extra)

  if (protectedFields.length > 0) {
    throw new ProviderRequestError(
      'invalid_request',
      `Advanced JSON cannot override protected fields: ${protectedFields.join(', ')}.`,
      { details: { protectedFields } },
    )
  }

  const body: Record<string, unknown> = {
    model: request.model,
  }

  if (request.system !== null) {
    body.system = request.system
  }

  body.messages = request.messages.map((message) => ({
    role: message.role === 'assistant' ? 'assistant' : 'user',
    content: contentBlocks(request, message),
  }))

  if (request.params.maxOutputTokens !== null) {
    body.max_tokens = request.params.maxOutputTokens
  }

  body.stream = true

  if (request.params.temperature !== null && supported.has('temperature')) {
    body.temperature = request.params.temperature
  }

  if (request.params.topP !== null && supported.has('top_p')) {
    body.top_p = request.params.topP
  }

  if (request.params.stop.length > 0 && supported.has('stop')) {
    body.stop_sequences = request.params.stop
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

function statusToCode(status: number, message: string): ChatErrorCode {
  if (status === 401) {
    return 'auth'
  }
  if (status === 402) {
    return 'quota'
  }
  if (status === 403) {
    return /moderation|policy|content|safety/i.test(message) ? 'content_filter' : 'permission'
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

    return /prompt is too long|context length|context_length|maximum context|too many tokens/i.test(
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
  const rawMessage =
    envelope && typeof envelope.message === 'string'
      ? envelope.message
      : `The Anthropic request failed with HTTP ${response.status}`
  const message = redactSecret(rawMessage, context.apiKey)
  const retryAfterSeconds = parseRetryAfter(response.headers.get('Retry-After'))
  const requestId =
    response.headers.get('request-id') ??
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
  if (typeof envelope.type === 'string') {
    switch (envelope.type) {
      case 'overloaded_error':
        return 529
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
): ProviderRequestError {
  const status = streamStatus(envelope)
  const rawMessage =
    typeof envelope.message === 'string' ? envelope.message : 'The Anthropic stream returned an error.'
  const message = redactSecret(rawMessage, context.apiKey)
  const code = status !== null ? statusToCode(status, message) : 'provider'

  return new ProviderRequestError(code, message, {
    providerId: context.provider.id,
    status,
    retryable: false,
  })
}

function mapFinishReason(reason: unknown): FinishReason {
  switch (reason) {
    case 'end_turn':
    case 'stop_sequence':
      return 'stop'
    case 'max_tokens':
      return 'length'
    case 'tool_use':
      return 'tool_calls'
    case 'refusal':
      return 'content_filter'
    default:
      return 'unknown'
  }
}

function cancelledError(context: ProviderContext, cause?: unknown): ProviderRequestError {
  return new ProviderRequestError('cancelled', 'The Anthropic request was cancelled.', {
    providerId: context.provider.id,
    cause,
  })
}

interface StreamState {
  responseId: string | null
  inputTokens: number | null
  outputTokens: number | null
  usageEmitted: boolean
  finishReason: FinishReason | null
}

export class AnthropicStreamMapper {
  private readonly context: ProviderContext
  private readonly emit: (event: StreamEvent) => void
  private readonly state: StreamState = {
    responseId: null,
    inputTokens: null,
    outputTokens: null,
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
        'The Anthropic stream contained invalid JSON.',
        { providerId: this.context.provider.id },
      )
      return
    }

    if (!isRecord(payload) || typeof payload.type !== 'string') {
      return
    }

    switch (payload.type) {
      case 'message_start':
        this.handleMessageStart(payload)
        break
      case 'content_block_start':
        this.handleContentBlockStart(payload)
        break
      case 'content_block_delta':
        this.handleContentBlockDelta(payload)
        break
      case 'content_block_stop':
        break
      case 'message_delta':
        this.handleMessageDelta(payload)
        break
      case 'message_stop':
        this.handleMessageStop()
        break
      case 'error':
        this.error = streamError(
          isRecord(payload.error) ? payload.error : payload,
          this.context,
        )
        break
      default:
        break
    }
  }

  finish(): void {
    if (this.error !== null || this.finished || !this.started) {
      return
    }

    this.emitUsage()
    this.emitFinish(this.state.finishReason ?? 'unknown')
  }

  private handleMessageStart(payload: Record<string, unknown>): void {
    const message = isRecord(payload.message) ? payload.message : null

    if (message && typeof message.id === 'string') {
      this.state.responseId = message.id
    }

    if (message && isRecord(message.usage)) {
      this.state.inputTokens = numberOrNull(message.usage.input_tokens)
      this.state.outputTokens = numberOrNull(message.usage.output_tokens)
    }

    if (!this.started) {
      this.started = true
      this.emit({ type: 'start', responseId: this.state.responseId })
    }
  }

  private handleContentBlockStart(payload: Record<string, unknown>): void {
    const block = isRecord(payload.content_block) ? payload.content_block : null

    if (!block) {
      return
    }

    if (block.type === 'text' && typeof block.text === 'string' && block.text !== '') {
      this.emit({ type: 'text-delta', text: block.text })
    }

    if (block.type === 'thinking' && typeof block.thinking === 'string' && block.thinking !== '') {
      this.emit({ type: 'reasoning-delta', text: block.thinking })
    }
  }

  private handleContentBlockDelta(payload: Record<string, unknown>): void {
    const delta = isRecord(payload.delta) ? payload.delta : null

    if (!delta) {
      return
    }

    if (delta.type === 'text_delta' && typeof delta.text === 'string' && delta.text !== '') {
      this.emit({ type: 'text-delta', text: delta.text })
      return
    }

    if (
      delta.type === 'thinking_delta' &&
      typeof delta.thinking === 'string' &&
      delta.thinking !== ''
    ) {
      this.emit({ type: 'reasoning-delta', text: delta.thinking })
    }
  }

  private handleMessageDelta(payload: Record<string, unknown>): void {
    const delta = isRecord(payload.delta) ? payload.delta : null

    if (delta && 'stop_reason' in delta) {
      this.state.finishReason = mapFinishReason(delta.stop_reason)
    }

    if (isRecord(payload.usage)) {
      const output = numberOrNull(payload.usage.output_tokens)
      if (output !== null) {
        this.state.outputTokens = output
      }
      const input = numberOrNull(payload.usage.input_tokens)
      if (input !== null) {
        this.state.inputTokens = input
      }
    }

    this.emitUsage()
  }

  private handleMessageStop(): void {
    if (this.finished) {
      return
    }

    this.emitUsage()
    this.emitFinish(this.state.finishReason ?? 'stop')
  }

  private usage(): Usage | null {
    const { inputTokens, outputTokens } = this.state

    if (inputTokens === null && outputTokens === null) {
      return null
    }

    const totalTokens =
      inputTokens !== null && outputTokens !== null ? inputTokens + outputTokens : null

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

  return new ProviderRequestError('unknown', 'The Anthropic request failed.', {
    providerId: context.provider.id,
    cause: error,
  })
}

/**
 * Stream an Anthropic Messages completion.
 *
 * Events follow the shared order: `start`, text/reasoning deltas, `usage`,
 * `finish`, or a single terminal `error`. Thinking deltas stay in
 * `reasoning-delta` and are never mixed into answer text.
 */
export async function streamAnthropicChat(
  context: ProviderContext,
  request: AnthropicChatRequest,
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

  try {
    // Validate serialization before spending a request.
    buildAnthropicRequestBody(
      request,
      options.supportedParameters ?? DEFAULT_ANTHROPIC_SUPPORTED_PARAMETERS,
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
        preset,
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
  preset: OpenAICompatiblePreset,
  request: AnthropicChatRequest,
  options: StreamChatOptions,
  signal: AbortSignal,
  emit: (event: StreamEvent) => void,
): Promise<ProviderRequestError | null> {
  const supported = options.supportedParameters ?? DEFAULT_ANTHROPIC_SUPPORTED_PARAMETERS
  const headers = buildAnthropicHeaders(context, preset)
  const body = buildAnthropicRequestBody(request, supported)
  const url = joinUrl(context.provider.baseUrl, '/messages')
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

    const failure = classifyNetworkFailure(error)

    if (failure === 'cors') {
      throw new ProviderRequestError('cors', corsNetworkMessage(preset.displayName), {
        providerId: context.provider.id,
        retryable: false,
        cause: error,
      })
    }

    throw new ProviderRequestError(
      'network',
      'Could not reach Anthropic. Check your connection and base URL.',
      { providerId: context.provider.id, retryable: true, cause: error },
    )
  }

  if (!response.ok) {
    throw await httpError(response, context)
  }

  if (!response.body) {
    throw new ProviderRequestError('provider', 'Anthropic returned an empty stream.', {
      providerId: context.provider.id,
      status: response.status,
    })
  }

  const mapper = new AnthropicStreamMapper({ context, emit })

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
        'The Anthropic stream exceeded the buffer limit.',
        { providerId: context.provider.id, cause: error },
      )
    }

    if (error instanceof SseTransportError) {
      throw new ProviderRequestError('network', 'The Anthropic stream was interrupted.', {
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

function withPreset(context: ProviderContext): ProviderContext {
  if (context.preset) {
    return context
  }

  const preset = getOpenAICompatiblePreset('anthropic')
  return preset ? { ...context, preset } : context
}

/** Validate an Anthropic key by loading `GET /models`; the key stays in a header. */
export async function validateKey(
  context: ProviderContext,
  signal: AbortSignal,
): Promise<ProviderKeyInfo> {
  return validateGenericKey(withPreset(context), signal)
}

/** Load the Anthropic catalog with the shared cache and last-known-good rules. */
export async function loadCatalog(
  context: ProviderContext,
  signal: AbortSignal,
  options: ListModelsOptions = {},
): Promise<CatalogResult> {
  const prepared = withPreset(context)
  const preset = options.preset ?? prepared.preset
  return loadGenericCatalog(prepared, signal, { ...options, preset })
}

/** Adapter-compatible catalog method. */
export async function listModels(
  context: ProviderContext,
  signal: AbortSignal,
  options: ListModelsOptions = {},
): Promise<ModelInfo[]> {
  const prepared = withPreset(context)
  const preset = options.preset ?? prepared.preset
  return listGenericModels(prepared, signal, { ...options, preset })
}

/** Force a catalog refresh while preserving last-known-good behavior. */
export async function refreshCatalog(
  context: ProviderContext,
  signal: AbortSignal,
  options: Omit<ListModelsOptions, 'forceRefresh'> = {},
): Promise<CatalogResult> {
  const prepared = withPreset(context)
  const preset = options.preset ?? prepared.preset
  return refreshGenericCatalog(prepared, signal, { ...options, preset })
}

/** Map an Anthropic `/models` payload to normalized entries. */
export { mapAnthropicModels }

/** Adapter surface for the Anthropic preset. */
export const anthropicAdapter = {
  kind: 'anthropic' as const,
  validateKey,
  listModels,
  streamChat: streamAnthropicChat,
}
