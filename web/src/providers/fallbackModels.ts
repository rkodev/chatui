import type { OutputModality } from '../domain'
import { getPreset, type ProviderPreset } from './registry'

/**
 * Fallback model lists used when discovery is blocked or unavailable.
 *
 * The shared fixture in `contracts/fixtures/expected/fallback-models.json`
 * defines these lists for presets with catalog fixtures. Every other preset
 * falls back to its contract `defaultModelId`, so onboarding still works when
 * `/models` cannot be reached. Custom providers use their contract default as
 * a placeholder until the user adds a real model.
 */
const EXPLICIT_FALLBACK_MODEL_IDS: Record<string, readonly string[]> = {
  openai: ['gpt-4o-mini', 'gpt-4o'],
  anthropic: ['claude-sonnet-4-5', 'claude-haiku-4-5'],
  google: ['gemini-2.5-flash', 'gemini-2.5-pro'],
  ollama: ['llama3.2:latest'],
}

/** Return the ordered fallback model ids for one preset id. */
export function fallbackModelIdsFor(
  presetId: string,
  preset: ProviderPreset | undefined = getPreset(presetId),
): string[] {
  const explicit = EXPLICIT_FALLBACK_MODEL_IDS[presetId]

  if (explicit) {
    return [...explicit]
  }

  // The OpenRouter reference keeps its M1 behavior: an empty catalog is a
  // setup error, not a fallback, because `/models` is public and reliable.
  if (presetId === 'openrouter') {
    return []
  }

  if (preset && preset.defaultModelId.trim() !== '') {
    return [preset.defaultModelId]
  }

  return []
}

/** Minimal catalog entry used by the fallback selection rule. */
export interface FallbackCatalogEntry {
  remoteId: string
  hidden: boolean
  outputModalities: readonly OutputModality[]
}

export interface FallbackSelectionInput {
  catalog: readonly FallbackCatalogEntry[]
  presetDefaultModelId: string
  fallbackModelIds: readonly string[]
}

export interface FallbackSelection {
  selectedModelId: string | null
  notice: string | null
}

function isUsableTextModel(entry: FallbackCatalogEntry): boolean {
  return !entry.hidden && entry.outputModalities.includes('text')
}

/**
 * Apply the shared fallback rule.
 *
 * 1. An empty catalog means discovery was blocked: pick the first fallback id,
 *    or report that no fallback exists.
 * 2. Use the preset default when it exists.
 * 3. Otherwise use the first fallback id present in the catalog.
 * 4. Otherwise use the first non-hidden text-output model by `remoteId`.
 * 5. Otherwise report that no text model is available.
 */
export function selectFallbackModel(input: FallbackSelectionInput): FallbackSelection {
  const { catalog, presetDefaultModelId, fallbackModelIds } = input

  if (catalog.length === 0) {
    const fallback = fallbackModelIds[0]

    return fallback === undefined
      ? { selectedModelId: null, notice: 'Discovery unavailable; no fallback model is available.' }
      : { selectedModelId: fallback, notice: `Discovery unavailable; using fallback model ${fallback}.` }
  }

  const presetDefault = catalog.find((entry) => entry.remoteId === presetDefaultModelId)

  if (presetDefault) {
    return { selectedModelId: presetDefault.remoteId, notice: null }
  }

  for (const id of fallbackModelIds) {
    const found = catalog.find((entry) => entry.remoteId === id && isUsableTextModel(entry))

    if (found) {
      return {
        selectedModelId: found.remoteId,
        notice: `Default model ${presetDefaultModelId} is unavailable; using ${found.remoteId}.`,
      }
    }
  }

  const firstText = catalog
    .filter(isUsableTextModel)
    .sort((left, right) =>
      left.remoteId < right.remoteId ? -1 : left.remoteId > right.remoteId ? 1 : 0,
    )[0]

  if (firstText) {
    return {
      selectedModelId: firstText.remoteId,
      notice: `Default model ${presetDefaultModelId} is unavailable; using ${firstText.remoteId}.`,
    }
  }

  return {
    selectedModelId: null,
    notice: `Default model ${presetDefaultModelId} is unavailable; no text model is available.`,
  }
}
