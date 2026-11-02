/**
 * Normalized chat error from spec section 6.2.
 *
 * User-facing messages must be actionable and must not include keys or raw
 * headers.
 */
export type ChatErrorCode =
  | 'auth'
  | 'permission'
  | 'rate_limit'
  | 'quota'
  | 'model_not_found'
  | 'context_length'
  | 'content_filter'
  | 'invalid_request'
  | 'network'
  | 'cors'
  | 'timeout'
  | 'cancelled'
  | 'provider'
  | 'parse'
  | 'storage'
  | 'unknown'

export interface ChatError {
  code: ChatErrorCode
  message: string
  providerId: string | null
  status: number | null
  retryable: boolean
  retryAfterSeconds: number | null
  requestId: string | null
  details: Record<string, unknown> | null
  at: string
}
