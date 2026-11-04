import { useCallback, useEffect, useState } from 'react'
import type { Conversation } from '../../domain'
import { downloadTextFile } from './download'
import {
  EXPORT_PLAINTEXT_WARNING,
  type ExportArtifact,
  type ExportServiceLike,
} from './exportService'
import './settings.css'

export interface DataExportPanelProps {
  service: ExportServiceLike
  /** Test override. Defaults to the browser download helper. */
  download?: (artifact: ExportArtifact) => void
}

function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause)
}

/**
 * Export controls for `/settings/data`.
 *
 * Both full-archive and per-conversation exports are explicit user actions.
 * The plaintext warning stays visible before any button is pressed, and
 * attachment bytes are excluded unless the user opts in.
 */
export function DataExportPanel({ service, download = downloadTextFile }: DataExportPanelProps) {
  const [conversations, setConversations] = useState<Conversation[] | null>(null)
  const [selectedId, setSelectedId] = useState('')
  const [includeAttachmentBytes, setIncludeAttachmentBytes] = useState(false)
  const [busy, setBusy] = useState<'archive' | 'json' | 'markdown' | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [status, setStatus] = useState<string | null>(null)

  const load = useCallback(async () => {
    const items = await service.listConversations()
    setConversations(items)
    setSelectedId((current) =>
      current !== '' && items.some((conversation) => conversation.id === current)
        ? current
        : (items[0]?.id ?? ''),
    )
  }, [service])

  useEffect(() => {
    let cancelled = false

    service.listConversations().then(
      (items) => {
        if (!cancelled) {
          setConversations(items)
          setSelectedId(items[0]?.id ?? '')
        }
      },
      (cause: unknown) => {
        if (!cancelled) {
          setError(errorMessage(cause))
        }
      },
    )

    return () => {
      cancelled = true
    }
  }, [service])

  async function run(kind: 'archive' | 'json' | 'markdown', action: () => Promise<ExportArtifact>) {
    setBusy(kind)
    setError(null)
    setStatus(null)

    try {
      const artifact = await action()
      download(artifact)
      setStatus(`Downloaded ${artifact.filename}.`)
      await load()
    } catch (cause) {
      setError(errorMessage(cause))
    } finally {
      setBusy(null)
    }
  }

  function handleExportAll() {
    void run('archive', () => service.exportAll({ includeAttachmentBytes }))
  }

  function handleExportConversation(format: 'json' | 'markdown') {
    if (selectedId === '') {
      return
    }

    void run(format, () =>
      service.exportConversation(selectedId, {
        format,
        includeAttachmentBytes: format === 'json' ? includeAttachmentBytes : false,
      }),
    )
  }

  return (
    <div className="settings-screen__panel" aria-labelledby="data-export-title">
      <h3 id="data-export-title">Export</h3>
      <p className="settings-screen__warning" role="note">
        {EXPORT_PLAINTEXT_WARNING}
      </p>

      <label className="settings-screen__checkbox">
        <input
          type="checkbox"
          checked={includeAttachmentBytes}
          onChange={(event) => setIncludeAttachmentBytes(event.target.checked)}
        />
        <span>Include attachment file contents (base64) in JSON exports</span>
      </label>

      <div className="settings-screen__actions">
        <button
          type="button"
          className="ui-button ui-button--primary ui-button--md"
          onClick={handleExportAll}
          disabled={busy !== null}
        >
          {busy === 'archive' ? 'Preparing archive…' : 'Export all data (JSON)'}
        </button>
      </div>

      {error && (
        <p className="settings-screen__error" role="alert">
          {error}
        </p>
      )}

      {status && (
        <p className="settings-screen__muted" role="status">
          {status}
        </p>
      )}

      <h3>Conversation export</h3>
      {conversations === null ? (
        <p role="status">Loading conversations…</p>
      ) : conversations.length === 0 ? (
        <p className="settings-screen__muted">No conversations are stored yet.</p>
      ) : (
        <>
          <label className="settings-screen__field" htmlFor="export-conversation">
            <span>Conversation</span>
            <select
              id="export-conversation"
              value={selectedId}
              onChange={(event) => setSelectedId(event.target.value)}
            >
              {conversations.map((conversation) => (
                <option key={conversation.id} value={conversation.id}>
                  {conversation.title}
                </option>
              ))}
            </select>
          </label>
          <div className="settings-screen__actions">
            <button
              type="button"
              className="ui-button ui-button--secondary ui-button--md"
              onClick={() => handleExportConversation('json')}
              disabled={busy !== null}
            >
              {busy === 'json' ? 'Preparing JSON…' : 'Export conversation (JSON)'}
            </button>
            <button
              type="button"
              className="ui-button ui-button--secondary ui-button--md"
              onClick={() => handleExportConversation('markdown')}
              disabled={busy !== null}
            >
              {busy === 'markdown' ? 'Preparing Markdown…' : 'Export conversation (Markdown)'}
            </button>
          </div>
        </>
      )}
    </div>
  )
}
