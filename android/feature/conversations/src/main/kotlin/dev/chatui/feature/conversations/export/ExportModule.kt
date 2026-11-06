package dev.chatui.feature.conversations.export

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.chatui.core.database.ChatUiDatabase
import dev.chatui.core.database.attachment.AttachmentStorage
import dev.chatui.core.database.export.ExportReader
import dev.chatui.core.database.repository.AttachmentRepository
import dev.chatui.core.database.repository.BackupConfigRepository
import dev.chatui.core.database.repository.ConversationRepository
import dev.chatui.core.database.repository.MessageRepository
import dev.chatui.core.database.repository.ModelRepository
import dev.chatui.core.database.repository.ProviderRepository
import dev.chatui.core.datastore.settings.SettingsDataStore
import java.time.Clock
import javax.inject.Singleton

/** Application-scoped export reader and service. */
@Module
@InstallIn(SingletonComponent::class)
object ExportModule {
    @Provides
    @Singleton
    fun provideExportReader(
        database: ChatUiDatabase,
        settings: SettingsDataStore,
        clock: Clock,
    ): ExportReader = ExportReader(
        conversations = ConversationRepository(database.conversationDao(), clock),
        messages = MessageRepository(database.messageDao(), clock),
        attachments = AttachmentRepository(database.attachmentDao()),
        providers = ProviderRepository(database.providerDao(), clock),
        models = ModelRepository(database.modelDao()),
        settings = AndroidExportSettingsSource(
            settings = settings,
            backupConfigs = BackupConfigRepository(database.backupConfigDao()),
        ),
    )

    @Provides
    @Singleton
    fun provideExportService(
        reader: ExportReader,
        @ApplicationContext context: Context,
        clock: Clock,
    ): ExportService = ExportService(
        reader = reader,
        attachmentBytes = AttachmentStorageByteReader(AttachmentStorage(context.filesDir)),
        now = { clock.instant().toString() },
    )
}

/** Entry point used by the shell to obtain the export service without Hilt VMs. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ExportEntryPoint {
    fun exportService(): ExportService
}
