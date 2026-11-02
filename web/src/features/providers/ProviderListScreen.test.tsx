import { RouterProvider } from '@tanstack/react-router'
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ConfigContext } from '../../app/config'
import { ProviderListScreen } from './ProviderListScreen'
import { ProviderManagementProvider } from './ProviderManagementProvider'
import { createFakeProviderManagementService, testConfig } from './testSupport'
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
  it('lists configured providers and keeps other providers unavailable', async () => {
    renderList()

    expect(await screen.findByRole('heading', { name: 'Providers' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /OpenRouter/ })).toHaveAttribute(
      'href',
      '/settings/providers/provider-1',
    )
    expect(screen.getByRole('heading', { name: 'Other providers' })).toBeInTheDocument()
    expect(screen.getByText('OpenAI')).toBeInTheDocument()
    expect(screen.getAllByText('Coming later').length).toBeGreaterThan(0)
    expect(screen.getByText(/Custom providers are not available/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /custom/i })).not.toBeInTheDocument()
  })

  it('shows an empty state when no provider exists', async () => {
    renderList(createFakeProviderManagementService({ listProviders: async () => [] }))

    expect(await screen.findByText(/No providers yet/)).toBeInTheDocument()
  })
})
