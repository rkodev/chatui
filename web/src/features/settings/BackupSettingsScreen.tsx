import { Link } from '@tanstack/react-router'
import { useEffect, useState } from 'react'
import { DELETE_REMOTE_CONFIRMATION } from '../backup'
import { useBackupStatus } from '../backup/useBackupStatus'
import {
  RESTORE_REPLACE_CONFIRMATION,
  createBackupSettingsService,
  type BackupFormValues,
  type BackupSettingsServiceLike,
} from './backupSettingsService'
import { formatBytes } from './formatBytes'
import './settings.css'

const EMPTY_FORM: BackupFormValues = {
  backend: 's3',
  endpoint: '',
  region: '',
  bucket: '',
  prefix: '',
  accessKeyId: '',
  secretAccessKey: '',
  passphrase: '',
  accountName: '',
  accountKey: '',
  sasToken: '',
  serviceAccountJson: '',
  accessToken: '',
  folderId: '',
  username: '',
  password: '',
}

export interface BackupSettingsScreenProps {
  /** Test or story override. When omitted, IndexedDB is opened lazily. */
  service?: BackupSettingsServiceLike
}

function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause)
}

function formatTimestamp(value: string | null): string {
  if (value === null) {
    return 'Never'
  }

  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString()
}

/**
 * `/settings/backup` configuration, status, and manual actions.
 *
 * Credentials are stored through `SecretStore`; the passphrase is only held in
 * memory by the service. Restore merges by default. Replace restore and remote
 * deletion both require the exact typed confirmation phrase.
 */
export function BackupSettingsScreen({ service }: BackupSettingsScreenProps = {}) {
  const [createdService, setCreatedService] = useState<BackupSettingsServiceLike | null>(null)
  const [loading, setLoading] = useState(service === undefined)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [form, setForm] = useState<BackupFormValues>(EMPTY_FORM)
  const [enabled, setEnabled] = useState(false)
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [statusMessage, setStatusMessage] = useState<string | null>(null)
  const [testResult, setTestResult] = useState<{
    kind: 'success' | 'error'
    message: string
  } | null>(null)
  const [deletePhrase, setDeletePhrase] = useState('')
  const [replacePhrase, setReplacePhrase] = useState('')
  const [refreshKey, setRefreshKey] = useState(0)
  const serviceState = service ?? createdService
  const isLoading = service === undefined && loading
  const { status, error: statusError, refresh } = useBackupStatus(serviceState, {
    refreshKey,
  })

  useEffect(() => {
    if (service !== undefined) {
      return
    }

    let cancelled = false

    createBackupSettingsService().then(
      (created) => {
        if (!cancelled) {
          setCreatedService(created)
          setLoading(false)
        }
      },
      (cause: unknown) => {
        if (!cancelled) {
          setLoadError(errorMessage(cause))
          setLoading(false)
        }
      },
    )

    return () => {
      cancelled = true
    }
  }, [service])

  useEffect(() => {
    if (serviceState === null) {
      return
    }

    let cancelled = false

    serviceState.load().then(
      (snapshot) => {
        if (cancelled) {
          return
        }

        if (snapshot.config !== null) {
          setForm({
            backend: snapshot.config.backend,
            endpoint: snapshot.config.endpoint ?? '',
            region: snapshot.config.region ?? '',
            bucket: snapshot.config.bucket,
            prefix: snapshot.config.prefix,
            accessKeyId: snapshot.credentials?.accessKeyId ?? '',
            secretAccessKey: snapshot.credentials?.secretAccessKey ?? '',
            passphrase: '',
            accountName: snapshot.credentials?.accountName ?? '',
            accountKey: snapshot.credentials?.accountKey ?? '',
            sasToken: snapshot.credentials?.sasToken ?? '',
            serviceAccountJson: snapshot.credentials?.serviceAccountJson ?? '',
            accessToken: snapshot.credentials?.accessToken ?? '',
            folderId:
              snapshot.credentials?.folderId ??
              (snapshot.config.backend === 'google-drive' ? snapshot.config.bucket : ''),
            username: snapshot.credentials?.username ?? '',
            password: snapshot.credentials?.password ?? '',
          })
          setEnabled(snapshot.config.enabled)
        }

        setLoading(false)
      },
      (cause: unknown) => {
        if (!cancelled) {
          setLoadError(errorMessage(cause))
          setLoading(false)
        }
      },
    )

    return () => {
      cancelled = true
    }
  }, [serviceState])

  function updateField<K extends keyof BackupFormValues>(
    key: K,
    value: BackupFormValues[K],
  ) {
    setForm((current) => ({ ...current, [key]: value }))
  }

  async function runAction(
    kind: string,
    action: () => Promise<string | void>,
  ): Promise<void> {
    if (busy !== null) {
      return
    }

    setBusy(kind)
    setError(null)
    setStatusMessage(null)

    try {
      const message = await action()
      if (message !== undefined) {
        setStatusMessage(message)
      }
      setRefreshKey((value) => value + 1)
      await refresh()
    } catch (cause) {
      setError(errorMessage(cause))
    } finally {
      setBusy(null)
    }
  }

  async function handleTestConnection() {
    if (serviceState === null || busy !== null) {
      return
    }

    setBusy('test')
    setError(null)
    setStatusMessage(null)
    setTestResult(null)

    try {
      await serviceState.testConnection(form)
      setTestResult({ kind: 'success', message: 'Connection succeeded.' })
    } catch (cause) {
      setTestResult({ kind: 'error', message: errorMessage(cause) })
    } finally {
      setBusy(null)
    }
  }

  function handleSave() {
    if (serviceState === null) {
      return
    }

    void runAction('save', async () => {
      await serviceState.saveConfig(form, enabled)
      return 'Backup configuration saved.'
    })
  }

  async function handleEnableChange(next: boolean) {
    if (serviceState === null || busy !== null) {
      return
    }

    setBusy('enable')
    setError(null)
    setStatusMessage(null)

    try {
      await serviceState.setEnabled(next, form)
      setEnabled(next)
      setStatusMessage(next ? 'Backup enabled.' : 'Backup disabled.')
      setRefreshKey((value) => value + 1)
      await refresh()
    } catch (cause) {
      setError(errorMessage(cause))
    } finally {
      setBusy(null)
    }
  }

  function handleBackupNow() {
    if (serviceState === null) {
      return
    }

    void runAction('backup', async () => {
      const result = await serviceState.backupNow()
      return `Backup complete: uploaded ${result.uploadedConversations.length} conversation(s) and ${result.uploadedAttachments.length} attachment(s).`
    })
  }

  function handleRestoreMerge() {
    if (serviceState === null) {
      return
    }

    void runAction('restore', async () => {
      const result = await serviceState.restore()
      return `Restore complete: merged ${result.mergedConversations.length} conversation(s).`
    })
  }

  function handleRestoreReplace() {
    if (serviceState === null || replacePhrase !== RESTORE_REPLACE_CONFIRMATION) {
      return
    }

    void runAction('replace', async () => {
      const result = await serviceState.restore({
        replace: true,
        confirmation: replacePhrase,
      })
      setReplacePhrase('')
      return `Replace restore complete: ${result.mergedConversations.length} conversation(s) restored.`
    })
  }

  function handleDeleteRemote() {
    if (serviceState === null || deletePhrase !== DELETE_REMOTE_CONFIRMATION) {
      return
    }

    void runAction('delete', async () => {
      await serviceState.deleteRemote(deletePhrase)
      setDeletePhrase('')
      return 'Remote backup deleted. Local data was not changed.'
    })
  }

  return (
    <section className="settings-screen" aria-labelledby="backup-settings-title">
      <header className="settings-screen__header">
        <h2 id="backup-settings-title">Backup</h2>
      </header>

      <div className="settings-screen__panel">
        <h3>Client-side encryption</h3>
        <p>
          Backups are encrypted on this device before upload. Storage credentials and the
          passphrase stay client-side and are never sent to ChatUI or roksky servers.
        </p>
        <p className="settings-screen__warning" role="note">
          If you lose your passphrase, you lose access to your backup. ChatUI cannot recover it.
        </p>
      </div>

      {isLoading ? (
        <p role="status">Preparing backup settings…</p>
      ) : loadError !== null ? (
        <p className="settings-screen__error" role="alert">
          {loadError}
        </p>
      ) : serviceState === null ? null : (
        <>
          <div className="settings-screen__panel" aria-labelledby="backup-config-title">
            <h3 id="backup-config-title">Storage destination</h3>

            <label className="settings-screen__field" htmlFor="backup-backend">
              <span>Backend</span>
              <select
                id="backup-backend"
                value={form.backend}
                disabled={busy !== null}
                onChange={(event) =>
                  updateField('backend', event.target.value as BackupFormValues['backend'])
                }
              >
                <option value="s3">S3-compatible</option>
                <option value="azure-blob">Azure Blob</option>
                <option value="gcs">Google Cloud Storage</option>
                <option value="google-drive">Google Drive</option>
                <option value="webdav">WebDAV</option>
              </select>
            </label>

            {form.backend === 's3' && (
              <>
                <div className="settings-screen__grid">
                  <label className="settings-screen__field" htmlFor="backup-endpoint">
                    <span>Endpoint</span>
                    <input
                      id="backup-endpoint"
                      type="url"
                      autoComplete="off"
                      spellCheck={false}
                      value={form.endpoint}
                      disabled={busy !== null}
                      onChange={(event) => updateField('endpoint', event.target.value)}
                    />
                  </label>

                  <label className="settings-screen__field" htmlFor="backup-region">
                    <span>Region</span>
                    <input
                      id="backup-region"
                      type="text"
                      autoComplete="off"
                      spellCheck={false}
                      value={form.region}
                      disabled={busy !== null}
                      onChange={(event) => updateField('region', event.target.value)}
                    />
                  </label>

                  <label className="settings-screen__field" htmlFor="backup-bucket">
                    <span>Bucket</span>
                    <input
                      id="backup-bucket"
                      type="text"
                      autoComplete="off"
                      spellCheck={false}
                      value={form.bucket}
                      disabled={busy !== null}
                      onChange={(event) => updateField('bucket', event.target.value)}
                    />
                  </label>

                  <label className="settings-screen__field" htmlFor="backup-prefix">
                    <span>Prefix</span>
                    <input
                      id="backup-prefix"
                      type="text"
                      autoComplete="off"
                      spellCheck={false}
                      value={form.prefix}
                      disabled={busy !== null}
                      onChange={(event) => updateField('prefix', event.target.value)}
                    />
                  </label>
                </div>

                <label className="settings-screen__field" htmlFor="backup-access-key">
                  <span>Access key ID</span>
                  <input
                    id="backup-access-key"
                    type="text"
                    autoComplete="off"
                    spellCheck={false}
                    value={form.accessKeyId}
                    disabled={busy !== null}
                    onChange={(event) => updateField('accessKeyId', event.target.value)}
                  />
                </label>

                <label className="settings-screen__field" htmlFor="backup-secret-key">
                  <span>Secret access key</span>
                  <input
                    id="backup-secret-key"
                    type="password"
                    autoComplete="new-password"
                    value={form.secretAccessKey}
                    disabled={busy !== null}
                    onChange={(event) => updateField('secretAccessKey', event.target.value)}
                  />
                </label>
              </>
            )}

            {form.backend === 'azure-blob' && (
              <>
                <div className="settings-screen__grid">
                  <label className="settings-screen__field" htmlFor="backup-azure-account">
                    <span>Account name</span>
                    <input
                      id="backup-azure-account"
                      type="text"
                      autoComplete="off"
                      spellCheck={false}
                      value={form.accountName ?? ''}
                      disabled={busy !== null}
                      onChange={(event) => updateField('accountName', event.target.value)}
                    />
                  </label>

                  <label className="settings-screen__field" htmlFor="backup-azure-container">
                    <span>Container</span>
                    <input
                      id="backup-azure-container"
                      type="text"
                      autoComplete="off"
                      spellCheck={false}
                      value={form.bucket}
                      disabled={busy !== null}
                      onChange={(event) => updateField('bucket', event.target.value)}
                    />
                  </label>

                  <label className="settings-screen__field" htmlFor="backup-azure-endpoint">
                    <span>Endpoint (optional)</span>
                    <input
                      id="backup-azure-endpoint"
                      type="url"
                      autoComplete="off"
                      spellCheck={false}
                      value={form.endpoint}
                      disabled={busy !== null}
                      onChange={(event) => updateField('endpoint', event.target.value)}
                    />
                  </label>

                  <label className="settings-screen__field" htmlFor="backup-azure-prefix">
                    <span>Prefix</span>
                    <input
                      id="backup-azure-prefix"
                      type="text"
                      autoComplete="off"
                      spellCheck={false}
                      value={form.prefix}
                      disabled={busy !== null}
                      onChange={(event) => updateField('prefix', event.target.value)}
                    />
                  </label>
                </div>

                <label className="settings-screen__field" htmlFor="backup-azure-key">
                  <span>Account key</span>
                  <input
                    id="backup-azure-key"
                    type="password"
                    autoComplete="new-password"
                    value={form.accountKey ?? ''}
                    disabled={busy !== null}
                    onChange={(event) => updateField('accountKey', event.target.value)}
                  />
                </label>

                <label className="settings-screen__field" htmlFor="backup-azure-sas">
                  <span>SAS token</span>
                  <input
                    id="backup-azure-sas"
                    type="password"
                    autoComplete="new-password"
                    value={form.sasToken ?? ''}
                    disabled={busy !== null}
                    onChange={(event) => updateField('sasToken', event.target.value)}
                  />
                </label>
              </>
            )}

            {form.backend === 'gcs' && (
              <>
                <div className="settings-screen__grid">
                  <label className="settings-screen__field" htmlFor="backup-gcs-bucket">
                    <span>Bucket</span>
                    <input
                      id="backup-gcs-bucket"
                      type="text"
                      autoComplete="off"
                      spellCheck={false}
                      value={form.bucket}
                      disabled={busy !== null}
                      onChange={(event) => updateField('bucket', event.target.value)}
                    />
                  </label>

                  <label className="settings-screen__field" htmlFor="backup-gcs-prefix">
                    <span>Prefix</span>
                    <input
                      id="backup-gcs-prefix"
                      type="text"
                      autoComplete="off"
                      spellCheck={false}
                      value={form.prefix}
                      disabled={busy !== null}
                      onChange={(event) => updateField('prefix', event.target.value)}
                    />
                  </label>
                </div>

                <label className="settings-screen__field" htmlFor="backup-gcs-service-account">
                  <span>Service account JSON</span>
                  <textarea
                    id="backup-gcs-service-account"
                    rows={6}
                    autoComplete="off"
                    spellCheck={false}
                    value={form.serviceAccountJson ?? ''}
                    disabled={busy !== null}
                    onChange={(event) =>
                      updateField('serviceAccountJson', event.target.value)
                    }
                  />
                </label>
              </>
            )}

            {form.backend === 'google-drive' && (
              <div className="settings-screen__grid">
                <label className="settings-screen__field" htmlFor="backup-drive-token">
                  <span>Access token</span>
                  <input
                    id="backup-drive-token"
                    type="password"
                    autoComplete="new-password"
                    value={form.accessToken ?? ''}
                    disabled={busy !== null}
                    onChange={(event) => updateField('accessToken', event.target.value)}
                  />
                </label>

                <label className="settings-screen__field" htmlFor="backup-drive-folder">
                  <span>Folder ID</span>
                  <input
                    id="backup-drive-folder"
                    type="text"
                    autoComplete="off"
                    spellCheck={false}
                    placeholder="appDataFolder"
                    value={form.folderId ?? ''}
                    disabled={busy !== null}
                    onChange={(event) => updateField('folderId', event.target.value)}
                  />
                </label>

                <label className="settings-screen__field" htmlFor="backup-drive-prefix">
                  <span>Prefix</span>
                  <input
                    id="backup-drive-prefix"
                    type="text"
                    autoComplete="off"
                    spellCheck={false}
                    value={form.prefix}
                    disabled={busy !== null}
                    onChange={(event) => updateField('prefix', event.target.value)}
                  />
                </label>
              </div>
            )}

            {form.backend === 'webdav' && (
              <div className="settings-screen__grid">
                <label className="settings-screen__field" htmlFor="backup-webdav-url">
                  <span>WebDAV URL</span>
                  <input
                    id="backup-webdav-url"
                    type="url"
                    autoComplete="off"
                    spellCheck={false}
                    value={form.endpoint}
                    disabled={busy !== null}
                    onChange={(event) => updateField('endpoint', event.target.value)}
                  />
                </label>

                <label className="settings-screen__field" htmlFor="backup-webdav-username">
                  <span>Username</span>
                  <input
                    id="backup-webdav-username"
                    type="text"
                    autoComplete="off"
                    spellCheck={false}
                    value={form.username ?? ''}
                    disabled={busy !== null}
                    onChange={(event) => updateField('username', event.target.value)}
                  />
                </label>

                <label className="settings-screen__field" htmlFor="backup-webdav-password">
                  <span>Password</span>
                  <input
                    id="backup-webdav-password"
                    type="password"
                    autoComplete="new-password"
                    value={form.password ?? ''}
                    disabled={busy !== null}
                    onChange={(event) => updateField('password', event.target.value)}
                  />
                </label>

                <label className="settings-screen__field" htmlFor="backup-webdav-prefix">
                  <span>Prefix</span>
                  <input
                    id="backup-webdav-prefix"
                    type="text"
                    autoComplete="off"
                    spellCheck={false}
                    value={form.prefix}
                    disabled={busy !== null}
                    onChange={(event) => updateField('prefix', event.target.value)}
                  />
                </label>
              </div>
            )}

            <label className="settings-screen__field" htmlFor="backup-passphrase">
              <span>Passphrase</span>
              <input
                id="backup-passphrase"
                type="password"
                autoComplete="new-password"
                value={form.passphrase}
                disabled={busy !== null}
                onChange={(event) => updateField('passphrase', event.target.value)}
              />
            </label>
            <p className="settings-screen__muted">
              The passphrase is never stored. Re-enter it after reloading the app.
            </p>

            <div className="settings-screen__actions">
              <button
                type="button"
                className="ui-button ui-button--secondary ui-button--md"
                disabled={busy !== null}
                onClick={() => void handleTestConnection()}
              >
                {busy === 'test' ? 'Testing…' : 'Test connection'}
              </button>
              <button
                type="button"
                className="ui-button ui-button--primary ui-button--md"
                disabled={busy !== null}
                onClick={handleSave}
              >
                {busy === 'save' ? 'Saving…' : 'Save configuration'}
              </button>
            </div>

            {testResult !== null && (
              <p
                className={
                  testResult.kind === 'success'
                    ? 'settings-screen__muted'
                    : 'settings-screen__error'
                }
                role={testResult.kind === 'success' ? 'status' : 'alert'}
              >
                {testResult.message}
              </p>
            )}

            <label className="settings-screen__checkbox">
              <input
                type="checkbox"
                checked={enabled}
                disabled={busy !== null}
                onChange={(event) => void handleEnableChange(event.target.checked)}
              />
              <span>Enable backup</span>
            </label>
          </div>

          <div className="settings-screen__panel" aria-labelledby="backup-status-title">
            <h3 id="backup-status-title">Status</h3>
            {statusError !== null && (
              <p className="settings-screen__error" role="alert">
                {statusError}
              </p>
            )}
            {status === null ? (
              <p role="status">Loading status…</p>
            ) : (
              <ul className="settings-screen__list">
                <li>
                  <strong>Last backup:</strong> {formatTimestamp(status.lastBackupAt)}
                </li>
                <li>
                  <strong>Pending changes:</strong> {status.pendingChanges}
                </li>
                <li>
                  <strong>Last error:</strong> {status.lastError ?? 'None'}
                </li>
                <li>
                  <strong>Storage usage (approximate):</strong>{' '}
                  {formatBytes(status.storageUsageBytes)}
                </li>
              </ul>
            )}
          </div>

          <div className="settings-screen__panel" aria-labelledby="backup-actions-title">
            <h3 id="backup-actions-title">Actions</h3>

            <div className="settings-screen__actions">
              <button
                type="button"
                className="ui-button ui-button--primary ui-button--md"
                disabled={busy !== null || !enabled}
                onClick={handleBackupNow}
              >
                {busy === 'backup' ? 'Backing up…' : 'Back up now'}
              </button>
              <button
                type="button"
                className="ui-button ui-button--secondary ui-button--md"
                disabled={busy !== null}
                onClick={handleRestoreMerge}
              >
                {busy === 'restore' ? 'Restoring…' : 'Restore from backup (merge)'}
              </button>
            </div>
            <p className="settings-screen__muted">
              Restore merges remote data by default and never deletes local conversations.
            </p>

            <label className="settings-screen__field" htmlFor="backup-replace-confirmation">
              <span>Type {RESTORE_REPLACE_CONFIRMATION} to confirm replace</span>
              <input
                id="backup-replace-confirmation"
                type="text"
                autoComplete="off"
                spellCheck={false}
                value={replacePhrase}
                disabled={busy !== null}
                onChange={(event) => setReplacePhrase(event.target.value)}
              />
            </label>
            <div className="settings-screen__actions">
              <button
                type="button"
                className="ui-button ui-button--danger ui-button--md"
                disabled={busy !== null || replacePhrase !== RESTORE_REPLACE_CONFIRMATION}
                onClick={handleRestoreReplace}
              >
                {busy === 'replace' ? 'Replacing…' : 'Replace local data'}
              </button>
            </div>

            <label className="settings-screen__field" htmlFor="backup-delete-confirmation">
              <span>Type {DELETE_REMOTE_CONFIRMATION} to confirm delete</span>
              <input
                id="backup-delete-confirmation"
                type="text"
                autoComplete="off"
                spellCheck={false}
                value={deletePhrase}
                disabled={busy !== null}
                onChange={(event) => setDeletePhrase(event.target.value)}
              />
            </label>
            <div className="settings-screen__actions">
              <button
                type="button"
                className="ui-button ui-button--danger ui-button--md"
                disabled={busy !== null || deletePhrase !== DELETE_REMOTE_CONFIRMATION}
                onClick={handleDeleteRemote}
              >
                {busy === 'delete' ? 'Deleting…' : 'Delete remote backup'}
              </button>
            </div>
            <p className="settings-screen__muted">
              Deleting the remote backup does not delete local conversations.
            </p>
          </div>

          {error !== null && (
            <p className="settings-screen__error" role="alert">
              {error}
            </p>
          )}

          {statusMessage !== null && (
            <p className="settings-screen__muted" role="status">
              {statusMessage}
            </p>
          )}
        </>
      )}

      <div className="settings-screen__panel">
        <h3>Related</h3>
        <p>
          <Link to="/settings/data">Data settings</Link>
        </p>
      </div>
    </section>
  )
}
