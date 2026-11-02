import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { IDBPDatabase } from 'idb'
import { deleteDatabase, openDatabase, type ChatUiDB } from '../../storage/db'
import { SettingsRepository } from '../../storage/SettingsRepository'
import { DEFAULT_SETTINGS, SettingsService } from './settingsService'

describe('SettingsService', () => {
  let db: IDBPDatabase<ChatUiDB>

  beforeEach(async () => {
    await deleteDatabase()
    db = await openDatabase()
  })

  afterEach(async () => {
    db.close()
    await deleteDatabase()
  })

  function service(): SettingsService {
    return new SettingsService(new SettingsRepository(db))
  }

  it('returns spec defaults on a fresh database', async () => {
    expect(await service().get()).toEqual(DEFAULT_SETTINGS)
  })

  it('merges stored values over defaults', async () => {
    const repository = new SettingsRepository(db)
    await repository.set('sendKey', 'mod-enter')
    await repository.set('onboardingCompleted', true)

    const settings = await new SettingsService(repository).get()

    expect(settings.sendKey).toBe('mod-enter')
    expect(settings.onboardingCompleted).toBe(true)
    expect(settings.theme).toBe('system')
    expect(settings.defaultParams.streaming).toBe(true)
  })

  it('updates individual settings through convenience accessors', async () => {
    const settings = service()

    await settings.setOnboardingCompleted(true)
    await settings.setSendKey('mod-enter')
    await settings.setShowTokenUsage(false)
    await settings.setLastActiveConversationId('conversation-1')
    await settings.setRememberKeysOnWeb(false)

    expect(await settings.getOnboardingCompleted()).toBe(true)
    expect(await settings.getSendKey()).toBe('mod-enter')
    expect(await settings.getShowTokenUsage()).toBe(false)
    expect(await settings.getLastActiveConversationId()).toBe('conversation-1')
    expect(await settings.getRememberKeysOnWeb()).toBe(false)
  })

  it('updates defaults without clobbering unrelated settings', async () => {
    const settings = service()
    await settings.setSendKey('mod-enter')

    const params = { ...DEFAULT_SETTINGS.defaultParams, temperature: 0.2 }
    await settings.setDefaults({
      providerId: 'provider-1',
      modelId: 'model-1',
      systemPrompt: 'Be concise.',
      params,
    })

    expect(await settings.getDefaults()).toEqual({
      providerId: 'provider-1',
      modelId: 'model-1',
      systemPrompt: 'Be concise.',
      params,
    })
    expect((await settings.get()).sendKey).toBe('mod-enter')
  })

  it('does not share mutable default params between reads', async () => {
    const settings = service()
    const first = await settings.get()
    first.defaultParams.stop.push('STOP')

    const second = await settings.get()

    expect(second.defaultParams.stop).toEqual([])
  })
})
