package dev.chatui.feature.chat

import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.chatui.core.database.repository.ConversationRepository
import dev.chatui.core.database.repository.MessageRepository
import dev.chatui.core.database.repository.ModelRepository
import dev.chatui.core.database.repository.ProviderRepository
import dev.chatui.core.datastore.secret.SecretStore
import dev.chatui.core.datastore.settings.SettingsDataStore
import dev.chatui.core.network.ProviderAdapter
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
)

/** Builds the chat dependency bundle from the singleton graph. */
@Module
@InstallIn(SingletonComponent::class)
object ChatModule {
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
        drafts = InMemoryChatDrafts(),
        clock = clock,
    )
}

/** Entry point used by the shell to obtain chat dependencies without Hilt VMs. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ChatEntryPoint {
    fun chatDependencies(): ChatDependencies
}
