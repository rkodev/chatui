package dev.chatui.core.database.attachment

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.model.AttachmentKind
import org.junit.Test

class AttachmentRulesTest {
    @Test
    fun `accepts supported images and text files`() {
        val image = validateAttachmentFile("photo.png", "image/png", 1024)
        val text = validateAttachmentFile("notes.md", "text/markdown", 1024)

        assertThat(image).isInstanceOf(AttachmentValidationResult.Valid::class.java)
        assertThat((image as AttachmentValidationResult.Valid).value.kind)
            .isEqualTo(AttachmentKind.IMAGE)
        assertThat((text as AttachmentValidationResult.Valid).value.kind)
            .isEqualTo(AttachmentKind.FILE)
    }

    @Test
    fun `infers mime type from extension when the picker reports none`() {
        val result = validateAttachmentFile("main.kt", "", 100)

        assertThat(result).isInstanceOf(AttachmentValidationResult.Valid::class.java)
        assertThat((result as AttachmentValidationResult.Valid).value.mimeType)
            .isEqualTo("text/plain")
    }

    @Test
    fun `rejects empty, oversized, and unsupported files`() {
        val empty = validateAttachmentFile("empty.txt", "text/plain", 0)
        val oversized = validateAttachmentFile("big.txt", "text/plain", MAX_TEXT_BYTES + 1)
        val unsupported = validateAttachmentFile("tool.exe", "application/octet-stream", 10)

        assertThat((empty as AttachmentValidationResult.Invalid).error.code)
            .isEqualTo(AttachmentValidationCode.EMPTY)
        assertThat((oversized as AttachmentValidationResult.Invalid).error.code)
            .isEqualTo(AttachmentValidationCode.TOO_LARGE)
        assertThat((unsupported as AttachmentValidationResult.Invalid).error.code)
            .isEqualTo(AttachmentValidationCode.UNSUPPORTED_TYPE)
    }

    @Test
    fun `sanitizes names and strips path separators`() {
        assertThat(sanitizeFileName("../../secret.txt")).isEqualTo("secret.txt")
        assertThat(sanitizeFileName("  spaced   name.txt ")).isEqualTo("spaced name.txt")
        assertThat(sanitizeFileName("")).isEqualTo("attachment")
    }

    @Test
    fun `scales the longest edge without upscaling`() {
        assertThat(scaledDimensions(4096, 2048)).isEqualTo(2048 to 1024)
        assertThat(scaledDimensions(100, 50)).isEqualTo(100 to 50)
        assertThat(scaledDimensions(0, 0)).isEqualTo(0 to 0)
    }

    @Test
    fun `formats sizes for chips`() {
        assertThat(formatAttachmentSize(512)).isEqualTo("512 B")
        assertThat(formatAttachmentSize(2048)).isEqualTo("2.0 KB")
        assertThat(formatAttachmentSize(2L * 1024 * 1024)).isEqualTo("2.0 MB")
    }
}
