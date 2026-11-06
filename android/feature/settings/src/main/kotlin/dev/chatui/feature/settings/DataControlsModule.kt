package dev.chatui.feature.settings

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.chatui.core.database.ChatUiDatabase
import dev.chatui.core.database.attachment.AttachmentStorage
import dev.chatui.core.datastore.secret.SecretStore
import dev.chatui.feature.providers.ProviderManagementService
import javax.inject.Singleton

/** Application-scoped data controls for the settings data screen. */
@Module
@InstallIn(SingletonComponent::class)
object DataControlsModule {
    @Provides
    @Singleton
    fun provideDataControlsService(
        database: ChatUiDatabase,
        secrets: SecretStore,
        providerService: ProviderManagementService,
        @ApplicationContext context: Context,
    ): DataControlsService = DataControlsService(
        database = database,
        secrets = secrets,
        attachmentStorage = AttachmentStorage(context.filesDir),
        providerService = providerService,
    )
}
