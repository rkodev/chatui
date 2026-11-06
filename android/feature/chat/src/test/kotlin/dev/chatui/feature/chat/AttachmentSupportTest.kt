package dev.chatui.feature.chat

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.model.Attachment
import dev.chatui.core.model.AttachmentKind
import dev.chatui.core.model.InputModality
import org.junit.Test

class AttachmentSupportTest {
    @Test
    fun `text-only models reject images but accept text files`() {
        val support = attachmentSupport(
            attachments = listOf(image(), file()),
            inputModalities = listOf(InputModality.TEXT),
        )

        assertThat(support.images).isFalse()
        assertThat(support.files).isTrue()
        assertThat(support.unsupportedCount).isEqualTo(1)
        assertThat(support.message).contains("cannot receive images")
    }

    @Test
    fun `vision models accept images and files`() {
        val support = attachmentSupport(
            attachments = listOf(image(), file()),
            inputModalities = listOf(InputModality.TEXT, InputModality.IMAGE, InputModality.FILE),
        )

        assertThat(support.supported).isTrue()
        assertThat(support.message).isNull()
    }

    @Test
    fun `empty modalities fall back to text`() {
        val support = attachmentSupport(
            attachments = listOf(file()),
            inputModalities = emptyList(),
        )

        assertThat(support.files).isTrue()
        assertThat(support.supported).isTrue()
    }

    private fun image(): Attachment = Attachment(
        id = "image-1",
        messageId = null,
        kind = AttachmentKind.IMAGE,
        mimeType = "image/png",
        name = "photo.png",
        size = 10,
        sha256 = "sha-image",
        storageKey = "attachments/sha-image",
        width = 1,
        height = 1,
        createdAt = TEST_NOW,
    )

    private fun file(): Attachment = Attachment(
        id = "file-1",
        messageId = null,
        kind = AttachmentKind.FILE,
        mimeType = "text/plain",
        name = "notes.txt",
        size = 10,
        sha256 = "sha-file",
        storageKey = "attachments/sha-file",
        width = null,
        height = null,
        createdAt = TEST_NOW,
    )
}
