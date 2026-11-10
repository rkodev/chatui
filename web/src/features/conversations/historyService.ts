import type { Conversation } from '../../domain'
import type {
  ConversationListOptions,
  ConversationPage,
  ConversationSearchOptions,
  ConversationSearchResult,
} from '../../storage'
import type { AppConfig } from '../../app/config'
import { openRouterAdapter } from '../../providers/openrouter'
import { getDefaultCatalogCache } from '../../providers/openrouterCatalog'
import { ConversationRepository } from '../../storage/ConversationRepository'
import { MessageRepository } from '../../storage/MessageRepository'
import { ModelRepository } from '../../storage/ModelRepository'
import { ProviderRepository } from '../../storage/ProviderRepository'
import { SettingsRepository } from '../../storage/SettingsRepository'
import { openDatabase } from '../../storage/db'
import { ModelService } from '../providers/modelService'
import { SettingsService } from '../settings/settingsService'
import { ConversationService } from './conversationService'
import { RecoveryService } from './recoveryService'
import type { CreateConversationInput, CreateConversationResult } from './types'

/**
 * History-facing subset of `ConversationService`.
 *
 * The sidebar and root redirect only need list/create/rename/delete and
 * last-active tracking. Keeping the interface narrow makes the UI easy to test
 * with an in-memory fake.
 */
export interface ConversationHistoryServiceLike {
  listConversations(options?: ConversationListOptions): Promise<ConversationPage>
  searchConversations(
    query: string,
    options?: ConversationSearchOptions,
  ): Promise<ConversationSearchResult>
  createConversation(input?: CreateConversationInput): Promise<CreateConversationResult>
  renameConversation(id: string, title: string): Promise<Conversation>
  setConversationPinned(id: string, pinned: boolean): Promise<Conversation>
  setConversationArchived(id: string, archived: boolean): Promise<Conversation>
  deleteConversation(id: string): Promise<void>
  getLastActiveConversation(): Promise<Conversation | null>
  setLastActiveConversation(id: string | null): Promise<void>
}

export interface ConversationHistoryServices {
  service: ConversationHistoryServiceLike
  recovery: RecoveryService
}

/**
 * Build the production history service and startup recovery from IndexedDB.
 *
 * The service shares the same repositories and typed settings as the chat
 * service, so last-active tracking and conversation metadata stay consistent.
 */
export async function createConversationHistoryService(
  config: AppConfig,
): Promise<ConversationHistoryServices> {
  const db = await openDatabase()
  const providers = new ProviderRepository(db)
  const models = new ModelRepository(db)
  const settings = new SettingsService(new SettingsRepository(db))
  const modelService = new ModelService({
    providers,
    models,
    adapter: openRouterAdapter,
    fetch: globalThis.fetch,
    attribution: {
      origin: config.openRouter.attributionOrigin,
      title: config.openRouter.appTitle,
    },
    catalogCache: getDefaultCatalogCache(),
  })
  const messages = new MessageRepository(db)
  const service = new ConversationService({
    conversations: new ConversationRepository(db),
    messages,
    providers,
    models: modelService,
    settings,
  })
  const recovery = new RecoveryService({ messages })

  return { service, recovery }
}
