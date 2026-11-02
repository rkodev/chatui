import type { IDBPDatabase } from 'idb'
import { nowIso } from '../domain'
import type { ChatUiDB, SettingRecord } from './db'

export class SettingsRepository {
  private readonly db: IDBPDatabase<ChatUiDB>

  constructor(db: IDBPDatabase<ChatUiDB>) {
    this.db = db
  }

  async get<T = unknown>(key: string): Promise<T | null> {
    const record = await this.db.get('settings', key)
    return record ? (record.value as T) : null
  }

  async set<T = unknown>(key: string, value: T): Promise<SettingRecord<T>> {
    const record: SettingRecord<T> = {
      key,
      value,
      updatedAt: nowIso(),
    }

    await this.db.put('settings', record)
    return record
  }

  async list(): Promise<SettingRecord[]> {
    return this.db.getAll('settings')
  }
}
