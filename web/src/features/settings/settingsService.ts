import type { ChatParams, SendKey, Settings } from '../../domain'

/**
 * Minimal settings persistence surface.
 *
 * `SettingsRepository` satisfies this interface. Tests can inject an in-memory
 * fake without opening IndexedDB.
 */
export interface SettingsStore {
  get<T = unknown>(key: string): Promise<T | null>
  set<T = unknown>(key: string, value: T): Promise<unknown>
  list(): Promise<Array<{ key: string; value: unknown }>>
}

/**
 * Spec-aligned defaults.
 *
 * `rememberKeysOnWeb` defaults to true because spec 8.6 names "remember on this
 * device" as the convenience default. `loadRemoteImages` stays false for M1;
 * remote Markdown images remain blocked until M2 exposes the control.
 */
export const DEFAULT_SETTINGS: Settings = {
  theme: 'system',
  density: 'comfortable',
  chatFontSize: 'md',
  sendKey: 'enter',
  defaultProviderId: null,
  defaultModelId: null,
  defaultSystemPrompt: '',
  defaultParams: {
    temperature: null,
    topP: null,
    maxOutputTokens: null,
    stop: [],
    reasoningEffort: null,
    streaming: true,
    extra: {},
  },
  rememberKeysOnWeb: true,
  showTokenUsage: true,
  loadRemoteImages: false,
  lastActiveConversationId: null,
  backup: null,
  onboardingCompleted: false,
}

export interface SettingsDefaults {
  providerId: string | null
  modelId: string | null
  systemPrompt: string
  params: ChatParams
}

/**
 * Typed facade over the key/value `SettingsRepository`.
 *
 * Each setting is stored under its own key. Reads merge stored values over
 * `DEFAULT_SETTINGS`, so a fresh database and a partially populated database
 * both return a complete `Settings` object.
 */
export class SettingsService {
  private readonly repository: SettingsStore

  constructor(repository: SettingsStore) {
    this.repository = repository
  }

  async get(): Promise<Settings> {
    const settings = structuredClone(DEFAULT_SETTINGS)
    const records = await this.repository.list()
    const stored = new Map(records.map((record) => [record.key, record.value]))
    const target = settings as unknown as Record<string, unknown>

    for (const key of Object.keys(settings) as Array<keyof Settings>) {
      if (stored.has(key)) {
        target[key] = stored.get(key)
      }
    }

    return settings
  }

  async update(patch: Partial<Settings>): Promise<Settings> {
    for (const key of Object.keys(patch) as Array<keyof Settings>) {
      const value = patch[key]

      if (value !== undefined) {
        await this.repository.set(key, value)
      }
    }

    return this.get()
  }

  async getOnboardingCompleted(): Promise<boolean> {
    return (await this.get()).onboardingCompleted
  }

  async setOnboardingCompleted(value: boolean): Promise<void> {
    await this.repository.set('onboardingCompleted', value)
  }

  async getSendKey(): Promise<SendKey> {
    return (await this.get()).sendKey
  }

  async setSendKey(value: SendKey): Promise<void> {
    await this.repository.set('sendKey', value)
  }

  async getShowTokenUsage(): Promise<boolean> {
    return (await this.get()).showTokenUsage
  }

  async setShowTokenUsage(value: boolean): Promise<void> {
    await this.repository.set('showTokenUsage', value)
  }

  async getLastActiveConversationId(): Promise<string | null> {
    return (await this.get()).lastActiveConversationId
  }

  async setLastActiveConversationId(value: string | null): Promise<void> {
    await this.repository.set('lastActiveConversationId', value)
  }

  async getRememberKeysOnWeb(): Promise<boolean> {
    return (await this.get()).rememberKeysOnWeb
  }

  async setRememberKeysOnWeb(value: boolean): Promise<void> {
    await this.repository.set('rememberKeysOnWeb', value)
  }

  async getDefaults(): Promise<SettingsDefaults> {
    const settings = await this.get()

    return {
      providerId: settings.defaultProviderId,
      modelId: settings.defaultModelId,
      systemPrompt: settings.defaultSystemPrompt,
      params: settings.defaultParams,
    }
  }

  async setDefaults(patch: Partial<SettingsDefaults>): Promise<Settings> {
    const update: Partial<Settings> = {}

    if (patch.providerId !== undefined) {
      update.defaultProviderId = patch.providerId
    }
    if (patch.modelId !== undefined) {
      update.defaultModelId = patch.modelId
    }
    if (patch.systemPrompt !== undefined) {
      update.defaultSystemPrompt = patch.systemPrompt
    }
    if (patch.params !== undefined) {
      update.defaultParams = patch.params
    }

    return this.update(update)
  }
}
