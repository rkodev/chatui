import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { IDBPDatabase } from 'idb'
import { newId } from '../../domain'
import { createFakeOpfsRoot } from '../../platform/testing/fakeOpfs'
import { createOpfsAttachmentFileSystem } from '../../platform/files'
import { AttachmentStore, storageKeyFor } from '../../storage/AttachmentStore'
import { ConversationRepository } from '../../storage/ConversationRepository'
import { deleteDatabase, openDatabase, type ChatUiDB } from '../../storage/db'
import { MessageRepository } from '../../storage/MessageRepository'
import { AttachmentService } from '../attachments/attachmentService'
import { createTestMessage } from '../chat/testSupport'
import { createTestConversation } from '../providers/testSupport'
import { createFakeConversationHistoryService } from './testSupport'
import { useConversationHistory } from './useConversationHistory'

function textFile(contents: string, name = 'notes.txt'): File {
  return new File([new TextEncoder().encode(contents)], name, { type: 'text/plain' })
}

describe('conversation delete undo with attachments', () => {
  let db: IDBPDatabase<ChatUiDB>

  beforeEach(async () => {
    await deleteDatabase()
    db = await openDatabase()
  })

  afterEach(async () => {
    db.close()
    await deleteDatabase()
  })

  it('defers the delete for the undo window and leaves attachments for orphan cleanup', async () => {
    const conversations = new ConversationRepository(db)
    const messages = new MessageRepository(db)
    const store = new AttachmentStore({
      db,
      capabilities: { opfs: true },
      fileSystem: createOpfsAttachmentFileSystem(createFakeOpfsRoot()),
    })
    const attachments = new AttachmentService({ db, store })
    const conversation = await conversations.create(
      createTestConversation({ id: newId(), title: 'With attachment' }),
    )
    const appended = await messages.appendMessage(
      createTestMessage({
        id: newId(),
        conversationId: conversation.id,
        parts: [{ type: 'text', text: 'Please read this.' }],
      }),
    )
    const added = await attachments.addFiles([textFile('attachment bytes')])
    const attachment = added.attachments[0]
    await attachments.attachToMessage([attachment.id], appended.message.id)

    const deleteConversation = vi.fn(async (id: string) => {
      await conversations.delete(id)
    })
    const service = createFakeConversationHistoryService({
      listConversations: (options) => conversations.list(options),
      deleteConversation,
    })
    const { result } = renderHook(() =>
      useConversationHistory({ service, undoWindowMs: 60 }),
    )

    await waitFor(() => expect(result.current.status).toBe('ready'))

    act(() => {
      result.current.confirmDelete(conversation)
    })

    expect(result.current.pendingDelete?.id).toBe(conversation.id)
    expect(deleteConversation).not.toHaveBeenCalled()
    expect(await conversations.get(conversation.id)).not.toBeNull()
    expect(await attachments.get(attachment.id)).not.toBeNull()
    await expect(store.get(storageKeyFor(attachment.sha256))).resolves.toBeInstanceOf(Uint8Array)

    act(() => {
      result.current.undoDelete()
    })

    expect(result.current.conversations.map((item) => item.id)).toEqual([conversation.id])
    expect(await conversations.get(conversation.id)).not.toBeNull()
    expect(await attachments.get(attachment.id)).not.toBeNull()

    act(() => {
      result.current.confirmDelete(conversation)
    })

    await waitFor(() => expect(deleteConversation).toHaveBeenCalledWith(conversation.id))
    await waitFor(() => expect(result.current.pendingDelete).toBeNull())

    expect(await conversations.get(conversation.id)).toBeNull()
    expect(await messages.get(appended.message.id)).toBeNull()
    // Deletion does not remove attachment rows; the orphan-cleanup path owns them.
    expect(await attachments.get(attachment.id)).not.toBeNull()
    await expect(store.get(storageKeyFor(attachment.sha256))).resolves.toBeInstanceOf(Uint8Array)

    const removed = await attachments.cleanupOrphans()

    expect(removed).toContain(storageKeyFor(attachment.sha256))
    expect(await attachments.get(attachment.id)).toBeNull()
    await expect(store.get(storageKeyFor(attachment.sha256))).rejects.toThrow()
  })
})
