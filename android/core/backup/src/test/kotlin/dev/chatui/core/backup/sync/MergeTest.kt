package dev.chatui.core.backup.sync

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.backup.BackupManifest
import dev.chatui.core.backup.ConversationPayload
import dev.chatui.core.backup.ConversationPayloadCodec
import dev.chatui.core.backup.ManifestTombstone
import dev.chatui.core.model.ChatParams
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.Conversation
import dev.chatui.core.model.Message
import dev.chatui.core.model.MessageRole
import dev.chatui.core.model.MessageStatus
import dev.chatui.core.model.Tombstone
import java.time.Instant
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test

class MergeTest {
    private val base = Instant.parse("2026-11-02T00:00:00Z")

    @Test
    fun `concurrent additions keep both and renumber deterministically`() {
        val shared0 = message("shared-0", sequence = 0, createdAt = base)
        val shared1 = message("shared-1", sequence = 1, createdAt = base.plusSeconds(1))
        val localExtra = message("local-extra", sequence = 2, createdAt = base.plusSeconds(2))
        val remoteExtra = message("remote-extra", sequence = 2, createdAt = base.plusSeconds(3))
        val local = payload(messages = listOf(shared0, shared1, localExtra))
        val remote = payload(messages = listOf(shared0, shared1, remoteExtra))

        val merged = mergeConversation(
            local = local,
            remote = remote,
            options = MergeConversationOptions("device-a", "device-b"),
        )

        assertThat(merged.renumbered).isTrue()
        assertThat(merged.payload.messages.map { it.id })
            .containsExactly("shared-0", "shared-1", "local-extra", "remote-extra")
            .inOrder()
        assertThat(merged.payload.messages.map { it.sequence })
            .containsExactly(0L, 1L, 2L, 3L)
            .inOrder()
    }

    @Test
    fun `metadata uses last write wins by updatedAt`() {
        val local = payload(title = "Local", updatedAt = base.plusSeconds(10))
        val remote = payload(title = "Remote", updatedAt = base.plusSeconds(20))

        val merged = mergeConversation(
            local = local,
            remote = remote,
            options = MergeConversationOptions("device-a", "device-b"),
        )

        assertThat(merged.payload.title).isEqualTo("Remote")
    }

    @Test
    fun `metadata tie breaks by device id`() {
        val local = payload(title = "Local", updatedAt = base)
        val remote = payload(title = "Remote", updatedAt = base)

        val remoteWins = mergeConversation(
            local = local,
            remote = remote,
            options = MergeConversationOptions("device-a", "device-b"),
        )
        val localWins = mergeConversation(
            local = local,
            remote = remote,
            options = MergeConversationOptions("device-z", "device-b"),
        )

        assertThat(remoteWins.payload.title).isEqualTo("Remote")
        assertThat(localWins.payload.title).isEqualTo("Local")
    }

    @Test
    fun `deletion tombstone wins over an older update`() {
        val local = payload(
            messages = listOf(message("message-1", sequence = 0, createdAt = base)),
        )
        val remote = payload(
            messages = listOf(message("message-1", sequence = 0, createdAt = base)),
        )
        val tombstone = Tombstone(
            id = "tombstone-1",
            conversationId = local.id,
            messageId = "message-1",
            deletedAt = base.plusSeconds(5),
            deviceId = "device-b",
        )

        val merged = mergeConversation(
            local = local,
            remote = remote,
            options = MergeConversationOptions(
                localDeviceId = "device-a",
                remoteDeviceId = "device-b",
                messageTombstones = listOf(tombstone),
            ),
        )

        assertThat(merged.payload.messages).isEmpty()
    }

    @Test
    fun `newer message update survives an older tombstone`() {
        val local = payload(
            messages = listOf(
                message(
                    "message-1",
                    sequence = 0,
                    createdAt = base,
                    updatedAt = base.plusSeconds(10),
                ),
            ),
        )
        val remote = payload(messages = emptyList())
        val tombstone = Tombstone(
            id = "tombstone-1",
            conversationId = local.id,
            messageId = "message-1",
            deletedAt = base.plusSeconds(5),
            deviceId = "device-b",
        )

        val merged = mergeConversation(
            local = local,
            remote = remote,
            options = MergeConversationOptions(
                localDeviceId = "device-a",
                remoteDeviceId = "device-b",
                messageTombstones = listOf(tombstone),
            ),
        )

        assertThat(merged.payload.messages.map { it.id }).containsExactly("message-1")
    }

    @Test
    fun `conversation tombstone marks the conversation deleted`() {
        val local = payload(updatedAt = base)
        val remote = payload(updatedAt = base)
        val tombstone = ManifestTombstone(
            conversationId = local.id,
            deletedAt = base.plusSeconds(1).toString(),
            deviceId = "device-b",
        )

        val merged = mergeConversation(
            local = local,
            remote = remote,
            options = MergeConversationOptions(
                localDeviceId = "device-a",
                remoteDeviceId = "device-b",
                conversationTombstones = listOf(tombstone),
            ),
        )

        assertThat(merged.deleted).isTrue()
    }

    @Test
    fun `manifest merge keeps the higher conversation revision`() {
        val local = BackupManifest.build(
            createdAt = base,
            updatedAt = base,
            deviceId = "device-a",
            conversations = listOf(conversation(revision = 2)),
            attachments = emptyList(),
            tombstones = emptyList(),
        )
        val remote = BackupManifest.build(
            createdAt = base,
            updatedAt = base,
            deviceId = "device-b",
            conversations = listOf(conversation(revision = 5)),
            attachments = emptyList(),
            tombstones = emptyList(),
        )

        val merged = mergeManifests(local, remote)

        assertThat(merged.conversations).hasSize(1)
        assertThat(merged.conversations.single().revision).isEqualTo(5)
        assertThat(merged.conversations.single().deviceId).isEqualTo("device-b")
    }

    @Test
    fun `manifest merge drops a conversation deleted at or after its update`() {
        val local = BackupManifest.build(
            createdAt = base,
            updatedAt = base,
            deviceId = "device-a",
            conversations = listOf(conversation(revision = 2)),
            attachments = emptyList(),
            tombstones = emptyList(),
        )
        val remote = BackupManifest.build(
            createdAt = base,
            updatedAt = base,
            deviceId = "device-b",
            conversations = emptyList(),
            attachments = emptyList(),
            tombstones = listOf(
                Tombstone(
                    id = "tombstone-1",
                    conversationId = "conversation-1",
                    messageId = null,
                    deletedAt = base.plusSeconds(1),
                    deviceId = "device-b",
                ),
            ),
        )

        val merged = mergeManifests(local, remote)

        assertThat(merged.conversations).isEmpty()
        assertThat(merged.tombstones).hasSize(1)
    }

    private fun conversation(
        id: String = "conversation-1",
        revision: Long = 1,
        updatedAt: Instant = base,
    ): Conversation = Conversation(
        id = id,
        title = "Conversation",
        systemPrompt = "",
        providerId = "provider-1",
        modelId = "model-1",
        modelLabel = "Model",
        params = ChatParams(
            temperature = null,
            topP = null,
            maxOutputTokens = null,
            stop = emptyList(),
            reasoningEffort = null,
            streaming = true,
            extra = buildJsonObject {},
        ),
        pinned = false,
        archived = false,
        temporary = false,
        createdAt = base,
        updatedAt = updatedAt,
        lastMessageAt = updatedAt,
        messageCount = 0,
        lastError = null,
        sync = null,
        storageKey = "00112233445566778899aabbccddeeff",
        revision = revision,
    )

    private fun message(
        id: String,
        sequence: Long,
        createdAt: Instant,
        updatedAt: Instant = createdAt,
    ): Message = Message(
        id = id,
        conversationId = "conversation-1",
        sequence = sequence,
        role = MessageRole.USER,
        parts = listOf(ContentPart.Text("hello")),
        status = MessageStatus.COMPLETE,
        providerId = null,
        modelId = null,
        modelLabel = null,
        usage = null,
        finishReason = null,
        error = null,
        latencyMs = null,
        firstTokenMs = null,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = null,
    )

    private fun payload(
        title: String = "Conversation",
        updatedAt: Instant = base,
        messages: List<Message> = emptyList(),
    ): ConversationPayload {
        val conversation = conversation(updatedAt = updatedAt).copy(title = title)
        return ConversationPayloadCodec.build(conversation, messages, emptyList())
    }
}
