import type { OpenAICompatiblePreset } from './openaiCompatible'
import { getOpenAICompatiblePreset } from './presets'

const preset = getOpenAICompatiblePreset('openrouter')

if (!preset) {
  throw new Error('OpenRouter preset is missing from contracts/providers.json')
}

/** The OpenRouter preset used by the M1-compatible adapter surface. */
export const OPENROUTER_PRESET: OpenAICompatiblePreset = preset
