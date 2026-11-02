import type { IDBPDatabase } from 'idb'
import type { Attachment } from '../domain'
import type { ChatUiDB } from './db'

export interface AttachmentListOptions {
  messageId?: string | null
  limit?: number
}

export class AttachmentRepository {
  private readonly db: IDBPDatabase<ChatUiDB>

  constructor(db: IDBPDatabase<ChatUiDB>) {
    this.db = db
  }

  async create(attachment: Attachment): Promise<Attachment> {
    await this.db.put('attachments', attachment)
    return attachment
  }

  async get(id: string): Promise<Attachment | null> {
    return (await this.db.get('attachments', id)) ?? null
  }

  async list(options: AttachmentListOptions = {}): Promise<Attachment[]> {
    let items: Attachment[]

    if (options.messageId === undefined) {
      items = await this.db.getAll('attachments')
    } else if (options.messageId === null) {
      items = (await this.db.getAll('attachments')).filter(
        (attachment) => attachment.messageId === null,
      )
    } else {
      items = await this.db.getAllFromIndex('attachments', 'messageId', options.messageId)
    }

    return options.limit === undefined ? items : items.slice(0, options.limit)
  }

  async delete(id: string): Promise<void> {
    await this.db.delete('attachments', id)
  }
}
