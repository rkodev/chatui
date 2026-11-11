import { afterEach, describe, expect, it } from 'vitest'
import { openDB, type IDBPDatabase } from 'idb'
import { newId, nowIso } from '../domain'
import { DB_VERSION, deleteDatabase, openDatabase, type ChatUiDB, type ChatUiStoreName } from './db'
import { migrations, runMigrations, type Migration } from './migrations'

const databaseNames = new Set<string>()

function uniqueDatabaseName(): string {
  const name = `chatui-migration-${newId()}`
  databaseNames.add(name)
  return name
}

function indexNames(db: IDBPDatabase<ChatUiDB>, store: ChatUiStoreName): string[] {
  return Array.from(db.transaction(store, 'readonly').store.indexNames).sort()
}

afterEach(async () => {
  for (const name of databaseNames) {
    await deleteDatabase(name)
  }
  databaseNames.clear()
})

describe('IndexedDB migrations', () => {
  it('creates the current version with every required store and index', async () => {
    const db = await openDatabase(uniqueDatabaseName())

    expect(db.version).toBe(DB_VERSION)
    expect(Array.from(db.objectStoreNames).sort()).toEqual(
      [
        'attachmentBlobs',
        'attachments',
        'backupConfig',
        'conversations',
        'messages',
        'models',
        'providers',
        'secrets',
        'settings',
        'syncQueue',
        'tombstones',
      ].sort(),
    )

    expect(indexNames(db, 'conversations')).toEqual([
      'archived',
      'lastMessageAt',
      'pinned',
      'updatedAt',
    ])
    expect(indexNames(db, 'messages')).toEqual([
      'conversationId',
      'conversationId+sequence',
      'createdAt',
    ])
    expect(indexNames(db, 'attachments')).toEqual(['createdAt', 'messageId', 'sha256'])
    expect(indexNames(db, 'providers')).toEqual(['enabled', 'presetId'])
    expect(indexNames(db, 'models')).toEqual(['providerId', 'providerId+remoteId'])
    expect(indexNames(db, 'settings')).toEqual([])
    expect(indexNames(db, 'secrets')).toEqual([])
    expect(indexNames(db, 'backupConfig')).toEqual([])
    expect(indexNames(db, 'syncQueue')).toEqual(['attempts', 'conversationId'])
    expect(indexNames(db, 'attachmentBlobs')).toEqual([])
    expect(indexNames(db, 'tombstones')).toEqual(['conversationId', 'deletedAt'])

    const modelTransaction = db.transaction('models', 'readonly')
    expect(modelTransaction.store.index('providerId+remoteId').unique).toBe(true)
    await modelTransaction.done

    db.close()
  })

  it('reopens an existing database without rerunning migrations', async () => {
    const name = uniqueDatabaseName()
    const first = await openDatabase(name)
    await first.put('settings', { key: 'theme', value: 'dark', updatedAt: nowIso() })
    first.close()

    const second = await openDatabase(name)
    expect(second.version).toBe(DB_VERSION)
    expect(await second.get('settings', 'theme')).toMatchObject({ value: 'dark' })
    second.close()
  })

  it('migrates v1 conversations to v2 and backfills storageKey and revision', async () => {
    const name = uniqueDatabaseName()
    const now = nowIso()
    const oldRecord = {
      id: 'conv-old',
      title: 'Old conversation',
      systemPrompt: '',
      providerId: 'provider-1',
      modelId: 'model-1',
      modelLabel: 'Model',
      params: {
        temperature: null,
        topP: null,
        maxOutputTokens: null,
        stop: [],
        reasoningEffort: null,
        streaming: true,
        extra: {},
      },
      pinned: 0,
      archived: 0,
      temporary: false,
      createdAt: now,
      updatedAt: now,
      lastMessageAt: now,
      messageCount: 0,
      lastError: null,
      sync: null,
    }
    const alreadyMigrated = {
      ...oldRecord,
      id: 'conv-migrated',
      storageKey: 'fedcba9876543210fedcba9876543210',
      revision: 7,
    }

    const first = await openDB<ChatUiDB>(name, 1, {
      upgrade(database, oldVersion, newVersion, transaction) {
        runMigrations(
          database,
          oldVersion,
          newVersion ?? 1,
          transaction,
          migrations.filter((migration) => migration.version <= 1),
        )
      },
    })
    await first.put('conversations', oldRecord as never)
    await first.put('conversations', alreadyMigrated as never)
    first.close()

    const second = await openDatabase(name)
    expect(second.version).toBe(2)

    const migrated = await second.get('conversations', 'conv-old')
    expect(migrated).toMatchObject({ title: 'Old conversation', revision: 1 })
    expect(migrated?.storageKey).toMatch(/^[0-9a-f]{32}$/u)

    const untouched = await second.get('conversations', 'conv-migrated')
    expect(untouched).toMatchObject({
      storageKey: 'fedcba9876543210fedcba9876543210',
      revision: 7,
    })

    expect(Array.from(second.objectStoreNames)).toContain('tombstones')
    second.close()
  })

  it('runs versioned migrations in order when upgrading a database', async () => {
    const name = uniqueDatabaseName()
    const probeMigration: Migration = {
      version: 3,
      description: 'Test-only migration probe',
      up: ({ db }) => {
        db.createObjectStore('migrationProbe' as never, { keyPath: 'id' })
      },
    }

    const db = await openDB<ChatUiDB>(name, 3, {
      upgrade(database, oldVersion, newVersion, transaction) {
        runMigrations(database, oldVersion, newVersion ?? 3, transaction, [
          ...migrations,
          probeMigration,
        ])
      },
    })

    expect(db.version).toBe(3)
    expect(Array.from(db.objectStoreNames)).toContain('migrationProbe')
    db.close()
  })
})
