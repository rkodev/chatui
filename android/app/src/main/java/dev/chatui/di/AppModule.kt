package dev.chatui.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.chatui.core.database.ChatUiDatabase
import dev.chatui.core.database.repository.ConversationRepository
import dev.chatui.core.database.repository.MessageRepository
import dev.chatui.core.database.repository.ModelRepository
import dev.chatui.core.database.repository.ProviderRepository
import dev.chatui.core.database.recovery.InterruptedMessageRecovery
import dev.chatui.core.database.recovery.StreamingRecoveryService
import dev.chatui.core.datastore.secret.SecretCipher
import dev.chatui.core.datastore.secret.SecretStore
import dev.chatui.core.datastore.secret.TinkSecretCipher
import dev.chatui.core.datastore.settings.SettingsDataStore
import dev.chatui.core.network.OpenRouterAdapter
import dev.chatui.core.network.ProviderAdapter
import dev.chatui.core.network.ProviderAdapterRegistry
import dev.chatui.core.network.ProviderRegistry
import dev.chatui.core.network.RoutingProviderAdapter
import dev.chatui.core.providers.ModelService
import dev.chatui.core.providers.ProviderGateway
import dev.chatui.core.providers.ProviderService
import dev.chatui.core.providers.RoutingProviderGateway
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient

/**
 * Application-scoped dependencies for the shell and M1 provider services.
 *
 * Every binding is a singleton because the database, secret keyset, HTTP
 * connection pool, and catalog cache must be shared across the process.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): SettingsDataStore =
        SettingsDataStore.create(context)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ChatUiDatabase =
        ChatUiDatabase.create(context)

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemUTC()

    @Provides
    @Singleton
    fun provideSecretCipher(@ApplicationContext context: Context): SecretCipher =
        TinkSecretCipher.androidKeystore(context)

    @Provides
    @Singleton
    fun provideSecretStore(
        database: ChatUiDatabase,
        cipher: SecretCipher,
        clock: Clock,
    ): SecretStore = SecretStore(
        dao = database.secretDao(),
        cipher = cipher,
        clock = { clock.instant() },
    )

    /**
     * Shared HTTP client for JSON requests.
     *
     * Streaming uses a dedicated client inside [OpenRouterAdapter] with read
     * and call timeouts disabled plus a 120 s inactivity watchdog. No logging
     * interceptor is installed, so request headers and secrets are never
     * written to logcat.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideProviderRegistry(): ProviderRegistry = ProviderRegistry.load()

    @Provides
    @Singleton
    fun provideOpenRouterAdapter(client: OkHttpClient): OpenRouterAdapter =
        OpenRouterAdapter(client)

    @Provides
    @Singleton
    fun provideProviderAdapterRegistry(client: OkHttpClient): ProviderAdapterRegistry =
        ProviderAdapterRegistry.create(client)

    @Provides
    @Singleton
    fun provideProviderAdapter(registry: ProviderAdapterRegistry): ProviderAdapter =
        RoutingProviderAdapter(registry)

    @Provides
    @Singleton
    fun provideProviderGateway(registry: ProviderAdapterRegistry): ProviderGateway =
        RoutingProviderGateway(registry)

    @Provides
    @Singleton
    fun provideConversationRepository(
        database: ChatUiDatabase,
        clock: Clock,
    ): ConversationRepository = ConversationRepository(database.conversationDao(), clock)

    @Provides
    @Singleton
    fun provideMessageRepository(
        database: ChatUiDatabase,
        clock: Clock,
    ): MessageRepository = MessageRepository(database.messageDao(), clock)

    @Provides
    @Singleton
    fun provideInterruptedMessageRecovery(
        messages: MessageRepository,
        clock: Clock,
    ): InterruptedMessageRecovery = StreamingRecoveryService(messages, clock)

    @Provides
    @Singleton
    fun provideProviderRepository(
        database: ChatUiDatabase,
        clock: Clock,
    ): ProviderRepository = ProviderRepository(database.providerDao(), clock)

    @Provides
    @Singleton
    fun provideModelRepository(database: ChatUiDatabase): ModelRepository =
        ModelRepository(database.modelDao())

    @Provides
    @Singleton
    fun provideProviderService(
        providers: ProviderRepository,
        secrets: SecretStore,
        gateway: ProviderGateway,
        registry: ProviderRegistry,
        clock: Clock,
    ): ProviderService = ProviderService(
        providers = providers,
        secrets = secrets,
        gateway = gateway,
        registry = registry,
        clock = clock,
    )

    @Provides
    @Singleton
    fun provideModelService(
        providers: ProviderRepository,
        models: ModelRepository,
        gateway: ProviderGateway,
        registry: ProviderRegistry,
        clock: Clock,
    ): ModelService = ModelService(
        providers = providers,
        models = models,
        gateway = gateway,
        registry = registry,
        clock = clock,
    )
}
