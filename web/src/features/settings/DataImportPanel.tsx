import { useRef, useState } from 'react'
import type { ImportMode, ImportServiceLike } from './importService'
import './settings.css'

export const IMPORT_REPLACE_CONFIRMATION =
  'Replace all local conversations, messages, attachments, providers, models, and settings with this archive? This cannot be undone.'

export interface DataImportPanelProps {
  service: ImportServiceLike
  /** Test override. Defaults to the browser confirmation dialog. */
  confirmReplace?: (message: string) => boolean
}

function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause)
}

/**
 * Import controls for `/settings/data`.
 *
 * Merge is selected by default. Replace is explicit and asks for confirmation
 * before the service is called. The file input is cleared after every attempt
 * so selecting the same file again still triggers a change event.
 */
export function DataImportPanel({
  service,
  confirmReplace = (message) => window.confirm(message),
}: DataImportPanelProps) {
  const [mode, setMode] = useState<ImportMode>('merge')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [status, setStatus] = useState<string | null>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  function resetInput() {
    if (inputRef.current !== null) {
      inputRef.current.value = ''
    }
  }

  async function handleFile(file: File) {
    if (mode === 'replace' && !confirmReplace(IMPORT_REPLACE_CONFIRMATION)) {
      setStatus('Import cancelled.')
      resetInput()
      return
    }

    setBusy(true)
    setError(null)
    setStatus(null)

    try {
      const result = await service.importFile(file, { mode })
      setStatus(
        `Imported ${result.conversations} conversation(s), ${result.messages} message(s), and ${result.attachments} attachment(s).`,
      )
    } catch (cause) {
      setError(errorMessage(cause))
    } finally {
      setBusy(false)
      resetInput()
    }
  }

  return (
    <div className="settings-screen__panel" aria-labelledby="data-import-title">
      <h3 id="data-import-title">Import</h3>
      <p className="settings-screen__warning" role="note">
        Only import `chatui.export` archives you trust. Imported content is never executed, and
        invalid archives leave local data unchanged.
      </p>

      <fieldset className="settings-screen__field">
        <legend>Import mode</legend>
        <label className="settings-screen__checkbox">
          <input
            type="radio"
            name="import-mode"
            value="merge"
            checked={mode === 'merge'}
            onChange={() => setMode('merge')}
          />
          <span>Merge (default) — keep local data and add or update records from the archive.</span>
        </label>
        <label className="settings-screen__checkbox">
          <input
            type="radio"
            name="import-mode"
            value="replace"
            checked={mode === 'replace'}
            onChange={() => setMode('replace')}
          />
          <span>
            Replace — delete local conversations, messages, attachments, providers, models, and
            settings first.
          </span>
        </label>
      </fieldset>

      {mode === 'replace' && (
        <p className="settings-screen__warning" role="alert">
          Replace mode deletes local data after you confirm.
        </p>
      )}

      <label className="settings-screen__field" htmlFor="import-file">
        <span>Archive file</span>
        <input
          id="import-file"
          ref={inputRef}
          type="file"
          accept=".json,application/json"
          disabled={busy}
          onChange={(event) => {
            const file = event.target.files?.[0]

            if (file !== undefined) {
              void handleFile(file)
            }
          }}
        />
      </label>

      {busy && <p role="status">Importing…</p>}

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
