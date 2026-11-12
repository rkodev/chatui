import type { IDBPDatabase } from 'idb'
import { newId, type Tombstone } from '../domain'
import type { ChatUiDB } from './db'

export interface NewTombstone extends Omit<Tombstone, 'id'> {
  id?: string
}

function compareTombstones(left: Tombstone, right: Tombstone): number {
  return (
    left.deletedAt.localeCompare(right.deletedAt) ||
    left.conversationId.localeCompare(right.conversationId) ||
    left.id.localeCompare(right.id)
  )
}

/**
 * Durable deletion tombstones.
 *
 * Tombstones are kept until a remote manifest confirms the deletion. The
 * repository only stores and lists them; merge and retention policy belong to
 * the sync engine.
 */
export class TombstoneRepository {
  private readonly db: IDBPDatabase<ChatUiDB>

  constructor(db: IDBPDatabase<ChatUiDB>) {
    this.db = db
  }

  async add(input: NewTombstone): Promise<Tombstone> {
    const tombstone: Tombstone = {
      ...input,
      id: input.id ?? newId(),
    }

    await this.db.put('tombstones', tombstone)
    return tombstone
  }

  async listByConversation(conversationId: string): Promise<Tombstone[]> {
    const tombstones = await this.db.getAllFromIndex('tombstones', 'conversationId', conversationId)
    return tombstones.sort(compareTombstones)
  }

  async listAll(): Promise<Tombstone[]> {
    const tombstones = await this.db.getAll('tombstones')
    return tombstones.sort(compareTombstones)
  }

  async delete(id: string): Promise<void> {
    await this.db.delete('tombstones', id)
  }
}
