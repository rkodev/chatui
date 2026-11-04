import { create } from 'zustand'
import type { ChatError, FinishReason, MessageStatus, Usage } from '../../domain'
import type { StreamEvent } from '../../providers/openrouterStream'

/**
 * Ephemeral state for one active assistant response.
 *
 * The runner reduces normalized provider events into this shape locally and
 * flushes snapshots to the store at a bounded interval. React only subscribes
 * to the stream for the conversation it is rendering.
 */
export interface StreamingState {
  conversationId: string
  messageId: string
  responseId: string | null
  started: boolean
  text: string
  reasoning: string
  usage: Usage | null
  finishReason: FinishReason | null
  error: ChatError | null
  status: MessageStatus
  done: boolean
  /** Epoch milliseconds when the request started. */
  startedAt: number
  /** Epoch milliseconds of the first text or reasoning delta. */
  firstTokenAt: number | null
  /** Epoch milliseconds when the stream reached a terminal state. */
  completedAt: number | null
  updatedAt: number
}

export interface CreateStreamingStateInput {
  conversationId: string
  messageId: string
  startedAt: number
}

/** Create the initial streaming state for a new assistant message. */
export function createStreamingState(input: CreateStreamingStateInput): StreamingState {
  return {
    conversationId: input.conversationId,
    messageId: input.messageId,
    responseId: null,
    started: false,
    text: '',
    reasoning: '',
    usage: null,
    finishReason: null,
    error: null,
    status: 'streaming',
    done: false,
    startedAt: input.startedAt,
    firstTokenAt: null,
    completedAt: null,
    updatedAt: input.startedAt,
  }
}

/**
 * Reduce one normalized stream event into the next streaming state.
 *
 * The function is pure: it never mutates the previous state and never touches
 * persistence. Text and reasoning deltas append in arrival order. The first
 * text or reasoning delta records the first-token timestamp.
 */
export function reduceStream(
  state: StreamingState,
  event: StreamEvent,
  now: number = Date.now(),
): StreamingState {
  switch (event.type) {
    case 'start':
      return {
        ...state,
        responseId: event.responseId,
        started: true,
        updatedAt: now,
      }
    case 'text-delta':
      return {
        ...state,
        text: state.text + event.text,
        firstTokenAt: state.firstTokenAt ?? now,
        updatedAt: now,
      }
    case 'reasoning-delta':
      return {
        ...state,
        reasoning: state.reasoning + event.text,
        firstTokenAt: state.firstTokenAt ?? now,
        updatedAt: now,
      }
    case 'usage':
      return { ...state, usage: event.usage, updatedAt: now }
    case 'finish':
      return {
        ...state,
        finishReason: event.reason,
        status: state.error !== null ? 'error' : 'complete',
        done: true,
        completedAt: state.completedAt ?? now,
        updatedAt: now,
      }
    case 'error':
      return {
        ...state,
        error: event.error,
        status: 'error',
        done: true,
        completedAt: state.completedAt ?? now,
        updatedAt: now,
      }
    case 'tool-call-delta':
      // M1 renders text and reasoning only. Tool calls are preserved by the
      // adapter contract but are not part of the streaming presentation yet.
      return state
    default:
      return state
  }
}

export interface StreamingStoreState {
  streams: Record<string, StreamingState>
  setStream: (state: StreamingState) => void
  clearStream: (conversationId: string) => void
  clearAll: () => void
}

/**
 * Active stream snapshots keyed by conversation ID.
 *
 * The runner writes snapshots here at the flush cadence. A completed stream
 * remains until the next run for the same conversation overwrites it or the
 * caller clears it, so the chat screen can render the final state without a
 * second source of truth.
 */
export const useStreamingStore = create<StreamingStoreState>((set) => ({
  streams: {},
  setStream: (state) =>
    set((current) => ({
      streams: { ...current.streams, [state.conversationId]: state },
    })),
  clearStream: (conversationId) =>
    set((current) => {
      if (!(conversationId in current.streams)) {
        return current
      }

      const streams = { ...current.streams }
      delete streams[conversationId]
      return { streams }
    }),
  clearAll: () => set({ streams: {} }),
}))

/** Minimal store surface injected into `ChatRunner`. */
export interface StreamingStoreApi {
  setStream: (state: StreamingState) => void
  clearStream: (conversationId: string) => void
}

/** Default store adapter used by the runner in the app. */
export const streamingStoreApi: StreamingStoreApi = {
  setStream: (state) => useStreamingStore.getState().setStream(state),
  clearStream: (conversationId) => useStreamingStore.getState().clearStream(conversationId),
}

/**
 * Selector for one conversation's stream.
 *
 * The returned object is the stored snapshot reference, so unrelated
 * conversations do not re-render when another stream flushes.
 */
export function selectStream(conversationId: string) {
  return (state: StreamingStoreState): StreamingState | null =>
    state.streams[conversationId] ?? null
}

/** Subscribe to one conversation's active stream. */
export function useStreamingStream(conversationId: string): StreamingState | null {
  return useStreamingStore(selectStream(conversationId))
}
