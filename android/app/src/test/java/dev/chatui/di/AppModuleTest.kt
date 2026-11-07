package dev.chatui.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dagger.Module
import dagger.Provides
import dev.chatui.core.database.ChatUiDatabase
import dev.chatui.core.database.attachment.AttachmentService
import dev.chatui.core.database.repository.ConversationRepository
import dev.chatui.core.database.repository.MessageRepository
import dev.chatui.core.database.repository.ModelRepository
import dev.chatui.core.database.repository.ProviderRepository
import dev.chatui.core.database.recovery.StreamingRecoveryService
import dev.chatui.core.datastore.secret.SecretCipher
import dev.chatui.core.datastore.secret.SecretStore
import dev.chatui.core.model.ProviderKind
import dev.chatui.core.network.CleartextPolicyInterceptor
import dev.chatui.core.network.ProviderRegistry
import dev.chatui.core.providers.ModelService
import dev.chatui.core.providers.ProviderGateway
import dev.chatui.core.providers.ProviderService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import javax.inject.Singleton
import kotlinx.coroutines.test.runTest
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
    fun `provides a shared HTTP client with only the cleartext policy interceptor`() {
        val client = AppModule.provideOkHttpClient()

        assertThat(client.connectTimeoutMillis).isEqualTo(30_000)
        assertThat(client.readTimeoutMillis).isEqualTo(30_000)
        assertThat(client.callTimeoutMillis).isEqualTo(60_000)
        assertThat(client.interceptors).hasSize(1)
        assertThat(client.interceptors[0]).isInstanceOf(CleartextPolicyInterceptor::class.java)
        assertThat(client.networkInterceptors).isEmpty()
        assertThat(client.hostnameVerifier.javaClass.name)
            .isEqualTo("okhttp3.internal.tls.OkHostnameVerifier")
        assertThat(client.sslSocketFactory).isNotNull()
    }

    @Test
    fun `shared HTTP client source has no custom trust manager or logging interceptor`() {
        val source = appModuleSource()
        val body = source
            .substringAfter("fun provideOkHttpClient()")
            .substringBefore("@Provides")

        assertThat(body).contains("addInterceptor(CleartextPolicyInterceptor())")
        assertThat(body).doesNotContain("sslSocketFactory")
        assertThat(body).doesNotContain("hostnameVerifier")
        assertThat(body).doesNotContain("trustManager")
        assertThat(body).doesNotContain("networkInterceptors")
        assertThat(body).doesNotContain(".interceptors(")
        assertThat(body).doesNotContain("HttpLoggingInterceptor")
        assertThat(body).doesNotContain("logging-interceptor")
    }

    private fun appModuleSource(): String {
        val candidates = listOf(
            File("src/main/java/dev/chatui/di/AppModule.kt"),
            File("android/app/src/main/java/dev/chatui/di/AppModule.kt"),
        )
        return candidates.first { it.isFile }.readText()
    }

    @Test
    fun `provides the provider registry with M2 presets enabled`() {
        val registry = AppModule.provideProviderRegistry()
        val enabled = registry.getEnabledPresets().map { it.id }

        assertThat(registry.getPreset("openrouter")).isNotNull()
        assertThat(enabled).contains("openai")
        assertThat(enabled).contains("custom")
        assertThat(enabled).contains("anthropic")
    }

    @Test
    fun `provides the adapter registry, routing adapter, and gateway`() {
        val client = AppModule.provideOkHttpClient()
        val registry = AppModule.provideProviderAdapterRegistry(client)

        assertThat(registry.adapterFor(ProviderKind.ANTHROPIC)).isNotNull()
        assertThat(registry.adapterFor(ProviderKind.GOOGLE_GEMINI)).isNotNull()
        assertThat(AppModule.provideProviderAdapter(registry)).isNotNull()
        assertThat(AppModule.provideProviderGateway(registry)).isNotNull()
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
    fun `provides attachment cleanup backed by the attachment service`() = runTest {
        val service = mockk<AttachmentService>()
        val before = Instant.parse("2026-11-06T12:00:00Z")
        coEvery { service.cleanupOrphans(before) } returns listOf("attachments/orphan")

        val cleanup = AppModule.provideAttachmentCleanup(service)

        assertThat(cleanup.cleanupOrphans(before)).containsExactly("attachments/orphan")
        coVerify { service.cleanupOrphans(before) }
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
