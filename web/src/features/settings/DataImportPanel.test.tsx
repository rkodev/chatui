import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { DataImportPanel, IMPORT_REPLACE_CONFIRMATION } from './DataImportPanel'
import type { ImportResult, ImportServiceLike } from './importService'

function result(overrides: Partial<ImportResult> = {}): ImportResult {
  return {
    mode: 'merge',
    conversations: 1,
    messages: 2,
    attachments: 1,
    providers: 1,
    models: 1,
    attachmentBytes: 1,
    ...overrides,
  }
}

function createService(importFile = vi.fn(async () => result())): ImportServiceLike {
  return {
    importFile,
    importText: vi.fn(),
  }
}

function archiveFile(name = 'export.json'): File {
  return new File(['{}'], name, { type: 'application/json' })
}

describe('DataImportPanel', () => {
  it('imports in merge mode by default', async () => {
    const user = userEvent.setup()
    const importFile = vi.fn(async () => result())
    const service = createService(importFile)

    render(<DataImportPanel service={service} confirmReplace={vi.fn()} />)

    expect(screen.getByLabelText(/Merge \(default\)/)).toBeChecked()
    await user.upload(screen.getByLabelText('Archive file'), archiveFile())

    await waitFor(() =>
      expect(importFile).toHaveBeenCalledWith(expect.any(File), { mode: 'merge' }),
    )
    expect(
      await screen.findByText('Imported 1 conversation(s), 2 message(s), and 1 attachment(s).'),
    ).toBeInTheDocument()
  })

  it('requires confirmation before replace mode and cancels cleanly', async () => {
    const user = userEvent.setup()
    const importFile = vi.fn(async () => result({ mode: 'replace' }))
    const confirmReplace = vi.fn(() => false)
    const service = createService(importFile)

    render(<DataImportPanel service={service} confirmReplace={confirmReplace} />)

    await user.click(screen.getByLabelText(/Replace/))
    expect(screen.getByRole('alert')).toHaveTextContent('Replace mode deletes local data')

    await user.upload(screen.getByLabelText('Archive file'), archiveFile())

    await waitFor(() => expect(confirmReplace).toHaveBeenCalledWith(IMPORT_REPLACE_CONFIRMATION))
    expect(importFile).not.toHaveBeenCalled()
    expect(await screen.findByText('Import cancelled.')).toBeInTheDocument()
  })

  it('imports in replace mode after confirmation', async () => {
    const user = userEvent.setup()
    const importFile = vi.fn(async () => result({ mode: 'replace' }))
    const confirmReplace = vi.fn(() => true)
    const service = createService(importFile)

    render(<DataImportPanel service={service} confirmReplace={confirmReplace} />)

    await user.click(screen.getByLabelText(/Replace/))
    await user.upload(screen.getByLabelText('Archive file'), archiveFile())

    await waitFor(() =>
      expect(importFile).toHaveBeenCalledWith(expect.any(File), { mode: 'replace' }),
    )
    expect(confirmReplace).toHaveBeenCalledWith(IMPORT_REPLACE_CONFIRMATION)
  })

  it('shows import errors without losing the panel', async () => {
    const user = userEvent.setup()
    const importFile = vi.fn(async () => {
      throw new Error('Unsafe attachment name: "../evil.txt"')
    })
    const service = createService(importFile)

    render(<DataImportPanel service={service} confirmReplace={vi.fn()} />)

    await user.upload(screen.getByLabelText('Archive file'), archiveFile())

    expect(await screen.findByRole('alert')).toHaveTextContent('Unsafe attachment name')
  })
})
