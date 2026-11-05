import type { IDBPDatabase } from 'idb'
import { openDatabase, ProviderRepository, type ChatUiDB } from '../../storage'
import { AttachmentStore } from '../../storage/AttachmentStore'
import { SecretStore } from '../../storage/SecretStore'
import { ProviderService } from '../providers/providerService'
import {
  DataControlsService,
  type AttachmentCleanupLike,
  type ProviderKeyServiceLike,
} from './dataControlsService'

export interface CreateDataControlsServiceOptions {
  db?: IDBPDatabase<ChatUiDB>
  attachmentStore?: AttachmentCleanupLike
  keyService?: ProviderKeyServiceLike
}

/**
 * Build the production data-controls service.
 *
 * Per-key deletion reuses `ProviderService.removeKey`, so the data screen and
 * provider detail screen share the same secret deletion and metadata cleanup
 * behavior. The adapter is never called by the data screen; it exists only to
 * satisfy the provider service constructor.
 */
export async function createDataControlsService(
  options: CreateDataControlsServiceOptions = {},
): Promise<DataControlsService> {
  const db = options.db ?? (await openDatabase())
  const providers = new ProviderRepository(db)
  const keyService =
    options.keyService ??
    new ProviderService({
      providers,
      secrets: new SecretStore({ db }),
      adapter: {
        validateKey: async () => {
          throw new Error('Key validation is not available from data controls.')
        },
        listModels: async () => [],
      },
    })

  return new DataControlsService({
    db,
    attachmentStore: options.attachmentStore ?? new AttachmentStore({ db }),
    keyService,
  })
}
