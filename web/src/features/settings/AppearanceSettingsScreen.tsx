import type { ChatFontSize, Density, Theme } from '../../domain'
import { useSettings } from './settingsContext'
import './settings.css'

const THEME_OPTIONS: Array<{ value: Theme; label: string }> = [
  { value: 'system', label: 'System' },
  { value: 'light', label: 'Light' },
  { value: 'dark', label: 'Dark' },
]

const DENSITY_OPTIONS: Array<{ value: Density; label: string }> = [
  { value: 'comfortable', label: 'Comfortable' },
  { value: 'compact', label: 'Compact' },
]

const FONT_SIZE_OPTIONS: Array<{ value: ChatFontSize; label: string }> = [
  { value: 'sm', label: 'Small' },
  { value: 'md', label: 'Medium' },
  { value: 'lg', label: 'Large' },
]

/**
 * `/settings/appearance` theme, density, and chat font-size controls.
 *
 * Every choice persists immediately through the typed settings facade. Reduced
 * motion follows the operating-system preference via CSS media query.
 */
export function AppearanceSettingsScreen() {
  const { settings, error, loading, update } = useSettings()

  if (settings === null) {
    return (
      <section
        className="settings-screen"
        aria-labelledby="appearance-settings-title"
        aria-busy={loading}
      >
        <header className="settings-screen__header">
          <h2 id="appearance-settings-title">Appearance</h2>
        </header>
        {error ? (
          <p className="settings-screen__error" role="alert">
            {error}
          </p>
        ) : (
          <p role="status">Loading appearance settings…</p>
        )}
      </section>
    )
  }

  return (
    <section className="settings-screen" aria-labelledby="appearance-settings-title">
      <header className="settings-screen__header">
        <h2 id="appearance-settings-title">Appearance</h2>
      </header>

      {error && (
        <p className="settings-screen__error" role="alert">
          {error}
        </p>
      )}

      <div className="settings-screen__panel">
        <label className="settings-screen__field" htmlFor="appearance-theme">
          <span>Theme</span>
          <select
            id="appearance-theme"
            value={settings.theme}
            onChange={(event) => void update({ theme: event.target.value as Theme })}
          >
            {THEME_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </label>

        <label className="settings-screen__field" htmlFor="appearance-density">
          <span>Density</span>
          <select
            id="appearance-density"
            value={settings.density}
            onChange={(event) => void update({ density: event.target.value as Density })}
          >
            {DENSITY_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </label>

        <label className="settings-screen__field" htmlFor="appearance-chat-font-size">
          <span>Chat font size</span>
          <select
            id="appearance-chat-font-size"
            value={settings.chatFontSize}
            onChange={(event) =>
              void update({ chatFontSize: event.target.value as ChatFontSize })
            }
          >
            {FONT_SIZE_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </label>

        <p className="settings-screen__muted">
          Reduced motion follows your operating-system preference. ChatUI disables transitions
          and animations when the system requests reduced motion.
        </p>
      </div>
    </section>
  )
}
