package dev.chatui.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dagger.Module
import dagger.Provides
import dev.chatui.core.database.ChatUiDatabase
import dev.chatui.core.database.repository.ConversationRepository
import dev.chatui.core.database.repository.MessageRepository
import dev.chatui.core.database.repository.ModelRepository
import dev.chatui.core.database.repository.ProviderRepository
import dev.chatui.core.database.recovery.StreamingRecoveryService
import dev.chatui.core.datastore.secret.SecretCipher
import dev.chatui.core.datastore.secret.SecretStore
import dev.chatui.core.network.ProviderRegistry
import dev.chatui.core.providers.ModelService
import dev.chatui.core.providers.ProviderGateway
import dev.chatui.core.providers.ProviderService
import io.mockk.mockk
import java.time.ZoneOffset
import java.util.Base64
import javax.inject.Singleton
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppModuleTest {
    private lateinit var context: Context
    private lateinit var database: ChatUiDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = ChatUiDatabase.inMemory(context)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `provides the typed settings store`() {
        assertThat(AppModule.provideSettingsDataStore(context)).isNotNull()
    }

    @Test
    fun `provides the database`() {
        val provided = AppModule.provideDatabase(context)

        assertThat(provided).isNotNull()
        assertThat(provided.providerDao()).isNotNull()
        provided.close()
    }

    @Test
    fun `provides a UTC clock`() {
        assertThat(AppModule.provideClock().zone).isEqualTo(ZoneOffset.UTC)
    }

    @Test
    fun `provides the secret store`() {
        val store = AppModule.provideSecretStore(
            database = database,
            cipher = FakeSecretCipher(),
            clock = AppModule.provideClock(),
        )

        assertThat(store).isNotNull()
    }

    @Test
    fun `provides a shared HTTP client without logging interceptors`() {
        val client = AppModule.provideOkHttpClient()

        assertThat(client.connectTimeoutMillis).isEqualTo(30_000)
        assertThat(client.readTimeoutMillis).isEqualTo(30_000)
        assertThat(client.callTimeoutMillis).isEqualTo(60_000)
        assertThat(client.interceptors).isEmpty()
        assertThat(client.networkInterceptors).isEmpty()
    }

    @Test
    fun `provides the provider registry with OpenRouter enabled`() {
        val registry = AppModule.provideProviderRegistry()

        assertThat(registry.getPreset("openrouter")).isNotNull()
        assertThat(registry.getEnabledPresets().map { it.id }).containsExactly("openrouter")
    }

    @Test
    fun `provides the adapter and gateway`() {
        val adapter = AppModule.provideOpenRouterAdapter(AppModule.provideOkHttpClient())

        assertThat(AppModule.provideProviderAdapter(adapter)).isSameInstanceAs(adapter)
        assertThat(AppModule.provideProviderGateway(adapter)).isNotNull()
    }

    @Test
    fun `provides repositories`() {
        val clock = AppModule.provideClock()

        assertThat(AppModule.provideConversationRepository(database, clock))
            .isInstanceOf(ConversationRepository::class.java)
        assertThat(AppModule.provideMessageRepository(database, clock))
            .isInstanceOf(MessageRepository::class.java)
        assertThat(AppModule.provideProviderRepository(database, clock))
            .isInstanceOf(ProviderRepository::class.java)
        assertThat(AppModule.provideModelRepository(database))
            .isInstanceOf(ModelRepository::class.java)
    }

    @Test
    fun `provides interrupted message recovery`() {
        val clock = AppModule.provideClock()

        val recovery = AppModule.provideInterruptedMessageRecovery(
            messages = AppModule.provideMessageRepository(database, clock),
            clock = clock,
        )

        assertThat(recovery).isInstanceOf(StreamingRecoveryService::class.java)
    }

    @Test
    fun `provides provider and model services`() {
        val clock = AppModule.provideClock()
        val registry = AppModule.provideProviderRegistry()
        val gateway = mockk<ProviderGateway>()
        val providerRepository = AppModule.provideProviderRepository(database, clock)
        val modelRepository = AppModule.provideModelRepository(database)
        val secretStore = AppModule.provideSecretStore(
            database = database,
            cipher = FakeSecretCipher(),
            clock = clock,
        )

        assertThat(
            AppModule.provideProviderService(
                providers = providerRepository,
                secrets = secretStore,
                gateway = gateway,
                registry = registry,
                clock = clock,
            ),
        ).isInstanceOf(ProviderService::class.java)

        assertThat(
            AppModule.provideModelService(
                providers = providerRepository,
                models = modelRepository,
                gateway = gateway,
                registry = registry,
                clock = clock,
            ),
        ).isInstanceOf(ModelService::class.java)
    }

    @Test
    fun `module is a Hilt module`() {
        assertThat(AppModule::class.java.isAnnotationPresent(Module::class.java)).isTrue()
    }

    @Test
    fun `every provider is singleton scoped`() {
        val providers = AppModule::class.java.declaredMethods.filter { method ->
            method.isAnnotationPresent(Provides::class.java)
        }

        assertThat(providers).isNotEmpty()
        providers.forEach { method ->
            assertThat(method.isAnnotationPresent(Singleton::class.java)).isTrue()
        }
    }
}

private class FakeSecretCipher : SecretCipher {
    override fun encrypt(plaintext: String): String =
        Base64.getEncoder().encodeToString(plaintext.toByteArray(Charsets.UTF_8))

    override fun decrypt(ciphertext: String): String =
        String(Base64.getDecoder().decode(ciphertext), Charsets.UTF_8)
}
