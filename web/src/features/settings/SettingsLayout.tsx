import { Link, Outlet } from '@tanstack/react-router'
import './settings.css'

type SettingsPath =
  | '/settings/appearance'
  | '/settings/chat'
  | '/settings/providers'
  | '/settings/models'
  | '/settings/data'
  | '/settings/backup'
  | '/settings/about'

const SETTINGS_LINKS: Array<{ to: SettingsPath; label: string }> = [
  { to: '/settings/appearance', label: 'Appearance' },
  { to: '/settings/chat', label: 'Chat defaults' },
  { to: '/settings/providers', label: 'Providers' },
  { to: '/settings/models', label: 'Models' },
  { to: '/settings/data', label: 'Data' },
  { to: '/settings/backup', label: 'Backup' },
  { to: '/settings/about', label: 'About' },
]

/**
 * Settings shell with section navigation.
 *
 * The nav is rendered once for every `/settings/*` route so sections remain
 * reachable without returning to the history sidebar.
 */
export function SettingsLayout() {
  return (
    <div className="settings-layout">
      <nav className="settings-nav" aria-label="Settings sections">
        <ul className="settings-nav__list">
          {SETTINGS_LINKS.map((link) => (
            <li key={link.to}>
              <Link
                to={link.to}
                className="settings-nav__link"
                activeProps={{ className: 'settings-nav__link settings-nav__link--active' }}
              >
                {link.label}
              </Link>
            </li>
          ))}
        </ul>
      </nav>
      <div className="settings-layout__content">
        <Outlet />
      </div>
    </div>
  )
}
