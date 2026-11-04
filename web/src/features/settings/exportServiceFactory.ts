import type { IDBPDatabase } from 'idb'
import { ExportReader, openDatabase, SettingsRepository, type ChatUiDB } from '../../storage'
import { AttachmentStore } from '../../storage/AttachmentStore'
import { ExportService, type AttachmentByteReader } from './exportService'
import { SettingsService } from './settingsService'

export interface CreateExportServiceOptions {
  db?: IDBPDatabase<ChatUiDB>
  attachmentBytes?: AttachmentByteReader
  appVersion?: string
}

/**
 * Build the production export service.
 *
 * The reader is constructed from IndexedDB and the typed settings facade. The
 * attachment byte reader is only consulted when the caller explicitly asks for
 * attachment contents.
 */
export async function createExportService(
  options: CreateExportServiceOptions = {},
): Promise<ExportService> {
  const db = options.db ?? (await openDatabase())
  const settings = new SettingsService(new SettingsRepository(db))
  const store = new AttachmentStore({ db })

  return new ExportService({
    reader: new ExportReader({ db, settings }),
    attachmentBytes: options.attachmentBytes ?? store,
    appVersion: options.appVersion,
  })
}
