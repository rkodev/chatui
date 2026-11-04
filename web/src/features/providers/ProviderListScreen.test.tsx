import { RouterProvider } from '@tanstack/react-router'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ConfigContext } from '../../app/config'
import { ProviderListScreen } from './ProviderListScreen'
import { ProviderManagementProvider } from './ProviderManagementProvider'
import {
  createFakeProviderManagementService,
  createTestProvider,
  testConfig,
} from './testSupport'
import { createTestRouter } from './testRouter'

function renderList(service = createFakeProviderManagementService()) {
  const router = createTestRouter(
    ProviderListScreen,
    'settings/providers',
    '/settings/providers',
    ['settings/providers/$providerId'],
  )

  render(
    <ConfigContext.Provider value={testConfig}>
      <ProviderManagementProvider service={service}>
        <RouterProvider router={router} />
      </ProviderManagementProvider>
    </ConfigContext.Provider>,
  )
  return service
}

describe('ProviderListScreen', () => {
  it('lists configured providers and labels other presets', async () => {
    renderList()

    expect(await screen.findByRole('heading', { name: 'Providers' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /OpenRouter/ })).toHaveAttribute(
      'href',
      '/settings/providers/provider-1',
    )
    expect(screen.getByRole('heading', { name: 'Other providers' })).toBeInTheDocument()
    expect(screen.getByText('OpenAI')).toBeInTheDocument()
    expect(screen.getAllByText('Available').length).toBeGreaterThan(0)
    expect(screen.getByText('Anthropic')).toBeInTheDocument()
    expect(screen.getByText('Ollama')).toBeInTheDocument()
    expect(screen.queryByText('Coming later')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Add a custom provider' })).toBeInTheDocument()
  })

  it('lists a configured Custom provider with its preset label', async () => {
    const custom = createTestProvider({
      id: 'custom-1',
      presetId: 'custom',
      label: 'Local',
      baseUrl: 'http://localhost:1234/v1',
    })
    renderList(createFakeProviderManagementService({ listProviders: async () => [custom] }))

    expect(await screen.findByRole('link', { name: /Local/ })).toHaveAttribute(
      'href',
      '/settings/providers/custom-1',
    )
    expect(screen.getByText(/Custom OpenAI-compatible/)).toBeInTheDocument()
  })

  it('shows an empty state when no provider exists', async () => {
    renderList(createFakeProviderManagementService({ listProviders: async () => [] }))

    expect(await screen.findByText(/No providers yet/)).toBeInTheDocument()
  })

  it('creates a custom provider from the form', async () => {
    const user = userEvent.setup()
    const createCustomProvider = vi.fn(async () => ({
      ...createTestProvider(),
      id: 'custom-provider',
      presetId: 'custom',
      label: 'Local',
    }))
    renderList(createFakeProviderManagementService({ createCustomProvider }))

    await screen.findByRole('heading', { name: 'Add a custom provider' })
    await user.type(screen.getByLabelText('Label (optional)'), 'Local')
    await user.type(screen.getByLabelText('Base URL'), 'https://api.example.com/v1')
    await user.type(screen.getByLabelText('API key'), 'sk-custom')
    await user.click(screen.getByRole('button', { name: 'Add custom provider' }))

    expect(createCustomProvider).toHaveBeenCalledWith({
      label: 'Local',
      baseUrl: 'https://api.example.com/v1',
      authType: 'bearer',
      apiKey: 'sk-custom',
      rememberKeys: true,
      headers: {},
    })
  })
})
