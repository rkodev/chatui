import type { IDBPDatabase } from 'idb'
import type { ChatUiDB, SecretRecord } from './db'

/**
 * Persist ciphertext records only. Encryption and plaintext handling belong to
 * the secret store in M0-02.5.
 */
export class SecretRepository {
  private readonly db: IDBPDatabase<ChatUiDB>

  constructor(db: IDBPDatabase<ChatUiDB>) {
    this.db = db
  }

  async get(id: string): Promise<SecretRecord | null> {
    return (await this.db.get('secrets', id)) ?? null
  }

  async put(record: SecretRecord): Promise<SecretRecord> {
    await this.db.put('secrets', record)
    return record
  }

  async delete(id: string): Promise<void> {
    await this.db.delete('secrets', id)
  }
}
