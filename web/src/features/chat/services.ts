import type { AppConfig } from '../../app/config'
import { openRouterAdapter } from '../../providers/openrouter'
import { getDefaultCatalogCache } from '../../providers/openrouterCatalog'
import { ConversationRepository } from '../../storage/ConversationRepository'
import { MessageRepository } from '../../storage/MessageRepository'
import { ModelRepository } from '../../storage/ModelRepository'
import { ProviderRepository } from '../../storage/ProviderRepository'
import { SecretStore } from '../../storage/SecretStore'
import { SettingsRepository } from '../../storage/SettingsRepository'
import { openDatabase } from '../../storage/db'
import { AttachmentStore } from '../../storage/AttachmentStore'
import { AttachmentService } from '../attachments/attachmentService'
import { ConversationService } from '../conversations'
import { ModelService } from '../providers'
import { SettingsService } from '../settings'
import { ChatRunner } from './ChatRunner'
import { ChatService } from './chatService'

function attributionFromConfig(config: AppConfig) {
  return {
    origin: config.openRouter.attributionOrigin,
    title: config.openRouter.appTitle,
  }
}

/**
 * Build the production chat service from IndexedDB and the OpenRouter adapter.
 *
 * The runner and the conversation service share the same repositories, so the
 * assistant placeholder and its final snapshot update conversation metadata in
 * the same transaction path as user messages.
 */
export async function createChatService(config: AppConfig): Promise<ChatService> {
  const db = await openDatabase()
  const attribution = attributionFromConfig(config)
  const providers = new ProviderRepository(db)
  const models = new ModelRepository(db)
  const secrets = new SecretStore({ db })
  const settings = new SettingsService(new SettingsRepository(db))
  const modelService = new ModelService({
    providers,
    models,
    adapter: openRouterAdapter,
    fetch: globalThis.fetch,
    attribution,
    catalogCache: getDefaultCatalogCache(),
  })
  const conversations = new ConversationService({
    conversations: new ConversationRepository(db),
    messages: new MessageRepository(db),
    providers,
    models: modelService,
    settings,
  })
  const runner = new ChatRunner({
    adapter: openRouterAdapter.streamChat,
    messages: conversations,
  })
  const attachmentService = new AttachmentService({
    db,
    store: new AttachmentStore({ db }),
  })

  // Best-effort startup cleanup removes metadata for deleted messages and
  // bytes left without metadata by a crash, a failed write, or an edit.
  await attachmentService.cleanupOrphans().catch(() => [])

  return new ChatService({
    conversations: {
      get: (id) => conversations.getConversation(id),
      appendMessage: (message) => conversations.appendMessage(message),
    },
    messages: new MessageRepository(db),
    providers,
    models,
    secrets,
    settings,
    runner,
    runtime: {
      fetch: globalThis.fetch,
      attribution,
    },
    attachments: attachmentService,
  })
}
