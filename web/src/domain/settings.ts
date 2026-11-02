/**
 * Settings and future backup configuration from spec section 6.2.
 */
import type { ChatParams } from './conversation'

export type Theme = 'system' | 'light' | 'dark'

export type Density = 'comfortable' | 'compact'

export type ChatFontSize = 'sm' | 'md' | 'lg'

export type SendKey = 'enter' | 'mod-enter'

export type BackupBackend = 's3' | 'azure-blob' | 'gcs' | 'webdav'

export type BackupEncryption = 'aes-256-gcm'

export type BackupKdf = 'pbkdf2-sha256' | 'argon2id'

export interface BackupConfig {
  enabled: boolean
  backend: BackupBackend
  endpoint: string | null
  region: string | null
  bucket: string
  prefix: string
  credentialSecretId: string | null
  encryption: BackupEncryption
  kdf: BackupKdf
  kdfSalt: string | null
  lastSyncAt: string | null
  lastManifestEtag: string | null
  deviceId: string
}

export interface Settings {
  theme: Theme
  density: Density
  chatFontSize: ChatFontSize
  sendKey: SendKey
  defaultProviderId: string | null
  defaultModelId: string | null
  defaultSystemPrompt: string
  defaultParams: ChatParams
  rememberKeysOnWeb: boolean
  showTokenUsage: boolean
  loadRemoteImages: boolean
  lastActiveConversationId: string | null
  backup: BackupConfig | null
  onboardingCompleted: boolean
}
