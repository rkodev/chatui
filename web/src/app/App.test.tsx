import { createMemoryHistory } from '@tanstack/react-router'
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { App } from './App'
import { ConfigContext, type AppConfig } from './config'
import { OnboardingServiceProvider, type OnboardingServiceLike } from '../features/onboarding'
import { createAppRouter } from './router'

const testConfig: AppConfig = {
  appName: 'ChatUI',
  defaultTheme: 'system',
  defaultProviderPreset: 'openrouter',
  enabledPresets: ['openrouter'],
  allowCustomProviders: false,
  openRouter: {
    attributionOrigin: 'https://chat.example.com',
    appTitle: 'ChatUI',
  },
  docsUrl: 'https://github.com/rkodev/chatui',
  telemetry: false,
}

const onboardingService: OnboardingServiceLike = {
  loadCatalog: async () => [],
  connect: async () => ({ conversationId: 'conversation-1', notice: null }),
  isOnboardingComplete: async () => false,
}

describe('App', () => {
  it('renders the routed shell', async () => {
    const router = createAppRouter(createMemoryHistory({ initialEntries: ['/'] }))

    render(
      <ConfigContext.Provider value={testConfig}>
        <OnboardingServiceProvider service={onboardingService}>
          <App router={router} />
        </OnboardingServiceProvider>
      </ConfigContext.Provider>,
    )

    expect(await screen.findByRole('heading', { name: 'ChatUI' })).toBeInTheDocument()
    expect(
      await screen.findByRole('heading', { name: 'Welcome to ChatUI' }),
    ).toBeInTheDocument()
  })
})
