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
 * Version 1 is the initial schema, so the test creates it from the committed
 * export and validates it. Future migrations should add a test that creates the
 * previous version, inserts representative rows, runs
 * [MigrationTestHelper.runMigrationsAndValidate] with the new migration, and
 * asserts the rows survived.
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
        helper.createDatabase(TEST_DB, 1).close()
        helper.runMigrationsAndValidate(TEST_DB, 1, true).close()
    }

    @Test
    fun `version 1 has no migrations yet`() {
        assertThat(CHATUI_DATABASE_VERSION).isEqualTo(1)
        assertThat(MIGRATIONS).isEmpty()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
