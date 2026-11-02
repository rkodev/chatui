import { createMemoryHistory, RouterProvider } from '@tanstack/react-router'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { ConfigContext, type AppConfig } from '../../app/config'
import { createAppRouter } from '../../app/router'
import { OnboardingServiceProvider } from './OnboardingServiceProvider'
import type { OnboardingServiceLike } from './types'

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

function createService(overrides: Partial<OnboardingServiceLike> = {}): OnboardingServiceLike {
  return {
    loadCatalog: async () => [],
    connect: async () => ({ conversationId: 'conversation-1', notice: null }),
    isOnboardingComplete: async () => false,
    ...overrides,
  }
}

function renderRoute(path: string, service: OnboardingServiceLike) {
  const router = createAppRouter(createMemoryHistory({ initialEntries: [path] }))

  render(
    <ConfigContext.Provider value={testConfig}>
      <OnboardingServiceProvider service={service}>
        <RouterProvider router={router} />
      </OnboardingServiceProvider>
    </ConfigContext.Provider>,
  )

  return router
}

describe('onboarding routes', () => {
  it('navigates to the new chat after a successful connection', async () => {
    const user = userEvent.setup()
    const router = renderRoute('/settings/providers/new', createService())

    await user.type(await screen.findByLabelText('OpenRouter API key'), 'sk-test-key')
    await user.click(screen.getByRole('button', { name: 'Connect' }))

    expect(
      await screen.findByRole('heading', { name: 'Chat placeholder' }),
    ).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/chat/conversation-1')
  })

  it('shows the no-conversation placeholder when onboarding is complete', async () => {
    renderRoute('/', createService({ isOnboardingComplete: async () => true }))

    expect(
      await screen.findByRole('heading', { name: 'No conversation selected' }),
    ).toBeInTheDocument()
  })
})
