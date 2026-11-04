import type { Model, Provider } from '../../domain'

/**
 * Pure model-picker selectors.
 *
 * The picker keeps pinned models at the top, then groups the remaining models
 * by provider. Hidden models are excluded unless the caller is managing
 * models. Search matches the label, remote id, and canonical slug.
 */

export interface ModelPickerGroup {
  key: string
  label: string
  pinned: boolean
  models: Model[]
}

export interface ModelPickerOptions {
  includeHidden?: boolean
}

export interface ModelAvailability {
  available: boolean
  message: string | null
}

function normalize(value: string): string {
  return value.trim().toLocaleLowerCase()
}

/** True when a model matches a free-text query. */
export function modelMatchesQuery(model: Model, query: string): boolean {
  const needle = normalize(query)

  if (needle === '') {
    return true
  }

  return [model.label, model.remoteId, model.canonicalSlug ?? '']
    .map(normalize)
    .some((value) => value.includes(needle))
}

function compareModels(left: Model, right: Model): number {
  const byLabel = left.label.localeCompare(right.label)

  if (byLabel !== 0) {
    return byLabel
  }

  return left.remoteId.localeCompare(right.remoteId)
}

/**
 * Build the picker groups.
 *
 * Pinned models appear first in a dedicated group. The remaining models are
 * grouped by provider id and sorted by provider label. Models are never
 * deduplicated by canonical slug because distinct OpenRouter ids remain
 * separately selectable.
 */
export function buildModelPickerGroups(
  models: readonly Model[],
  providers: readonly Provider[],
  query = '',
  options: ModelPickerOptions = {},
): ModelPickerGroup[] {
  const providerLabels = new Map(providers.map((provider) => [provider.id, provider.label]))
  const visible = models.filter(
    (model) => (options.includeHidden === true || !model.hidden) && modelMatchesQuery(model, query),
  )
  const pinned = visible.filter((model) => model.pinned).sort(compareModels)
  const groups: ModelPickerGroup[] = []

  if (pinned.length > 0) {
    groups.push({
      key: 'pinned',
      label: 'Pinned',
      pinned: true,
      models: pinned,
    })
  }

  const byProvider = new Map<string, Model[]>()

  for (const model of visible) {
    if (model.pinned) {
      continue
    }

    const list = byProvider.get(model.providerId) ?? []
    list.push(model)
    byProvider.set(model.providerId, list)
  }

  const providerGroups = [...byProvider.entries()]
    .map(([providerId, providerModels]) => ({
      key: providerId,
      label: providerLabels.get(providerId) ?? providerId,
      pinned: false,
      models: providerModels.sort(compareModels),
    }))
    .sort((left, right) => left.label.localeCompare(right.label))

  return [...groups, ...providerGroups]
}

/** Capability chip labels for a model row. */
export function modelCapabilityChips(model: Pick<Model, 'capabilities'>): string[] {
  return model.capabilities.map((capability) => {
    switch (capability) {
      case 'text':
        return 'Text'
      case 'vision':
        return 'Vision'
      case 'files':
        return 'Files'
      case 'tools':
        return 'Tools'
      case 'reasoning':
        return 'Reasoning'
      case 'streaming':
        return 'Streaming'
      default:
        return capability
    }
  })
}

/**
 * Warn when a selected model cannot be used for a send.
 *
 * The check is deliberately non-substituting: callers show the warning and
 * keep the user's selection instead of silently choosing another model.
 */
export function modelAvailability(
  model: Model | null | undefined,
  models: readonly Model[],
): ModelAvailability {
  if (!model) {
    return {
      available: false,
      message: 'The selected model is no longer available. Choose another model before sending.',
    }
  }

  const current = models.find((candidate) => candidate.id === model.id)

  if (!current) {
    return {
      available: false,
      message: `Model ${model.remoteId} is no longer in the catalog. Choose another model before sending.`,
    }
  }

  if (!current.outputModalities.includes('text')) {
    return {
      available: false,
      message: `Model ${current.remoteId} does not produce text output. Choose another model before sending.`,
    }
  }

  return { available: true, message: null }
}
