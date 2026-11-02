import { RouterProvider } from '@tanstack/react-router'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ConfigContext } from '../../app/config'
import { ProviderManagementProvider } from '../providers'
import {
  createFakeProviderManagementService,
  createTestModel,
  createTestProvider,
  testConfig,
} from '../providers/testSupport'
import { ModelsSettingsScreen } from './ModelsSettingsScreen'
import { createTestRouter } from '../providers/testRouter'

const provider = createTestProvider()
const paid = createTestModel()
const free = createTestModel({
  id: 'free-model',
  remoteId: 'author/free:free',
  label: 'Free model',
  pricing: { inputPerMTok: 0, outputPerMTok: 0 },
})
const hidden = createTestModel({
  id: 'hidden-model',
  remoteId: 'author/hidden',
  label: 'Hidden model',
  hidden: true,
})

function renderSettings(service = createFakeProviderManagementService()) {
  const router = createTestRouter(
    ModelsSettingsScreen,
    'settings/models',
    '/settings/models',
    ['settings/providers', 'settings/providers/new'],
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

describe('ModelsSettingsScreen', () => {
  it('shows the global default provider and model', async () => {
    renderSettings(
      createFakeProviderManagementService({
        listModels: async () => [paid, free, hidden],
      }),
    )

    expect(await screen.findByRole('heading', { name: 'Models' })).toBeInTheDocument()
    expect(screen.getByText('OpenRouter')).toBeInTheDocument()
    expect(screen.getByText('OpenAI: GPT-4o mini')).toBeInTheDocument()
    expect(screen.getByText('openai/gpt-4o-mini')).toBeInTheDocument()
  })

  it('shows an unavailable default warning', async () => {
    renderSettings(
      createFakeProviderManagementService({
        getDefaultModelWarning: async () =>
          'Default model missing is unavailable. Choose another model before sending.',
      }),
    )

    expect(await screen.findByText(/Default model missing is unavailable/)).toBeInTheDocument()
  })

  it('persists a new global default model', async () => {
    const user = userEvent.setup()
    const setDefaults = vi.fn(async () => ({
      ...(await createFakeProviderManagementService().setDefaults({})),
    }))
    renderSettings(
      createFakeProviderManagementService({
        listModels: async () => [paid, free, hidden],
        setDefaults,
      }),
    )

    await screen.findByRole('heading', { name: 'Models' })
    await user.click(screen.getByRole('button', { name: 'Choose default model' }))
    await user.click(screen.getByRole('button', { name: /Free model/ }))

    await waitFor(() =>
      expect(setDefaults).toHaveBeenCalledWith({
        providerId: provider.id,
        modelId: free.id,
      }),
    )
  })

  it('opens management mode with hidden models and refreshes the catalog', async () => {
    const user = userEvent.setup()
    const refreshModels = vi.fn(async () => ({
      models: [paid, free, hidden],
      created: 0,
      updated: 3,
    }))
    renderSettings(
      createFakeProviderManagementService({
        listModels: async () => [paid, free, hidden],
        refreshModels,
      }),
    )

    await screen.findByRole('heading', { name: 'Models' })
    await user.click(screen.getByRole('button', { name: 'Manage models' }))

    expect(screen.getByRole('heading', { name: 'Manage models' })).toBeInTheDocument()
    expect(screen.getByText('Hidden model')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Refresh models' }))
    await waitFor(() => expect(refreshModels).toHaveBeenCalledWith(provider.id))
  })
})
