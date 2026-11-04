import { Link } from '@tanstack/react-router'
import { useEffect, useState } from 'react'
import { DataExportPanel } from './DataExportPanel'
import { createExportService } from './exportServiceFactory'
import type { ExportServiceLike } from './exportService'
import './settings.css'

export interface DataSettingsScreenProps {
  /** Test or story override. When omitted, IndexedDB is opened lazily. */
  exportService?: ExportServiceLike
}

function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause)
}

/**
 * `/settings/data` entry point for storage and portability.
 *
 * Export is implemented here. Import and clear-data controls remain for
 * M2-04.4 and M2-04.5; this screen never deletes or replaces local data.
 */
export function DataSettingsScreen({ exportService }: DataSettingsScreenProps = {}) {
  const [service, setService] = useState<ExportServiceLike | null>(exportService ?? null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (exportService !== undefined) {
      return
    }

    let cancelled = false

    createExportService().then(
      (value) => {
        if (!cancelled) {
          setService(value)
        }
      },
      (cause: unknown) => {
        if (!cancelled) {
          setError(errorMessage(cause))
        }
      },
    )

    return () => {
      cancelled = true
    }
  }, [exportService])

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
          Provider API keys are never included in exports. Import and clear-data controls arrive in
          later M2-04 tasks.
        </p>
      </div>

      {error && (
        <p className="settings-screen__error" role="alert">
          {error}
        </p>
      )}

      {service === null ? (
        !error && <p role="status">Preparing export…</p>
      ) : (
        <DataExportPanel service={service} />
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
