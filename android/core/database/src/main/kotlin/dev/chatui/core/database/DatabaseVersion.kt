package dev.chatui.core.database

/**
 * Room schema version. Bump this and add a [androidx.room.migration.Migration]
 * in `Migrations.kt` for every schema change.
 */
const val CHATUI_DATABASE_VERSION: Int = 2

/**
 * Export/import format version. This is deliberately independent of
 * [CHATUI_DATABASE_VERSION]: a Room migration must not silently change the
 * on-disk export contract, and an export format change must not force a Room
 * migration.
 */
const val CHATUI_EXPORT_FORMAT_VERSION: Int = 1
