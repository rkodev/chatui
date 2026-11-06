import { Link } from '@tanstack/react-router'
import { useEffect, useState } from 'react'
import { openDatabase } from '../../storage'
import { DataClearPanel } from './DataClearPanel'
import { DataExportPanel } from './DataExportPanel'
import { DataImportPanel } from './DataImportPanel'
import { DataKeysPanel } from './DataKeysPanel'
import { DataUsagePanel } from './DataUsagePanel'
import { createDataControlsService } from './dataControlsServiceFactory'
import { createExportService } from './exportServiceFactory'
import { createImportService } from './importServiceFactory'
import type { DataControlsServiceLike } from './dataControlsService'
import type { ExportServiceLike } from './exportService'
import type { ImportServiceLike } from './importService'
import './settings.css'

export interface DataSettingsScreenProps {
  /** Test or story override. When omitted, IndexedDB is opened lazily. */
  exportService?: ExportServiceLike
  /** Test or story override. When omitted, IndexedDB is opened lazily. */
  importService?: ImportServiceLike
  /** Test or story override. When omitted, IndexedDB is opened lazily. */
  dataControlsService?: DataControlsServiceLike
}

function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause)
}

/**
 * `/settings/data` entry point for storage and portability.
 *
 * Export, import, usage, clear-all, and per-key deletion share one IndexedDB
 * connection when created here. Import is merge-by-default; replace mode and
 * clear-all are confirmed in their panels. Backup remains a placeholder.
 */
export function DataSettingsScreen({
  exportService,
  importService,
  dataControlsService,
}: DataSettingsScreenProps = {}) {
  const [exportServiceState, setExportServiceState] = useState<ExportServiceLike | null>(
    exportService ?? null,
  )
  const [importServiceState, setImportServiceState] = useState<ImportServiceLike | null>(
    importService ?? null,
  )
  const [dataControlsServiceState, setDataControlsServiceState] =
    useState<DataControlsServiceLike | null>(dataControlsService ?? null)
  const [dataVersion, setDataVersion] = useState(0)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (
      exportService !== undefined &&
      importService !== undefined &&
      dataControlsService !== undefined
    ) {
      return
    }

    let cancelled = false

    async function load() {
      const db = await openDatabase()
      const [nextExport, nextImport, nextDataControls] = await Promise.all([
        exportService ?? createExportService({ db }),
        importService ?? createImportService({ db }),
        dataControlsService ?? createDataControlsService({ db }),
      ])

      if (!cancelled) {
        setExportServiceState(nextExport)
        setImportServiceState(nextImport)
        setDataControlsServiceState(nextDataControls)
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
  }, [exportService, importService, dataControlsService])

  function refreshData() {
    setDataVersion((current) => current + 1)
  }

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
          writing. Clear-all and per-key deletion are available below.
        </p>
      </div>

      {error && (
        <p className="settings-screen__error" role="alert">
          {error}
        </p>
      )}

      {exportServiceState === null ||
      importServiceState === null ||
      dataControlsServiceState === null ? (
        !error && <p role="status">Preparing data services…</p>
      ) : (
        <>
          <DataUsagePanel service={dataControlsServiceState} refreshKey={dataVersion} />
          <DataKeysPanel
            service={dataControlsServiceState}
            refreshKey={dataVersion}
            onChanged={refreshData}
          />
          <DataClearPanel service={dataControlsServiceState} onCleared={refreshData} />
          <DataExportPanel service={exportServiceState} />
          <DataImportPanel service={importServiceState} onImported={refreshData} />
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
