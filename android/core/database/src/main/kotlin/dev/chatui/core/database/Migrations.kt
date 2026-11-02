package dev.chatui.core.database

import androidx.room.migration.Migration

/**
 * Ordered Room migrations.
 *
 * Version 1 is the initial schema, so there are no migrations yet. Every future
 * schema change must add a `Migration(oldVersion, newVersion)` here and a
 * `MigrationTestHelper` test in `MigrationTest.kt`. Destructive migration is
 * intentionally never configured.
 */
val MIGRATIONS: Array<Migration> = emptyArray()
