import { createMemoryHistory } from '@tanstack/react-router'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { App } from '../app/App'
import { ConfigContext, type AppConfig } from '../app/config'
import { AppProviders } from '../app/providers'
import { createAppRouter } from '../app/router'
import { useToastStore } from '../app/stores'
import {
  OnboardingServiceProvider,
  type OnboardingServiceLike,
} from '../features/onboarding'
import { ProviderManagementProvider } from '../features/providers'
import { createFakeProviderManagementService } from '../features/providers/testSupport'
import { useToast } from '../ui/primitives'
import { useTheme } from '../ui/theme'

const validConfig: AppConfig = {
  appName: 'ChatUI',
  defaultTheme: 'system',
  defaultProviderPreset: 'openrouter',
  enabledPresets: ['openrouter', 'openai', 'custom'],
  allowCustomProviders: true,
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

function renderShell(path = '/') {
  const router = createAppRouter(createMemoryHistory({ initialEntries: [path] }))

  return render(
    <ConfigContext.Provider value={validConfig}>
      <AppProviders>
        <ProviderManagementProvider service={createFakeProviderManagementService()}>
          <OnboardingServiceProvider service={onboardingService}>
            <App router={router} />
          </OnboardingServiceProvider>
        </ProviderManagementProvider>
      </AppProviders>
    </ConfigContext.Provider>,
  )
}

function ThemeToggle() {
  const { theme, setTheme } = useTheme()

  return (
    <button type="button" onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')}>
      Toggle theme
    </button>
  )
}

function ToastTrigger() {
  const { addToast } = useToast()

  return (
    <button type="button" onClick={() => addToast({ message: 'Shell toast' })}>
      Show toast
    </button>
  )
}

beforeEach(() => {
  useToastStore.setState({ toasts: [] })
  document.documentElement.removeAttribute('data-theme')
})

describe('web shell smoke tests', () => {
  it('renders the themed shell with valid config', async () => {
    renderShell('/')

    expect(await screen.findByRole('heading', { name: 'ChatUI' })).toBeInTheDocument()
    expect(
      await screen.findByRole('heading', { name: 'Welcome to ChatUI' }),
    ).toBeInTheDocument()
    expect(document.documentElement.dataset.theme).toBe('system')
  })

  it('switches the theme attribute through the shell provider', async () => {
    const user = userEvent.setup()

    render(
      <ConfigContext.Provider value={{ ...validConfig, defaultTheme: 'light' }}>
        <AppProviders>
          <ThemeToggle />
        </AppProviders>
      </ConfigContext.Provider>,
    )

    expect(document.documentElement.dataset.theme).toBe('light')

    await user.click(screen.getByRole('button', { name: 'Toggle theme' }))

    expect(document.documentElement.dataset.theme).toBe('dark')
  })

  it('renders toasts added through the shell store', async () => {
    const user = userEvent.setup()

    render(
      <ConfigContext.Provider value={validConfig}>
        <AppProviders>
          <ToastTrigger />
        </AppProviders>
      </ConfigContext.Provider>,
    )

    await user.click(screen.getByRole('button', { name: 'Show toast' }))

    expect(await screen.findByText('Shell toast')).toBeInTheDocument()
  })

  it.each([
    ['/', 'Welcome to ChatUI'],
    ['/chat/conversation-1', 'Conversation not found'],
    ['/settings/appearance', 'Appearance settings placeholder'],
    ['/settings/providers', 'Providers'],
    ['/settings/providers/new', 'Welcome to ChatUI'],
    ['/settings/providers/provider-1', 'OpenRouter'],
    ['/settings/models', 'Models'],
    ['/settings/data', 'Data settings placeholder'],
    ['/settings/backup', 'Backup settings placeholder'],
    ['/settings/about', 'About settings placeholder'],
  ])('renders the %s route', async (path, heading) => {
    renderShell(path)

    expect(await screen.findByRole('heading', { name: heading })).toBeInTheDocument()
  })
})
