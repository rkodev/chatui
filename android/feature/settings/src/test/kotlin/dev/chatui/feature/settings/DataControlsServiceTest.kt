package dev.chatui.feature.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.database.ChatUiDatabase
import dev.chatui.core.database.attachment.AttachmentStorage
import dev.chatui.core.database.entity.AttachmentEntity
import dev.chatui.core.database.entity.ConversationEntity
import dev.chatui.core.database.entity.MessageEntity
import dev.chatui.core.database.entity.ModelEntity
import dev.chatui.core.database.entity.ProviderEntity
import dev.chatui.core.database.entity.SyncQueueEntity
import dev.chatui.core.datastore.secret.SecretCipher
import dev.chatui.core.datastore.secret.SecretStore
import dev.chatui.core.model.BrowserCors
import dev.chatui.core.model.Provider
import dev.chatui.core.model.ProviderAuthType
import dev.chatui.core.model.ProviderKind
import dev.chatui.feature.providers.ProviderManagementService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataControlsServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var database: ChatUiDatabase
    private lateinit var secretStore: SecretStore
    private lateinit var attachmentStorage: AttachmentStorage
    private lateinit var providerService: ProviderManagementService
    private lateinit var service: DataControlsService

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = ChatUiDatabase.inMemory(context)
        secretStore = SecretStore(
            dao = database.secretDao(),
            cipher = FakeSecretCipher(),
        )
        attachmentStorage = AttachmentStorage(temporaryFolder.newFolder("files"))
        providerService = mockk(relaxed = true)
        service = DataControlsService(
            database = database,
            secrets = secretStore,
            attachmentStorage = attachmentStorage,
            providerService = providerService,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `usage counts records and deduplicates attachment bytes`() = runTest {
        seedData()

        val usage = service.getUsage()

        assertThat(usage.conversations.count).isEqualTo(1)
        assertThat(usage.messages.count).isEqualTo(1)
        assertThat(usage.attachments.count).isEqualTo(2)
        assertThat(usage.attachments.bytes).isEqualTo(ATTACHMENT_BYTES.size.toLong())
        assertThat(usage.secrets.count).isEqualTo(1)
        assertThat(usage.providers.count).isEqualTo(1)
        assertThat(usage.models.count).isEqualTo(1)
        assertThat(usage.totalBytes).isGreaterThan(0L)
    }

    @Test
    fun `clear all removes conversations messages attachments keys and bytes`() = runTest {
        seedData()
        val storageKey = AttachmentStorage.storageKeyFor(sha256(ATTACHMENT_BYTES))
        assertThat(attachmentStorage.fileFor(storageKey).isFile).isTrue()

        val result = service.clearAll()

        assertThat(result.conversations).isEqualTo(1)
        assertThat(result.messages).isEqualTo(1)
        assertThat(result.attachments).isEqualTo(2)
        assertThat(result.secrets).isEqualTo(1)
        assertThat(result.providers).isEqualTo(1)
        assertThat(result.models).isEqualTo(1)

        assertThat(database.conversationDao().all()).isEmpty()
        assertThat(database.messageDao().all()).isEmpty()
        assertThat(database.attachmentDao().all()).isEmpty()
        assertThat(database.providerDao().observeAll().first()).isEmpty()
        assertThat(database.modelDao().all()).isEmpty()
        assertThat(database.secretDao().getAll()).isEmpty()
        assertThat(database.syncQueueDao().all()).isEmpty()
        assertThat(attachmentStorage.fileFor(storageKey).isFile).isFalse()
    }

    @Test
    fun `list provider keys reports stored keys and labels`() = runTest {
        coEvery { providerService.listProviders() } returns listOf(
            provider(
                id = "provider-1",
                label = "Work",
                secretId = "secret-1",
                keyLabel = "Team key",
            ),
            provider(
                id = "provider-2",
                label = "Personal",
                secretId = null,
            ),
        )

        val keys = service.listProviderKeys()

        assertThat(keys).containsExactly(
            ProviderKeySummary(
                id = "provider-1",
                label = "Work",
                presetId = "custom",
                hasKey = true,
                keyLabel = "Team key",
            ),
            ProviderKeySummary(
                id = "provider-2",
                label = "Personal",
                presetId = "custom",
                hasKey = false,
                keyLabel = null,
            ),
        )
    }

    @Test
    fun `remove provider key delegates to provider management`() = runTest {
        coEvery { providerService.removeKey("provider-1") } returns
            provider(id = "provider-1", label = "Work", secretId = null)

        service.removeProviderKey("provider-1")

        coVerify(exactly = 1) { providerService.removeKey("provider-1") }
    }

    private suspend fun seedData() {
        database.conversationDao().upsert(conversation())
        database.messageDao().insert(message(), "hello")
        database.providerDao().upsert(providerEntity())
        database.modelDao().upsert(model())
        database.syncQueueDao().upsert(syncQueue())
        secretStore.put("secret-1", "sk-test")

        val sha = sha256(ATTACHMENT_BYTES)
        val storageKey = attachmentStorage.put(ATTACHMENT_BYTES, sha)
        database.attachmentDao().upsert(attachment("attachment-1", sha, storageKey))
        database.attachmentDao().upsert(attachment("attachment-2", sha, storageKey))
    }

    private fun conversation(): ConversationEntity = ConversationEntity(
        id = "conversation-1",
        title = "Conversation",
        systemPrompt = "",
        providerId = "provider-1",
        modelId = "model-1",
        modelLabel = "Model One",
        paramsJson = "{}",
        pinned = false,
        archived = false,
        temporary = false,
        createdAt = NOW,
        updatedAt = NOW,
        lastMessageAt = NOW,
        messageCount = 1,
        lastErrorJson = null,
        syncState = null,
    )

    private fun message(): MessageEntity = MessageEntity(
        id = "message-1",
        conversationId = "conversation-1",
        sequence = 0,
        role = "user",
        partsJson = """[{"type":"text","text":"hello"}]""",
        status = "complete",
        providerId = null,
        modelId = null,
        modelLabel = null,
        usageJson = null,
        finishReason = null,
        errorJson = null,
        latencyMs = null,
        firstTokenMs = null,
        createdAt = NOW,
        updatedAt = NOW,
        deletedAt = null,
    )

    private fun attachment(
        id: String,
        sha256: String,
        storageKey: String,
    ): AttachmentEntity = AttachmentEntity(
        id = id,
        messageId = "message-1",
        kind = "file",
        mimeType = "text/plain",
        name = "note.txt",
        size = ATTACHMENT_BYTES.size.toLong(),
        sha256 = sha256,
        storageKey = storageKey,
        width = null,
        height = null,
        createdAt = NOW,
    )

    private fun providerEntity(): ProviderEntity = ProviderEntity(
        id = "provider-1",
        presetId = "custom",
        label = "Work",
        kind = "openai-compatible",
        baseUrl = "https://example.test/v1",
        authType = "bearer",
        secretId = "secret-1",
        headersJson = "{}",
        enabled = true,
        browserCors = "unknown",
        metadataJson = null,
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun model(): ModelEntity = ModelEntity(
        id = "model-1",
        providerId = "provider-1",
        remoteId = "remote-1",
        label = "Model One",
        contextWindow = 128_000,
        maxOutputTokens = 4_096,
        capabilitiesJson = """["text"]""",
        pricingJson = null,
        canonicalSlug = null,
        inputModalitiesJson = """["text"]""",
        outputModalitiesJson = """["text"]""",
        supportedParametersJson = "[]",
        metadataJson = null,
        hidden = false,
        pinned = false,
        source = "discovered",
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun syncQueue(): SyncQueueEntity = SyncQueueEntity(
        id = "sync-1",
        conversationId = "conversation-1",
        operation = "upsert",
        payloadJson = "{}",
        attempts = 0,
        lastAttemptAt = null,
        nextAttemptAt = null,
        lastError = null,
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun provider(
        id: String,
        label: String,
        secretId: String?,
        keyLabel: String? = null,
    ): Provider = Provider(
        id = id,
        presetId = "custom",
        label = label,
        kind = ProviderKind.OPENAI_COMPATIBLE,
        baseUrl = "https://example.test/v1",
        authType = ProviderAuthType.BEARER,
        secretId = secretId,
        headers = emptyMap(),
        enabled = true,
        browserCors = BrowserCors.UNKNOWN,
        metadata = keyLabel?.let {
            JsonObject(mapOf("key" to JsonObject(mapOf("label" to JsonPrimitive(it)))))
        },
        createdAt = Instant.parse(NOW),
        updatedAt = Instant.parse(NOW),
    )

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val NOW: String = "2026-11-06T00:00:00Z"
        val ATTACHMENT_BYTES: ByteArray = "attachment-bytes".toByteArray(Charsets.UTF_8)
    }
}

private class FakeSecretCipher : SecretCipher {
    override fun encrypt(plaintext: String): String =
        Base64.getEncoder().encodeToString(plaintext.toByteArray(Charsets.UTF_8))

    override fun decrypt(ciphertext: String): String =
        String(Base64.getDecoder().decode(ciphertext), Charsets.UTF_8)
}
