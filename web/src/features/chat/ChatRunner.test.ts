import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ChatError, ChatParams, Conversation, Message, Usage } from '../../domain'
import type { ProviderContext } from '../../providers/openrouter'
import type { NormalizedChatRequest, StreamEvent } from '../../providers/openrouterStream'
import {
  ChatRunner,
  SerialMessagePersister,
  type ChatStreamAdapter,
  type ChatRunnerLifecycle,
} from './ChatRunner'
import {
  createStreamingState,
  reduceStream,
  selectStream,
  useStreamingStore,
  type StreamingState,
  type StreamingStoreApi,
} from './streamingStore'

const params: ChatParams = {
  temperature: null,
  topP: null,
  maxOutputTokens: null,
  stop: [],
  reasoningEffort: null,
  streaming: true,
  extra: {},
}

const usage: Usage = {
  inputTokens: 10,
  outputTokens: 20,
  totalTokens: 30,
  costUsd: 0.001,
  estimated: false,
}

function conversation(overrides: Partial<Conversation> = {}): Conversation {
  const now = '2026-01-01T00:00:00.000Z'
  return {
    id: 'conversation-1',
    title: 'New chat',
    systemPrompt: '',
    providerId: 'provider-1',
    modelId: 'model-1',
    modelLabel: 'OpenRouter: Auto',
    params,
    pinned: false,
    archived: false,
    temporary: false,
    createdAt: now,
    updatedAt: now,
    lastMessageAt: now,
    messageCount: 0,
    lastError: null,
    sync: null,
    ...overrides,
  }
}

function message(overrides: Partial<Message> = {}): Message {
  const now = '2026-01-01T00:00:00.000Z'
  return {
    id: 'message-1',
    conversationId: 'conversation-1',
    sequence: 1,
    role: 'assistant',
    parts: [{ type: 'text', text: 'old answer' }],
    status: 'complete',
    providerId: 'provider-1',
    modelId: 'model-1',
    modelLabel: 'OpenRouter: Auto',
    usage: null,
    finishReason: 'stop',
    error: null,
    latencyMs: null,
    firstTokenMs: null,
    createdAt: now,
    updatedAt: now,
    deletedAt: null,
    ...overrides,
  }
}

const context: ProviderContext = {
  provider: { id: 'provider-1', baseUrl: 'https://openrouter.ai/api/v1' },
  apiKey: 'sk-test',
  fetch: globalThis.fetch,
}

const request: NormalizedChatRequest = {
  model: 'openrouter/auto',
  system: null,
  messages: [{ role: 'user', parts: [{ type: 'text', text: 'Hello' }] }],
  params,
}

class FakeMessageStore {
  readonly writes: Message[] = []

  async appendMessage(next: Message): Promise<unknown> {
    this.writes.push(next)
    return { message: next, conversation: conversation() }
  }
}

function fakeStore(): StreamingStoreApi & { snapshots: StreamingState[] } {
  const snapshots: StreamingState[] = []
  return {
    snapshots,
    setStream: (state) => {
      snapshots.push(state)
    },
    clearStream: () => {},
  }
}

interface ControlledAdapter {
  adapter: ChatStreamAdapter
  started: Promise<void>
  emit: (event: StreamEvent) => void
  finish: () => void
  fail: (error: unknown) => void
  calls: () => number
  signal: () => AbortSignal | null
}

function controlledAdapter(): ControlledAdapter {
  let emitFn: ((event: StreamEvent) => void) | null = null
  let resolveFn: (() => void) | null = null
  let rejectFn: ((error: unknown) => void) | null = null
  let signal: AbortSignal | null = null
  let calls = 0
  let startedResolve: () => void = () => {}
  const started = new Promise<void>((resolve) => {
    startedResolve = resolve
  })
  const completion = new Promise<void>((resolve, reject) => {
    resolveFn = resolve
    rejectFn = reject
  })

  const adapter: ChatStreamAdapter = async (_context, _request, nextSignal, onEvent) => {
    calls += 1
    signal = nextSignal
    emitFn = onEvent
    nextSignal.addEventListener('abort', () => resolveFn?.(), { once: true })
    startedResolve()
    await completion
  }

  return {
    adapter,
    started,
    emit: (event) => emitFn?.(event),
    finish: () => resolveFn?.(),
    fail: (error) => rejectFn?.(error),
    calls: () => calls,
    signal: () => signal,
  }
}

function createRunner(
  adapter: ChatStreamAdapter,
  messages: FakeMessageStore,
  store: StreamingStoreApi,
  options: {
    now?: () => number
    newId?: () => string
    lifecycle?: ChatRunnerLifecycle
  } = {},
): ChatRunner {
  return new ChatRunner({
    adapter,
    messages,
    store,
    now: options.now ?? (() => 1000),
    newId: options.newId ?? (() => 'assistant-1'),
    lifecycle: options.lifecycle ?? {},
  })
}

afterEach(() => {
  useStreamingStore.getState().clearAll()
  vi.useRealTimers()
})

describe('reduceStream', () => {
  it('reduces start, reasoning, text, usage, and finish in order', () => {
    let state = createStreamingState({
      conversationId: 'conversation-1',
      messageId: 'assistant-1',
      startedAt: 1000,
    })

    state = reduceStream(state, { type: 'start', responseId: 'response-1' }, 1001)
    state = reduceStream(state, { type: 'reasoning-delta', text: 'think ' }, 1002)
    state = reduceStream(state, { type: 'text-delta', text: 'Hello' }, 1003)
    state = reduceStream(state, { type: 'text-delta', text: ' world' }, 1004)
    state = reduceStream(state, { type: 'usage', usage }, 1005)
    state = reduceStream(state, { type: 'finish', reason: 'stop' }, 1006)

    expect(state.responseId).toBe('response-1')
    expect(state.started).toBe(true)
    expect(state.reasoning).toBe('think ')
    expect(state.text).toBe('Hello world')
    expect(state.firstTokenAt).toBe(1002)
    expect(state.usage).toEqual(usage)
    expect(state.finishReason).toBe('stop')
    expect(state.status).toBe('complete')
    expect(state.done).toBe(true)
    expect(state.completedAt).toBe(1006)
  })

  it('records an error without discarding partial text', () => {
    const error: ChatError = {
      code: 'network',
      message: 'Connection lost.',
      providerId: 'provider-1',
      status: null,
      retryable: false,
      retryAfterSeconds: null,
      requestId: null,
      details: null,
      at: '2026-01-01T00:00:00.000Z',
    }

    let state = createStreamingState({
      conversationId: 'conversation-1',
      messageId: 'assistant-1',
      startedAt: 1000,
    })
    state = reduceStream(state, { type: 'text-delta', text: 'partial' }, 1001)
    state = reduceStream(state, { type: 'error', error }, 1002)

    expect(state.text).toBe('partial')
    expect(state.error).toEqual(error)
    expect(state.status).toBe('error')
    expect(state.done).toBe(true)
  })
})

describe('ChatRunner', () => {
  it('streams events in order and persists a complete message', async () => {
    const adapter = controlledAdapter()
    const messages = new FakeMessageStore()
    const store = fakeStore()
    let clock = 1000
    const runner = createRunner(adapter.adapter, messages, store, {
      now: () => clock,
      newId: () => 'assistant-1',
    })

    const run = runner.send({ conversation: conversation(), context, request })
    await adapter.started

    clock = 1010
    adapter.emit({ type: 'start', responseId: 'response-1' })
    clock = 1020
    adapter.emit({ type: 'reasoning-delta', text: 'reason' })
    clock = 1030
    adapter.emit({ type: 'text-delta', text: 'Hello' })
    clock = 1040
    adapter.emit({ type: 'text-delta', text: ' world' })
    clock = 1050
    adapter.emit({ type: 'finish', reason: 'stop' })
    clock = 1060
    adapter.emit({ type: 'usage', usage })
    clock = 1100
    adapter.finish()

    const result = await run

    expect(result.ok).toBe(true)
    if (!result.ok) {
      return
    }

    expect(result.message.id).toBe('assistant-1')
    expect(result.message.role).toBe('assistant')
    expect(result.message.status).toBe('complete')
    expect(result.message.parts).toEqual([
      { type: 'reasoning', text: 'reason' },
      { type: 'text', text: 'Hello world' },
    ])
    expect(result.message.usage).toEqual(usage)
    expect(result.message.finishReason).toBe('stop')
    expect(result.message.firstTokenMs).toBe(20)
    expect(result.message.latencyMs).toBe(100)
    expect(result.state.responseId).toBe('response-1')

    expect(messages.writes).toHaveLength(2)
    expect(messages.writes[0]?.status).toBe('streaming')
    expect(messages.writes[1]?.status).toBe('complete')
    expect(store.snapshots.at(-1)?.status).toBe('complete')
  })

  it('throttles UI flushes and does not persist per token', async () => {
    const adapter = controlledAdapter()
    const messages = new FakeMessageStore()
    const store = fakeStore()
    const runner = createRunner(adapter.adapter, messages, store)

    const run = runner.send({ conversation: conversation(), context, request })
    await adapter.started

    for (let index = 0; index < 100; index += 1) {
      adapter.emit({ type: 'text-delta', text: 'x' })
    }
    adapter.emit({ type: 'finish', reason: 'stop' })
    adapter.finish()

    const result = await run

    expect(result.ok).toBe(true)
    expect(store.snapshots.length).toBeLessThanOrEqual(3)
    expect(messages.writes.length).toBeLessThanOrEqual(3)
    expect(messages.writes.at(-1)?.parts).toEqual([{ type: 'text', text: 'x'.repeat(100) }])
  })

  it('flushes buffered text on the bounded timer', async () => {
    vi.useFakeTimers()
    const adapter = controlledAdapter()
    const messages = new FakeMessageStore()
    const store = fakeStore()
    const runner = createRunner(adapter.adapter, messages, store)

    const run = runner.send({ conversation: conversation(), context, request })
    await adapter.started

    adapter.emit({ type: 'text-delta', text: 'a' })
    expect(store.snapshots).toHaveLength(1)

    await vi.advanceTimersByTimeAsync(40)
    expect(store.snapshots).toHaveLength(2)
    expect(store.snapshots[1]?.text).toBe('a')

    adapter.emit({ type: 'text-delta', text: 'b' })
    await vi.advanceTimersByTimeAsync(40)
    expect(store.snapshots).toHaveLength(3)
    expect(store.snapshots[2]?.text).toBe('ab')

    adapter.finish()
    await run
  })

  it('persists partial text about once per second', async () => {
    vi.useFakeTimers()
    const adapter = controlledAdapter()
    const messages = new FakeMessageStore()
    const store = fakeStore()
    const runner = createRunner(adapter.adapter, messages, store)

    const run = runner.send({ conversation: conversation(), context, request })
    await adapter.started

    adapter.emit({ type: 'text-delta', text: 'partial' })
    expect(messages.writes).toHaveLength(1)

    await vi.advanceTimersByTimeAsync(1000)
    expect(messages.writes).toHaveLength(2)
    expect(messages.writes[1]?.status).toBe('streaming')
    expect(messages.writes[1]?.parts).toEqual([{ type: 'text', text: 'partial' }])

    adapter.finish()
    await run
    expect(messages.writes.at(-1)?.status).toBe('complete')
  })

  it('cancels the adapter, persists partial text, and marks the message cancelled', async () => {
    const adapter = controlledAdapter()
    const messages = new FakeMessageStore()
    const store = fakeStore()
    const runner = createRunner(adapter.adapter, messages, store)

    const run = runner.send({ conversation: conversation(), context, request })
    await adapter.started

    adapter.emit({ type: 'start', responseId: 'response-1' })
    adapter.emit({ type: 'text-delta', text: 'partial answer' })

    const cancelled = await runner.cancel('conversation-1')

    expect(adapter.signal()?.aborted).toBe(true)
    expect(cancelled?.status).toBe('cancelled')
    expect(cancelled?.finishReason).toBe('cancelled')
    expect(cancelled?.parts).toEqual([{ type: 'text', text: 'partial answer' }])
    expect(messages.writes.at(-1)?.status).toBe('cancelled')
    expect(store.snapshots.at(-1)?.status).toBe('cancelled')

    const result = await run
    expect(result.ok).toBe(true)
    if (result.ok) {
      expect(result.message.status).toBe('cancelled')
    }
  })

  it('preserves partial text and normalized error state on failure', async () => {
    const adapter = controlledAdapter()
    const messages = new FakeMessageStore()
    const store = fakeStore()
    const runner = createRunner(adapter.adapter, messages, store)

    const run = runner.send({ conversation: conversation(), context, request })
    await adapter.started

    adapter.emit({ type: 'text-delta', text: 'partial' })
    const error: ChatError = {
      code: 'rate_limit',
      message: 'Rate limit exceeded.',
      providerId: 'provider-1',
      status: 429,
      retryable: true,
      retryAfterSeconds: 5,
      requestId: null,
      details: null,
      at: '2026-01-01T00:00:00.000Z',
    }
    adapter.emit({ type: 'error', error })
    adapter.finish()

    const result = await run

    expect(result.ok).toBe(true)
    if (!result.ok) {
      return
    }

    expect(result.message.status).toBe('error')
    expect(result.message.parts).toEqual([{ type: 'text', text: 'partial' }])
    expect(result.message.error).toEqual(error)
    expect(result.message.finishReason).toBe('error')
    expect(messages.writes.at(-1)?.status).toBe('error')
  })

  it('normalizes a thrown adapter failure without losing partial text', async () => {
    const adapter = controlledAdapter()
    const messages = new FakeMessageStore()
    const store = fakeStore()
    const runner = createRunner(adapter.adapter, messages, store)

    const run = runner.send({ conversation: conversation(), context, request })
    await adapter.started

    adapter.emit({ type: 'text-delta', text: 'partial' })
    adapter.fail(new Error('boom'))

    const result = await run

    expect(result.ok).toBe(true)
    if (!result.ok) {
      return
    }

    expect(result.message.status).toBe('error')
    expect(result.message.parts).toEqual([{ type: 'text', text: 'partial' }])
    expect(result.message.error?.code).toBe('unknown')
    expect(result.message.error?.message).toBe('boom')
  })

  it('allows only one active stream per conversation', async () => {
    const adapter = controlledAdapter()
    const messages = new FakeMessageStore()
    const store = fakeStore()
    const runner = createRunner(adapter.adapter, messages, store)

    const first = runner.send({ conversation: conversation(), context, request })
    await adapter.started

    const second = await runner.send({ conversation: conversation(), context, request })

    expect(second).toEqual({ ok: false, reason: 'busy' })
    expect(adapter.calls()).toBe(1)
    expect(runner.isStreaming('conversation-1')).toBe(true)

    adapter.finish()
    await first
    expect(runner.isStreaming('conversation-1')).toBe(false)
  })

  it('regenerates in place without creating a branch', async () => {
    const adapter = controlledAdapter()
    const messages = new FakeMessageStore()
    const store = fakeStore()
    const runner = createRunner(adapter.adapter, messages, store)
    const target = message({ id: 'assistant-old', sequence: 7 })

    const run = runner.regenerate({
      conversation: conversation({ messageCount: 7 }),
      context,
      request,
      target,
    })
    await adapter.started

    adapter.emit({ type: 'text-delta', text: 'new answer' })
    adapter.emit({ type: 'finish', reason: 'stop' })
    adapter.finish()

    const result = await run

    expect(result.ok).toBe(true)
    if (!result.ok) {
      return
    }

    expect(result.message.id).toBe('assistant-old')
    expect(result.message.sequence).toBe(7)
    expect(result.message.parts).toEqual([{ type: 'text', text: 'new answer' }])
    expect(messages.writes.every((write) => write.id === 'assistant-old')).toBe(true)
    expect(messages.writes.every((write) => write.sequence === 7)).toBe(true)
  })

  it('rejects a regenerate target that is not an assistant message in the conversation', async () => {
    const adapter = controlledAdapter()
    const messages = new FakeMessageStore()
    const store = fakeStore()
    const runner = createRunner(adapter.adapter, messages, store)

    const result = await runner.regenerate({
      conversation: conversation(),
      context,
      request,
      target: message({ role: 'user' }),
    })

    expect(result).toEqual({ ok: false, reason: 'invalid_target' })
    expect(adapter.calls()).toBe(0)
  })

  it('persists on visibilitychange and pagehide', async () => {
    const adapter = controlledAdapter()
    const messages = new FakeMessageStore()
    const store = fakeStore()
    const documentListeners = new Set<() => void>()
    const windowListeners = new Set<() => void>()
    let visibilityState = 'visible'

    const lifecycle: ChatRunnerLifecycle = {
      document: {
        get visibilityState() {
          return visibilityState
        },
        addEventListener: (_type, listener) => {
          documentListeners.add(listener)
        },
        removeEventListener: (_type, listener) => {
          documentListeners.delete(listener)
        },
      },
      window: {
        addEventListener: (_type, listener) => {
          windowListeners.add(listener)
        },
        removeEventListener: (_type, listener) => {
          windowListeners.delete(listener)
        },
      },
    }

    const runner = createRunner(adapter.adapter, messages, store, { lifecycle })
    const run = runner.send({ conversation: conversation(), context, request })
    await adapter.started

    adapter.emit({ type: 'text-delta', text: 'hidden text' })
    visibilityState = 'hidden'
    documentListeners.forEach((listener) => listener())
    await vi.waitFor(() => {
      expect(messages.writes.at(-1)?.parts).toEqual([{ type: 'text', text: 'hidden text' }])
    })

    adapter.emit({ type: 'text-delta', text: ' more' })
    windowListeners.forEach((listener) => listener())
    await vi.waitFor(() => {
      expect(messages.writes.at(-1)?.parts).toEqual([{ type: 'text', text: 'hidden text more' }])
    })

    adapter.finish()
    await run
  })
})

describe('streaming store', () => {
  it('keeps streams isolated by conversation', () => {
    const first = createStreamingState({
      conversationId: 'conversation-a',
      messageId: 'message-a',
      startedAt: 1,
    })
    const second = createStreamingState({
      conversationId: 'conversation-b',
      messageId: 'message-b',
      startedAt: 2,
    })

    useStreamingStore.getState().setStream(first)
    useStreamingStore.getState().setStream(second)

    expect(selectStream('conversation-a')(useStreamingStore.getState())).toBe(first)
    expect(selectStream('conversation-b')(useStreamingStore.getState())).toBe(second)

    useStreamingStore.getState().clearStream('conversation-a')

    expect(selectStream('conversation-a')(useStreamingStore.getState())).toBeNull()
    expect(selectStream('conversation-b')(useStreamingStore.getState())).toBe(second)
  })
})

describe('SerialMessagePersister', () => {
  it('serializes writes and coalesces pending snapshots', async () => {
    const writes: string[] = []
    const gate: { release: (() => void) | null } = { release: null }
    const persister = new SerialMessagePersister(async (next) => {
      writes.push(next.id)

      if (next.id === 'a') {
        await new Promise<void>((resolve) => {
          gate.release = resolve
        })
      }
    })

    persister.enqueue(message({ id: 'a' }))
    persister.enqueue(message({ id: 'b' }))
    persister.enqueue(message({ id: 'c' }))

    expect(writes).toEqual(['a'])
    gate.release?.()
    await persister.flush()

    expect(writes).toEqual(['a', 'c'])
  })
})
