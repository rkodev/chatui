import type { InputModality, ModelCapability, OutputModality } from '../domain'
import type { ModelInfo } from './openrouterCatalog'

/**
 * Defaults used when an OpenAI-compatible `/models` response omits capability
 * metadata. The shared contract fixtures define these values per provider kind.
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

/**
 * Preset defaults for providers whose catalogs omit capability metadata.
 *
 * These mirror the shared capability-inference fixture. OpenRouter uses its own
 * richer mapper and is not listed here.
 */
const PRESET_CATALOG_DEFAULTS: Record<string, OpenAICatalogDefaults> = {
  anthropic: {
    inputModalities: ['text', 'image'],
    outputModalities: ['text'],
    capabilities: ['text', 'vision', 'streaming'],
    supportedParameters: ['temperature', 'top_p', 'max_tokens', 'stop', 'stream'],
  },
  google: {
    inputModalities: ['text', 'image', 'file'],
    outputModalities: ['text'],
    capabilities: ['text', 'vision', 'files', 'streaming'],
    supportedParameters: ['temperature', 'top_p', 'max_tokens', 'stop', 'stream'],
  },
  ollama: {
    inputModalities: ['text'],
    outputModalities: ['text'],
    capabilities: ['text', 'streaming'],
    supportedParameters: ['temperature', 'top_p', 'max_tokens', 'stop', 'stream'],
  },
}

/** Return the capability-inference defaults for one preset id. */
export function catalogDefaultsForPreset(presetId: string): OpenAICatalogDefaults {
  return PRESET_CATALOG_DEFAULTS[presetId] ?? OPENAI_CATALOG_DEFAULTS
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

function isInputModality(value: string): value is InputModality {
  return (INPUT_MODALITIES as readonly string[]).includes(value)
}

function isOutputModality(value: string): value is OutputModality {
  return (OUTPUT_MODALITIES as readonly string[]).includes(value)
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

function uniqueStrings(value: unknown): string[] {
  if (!Array.isArray(value)) {
    return []
  }

  return [...new Set(value.filter((item): item is string => typeof item === 'string'))]
}

function firstDefined(...values: unknown[]): unknown {
  return values.find((value) => value !== undefined)
}

function toPositiveInteger(value: unknown): number | null {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    return null
  }

  const integer = Math.trunc(value)
  return integer > 0 ? integer : null
}

function inferCapabilities(
  inputModalities: readonly InputModality[],
  outputModalities: readonly OutputModality[],
  supportedParameters: readonly string[],
): ModelCapability[] {
  const capabilities = new Set<ModelCapability>()

  if (inputModalities.includes('text') || outputModalities.includes('text')) {
    capabilities.add('text')
  }

  if (inputModalities.includes('image')) {
    capabilities.add('vision')
  }

  if (inputModalities.includes('file')) {
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

function orderedCapabilities(value: unknown): ModelCapability[] {
  if (!Array.isArray(value)) {
    return []
  }

  const declared = new Set(
    value.filter((item): item is ModelCapability =>
      (CAPABILITY_ORDER as readonly string[]).includes(item as string),
    ),
  )

  return declared.size === 0
    ? []
    : CAPABILITY_ORDER.filter((capability) => declared.has(capability))
}

/** Metadata inferred from raw catalog fields and preset defaults. */
export interface InferredOpenAIModelMetadata {
  inputModalities: InputModality[]
  outputModalities: OutputModality[]
  capabilities: ModelCapability[]
  supportedParameters: string[]
  contextWindow: number | null
  maxOutputTokens: number | null
}

/**
 * Infer missing model metadata from the shared rule.
 *
 * Raw provider values win. Missing modalities and supported parameters fall
 * back to the preset defaults, missing limits are `null`, and missing
 * capabilities are derived in canonical order from the effective modalities
 * and supported parameters.
 */
export function inferOpenAIModelMetadata(
  raw: unknown,
  defaults: OpenAICatalogDefaults = OPENAI_CATALOG_DEFAULTS,
): InferredOpenAIModelMetadata {
  const record = isRecord(raw) ? raw : {}

  const declaredInput = uniqueInputModalities(
    firstDefined(record.inputModalities, record.input_modalities),
  )
  const declaredOutput = uniqueOutputModalities(
    firstDefined(record.outputModalities, record.output_modalities),
  )
  const declaredParameters = uniqueStrings(
    firstDefined(record.supportedParameters, record.supported_parameters),
  )
  const declaredCapabilities = orderedCapabilities(record.capabilities)

  const inputModalities =
    declaredInput.length > 0 ? declaredInput : [...defaults.inputModalities]
  const outputModalities =
    declaredOutput.length > 0 ? declaredOutput : [...defaults.outputModalities]
  const supportedParameters =
    declaredParameters.length > 0
      ? declaredParameters
      : [...defaults.supportedParameters]
  const capabilities =
    declaredCapabilities.length > 0
      ? declaredCapabilities
      : inferCapabilities(inputModalities, outputModalities, supportedParameters)

  return {
    inputModalities,
    outputModalities,
    capabilities,
    supportedParameters,
    contextWindow: toPositiveInteger(
      firstDefined(record.contextWindow, record.context_window, record.context_length),
    ),
    maxOutputTokens: toPositiveInteger(
      firstDefined(
        record.maxOutputTokens,
        record.max_output_tokens,
        record.max_completion_tokens,
      ),
    ),
  }
}

/**
 * Map one OpenAI-compatible model entry.
 *
 * OpenAI's `/models` response only guarantees `id`. Capability metadata is
 * therefore inferred from any raw fields the provider does expose, falling
 * back to the preset defaults. Embedding models are marked hidden so they never
 * become the default chat model.
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

  const inferred = inferOpenAIModelMetadata(raw, defaults)

  return {
    remoteId,
    label: remoteId,
    contextWindow: inferred.contextWindow,
    maxOutputTokens: inferred.maxOutputTokens,
    capabilities: inferred.capabilities,
    pricing: null,
    canonicalSlug: null,
    inputModalities: inferred.inputModalities,
    outputModalities: inferred.outputModalities,
    supportedParameters: inferred.supportedParameters,
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
