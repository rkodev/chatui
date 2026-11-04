import type { OpenAICompatiblePreset, ProviderMessages } from './openaiCompatible'
import { getPreset, type ProviderPreset } from './registry'

/**
 * OpenRouter keeps its M1 copy and curated advanced-JSON behavior. Every other
 * OpenAI-compatible preset uses the provider-neutral defaults.
 */
const OPENROUTER_MESSAGES: ProviderMessages = {
  missingKey: 'An OpenRouter API key is required.',
  cancelled: 'OpenRouter request was cancelled.',
  network: 'Could not reach OpenRouter.',
  invalidJson: 'OpenRouter returned invalid JSON.',
  invalidModels: 'OpenRouter /models response was not valid.',
  invalidKey: 'OpenRouter /key response was not valid.',
  emptyStream: 'OpenRouter returned an empty stream.',
  invalidStreamJson: 'OpenRouter stream contained invalid JSON.',
  streamError: 'OpenRouter stream returned an error.',
  bufferOverflow: 'The OpenRouter stream exceeded the buffer limit.',
  interrupted: 'The OpenRouter stream was interrupted.',
  unknown: 'OpenRouter request failed.',
  invalidRequest: 'The chat request was invalid.',
  defaultHttp: (status) => `OpenRouter request failed with HTTP ${status}`,
  status: {
    401: 'Authentication failed. Check your OpenRouter API key.',
    402: 'Insufficient credits. Add more credits to continue.',
    403: 'OpenRouter blocked the request.',
    404: 'The requested model was not found.',
    429: 'Rate limit exceeded. Please retry later.',
    503: 'No available upstream provider could serve the request.',
  },
}

const OPENROUTER_PASSTHROUGH_FIELDS = ['provider', 'models', 'route', 'reasoning'] as const

/**
 * Convert a validated contract preset into the transport's preset shape.
 *
 * The transport reads only data from this object. OpenRouter is the only
 * preset with curated extras and OpenRouter-specific copy.
 */
export function toOpenAICompatiblePreset(preset: ProviderPreset): OpenAICompatiblePreset {
  const base: OpenAICompatiblePreset = {
    id: preset.id,
    displayName: preset.label,
    kind: preset.kind,
    baseUrl: preset.baseUrl,
    auth: preset.auth,
    defaultHeaders: preset.defaultHeaders,
    attributionHeaders: preset.attributionHeaders,
    modelDiscovery: preset.modelDiscovery,
    keyValidation: preset.keyValidation,
    extraNamespace: preset.id,
    extraMode: 'all',
    reasoningStyle: 'openai',
  }

  if (preset.id === 'openrouter') {
    return {
      ...base,
      extraMode: 'curated',
      extraPassthroughFields: OPENROUTER_PASSTHROUGH_FIELDS,
      reasoningStyle: 'openrouter',
      messages: OPENROUTER_MESSAGES,
    }
  }

  return base
}

/** Resolve a contract preset id to a transport preset. */
export function getOpenAICompatiblePreset(id: string): OpenAICompatiblePreset | undefined {
  const preset = getPreset(id)
  return preset ? toOpenAICompatiblePreset(preset) : undefined
}
