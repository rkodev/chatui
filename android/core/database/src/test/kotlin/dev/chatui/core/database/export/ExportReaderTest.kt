package dev.chatui.core.database.export

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.database.DatabaseTestBase
import dev.chatui.core.database.TestData
import dev.chatui.core.database.repository.AttachmentRepository
import dev.chatui.core.database.repository.ConversationRepository
import dev.chatui.core.database.repository.MessageRepository
import dev.chatui.core.database.repository.ModelRepository
import dev.chatui.core.database.repository.ProviderRepository
import dev.chatui.core.database.repository.RepositoryTestData
import dev.chatui.core.model.AttachmentKind
import dev.chatui.core.model.ContentPart
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportReaderTest : DatabaseTestBase() {
    private lateinit var conversations: ConversationRepository
    private lateinit var messages: MessageRepository
    private lateinit var attachments: AttachmentRepository
    private lateinit var providers: ProviderRepository
    private lateinit var models: ModelRepository
    private lateinit var reader: ExportReader

    @Before
    fun setUp() {
        conversations = ConversationRepository(db.conversationDao())
        messages = MessageRepository(db.messageDao())
        attachments = AttachmentRepository(db.attachmentDao())
        providers = ProviderRepository(db.providerDao())
        models = ModelRepository(db.modelDao())
        reader = ExportReader(
            conversations = conversations,
            messages = messages,
            attachments = attachments,
            providers = providers,
            models = models,
            settings = ExportSettingsSource { RepositoryTestData.settings() },
        )
    }

    @Test
    fun `readAll orders conversations messages attachments providers and models`() = runBlocking {
        val first = RepositoryTestData.conversation(
            id = "conversation-1",
            createdAt = Instant.parse("2026-11-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-11-01T00:00:00Z"),
            lastMessageAt = Instant.parse("2026-11-01T00:00:00Z"),
        )
        val second = RepositoryTestData.conversation(
            id = "conversation-2",
            createdAt = Instant.parse("2026-11-02T00:00:00Z"),
            updatedAt = Instant.parse("2026-11-02T00:00:00Z"),
            lastMessageAt = Instant.parse("2026-11-02T00:00:00Z"),
        )
        conversations.create(second)
        conversations.create(first)

        messages.appendMessage(
            RepositoryTestData.message(
                id = "message-2",
                conversationId = "conversation-1",
                sequence = 1,
            ),
        )
        messages.appendMessage(
            RepositoryTestData.message(
                id = "message-1",
                conversationId = "conversation-1",
                sequence = 0,
            ),
        )
        messages.appendMessage(
            RepositoryTestData.message(
                id = "message-3",
                conversationId = "conversation-2",
                sequence = 0,
            ),
        )

        attachments.upsert(
            RepositoryTestData.attachment(
                id = "attachment-2",
                createdAt = Instant.parse("2026-11-02T00:00:00Z"),
            ),
        )
        attachments.upsert(
            RepositoryTestData.attachment(
                id = "attachment-1",
                createdAt = Instant.parse("2026-11-01T00:00:00Z"),
            ),
        )

        providers.upsert(
            RepositoryTestData.provider(
                id = "provider-2",
                createdAt = Instant.parse("2026-11-02T00:00:00Z"),
            ),
        )
        providers.upsert(
            RepositoryTestData.provider(
                id = "provider-1",
                createdAt = Instant.parse("2026-11-01T00:00:00Z"),
            ),
        )

        models.upsert(
            RepositoryTestData.model(
                id = "model-2",
                providerId = "provider-2",
                remoteId = "b",
            ),
        )
        models.upsert(
            RepositoryTestData.model(
                id = "model-1",
                providerId = "provider-1",
                remoteId = "a",
            ),
        )

        val snapshot = reader.readAll()

        assertThat(snapshot.conversations.map { it.id })
            .containsExactly("conversation-1", "conversation-2")
            .inOrder()
        assertThat(snapshot.messages.map { it.id })
            .containsExactly("message-1", "message-2", "message-3")
            .inOrder()
        assertThat(snapshot.attachments.map { it.id })
            .containsExactly("attachment-1", "attachment-2")
            .inOrder()
        assertThat(snapshot.providers.map { it.id })
            .containsExactly("provider-1", "provider-2")
            .inOrder()
        assertThat(snapshot.models.map { it.id })
            .containsExactly("model-1", "model-2")
            .inOrder()
        assertThat(snapshot.settings).isEqualTo(RepositoryTestData.settings())
    }

    @Test
    fun `readConversation scopes messages and referenced attachments`() = runBlocking {
        conversations.create(RepositoryTestData.conversation(id = "conversation-1"))
        conversations.create(RepositoryTestData.conversation(id = "conversation-2"))

        messages.appendMessage(
            RepositoryTestData.message(
                id = "message-1",
                conversationId = "conversation-1",
                sequence = 0,
                parts = listOf(
                    ContentPart.Text("Describe this image."),
                    ContentPart.Image("attachment-1", "image/png", "A single pixel."),
                ),
            ),
        )
        messages.appendMessage(
            RepositoryTestData.message(
                id = "message-2",
                conversationId = "conversation-1",
                sequence = 1,
            ),
        )
        messages.appendMessage(
            RepositoryTestData.message(
                id = "message-3",
                conversationId = "conversation-2",
                sequence = 0,
            ),
        )

        attachments.upsert(
            RepositoryTestData.attachment(
                id = "attachment-1",
                kind = AttachmentKind.IMAGE,
                mimeType = "image/png",
                name = "pixel.png",
            ),
        )
        attachments.upsert(
            RepositoryTestData.attachment(
                id = "attachment-2",
                messageId = "message-3",
            ),
        )

        val snapshot = reader.readConversation("conversation-1")!!

        assertThat(snapshot.conversations.map { it.id }).containsExactly("conversation-1")
        assertThat(snapshot.messages.map { it.id })
            .containsExactly("message-1", "message-2")
            .inOrder()
        assertThat(snapshot.attachments.map { it.id }).containsExactly("attachment-1")
        assertThat(reader.readConversation("missing")).isNull()
    }

    @Test
    fun `listConversations returns newest updated first`() = runBlocking {
        conversations.create(
            RepositoryTestData.conversation(
                id = "conversation-1",
                updatedAt = Instant.parse("2026-11-01T00:00:00Z"),
            ),
        )
        conversations.create(
            RepositoryTestData.conversation(
                id = "conversation-2",
                updatedAt = Instant.parse("2026-11-03T00:00:00Z"),
            ),
        )

        assertThat(reader.listConversations().map { it.id })
            .containsExactly("conversation-2", "conversation-1")
            .inOrder()
    }

    @Test
    fun `secrets never enter an export snapshot`() = runBlocking {
        db.secretDao().upsert(
            TestData.secret(id = "secret-1", ciphertext = "super-secret-ciphertext"),
        )
        providers.upsert(RepositoryTestData.provider(id = "provider-1", secretId = "secret-1"))

        val snapshot = reader.readAll()

        assertThat(snapshot.providers.single().secretId).isEqualTo("secret-1")
        assertThat(snapshot.toString()).doesNotContain("super-secret-ciphertext")
    }
}
