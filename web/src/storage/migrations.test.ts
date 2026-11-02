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
  it('creates version 1 with every required store and index', async () => {
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

    const modelTransaction = db.transaction('models', 'readonly')
    expect(modelTransaction.store.index('providerId+remoteId').unique).toBe(true)
    await modelTransaction.done

    db.close()
  })

  it('reopens an existing v1 database without rerunning migrations', async () => {
    const name = uniqueDatabaseName()
    const first = await openDatabase(name)
    await first.put('settings', { key: 'theme', value: 'dark', updatedAt: nowIso() })
    first.close()

    const second = await openDatabase(name)
    expect(second.version).toBe(DB_VERSION)
    expect(await second.get('settings', 'theme')).toMatchObject({ value: 'dark' })
    second.close()
  })

  it('runs versioned migrations in order when upgrading a v1 database', async () => {
    const name = uniqueDatabaseName()
    const probeMigration: Migration = {
      version: 2,
      description: 'Test-only migration probe',
      up: ({ db }) => {
        db.createObjectStore('migrationProbe' as never, { keyPath: 'id' })
      },
    }

    const db = await openDB<ChatUiDB>(name, 2, {
      upgrade(database, oldVersion, newVersion, transaction) {
        runMigrations(database, oldVersion, newVersion ?? 2, transaction, [
          ...migrations,
          probeMigration,
        ])
      },
    })

    expect(db.version).toBe(2)
    expect(Array.from(db.objectStoreNames)).toContain('migrationProbe')
    db.close()
  })
})
