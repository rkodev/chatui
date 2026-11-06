import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  M2_ENABLED_PRESET_IDS,
  ProviderRegistryError,
  getDefaultPreset,
  getEnabledPresets,
  getPreset,
  getPresetAvailability,
  isPresetEnabledInM2,
  loadProviderPresets,
  providerPresets,
  type ProviderPreset,
} from './registry'

function openRouterPreset(): ProviderPreset {
  const preset = getPreset('openrouter')

  if (!preset) {
    throw new Error('openrouter preset is missing from contracts')
  }

  return preset
}

function clonePreset(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return { ...openRouterPreset(), ...overrides }
}

describe('loadProviderPresets', () => {
  it('loads every preset from the checked-in contracts document', () => {
    const contracts = JSON.parse(
      readFileSync(resolve(process.cwd(), '../contracts/providers.json'), 'utf8'),
    ) as Array<{ id: string; baseUrl: string }>

    expect(providerPresets.map((preset) => preset.id)).toEqual(
      contracts.map((preset) => preset.id),
    )
    expect(getPreset('openrouter')?.baseUrl).toBe(
      contracts.find((preset) => preset.id === 'openrouter')?.baseUrl,
    )
  })

  it('accepts the contracts document explicitly', () => {
    expect(loadProviderPresets()).toHaveLength(providerPresets.length)
  })

  it('rejects a document that is not an array', () => {
    expect(() => loadProviderPresets({})).toThrow(ProviderRegistryError)
  })

  it('rejects a preset with a missing required field', () => {
    const missingLabel = clonePreset()
    delete missingLabel.label

    try {
      loadProviderPresets([missingLabel])
      throw new Error('Expected loadProviderPresets to reject')
    } catch (error) {
      expect(error).toBeInstanceOf(ProviderRegistryError)
      expect((error as ProviderRegistryError).issues.some((issue) => issue.includes('label'))).toBe(
        true,
      )
    }
  })

  it('rejects a preset with an invalid field value', () => {
    expect(() => loadProviderPresets([clonePreset({ kind: 'unknown' })])).toThrow(
      ProviderRegistryError,
    )
  })

  it('rejects duplicate preset IDs', () => {
    expect(() =>
      loadProviderPresets([openRouterPreset(), clonePreset({ label: 'Duplicate OpenRouter' })]),
    ).toThrow('Duplicate provider preset id(s): openrouter')
  })
})

describe('preset lookup', () => {
  it('finds a preset by ID', () => {
    expect(getPreset('openrouter')?.label).toBe('OpenRouter')
    expect(getPreset('missing')).toBeUndefined()
  })

  it('returns the contract default preset', () => {
    expect(getDefaultPreset().id).toBe('openrouter')
  })

  it('fails when no preset is marked as default', () => {
    expect(() => getDefaultPreset([clonePreset({ default: false }) as ProviderPreset])).toThrow(
      'No provider preset is marked as default',
    )
  })

  it('fails when multiple presets are marked as default', () => {
    expect(() =>
      getDefaultPreset([
        openRouterPreset(),
        clonePreset({ id: 'other', default: true }) as ProviderPreset,
      ]),
    ).toThrow('Multiple provider presets are marked as default: openrouter, other')
  })
})

describe('M2 enablement', () => {
  it('enables every launch OpenAI-compatible preset', () => {
    expect(M2_ENABLED_PRESET_IDS).toEqual([
      'openrouter',
      'openai',
      'anthropic',
      'google',
      'groq',
      'mistral',
      'deepseek',
      'xai',
      'together',
      'fireworks',
      'ollama',
      'lmstudio',
      'llamacpp',
      'custom',
    ])
    expect(getEnabledPresets().map((preset) => preset.id)).toEqual([
      ...M2_ENABLED_PRESET_IDS,
    ])
    expect(isPresetEnabledInM2('openrouter')).toBe(true)
    expect(isPresetEnabledInM2('openai')).toBe(true)
    expect(isPresetEnabledInM2('anthropic')).toBe(true)
    expect(isPresetEnabledInM2('google')).toBe(true)
    expect(isPresetEnabledInM2('ollama')).toBe(true)
    expect(isPresetEnabledInM2('custom')).toBe(true)
  })

  it('only enables presets that exist in contracts', () => {
    const contractIds = new Set(providerPresets.map((preset) => preset.id))

    for (const id of M2_ENABLED_PRESET_IDS) {
      expect(contractIds.has(id)).toBe(true)
    }
  })

  it('labels enabled, disabled, and coming-later presets', () => {
    const availability = getPresetAvailability(providerPresets, [
      'openrouter',
      'openai',
    ])

    expect(availability.find((entry) => entry.preset.id === 'openrouter')).toMatchObject({
      enabled: true,
      selectable: true,
      status: 'available',
    })
    expect(availability.find((entry) => entry.preset.id === 'openai')).toMatchObject({
      enabled: true,
      selectable: true,
      status: 'available',
    })
    expect(availability.find((entry) => entry.preset.id === 'custom')).toMatchObject({
      enabled: false,
      selectable: false,
      status: 'disabled',
    })
    expect(availability.find((entry) => entry.preset.id === 'anthropic')).toMatchObject({
      enabled: false,
      selectable: false,
      status: 'disabled',
    })
    expect(availability.find((entry) => entry.preset.id === 'google')).toMatchObject({
      enabled: false,
      selectable: false,
      status: 'disabled',
    })
  })

  it('filters enabled presets by the runtime allow-list', () => {
    expect(
      getEnabledPresets(providerPresets, ['openrouter', 'groq']).map(
        (preset) => preset.id,
      ),
    ).toEqual(['openrouter', 'groq'])
  })
})
