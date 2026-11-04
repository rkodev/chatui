import { Link } from '@tanstack/react-router'
import './settings.css'

/**
 * `/settings/backup` placeholder for the post-M2 backup feature.
 *
 * It renders a real, reachable screen with no configuration controls and no
 * data movement.
 */
export function BackupSettingsScreen() {
  return (
    <section className="settings-screen" aria-labelledby="backup-settings-title">
      <header className="settings-screen__header">
        <h2 id="backup-settings-title">Backup</h2>
      </header>

      <div className="settings-screen__panel">
        <h3>Not configured</h3>
        <p>
          ChatUI does not back up or sync data in this release. Everything stays on this device
          until you export it in a future release.
        </p>
        <p className="settings-screen__muted">
          When backup arrives it will encrypt data before upload, keep provider keys out of backup
          metadata, and use a user-owned destination. No backup credentials are stored today.
        </p>
        <p>
          <Link to="/settings/data">Data settings</Link>
        </p>
      </div>
    </section>
  )
}
