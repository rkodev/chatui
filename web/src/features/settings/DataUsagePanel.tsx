import { useEffect, useState } from 'react'
import type { DataControlsServiceLike, StorageUsage } from './dataControlsService'
import { formatBytes } from './formatBytes'
import './settings.css'

export interface DataUsagePanelProps {
  service: Pick<DataControlsServiceLike, 'getUsage'>
  /** Bump to reload usage after a clear or key deletion. */
  refreshKey?: number
}

function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause)
}

const ROWS: Array<{ key: keyof Omit<StorageUsage, 'totalBytes'>; label: string }> = [
  { key: 'conversations', label: 'Conversations' },
  { key: 'messages', label: 'Messages' },
  { key: 'attachments', label: 'Attachments' },
  { key: 'secrets', label: 'Stored keys' },
  { key: 'providers', label: 'Providers' },
  { key: 'models', label: 'Models' },
]

/**
 * Storage usage for `/settings/data`.
 *
 * Attachment sizes come from metadata, so this panel never reads attachment
 * file contents. The numbers are guidance, not an exact browser quota report.
 */
export function DataUsagePanel({ service, refreshKey = 0 }: DataUsagePanelProps) {
  const [usage, setUsage] = useState<StorageUsage | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false

    service.getUsage().then(
      (value) => {
        if (!cancelled) {
          setUsage(value)
          setError(null)
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
  }, [service, refreshKey])

  const loading = usage === null && error === null

  return (
    <div className="settings-screen__panel" aria-labelledby="data-usage-title">
      <h3 id="data-usage-title">Storage usage</h3>
      <p className="settings-screen__muted">
        Sizes are estimated from stored records and attachment metadata. Attachment file contents
        are not read to calculate this.
      </p>

      {loading ? (
        <p role="status">Calculating storage usage…</p>
      ) : error ? (
        <p className="settings-screen__error" role="alert">
          {error}
        </p>
      ) : usage ? (
        <table className="settings-screen__usage">
          <thead>
            <tr>
              <th scope="col">Data</th>
              <th scope="col">Count</th>
              <th scope="col">Size</th>
            </tr>
          </thead>
          <tbody>
            {ROWS.map((row) => (
              <tr key={row.key}>
                <th scope="row">{row.label}</th>
                <td>{usage[row.key].count.toLocaleString()}</td>
                <td>{formatBytes(usage[row.key].bytes)}</td>
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr>
              <th scope="row">Total</th>
              <td />
              <td>{formatBytes(usage.totalBytes)}</td>
            </tr>
          </tfoot>
        </table>
      ) : null}
    </div>
  )
}
