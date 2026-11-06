import { useState } from 'react'
import type { DataControlsServiceLike } from './dataControlsService'
import './settings.css'

export const CLEAR_ALL_CONFIRMATION = 'DELETE ALL DATA'

export interface DataClearPanelProps {
  service: Pick<DataControlsServiceLike, 'clearAll'>
  /** Called after a successful clear so usage and key lists can reload. */
  onCleared?: () => void
  /** Test override. Defaults to `CLEAR_ALL_CONFIRMATION`. */
  confirmationPhrase?: string
}

function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause)
}

/**
 * Clear-all control for `/settings/data`.
 *
 * The destructive action stays disabled until the user types the exact
 * confirmation phrase. The warning is always visible, and the button is the
 * only path to `clearAll`.
 */
export function DataClearPanel({
  service,
  onCleared,
  confirmationPhrase = CLEAR_ALL_CONFIRMATION,
}: DataClearPanelProps) {
  const [phrase, setPhrase] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [status, setStatus] = useState<string | null>(null)

  const confirmed = phrase === confirmationPhrase

  async function handleClear() {
    if (!confirmed || busy) {
      return
    }

    setBusy(true)
    setError(null)
    setStatus(null)

    try {
      const result = await service.clearAll()
      setPhrase('')
      setStatus(
        `Removed ${result.conversations} conversation(s), ${result.messages} message(s), ${result.attachments} attachment(s), and ${result.secrets} stored key(s).`,
      )
      onCleared?.()
    } catch (cause) {
      setError(errorMessage(cause))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="settings-screen__panel" aria-labelledby="data-clear-title">
      <h3 id="data-clear-title">Clear all data</h3>
      <p className="settings-screen__warning" role="note">
        This permanently deletes conversations, messages, attachments, providers, models, and
        stored API keys from this device. Appearance and chat preferences are kept. This cannot be
        undone.
      </p>

      <label className="settings-screen__field" htmlFor="clear-all-confirmation">
        <span>Type {confirmationPhrase} to confirm</span>
        <input
          id="clear-all-confirmation"
          type="text"
          autoComplete="off"
          spellCheck={false}
          value={phrase}
          disabled={busy}
          onChange={(event) => setPhrase(event.target.value)}
        />
      </label>

      <div className="settings-screen__actions">
        <button
          type="button"
          className="ui-button ui-button--danger ui-button--md"
          disabled={!confirmed || busy}
          onClick={handleClear}
        >
          {busy ? 'Clearing…' : 'Clear all data'}
        </button>
        {phrase !== '' && !busy && (
          <button
            type="button"
            className="ui-button ui-button--ghost ui-button--md"
            onClick={() => setPhrase('')}
          >
            Cancel
          </button>
        )}
      </div>

      {error && (
        <p className="settings-screen__error" role="alert">
          {error}
        </p>
      )}

      {status && (
        <p className="settings-screen__muted" role="status">
          {status}
        </p>
      )}
    </div>
  )
}
