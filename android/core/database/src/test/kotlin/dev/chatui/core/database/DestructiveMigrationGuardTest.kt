package dev.chatui.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves the production builder does not enable destructive migration.
 *
 * A database whose `user_version` is newer than the Room version must fail to
 * open. If destructive migration were configured, Room would silently drop and
 * recreate the schema instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DestructiveMigrationGuardTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun cleanBefore() {
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun cleanAfter() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun `newer schema without a migration fails instead of deleting data`() {
        val initial = ChatUiDatabase.builder(context, DB_NAME).build()
        try {
            initial.openHelper.writableDatabase
        } finally {
            initial.close()
        }

        val path = context.getDatabasePath(DB_NAME).absolutePath
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL("PRAGMA user_version = 2")
        }

        val reopened = ChatUiDatabase.builder(context, DB_NAME).build()
        try {
            assertThrows(IllegalStateException::class.java) {
                reopened.openHelper.writableDatabase
            }
        } finally {
            reopened.close()
        }
    }

    private companion object {
        const val DB_NAME = "destructive-migration-guard.db"
    }
}
