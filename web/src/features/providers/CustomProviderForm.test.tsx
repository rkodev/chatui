import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ConfigContext } from '../../app/config'
import { ProviderServiceError } from './types'
import { CustomProviderForm } from './CustomProviderForm'
import { ProviderManagementProvider } from './ProviderManagementProvider'
import {
  createFakeProviderManagementService,
  createTestProvider,
  testConfig,
} from './testSupport'

function renderForm(service = createFakeProviderManagementService()) {
  render(
    <ConfigContext.Provider value={testConfig}>
      <ProviderManagementProvider service={service}>
        <CustomProviderForm />
      </ProviderManagementProvider>
    </ConfigContext.Provider>,
  )
  return service
}

describe('CustomProviderForm', () => {
  it('submits a valid custom provider with non-secret headers', async () => {
    const user = userEvent.setup()
    const createCustomProvider = vi.fn(async () => ({
      ...createTestProvider(),
      id: 'custom-provider',
      presetId: 'custom',
      label: 'Local',
    }))
    renderForm(createFakeProviderManagementService({ createCustomProvider }))

    await user.type(screen.getByLabelText('Label (optional)'), 'Local')
    await user.type(screen.getByLabelText('Base URL'), 'https://api.example.com/v1')
    await user.type(screen.getByLabelText('API key'), 'sk-custom')
    await user.type(
      screen.getByLabelText('Non-secret headers (optional)'),
      'X-Organization: team',
    )
    await user.click(screen.getByRole('button', { name: 'Add custom provider' }))

    expect(createCustomProvider).toHaveBeenCalledWith({
      label: 'Local',
      baseUrl: 'https://api.example.com/v1',
      authType: 'bearer',
      apiKey: 'sk-custom',
      rememberKeys: true,
      headers: { 'X-Organization': 'team' },
    })
    expect(await screen.findByText('Added Local.')).toBeInTheDocument()
  })

  it('shows actionable service validation copy', async () => {
    const user = userEvent.setup()
    const createCustomProvider = vi.fn(async () => {
      throw new ProviderServiceError(
        'invalid_input',
        'Enter a valid http(s) base URL, for example https://api.example.com/v1.',
      )
    })
    renderForm(createFakeProviderManagementService({ createCustomProvider }))

    await user.type(screen.getByLabelText('Base URL'), 'not-a-url')
    await user.type(screen.getByLabelText('API key'), 'sk-custom')
    await user.click(screen.getByRole('button', { name: 'Add custom provider' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Enter a valid http(s) base URL',
    )
  })

  it('rejects malformed header lines before calling the service', async () => {
    const user = userEvent.setup()
    const createCustomProvider = vi.fn(async () => createTestProvider())
    renderForm(createFakeProviderManagementService({ createCustomProvider }))

    await user.type(screen.getByLabelText('Base URL'), 'https://api.example.com/v1')
    await user.type(screen.getByLabelText('API key'), 'sk-custom')
    await user.type(screen.getByLabelText('Non-secret headers (optional)'), 'not-a-header')
    await user.click(screen.getByRole('button', { name: 'Add custom provider' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Header lines must use "Name: value"',
    )
    expect(createCustomProvider).not.toHaveBeenCalled()
  })

  it('hides the key field when no authentication is selected', async () => {
    const user = userEvent.setup()
    renderForm()

    await user.selectOptions(screen.getByLabelText('Authentication'), 'none')

    expect(screen.queryByLabelText('API key')).not.toBeInTheDocument()
  })
})
