import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { Conversation } from '../../domain'
import { DataExportPanel } from './DataExportPanel'
import {
  EXPORT_PLAINTEXT_WARNING,
  type ExportArtifact,
  type ExportServiceLike,
} from './exportService'

function conversation(id: string, title: string): Conversation {
  return {
    id,
    title,
    systemPrompt: '',
    providerId: 'provider-1',
    modelId: 'model-1',
    modelLabel: 'Model',
    params: {
      temperature: null,
      topP: null,
      maxOutputTokens: null,
      stop: [],
      reasoningEffort: null,
      streaming: true,
      extra: {},
    },
    pinned: false,
    archived: false,
    temporary: false,
    createdAt: '2026-01-01T00:00:00.000Z',
    updatedAt: '2026-01-01T00:00:00.000Z',
    lastMessageAt: '2026-01-01T00:00:00.000Z',
    messageCount: 0,
    lastError: null,
    sync: null,
  }
}

function artifact(name: string): ExportArtifact {
  return { filename: name, mimeType: 'application/json', content: '{}' }
}

function createService() {
  const items = [conversation('conv-1', 'First chat'), conversation('conv-2', 'Second chat')]
  const exportAll = vi.fn(async () => artifact('all.json'))
  const exportConversation = vi.fn(async (id: string, options: { format?: string } = {}) =>
    artifact(`${id}.${options.format === 'markdown' ? 'md' : 'json'}`),
  )
  const service: ExportServiceLike = {
    listConversations: async () => items,
    exportAll,
    exportConversation,
  }
  return { service, exportAll, exportConversation }
}

describe('DataExportPanel', () => {
  it('shows the plaintext warning and exports all data without bytes by default', async () => {
    const user = userEvent.setup()
    const { service, exportAll } = createService()
    const download = vi.fn()

    render(<DataExportPanel service={service} download={download} />)

    expect(screen.getByRole('note')).toHaveTextContent(EXPORT_PLAINTEXT_WARNING)
    await user.click(screen.getByRole('button', { name: 'Export all data (JSON)' }))

    await waitFor(() => expect(exportAll).toHaveBeenCalledWith({ includeAttachmentBytes: false }))
    expect(download).toHaveBeenCalledWith(expect.objectContaining({ filename: 'all.json' }))
    expect(await screen.findByText('Downloaded all.json.')).toBeInTheDocument()
  })

  it('passes the attachment byte opt-in to JSON exports', async () => {
    const user = userEvent.setup()
    const { service, exportAll } = createService()

    render(<DataExportPanel service={service} download={vi.fn()} />)
    await user.click(
      screen.getByLabelText('Include attachment file contents (base64) in JSON exports'),
    )
    await user.click(screen.getByRole('button', { name: 'Export all data (JSON)' }))

    await waitFor(() => expect(exportAll).toHaveBeenCalledWith({ includeAttachmentBytes: true }))
  })

  it('exports the selected conversation as JSON and Markdown', async () => {
    const user = userEvent.setup()
    const { service, exportConversation } = createService()
    const download = vi.fn()

    render(<DataExportPanel service={service} download={download} />)
    await screen.findByLabelText('Conversation')
    await user.selectOptions(screen.getByLabelText('Conversation'), 'conv-2')

    await user.click(screen.getByRole('button', { name: 'Export conversation (JSON)' }))
    await waitFor(() =>
      expect(exportConversation).toHaveBeenCalledWith('conv-2', {
        format: 'json',
        includeAttachmentBytes: false,
      }),
    )

    await user.click(screen.getByRole('button', { name: 'Export conversation (Markdown)' }))
    await waitFor(() =>
      expect(exportConversation).toHaveBeenCalledWith('conv-2', {
        format: 'markdown',
        includeAttachmentBytes: false,
      }),
    )
  })

  it('shows an empty state when no conversations exist', async () => {
    const service: ExportServiceLike = {
      listConversations: async () => [],
      exportAll: async () => artifact('all.json'),
      exportConversation: vi.fn(),
    }

    render(<DataExportPanel service={service} download={vi.fn()} />)

    expect(await screen.findByText('No conversations are stored yet.')).toBeInTheDocument()
  })
})
