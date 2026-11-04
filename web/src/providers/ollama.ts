import type { ChatErrorCode, FinishReason, Usage } from '../domain'
import { mapOllamaModels } from './openaiCatalog'
import {
  ProviderRequestError,
  loadCatalog as loadGenericCatalog,
  listModels as listGenericModels,
  refreshCatalog as refreshGenericCatalog,
  streamChat as streamGenericChat,
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
import { getOpenAICompatiblePreset } from './presets'
import { RETRY_MAX_ATTEMPTS, retryDelayFor, sleep, type RetryPolicy } from './retry'

/**
 * Ollama native chat and discovery.
 *
 * Native mode uses `POST /api/chat` with NDJSON lines and `GET /api/tags` for
 * discovery. The OpenAI-compatible path at `/v1/chat/completions` remains
 * available for custom local servers and for users who explicitly opt in.
 *
 * Browser access to Ollama requires CORS to be enabled on the server, for
 * example:
 *
 * ```text
 * OLLAMA_ORIGINS=http://localhost:5173 ollama serve
 * ```
 *
 * `OLLAMA_ORIGINS=*` works during local development but is not recommended on
 * a shared machine. Browsers may also block HTTPS pages from calling a
 * cleartext `http://localhost` endpoint or apply private-network restrictions.
 */

/** Attachments resolved to bytes by the caller before serialization. */
export interface OllamaAttachment {
  mimeType: string
  name?: string
  dataBase64: string
}

/**
 * Ollama chat request.
 *
 * The shared normalized request keeps attachment bytes out of `messages`;
 * Ollama's native endpoint expects image base64 strings inline, so callers
 * attach the resolved bytes here.
 */
export interface OllamaChatRequest extends NormalizedChatRequest {
  attachments?: Record<string, OllamaAttachment>
}

/** Ollama's supported sampling controls for the phase-1 surface. */
export const DEFAULT_OLLAMA_SUPPORTED_PARAMETERS: readonly string[] = [
  'temperature',
  'top_p',
  'max_tokens',
  'stop',
  'stream',
]

/** Namespace that owns Ollama-only advanced JSON. */
export const OLLAMA_EXTRA_NAMESPACE = 'ollama'

/** Guidance shown when a browser cannot reach a local Ollama server. */
export const OLLAMA_ORIGINS_GUIDANCE =
  'Could not reach Ollama. Start it with OLLAMA_ORIGINS set to this app origin, ' +
  'for example OLLAMA_ORIGINS=http://localhost:5173 ollama serve, and check that ' +
  'the browser allows this page to call a local http:// endpoint.'

/** Transport selected for one Ollama chat request. */
export type OllamaTransport = 'native' | 'openai-compatible'

const PROTECTED_ADVANCED_FIELDS = ['model', 'messages', 'stream', 'stream_options'] as const
const OLLAMA_CONTROL_FIELDS = ['transport', 'mode'] as const
/** Default maximum number of characters retained for an incomplete NDJSON line. */
export const DEFAULT_MAX_NDJSON_BUFFER_CHARS = 1024 * 1024

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function joinUrl(baseUrl: string, path: string): string {
  if (/^https?:\/\//i.test(path)) {
    return path
  }

  return `${baseUrl.replace(/\/+$/, '')}/${path.replace(/^\/+/, '')}`
}

function numberOrNull(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function resolvePreset(
  context: ProviderContext,
  preset?: OpenAICompatiblePreset,
): OpenAICompatiblePreset {
  const resolved = preset ?? context.preset ?? getOpenAICompatiblePreset('ollama')

  if (!resolved) {
    throw new ProviderRequestError('invalid_request', 'An Ollama provider preset is required.')
  }

  return resolved
}

/**
 * Ollama's native API lives at the server root, while the contract preset's
 * base URL points at the OpenAI-compatible `/v1` prefix.
 */
export function ollamaNativeBaseUrl(baseUrl: string): string {
  return baseUrl.replace(/\/+$/, '').replace(/\/v1$/i, '')
}

/** Build the native `POST /api/chat` URL. */
export function buildOllamaChatUrl(baseUrl: string): string {
  return joinUrl(ollamaNativeBaseUrl(baseUrl), 'api/chat')
}

function applyAuth(headers: Headers, preset: OpenAICompatiblePreset, apiKey: string | null): void {
  if (!apiKey || preset.auth.type === 'none') {
    return
  }

  if (preset.auth.type === 'bearer') {
    headers.set(preset.auth.headerName ?? 'Authorization', `${preset.auth.scheme ?? 'Bearer'} ${apiKey}`)
    return
  }

  if (preset.auth.type === 'x-api-key') {
    headers.set(preset.auth.headerName ?? 'x-api-key', apiKey)
  }
  // Query-string keys are never used: URLs leak into logs and error reports.
}

/** Build native Ollama request headers. */
export function buildOllamaHeaders(
  context: ProviderContext,
  preset?: OpenAICompatiblePreset,
  accept = 'application/x-ndjson',
): Headers {
  const resolved = resolvePreset(context, preset)
  const headers = new Headers()

  headers.set('Accept', accept)
  headers.set('Content-Type', 'application/json')

  for (const [name, value] of Object.entries(resolved.defaultHeaders)) {
    headers.set(name, value)
  }

  for (const [name, value] of Object.entries(context.headers ?? {})) {
    headers.set(name, value)
  }

  applyAuth(headers, resolved, context.apiKey)

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

function ollamaExtra(request: NormalizedChatRequest): Record<string, unknown> | null {
  const extra = request.params.extra?.[OLLAMA_EXTRA_NAMESPACE]
  return isRecord(extra) ? extra : null
}

function withoutTransportControl(request: NormalizedChatRequest): NormalizedChatRequest {
  const extra = request.params.extra
  const ollama = isRecord(extra) ? extra[OLLAMA_EXTRA_NAMESPACE] : null

  if (!isRecord(extra) || !isRecord(ollama)) {
    return request
  }

  if (!OLLAMA_CONTROL_FIELDS.some((field) => field in ollama)) {
    return request
  }

  const nextOllama = { ...ollama }
  for (const field of OLLAMA_CONTROL_FIELDS) {
    delete nextOllama[field]
  }

  return {
    ...request,
    params: {
      ...request.params,
      extra: { ...extra, [OLLAMA_EXTRA_NAMESPACE]: nextOllama },
    },
  }
}

function messageContent(
  request: OllamaChatRequest,
  message: OllamaChatRequest['messages'][number],
): { content: string; images: string[] } {
  const attachments = request.attachments ?? {}
  const textParts: string[] = []
  const images: string[] = []

  for (const part of message.parts) {
    switch (part.type) {
      case 'text':
        if (part.text !== '') {
          textParts.push(part.text)
        }
        break
      case 'image': {
        const attachment = attachments[part.attachmentId]
        if (!attachment) {
          throw new ProviderRequestError(
            'invalid_request',
            `Missing attachment ${part.attachmentId} for the Ollama request.`,
          )
        }

        images.push(attachment.dataBase64)
        break
      }
      case 'file': {
        const attachment = attachments[part.attachmentId]
        if (!attachment) {
          throw new ProviderRequestError(
            'invalid_request',
            `Missing attachment ${part.attachmentId} for the Ollama request.`,
          )
        }

        const name = attachment.name ?? part.name
        const body = /^text\//i.test(attachment.mimeType || part.mimeType)
          ? decodeBase64Utf8(attachment.dataBase64)
          : ''

        textParts.push(`\n\n[File: ${name}]\n${body}`)
        break
      }
      default:
        // Reasoning, tool calls, and tool results are not replayed in phase 1.
        break
    }
  }

  return { content: textParts.join(''), images }
}

/**
 * Build the exact native Ollama `POST /api/chat` body.
 *
 * The system prompt is the first message. Text and text-file parts become the
 * message `content`; images become the message `images` array. Generation
 * controls live under `options`, and advanced JSON under `params.extra.ollama`
 * may not override protected fields or the transport selector.
 */
export function buildOllamaRequestBody(
  request: OllamaChatRequest,
  supportedParameters: readonly string[] = DEFAULT_OLLAMA_SUPPORTED_PARAMETERS,
): Record<string, unknown> {
  const supported = new Set(supportedParameters)
  const extra = ollamaExtra(request)
  const protectedFields = protectedAdvancedFields(extra)

  if (protectedFields.length > 0) {
    throw new ProviderRequestError(
      'invalid_request',
      `Advanced JSON cannot override protected fields: ${protectedFields.join(', ')}.`,
      { details: { protectedFields } },
    )
  }

  const messages: Array<Record<string, unknown>> = []

  if (request.system !== null) {
    messages.push({ role: 'system', content: request.system })
  }

  for (const message of request.messages) {
    const { content, images } = messageContent(request, message)
    const entry: Record<string, unknown> = {
      role: message.role,
      content,
    }

    if (images.length > 0) {
      entry.images = images
    }

    messages.push(entry)
  }

  const body: Record<string, unknown> = {
    model: request.model,
    messages,
    stream: true,
  }

  const options: Record<string, unknown> = {}

  if (request.params.maxOutputTokens !== null && supported.has('max_tokens')) {
    options.num_predict = request.params.maxOutputTokens
  }

  if (request.params.temperature !== null && supported.has('temperature')) {
    options.temperature = request.params.temperature
  }

  if (request.params.topP !== null && supported.has('top_p')) {
    options.top_p = request.params.topP
  }

  if (request.params.stop.length > 0 && supported.has('stop')) {
    options.stop = request.params.stop
  }

  if (Object.keys(options).length > 0) {
    body.options = options
  }

  if (extra) {
    for (const [field, value] of Object.entries(extra)) {
      if ((PROTECTED_ADVANCED_FIELDS as readonly string[]).includes(field)) {
        continue
      }

      if ((OLLAMA_CONTROL_FIELDS as readonly string[]).includes(field)) {
        continue
      }

      body[field] = value
    }
  }

  return body
}

/**
 * Native mode is the default for the Ollama preset. The OpenAI-compatible
 * fallback is selected deterministically when the preset is not Ollama (for
 * example a Custom local server) or when the request sets
 * `params.extra.ollama.transport` to `openai-compatible`.
 */
export function selectOllamaTransport(
  preset: OpenAICompatiblePreset | undefined,
  request: NormalizedChatRequest,
): OllamaTransport {
  const extra = ollamaExtra(request)
  const requested = extra?.transport ?? extra?.mode

  if (requested === 'native' || requested === 'openai-compatible') {
    return requested
  }

  if (preset?.modelDiscovery.format === 'ollama-tags' || preset?.id === 'ollama') {
    return 'native'
  }

  return 'openai-compatible'
}

/** Map an Ollama `done_reason` to the normalized reason. */
export function mapOllamaFinishReason(reason: unknown): FinishReason {
  switch (reason) {
    case 'stop':
      return 'stop'
    case 'length':
      return 'length'
    case 'content_filter':
      return 'content_filter'
    case 'tool_calls':
      return 'tool_calls'
    default:
      return 'unknown'
  }
}

function redactSecret(message: string, secret: string | null): string {
  if (!secret) {
    return message
  }

  return message.split(secret).join('[redacted]')
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
  if (status === 413) {
    return 'context_length'
  }
  if (status === 429) {
    return 'rate_limit'
  }
  if (status === 400) {
    return /context length|context_length|too many tokens|too long/i.test(message)
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

function errorMessage(payload: unknown): string | null {
  if (!isRecord(payload)) {
    return null
  }

  if (typeof payload.error === 'string' && payload.error.trim() !== '') {
    return payload.error
  }

  if (isRecord(payload.error) && typeof payload.error.message === 'string') {
    return payload.error.message
  }

  if (typeof payload.message === 'string' && payload.message.trim() !== '') {
    return payload.message
  }

  return null
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

  const rawMessage =
    errorMessage(payload) ?? `The Ollama request failed with HTTP ${response.status}`
  const message = redactSecret(rawMessage, context.apiKey)
  const retryAfterSeconds = parseRetryAfter(response.headers.get('Retry-After'))

  return new ProviderRequestError(statusToCode(response.status, message), message, {
    providerId: context.provider.id,
    status: response.status,
    retryable: isRetryableStatus(response.status),
    retryAfterSeconds,
    requestId: response.headers.get('x-request-id'),
    details: retryAfterSeconds === null ? null : { retryAfterSeconds },
  })
}

function streamError(payload: Record<string, unknown>, context: ProviderContext): ProviderRequestError {
  const rawMessage = errorMessage(payload) ?? 'The Ollama stream returned an error.'
  const message = redactSecret(rawMessage, context.apiKey)
  const status = numberOrNull(payload.status) ?? 500

  return new ProviderRequestError(statusToCode(status, message), message, {
    providerId: context.provider.id,
    status,
    retryable: false,
  })
}

function cancelledError(context: ProviderContext, cause?: unknown): ProviderRequestError {
  return new ProviderRequestError('cancelled', 'The Ollama request was cancelled.', {
    providerId: context.provider.id,
    cause,
  })
}

/** Minimal response shape needed by the NDJSON reader. A real `Response` fits. */
export interface NdjsonResponse {
  readonly body: ReadableStream<Uint8Array> | null
}

export interface ReadNdjsonOptions {
  /** Called once per complete non-empty line. */
  onLine: (line: string) => void
  /** Stops reading promptly and rejects with `NdjsonAbortError`. */
  signal?: AbortSignal
  /** Overrides `DEFAULT_MAX_NDJSON_BUFFER_CHARS`; useful for tests. */
  maxBufferChars?: number
}

/** Base class for normalized NDJSON transport failures. */
export class NdjsonTransportError extends Error {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options)
    this.name = 'NdjsonTransportError'
  }
}

/** The response did not expose a readable body. */
export class NdjsonMissingBodyError extends NdjsonTransportError {
  constructor() {
    super('NDJSON response body is missing')
    this.name = 'NdjsonMissingBodyError'
  }
}

/** An incomplete line grew past the configured buffer limit. */
export class NdjsonBufferOverflowError extends NdjsonTransportError {
  readonly maxBufferChars: number

  constructor(maxBufferChars: number) {
    super(`NDJSON buffer exceeded the ${maxBufferChars} character limit`)
    this.name = 'NdjsonBufferOverflowError'
    this.maxBufferChars = maxBufferChars
  }
}

/** Reading was stopped by an `AbortSignal`. */
export class NdjsonAbortError extends NdjsonTransportError {
  constructor() {
    super('NDJSON read was aborted')
    this.name = 'AbortError'
  }
}

/**
 * Read an NDJSON response until the stream ends or the signal aborts.
 *
 * Line endings are normalized before splitting, so CRLF, LF, and lone CR all
 * work even when a CRLF pair is split across chunks. The final partial line is
 * flushed when the stream ends without a trailing newline.
 */
export async function readNdjson(
  response: NdjsonResponse,
  options: ReadNdjsonOptions,
): Promise<void> {
  const { onLine, signal, maxBufferChars = DEFAULT_MAX_NDJSON_BUFFER_CHARS } = options

  if (!response.body) {
    throw new NdjsonMissingBodyError()
  }

  if (signal?.aborted) {
    throw new NdjsonAbortError()
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let pendingCarriageReturn = false

  const normalizeChunk = (chunk: string): string => {
    let text = chunk

    if (pendingCarriageReturn) {
      text = `\r${text}`
      pendingCarriageReturn = false
    }

    if (text.endsWith('\r')) {
      pendingCarriageReturn = true
      text = text.slice(0, -1)
    }

    return text.replace(/\r\n/g, '\n').replace(/\r/g, '\n')
  }

  const emitLine = (line: string): void => {
    if (line.trim() !== '') {
      onLine(line)
    }
  }

  const drainCompleteLines = (): void => {
    let boundary = buffer.indexOf('\n')

    while (boundary !== -1) {
      const line = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 1)
      emitLine(line)
      boundary = buffer.indexOf('\n')
    }

    if (buffer.length > maxBufferChars) {
      throw new NdjsonBufferOverflowError(maxBufferChars)
    }
  }

  const onAbort = (): void => {
    void reader.cancel().catch(() => {})
  }

  signal?.addEventListener('abort', onAbort, { once: true })

  try {
    while (true) {
      if (signal?.aborted) {
        throw new NdjsonAbortError()
      }

      let result: ReadableStreamReadResult<Uint8Array>
      try {
        result = await reader.read()
      } catch (error) {
        if (signal?.aborted) {
          throw new NdjsonAbortError()
        }
        throw new NdjsonTransportError('Failed to read the NDJSON stream', { cause: error })
      }

      if (signal?.aborted) {
        throw new NdjsonAbortError()
      }

      if (result.done) {
        break
      }

      if (result.value) {
        buffer += normalizeChunk(decoder.decode(result.value, { stream: true }))
        drainCompleteLines()
      }
    }

    const tail = decoder.decode()
    if (tail) {
      buffer += normalizeChunk(tail)
    }

    if (pendingCarriageReturn) {
      buffer += '\n'
      pendingCarriageReturn = false
    }

    drainCompleteLines()

    if (buffer !== '') {
      const finalLine = buffer
      buffer = ''
      emitLine(finalLine)
    }
  } finally {
    signal?.removeEventListener('abort', onAbort)
    try {
      reader.releaseLock()
    } catch {
      // The reader may already be released after cancellation.
    }
  }
}

interface StreamState {
  inputTokens: number | null
  outputTokens: number | null
  usageEmitted: boolean
  finished: boolean
}

/** Map native Ollama NDJSON lines to normalized stream events. */
export class OllamaStreamMapper {
  private readonly context: ProviderContext
  private readonly emit: (event: StreamEvent) => void
  private readonly state: StreamState = {
    inputTokens: null,
    outputTokens: null,
    usageEmitted: false,
    finished: false,
  }

  private started = false
  private error: ProviderRequestError | null = null

  constructor(options: { context: ProviderContext; emit: (event: StreamEvent) => void }) {
    this.context = options.context
    this.emit = options.emit
  }

  getError(): ProviderRequestError | null {
    return this.error
  }

  handleLine(line: string): void {
    if (this.error !== null) {
      return
    }

    let payload: unknown

    try {
      payload = JSON.parse(line)
    } catch {
      this.error = new ProviderRequestError(
        'parse',
        'The Ollama stream contained invalid JSON.',
        { providerId: this.context.provider.id },
      )
      return
    }

    if (!isRecord(payload)) {
      return
    }

    if (payload.error !== undefined && payload.error !== null) {
      this.error = streamError(payload, this.context)
      return
    }

    if (!this.started) {
      this.started = true
      this.emit({ type: 'start', responseId: null })
    }

    const message = isRecord(payload.message) ? payload.message : null

    if (message && typeof message.content === 'string' && message.content !== '') {
      this.emit({ type: 'text-delta', text: message.content })
    }

    if (message && typeof message.thinking === 'string' && message.thinking !== '') {
      this.emit({ type: 'reasoning-delta', text: message.thinking })
    }

    this.state.inputTokens = numberOrNull(payload.prompt_eval_count) ?? this.state.inputTokens
    this.state.outputTokens = numberOrNull(payload.eval_count) ?? this.state.outputTokens

    if (payload.done === true) {
      const reason =
        typeof payload.done_reason === 'string'
          ? mapOllamaFinishReason(payload.done_reason)
          : 'stop'
      this.emitFinish(reason)
      this.emitUsage()
    }
  }

  finish(): void {
    if (this.error !== null || !this.started || this.state.finished) {
      return
    }

    this.emitFinish('unknown')
    this.emitUsage()
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
    if (this.state.finished) {
      return
    }

    this.emit({ type: 'finish', reason })
    this.state.finished = true
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

  return new ProviderRequestError('unknown', 'The Ollama request failed.', {
    providerId: context.provider.id,
    cause: error,
  })
}

/**
 * Stream an Ollama chat completion.
 *
 * Native NDJSON is used for the Ollama preset. A Custom provider that points at
 * a local OpenAI-compatible server, or a request that sets
 * `params.extra.ollama.transport` to `openai-compatible`, uses the shared
 * OpenAI-compatible transport instead.
 *
 * Events follow the shared order: `start`, text/reasoning deltas, `finish`,
 * `usage`, or a single terminal `error`.
 */
export async function streamOllamaChat(
  context: ProviderContext,
  request: OllamaChatRequest,
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

  if (selectOllamaTransport(preset, request) === 'openai-compatible') {
    return streamGenericChat(context, withoutTransportControl(request), signal, onEvent, {
      ...options,
      preset,
    })
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
    buildOllamaRequestBody(
      request,
      options.supportedParameters ?? DEFAULT_OLLAMA_SUPPORTED_PARAMETERS,
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
  request: OllamaChatRequest,
  options: StreamChatOptions,
  signal: AbortSignal,
  emit: (event: StreamEvent) => void,
): Promise<ProviderRequestError | null> {
  const supported = options.supportedParameters ?? DEFAULT_OLLAMA_SUPPORTED_PARAMETERS
  const headers = buildOllamaHeaders(context, preset)
  const body = buildOllamaRequestBody(request, supported)
  const url = buildOllamaChatUrl(context.provider.baseUrl)
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

    throw new ProviderRequestError('network', OLLAMA_ORIGINS_GUIDANCE, {
      providerId: context.provider.id,
      retryable: true,
      cause: error,
    })
  }

  if (!response.ok) {
    throw await httpError(response, context)
  }

  if (!response.body) {
    throw new ProviderRequestError('provider', 'Ollama returned an empty stream.', {
      providerId: context.provider.id,
      status: response.status,
    })
  }

  const mapper = new OllamaStreamMapper({ context, emit })

  try {
    await readNdjson(response, {
      onLine: (line) => mapper.handleLine(line),
      signal,
    })
  } catch (error) {
    if (error instanceof NdjsonAbortError || signal.aborted) {
      throw cancelledError(context, error)
    }

    if (error instanceof NdjsonBufferOverflowError) {
      throw new ProviderRequestError(
        'provider',
        'The Ollama stream exceeded the buffer limit.',
        { providerId: context.provider.id, cause: error },
      )
    }

    if (error instanceof NdjsonTransportError) {
      throw new ProviderRequestError('network', 'The Ollama stream was interrupted.', {
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

  const preset = getOpenAICompatiblePreset('ollama')
  return preset ? { ...context, preset } : context
}

/** Validate an Ollama endpoint; no key is required, so this is a no-op. */
export async function validateKey(
  context: ProviderContext,
  signal: AbortSignal,
): Promise<ProviderKeyInfo> {
  return validateGenericKey(withPreset(context), signal)
}

/** Load the Ollama catalog from `GET /api/tags` with cache rules. */
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

/** Map an Ollama `/api/tags` payload to normalized entries. */
export { mapOllamaModels }

/** Adapter surface for the Ollama preset. */
export const ollamaAdapter = {
  kind: 'openai-compatible' as const,
  validateKey,
  listModels,
  streamChat: streamOllamaChat,
}
