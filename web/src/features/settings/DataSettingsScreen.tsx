import { Link } from '@tanstack/react-router'
import './settings.css'

/**
 * `/settings/data` entry point reserved for M2-04.5.
 *
 * This task deliberately ships navigation and scope copy only. Storage usage,
 * export/import, and clear-data controls are implemented by M2-04.
 */
export function DataSettingsScreen() {
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
          Storage usage, export, import, and clear-data controls arrive in M2-04. This screen is
          the entry point and does not move or delete data yet.
        </p>
      </div>

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
