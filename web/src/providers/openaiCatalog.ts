import type { InputModality, ModelCapability, OutputModality } from '../domain'
import type { ModelInfo } from './openrouterCatalog'

/**
 * Defaults used when an OpenAI-compatible `/models` response omits capability
 * metadata. The shared contract fixtures define these values for the OpenAI
 * preset; other OpenAI-compatible presets can pass their own defaults.
 */
export interface OpenAICatalogDefaults {
  inputModalities: InputModality[]
  outputModalities: OutputModality[]
  capabilities: ModelCapability[]
  supportedParameters: string[]
}

export const OPENAI_CATALOG_DEFAULTS: OpenAICatalogDefaults = {
  inputModalities: ['text'],
  outputModalities: ['text'],
  capabilities: ['text', 'streaming'],
  supportedParameters: ['temperature', 'top_p', 'max_tokens', 'stop', 'stream'],
}

/** Raised when an OpenAI-compatible `/models` payload cannot be normalized. */
export class OpenAICatalogError extends Error {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options)
    this.name = 'OpenAICatalogError'
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function cloneDefaults(defaults: OpenAICatalogDefaults): OpenAICatalogDefaults {
  return {
    inputModalities: [...defaults.inputModalities],
    outputModalities: [...defaults.outputModalities],
    capabilities: [...defaults.capabilities],
    supportedParameters: [...defaults.supportedParameters],
  }
}

/**
 * Map one OpenAI-compatible model entry.
 *
 * OpenAI's `/models` response only guarantees `id`. Capability metadata is
 * therefore taken from the preset defaults. Embedding models are marked hidden
 * so they never become the default chat model.
 */
export function mapOpenAIModel(
  raw: unknown,
  defaults: OpenAICatalogDefaults = OPENAI_CATALOG_DEFAULTS,
): ModelInfo | null {
  if (!isRecord(raw)) {
    return null
  }

  const remoteId = typeof raw.id === 'string' ? raw.id.trim() : ''
  if (remoteId === '') {
    return null
  }

  const resolved = cloneDefaults(defaults)

  return {
    remoteId,
    label: remoteId,
    contextWindow: null,
    maxOutputTokens: null,
    capabilities: resolved.capabilities,
    pricing: null,
    canonicalSlug: null,
    inputModalities: resolved.inputModalities,
    outputModalities: resolved.outputModalities,
    supportedParameters: resolved.supportedParameters,
    metadata: null,
    isFree: false,
    hidden: /embedding/i.test(remoteId),
  }
}

/**
 * Map an OpenAI-compatible `/models` envelope to normalized entries.
 *
 * The envelope may be `{ data: [...] }` or a bare array. Malformed entries are
 * skipped; a malformed envelope throws.
 */
export function mapOpenAIModels(
  payload: unknown,
  defaults: OpenAICatalogDefaults = OPENAI_CATALOG_DEFAULTS,
): ModelInfo[] {
  let entries: unknown[]

  if (Array.isArray(payload)) {
    entries = payload
  } else if (isRecord(payload) && Array.isArray(payload.data)) {
    entries = payload.data
  } else {
    throw new OpenAICatalogError('OpenAI /models response must contain a data array')
  }

  return entries
    .map((entry) => mapOpenAIModel(entry, defaults))
    .filter((model): model is ModelInfo => model !== null)
}
