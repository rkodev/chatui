import { RouterProvider } from '@tanstack/react-router'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { Settings } from '../../domain'
import { ConfigContext } from '../../app/config'
import { ThemeProvider } from '../../ui/theme'
import { ProviderManagementProvider } from '../providers'
import { createTestRouter } from '../providers/testRouter'
import { createFakeProviderManagementService, testConfig } from '../providers/testSupport'
import { AppearanceSettingsScreen } from './AppearanceSettingsScreen'
import { ChatSettingsScreen } from './ChatSettingsScreen'
import { DEFAULT_SETTINGS, type SettingsClient } from './settingsService'
import { SettingsProvider } from './SettingsProvider'

function createMemorySettings(overrides: Partial<Settings> = {}) {
  let settings: Settings = { ...structuredClone(DEFAULT_SETTINGS), ...overrides }
  const update = vi.fn(async (patch: Partial<Settings>) => {
    settings = { ...settings, ...patch }
    return settings
  })
  const client: SettingsClient = {
    get: async () => settings,
    update,
  }

  return { client, update }
}

function renderAppearance(client: SettingsClient) {
  render(
    <ThemeProvider>
      <SettingsProvider service={client}>
        <AppearanceSettingsScreen />
      </SettingsProvider>
    </ThemeProvider>,
  )
}

describe('appearance settings', () => {
  it('loads persisted values and applies them to the document', async () => {
    const { client } = createMemorySettings({ theme: 'dark', density: 'compact', chatFontSize: 'lg' })

    renderAppearance(client)

    expect(await screen.findByLabelText('Theme')).toHaveValue('dark')
    expect(screen.getByRole('heading', { name: 'Appearance' })).toBeInTheDocument()
    expect(screen.getByLabelText('Density')).toHaveValue('compact')
    expect(screen.getByLabelText('Chat font size')).toHaveValue('lg')

    await waitFor(() => expect(document.documentElement.dataset.theme).toBe('dark'))
    expect(document.documentElement.dataset.density).toBe('compact')
    expect(document.documentElement.dataset.chatFontSize).toBe('lg')
  })

  it('persists theme changes immediately', async () => {
    const user = userEvent.setup()
    const { client, update } = createMemorySettings()

    renderAppearance(client)
    await screen.findByRole('heading', { name: 'Appearance' })

    await user.selectOptions(screen.getByLabelText('Theme'), 'light')

    await waitFor(() => expect(update).toHaveBeenCalledWith({ theme: 'light' }))
    await waitFor(() => expect(document.documentElement.dataset.theme).toBe('light'))
  })
})

describe('chat default settings', () => {
  function renderChat(client: SettingsClient, management = createFakeProviderManagementService()) {
    const router = createTestRouter(
      ChatSettingsScreen,
      'settings/chat',
      '/settings/chat',
      ['settings/models', 'settings/providers/new'],
    )

    render(
      <ConfigContext.Provider value={testConfig}>
        <SettingsProvider service={client}>
          <ProviderManagementProvider service={management}>
            <RouterProvider router={router} />
          </ProviderManagementProvider>
        </SettingsProvider>
      </ConfigContext.Provider>,
    )
    return management
  }

  it('persists send key, token usage, remote images, and remember-keys toggles', async () => {
    const user = userEvent.setup()
    const { client, update } = createMemorySettings()

    renderChat(client)

    expect(await screen.findByLabelText('Send key')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Chat defaults' })).toBeInTheDocument()

    await user.selectOptions(screen.getByLabelText('Send key'), 'mod-enter')
    await user.click(screen.getByLabelText('Show token usage on assistant messages'))
    await user.click(screen.getByLabelText('Load remote images in Markdown responses'))
    await user.click(screen.getByLabelText('Remember keys on this device by default'))

    expect(update).toHaveBeenCalledWith({ sendKey: 'mod-enter' })
    expect(update).toHaveBeenCalledWith({ showTokenUsage: false })
    expect(update).toHaveBeenCalledWith({ loadRemoteImages: true })
    expect(update).toHaveBeenCalledWith({ rememberKeysOnWeb: false })
  })

  it('persists default prompt and parameters through provider management', async () => {
    const setDefaults = vi.fn(async () => ({
      ...DEFAULT_SETTINGS,
      defaultSystemPrompt: 'Be concise.',
    }))
    const management = createFakeProviderManagementService({ setDefaults })
    const { client } = createMemorySettings()

    renderChat(client, management)
    const prompt = await screen.findByLabelText('System prompt')

    fireEvent.change(prompt, { target: { value: 'Be concise.' } })

    await waitFor(() =>
      expect(setDefaults).toHaveBeenCalledWith(expect.objectContaining({ systemPrompt: 'Be concise.' })),
    )
  })
})
