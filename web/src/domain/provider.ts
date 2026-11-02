/**
 * Provider entity from spec section 6.2.
 *
 * `secretId` references the secret store; the key itself is never stored here.
 * `headers` may contain non-secret headers only.
 */
export type ProviderKind = 'openai-compatible' | 'anthropic' | 'google-gemini'

export type ProviderAuthType = 'bearer' | 'x-api-key' | 'query-key' | 'none'

export type BrowserCors = 'supported' | 'unsupported' | 'unknown'

export interface Provider {
  id: string
  presetId: string
  label: string
  kind: ProviderKind
  baseUrl: string
  authType: ProviderAuthType
  secretId: string | null
  headers: Record<string, string>
  enabled: boolean
  browserCors: BrowserCors
  metadata: Record<string, unknown> | null
  createdAt: string
  updatedAt: string
}
