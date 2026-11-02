/**
 * Model entity from spec section 6.2.
 *
 * Models are unique on `(providerId, remoteId)`. Provider-specific catalog
 * fields belong in `metadata`; this type does not contain request payloads.
 */
export type ModelCapability = 'text' | 'vision' | 'files' | 'tools' | 'reasoning' | 'streaming'

export type InputModality = 'text' | 'image' | 'file' | 'audio' | 'video'

export type OutputModality = 'text' | 'image' | 'audio'

export type ModelSource = 'discovered' | 'preset' | 'manual'

export interface ModelPricing {
  inputPerMTok?: number
  outputPerMTok?: number
  perRequest?: number
  currency?: string
}

export interface Model {
  id: string
  providerId: string
  remoteId: string
  label: string
  contextWindow: number | null
  maxOutputTokens: number | null
  capabilities: ModelCapability[]
  pricing: ModelPricing | null
  canonicalSlug: string | null
  inputModalities: InputModality[]
  outputModalities: OutputModality[]
  supportedParameters: string[]
  metadata: Record<string, unknown> | null
  hidden: boolean
  pinned: boolean
  source: ModelSource
  createdAt: string
  updatedAt: string
}
