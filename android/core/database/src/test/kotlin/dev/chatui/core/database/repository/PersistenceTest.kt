package dev.chatui.core.database.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.database.ChatUiDatabase
import dev.chatui.core.database.recovery.StreamingRecoveryService
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.MessageStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * File-backed persistence tests that simulate an activity restart or process
 * death by closing and reopening the same database file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PersistenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "chatui-persistence-test.db"
    private val clock = Clock.fixed(
        Instant.parse("2026-11-03T12:00:00Z"),
        ZoneOffset.UTC,
    )

    @Before
    fun setUp() {
        context.deleteDatabase(databaseName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun `conversations and messages survive a database reopen`() = runTest {
        withDatabase { db ->
            val conversations = ConversationRepository(db.conversationDao(), clock)
            val messages = MessageRepository(db.messageDao(), clock)
            conversations.create(RepositoryTestData.conversation())
            messages.appendMessage(
                RepositoryTestData.message(
                    id = "message-1",
                    sequence = 1,
                    parts = listOf(ContentPart.Text("first")),
                ),
            )
            messages.appendMessage(
                RepositoryTestData.message(
                    id = "message-2",
                    sequence = 2,
                    parts = listOf(ContentPart.Text("second")),
                ),
            )
        }

        withDatabase { db ->
            val conversations = ConversationRepository(db.conversationDao(), clock)
            val messages = MessageRepository(db.messageDao(), clock)

            assertThat(conversations.get("conversation-1")).isNotNull()
            assertThat(messages.count("conversation-1")).isEqualTo(2)
            assertThat(messages.latest("conversation-1").map { it.id })
                .containsExactly("message-1", "message-2")
                .inOrder()
            assertThat(messages.latest("conversation-1").map { it.parts })
                .containsExactly(
                    listOf(ContentPart.Text("first")),
                    listOf(ContentPart.Text("second")),
                )
                .inOrder()
        }
    }

    @Test
    fun `pin and archive flags survive a database reopen`() = runTest {
        withDatabase { db ->
            val conversations = ConversationRepository(db.conversationDao(), clock)
            conversations.create(RepositoryTestData.conversation(id = "active"))
            conversations.create(RepositoryTestData.conversation(id = "archived"))
            conversations.setPinned("active", true)
            conversations.setArchived("archived", true)
        }

        withDatabase { db ->
            val conversations = ConversationRepository(db.conversationDao(), clock)

            assertThat(conversations.get("active")!!.pinned).isTrue()
            assertThat(conversations.get("archived")!!.archived).isTrue()
            assertThat(conversations.observeActive().first().map { it.id })
                .containsExactly("active")
            assertThat(conversations.observeArchived().first().map { it.id })
                .containsExactly("archived")
        }
    }

    @Test
    fun `a streaming message is recovered after a reopen with partial content`() = runTest {
        withDatabase { db ->
            val conversations = ConversationRepository(db.conversationDao(), clock)
            val messages = MessageRepository(db.messageDao(), clock)
            conversations.create(RepositoryTestData.conversation())
            messages.appendMessage(
                RepositoryTestData.message(
                    id = "stream-1",
                    sequence = 1,
                    status = MessageStatus.STREAMING,
                    parts = listOf(ContentPart.Text("partial answer")),
                ),
            )
        }

        withDatabase { db ->
            val messages = MessageRepository(db.messageDao(), clock)
            val recovery = StreamingRecoveryService(messages, clock)

            val result = recovery.recoverInterruptedMessages()

            assertThat(result.recovered.map { it.id }).containsExactly("stream-1")
            val recovered = messages.get("stream-1")!!
            assertThat(recovered.status).isEqualTo(MessageStatus.CANCELLED)
            assertThat(recovered.parts).containsExactly(ContentPart.Text("partial answer"))
            assertThat(messages.count("conversation-1")).isEqualTo(1)
        }
    }

    @Test
    fun `reopening does not duplicate messages`() = runTest {
        withDatabase { db ->
            val conversations = ConversationRepository(db.conversationDao(), clock)
            val messages = MessageRepository(db.messageDao(), clock)
            conversations.create(RepositoryTestData.conversation())
            val message = RepositoryTestData.message(id = "message-1", sequence = 1)
            messages.appendMessage(message)
            messages.appendMessage(message)
        }

        withDatabase { db ->
            val messages = MessageRepository(db.messageDao(), clock)

            assertThat(messages.count("conversation-1")).isEqualTo(1)
            assertThat(messages.latest("conversation-1").map { it.id })
                .containsExactly("message-1")
        }
    }

    private suspend fun <T> withDatabase(block: suspend (ChatUiDatabase) -> T): T {
        val db = ChatUiDatabase.builder(context, databaseName).build()

        return try {
            block(db)
        } finally {
            db.close()
        }
    }
}
