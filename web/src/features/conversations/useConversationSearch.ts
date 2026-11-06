import { useEffect, useRef, useState } from 'react'
import type { ConversationSearchGroup, ConversationSearchOptions } from '../../storage'
import type { ConversationHistoryServiceLike } from './historyService'

/** Debounce before a query reaches storage, per M2-04.1. */
export const DEFAULT_SEARCH_DEBOUNCE_MS = 250

export type ConversationSearchStatus = 'idle' | 'loading' | 'ready' | 'error'

export interface UseConversationSearchOptions {
  service: ConversationHistoryServiceLike
  query: string
  debounceMs?: number
  /** Passed through to the repository result limit. */
  limit?: ConversationSearchOptions['limit']
}

export interface ConversationSearchState {
  status: ConversationSearchStatus
  /** Trimmed query that produced this state. Empty while idle. */
  query: string
  groups: ConversationSearchGroup[]
  error: string | null
  truncated: boolean
}

function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause)
}

const IDLE_STATE: ConversationSearchState = {
  status: 'idle',
  query: '',
  groups: [],
  error: null,
  truncated: false,
}

/**
 * Debounced conversation search with stale-result protection.
 *
 * Every query starts a new request id. Timers are cleared on cleanup and late
 * responses are ignored when a newer query has started, so an older request
 * can never overwrite newer results. Empty queries return to idle without
 * touching storage.
 */
export function useConversationSearch({
  service,
  query,
  debounceMs = DEFAULT_SEARCH_DEBOUNCE_MS,
  limit,
}: UseConversationSearchOptions): ConversationSearchState {
  const [state, setState] = useState<ConversationSearchState>(IDLE_STATE)
  const requestIdRef = useRef(0)

  useEffect(() => {
    const trimmed = query.trim()
    const requestId = requestIdRef.current + 1
    requestIdRef.current = requestId

    if (trimmed === '') {
      return
    }

    let cancelled = false
    const options: ConversationSearchOptions = limit === undefined ? {} : { limit }
    const timer = setTimeout(() => {
      service
        .searchConversations(trimmed, options)
        .then((result) => {
          if (cancelled || requestIdRef.current !== requestId) {
            return
          }

          setState({
            status: 'ready',
            query: trimmed,
            groups: result.groups,
            error: null,
            truncated: result.truncated,
          })
        })
        .catch((cause: unknown) => {
          if (cancelled || requestIdRef.current !== requestId) {
            return
          }

          setState({
            status: 'error',
            query: trimmed,
            groups: [],
            error: errorMessage(cause),
            truncated: false,
          })
        })
    }, debounceMs)

    return () => {
      cancelled = true
      clearTimeout(timer)
    }
  }, [debounceMs, limit, query, service])

  const trimmed = query.trim()

  if (trimmed === '') {
    return IDLE_STATE
  }

  if (state.query !== trimmed) {
    return {
      status: 'loading',
      query: trimmed,
      groups: [],
      error: null,
      truncated: false,
    }
  }

  return state
}
