package dev.chatui.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RedactionTest {
    @Test
    fun `redacts all default secret headers case-insensitively`() {
        val redacted = Redaction.redactHeaders(
            mapOf(
                "Authorization" to "Bearer sk-123",
                "x-api-key" to "key-456",
                "X-Goog-Api-Key" to "key-789",
                "Content-Type" to "application/json",
            ),
        )

        assertThat(redacted["Authorization"]).isEqualTo(Redaction.REDACTED)
        assertThat(redacted["x-api-key"]).isEqualTo(Redaction.REDACTED)
        assertThat(redacted["X-Goog-Api-Key"]).isEqualTo(Redaction.REDACTED)
        assertThat(redacted["Content-Type"]).isEqualTo("application/json")
        assertThat(redacted.toString()).doesNotContain("sk-123")
        assertThat(redacted.toString()).doesNotContain("key-456")
        assertThat(redacted.toString()).doesNotContain("key-789")
    }

    @Test
    fun `redacts configurable secret headers`() {
        val redacted = Redaction.redactHeaders(
            mapOf("X-Custom-Secret" to "custom-value", "Accept" to "text/plain"),
            secretHeaders = setOf("x-custom-secret"),
        )

        assertThat(redacted["X-Custom-Secret"]).isEqualTo(Redaction.REDACTED)
        assertThat(redacted["Accept"]).isEqualTo("text/plain")
    }

    @Test
    fun `redacts header values in diagnostic text`() {
        val text = listOf(
            "Authorization: Bearer sk-123",
            "x-api-key: key-456",
            "X-Goog-Api-Key: key-789",
            "Content-Type: application/json",
        ).joinToString("\n")

        val redacted = Redaction.redactText(text)

        assertThat(redacted).contains("Authorization: [REDACTED]")
        assertThat(redacted).contains("x-api-key: [REDACTED]")
        assertThat(redacted).contains("X-Goog-Api-Key: [REDACTED]")
        assertThat(redacted).contains("Content-Type: application/json")
        assertThat(redacted).doesNotContain("sk-123")
        assertThat(redacted).doesNotContain("key-456")
        assertThat(redacted).doesNotContain("key-789")
    }

    @Test
    fun `redacts JSON style header values`() {
        val redacted = Redaction.redactText("{\"Authorization\":\"Bearer sk-123\",\"x-api-key\":\"key-456\"}")

        assertThat(redacted).doesNotContain("sk-123")
        assertThat(redacted).doesNotContain("key-456")
        assertThat(redacted).contains("\"Authorization\":\"[REDACTED]\"")
    }

    @Test
    fun `redacts bare bearer tokens`() {
        assertThat(Redaction.redactText("request failed with Bearer sk-123"))
            .isEqualTo("request failed with Bearer [REDACTED]")
    }

    @Test
    fun `redacts custom headers in text`() {
        val redacted = Redaction.redactText(
            "X-Custom-Secret: custom-value",
            secretHeaders = setOf("x-custom-secret"),
        )

        assertThat(redacted).isEqualTo("X-Custom-Secret: [REDACTED]")
    }

    @Test
    fun `exposes the default header names`() {
        assertThat(Redaction.DEFAULT_SECRET_HEADERS)
            .containsExactly("authorization", "x-api-key", "x-goog-api-key")
    }
}
