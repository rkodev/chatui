import { useConfig } from '../../app/config'
import './settings.css'

/**
 * `/settings/about` version, license, and privacy information.
 *
 * The copy restates the web threat model and data-handling rules that affect
 * users: local storage is not encrypted at rest, keys are device-bound, remote
 * images are blocked by default, and provider requests go directly from the
 * browser.
 */
export function AboutSettingsScreen() {
  const config = useConfig()

  return (
    <section className="settings-screen" aria-labelledby="about-settings-title">
      <header className="settings-screen__header">
        <h2 id="about-settings-title">About</h2>
      </header>

      <div className="settings-screen__panel">
        <h3>{config.appName}</h3>
        <p className="settings-screen__muted">Version: development build</p>
        <p>
          {config.appName} is a local-first chat client. Conversations and settings live in this
          browser profile, and chat requests go directly from this browser to the provider you
          select.
        </p>
        <p>
          Documentation:{' '}
          <a href={config.docsUrl} target="_blank" rel="noopener noreferrer">
            {config.docsUrl}
          </a>
        </p>
      </div>

      <div className="settings-screen__panel">
        <h3>Privacy</h3>
        <ul className="settings-screen__list">
          <li>Local conversation storage is not encrypted at rest in this phase.</li>
          <li>
            Provider keys are kept in memory or encrypted with a non-extractable device key. They
            are never placed in URLs, logs, or exports.
          </li>
          <li>
            Chat content is sent only to the selected provider. When OpenRouter is selected, it
            forwards requests to an upstream provider under its own data policy.
          </li>
          <li>
            Remote images in model output are blocked until you allow them globally or load one
            message image explicitly.
          </li>
          <li>No analytics or telemetry scripts are loaded by ChatUI itself.</li>
        </ul>
      </div>

      <div className="settings-screen__panel">
        <h3>Licenses</h3>
        <p className="settings-screen__muted">
          Third-party package notices are distributed with the application build. ChatUI does not
          inject third-party scripts at runtime.
        </p>
      </div>
    </section>
  )
}
