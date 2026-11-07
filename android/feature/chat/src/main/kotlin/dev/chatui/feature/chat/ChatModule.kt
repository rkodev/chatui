package dev.chatui.feature.chat

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.chatui.core.database.ChatUiDatabase
import dev.chatui.core.database.attachment.AndroidImageProcessor
import dev.chatui.core.database.attachment.AttachmentService
import dev.chatui.core.database.attachment.AttachmentStorage
import dev.chatui.core.database.repository.AttachmentRepository
import dev.chatui.core.database.repository.ConversationRepository
import dev.chatui.core.database.repository.MessageRepository
import dev.chatui.core.database.repository.ModelRepository
import dev.chatui.core.database.repository.ProviderRepository
import dev.chatui.core.datastore.secret.SecretStore
import dev.chatui.core.datastore.settings.SettingsDataStore
import dev.chatui.core.network.ProviderAdapter
import java.io.File
import java.time.Clock
import javax.inject.Singleton

/** Application-scoped dependencies needed to render one conversation. */
data class ChatDependencies(
    val conversationSource: ChatConversationSource,
    val messageStore: ChatMessageStore,
    val contextResolver: ChatContextResolver,
    val adapter: ProviderAdapter,
    val settings: SettingsDataStore,
    val drafts: ChatDrafts,
    val clock: Clock,
    val attachmentStore: ChatAttachmentStore? = null,
    val attachmentRoot: File? = null,
)

/** Builds the chat dependency bundle from the singleton graph. */
@Module
@InstallIn(SingletonComponent::class)
object ChatModule {
    @Provides
    @Singleton
    fun provideAttachmentRepository(database: ChatUiDatabase): AttachmentRepository =
        AttachmentRepository(database.attachmentDao())

    @Provides
    @Singleton
    fun provideAttachmentService(
        @ApplicationContext context: Context,
        attachments: AttachmentRepository,
        messages: MessageRepository,
        clock: Clock,
    ): AttachmentService = AttachmentService(
        repository = attachments,
        messages = messages,
        storage = AttachmentStorage(context.filesDir),
        imageProcessor = AndroidImageProcessor(),
        clock = clock,
    )

    @Provides
    @Singleton
    fun provideChatAttachmentStore(service: AttachmentService): ChatAttachmentStore =
        RepositoryChatAttachmentStore(service)

    @Provides
    @Singleton
    fun provideChatDependencies(
        conversations: ConversationRepository,
        messages: MessageRepository,
        providers: ProviderRepository,
        models: ModelRepository,
        secrets: SecretStore,
        settings: SettingsDataStore,
        adapter: ProviderAdapter,
        clock: Clock,
        attachmentStore: ChatAttachmentStore,
        @ApplicationContext context: Context,
    ): ChatDependencies = ChatDependencies(
        conversationSource = RepositoryChatConversationSource(conversations),
        messageStore = RepositoryChatMessageStore(messages),
        contextResolver = DefaultChatContextResolver(
            providers = providers,
            models = models,
            secrets = secrets,
            clock = clock,
        ),
        adapter = adapter,
        settings = settings,
        drafts = PersistentChatDrafts.create(
            persistence = SettingsChatDraftPersistence(settings),
        ),
        clock = clock,
        attachmentStore = attachmentStore,
        attachmentRoot = context.filesDir,
    )
}

/** Entry point used by the shell to obtain chat dependencies without Hilt VMs. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ChatEntryPoint {
    fun chatDependencies(): ChatDependencies
}
