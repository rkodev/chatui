import { RouterProvider } from '@tanstack/react-router'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ConfigContext } from '../../app/config'
import { ProviderDetailScreen } from './ProviderDetailScreen'
import { ProviderManagementProvider } from './ProviderManagementProvider'
import {
  createFakeProviderManagementService,
  createTestProvider,
  testConfig,
} from './testSupport'
import { createTestRouter } from './testRouter'

function renderDetail(service = createFakeProviderManagementService()) {
  const router = createTestRouter(
    ProviderDetailScreen,
    'settings/providers/$providerId',
    '/settings/providers/provider-1',
    ['settings/providers', 'settings/models'],
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

describe('ProviderDetailScreen', () => {
  it('shows key metadata, CORS status, and the preset docs link without the key', async () => {
    renderDetail()

    expect(await screen.findByRole('heading', { name: 'OpenRouter' })).toBeInTheDocument()
    expect(screen.getByText('Work key')).toBeInTheDocument()
    expect(screen.getByText('$1.25')).toBeInTheDocument()
    expect(screen.getByText('$10')).toBeInTheDocument()
    expect(screen.getByText('No')).toBeInTheDocument()
    expect(screen.getByText('60 requests per minute')).toBeInTheDocument()
    expect(screen.getByText('Unknown')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'OpenRouter documentation' })).toHaveAttribute(
      'href',
      'https://openrouter.ai/docs',
    )
    expect(screen.queryByText('secret-1')).not.toBeInTheDocument()
  })

  it('rotates and validates a key', async () => {
    const user = userEvent.setup()
    const provider = createTestProvider()
    const rotateKey = vi.fn(async () => provider)
    const validateProviderKey = vi.fn(async () => provider)
    renderDetail(createFakeProviderManagementService({ rotateKey, validateProviderKey }))

    await screen.findByRole('heading', { name: 'OpenRouter' })
    await user.type(screen.getByLabelText('New API key'), 'sk-new-key')
    await user.click(screen.getByRole('button', { name: 'Rotate key' }))

    await waitFor(() =>
      expect(rotateKey).toHaveBeenCalledWith('provider-1', 'sk-new-key', true),
    )
    expect(validateProviderKey).toHaveBeenCalledWith('provider-1')
    expect(await screen.findByText('Key rotated and validated.')).toBeInTheDocument()
  })

  it('removes a key after confirmation', async () => {
    const user = userEvent.setup()
    const removeKey = vi.fn(async () => ({ ...createTestProvider(), secretId: null }))
    renderDetail(createFakeProviderManagementService({ removeKey }))

    await screen.findByRole('heading', { name: 'OpenRouter' })
    await user.click(screen.getByRole('button', { name: 'Remove key' }))
    expect(removeKey).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: 'Confirm removal' }))
    await waitFor(() => expect(removeKey).toHaveBeenCalledWith('provider-1'))
    expect(await screen.findByText(/Key removed/)).toBeInTheDocument()
  })

  it('toggles the provider and refreshes models', async () => {
    const user = userEvent.setup()
    const setProviderEnabled = vi.fn(async (_id: string, enabled: boolean) => ({
      ...createTestProvider(),
      enabled,
    }))
    const refreshModels = vi.fn(async () => ({
      models: [],
      created: 0,
      updated: 0,
    }))
    renderDetail(createFakeProviderManagementService({ setProviderEnabled, refreshModels }))

    await screen.findByRole('heading', { name: 'OpenRouter' })
    await user.click(screen.getByRole('checkbox', { name: 'Enable this provider' }))
    await waitFor(() =>
      expect(setProviderEnabled).toHaveBeenCalledWith('provider-1', false),
    )

    await user.click(screen.getByRole('button', { name: 'Refresh models' }))
    await waitFor(() => expect(refreshModels).toHaveBeenCalledWith('provider-1'))
  })
})
