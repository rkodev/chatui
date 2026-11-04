import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  fallbackModelIdsFor,
  selectFallbackModel,
  type FallbackCatalogEntry,
} from './fallbackModels'
import { M2_ENABLED_PRESET_IDS } from './registry'

interface FallbackFixture {
  presets: Array<{ providerId: string; fallbackModelIds: string[] }>
  cases: Array<{
    name: string
    providerId: string
    presetDefaultModelId: string
    fallbackModelIds: string[]
    catalog: FallbackCatalogEntry[]
    expected: { selectedModelId: string | null; notice: string | null }
  }>
}

const fixture: FallbackFixture = JSON.parse(
  readFileSync(
    resolve(process.cwd(), '../contracts/fixtures/expected/fallback-models.json'),
    'utf8',
  ),
) as FallbackFixture

describe('fallback model rule', () => {
  it.each(fixture.cases)('$name matches the shared fixture', (testCase) => {
    expect(
      selectFallbackModel({
        catalog: testCase.catalog,
        presetDefaultModelId: testCase.presetDefaultModelId,
        fallbackModelIds: testCase.fallbackModelIds,
      }),
    ).toEqual(testCase.expected)
  })

  it('matches the fixture fallback lists for presets that define them', () => {
    for (const entry of fixture.presets) {
      expect(fallbackModelIdsFor(entry.providerId)).toEqual(entry.fallbackModelIds)
    }
  })

  it('gives every enabled preset except the OpenRouter reference a fallback', () => {
    for (const presetId of M2_ENABLED_PRESET_IDS) {
      if (presetId === 'openrouter') {
        expect(fallbackModelIdsFor(presetId)).toEqual([])
        continue
      }

      expect(fallbackModelIdsFor(presetId).length).toBeGreaterThan(0)
    }
  })

  it('treats an empty catalog as blocked discovery', () => {
    expect(
      selectFallbackModel({
        catalog: [],
        presetDefaultModelId: 'gpt-4o-mini',
        fallbackModelIds: ['gpt-4o-mini', 'gpt-4o'],
      }),
    ).toEqual({
      selectedModelId: 'gpt-4o-mini',
      notice: 'Discovery unavailable; using fallback model gpt-4o-mini.',
    })
  })
})
