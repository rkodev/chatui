import type { IDBPDatabase } from 'idb'
import { nowIso, type BackupConfig } from '../domain'
import type { BackupConfigRecord, ChatUiDB } from './db'

export const DEFAULT_BACKUP_CONFIG_ID = 'default'

export class BackupConfigRepository {
  private readonly db: IDBPDatabase<ChatUiDB>

  constructor(db: IDBPDatabase<ChatUiDB>) {
    this.db = db
  }

  async get(id: string = DEFAULT_BACKUP_CONFIG_ID): Promise<BackupConfigRecord | null> {
    return (await this.db.get('backupConfig', id)) ?? null
  }

  async put(
    config: BackupConfig,
    id: string = DEFAULT_BACKUP_CONFIG_ID,
  ): Promise<BackupConfigRecord> {
    const record: BackupConfigRecord = {
      ...config,
      id,
      updatedAt: nowIso(),
    }

    await this.db.put('backupConfig', record)
    return record
  }
}
