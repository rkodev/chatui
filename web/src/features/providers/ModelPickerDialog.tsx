import { useState, type FormEvent } from 'react'
import type { Model, Provider } from '../../domain'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogTitle,
} from '../../ui/primitives'
import {
  formatContextWindow,
  formatModelPrice,
  formatModalities,
  isFreeModel,
} from './format'
import {
  buildModelPickerGroups,
  modelAvailability,
  modelCapabilityChips,
} from './modelPicker'
import type { ManualModelInput } from './types'
import './providers.css'

export interface ModelPickerDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  models: Model[]
  providers: Provider[]
  selectedModelId: string | null
  onSelect: (model: Model) => void | Promise<void>
  onPin?: (model: Model, pinned: boolean) => void | Promise<void>
  onHide?: (model: Model, hidden: boolean) => void | Promise<void>
  onAddManual?: (input: ManualModelInput) => void | Promise<void>
  onRefresh?: () => void | Promise<void>
  onManage?: () => void
  title?: string
  includeHidden?: boolean
  /** Optional focus target restored when the dialog closes. */
  onCloseAutoFocus?: (event: Event) => void
}

function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause)
}

function moderationLabel(model: Model): string {
  const topProvider = model.metadata?.topProvider

  if (
    typeof topProvider === 'object' &&
    topProvider !== null &&
    !Array.isArray(topProvider) &&
    typeof (topProvider as Record<string, unknown>).isModerated === 'boolean'
  ) {
    return (topProvider as Record<string, unknown>).isModerated ? 'Moderated' : 'Not moderated'
  }

  return 'Not reported'
}

function upstreamContext(model: Model): string {
  const topProvider = model.metadata?.topProvider

  if (
    typeof topProvider === 'object' &&
    topProvider !== null &&
    !Array.isArray(topProvider) &&
    typeof (topProvider as Record<string, unknown>).contextLength === 'number'
  ) {
    return formatContextWindow((topProvider as Record<string, unknown>).contextLength as number)
  }

  return 'Not reported'
}

/**
 * Accessible model picker and management dialog.
 *
 * Search, grouping, pinned-first ordering, and availability warnings are pure
 * selectors. Pin/hide/manual-add/refresh actions are delegated to the caller so
 * the same dialog works for global defaults, per-conversation overrides, and
 * model management.
 */
export function ModelPickerDialog({
  open,
  onOpenChange,
  models,
  providers,
  selectedModelId,
  onSelect,
  onPin,
  onHide,
  onAddManual,
  onRefresh,
  onManage,
  title = 'Choose a model',
  includeHidden = false,
  onCloseAutoFocus,
}: ModelPickerDialogProps) {
  const [query, setQuery] = useState('')
  const [manualRemoteId, setManualRemoteId] = useState('')
  const [manualLabel, setManualLabel] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const groups = buildModelPickerGroups(models, providers, query, { includeHidden })
  const selectedModel = models.find((model) => model.id === selectedModelId) ?? null
  const availability = modelAvailability(selectedModel, models)

  async function run(action: () => void | Promise<void>) {
    if (busy) {
      return
    }

    setBusy(true)
    setError(null)

    try {
      await action()
    } catch (cause) {
      setError(errorMessage(cause))
    } finally {
      setBusy(false)
    }
  }

  async function handleSelect(model: Model) {
    await run(async () => {
      await onSelect(model)
      onOpenChange(false)
    })
  }

  async function handleManualAdd(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()

    if (!onAddManual) {
      return
    }

    const remoteId = manualRemoteId.trim()

    if (remoteId === '') {
      setError('Enter the model id, for example author/model.')
      return
    }

    await run(async () => {
      await onAddManual({
        remoteId,
        label: manualLabel.trim() || undefined,
      })
      setManualRemoteId('')
      setManualLabel('')
    })
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="model-picker" onCloseAutoFocus={onCloseAutoFocus}>
        <DialogTitle>{title}</DialogTitle>
        <DialogDescription>
          Search models, pin favourites, and review context, modalities, and price per
          million tokens.
        </DialogDescription>

        <label htmlFor="model-picker-search">Search models</label>
        <input
          id="model-picker-search"
          className="model-picker__search"
          type="search"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="Search by name or model id"
        />

        {error && (
          <p className="model-picker__error" role="alert">
            {error}
          </p>
        )}

        {!availability.available && availability.message && (
          <p className="model-picker__warning" role="status">
            {availability.message}
          </p>
        )}

        {groups.length === 0 && (
          <p className="model-picker__empty">No models match your search.</p>
        )}

        <div className="model-picker__groups">
          {groups.map((group) => (
            <section key={group.key} className="model-picker__group">
              <h3 className="model-picker__group-title">{group.label}</h3>
              <ul className="model-picker__list">
                {group.models.map((model) => {
                  const selected = model.id === selectedModelId
                  const free = isFreeModel(model)

                  return (
                    <li
                      key={model.id}
                      className="model-row"
                      data-selected={selected}
                      data-hidden={model.hidden}
                    >
                      <div className="model-row__main">
                        <button
                          type="button"
                          className="model-row__select"
                          aria-pressed={selected}
                          onClick={() => handleSelect(model)}
                        >
                          <span className="model-row__label">{model.label}</span>
                          <span className="model-row__meta">
                            <span>{model.remoteId}</span>
                            <span>{formatContextWindow(model.contextWindow)}</span>
                            <span>{formatModalities(model)}</span>
                            <span>{formatModelPrice(model)}</span>
                          </span>
                        </button>
                        <div className="model-row__actions">
                          {free && (
                            <span className="model-row__badge model-row__badge--free">
                              Free
                            </span>
                          )}
                          {model.hidden && (
                            <span className="model-row__badge model-row__badge--warning">
                              Hidden
                            </span>
                          )}
                          {onPin && (
                            <button
                              type="button"
                              className="ui-button ui-button--ghost ui-button--sm"
                              disabled={busy}
                              onClick={() => run(() => onPin(model, !model.pinned))}
                            >
                              {model.pinned ? 'Unpin' : 'Pin'}
                            </button>
                          )}
                          {onHide && (
                            <button
                              type="button"
                              className="ui-button ui-button--ghost ui-button--sm"
                              disabled={busy}
                              onClick={() => run(() => onHide(model, !model.hidden))}
                            >
                              {model.hidden ? 'Unhide' : 'Hide'}
                            </button>
                          )}
                        </div>
                      </div>

                      <div className="model-row__chips">
                        {modelCapabilityChips(model).map((chip) => (
                          <span key={chip} className="model-row__chip">
                            {chip}
                          </span>
                        ))}
                      </div>

                      <details className="model-row__details">
                        <summary>Details</summary>
                        <dl>
                          <dt>Canonical slug</dt>
                          <dd>{model.canonicalSlug ?? 'Not reported'}</dd>
                          <dt>Max output</dt>
                          <dd>
                            {model.maxOutputTokens === null
                              ? 'Not reported'
                              : `${model.maxOutputTokens.toLocaleString()} tokens`}
                          </dd>
                          <dt>Upstream context</dt>
                          <dd>{upstreamContext(model)}</dd>
                          <dt>Supported parameters</dt>
                          <dd>
                            {model.supportedParameters.length > 0
                              ? model.supportedParameters.join(', ')
                              : 'Not reported'}
                          </dd>
                          <dt>Moderation</dt>
                          <dd>{moderationLabel(model)}</dd>
                          <dt>Model page</dt>
                          <dd>
                            <a
                              href={`https://openrouter.ai/${model.remoteId}`}
                              target="_blank"
                              rel="noreferrer"
                            >
                              OpenRouter model page
                            </a>
                          </dd>
                        </dl>
                      </details>
                    </li>
                  )
                })}
              </ul>
            </section>
          ))}
        </div>

        {onAddManual && (
          <form className="model-picker__manual" onSubmit={handleManualAdd}>
            <h3>Add a model manually</h3>
            <div className="model-picker__field">
              <label htmlFor="model-picker-manual-id">Model id</label>
              <input
                id="model-picker-manual-id"
                type="text"
                value={manualRemoteId}
                onChange={(event) => setManualRemoteId(event.target.value)}
                placeholder="author/model"
              />
            </div>
            <div className="model-picker__field">
              <label htmlFor="model-picker-manual-label">Label (optional)</label>
              <input
                id="model-picker-manual-label"
                type="text"
                value={manualLabel}
                onChange={(event) => setManualLabel(event.target.value)}
              />
            </div>
            <div className="model-picker__actions">
              <button
                type="submit"
                className="ui-button ui-button--secondary ui-button--sm"
                disabled={busy}
              >
                Add model
              </button>
            </div>
          </form>
        )}

        <div className="model-picker__actions">
          {onRefresh && (
            <button
              type="button"
              className="ui-button ui-button--secondary ui-button--sm"
              disabled={busy}
              onClick={() => run(onRefresh)}
            >
              Refresh models
            </button>
          )}
          {onManage && (
            <button
              type="button"
              className="ui-button ui-button--ghost ui-button--sm"
              onClick={onManage}
            >
              Manage models
            </button>
          )}
        </div>
      </DialogContent>
    </Dialog>
  )
}
