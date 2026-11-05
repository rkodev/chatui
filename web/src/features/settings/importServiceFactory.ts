import type { IDBPDatabase } from 'idb'
import { openDatabase, type ChatUiDB } from '../../storage'
import { AttachmentStore } from '../../storage/AttachmentStore'
import { ImportService, type ImportAttachmentStore } from './importService'

export interface CreateImportServiceOptions {
  db?: IDBPDatabase<ChatUiDB>
  attachmentStore?: ImportAttachmentStore
  now?: () => string
}

/**
 * Build the production import service.
 *
 * The service validates the whole archive before writing. Attachment bytes use
 * the same content-addressed store as the attachment picker, so imported files
 * deduplicate with existing bytes.
 */
export async function createImportService(
  options: CreateImportServiceOptions = {},
): Promise<ImportService> {
  const db = options.db ?? (await openDatabase())

  return new ImportService({
    db,
    attachmentStore: options.attachmentStore ?? new AttachmentStore({ db }),
    now: options.now,
  })
}
