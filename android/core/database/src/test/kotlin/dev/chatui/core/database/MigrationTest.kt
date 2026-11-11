package dev.chatui.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Migration harness for the current schema.
 *
 * Each test creates the previous schema from the committed export, inserts
 * representative rows, runs the migration, and validates the result against
 * the new exported schema.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {
    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ChatUiDatabase::class.java,
    )

    @Test
    fun `version 1 schema is created and validated`() {
        helper.createDatabase(V1_DB, 1).close()
        helper.runMigrationsAndValidate(V1_DB, 1, true).close()
    }

    @Test
    fun `version 2 has the 1 to 2 migration`() {
        assertThat(CHATUI_DATABASE_VERSION).isEqualTo(2)
        assertThat(MIGRATIONS).hasLength(1)
        assertThat(MIGRATIONS.single()).isEqualTo(MIGRATION_1_2)
    }

    @Test
    fun `migration 1 to 2 preserves data and backfills sync fields`() {
        helper.createDatabase(MIGRATION_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO conversations (" +
                    "id, title, systemPrompt, providerId, modelId, modelLabel, " +
                    "paramsJson, pinned, archived, temporary, createdAt, updatedAt, " +
                    "lastMessageAt, messageCount, lastErrorJson, syncState" +
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    "conversation-1",
                    "Existing conversation",
                    "Be helpful",
                    "provider-1",
                    "model-1",
                    "Model One",
                    "{}",
                    0,
                    0,
                    0,
                    "2026-01-01T00:00:00Z",
                    "2026-01-02T00:00:00Z",
                    "2026-01-02T00:00:00Z",
                    2,
                    null,
                    null,
                ),
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            MIGRATION_DB,
            2,
            true,
            MIGRATION_1_2,
        )

        migrated.query(
            "SELECT title, storage_key, revision FROM conversations WHERE id = ?",
            arrayOf("conversation-1"),
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("Existing conversation")
            val storageKey = cursor.getString(1)
            assertThat(storageKey).matches("^[0-9a-f]{32}$")
            assertThat(cursor.getLong(2)).isEqualTo(1)
        }

        migrated.query("SELECT COUNT(*) FROM tombstones").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(0)
        }
    }

    @Test
    fun `migration 1 to 2 backfills unique storage keys`() {
        helper.createDatabase(UNIQUE_DB, 1).use { db ->
            listOf("conversation-1", "conversation-2").forEach { id ->
                db.execSQL(
                    "INSERT INTO conversations (" +
                        "id, title, systemPrompt, providerId, modelId, modelLabel, " +
                        "paramsJson, pinned, archived, temporary, createdAt, updatedAt, " +
                        "lastMessageAt, messageCount, lastErrorJson, syncState" +
                        ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(
                        id,
                        "Conversation $id",
                        "",
                        "provider-1",
                        "model-1",
                        "Model One",
                        "{}",
                        0,
                        0,
                        0,
                        "2026-01-01T00:00:00Z",
                        "2026-01-01T00:00:00Z",
                        "2026-01-01T00:00:00Z",
                        0,
                        null,
                        null,
                    ),
                )
            }
        }

        val migrated = helper.runMigrationsAndValidate(
            UNIQUE_DB,
            2,
            true,
            MIGRATION_1_2,
        )

        val keys = mutableListOf<String>()
        migrated.query("SELECT storage_key FROM conversations").use { cursor ->
            while (cursor.moveToNext()) {
                keys += cursor.getString(0)
            }
        }
        assertThat(keys).hasSize(2)
        assertThat(keys.toSet()).hasSize(2)
        keys.forEach { key -> assertThat(key).matches("^[0-9a-f]{32}$") }
    }

    private companion object {
        const val V1_DB = "migration-v1-test.db"
        const val MIGRATION_DB = "migration-1-2-test.db"
        const val UNIQUE_DB = "migration-1-2-unique-test.db"
    }
}
