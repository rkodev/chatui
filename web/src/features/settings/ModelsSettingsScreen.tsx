import { Link } from '@tanstack/react-router'
import { useCallback, useEffect, useState } from 'react'
import type { Model, Provider } from '../../domain'
import {
  ModelPickerDialog,
  ProviderManagementProvider,
  useProviderManagement,
  type ManualModelInput,
} from '../providers'
import type { SettingsDefaults } from './settingsService'
import './settings.css'

function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause)
}

/**
 * `/settings/models` global defaults and model management.
 *
 * The default model is persisted through the typed settings facade. Pin, hide,
 * manual add, and refresh go through the model service so user state survives
 * catalog refreshes.
 */
export function ModelsSettingsScreen() {
  const service = useProviderManagement()
  const [providers, setProviders] = useState<Provider[] | null>(null)
  const [models, setModels] = useState<Model[]>([])
  const [defaults, setDefaults] = useState<SettingsDefaults | null>(null)
  const [warning, setWarning] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [includeHidden, setIncludeHidden] = useState(false)

  const load = useCallback(async () => {
    const providerList = await service.listProviders()
    const defaultsValue = await service.getDefaults()
    const providerId = defaultsValue.providerId ?? providerList[0]?.id ?? null
    const modelList = providerId === null ? [] : await service.listModels(providerId)
    const warningValue = await service.getDefaultModelWarning()

    setProviders(providerList)
    setDefaults(defaultsValue)
    setModels(modelList)
    setWarning(warningValue)
  }, [service])

  useEffect(() => {
    let cancelled = false

    service
      .listProviders()
      .then(async (providerList) => {
        const defaultsValue = await service.getDefaults()
        const providerId = defaultsValue.providerId ?? providerList[0]?.id ?? null
        const modelList = providerId === null ? [] : await service.listModels(providerId)
        const warningValue = await service.getDefaultModelWarning()

        if (!cancelled) {
          setProviders(providerList)
          setDefaults(defaultsValue)
          setModels(modelList)
          setWarning(warningValue)
        }
      })
      .catch((cause: unknown) => {
        if (!cancelled) {
          setError(errorMessage(cause))
        }
      })

    return () => {
      cancelled = true
    }
  }, [service])

  async function run(action: () => Promise<void>) {
    setError(null)

    try {
      await action()
      await load()
    } catch (cause) {
      setError(errorMessage(cause))
    }
  }

  async function handleSelectDefault(model: Model) {
    await run(async () => {
      await service.setDefaults({ providerId: model.providerId, modelId: model.id })
    })
  }

  async function handlePin(model: Model, pinned: boolean) {
    await run(async () => {
      await service.setModelPinned(model.id, pinned)
    })
  }

  async function handleHide(model: Model, hidden: boolean) {
    await run(async () => {
      await service.setModelHidden(model.id, hidden)
    })
  }

  async function handleAddManual(input: ManualModelInput) {
    const providerId = defaults?.providerId ?? providers?.[0]?.id ?? null

    if (providerId === null) {
      throw new Error('Add a provider before adding a model.')
    }

    await run(async () => {
      await service.addManualModel(providerId, input)
    })
  }

  async function handleRefresh() {
    const providerId = defaults?.providerId ?? providers?.[0]?.id ?? null

    if (providerId === null) {
      throw new Error('Add a provider before refreshing models.')
    }

    await run(async () => {
      await service.refreshModels(providerId)
    })
  }

  function openDialog(manage: boolean) {
    setIncludeHidden(manage)
    setDialogOpen(true)
  }

  if (providers === null || defaults === null) {
    if (error) {
      return (
        <p className="models-settings__error" role="alert">
          {error}
        </p>
      )
    }

    return <p role="status">Loading models…</p>
  }

  const defaultProvider =
    providers.find((provider) => provider.id === defaults.providerId) ?? null
  const defaultModel = models.find((model) => model.id === defaults.modelId) ?? null
  const pinnedCount = models.filter((model) => model.pinned).length
  const hiddenCount = models.filter((model) => model.hidden).length

  return (
    <section className="models-settings" aria-labelledby="models-settings-title">
      <header className="models-settings__header">
        <h2 id="models-settings-title">Models</h2>
        <Link to="/settings/providers" className="providers-screen__link">
          Manage providers
        </Link>
      </header>

      {error && (
        <p className="models-settings__error" role="alert">
          {error}
        </p>
      )}

      {warning && (
        <p className="models-settings__warning" role="status">
          {warning}
        </p>
      )}

      {providers.length === 0 && (
        <p>
          No provider is configured. <Link to="/settings/providers/new">Add OpenRouter</Link>{' '}
          before choosing a default model.
        </p>
      )}

      {providers.length > 0 && (
        <>
          <section className="models-settings__panel" aria-labelledby="default-model-title">
            <h3 id="default-model-title">Global default</h3>
            <dl className="provider-detail__details">
              <dt>Provider</dt>
              <dd>{defaultProvider?.label ?? 'Not selected'}</dd>
              <dt>Model</dt>
              <dd>{defaultModel?.label ?? 'Not selected'}</dd>
              <dt>Model id</dt>
              <dd>{defaultModel?.remoteId ?? '—'}</dd>
            </dl>
            <div className="models-settings__actions">
              <button
                type="button"
                className="ui-button ui-button--primary ui-button--md"
                onClick={() => openDialog(false)}
              >
                Choose default model
              </button>
              <button
                type="button"
                className="ui-button ui-button--secondary ui-button--md"
                onClick={() => openDialog(true)}
              >
                Manage models
              </button>
            </div>
          </section>

          <section className="models-settings__panel" aria-labelledby="model-management-title">
            <h3 id="model-management-title">Model management</h3>
            <p className="models-settings__muted">
              {models.length} models cached · {pinnedCount} pinned · {hiddenCount} hidden
            </p>
            <p className="models-settings__muted">
              Pin models to keep them at the top of the picker. Hidden models stay
              selectable but are removed from the normal picker.
            </p>
            <div className="models-settings__actions">
              <button
                type="button"
                className="ui-button ui-button--secondary ui-button--md"
                onClick={() => openDialog(true)}
              >
                Pin, hide, or add models
              </button>
            </div>
          </section>
        </>
      )}

      <ModelPickerDialog
        open={dialogOpen}
        onOpenChange={setDialogOpen}
        models={models}
        providers={providers}
        selectedModelId={defaults.modelId}
        onSelect={handleSelectDefault}
        onPin={handlePin}
        onHide={handleHide}
        onAddManual={handleAddManual}
        onRefresh={handleRefresh}
        title={includeHidden ? 'Manage models' : 'Choose a default model'}
        includeHidden={includeHidden}
      />
    </section>
  )
}

/** `/settings/models` route component. */
export function ModelsSettingsRoute() {
  return (
    <ProviderManagementProvider>
      <ModelsSettingsScreen />
    </ProviderManagementProvider>
  )
}
