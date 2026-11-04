import { newId } from '../../domain'
import type { ChatError, ContentPart, Conversation, Message, MessageStatus } from '../../domain'
import type { ProviderContext } from '../../providers/openrouter'
import type {
  NormalizedChatRequest,
  StreamChatOptions,
  StreamEvent,
} from '../../providers/openrouterStream'
import { OpenRouterRequestError, toChatError } from '../../providers/openrouterStream'
import {
  createStreamingState,
  reduceStream,
  streamingStoreApi,
  type StreamingState,
  type StreamingStoreApi,
} from './streamingStore'

/** Default UI flush cadence, inside the 33-50 ms range from spec 8.4. */
export const DEFAULT_FLUSH_INTERVAL_MS = 40

/** Default persistence cadence from spec 8.4. */
export const DEFAULT_PERSIST_INTERVAL_MS = 1000

/**
 * Adapter surface used by the runner.
 *
 * `streamChat` from the OpenRouter adapter satisfies this signature. Tests
 * inject a controllable fake instead of opening a network connection.
 */
export type ChatStreamAdapter = (
  context: ProviderContext,
  request: NormalizedChatRequest,
  signal: AbortSignal,
  onEvent: (event: StreamEvent) => void,
  options?: StreamChatOptions,
) => Promise<void>

/** Minimal message persistence surface used by the runner. */
export interface ChatMessageStore {
  appendMessage(message: Message): Promise<unknown>
}

/** Document surface used for `visibilitychange` persistence. */
export interface ChatRunnerDocument {
  addEventListener(type: 'visibilitychange', listener: () => void): void
  removeEventListener(type: 'visibilitychange', listener: () => void): void
  visibilityState?: string
}

/** Window surface used for `pagehide` persistence. */
export interface ChatRunnerWindow {
  addEventListener(type: 'pagehide', listener: () => void): void
  removeEventListener(type: 'pagehide', listener: () => void): void
}

export interface ChatRunnerLifecycle {
  document?: ChatRunnerDocument
  window?: ChatRunnerWindow
}

export interface ChatRunnerOptions {
  adapter: ChatStreamAdapter
  messages: ChatMessageStore
  store?: StreamingStoreApi
  /** Injectable epoch-millisecond clock for deterministic timing tests. */
  now?: () => number
  newId?: () => string
  flushIntervalMs?: number
  persistIntervalMs?: number
  lifecycle?: ChatRunnerLifecycle
}

export interface ChatRunInput {
  conversation: Conversation
  context: ProviderContext
  request: NormalizedChatRequest
  /** Optional external cancellation signal, linked to the runner's controller. */
  signal?: AbortSignal
  /** Assistant message sequence. Defaults to `conversation.messageCount + 1`. */
  sequence?: number
  streamOptions?: StreamChatOptions
}

export interface RegenerateInput extends ChatRunInput {
  /**
   * The assistant message to replace. The caller must confirm this target with
   * the user before calling; the runner reuses its id and sequence so
   * regeneration never creates a branch.
   */
  target: Message
}

export type ChatRunFailureReason = 'busy' | 'storage' | 'invalid_target'

export type ChatRunResult =
  | { ok: true; message: Message; state: StreamingState }
  | { ok: false; reason: ChatRunFailureReason; error?: ChatError }

interface ActiveStream {
  conversationId: string
  controller: AbortController
  state: StreamingState
  message: Message
  dirty: boolean
  persistDirty: boolean
  cancelled: boolean
  flushTimer: ReturnType<typeof setInterval> | null
  persistTimer: ReturnType<typeof setInterval> | null
  persister: SerialMessagePersister
  done: Promise<void>
  finalMessage: Message | null
  visibilityListener: (() => void) | null
  pageHideListener: (() => void) | null
}

function defaultLifecycle(): ChatRunnerLifecycle {
  const lifecycle: ChatRunnerLifecycle = {}

  if (typeof document !== 'undefined') {
    lifecycle.document = document
  }

  if (typeof window !== 'undefined') {
    lifecycle.window = window
  }

  return lifecycle
}

function storageError(error: unknown, now: number): ChatError {
  return {
    code: 'storage',
    message: error instanceof Error ? error.message : 'Could not save the message.',
    providerId: null,
    status: null,
    retryable: false,
    retryAfterSeconds: null,
    requestId: null,
    details: null,
    at: new Date(now).toISOString(),
  }
}

/**
 * Serialize message writes so persistence can never overlap or reorder.
 *
 * `enqueue` coalesces to the newest snapshot while a write is in flight. The
 * final `flush` waits for the queue to drain and rethrows the last write error
 * so the runner can report a storage failure.
 */
export class SerialMessagePersister {
  private readonly write: (message: Message) => Promise<unknown>
  private pending: Message | null = null
  private running: Promise<void> | null = null
  private lastError: unknown = null

  constructor(write: (message: Message) => Promise<unknown>) {
    this.write = write
  }

  enqueue(message: Message): void {
    this.pending = message

    if (this.running === null) {
      this.running = this.drain()
    }
  }

  async flush(message?: Message): Promise<void> {
    if (message !== undefined) {
      this.enqueue(message)
    }

    if (this.running !== null) {
      await this.running
    }

    if (this.lastError !== null) {
      const error = this.lastError
      this.lastError = null
      throw error
    }
  }

  private async drain(): Promise<void> {
    try {
      while (this.pending !== null) {
        const next = this.pending
        this.pending = null

        try {
          await this.write(next)
          this.lastError = null
        } catch (error) {
          this.lastError = error
        }
      }
    } finally {
      this.running = null
    }
  }
}

/**
 * Non-React service that owns one OpenRouter stream per conversation.
 *
 * Responsibilities:
 * - create and persist the assistant placeholder;
 * - reduce normalized events into a local streaming buffer;
 * - flush UI snapshots at a bounded cadence;
 * - persist partial text about once per second and on lifecycle boundaries;
 * - enforce one active stream per conversation and a send lock;
 * - cancel, regenerate, and normalize terminal errors.
 */
export class ChatRunner {
  private readonly adapter: ChatStreamAdapter
  private readonly messages: ChatMessageStore
  private readonly store: StreamingStoreApi
  private readonly now: () => number
  private readonly newId: () => string
  private readonly flushIntervalMs: number
  private readonly persistIntervalMs: number
  private readonly lifecycle: ChatRunnerLifecycle
  private readonly active = new Map<string, ActiveStream>()

  constructor(options: ChatRunnerOptions) {
    this.adapter = options.adapter
    this.messages = options.messages
    this.store = options.store ?? streamingStoreApi
    this.now = options.now ?? Date.now
    this.newId = options.newId ?? newId
    this.flushIntervalMs = options.flushIntervalMs ?? DEFAULT_FLUSH_INTERVAL_MS
    this.persistIntervalMs = options.persistIntervalMs ?? DEFAULT_PERSIST_INTERVAL_MS
    this.lifecycle = options.lifecycle ?? defaultLifecycle()
  }

  /** True while a stream is active for the conversation. */
  isStreaming(conversationId: string): boolean {
    return this.active.has(conversationId)
  }

  /** Conversation IDs with an active stream. */
  activeConversationIds(): string[] {
    return [...this.active.keys()]
  }

  /**
   * Start a new assistant response.
   *
   * The send lock is acquired synchronously before the first await, so a
   * second call for the same conversation returns `busy` instead of starting a
   * duplicate stream.
   */
  async send(input: ChatRunInput): Promise<ChatRunResult> {
    return this.start(input)
  }

  /**
   * Replace an existing assistant message in place.
   *
   * The target must be an assistant message in the same conversation. Its id
   * and sequence are reused, so the persisted history is replaced rather than
   * branched.
   */
  async regenerate(input: RegenerateInput): Promise<ChatRunResult> {
    if (
      input.target.role !== 'assistant' ||
      input.target.conversationId !== input.conversation.id
    ) {
      return { ok: false, reason: 'invalid_target' }
    }

    return this.start(input, input.target)
  }

  /**
   * Cancel the active stream for a conversation.
   *
   * Cancellation aborts the adapter, persists the partial text immediately as
   * `cancelled`, and waits for the run to finish so the final snapshot is
   * durable. Returns null when no stream is active.
   */
  async cancel(conversationId: string): Promise<Message | null> {
    const active = this.active.get(conversationId)

    if (!active) {
      return null
    }

    if (active.state.done) {
      await active.done
      return active.finalMessage
    }

    active.cancelled = true
    active.controller.abort()

    const now = this.now()
    const cancelledState: StreamingState = {
      ...active.state,
      status: 'cancelled',
      finishReason: 'cancelled',
      done: true,
      completedAt: active.state.completedAt ?? now,
      updatedAt: now,
    }
    const snapshot = this.toMessage(active, cancelledState)
    this.store.setStream(cancelledState)
    active.persister.enqueue(snapshot)

    try {
      await active.persister.flush()
    } catch {
      // The final flush reports storage failures to the caller.
    }

    await active.done
    return active.finalMessage ?? snapshot
  }

  /** Cancel every active stream. */
  async dispose(): Promise<void> {
    await Promise.all([...this.active.keys()].map((conversationId) => this.cancel(conversationId)))
  }

  private async start(input: ChatRunInput, target?: Message): Promise<ChatRunResult> {
    const conversationId = input.conversation.id

    if (this.active.has(conversationId)) {
      return { ok: false, reason: 'busy' }
    }

    const now = this.now()
    const controller = new AbortController()
    const message = target
      ? this.regenerateMessage(target, input.conversation, now)
      : this.placeholder(input, now)
    const state = createStreamingState({
      conversationId,
      messageId: message.id,
      startedAt: now,
    })

    let resolveDone: () => void = () => {}
    const done = new Promise<void>((resolve) => {
      resolveDone = resolve
    })

    const active: ActiveStream = {
      conversationId,
      controller,
      state,
      message,
      dirty: false,
      persistDirty: false,
      cancelled: false,
      flushTimer: null,
      persistTimer: null,
      persister: new SerialMessagePersister((next) => this.messages.appendMessage(next)),
      done,
      finalMessage: null,
      visibilityListener: null,
      pageHideListener: null,
    }

    this.active.set(conversationId, active)

    const external = input.signal
    const onExternalAbort = () => {
      active.cancelled = true
      controller.abort()
    }

    if (external) {
      if (external.aborted) {
        onExternalAbort()
      } else {
        external.addEventListener('abort', onExternalAbort, { once: true })
      }
    }

    try {
      await this.messages.appendMessage(message)
    } catch (error) {
      this.active.delete(conversationId)
      external?.removeEventListener('abort', onExternalAbort)
      resolveDone()
      return { ok: false, reason: 'storage', error: storageError(error, this.now()) }
    }

    try {
      return await this.run(active, input)
    } finally {
      this.active.delete(conversationId)
      external?.removeEventListener('abort', onExternalAbort)
      resolveDone()
    }
  }

  private async run(active: ActiveStream, input: ChatRunInput): Promise<ChatRunResult> {
    this.store.setStream(active.state)
    this.startTimers(active)
    this.addLifecycle(active)

    try {
      await this.adapter(
        input.context,
        input.request,
        active.controller.signal,
        (event) => this.handleEvent(active, event),
        {
          now: () => new Date(this.now()).toISOString(),
          ...input.streamOptions,
        },
      )
    } catch (error) {
      this.handleEvent(active, {
        type: 'error',
        error: this.normalizeError(error, input.context),
      })
    } finally {
      this.stopTimers(active)
      this.removeLifecycle(active)
    }

    const finalState = this.finalize(active)
    const finalMessage = this.toMessage(active, finalState)
    active.finalMessage = finalMessage
    this.store.setStream(finalState)

    try {
      await active.persister.flush(finalMessage)
    } catch (error) {
      return { ok: false, reason: 'storage', error: storageError(error, this.now()) }
    }

    return { ok: true, message: finalMessage, state: finalState }
  }

  private handleEvent(active: ActiveStream, event: StreamEvent): void {
    if (active.cancelled) {
      return
    }

    const next = reduceStream(active.state, event, this.now())

    if (next === active.state) {
      return
    }

    active.state = next
    active.dirty = true
    active.persistDirty = true
  }

  private finalize(active: ActiveStream): StreamingState {
    const now = this.now()
    const status: MessageStatus = active.cancelled
      ? 'cancelled'
      : active.state.error !== null
        ? 'error'
        : 'complete'
    const finishReason = active.cancelled
      ? 'cancelled'
      : active.state.error !== null
        ? 'error'
        : (active.state.finishReason ?? 'unknown')

    return {
      ...active.state,
      status,
      finishReason,
      done: true,
      // Measure latency to the end of the adapter call, not to the provider's
      // finish event, because usage may arrive after `finish`.
      completedAt: now,
      updatedAt: now,
    }
  }

  private toMessage(active: ActiveStream, state: StreamingState): Message {
    const parts: ContentPart[] = []

    if (state.reasoning !== '') {
      parts.push({ type: 'reasoning', text: state.reasoning })
    }

    if (state.text !== '') {
      parts.push({ type: 'text', text: state.text })
    }

    return {
      ...active.message,
      parts,
      status: state.status,
      usage: state.usage,
      finishReason: state.finishReason,
      error: state.error,
      latencyMs: state.completedAt === null ? null : state.completedAt - state.startedAt,
      firstTokenMs: state.firstTokenAt === null ? null : state.firstTokenAt - state.startedAt,
      updatedAt: new Date(this.now()).toISOString(),
    }
  }

  private placeholder(input: ChatRunInput, now: number): Message {
    const conversation = input.conversation
    const timestamp = new Date(now).toISOString()

    return {
      id: this.newId(),
      conversationId: conversation.id,
      sequence: input.sequence ?? conversation.messageCount + 1,
      role: 'assistant',
      parts: [],
      status: 'streaming',
      providerId: conversation.providerId,
      modelId: conversation.modelId,
      modelLabel: conversation.modelLabel,
      usage: null,
      finishReason: null,
      error: null,
      latencyMs: null,
      firstTokenMs: null,
      createdAt: timestamp,
      updatedAt: timestamp,
      deletedAt: null,
    }
  }

  private regenerateMessage(target: Message, conversation: Conversation, now: number): Message {
    return {
      ...target,
      providerId: conversation.providerId,
      modelId: conversation.modelId,
      modelLabel: conversation.modelLabel,
      parts: [],
      status: 'streaming',
      usage: null,
      finishReason: null,
      error: null,
      latencyMs: null,
      firstTokenMs: null,
      updatedAt: new Date(now).toISOString(),
      deletedAt: null,
    }
  }

  private normalizeError(error: unknown, context: ProviderContext): ChatError {
    if (error instanceof OpenRouterRequestError) {
      return toChatError(error, new Date(this.now()).toISOString())
    }

    return {
      code: 'unknown',
      message: error instanceof Error ? error.message : 'The chat request failed.',
      providerId: context.provider.id,
      status: null,
      retryable: false,
      retryAfterSeconds: null,
      requestId: null,
      details: null,
      at: new Date(this.now()).toISOString(),
    }
  }

  private startTimers(active: ActiveStream): void {
    active.flushTimer = setInterval(() => this.flush(active), this.flushIntervalMs)
    active.persistTimer = setInterval(() => this.persist(active), this.persistIntervalMs)
  }

  private stopTimers(active: ActiveStream): void {
    if (active.flushTimer !== null) {
      clearInterval(active.flushTimer)
      active.flushTimer = null
    }

    if (active.persistTimer !== null) {
      clearInterval(active.persistTimer)
      active.persistTimer = null
    }
  }

  private flush(active: ActiveStream): void {
    if (!active.dirty) {
      return
    }

    active.dirty = false
    this.store.setStream(active.state)
  }

  private persist(active: ActiveStream): void {
    if (!active.persistDirty) {
      return
    }

    active.persistDirty = false
    active.persister.enqueue(this.toMessage(active, active.state))
  }

  private async persistNow(active: ActiveStream): Promise<void> {
    active.persistDirty = false

    try {
      await active.persister.flush(this.toMessage(active, active.state))
    } catch {
      // Lifecycle persistence is best-effort; the final flush reports errors.
    }
  }

  private addLifecycle(active: ActiveStream): void {
    const doc = this.lifecycle.document

    if (doc) {
      const listener = () => {
        void this.persistNow(active)
      }
      active.visibilityListener = listener
      doc.addEventListener('visibilitychange', listener)
    }

    const win = this.lifecycle.window

    if (win) {
      const listener = () => {
        void this.persistNow(active)
      }
      active.pageHideListener = listener
      win.addEventListener('pagehide', listener)
    }
  }

  private removeLifecycle(active: ActiveStream): void {
    const doc = this.lifecycle.document

    if (doc && active.visibilityListener !== null) {
      doc.removeEventListener('visibilitychange', active.visibilityListener)
      active.visibilityListener = null
    }

    const win = this.lifecycle.window

    if (win && active.pageHideListener !== null) {
      win.removeEventListener('pagehide', active.pageHideListener)
      active.pageHideListener = null
    }
  }
}
