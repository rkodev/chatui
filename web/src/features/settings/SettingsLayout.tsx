import { Link, Outlet } from '@tanstack/react-router'
import { Icon, type IconName } from '../../ui/Icon'
import './settings.css'

type SettingsPath =
  | '/settings/appearance'
  | '/settings/chat'
  | '/settings/providers'
  | '/settings/models'
  | '/settings/data'
  | '/settings/backup'
  | '/settings/about'

const SETTINGS_LINKS: Array<{ to: SettingsPath; label: string; icon: IconName }> = [
  { to: '/settings/appearance', label: 'Appearance', icon: 'sun' },
  { to: '/settings/chat', label: 'Chat defaults', icon: 'sliders' },
  { to: '/settings/providers', label: 'Providers', icon: 'plug' },
  { to: '/settings/models', label: 'Models', icon: 'layers' },
  { to: '/settings/data', label: 'Data', icon: 'database' },
  { to: '/settings/backup', label: 'Backup', icon: 'cloud' },
  { to: '/settings/about', label: 'About', icon: 'info' },
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
        <p className="settings-nav__title">Settings</p>
        <ul className="settings-nav__list">
          {SETTINGS_LINKS.map((link) => (
            <li key={link.to}>
              <Link
                to={link.to}
                className="settings-nav__link"
                activeProps={{ className: 'settings-nav__link settings-nav__link--active' }}
              >
                <Icon name={link.icon} size={17} />
                <span>{link.label}</span>
              </Link>
            </li>
          ))}
        </ul>
        <p className="settings-nav__hint">A little more you.</p>
      </nav>
      <div className="settings-layout__content">
        <Outlet />
      </div>
    </div>
  )
}
