import type { Page } from '@playwright/test'

interface SwitchProviderInput {
  conversationTitle: string
  providerLabel: string
  preferredRemoteId?: string
}

interface SwitchProviderResult {
  providerId: string
  modelId: string
  modelLabel: string
  remoteId: string
}

/**
 * Point one conversation at another provider and model.
 *
 * The web UI currently exposes model switching but not a provider selector in
 * the conversation settings surface. This helper changes the persisted
 * conversation record through the same IndexedDB schema the app uses, so the
 * E2E test can still verify that a provider switch is durable and that the next
 * send uses the new provider. The provider itself is created through the UI.
 */
export async function switchConversationProvider(
  page: Page,
  input: SwitchProviderInput,
): Promise<SwitchProviderResult> {
  return page.evaluate(async ({ conversationTitle, providerLabel, preferredRemoteId }) => {
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
      const transaction = db.transaction(['conversations', 'providers', 'models'], 'readwrite')
      const conversations = transaction.objectStore('conversations')
      const providers = transaction.objectStore('providers')
      const models = transaction.objectStore('models')

      const allConversations = await requestToPromise(conversations.getAll())
      const allProviders = await requestToPromise(providers.getAll())
      const allModels = await requestToPromise(models.getAll())

      const conversation = allConversations.find((item) => item.title === conversationTitle)
      const provider = allProviders.find((item) => item.label === providerLabel)

      if (!conversation) {
        throw new Error(`Conversation "${conversationTitle}" was not found`)
      }

      if (!provider) {
        throw new Error(`Provider "${providerLabel}" was not found`)
      }

      const providerModels = allModels.filter((item) => item.providerId === provider.id)
      const model =
        providerModels.find((item) => item.remoteId === preferredRemoteId) ?? providerModels[0]

      if (!model) {
        throw new Error(`Provider "${providerLabel}" has no models`)
      }

      const updated = {
        ...conversation,
        providerId: provider.id,
        modelId: model.id,
        modelLabel: model.label,
      }

      await requestToPromise(conversations.put(updated))
      await new Promise<void>((resolve, reject) => {
        transaction.oncomplete = () => resolve()
        transaction.onerror = () => reject(transaction.error)
        transaction.onabort = () => reject(transaction.error)
      })

      return {
        providerId: provider.id,
        modelId: model.id,
        modelLabel: model.label,
        remoteId: model.remoteId,
      }
    } finally {
      db.close()
    }
  }, input)
}
