import { createMemoryHistory, RouterProvider } from '@tanstack/react-router'
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ConfigContext, type AppConfig } from './config'
import { OnboardingServiceProvider, type OnboardingServiceLike } from '../features/onboarding'
import { ProviderManagementProvider } from '../features/providers'
import { createFakeProviderManagementService } from '../features/providers/testSupport'
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

function renderRoute(path: string) {
  const router = createAppRouter(createMemoryHistory({ initialEntries: [path] }))
  render(
    <ConfigContext.Provider value={testConfig}>
      <ProviderManagementProvider service={createFakeProviderManagementService()}>
        <OnboardingServiceProvider service={onboardingService}>
          <RouterProvider router={router} />
        </OnboardingServiceProvider>
      </ProviderManagementProvider>
    </ConfigContext.Provider>,
  )
  return router
}

describe('app router', () => {
  it.each([
    ['/', 'Welcome to ChatUI'],
    ['/chat/conversation-1', 'Chat placeholder'],
    ['/settings/appearance', 'Appearance settings placeholder'],
    ['/settings/providers', 'Providers'],
    ['/settings/providers/new', 'Welcome to ChatUI'],
    ['/settings/providers/provider-1', 'OpenRouter'],
    ['/settings/models', 'Models'],
    ['/settings/data', 'Data settings placeholder'],
    ['/settings/backup', 'Backup settings placeholder'],
    ['/settings/about', 'About settings placeholder'],
  ])('resolves %s', async (path, heading) => {
    renderRoute(path)

    expect(await screen.findByRole('heading', { name: heading })).toBeInTheDocument()
  })

  it('redirects /settings to /settings/appearance', async () => {
    const router = renderRoute('/settings')

    expect(
      await screen.findByRole('heading', { name: 'Appearance settings placeholder' }),
    ).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/settings/appearance')
  })

  it('renders a visible fallback for unknown routes', async () => {
    renderRoute('/does-not-exist')

    expect(await screen.findByRole('heading', { name: 'Page not found' })).toBeInTheDocument()
  })
})
