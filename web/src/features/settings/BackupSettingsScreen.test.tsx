import { RouterProvider } from '@tanstack/react-router'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { BackupConfig } from '../../domain'
import { DELETE_REMOTE_CONFIRMATION } from '../backup'
import { createTestRouter } from '../providers/testRouter'
import { BackupSettingsScreen } from './BackupSettingsScreen'
import {
  BACKUP_CREDENTIALS_SECRET_ID,
  RESTORE_REPLACE_CONFIRMATION,
  type BackupSettingsServiceLike,
} from './backupSettingsService'

const NOW = '2026-11-11T12:00:00.000Z'

function backupConfig(overrides: Partial<BackupConfig> = {}): BackupConfig {
  return {
    enabled: true,
    backend: 's3',
    endpoint: 'https://s3.us-east-1.amazonaws.com',
    region: 'us-east-1',
    bucket: 'chatui-backups',
    prefix: 'backups',
    credentialSecretId: BACKUP_CREDENTIALS_SECRET_ID,
    encryption: 'aes-256-gcm',
    kdf: 'pbkdf2-sha256',
    kdfSalt: null,
    lastSyncAt: NOW,
    lastManifestEtag: null,
    deviceId: 'device-1',
    ...overrides,
  }
}

function createFakeService(overrides: Partial<BackupSettingsServiceLike> = {}) {
  const load = vi.fn(async () => ({
    config: backupConfig(),
    credentials: { accessKeyId: 'AKIAEXAMPLE', secretAccessKey: 'secret-example' },
  }))
  const saveConfig = vi.fn(async () => backupConfig())
  const setEnabled = vi.fn(async (enabled: boolean) => backupConfig({ enabled }))
  const testConnection = vi.fn(async () => undefined)
  const backupNow = vi.fn(async () => ({
    uploadedConversations: ['conv-1'],
    uploadedAttachments: [] as string[],
    downloadedConversations: [] as string[],
    manifestEtag: null,
    drainedQueue: 0,
  }))
  const restore = vi.fn(async () => ({
    mergedConversations: ['conv-1'],
    replaced: false,
  }))
  const deleteRemote = vi.fn(async () => undefined)
  const getStatus = vi.fn(async () => ({
    enabled: true,
    lastBackupAt: NOW,
    pendingChanges: 2,
    lastError: null,
    storageUsageBytes: 2048,
  }))

  const service: BackupSettingsServiceLike = {
    load,
    saveConfig,
    setEnabled,
    testConnection,
    backupNow,
    restore,
    deleteRemote,
    getStatus,
    ...overrides,
  }

  return {
    service,
    load,
    saveConfig,
    setEnabled,
    testConnection,
    backupNow,
    restore,
    deleteRemote,
    getStatus,
  }
}

function renderScreen(service: BackupSettingsServiceLike) {
  function BackupRoute() {
    return <BackupSettingsScreen service={service} />
  }

  const router = createTestRouter(BackupRoute, 'settings/backup', '/settings/backup', [
    'settings/data',
  ])
  render(<RouterProvider router={router} />)
}

describe('BackupSettingsScreen', () => {
  it('loads saved configuration and saves edits', async () => {
    const user = userEvent.setup()
    const { service, saveConfig } = createFakeService()

    renderScreen(service)

    expect(await screen.findByLabelText('Bucket')).toHaveValue('chatui-backups')
    expect(screen.getByLabelText('Access key ID')).toHaveValue('AKIAEXAMPLE')
    expect(screen.getByLabelText('Passphrase')).toHaveValue('')

    await user.clear(screen.getByLabelText('Bucket'))
    await user.type(screen.getByLabelText('Bucket'), 'new-bucket')
    await user.type(screen.getByLabelText('Passphrase'), 'passphrase')
    await user.click(screen.getByRole('button', { name: 'Save configuration' }))

    await waitFor(() =>
      expect(saveConfig).toHaveBeenCalledWith(
        expect.objectContaining({ bucket: 'new-bucket', passphrase: 'passphrase' }),
        true,
      ),
    )
    expect(await screen.findByText('Backup configuration saved.')).toBeInTheDocument()
  })

  it('shows typed connectivity success and failure', async () => {
    const user = userEvent.setup()
    const { service, testConnection } = createFakeService()

    renderScreen(service)
    await screen.findByLabelText('Endpoint')

    await user.click(screen.getByRole('button', { name: 'Test connection' }))
    expect(await screen.findByText('Connection succeeded.')).toBeInTheDocument()

    testConnection.mockRejectedValueOnce(new Error('HEAD bucket failed with HTTP 401'))
    await user.click(screen.getByRole('button', { name: 'Test connection' }))
    expect(
      await screen.findByText('HEAD bucket failed with HTTP 401'),
    ).toBeInTheDocument()
  })

  it('enables and disables backup', async () => {
    const user = userEvent.setup()
    const { service, setEnabled } = createFakeService()

    renderScreen(service)

    const toggle = await screen.findByLabelText('Enable backup')
    expect(toggle).toBeChecked()

    await user.click(toggle)

    await waitFor(() =>
      expect(setEnabled).toHaveBeenCalledWith(false, expect.any(Object)),
    )
    expect(await screen.findByText('Backup disabled.')).toBeInTheDocument()
    expect(toggle).not.toBeChecked()
  })

  it('backs up now through the service', async () => {
    const user = userEvent.setup()
    const { service, backupNow } = createFakeService()

    renderScreen(service)

    await user.click(await screen.findByRole('button', { name: 'Back up now' }))

    await waitFor(() => expect(backupNow).toHaveBeenCalledTimes(1))
    expect(await screen.findByText(/Backup complete/)).toBeInTheDocument()
  })

  it('restores with merge by default and requires typed confirmation for replace', async () => {
    const user = userEvent.setup()
    const { service, restore } = createFakeService()

    renderScreen(service)

    await user.click(
      await screen.findByRole('button', { name: 'Restore from backup (merge)' }),
    )
    await waitFor(() => expect(restore).toHaveBeenCalledWith())

    const replaceButton = screen.getByRole('button', { name: 'Replace local data' })
    expect(replaceButton).toBeDisabled()

    await user.type(
      screen.getByLabelText(`Type ${RESTORE_REPLACE_CONFIRMATION} to confirm replace`),
      RESTORE_REPLACE_CONFIRMATION,
    )
    expect(replaceButton).toBeEnabled()

    await user.click(replaceButton)
    await waitFor(() =>
      expect(restore).toHaveBeenCalledWith({
        replace: true,
        confirmation: RESTORE_REPLACE_CONFIRMATION,
      }),
    )
  })

  it('requires typed confirmation to delete the remote backup', async () => {
    const user = userEvent.setup()
    const { service, deleteRemote } = createFakeService()

    renderScreen(service)

    const deleteButton = await screen.findByRole('button', {
      name: 'Delete remote backup',
    })
    expect(deleteButton).toBeDisabled()

    await user.type(
      screen.getByLabelText(`Type ${DELETE_REMOTE_CONFIRMATION} to confirm delete`),
      DELETE_REMOTE_CONFIRMATION,
    )
    expect(deleteButton).toBeEnabled()

    await user.click(deleteButton)
    await waitFor(() =>
      expect(deleteRemote).toHaveBeenCalledWith(DELETE_REMOTE_CONFIRMATION),
    )
    expect(await screen.findByText(/Local data was not changed/)).toBeInTheDocument()
  })

  it('renders last backup, pending changes, errors, and storage usage', async () => {
    const { service } = createFakeService({
      getStatus: vi.fn(async () => ({
        enabled: true,
        lastBackupAt: NOW,
        pendingChanges: 3,
        lastError: 'network down',
        storageUsageBytes: 2048,
      })),
    })

    renderScreen(service)

    expect(await screen.findByText(/Last backup:/)).toBeInTheDocument()
    expect(screen.getByText(/Pending changes:/).closest('li')).toHaveTextContent('3')
    expect(screen.getByText(/network down/)).toBeInTheDocument()
    expect(screen.getByText(/2.0 KB/)).toBeInTheDocument()
  })

  it('states that credentials and passphrase stay client-side and warns about passphrase loss', async () => {
    const { service } = createFakeService()

    renderScreen(service)

    expect(
      await screen.findByText(/credentials and the passphrase stay client-side/i),
    ).toBeInTheDocument()
    expect(
      screen.getByText(/If you lose your passphrase, you lose access/i),
    ).toBeInTheDocument()
  })

  it('enables every backend and shows backend-specific fields', async () => {
    const user = userEvent.setup()
    const { service } = createFakeService()

    renderScreen(service)
    await screen.findByLabelText('Bucket')

    const options = screen.getAllByRole('option') as HTMLOptionElement[]
    expect(options.map((option) => option.value)).toEqual([
      's3',
      'azure-blob',
      'gcs',
      'google-drive',
      'webdav',
    ])
    expect(options.every((option) => !option.disabled)).toBe(true)

    await user.selectOptions(screen.getByLabelText('Backend'), 'azure-blob')
    expect(screen.getByLabelText('Account name')).toBeInTheDocument()
    expect(screen.getByLabelText('Container')).toBeInTheDocument()
    expect(screen.getByLabelText('Account key')).toBeInTheDocument()
    expect(screen.getByLabelText('SAS token')).toBeInTheDocument()

    await user.selectOptions(screen.getByLabelText('Backend'), 'gcs')
    expect(screen.getByLabelText('Service account JSON')).toBeInTheDocument()

    await user.selectOptions(screen.getByLabelText('Backend'), 'google-drive')
    expect(screen.getByLabelText('Access token')).toBeInTheDocument()
    expect(screen.getByLabelText('Folder ID')).toBeInTheDocument()

    await user.selectOptions(screen.getByLabelText('Backend'), 'webdav')
    expect(screen.getByLabelText('WebDAV URL')).toBeInTheDocument()
    expect(screen.getByLabelText('Username')).toBeInTheDocument()
    expect(screen.getByLabelText('Password')).toBeInTheDocument()
  })
})
