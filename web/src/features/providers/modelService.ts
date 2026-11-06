import { newId, nowIso, type Model } from '../../domain'
import { mapOpenAIModel, catalogDefaultsForPreset } from '../../providers/openaiCatalog'
import type { ModelInfo } from '../../providers/openrouter'
import { fallbackModelIdsFor, selectFallbackModel } from '../../providers/fallbackModels'
import { getPreset } from '../../providers/registry'
import { buildProviderContext } from './runtime'
import {
  ProviderServiceError,
  type DefaultModelSelection,
  type ManualModelInput,
  type ModelServiceOptions,
  type RefreshModelsResult,
} from './types'

function modelFromInfo(
  info: ModelInfo,
  providerId: string,
  existing: Model | undefined,
  now: string,
  id: string,
): Model {
  const isManual = existing?.source === 'manual'

  return {
    id: existing?.id ?? id,
    providerId,
    remoteId: info.remoteId,
    // A manual model's label and metadata are user-owned. Catalog refreshes
    // update its capabilities and pricing but do not overwrite those fields.
    label: isManual ? existing.label : info.label,
    contextWindow: info.contextWindow,
    maxOutputTokens: info.maxOutputTokens,
    capabilities: info.capabilities,
    pricing: info.pricing,
    canonicalSlug: info.canonicalSlug,
    inputModalities: info.inputModalities,
    outputModalities: info.outputModalities,
    supportedParameters: info.supportedParameters,
    metadata: isManual ? existing.metadata : info.metadata,
    hidden: existing?.hidden ?? false,
    pinned: existing?.pinned ?? false,
    source: existing?.source ?? 'discovered',
    createdAt: existing?.createdAt ?? now,
    updatedAt: now,
  }
}

/**
 * Model catalog and selection service.
 *
 * Discovery is delegated to the adapter. Persistence preserves user state
 * (`pinned`, `hidden`, and `source: 'manual'`) across refreshes.
 */
export class ModelService {
  private readonly providers: ModelServiceOptions['providers']
  private readonly models: ModelServiceOptions['models']
  private readonly adapter: ModelServiceOptions['adapter']
  private readonly runtime: ModelServiceOptions
  private readonly timestamp: () => string
  private readonly generateId: () => string

  constructor(options: ModelServiceOptions) {
    this.providers = options.providers
    this.models = options.models
    this.adapter = options.adapter
    this.runtime = options
    this.timestamp = options.nowIso ?? nowIso
    this.generateId = options.newId ?? newId
  }

  /**
   * Refresh the provider catalog and upsert models by `(providerId, remoteId)`.
   *
   * Existing rows keep their local id, `pinned`, `hidden`, and `source` flags.
   * Manual rows also keep their user-provided label and metadata. Models that
   * disappear from the catalog are not deleted, so cached and manual entries
   * remain selectable.
   */
  async refreshModels(
    providerId: string,
    options: { signal?: AbortSignal; forceRefresh?: boolean } = {},
  ): Promise<RefreshModelsResult> {
    const provider = await this.requireProvider(providerId)
    const preset = getPreset(provider.presetId)
    const signal = options.signal ?? new AbortController().signal
    let infos: ModelInfo[]

    try {
      infos = await this.adapter.listModels(
        buildProviderContext(provider, this.runtime, null),
        signal,
        {
          forceRefresh: options.forceRefresh ?? true,
          cache: this.runtime.catalogCache,
          now: this.runtime.now,
        },
      )
    } catch (error) {
      if (signal.aborted) {
        throw error
      }

      return this.degradedRefresh(providerId, preset, error)
    }

    const result = await this.upsertModels(providerId, infos)
    return { ...result, degraded: false, notice: null }
  }

  private async upsertModels(
    providerId: string,
    infos: readonly ModelInfo[],
  ): Promise<RefreshModelsResult> {
    const existing = await this.models.list({ providerId })
    const byRemoteId = new Map(existing.map((model) => [model.remoteId, model]))
    const now = this.timestamp()
    const models: Model[] = []
    let created = 0
    let updated = 0

    for (const info of infos) {
      const current = byRemoteId.get(info.remoteId)
      const model = modelFromInfo(info, providerId, current, now, this.generateId())

      if (current) {
        await this.models.update(current.id, model)
        updated += 1
      } else {
        await this.models.create(model)
        created += 1
      }

      models.push(model)
    }

    return { models, created, updated }
  }

  /**
   * Keep chat usable when discovery is blocked.
   *
   * Cached models are returned unchanged. When the cache is empty, the preset's
   * fallback list is persisted so model selection and onboarding still work.
   * The result carries a non-blocking notice; callers never need to treat this
   * as a send failure.
   */
  private async degradedRefresh(
    providerId: string,
    preset: ReturnType<typeof getPreset>,
    error: unknown,
  ): Promise<RefreshModelsResult> {
    const existing = await this.models.list({ providerId })

    if (existing.length > 0) {
      return {
        models: existing,
        created: 0,
        updated: 0,
        degraded: true,
        notice: `Discovery unavailable; using ${existing.length} cached model${existing.length === 1 ? '' : 's'}.`,
        refreshError: error instanceof Error ? error : new Error(String(error)),
      }
    }

    if (!preset) {
      throw error
    }

    const fallbackIds = fallbackModelIdsFor(preset.id, preset)
    const fallbackModels = await this.ensureFallbackModels(providerId, preset, fallbackIds)

    if (fallbackModels.length === 0) {
      throw error
    }

    const notice =
      fallbackIds.length === 1
        ? `Discovery unavailable; using fallback model ${fallbackIds[0]}.`
        : `Discovery unavailable; using ${fallbackModels.length} fallback models.`

    return {
      models: fallbackModels,
      created: fallbackModels.length,
      updated: 0,
      degraded: true,
      notice,
      refreshError: error instanceof Error ? error : new Error(String(error)),
    }
  }

  private async ensureFallbackModels(
    providerId: string,
    preset: NonNullable<ReturnType<typeof getPreset>>,
    fallbackIds: readonly string[],
  ): Promise<Model[]> {
    const existing = await this.models.list({ providerId })
    const byRemoteId = new Map(existing.map((model) => [model.remoteId, model]))
    const defaults = catalogDefaultsForPreset(preset.id)
    const created: Model[] = []

    for (const remoteId of fallbackIds) {
      if (byRemoteId.has(remoteId)) {
        continue
      }

      const info = mapOpenAIModel({ id: remoteId }, defaults)

      if (!info) {
        continue
      }

      info.metadata = { fallback: true }
      const model = modelFromInfo(info, providerId, undefined, this.timestamp(), this.generateId())
      created.push(await this.models.create(model))
    }

    return [...existing, ...created]
  }

  /**
   * Select the default model using the M1-01.2 fallback rule.
   *
   * 1. Use the preset `defaultModelId` when it exists in the catalog, even if
   *    the model is hidden.
   * 2. Otherwise use the first non-hidden text-output model ordered by
   *    `remoteId` ascending.
   * 3. If no text model exists, return null and a non-blocking notice.
   */
  async selectDefaultModel(providerId: string): Promise<DefaultModelSelection> {
    const provider = await this.requireProvider(providerId)
    const preset = getPreset(provider.presetId)

    if (!preset) {
      throw new ProviderServiceError(
        'invalid_input',
        `Unknown provider preset ${provider.presetId}.`,
        providerId,
      )
    }

    let models = await this.models.list({ providerId })
    const fallbackModelIds = fallbackModelIdsFor(preset.id, preset)

    if (models.length === 0 && fallbackModelIds.length > 0) {
      models = await this.ensureFallbackModels(providerId, preset, fallbackModelIds)
    }

    // Fallback rows are marked in metadata. Treat them as an empty catalog so
    // the rule reports that discovery was unavailable instead of pretending the
    // placeholder is a discovered default.
    const onlyFallback =
      models.length > 0 && models.every((model) => model.metadata?.fallback === true)
    const catalog = onlyFallback
      ? []
      : models.map((model) => ({
          remoteId: model.remoteId,
          hidden: model.hidden,
          outputModalities: model.outputModalities,
        }))

    const selection = selectFallbackModel({
      catalog,
      presetDefaultModelId: preset.defaultModelId,
      fallbackModelIds,
    })

    if (selection.selectedModelId === null) {
      return { modelId: null, remoteId: null, notice: selection.notice }
    }

    const selected = models.find((model) => model.remoteId === selection.selectedModelId)

    if (!selected) {
      return {
        modelId: null,
        remoteId: null,
        notice:
          selection.notice ??
          `Default model ${preset.defaultModelId} is unavailable; no text model is available.`,
      }
    }

    return {
      modelId: selected.id,
      remoteId: selected.remoteId,
      notice: selection.notice,
    }
  }

  async listModels(providerId: string): Promise<Model[]> {
    return this.models.list({ providerId })
  }

  async setModelPinned(modelId: string, pinned: boolean): Promise<Model> {
    const model = await this.requireModel(modelId)
    return this.models.update(model.id, { pinned })
  }

  async setModelHidden(modelId: string, hidden: boolean): Promise<Model> {
    const model = await this.requireModel(modelId)
    return this.models.update(model.id, { hidden })
  }

  /** Add a user-defined model that is not present in the catalog. */
  async addManualModel(providerId: string, input: ManualModelInput): Promise<Model> {
    const provider = await this.requireProvider(providerId)
    const remoteId = input.remoteId.trim()

    if (remoteId === '') {
      throw new ProviderServiceError('invalid_input', 'remoteId must not be empty.', providerId)
    }

    const existing = await this.models.list({ providerId })

    if (existing.some((model) => model.remoteId === remoteId)) {
      throw new ProviderServiceError(
        'invalid_input',
        `Model ${remoteId} already exists for this provider.`,
        providerId,
      )
    }

    const now = this.timestamp()
    const model: Model = {
      id: this.generateId(),
      providerId: provider.id,
      remoteId,
      label: input.label?.trim() || remoteId,
      contextWindow: input.contextWindow ?? null,
      maxOutputTokens: input.maxOutputTokens ?? null,
      capabilities: input.capabilities ?? ['text'],
      pricing: input.pricing ?? null,
      canonicalSlug: input.canonicalSlug ?? null,
      inputModalities: input.inputModalities ?? ['text'],
      outputModalities: input.outputModalities ?? ['text'],
      supportedParameters: input.supportedParameters ?? [],
      metadata: input.metadata ?? null,
      hidden: false,
      pinned: false,
      source: 'manual',
      createdAt: now,
      updatedAt: now,
    }

    return this.models.create(model)
  }

  private async requireProvider(providerId: string) {
    const provider = await this.providers.get(providerId)

    if (!provider) {
      throw new ProviderServiceError(
        'not_found',
        `Provider ${providerId} was not found.`,
        providerId,
      )
    }

    return provider
  }

  private async requireModel(modelId: string): Promise<Model> {
    const model = await this.models.get(modelId)

    if (!model) {
      throw new ProviderServiceError('not_found', `Model ${modelId} was not found.`)
    }

    return model
  }
}
