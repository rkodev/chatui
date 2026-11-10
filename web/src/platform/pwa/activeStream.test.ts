import { beforeEach, describe, expect, it } from 'vitest'
import { createStreamingState, useStreamingStore } from '../../features/chat/streamingStore'
import { hasActiveStream } from './activeStream'

beforeEach(() => {
  useStreamingStore.setState({ streams: {} })
})

describe('hasActiveStream', () => {
  it('is false when no stream exists', () => {
    expect(hasActiveStream()).toBe(false)
  })

  it('is true while a stream is unfinished', () => {
    useStreamingStore.getState().setStream(
      createStreamingState({
        conversationId: 'conversation-1',
        messageId: 'message-1',
        startedAt: 1,
      }),
    )

    expect(hasActiveStream()).toBe(true)
  })

  it('is false when every stream is done', () => {
    useStreamingStore.getState().setStream({
      ...createStreamingState({
        conversationId: 'conversation-1',
        messageId: 'message-1',
        startedAt: 1,
      }),
      done: true,
    })

    expect(hasActiveStream()).toBe(false)
  })
})
