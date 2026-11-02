import { createElement, StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import { ConfigContext, ConfigError, loadConfig } from './config'
import { AppProviders } from './providers'

function ConfigErrorScreen({ error }: { error: ConfigError }) {
  return createElement(
    'main',
    { role: 'alert', style: { fontFamily: 'system-ui, sans-serif', padding: '2rem' } },
    createElement('h1', null, 'Configuration error'),
    createElement(
      'p',
      null,
      'ChatUI could not start because its runtime configuration is invalid.',
    ),
    createElement('pre', { style: { whiteSpace: 'pre-wrap' } }, error.message),
  )
}

export async function bootstrap(): Promise<void> {
  const container = document.getElementById('root')

  if (!container) {
    throw new Error('Root element #root not found')
  }

  const root = createRoot(container)

  try {
    const config = await loadConfig()

    root.render(
      createElement(
        StrictMode,
        null,
        createElement(
          ConfigContext.Provider,
          { value: config },
          createElement(AppProviders, null, createElement(App)),
        ),
      ),
    )
  } catch (error) {
    const configError =
      error instanceof ConfigError
        ? error
        : new ConfigError('Unexpected error while loading configuration.', { cause: error })

    root.render(
      createElement(StrictMode, null, createElement(ConfigErrorScreen, { error: configError })),
    )
  }
}
