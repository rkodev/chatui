import { Link } from '@tanstack/react-router'
import { useEffect, useState } from 'react'
import { openDatabase } from '../../storage'
import { DataExportPanel } from './DataExportPanel'
import { DataImportPanel } from './DataImportPanel'
import { createExportService } from './exportServiceFactory'
import { createImportService } from './importServiceFactory'
import type { ExportServiceLike } from './exportService'
import type { ImportServiceLike } from './importService'
import './settings.css'

export interface DataSettingsScreenProps {
  /** Test or story override. When omitted, IndexedDB is opened lazily. */
  exportService?: ExportServiceLike
  /** Test or story override. When omitted, IndexedDB is opened lazily. */
  importService?: ImportServiceLike
}

function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause)
}

/**
 * `/settings/data` entry point for storage and portability.
 *
 * Export and import share one IndexedDB connection when both are created here.
 * Import is merge-by-default; replace mode is confirmed in `DataImportPanel`.
 * Clear-data controls remain for M2-04.5.
 */
export function DataSettingsScreen({
  exportService,
  importService,
}: DataSettingsScreenProps = {}) {
  const [exportServiceState, setExportServiceState] = useState<ExportServiceLike | null>(
    exportService ?? null,
  )
  const [importServiceState, setImportServiceState] = useState<ImportServiceLike | null>(
    importService ?? null,
  )
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (exportService !== undefined && importService !== undefined) {
      return
    }

    let cancelled = false

    async function load() {
      const db = await openDatabase()
      const [nextExport, nextImport] = await Promise.all([
        exportService ?? createExportService({ db }),
        importService ?? createImportService({ db }),
      ])

      if (!cancelled) {
        setExportServiceState(nextExport)
        setImportServiceState(nextImport)
      }
    }

    load().catch((cause: unknown) => {
      if (!cancelled) {
        setError(errorMessage(cause))
      }
    })

    return () => {
      cancelled = true
    }
  }, [exportService, importService])

  return (
    <section className="settings-screen" aria-labelledby="data-settings-title">
      <header className="settings-screen__header">
        <h2 id="data-settings-title">Data</h2>
      </header>

      <div className="settings-screen__panel">
        <h3>Storage and portability</h3>
        <p>
          Conversations, messages, attachments, provider records, and settings are stored on this
          device in IndexedDB and OPFS.
        </p>
        <p className="settings-screen__muted">
          Provider API keys are never included in exports. Import validates the archive before
          writing; clear-data controls arrive in M2-04.5.
        </p>
      </div>

      {error && (
        <p className="settings-screen__error" role="alert">
          {error}
        </p>
      )}

      {exportServiceState === null || importServiceState === null ? (
        !error && <p role="status">Preparing data services…</p>
      ) : (
        <>
          <DataExportPanel service={exportServiceState} />
          <DataImportPanel service={importServiceState} />
        </>
      )}

      <div className="settings-screen__panel">
        <h3>Related</h3>
        <p>
          Backup configuration is a future feature. See <Link to="/settings/backup">Backup</Link>{' '}
          for its current status, and <Link to="/settings/about">About</Link> for privacy details.
        </p>
      </div>
    </section>
  )
}
