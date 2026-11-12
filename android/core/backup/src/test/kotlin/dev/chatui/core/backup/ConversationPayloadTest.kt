package dev.chatui.core.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ConversationPayloadTest {
    private val payload = ConversationPayloadCodec.build(
        conversation = BackupTestData.conversation(),
        messages = listOf(BackupTestData.message()),
        attachments = listOf(BackupTestData.attachment()),
    )

    @Test
    fun `payload round trips through canonical JSON`() {
        val encoded = ConversationPayloadCodec.encode(payload)

        assertThat(ConversationPayloadCodec.parse(encoded)).isEqualTo(payload)
        assertThat(ConversationPayloadCodec.parse(encoded.toByteArray())).isEqualTo(payload)
    }

    @Test
    fun `payload carries metadata messages and attachment references`() {
        assertThat(payload.storageKey).isEqualTo(BackupTestData.STORAGE_KEY)
        assertThat(payload.revision).isEqualTo(3)
        assertThat(payload.messages.single().id).isEqualTo("message-1")
        assertThat(payload.attachments.single().storageKey).isEqualTo(BackupTestData.SHA256)
    }

    @Test
    fun `payload contains no secret fields`() {
        val encoded = ConversationPayloadCodec.encode(payload)

        assertThat(encoded).doesNotContain("secret")
        assertThat(encoded).doesNotContain("apiKey")
        assertThat(encoded).doesNotContain("passphrase")
    }

    @Test
    fun `payload rejects an invalid storage key`() {
        val error = runCatching {
            ConversationPayloadCodec.build(
                conversation = BackupTestData.conversation(storageKey = "invalid"),
                messages = emptyList(),
                attachments = emptyList(),
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `payload rejects a missing attachment reference`() {
        val error = runCatching {
            ConversationPayloadCodec.build(
                conversation = BackupTestData.conversation(),
                messages = listOf(BackupTestData.message()),
                attachments = emptyList(),
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `payload rejects unknown fields`() {
        val encoded = ConversationPayloadCodec.encode(payload)
            .replace("\"revision\":3", "\"revision\":3,\"extra\":true")

        val error = runCatching { ConversationPayloadCodec.parse(encoded) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }
}
