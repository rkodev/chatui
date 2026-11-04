import type { BrowserCors, Model, ModelPricing } from '../../domain'

/**
 * Display helpers for provider and model metadata.
 *
 * Pricing is stored as USD per token. The UI always shows USD per million
 * tokens, matching the OpenRouter catalog and spec section 7.4. Rounding is
 * display-only; persisted values are never rewritten.
 */

function trimFixed(value: string): string {
  return value.replace(/0+$/u, '').replace(/\.$/u, '')
}

/** Format a USD amount without losing small per-million prices. */
export function formatUsd(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) {
    return '—'
  }

  if (value === 0) {
    return '$0'
  }

  const absolute = Math.abs(value)
  const digits = absolute >= 1 ? 2 : absolute >= 0.01 ? 4 : 6
  return `$${trimFixed(value.toFixed(digits))}`
}

/** Format a per-million-token price, or "Free" when the effective price is zero. */
export function formatPricePerMillion(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) {
    return '—'
  }

  if (value === 0) {
    return 'Free'
  }

  return `${formatUsd(value)} / M`
}

/** True when a model has no effective input, output, or per-request price. */
export function isFreeModel(model: Pick<Model, 'pricing' | 'remoteId'>): boolean {
  if (model.remoteId.endsWith(':free')) {
    return true
  }

  const pricing = model.pricing

  if (pricing === null) {
    return false
  }

  return effectivePrice(pricing) === 0
}

function effectivePrice(pricing: ModelPricing): number {
  return (pricing.inputPerMTok ?? 0) + (pricing.outputPerMTok ?? 0) + (pricing.perRequest ?? 0)
}

/** Human-readable price line for a model row. */
export function formatModelPrice(model: Pick<Model, 'pricing' | 'remoteId'>): string {
  if (isFreeModel(model)) {
    return 'Free'
  }

  const pricing = model.pricing

  if (pricing === null) {
    return 'Price unavailable'
  }

  const parts: string[] = []

  if (pricing.inputPerMTok !== undefined) {
    parts.push(`${formatPricePerMillion(pricing.inputPerMTok)} input`)
  }
  if (pricing.outputPerMTok !== undefined) {
    parts.push(`${formatPricePerMillion(pricing.outputPerMTok)} output`)
  }
  if (pricing.perRequest !== undefined && pricing.perRequest > 0) {
    parts.push(`${formatUsd(pricing.perRequest)} / request`)
  }

  return parts.length > 0 ? parts.join(' · ') : 'Price unavailable'
}

/** Compact context-window label, for example "128K context". */
export function formatContextWindow(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value) || value <= 0) {
    return 'Context unknown'
  }

  if (value >= 1_000_000) {
    return `${trimFixed((value / 1_000_000).toFixed(1))}M context`
  }

  if (value >= 1_000) {
    return `${trimFixed((value / 1_000).toFixed(1))}K context`
  }

  return `${value} context`
}

/** Input and output modality summary. */
export function formatModalities(model: Pick<Model, 'inputModalities' | 'outputModalities'>): string {
  const input = model.inputModalities.length > 0 ? model.inputModalities.join(', ') : 'none'
  const output = model.outputModalities.length > 0 ? model.outputModalities.join(', ') : 'none'
  return `${input} in · ${output} out`
}

/** CORS status copy for provider details. */
export function formatCors(value: BrowserCors): string {
  switch (value) {
    case 'supported':
      return 'Supported'
    case 'unsupported':
      return 'Not supported'
    default:
      return 'Unknown'
  }
}

/** Free-tier copy for provider details. */
export function formatFreeTier(value: boolean | null | undefined): string {
  if (value === null || value === undefined) {
    return 'Unknown'
  }

  return value ? 'Yes' : 'No'
}

/** Format the non-secret `/key` rate-limit object for display. */
export function formatRateLimit(value: Record<string, unknown> | null | undefined): string | null {
  if (value === null || value === undefined) {
    return null
  }

  const requests = value.requests
  const interval = value.interval

  if (typeof requests === 'number' && typeof interval === 'string') {
    return `${requests} requests per ${interval}`
  }

  const entries = Object.entries(value)

  if (entries.length === 0) {
    return null
  }

  return entries
    .map(([key, entry]) => `${key}: ${typeof entry === 'string' ? entry : JSON.stringify(entry)}`)
    .join(', ')
}
