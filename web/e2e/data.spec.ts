import { readFile } from 'node:fs/promises'
import {
  clearAllData,
  completeOnboarding,
  deleteConversation,
  expect,
  openDataSettings,
  sendMessage,
  test,
  waitForAssistantText,
} from './fixtures/app'

interface ExportedArchive {
  format: string
  conversations: Array<{ id: string; title: string }>
  messages: Array<{
    id: string
    conversationId: string
    sequence: number
    role: string
    modelLabel: string | null
  }>
}

test.describe('conversation data controls', () => {
  test('exports and imports an archive while preserving ids, order, and model snapshots', async ({
    page,
  }) => {
    await completeOnboarding(page)
    await sendMessage(page, 'Export me')
    await waitForAssistantText(page, 'Hello from the mock provider.')

    await openDataSettings(page)
    await expect(page.getByRole('row', { name: /Conversations/ })).toContainText('1')
    await expect(page.getByRole('row', { name: /Messages/ })).toContainText('2')

    const downloadPromise = page.waitForEvent('download')
    await page.getByRole('button', { name: 'Export all data (JSON)' }).click()
    const download = await downloadPromise
    const downloadPath = await download.path()
    expect(downloadPath).not.toBeNull()

    const archive = JSON.parse(await readFile(downloadPath as string, 'utf8')) as ExportedArchive
    expect(archive.format).toBe('chatui.export')
    expect(archive.conversations).toHaveLength(1)
    expect(archive.messages).toHaveLength(2)

    const exportedConversation = archive.conversations[0]
    const exportedMessages = [...archive.messages].sort(
      (left, right) => left.sequence - right.sequence,
    )
    const exportedAssistant = exportedMessages.find((message) => message.role === 'assistant')
    expect(exportedAssistant?.modelLabel).toBe('Auto Router')

    await clearAllData(page)
    await expect(page.getByRole('row', { name: /Conversations/ })).toContainText('0')
    await expect(page.getByText('No API keys are stored on this device.')).toBeVisible()

    await page.locator('#import-file').setInputFiles(downloadPath as string)
    await expect(page.getByRole('status').filter({ hasText: /Imported 1 conversation/u })).toBeVisible()

    const imported = await page.evaluate(async () => {
      function requestToPromise<T>(request: IDBRequest<T>): Promise<T> {
        return new Promise((resolve, reject) => {
          request.onsuccess = () => resolve(request.result)
          request.onerror = () => reject(request.error)
        })
      }

      const openRequest = indexedDB.open('chatui')
      const db = await new Promise<IDBDatabase>((resolve, reject) => {
        openRequest.onsuccess = () => resolve(openRequest.result)
        openRequest.onerror = () => reject(openRequest.error)
      })

      try {
        const transaction = db.transaction(['conversations', 'messages'], 'readonly')
        const conversations = await requestToPromise(
          transaction.objectStore('conversations').getAll(),
        )
        const messages = await requestToPromise(transaction.objectStore('messages').getAll())
        return { conversations, messages }
      } finally {
        db.close()
      }
    })

    expect(imported.conversations).toHaveLength(1)
    expect(imported.conversations[0].id).toBe(exportedConversation.id)
    expect(imported.conversations[0].title).toBe('Export me')

    const importedMessages = [...imported.messages].sort(
      (left, right) => left.sequence - right.sequence,
    )
    expect(importedMessages.map((message) => message.id)).toEqual(
      exportedMessages.map((message) => message.id),
    )
    expect(importedMessages.map((message) => message.sequence)).toEqual(
      exportedMessages.map((message) => message.sequence),
    )
    expect(importedMessages.find((message) => message.role === 'assistant')?.modelLabel).toBe(
      'Auto Router',
    )

    await page.goto('/')
    await page.getByRole('button', { name: /^Export me/u }).first().click()
    await expect(page.getByRole('heading', { name: 'Export me' })).toBeVisible()
    await expect(page.locator('article[data-role="user"]').last()).toContainText('Export me')
    await expect(page.locator('article[data-role="assistant"]').last()).toContainText(
      'Hello from the mock provider.',
    )
  })

  test('deletes a conversation with undo and commits after the undo window', async ({ page }) => {
    await completeOnboarding(page)
    await sendMessage(page, 'Delete me')
    await waitForAssistantText(page, 'Hello from the mock provider.')
    await page.reload()

    await deleteConversation(page, 'Delete me')
    await expect(page.getByText('Deleted “Delete me”.')).toBeVisible()
    await page.getByRole('button', { name: 'Undo' }).click()

    await expect(page.getByRole('heading', { name: 'Delete me' })).toBeVisible()
    await expect(page.getByRole('button', { name: /^Delete me/u })).toBeVisible()

    await page.reload()
    await expect(page.getByRole('heading', { name: 'Delete me' })).toBeVisible()

    await deleteConversation(page, 'Delete me')
    await expect(page.getByText('Deleted “Delete me”.')).toBeVisible()
    await expect(page.getByRole('button', { name: /^Delete me/u })).toHaveCount(0, {
      timeout: 7000,
    })
  })

  test('clears conversations, messages, providers, models, and stored keys', async ({ page }) => {
    await completeOnboarding(page)
    await sendMessage(page, 'Clear me')
    await waitForAssistantText(page, 'Hello from the mock provider.')

    await openDataSettings(page)
    await expect(page.getByRole('row', { name: /Conversations/ })).toContainText('1')
    await expect(page.getByRole('row', { name: /Messages/ })).toContainText('2')
    await expect(page.getByRole('row', { name: /Stored keys/ })).toContainText('1')

    await clearAllData(page)

    await expect(
      page.getByRole('status').filter({ hasText: /Removed 1 conversation/u }),
    ).toBeVisible()
    await expect(page.getByText('No API keys are stored on this device.')).toBeVisible()
    await expect(page.getByRole('row', { name: /Conversations/ })).toContainText('0')
    await expect(page.getByRole('row', { name: /Messages/ })).toContainText('0')
    await expect(page.getByRole('row', { name: /Stored keys/ })).toContainText('0')
    await expect(page.getByRole('row', { name: /Providers/ })).toContainText('0')
    await expect(page.getByRole('row', { name: /Models/ })).toContainText('0')
  })
})
