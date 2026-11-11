package dev.chatui.core.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BackupManifestTest {
    private val body = BackupManifest.build(
        createdAt = BackupTestData.NOW,
        updatedAt = BackupTestData.NOW,
        deviceId = "device-1",
        conversations = listOf(BackupTestData.conversation()),
        attachments = listOf(BackupTestData.attachment()),
        tombstones = listOf(BackupTestData.tombstone()),
    )

    @Test
    fun `manifest round trips through canonical JSON`() {
        val encoded = BackupManifest.encode(body)

        assertThat(BackupManifest.parse(encoded)).isEqualTo(body)
        assertThat(BackupManifest.parse(encoded.toByteArray())).isEqualTo(body)
    }

    @Test
    fun `manifest uses opaque object keys`() {
        assertThat(body.conversations.single().objectKey)
            .isEqualTo("conversations/${BackupTestData.STORAGE_KEY}.enc")
        assertThat(body.attachments.single().objectKey)
            .isEqualTo("attachments/${BackupTestData.SHA256}.enc")
    }

    @Test
    fun `manifest contains no secret fields`() {
        val encoded = BackupManifest.encode(body)

        assertThat(encoded).doesNotContain("secret")
        assertThat(encoded).doesNotContain("apiKey")
        assertThat(encoded).doesNotContain("passphrase")
    }

    @Test
    fun `manifest rejects an unsupported format version`() {
        val encoded = BackupManifest.encode(body)
            .replace("\"formatVersion\":1", "\"formatVersion\":2")

        val error = runCatching { BackupManifest.parse(encoded) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `manifest rejects unknown fields`() {
        val encoded = BackupManifest.encode(body)
            .replace("\"deviceId\":\"device-1\"", "\"deviceId\":\"device-1\",\"extra\":true")

        val error = runCatching { BackupManifest.parse(encoded) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `manifest rejects duplicate storage keys`() {
        val error = runCatching {
            BackupManifest.build(
                createdAt = BackupTestData.NOW,
                updatedAt = BackupTestData.NOW,
                deviceId = "device-1",
                conversations = listOf(
                    BackupTestData.conversation(id = "conversation-1"),
                    BackupTestData.conversation(id = "conversation-2"),
                ),
                attachments = emptyList(),
                tombstones = emptyList(),
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `manifest rejects an invalid storage key`() {
        val error = runCatching {
            BackupManifest.build(
                createdAt = BackupTestData.NOW,
                updatedAt = BackupTestData.NOW,
                deviceId = "device-1",
                conversations = listOf(
                    BackupTestData.conversation(storageKey = "not-a-storage-key"),
                ),
                attachments = emptyList(),
                tombstones = emptyList(),
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }
}
