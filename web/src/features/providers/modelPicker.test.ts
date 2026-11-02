import { describe, expect, it } from 'vitest'
import type { Model, Provider } from '../../domain'
import {
  formatContextWindow,
  formatModelPrice,
  formatModalities,
  formatPricePerMillion,
  isFreeModel,
} from './format'
import {
  buildModelPickerGroups,
  modelAvailability,
  modelCapabilityChips,
  modelMatchesQuery,
} from './modelPicker'
import { createTestModel, createTestProvider } from './testSupport'

function model(overrides: Partial<Model> = {}): Model {
  return createTestModel(overrides)
}

function provider(overrides: Partial<Provider> = {}): Provider {
  return createTestProvider(overrides)
}

describe('model pricing and formatting', () => {
  it('converts per-token prices to per-million display values', () => {
    expect(formatPricePerMillion(0.15)).toBe('$0.15 / M')
    expect(formatPricePerMillion(3)).toBe('$3 / M')
    expect(formatPricePerMillion(0.0005)).toBe('$0.0005 / M')
    expect(formatPricePerMillion(0)).toBe('Free')
    expect(formatPricePerMillion(null)).toBe('—')
  })

  it('marks zero-priced and :free models as free', () => {
    expect(isFreeModel(model({ pricing: { inputPerMTok: 0, outputPerMTok: 0 } }))).toBe(true)
    expect(isFreeModel(model({ pricing: null, remoteId: 'author/model:free' }))).toBe(true)
    expect(isFreeModel(model({ pricing: { inputPerMTok: 0.15, outputPerMTok: 0.6 } }))).toBe(
      false,
    )
    expect(isFreeModel(model({ pricing: null, remoteId: 'author/model' }))).toBe(false)
  })

  it('formats a model price line with input and output prices', () => {
    expect(
      formatModelPrice(model({ pricing: { inputPerMTok: 0.15, outputPerMTok: 0.6 } })),
    ).toBe('$0.15 / M input · $0.6 / M output')
    expect(formatModelPrice(model({ pricing: { inputPerMTok: 0, outputPerMTok: 0 } }))).toBe(
      'Free',
    )
    expect(formatModelPrice(model({ pricing: null }))).toBe('Price unavailable')
  })

  it('formats context windows and modalities', () => {
    expect(formatContextWindow(128000)).toBe('128K context')
    expect(formatContextWindow(1_000_000)).toBe('1M context')
    expect(formatContextWindow(null)).toBe('Context unknown')
    expect(
      formatModalities(
        model({ inputModalities: ['text', 'image'], outputModalities: ['text'] }),
      ),
    ).toBe('text, image in · text out')
  })

  it('builds capability chips', () => {
    expect(modelCapabilityChips(model({ capabilities: ['text', 'vision', 'tools'] }))).toEqual([
      'Text',
      'Vision',
      'Tools',
    ])
  })
})

describe('model picker selectors', () => {
  const openrouter = provider({ id: 'provider-1', label: 'OpenRouter' })
  const other = provider({ id: 'provider-2', label: 'Other' })
  const pinned = model({
    id: 'pinned',
    label: 'Pinned model',
    pinned: true,
    remoteId: 'author/pinned',
  })
  const regular = model({
    id: 'regular',
    label: 'Regular model',
    remoteId: 'author/regular',
  })
  const hidden = model({
    id: 'hidden',
    label: 'Hidden model',
    hidden: true,
    remoteId: 'author/hidden',
  })
  const otherModel = model({
    id: 'other',
    providerId: 'provider-2',
    label: 'Other model',
    remoteId: 'other/model',
  })

  it('puts pinned models first and groups the rest by provider', () => {
    const groups = buildModelPickerGroups(
      [regular, hidden, otherModel, pinned],
      [openrouter, other],
    )

    expect(groups.map((group) => group.label)).toEqual(['Pinned', 'OpenRouter', 'Other'])
    expect(groups[0].models.map((entry) => entry.id)).toEqual(['pinned'])
    expect(groups[1].models.map((entry) => entry.id)).toEqual(['regular'])
    expect(groups[2].models.map((entry) => entry.id)).toEqual(['other'])
  })

  it('searches by label, remote id, and canonical slug', () => {
    expect(modelMatchesQuery(regular, 'regular')).toBe(true)
    expect(modelMatchesQuery(regular, 'author/reg')).toBe(true)
    expect(modelMatchesQuery(regular, 'missing')).toBe(false)

    const groups = buildModelPickerGroups([regular, otherModel], [openrouter, other], 'other')
    expect(groups.flatMap((group) => group.models).map((entry) => entry.id)).toEqual(['other'])
  })

  it('excludes hidden models unless management mode is requested', () => {
    const normal = buildModelPickerGroups([regular, hidden], [openrouter])
    expect(normal.flatMap((group) => group.models).map((entry) => entry.id)).toEqual([
      'regular',
    ])

    const managed = buildModelPickerGroups([regular, hidden], [openrouter], '', {
      includeHidden: true,
    })
    expect(managed.flatMap((group) => group.models).map((entry) => entry.id)).toEqual([
      'hidden',
      'regular',
    ])
  })

  it('warns without substituting when a selected model is unavailable', () => {
    const available = modelAvailability(regular, [regular])
    expect(available.available).toBe(true)
    expect(available.message).toBeNull()

    const missing = modelAvailability(regular, [])
    expect(missing.available).toBe(false)
    expect(missing.message).toContain('no longer in the catalog')

    const nonText = modelAvailability(
      model({ id: 'image-only', outputModalities: ['image'] }),
      [model({ id: 'image-only', outputModalities: ['image'] })],
    )
    expect(nonText.available).toBe(false)
    expect(nonText.message).toContain('does not produce text output')
  })
})
