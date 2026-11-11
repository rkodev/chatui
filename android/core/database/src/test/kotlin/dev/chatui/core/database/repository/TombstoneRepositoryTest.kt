package dev.chatui.core.database.repository

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.database.DatabaseTestBase
import dev.chatui.core.model.Tombstone
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TombstoneRepositoryTest : DatabaseTestBase() {
    private val repository: TombstoneRepository
        get() = TombstoneRepository(db.tombstoneDao())

    @Test
    fun `add and list tombstones`() = runTest {
        val tombstone = Tombstone(
            id = "tombstone-1",
            conversationId = "conversation-1",
            messageId = "message-1",
            deletedAt = Instant.parse("2026-11-02T00:00:00Z"),
            deviceId = "device-1",
        )

        repository.add(tombstone)

        assertThat(repository.all()).containsExactly(tombstone)
        assertThat(repository.forConversation("conversation-1")).containsExactly(tombstone)
    }

    @Test
    fun `delete tombstone`() = runTest {
        repository.add(
            Tombstone(
                id = "tombstone-1",
                conversationId = "conversation-1",
                messageId = null,
                deletedAt = Instant.parse("2026-11-02T00:00:00Z"),
                deviceId = "device-1",
            ),
        )

        repository.delete("tombstone-1")

        assertThat(repository.all()).isEmpty()
    }

    @Test
    fun `delete tombstones for a conversation`() = runTest {
        repository.add(
            Tombstone(
                id = "tombstone-1",
                conversationId = "conversation-1",
                messageId = null,
                deletedAt = Instant.parse("2026-11-02T00:00:00Z"),
                deviceId = "device-1",
            ),
        )
        repository.add(
            Tombstone(
                id = "tombstone-2",
                conversationId = "conversation-2",
                messageId = null,
                deletedAt = Instant.parse("2026-11-02T00:00:00Z"),
                deviceId = "device-1",
            ),
        )

        repository.deleteForConversation("conversation-1")

        assertThat(repository.all().map { it.id }).containsExactly("tombstone-2")
    }
}
