export {
  DB_NAME,
  DB_VERSION,
  deleteDatabase,
  fromConversationRecord,
  fromProviderRecord,
  openDatabase,
  runTransaction,
  toConversationRecord,
  toProviderRecord,
} from './db'
export type {
  AttachmentBlobRecord,
  BackupConfigRecord,
  ChatUiDB,
  ChatUiStoreName,
  ChatUiTransaction,
  ConversationRecord,
  ProviderRecord,
  SecretRecord,
  SettingRecord,
  SyncQueueItem,
  SyncQueueOperation,
} from './db'

export { migrations, runMigrations } from './migrations'
export type { Migration, MigrationContext } from './migrations'

export { ConversationRepository } from './ConversationRepository'
export type {
  ConversationListOptions,
  ConversationPage,
  NewConversation,
} from './ConversationRepository'

export { DEFAULT_MESSAGE_LIMIT, MessageRepository } from './MessageRepository'
export type { AppendMessageResult, MessageListOptions, MessagePage } from './MessageRepository'

export { AttachmentRepository } from './AttachmentRepository'
export type { AttachmentListOptions } from './AttachmentRepository'

export { ProviderRepository } from './ProviderRepository'
export type { ProviderListOptions } from './ProviderRepository'

export { ModelConflictError, ModelRepository } from './ModelRepository'
export type { ModelListOptions } from './ModelRepository'

export { SettingsRepository } from './SettingsRepository'

export { SecretRepository } from './SecretRepository'

export { BackupConfigRepository, DEFAULT_BACKUP_CONFIG_ID } from './BackupConfigRepository'

export { SyncQueueRepository } from './SyncQueueRepository'
export type {
  NewSyncQueueItem,
  SyncQueueAttemptPatch,
  SyncQueueListOptions,
} from './SyncQueueRepository'
