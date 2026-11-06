package dev.chatui.feature.conversations.importer

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.chatui.core.database.ChatUiDatabase
import dev.chatui.core.database.attachment.AttachmentStorage
import dev.chatui.core.datastore.settings.SettingsDataStore
import java.time.Clock
import javax.inject.Singleton

/** Application-scoped import service. */
@Module
@InstallIn(SingletonComponent::class)
object ImportModule {
    @Provides
    @Singleton
    fun provideImportSettingsStore(settings: SettingsDataStore): ImportSettingsStore =
        DataStoreImportSettingsStore(settings)

    @Provides
    @Singleton
    fun provideImportService(
        database: ChatUiDatabase,
        @ApplicationContext context: Context,
        settingsStore: ImportSettingsStore,
        clock: Clock,
    ): ImportService = ImportService(
        database = database,
        attachmentStorage = AttachmentStorage(context.filesDir),
        settingsStore = settingsStore,
        now = { clock.instant().toString() },
    )
}

/** Entry point used by the shell to obtain the import service without Hilt VMs. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ImportEntryPoint {
    fun importService(): ImportService
}
