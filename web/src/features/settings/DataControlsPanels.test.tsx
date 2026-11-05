import { RouterProvider } from '@tanstack/react-router'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { createTestRouter } from '../providers/testRouter'
import { BackupSettingsScreen } from './BackupSettingsScreen'
import { DataClearPanel } from './DataClearPanel'
import { DataKeysPanel } from './DataKeysPanel'
import { DataSettingsScreen } from './DataSettingsScreen'
import { DataUsagePanel } from './DataUsagePanel'
import { formatBytes } from './formatBytes'
import type { DataControlsServiceLike, StorageUsage } from './dataControlsService'
import type { ExportServiceLike } from './exportService'
import type { ImportServiceLike } from './importService'

const usage: StorageUsage = {
  conversations: { count: 2, bytes: 1024 },
  messages: { count: 4, bytes: 2048 },
  attachments: { count: 1, bytes: 1536 },
  secrets: { count: 1, bytes: 64 },
  providers: { count: 1, bytes: 256 },
  models: { count: 3, bytes: 512 },
  totalBytes: 5440,
}

function createDataControls(
  overrides: Partial<DataControlsServiceLike> = {},
): DataControlsServiceLike {
  return {
    getUsage: async () => usage,
    clearAll: async () => ({
      conversations: 2,
      messages: 4,
      attachments: 1,
      secrets: 1,
      providers: 1,
      models: 3,
    }),
    listProviderKeys: async () => [
      {
        id: 'provider-1',
        label: 'OpenAI',
        presetId: 'openai',
        hasKey: true,
        keyLabel: 'Work key',
      },
    ],
    removeProviderKey: async () => undefined,
    ...overrides,
  }
}

function createExportService(): ExportServiceLike {
  return {
    listConversations: async () => [],
    exportAll: async () => ({ filename: 'all.json', mimeType: 'application/json', content: '{}' }),
    exportConversation: async () => ({
      filename: 'conversation.json',
      mimeType: 'application/json',
      content: '{}',
    }),
  }
}

function createImportService(): ImportServiceLike {
  const result = {
    mode: 'merge' as const,
    conversations: 0,
    messages: 0,
    attachments: 0,
    providers: 0,
    models: 0,
    attachmentBytes: 0,
  }

  return {
    importFile: async () => result,
    importText: async () => result,
  }
}

describe('formatBytes', () => {
  it('formats byte sizes for storage guidance', () => {
    expect(formatBytes(0)).toBe('0 B')
    expect(formatBytes(512)).toBe('512 B')
    expect(formatBytes(1536)).toBe('1.5 KB')
    expect(formatBytes(5 * 1024 * 1024)).toBe('5.0 MB')
  })
})

describe('DataUsagePanel', () => {
  it('renders counts and sizes without reading attachment bytes', async () => {
    const getUsage = vi.fn(async () => usage)

    render(<DataUsagePanel service={{ getUsage }} />)

    expect(await screen.findByText('Conversations')).toBeInTheDocument()
    expect(screen.getByText('Attachments')).toBeInTheDocument()
    expect(screen.getByText('1.5 KB')).toBeInTheDocument()
    expect(screen.getByText('5.3 KB')).toBeInTheDocument()
    expect(getUsage).toHaveBeenCalledTimes(1)
  })
})

describe('DataClearPanel', () => {
  it('keeps clear-all disabled until the confirmation phrase matches', async () => {
    const user = userEvent.setup()
    const clearAll = vi.fn(async () => ({
      conversations: 1,
      messages: 2,
      attachments: 3,
      secrets: 1,
      providers: 1,
      models: 1,
    }))
    const onCleared = vi.fn()

    render(<DataClearPanel service={{ clearAll }} onCleared={onCleared} />)

    const button = screen.getByRole('button', { name: 'Clear all data' })
    expect(button).toBeDisabled()

    await user.type(screen.getByLabelText('Type DELETE ALL DATA to confirm'), 'delete all data')
    expect(button).toBeDisabled()

    await user.clear(screen.getByLabelText('Type DELETE ALL DATA to confirm'))
    await user.type(screen.getByLabelText('Type DELETE ALL DATA to confirm'), 'DELETE ALL DATA')
    expect(button).toBeEnabled()

    await user.click(button)

    await waitFor(() => expect(clearAll).toHaveBeenCalledTimes(1))
    expect(onCleared).toHaveBeenCalledTimes(1)
    expect(
      await screen.findByText(
        'Removed 1 conversation(s), 2 message(s), 3 attachment(s), and 1 stored key(s).',
      ),
    ).toBeInTheDocument()
  })

  it('shows the irreversible warning before confirmation', () => {
    render(<DataClearPanel service={{ clearAll: async () => ({
      conversations: 0,
      messages: 0,
      attachments: 0,
      secrets: 0,
      providers: 0,
      models: 0,
    }) }} />)

    expect(screen.getByRole('note')).toHaveTextContent('This cannot be undone.')
  })
})

describe('DataKeysPanel', () => {
  function renderKeys(service: DataControlsServiceLike, onChanged = vi.fn()) {
    function KeysRoute() {
      return <DataKeysPanel service={service} onChanged={onChanged} />
    }

    const router = createTestRouter(KeysRoute, 'settings/data', '/settings/data', [
      'settings/providers',
    ])
    render(<RouterProvider router={router} />)
    return onChanged
  }

  it('requires a second click before removing a key', async () => {
    const user = userEvent.setup()
    const removeProviderKey = vi.fn(async () => undefined)
    const service = createDataControls({ removeProviderKey })
    const onChanged = renderKeys(service)

    expect(await screen.findByText('OpenAI')).toBeInTheDocument()
    expect(screen.getByText('openai · Work key')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Remove key' }))
    expect(removeProviderKey).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: 'Confirm removal' }))

    await waitFor(() => expect(removeProviderKey).toHaveBeenCalledWith('provider-1'))
    expect(onChanged).toHaveBeenCalledTimes(1)
    expect(await screen.findByText('No API keys are stored on this device.')).toBeInTheDocument()
  })

  it('shows an empty state when no keys are stored', async () => {
    renderKeys(createDataControls({ listProviderKeys: async () => [] }))

    expect(await screen.findByText('No API keys are stored on this device.')).toBeInTheDocument()
  })
})

describe('DataSettingsScreen', () => {
  it('renders usage, key deletion, clear-all, export, import, and backup entry points', async () => {
    function DataRoute() {
      return (
        <DataSettingsScreen
          exportService={createExportService()}
          importService={createImportService()}
          dataControlsService={createDataControls()}
        />
      )
    }

    const router = createTestRouter(DataRoute, 'settings/data', '/settings/data', [
      'settings/providers',
      'settings/backup',
      'settings/about',
    ])
    render(<RouterProvider router={router} />)

    expect(await screen.findByRole('heading', { name: 'Storage usage' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Stored API keys' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Clear all data' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Export' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Import' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Manage providers and keys' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Backup' })).toBeInTheDocument()
  })
})

describe('BackupSettingsScreen', () => {
  it('remains a reachable placeholder with no configuration controls', async () => {
    const router = createTestRouter(BackupSettingsScreen, 'settings/backup', '/settings/backup', [
      'settings/data',
    ])
    render(<RouterProvider router={router} />)

    expect(await screen.findByRole('heading', { name: 'Backup' })).toBeInTheDocument()
    expect(screen.getByText('Not configured')).toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Data settings' })).toBeInTheDocument()
  })
})
