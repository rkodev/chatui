import { createElement, StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import { AppProviders } from './providers'

export function bootstrap(): void {
  const container = document.getElementById('root')

  if (!container) {
    throw new Error('Root element #root not found')
  }

  createRoot(container).render(
    createElement(StrictMode, null, createElement(AppProviders, null, createElement(App))),
  )
}
