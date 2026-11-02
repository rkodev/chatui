import type { InputModality, ModelCapability, ModelPricing, OutputModality } from '../domain'

/**
 * Normalized OpenRouter catalog entry.
 *
 * This is intentionally smaller than the persisted `Model` entity: callers
 * assign local IDs, provider IDs, timestamps, and source. `isFree` is derived
 * from the effective pricing and the OpenRouter `:free` suffix.
 */
export interface ModelInfo {
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
  isFree: boolean
}

/** Normalized key introspection result from spec section 7.1. */
export interface ProviderKeyInfo {
  label: string | null
  usageUsd: number | null
  limitUsd: number | null
  limitRemainingUsd: number | null
  isFreeTier: boolean | null
  rateLimit: Record<string, unknown> | null
}

/** A successful catalog snapshot, including when it was fetched. */
export interface CatalogSnapshot {
  fetchedAt: number
  models: ModelInfo[]
}

/** Result of a cache-aware catalog load. */
export interface CatalogResult {
  models: ModelInfo[]
  fetchedAt: number | null
  fromCache: boolean
  stale: boolean
  refreshError: Error | null
}

/** Injectable cache so tests and callers can choose their own storage. */
export interface CatalogCache {
  read(key: string): Promise<CatalogSnapshot | null>
  write(key: string, snapshot: CatalogSnapshot): Promise<void>
}

export const OPENROUTER_CATALOG_TTL_MS = 24 * 60 * 60 * 1000

/** Cache key for the OpenRouter catalog. */
export function openRouterCatalogCacheKey(providerId = 'openrouter'): string {
  return `chatui.catalog.${providerId}.v1`
}

/** Raised when an OpenRouter catalog or key payload cannot be normalized. */
export class OpenRouterCatalogError extends Error {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options)
    this.name = 'OpenRouterCatalogError'
  }
}

const INPUT_MODALITIES: readonly InputModality[] = ['text', 'image', 'file', 'audio', 'video']

const OUTPUT_MODALITIES: readonly OutputModality[] = ['text', 'image', 'audio']

const CAPABILITY_ORDER: readonly ModelCapability[] = [
  'text',
  'vision',
  'files',
  'tools',
  'reasoning',
  'streaming',
]

const KNOWN_MODEL_FIELDS = new Set([
  'id',
  'canonical_slug',
  'name',
  'created',
  'description',
  'context_length',
  'architecture',
  'pricing',
  'top_provider',
  'per_request_limits',
  'supported_parameters',
  'default_parameters',
])

const SECRET_LIKE_FIELD = /key|token|secret|auth|password|credential/i

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isInputModality(value: string): value is InputModality {
  return (INPUT_MODALITIES as readonly string[]).includes(value)
}

function isOutputModality(value: string): value is OutputModality {
  return (OUTPUT_MODALITIES as readonly string[]).includes(value)
}

function toPositiveInteger(value: unknown): number | null {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    return null
  }

  const integer = Math.trunc(value)
  return integer > 0 ? integer : null
}

function toFiniteNumber(value: unknown): number | null {
  if (typeof value === 'number') {
    return Number.isFinite(value) ? value : null
  }

  if (typeof value === 'string' && value.trim() !== '') {
    const parsed = Number(value)
    return Number.isFinite(parsed) ? parsed : null
  }

  return null
}

/**
 * Multiply a non-negative decimal string by one million without going through
 * binary floating point.
 *
 * The result is a canonical decimal string. Invalid input returns `null`.
 */
export function multiplyDecimalByMillion(value: string): string | null {
  const match = /^([+-]?)(\d+)(?:\.(\d+))?$/.exec(value.trim())

  if (!match) {
    return null
  }

  const sign = match[1] === '-' ? '-' : ''
  const integerPart = match[2]
  const fractionPart = match[3] ?? ''
  const digits = `${integerPart}${fractionPart}`
  const pointIndex = integerPart.length + 6

  let result: string
  if (pointIndex >= digits.length) {
    result = `${digits}${'0'.repeat(pointIndex - digits.length)}`
  } else {
    result = `${digits.slice(0, pointIndex)}.${digits.slice(pointIndex)}`
  }

  const [rawInteger, rawFraction = ''] = result.split('.')
  const normalizedInteger = rawInteger.replace(/^0+(?=\d)/, '') || '0'
  const normalizedFraction = rawFraction.replace(/0+$/, '')
  const normalized = normalizedFraction
    ? `${normalizedInteger}.${normalizedFraction}`
    : normalizedInteger

  if (normalized === '0') {
    return '0'
  }

  return `${sign}${normalized}`
}

/** Convert a USD-per-token decimal string to USD per million tokens. */
export function usdPerMillion(value: unknown): number | null {
  if (typeof value !== 'string') {
    return null
  }

  const multiplied = multiplyDecimalByMillion(value)
  return multiplied === null ? null : Number(multiplied)
}

/** Convert a USD decimal string to a number, preserving the raw value. */
export function usdAmount(value: unknown): number | null {
  if (typeof value !== 'string' || value.trim() === '') {
    return null
  }

  if (!/^[+-]?\d+(?:\.\d+)?$/.test(value.trim())) {
    return null
  }

  return Number(value)
}

function parseModality(value: unknown): {
  input: InputModality[]
  output: OutputModality[]
} {
  if (typeof value !== 'string') {
    return { input: [], output: [] }
  }

  const [rawInput = '', rawOutput = ''] = value.split('->')
  const input = rawInput
    .split('+')
    .map((part) => part.trim())
    .filter(isInputModality)
  const output = rawOutput
    .split('+')
    .map((part) => part.trim())
    .filter(isOutputModality)

  return { input, output }
}

function uniqueStrings(value: unknown): string[] {
  if (!Array.isArray(value)) {
    return []
  }

  return [...new Set(value.filter((item): item is string => typeof item === 'string'))]
}

function uniqueInputModalities(value: unknown): InputModality[] {
  if (!Array.isArray(value)) {
    return []
  }

  return [...new Set(value.filter((item): item is InputModality => isInputModality(item)))]
}

function uniqueOutputModalities(value: unknown): OutputModality[] {
  if (!Array.isArray(value)) {
    return []
  }

  return [...new Set(value.filter((item): item is OutputModality => isOutputModality(item)))]
}

function inferCapabilities(
  inputModalities: readonly InputModality[],
  outputModalities: readonly OutputModality[],
  supportedParameters: readonly string[],
  modality: string,
): ModelCapability[] {
  const capabilities = new Set<ModelCapability>()

  if (
    inputModalities.includes('text') ||
    outputModalities.includes('text') ||
    modality.includes('text')
  ) {
    capabilities.add('text')
  }

  if (inputModalities.includes('image') || modality.includes('image')) {
    capabilities.add('vision')
  }

  if (inputModalities.includes('file') || modality.includes('file')) {
    capabilities.add('files')
  }

  if (supportedParameters.includes('tools') || supportedParameters.includes('tool_choice')) {
    capabilities.add('tools')
  }

  if (
    supportedParameters.includes('reasoning') ||
    supportedParameters.includes('include_reasoning')
  ) {
    capabilities.add('reasoning')
  }

  if (supportedParameters.includes('stream')) {
    capabilities.add('streaming')
  }

  return CAPABILITY_ORDER.filter((capability) => capabilities.has(capability))
}

function mapPricing(rawPricing: unknown): ModelPricing | null {
  if (!isRecord(rawPricing)) {
    return null
  }

  const pricing: ModelPricing = {}
  const inputPerMTok = usdPerMillion(rawPricing.prompt)
  const outputPerMTok = usdPerMillion(rawPricing.completion)
  const perRequest = usdAmount(rawPricing.request)

  if (inputPerMTok !== null) {
    pricing.inputPerMTok = inputPerMTok
  }
  if (outputPerMTok !== null) {
    pricing.outputPerMTok = outputPerMTok
  }
  if (perRequest !== null) {
    pricing.perRequest = perRequest
  }

  if (Object.keys(pricing).length === 0) {
    return null
  }

  pricing.currency = 'USD'
  return pricing
}

function isFreeModel(remoteId: string, rawPricing: unknown, pricing: ModelPricing | null): boolean {
  if (remoteId.endsWith(':free')) {
    return true
  }

  if (!isRecord(rawPricing) || pricing === null) {
    return false
  }

  const prompt = usdAmount(rawPricing.prompt)
  const completion = usdAmount(rawPricing.completion)

  if (prompt !== 0 || completion !== 0) {
    return false
  }

  const otherCharges = [rawPricing.request, rawPricing.image, rawPricing.internal_reasoning].map(
    usdAmount,
  )

  return otherCharges.every((charge) => charge === null || charge === 0)
}

function copySmallUnknownFields(
  raw: Record<string, unknown>,
  metadata: Record<string, unknown>,
): void {
  for (const [key, value] of Object.entries(raw)) {
    if (KNOWN_MODEL_FIELDS.has(key) || SECRET_LIKE_FIELD.test(key)) {
      continue
    }

    let serialized: string
    try {
      serialized = JSON.stringify(value)
    } catch {
      continue
    }

    if (serialized === undefined || serialized.length > 512) {
      continue
    }

    metadata[key] = value
  }
}

function buildMetadata(raw: Record<string, unknown>): Record<string, unknown> | null {
  const metadata: Record<string, unknown> = {}

  if (isRecord(raw.architecture)) {
    const architecture: Record<string, unknown> = {}
    if (typeof raw.architecture.modality === 'string') {
      architecture.modality = raw.architecture.modality
    }
    if (typeof raw.architecture.tokenizer === 'string') {
      architecture.tokenizer = raw.architecture.tokenizer
    }
    if ('instruct_type' in raw.architecture) {
      architecture.instructType = raw.architecture.instruct_type
    }
    if (Object.keys(architecture).length > 0) {
      metadata.architecture = architecture
    }
  }

  if (isRecord(raw.pricing)) {
    metadata.pricing = { ...raw.pricing }
  }

  if (isRecord(raw.default_parameters)) {
    metadata.defaultParameters = { ...raw.default_parameters }
  }

  if (isRecord(raw.top_provider)) {
    const topProvider: Record<string, unknown> = {}
    if (typeof raw.top_provider.is_moderated === 'boolean') {
      topProvider.isModerated = raw.top_provider.is_moderated
    }
    if (raw.top_provider.context_length !== undefined) {
      topProvider.contextLength = raw.top_provider.context_length
    }
    if (raw.top_provider.max_completion_tokens !== undefined) {
      topProvider.maxCompletionTokens = raw.top_provider.max_completion_tokens
    }
    if (Object.keys(topProvider).length > 0) {
      metadata.topProvider = topProvider
    }
  }

  if (raw.per_request_limits !== undefined) {
    metadata.perRequestLimits = raw.per_request_limits
  }

  if (typeof raw.description === 'string' && raw.description.length <= 512) {
    metadata.description = raw.description
  }

  if (typeof raw.created === 'number' && Number.isFinite(raw.created)) {
    metadata.created = raw.created
  }

  copySmallUnknownFields(raw, metadata)

  return Object.keys(metadata).length > 0 ? metadata : null
}

/**
 * Map one raw OpenRouter model entry.
 *
 * Unknown fields are ignored unless they are small and non-secret, in which
 * case they are copied into `metadata`. Entries without a usable `id` are
 * skipped by `mapOpenRouterModels` rather than failing the whole catalog.
 */
export function mapOpenRouterModel(raw: unknown): ModelInfo | null {
  if (!isRecord(raw)) {
    return null
  }

  const remoteId = typeof raw.id === 'string' ? raw.id.trim() : ''
  if (remoteId === '') {
    return null
  }

  const architecture = isRecord(raw.architecture) ? raw.architecture : {}
  const topProvider = isRecord(raw.top_provider) ? raw.top_provider : {}
  const modality = typeof architecture.modality === 'string' ? architecture.modality : ''
  const parsedModality = parseModality(modality)

  const declaredInputModalities = uniqueInputModalities(architecture.input_modalities)
  const declaredOutputModalities = uniqueOutputModalities(architecture.output_modalities)
  const inputModalities =
    declaredInputModalities.length > 0 ? declaredInputModalities : parsedModality.input
  const outputModalities =
    declaredOutputModalities.length > 0 ? declaredOutputModalities : parsedModality.output

  const supportedParameters = uniqueStrings(raw.supported_parameters)
  const contextWindow =
    toPositiveInteger(raw.context_length) ?? toPositiveInteger(topProvider.context_length)
  const maxOutputTokens = toPositiveInteger(topProvider.max_completion_tokens) ?? contextWindow
  const pricing = mapPricing(raw.pricing)
  const label = typeof raw.name === 'string' && raw.name.trim() !== '' ? raw.name : remoteId

  return {
    remoteId,
    label,
    contextWindow,
    maxOutputTokens,
    capabilities: inferCapabilities(
      inputModalities,
      outputModalities,
      supportedParameters,
      modality,
    ),
    pricing,
    canonicalSlug:
      typeof raw.canonical_slug === 'string' && raw.canonical_slug.trim() !== ''
        ? raw.canonical_slug
        : null,
    inputModalities,
    outputModalities,
    supportedParameters,
    metadata: buildMetadata(raw),
    isFree: isFreeModel(remoteId, raw.pricing, pricing),
  }
}

/**
 * Map an OpenRouter `/models` envelope to normalized entries.
 *
 * The envelope may be `{ data: [...] }`, `{ models: [...] }`, or a bare array.
 * Malformed entries are skipped; a malformed envelope throws.
 */
export function mapOpenRouterModels(payload: unknown): ModelInfo[] {
  let entries: unknown[]

  if (Array.isArray(payload)) {
    entries = payload
  } else if (isRecord(payload) && Array.isArray(payload.data)) {
    entries = payload.data
  } else if (isRecord(payload) && Array.isArray(payload.models)) {
    entries = payload.models
  } else {
    throw new OpenRouterCatalogError('OpenRouter /models response must contain a data array')
  }

  return entries
    .map((entry) => mapOpenRouterModel(entry))
    .filter((model): model is ModelInfo => model !== null)
}

/** Map an OpenRouter `/key` envelope to normalized key metadata. */
export function mapOpenRouterKey(payload: unknown): ProviderKeyInfo {
  if (!isRecord(payload) || !isRecord(payload.data)) {
    throw new OpenRouterCatalogError('OpenRouter /key response must contain a data object')
  }

  const data = payload.data

  return {
    label: typeof data.label === 'string' ? data.label : null,
    usageUsd: toFiniteNumber(data.usage),
    limitUsd: toFiniteNumber(data.limit),
    limitRemainingUsd: toFiniteNumber(data.limit_remaining),
    isFreeTier: typeof data.is_free_tier === 'boolean' ? data.is_free_tier : null,
    rateLimit: isRecord(data.rate_limit) ? { ...data.rate_limit } : null,
  }
}

function cloneSnapshot(snapshot: CatalogSnapshot): CatalogSnapshot {
  return structuredClone(snapshot)
}

function isCatalogSnapshot(value: unknown): value is CatalogSnapshot {
  return (
    isRecord(value) &&
    typeof value.fetchedAt === 'number' &&
    Number.isFinite(value.fetchedAt) &&
    Array.isArray(value.models)
  )
}

/** In-memory cache used by tests and as a fallback when storage is absent. */
export class MemoryCatalogCache implements CatalogCache {
  private readonly entries = new Map<string, CatalogSnapshot>()

  async read(key: string): Promise<CatalogSnapshot | null> {
    const snapshot = this.entries.get(key)
    return snapshot ? cloneSnapshot(snapshot) : null
  }

  async write(key: string, snapshot: CatalogSnapshot): Promise<void> {
    this.entries.set(key, cloneSnapshot(snapshot))
  }
}

/**
 * Create a `localStorage`-backed cache.
 *
 * The catalog is public, so it is safe to store. Storage failures (quota,
 * private mode, malformed JSON) are treated as cache misses rather than
 * breaking discovery.
 */
export function createLocalStorageCatalogCache(storage?: Storage): CatalogCache {
  const target = storage ?? (typeof localStorage !== 'undefined' ? localStorage : null)

  if (target === null) {
    return new MemoryCatalogCache()
  }

  return {
    async read(key: string): Promise<CatalogSnapshot | null> {
      try {
        const raw = target.getItem(key)
        if (raw === null) {
          return null
        }

        const parsed: unknown = JSON.parse(raw)
        return isCatalogSnapshot(parsed) ? parsed : null
      } catch {
        return null
      }
    },
    async write(key: string, snapshot: CatalogSnapshot): Promise<void> {
      try {
        target.setItem(key, JSON.stringify(snapshot))
      } catch {
        // A full or unavailable storage area must not break discovery.
      }
    },
  }
}

let defaultCache: CatalogCache | null = null

/** Return the process-wide default catalog cache. */
export function getDefaultCatalogCache(): CatalogCache {
  if (defaultCache === null) {
    defaultCache = createLocalStorageCatalogCache()
  }

  return defaultCache
}

/** True when a snapshot is still inside the 24-hour TTL. */
export function isCatalogFresh(
  snapshot: CatalogSnapshot,
  now: number,
  ttlMs = OPENROUTER_CATALOG_TTL_MS,
): boolean {
  return now - snapshot.fetchedAt < ttlMs
}
