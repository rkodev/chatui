package dev.chatui.core.database.repository

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.database.DatabaseTestBase
import dev.chatui.core.model.ContentPart
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ConversationSearchTest : DatabaseTestBase() {
    private val clock = Clock.fixed(
        Instant.parse("2026-11-02T12:00:00Z"),
        ZoneOffset.UTC,
    )

    private val conversations: ConversationRepository
        get() = ConversationRepository(db.conversationDao(), clock)

    private val messages: MessageRepository
        get() = MessageRepository(db.messageDao(), clock)

    @Test
    fun `message search joins the owning conversation`() = runTest {
        conversations.create(
            RepositoryTestData.conversation(id = "conversation-1", title = "First"),
        )
        messages.appendMessage(
            RepositoryTestData.message(
                id = "message-1",
                conversationId = "conversation-1",
                parts = listOf(ContentPart.Text("The quick brown fox")),
            ),
        )

        val hits = messages.searchWithConversations("brown*")

        assertThat(hits).hasSize(1)
        assertThat(hits.single().message.id).isEqualTo("message-1")
        assertThat(hits.single().conversation.id).isEqualTo("conversation-1")
        assertThat(hits.single().conversation.title).isEqualTo("First")
    }

    @Test
    fun `message search excludes archived conversations`() = runTest {
        conversations.create(RepositoryTestData.conversation(id = "active"))
        conversations.create(
            RepositoryTestData.conversation(id = "archived", archived = true),
        )
        messages.appendMessage(
            RepositoryTestData.message(
                id = "message-active",
                conversationId = "active",
                parts = listOf(ContentPart.Text("searchable")),
            ),
        )
        messages.appendMessage(
            RepositoryTestData.message(
                id = "message-archived",
                conversationId = "archived",
                parts = listOf(ContentPart.Text("searchable")),
            ),
        )

        val hits = messages.searchWithConversations("searchable*")

        assertThat(hits.map { it.message.id }).containsExactly("message-active")
    }

    @Test
    fun `title search is case insensitive and excludes archived`() = runTest {
        conversations.create(
            RepositoryTestData.conversation(id = "active", title = "Kotlin Notes"),
        )
        conversations.create(
            RepositoryTestData.conversation(
                id = "archived",
                title = "Kotlin Notes",
                archived = true,
            ),
        )

        val results = conversations.searchByTitle("kotlin")

        assertThat(results.map { it.id }).containsExactly("active")
    }

    @Test
    fun `title search escapes like wildcards`() = runTest {
        conversations.create(
            RepositoryTestData.conversation(id = "percent", title = "100% done"),
        )
        conversations.create(
            RepositoryTestData.conversation(id = "other", title = "100 done"),
        )

        val results = conversations.searchByTitle("100%")

        assertThat(results.map { it.id }).containsExactly("percent")
    }

    @Test
    fun `title search respects the result limit`() = runTest {
        repeat(5) { index ->
            conversations.create(
                RepositoryTestData.conversation(
                    id = "conversation-$index",
                    title = "Kotlin $index",
                ),
            )
        }

        assertThat(conversations.searchByTitle("Kotlin", limit = 2)).hasSize(2)
    }
}
