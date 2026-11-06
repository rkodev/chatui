package dev.chatui.core.database.import

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.database.DatabaseTestBase
import dev.chatui.core.database.attachment.AttachmentStorage
import dev.chatui.core.database.repository.RepositoryTestData
import dev.chatui.core.database.repository.toEntity
import dev.chatui.core.model.ChatParams
import dev.chatui.core.model.ModelSource
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportWriterTest : DatabaseTestBase() {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var storage: AttachmentStorage
    private lateinit var writer: ImportWriter

    private val bytes = "hello".toByteArray()
    private val sha256 = sha256Hex(bytes)
    private val storageKey = AttachmentStorage.storageKeyFor(sha256)

    private val provider = RepositoryTestData.provider(
        id = "provider-1",
        secretId = "secret-1",
    )
    private val model = RepositoryTestData.model(
        id = "model-1",
        providerId = "provider-1",
        remoteId = "remote-1",
    )
    private val conversation = RepositoryTestData.conversation(
        id = "conversation-1",
        providerId = "provider-1",
        modelId = "model-1",
    )
    private val message = RepositoryTestData.message(
        id = "message-1",
        conversationId = "conversation-1",
        sequence = 0,
    )
    private val attachment = RepositoryTestData.attachment(
        id = "attachment-1",
        messageId = "message-1",
        sha256 = sha256,
        storageKey = storageKey,
    )

    private fun setUpWriter() {
        storage = AttachmentStorage(temporaryFolder.newFolder("attachments-root"))
        writer = ImportWriter(db, storage)
    }

    @Test
    fun `merge writes every record and preserves ids`() = runBlocking {
        setUpWriter()

        val result = writer.apply(
            request = request(),
            mode = ImportMode.MERGE,
        )

        assertThat(result.conversations).isEqualTo(1)
        assertThat(result.messages).isEqualTo(1)
        assertThat(result.attachments).isEqualTo(1)
        assertThat(result.providers).isEqualTo(1)
        assertThat(result.models).isEqualTo(1)
        assertThat(result.attachmentBytes).isEqualTo(1)

        assertThat(db.providerDao().getById("provider-1")).isNotNull()
        assertThat(db.modelDao().getById("model-1")).isNotNull()
        assertThat(db.conversationDao().getById("conversation-1")).isNotNull()
        assertThat(db.messageDao().getById("message-1")).isNotNull()
        assertThat(db.attachmentDao().getById("attachment-1")).isNotNull()
        assertThat(storage.read(storageKey)).isEqualTo(bytes)
    }

    @Test
    fun `replace clears local records and orphan bytes`() = runBlocking {
        setUpWriter()
        val oldBytes = "old".toByteArray()
        val oldSha = sha256Hex(oldBytes)
        val oldStorageKey = AttachmentStorage.storageKeyFor(oldSha)
        storage.put(oldBytes, oldSha)

        db.providerDao().upsert(
            RepositoryTestData.provider(id = "local-provider").toEntity(),
        )
        db.modelDao().upsert(
            RepositoryTestData.model(
                id = "local-model",
                providerId = "local-provider",
                remoteId = "local-remote",
            ).toEntity(),
        )
        db.conversationDao().upsert(
            RepositoryTestData.conversation(
                id = "local-conversation",
                providerId = "local-provider",
                modelId = "local-model",
            ).toEntity(),
        )
        db.messageDao().insert(
            RepositoryTestData.message(
                id = "local-message",
                conversationId = "local-conversation",
            ).toEntity(),
            "local",
        )
        db.attachmentDao().upsert(
            RepositoryTestData.attachment(
                id = "local-attachment",
                messageId = "local-message",
                sha256 = oldSha,
                storageKey = oldStorageKey,
            ).toEntity(),
        )

        writer.apply(request = request(), mode = ImportMode.REPLACE)

        assertThat(db.providerDao().getById("local-provider")).isNull()
        assertThat(db.modelDao().getById("local-model")).isNull()
        assertThat(db.conversationDao().getById("local-conversation")).isNull()
        assertThat(db.messageDao().getById("local-message")).isNull()
        assertThat(db.attachmentDao().getById("local-attachment")).isNull()
        assertThat(storage.read(oldStorageKey)).isNull()

        assertThat(db.providerDao().getById("provider-1")).isNotNull()
        assertThat(db.conversationDao().getById("conversation-1")).isNotNull()
        assertThat(storage.read(storageKey)).isEqualTo(bytes)
    }

    @Test
    fun `merge preserves local provider secret and model flags`() = runBlocking {
        setUpWriter()
        db.providerDao().upsert(
            RepositoryTestData.provider(
                id = "provider-1",
                secretId = "local-secret",
                enabled = false,
            ).toEntity(),
        )
        db.modelDao().upsert(
            RepositoryTestData.model(
                id = "model-1",
                providerId = "provider-1",
                remoteId = "remote-1",
                hidden = true,
                pinned = true,
                source = ModelSource.MANUAL,
            ).toEntity(),
        )

        writer.apply(
            request = request().copy(
                providers = listOf(
                    provider.copy(secretId = "archive-secret", enabled = true),
                ),
                models = listOf(
                    model.copy(hidden = false, pinned = false, source = ModelSource.DISCOVERED),
                ),
            ),
            mode = ImportMode.MERGE,
        )

        val storedProvider = db.providerDao().getById("provider-1")!!
        assertThat(storedProvider.secretId).isEqualTo("local-secret")
        assertThat(storedProvider.enabled).isFalse()

        val storedModel = db.modelDao().getById("model-1")!!
        assertThat(storedModel.hidden).isTrue()
        assertThat(storedModel.pinned).isTrue()
        assertThat(storedModel.source).isEqualTo("manual")
    }

    @Test
    fun `failed transaction rolls back and removes newly written bytes`() = runBlocking {
        setUpWriter()
        val invalidConversation = conversation.copy(
            params = ChatParams(
                temperature = Double.NaN,
                topP = null,
                maxOutputTokens = null,
                stop = emptyList(),
                reasoningEffort = null,
                streaming = true,
                extra = JsonObject(emptyMap()),
            ),
        )

        assertThrows(Throwable::class.java) {
            runBlocking {
                writer.apply(
                    request = request().copy(conversations = listOf(invalidConversation)),
                    mode = ImportMode.MERGE,
                )
            }
        }

        assertThat(db.providerDao().getById("provider-1")).isNull()
        assertThat(db.modelDao().getById("model-1")).isNull()
        assertThat(db.conversationDao().getById("conversation-1")).isNull()
        assertThat(db.messageDao().getById("message-1")).isNull()
        assertThat(db.attachmentDao().getById("attachment-1")).isNull()
        assertThat(storage.read(storageKey)).isNull()
    }

    private fun request(): ImportWriteRequest = ImportWriteRequest(
        providers = listOf(provider),
        models = listOf(model),
        conversations = listOf(conversation),
        messages = listOf(message),
        attachments = listOf(attachment),
        attachmentBytes = mapOf(sha256 to bytes),
    )

    private companion object {
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
