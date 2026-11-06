import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { IDBPDatabase } from 'idb'
import type { Message } from '../../domain'
import { bytesToBase64 } from '../../platform/crypto'
import { createFakeOpfsRoot } from '../../platform/testing/fakeOpfs'
import { createOpfsAttachmentFileSystem } from '../../platform/files'
import { deleteDatabase, openDatabase, type ChatUiDB } from '../../storage/db'
import { AttachmentStore, storageKeyFor } from '../../storage/AttachmentStore'
import { AttachmentService } from './attachmentService'
import { createTestMessage } from '../chat/testSupport'

function file(contents: string, name: string, type: string): File {
  return new File([new TextEncoder().encode(contents)], name, { type })
}

function makeService(db: IDBPDatabase<ChatUiDB>) {
  const root = createFakeOpfsRoot()
  const store = new AttachmentStore({
    db,
    capabilities: { opfs: true },
    fileSystem: createOpfsAttachmentFileSystem(root),
  })
  const service = new AttachmentService({
    db,
    store,
    imageProcessor: async () => ({
      bytes: new TextEncoder().encode('processed-image'),
      mimeType: 'image/webp',
      width: 1024,
      height: 512,
    }),
  })

  return { service, store, root }
}

describe('AttachmentService', () => {
  let db: IDBPDatabase<ChatUiDB>

  beforeEach(async () => {
    await deleteDatabase()
    db = await openDatabase()
  })

  afterEach(async () => {
    db.close()
    await deleteDatabase()
  })

  it('processes an image, stores processed bytes, and persists metadata', async () => {
    const { service, store } = makeService(db)

    const result = await service.addFiles([file('original-image-bytes', 'pixel.png', 'image/png')])

    expect(result.errors).toEqual([])
    expect(result.attachments).toHaveLength(1)
    const [attachment] = result.attachments
    expect(attachment).toMatchObject({
      kind: 'image',
      mimeType: 'image/webp',
      name: 'pixel.png',
      width: 1024,
      height: 512,
      messageId: null,
    })
    expect(new TextDecoder().decode(await store.get(attachment.storageKey))).toBe('processed-image')
    expect(await service.get(attachment.id)).toEqual(attachment)
  })

  it('stores text files and resolves their bytes to base64', async () => {
    const { service } = makeService(db)
    const result = await service.addFiles([file('Hello attachment.', 'notes.txt', 'text/plain')])
    const [attachment] = result.attachments

    const messages: Message[] = [
      createTestMessage({
        parts: [
          { type: 'text', text: 'Describe' },
          {
            type: 'file',
            attachmentId: attachment.id,
            mimeType: attachment.mimeType,
            name: attachment.name,
          },
        ],
      }),
    ]

    const resolved = await service.resolveForMessages(messages)

    expect(resolved[attachment.id]).toEqual({
      mimeType: 'text/plain',
      name: 'notes.txt',
      dataBase64: bytesToBase64(new TextEncoder().encode('Hello attachment.')),
    })
  })

  it('rejects invalid files without writing metadata or bytes', async () => {
    const { service } = makeService(db)

    const result = await service.addFiles([file('MZ', 'payload.exe', 'application/x-msdownload')])

    expect(result.attachments).toEqual([])
    expect(result.errors[0]).toMatchObject({ fileName: 'payload.exe', code: 'unsupported_type' })
    await expect(service.listAll()).resolves.toEqual([])
  })

  it('survives a new service instance over the same database', async () => {
    const first = makeService(db)
    const result = await first.service.addFiles([file('keep', 'notes.md', 'text/markdown')])

    const second = makeService(db)
    const reloaded = await second.service.get(result.attachments[0].id)

    expect(reloaded).toEqual(result.attachments[0])
    expect(await second.service.getDataUrl(result.attachments[0].id)).toBeNull()
  })

  it('binds a draft attachment to a message and cleans it up on delete', async () => {
    const { service, store } = makeService(db)
    const result = await service.addFiles([file('draft', 'notes.txt', 'text/plain')])
    const [attachment] = result.attachments

    await service.attachToMessage([attachment.id], 'message-1')
    expect(await service.get(attachment.id)).toMatchObject({ messageId: 'message-1' })

    await service.deleteForMessages(['message-1'])

    expect(await service.get(attachment.id)).toBeNull()
    await expect(store.get(attachment.storageKey)).rejects.toThrow()
  })

  it('keeps shared content-addressed bytes until the last metadata row is removed', async () => {
    const { service, store } = makeService(db)
    const first = await service.addFiles([file('same', 'a.txt', 'text/plain')])
    const second = await service.addFiles([file('same', 'b.txt', 'text/plain')])
    const a = first.attachments[0]
    const b = second.attachments[0]

    expect(a.sha256).toBe(b.sha256)

    await service.remove(a.id)
    expect(new TextDecoder().decode(await store.get(b.storageKey))).toBe('same')

    await service.remove(b.id)
    await expect(store.get(b.storageKey)).rejects.toThrow()
  })

  it('removes stale metadata and bytes with no metadata row during orphan cleanup', async () => {
    const { service, store } = makeService(db)
    const keep = await service.addFiles([file('keep', 'keep.txt', 'text/plain')])
    await service.attachToMessage([keep.attachments[0].id], 'message-1')
    await db.put('messages', createTestMessage({ id: 'message-1' }))

    const draft = await service.addFiles([file('draft', 'draft.txt', 'text/plain')])
    const orphanSha = 'f'.repeat(64)
    await store.put(new TextEncoder().encode('orphan'), orphanSha)

    const removed = await service.cleanupOrphans()

    expect(removed).toContain(storageKeyFor(orphanSha))
    expect(await service.get(keep.attachments[0].id)).not.toBeNull()
    expect(await service.get(draft.attachments[0].id)).toBeNull()
    await expect(store.get(storageKeyFor(orphanSha))).rejects.toThrow()
  })

  it('returns a data URL for stored images', async () => {
    const { service } = makeService(db)
    const result = await service.addFiles([file('original', 'pixel.png', 'image/png')])

    const url = await service.getDataUrl(result.attachments[0].id)

    expect(url).toBe(
      `data:image/webp;base64,${bytesToBase64(new TextEncoder().encode('processed-image'))}`,
    )
  })
})
