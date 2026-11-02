import type { IDBPDatabase } from 'idb'
import { nowIso, type Provider } from '../domain'
import { fromProviderRecord, runTransaction, toProviderRecord, type ChatUiDB } from './db'

export interface ProviderListOptions {
  enabled?: boolean
}

export class ProviderRepository {
  private readonly db: IDBPDatabase<ChatUiDB>

  constructor(db: IDBPDatabase<ChatUiDB>) {
    this.db = db
  }

  async create(provider: Provider): Promise<Provider> {
    await this.db.put('providers', toProviderRecord(provider))
    return provider
  }

  async get(id: string): Promise<Provider | null> {
    const record = await this.db.get('providers', id)
    return record ? fromProviderRecord(record) : null
  }

  async list(options: ProviderListOptions = {}): Promise<Provider[]> {
    const providers = (await this.db.getAll('providers')).map(fromProviderRecord)
    return options.enabled === undefined
      ? providers
      : providers.filter((provider) => provider.enabled === options.enabled)
  }

  async update(id: string, patch: Partial<Omit<Provider, 'id' | 'createdAt'>>): Promise<Provider> {
    return runTransaction(this.db, ['providers'], 'readwrite', async (transaction) => {
      const store = transaction.objectStore('providers')
      const existing = await store.get(id)
      if (!existing) {
        throw new Error(`Provider ${id} not found`)
      }

      const updated: Provider = {
        ...fromProviderRecord(existing),
        ...patch,
        id,
        createdAt: existing.createdAt,
        updatedAt: patch.updatedAt ?? nowIso(),
      }

      await store.put(toProviderRecord(updated))
      return updated
    })
  }

  /**
   * Delete a provider, its models, and its referenced secret in one
   * transaction, matching spec 6.4.
   */
  async delete(id: string): Promise<void> {
    await runTransaction(
      this.db,
      ['providers', 'models', 'secrets'],
      'readwrite',
      async (transaction) => {
        const providers = transaction.objectStore('providers')
        const provider = await providers.get(id)
        if (!provider) {
          return
        }

        await providers.delete(id)

        const modelIndex = transaction.objectStore('models').index('providerId')
        let cursor = await modelIndex.openCursor(IDBKeyRange.only(id))
        while (cursor) {
          await cursor.delete()
          cursor = await cursor.continue()
        }

        if (provider.secretId) {
          await transaction.objectStore('secrets').delete(provider.secretId)
        }
      },
    )
  }
}
