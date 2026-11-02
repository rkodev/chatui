import type { IDBPDatabase } from 'idb'
import { nowIso, type Model } from '../domain'
import { runTransaction, type ChatUiDB } from './db'

export interface ModelListOptions {
  providerId?: string
}

export class ModelConflictError extends Error {
  constructor(providerId: string, remoteId: string) {
    super(`Model ${providerId}/${remoteId} already exists`)
    this.name = 'ModelConflictError'
  }
}

export class ModelRepository {
  private readonly db: IDBPDatabase<ChatUiDB>

  constructor(db: IDBPDatabase<ChatUiDB>) {
    this.db = db
  }

  async create(model: Model): Promise<Model> {
    return runTransaction(this.db, ['models'], 'readwrite', async (transaction) => {
      const store = transaction.objectStore('models')
      const existing = await store
        .index('providerId+remoteId')
        .get([model.providerId, model.remoteId])

      if (existing && existing.id !== model.id) {
        throw new ModelConflictError(model.providerId, model.remoteId)
      }

      await store.put(model)
      return model
    })
  }

  async get(id: string): Promise<Model | null> {
    return (await this.db.get('models', id)) ?? null
  }

  async list(options: ModelListOptions = {}): Promise<Model[]> {
    if (options.providerId === undefined) {
      return this.db.getAll('models')
    }
    return this.db.getAllFromIndex('models', 'providerId', options.providerId)
  }

  async update(id: string, patch: Partial<Omit<Model, 'id' | 'createdAt'>>): Promise<Model> {
    return runTransaction(this.db, ['models'], 'readwrite', async (transaction) => {
      const store = transaction.objectStore('models')
      const existing = await store.get(id)
      if (!existing) {
        throw new Error(`Model ${id} not found`)
      }

      const updated: Model = {
        ...existing,
        ...patch,
        id,
        createdAt: existing.createdAt,
        updatedAt: patch.updatedAt ?? nowIso(),
      }

      const conflict = await store
        .index('providerId+remoteId')
        .get([updated.providerId, updated.remoteId])
      if (conflict && conflict.id !== id) {
        throw new ModelConflictError(updated.providerId, updated.remoteId)
      }

      await store.put(updated)
      return updated
    })
  }

  async delete(id: string): Promise<void> {
    await this.db.delete('models', id)
  }
}
