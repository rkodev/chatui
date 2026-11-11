package dev.chatui.feature.backup

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.chatui.core.database.ChatUiDatabase
import dev.chatui.core.database.attachment.AttachmentStorage
import dev.chatui.core.database.repository.BackupConfigRepository
import dev.chatui.core.database.sync.RoomSyncLocalStore
import dev.chatui.core.datastore.secret.SecretStore
import javax.inject.Singleton

/** Application-scoped backup settings service. */
@Module
@InstallIn(SingletonComponent::class)
object BackupModule {
    @Provides
    @Singleton
    fun provideBackupSettingsService(
        database: ChatUiDatabase,
        secrets: SecretStore,
        @ApplicationContext context: Context,
    ): BackupSettingsService = BackupSettingsService(
        store = RoomSyncLocalStore(
            database = database,
            attachmentStorage = AttachmentStorage(context.filesDir),
        ),
        secrets = secrets,
        configRepository = BackupConfigRepository(database.backupConfigDao()),
        statusSource = RoomBackupStatusSource(database),
    )
}

/** Entry point used by the shell to obtain the backup service without Hilt VMs. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface BackupEntryPoint {
    fun backupSettingsService(): BackupSettingsService
}
