package dev.chatui.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.chatui.core.model.newStorageKey

/**
 * Adds sync storage keys, revisions, and deletion tombstones.
 *
 * SQLite cannot generate random hex values in a portable `ALTER TABLE`
 * default, so existing conversations are backfilled in Kotlin after the
 * columns are added. The migration runs inside Room's transaction, so a
 * failure rolls the whole upgrade back.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE conversations ADD COLUMN storage_key TEXT NOT NULL DEFAULT ''",
        )
        db.execSQL(
            "ALTER TABLE conversations ADD COLUMN revision INTEGER NOT NULL DEFAULT 1",
        )
        backfillStorageKeys(db)

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tombstones` (" +
                "`id` TEXT NOT NULL, " +
                "`conversationId` TEXT NOT NULL, " +
                "`messageId` TEXT, " +
                "`deletedAt` TEXT NOT NULL, " +
                "`deviceId` TEXT NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tombstones_conversationId` " +
                "ON `tombstones` (`conversationId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tombstones_messageId` " +
                "ON `tombstones` (`messageId`)",
        )
    }
}

/**
 * Ordered Room migrations.
 *
 * Version 1 is the initial schema. Every schema change must add a
 * `Migration(oldVersion, newVersion)` here and a `MigrationTestHelper` test in
 * `MigrationTest.kt`. Destructive migration is intentionally never configured.
 */
val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

private fun backfillStorageKeys(db: SupportSQLiteDatabase) {
    val cursor = db.query("SELECT id FROM conversations WHERE storage_key = ''")
    cursor.use {
        while (it.moveToNext()) {
            val id = it.getString(0)
            db.execSQL(
                "UPDATE conversations SET storage_key = ? WHERE id = ?",
                arrayOf(newStorageKey(), id),
            )
        }
    }
}
