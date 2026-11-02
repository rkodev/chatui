package dev.chatui.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dev.chatui.core.database.dao.AttachmentDao
import dev.chatui.core.database.dao.BackupConfigDao
import dev.chatui.core.database.dao.ConversationDao
import dev.chatui.core.database.dao.MessageDao
import dev.chatui.core.database.dao.ModelDao
import dev.chatui.core.database.dao.ProviderDao
import dev.chatui.core.database.dao.SecretDao
import dev.chatui.core.database.dao.SyncQueueDao
import dev.chatui.core.database.entity.AttachmentEntity
import dev.chatui.core.database.entity.BackupConfigEntity
import dev.chatui.core.database.entity.ConversationEntity
import dev.chatui.core.database.entity.MessageEntity
import dev.chatui.core.database.entity.MessageFtsEntity
import dev.chatui.core.database.entity.ModelEntity
import dev.chatui.core.database.entity.ProviderEntity
import dev.chatui.core.database.entity.SecretEntity
import dev.chatui.core.database.entity.SyncQueueEntity

/**
 * Room database for spec 9.5.
 *
 * No UI or feature module may depend on this class directly; repositories in
 * later milestones own the mapping between entities and domain models.
 *
 * Destructive migration is deliberately absent. A missing migration must fail
 * loudly in every build type rather than silently deleting user data.
 */
@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        AttachmentEntity::class,
        ProviderEntity::class,
        ModelEntity::class,
        SecretEntity::class,
        BackupConfigEntity::class,
        SyncQueueEntity::class,
        MessageFtsEntity::class,
    ],
    version = CHATUI_DATABASE_VERSION,
    exportSchema = true,
)
abstract class ChatUiDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao

    abstract fun messageDao(): MessageDao

    abstract fun attachmentDao(): AttachmentDao

    abstract fun providerDao(): ProviderDao

    abstract fun modelDao(): ModelDao

    abstract fun secretDao(): SecretDao

    abstract fun backupConfigDao(): BackupConfigDao

    abstract fun syncQueueDao(): SyncQueueDao

    companion object {
        const val NAME: String = "chatui.db"

        /**
         * Shared builder so production and tests use the same migration
         * configuration. The builder never enables destructive migration.
         */
        fun builder(
            context: Context,
            name: String = NAME,
        ): RoomDatabase.Builder<ChatUiDatabase> =
            Room.databaseBuilder(context, ChatUiDatabase::class.java, name)
                .addMigrations(*MIGRATIONS)

        fun create(context: Context): ChatUiDatabase = builder(context).build()

        fun inMemory(context: Context): ChatUiDatabase =
            Room.inMemoryDatabaseBuilder(context, ChatUiDatabase::class.java)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
