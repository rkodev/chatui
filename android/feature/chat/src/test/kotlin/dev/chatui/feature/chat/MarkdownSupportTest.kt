package dev.chatui.feature.chat

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.MessageRole
import dev.chatui.core.model.MessageStatus
import dev.snipme.highlights.model.SyntaxLanguage
import org.junit.Test

class MarkdownSupportTest {
    @Test
    fun `closes an unterminated backtick fence`() {
        val markdown = "Before\n```kotlin\nval x = 1"

        assertThat(closeIncompleteFence(markdown))
            .isEqualTo("Before\n```kotlin\nval x = 1\n```")
    }

    @Test
    fun `closes an unterminated tilde fence`() {
        assertThat(closeIncompleteFence("~~~\ncode"))
            .isEqualTo("~~~\ncode\n~~~")
    }

    @Test
    fun `leaves complete fences unchanged`() {
        val markdown = "```kotlin\nval x = 1\n```\nAfter"

        assertThat(closeIncompleteFence(markdown)).isEqualTo(markdown)
    }

    @Test
    fun `sanitizes markdown urls to the M1 allowlist`() {
        assertThat(sanitizeMarkdownUrl("https://example.com/a")).isEqualTo("https://example.com/a")
        assertThat(sanitizeMarkdownUrl("http://example.com")).isEqualTo("http://example.com")
        assertThat(sanitizeMarkdownUrl("mailto:user@example.com"))
            .isEqualTo("mailto:user@example.com")
        assertThat(sanitizeMarkdownUrl("#section")).isEqualTo("#section")

        assertThat(sanitizeMarkdownUrl("javascript:alert(1)")).isNull()
        assertThat(sanitizeMarkdownUrl("data:text/html,<script>alert(1)</script>")).isNull()
        assertThat(sanitizeMarkdownUrl("//example.com/a")).isNull()
        assertThat(sanitizeMarkdownUrl("/relative")).isNull()
        assertThat(sanitizeMarkdownUrl("file:///etc/passwd")).isNull()
        assertThat(sanitizeMarkdownUrl(null)).isNull()
    }

    @Test
    fun `normalizes a bounded highlighter language set`() {
        assertThat(normalizeCodeLanguage("kt")).isEqualTo(SyntaxLanguage.KOTLIN)
        assertThat(normalizeCodeLanguage("Kotlin")).isEqualTo(SyntaxLanguage.KOTLIN)
        assertThat(normalizeCodeLanguage("c++")).isEqualTo(SyntaxLanguage.CPP)
        assertThat(normalizeCodeLanguage("sh")).isEqualTo(SyntaxLanguage.SHELL)
        assertThat(normalizeCodeLanguage("python")).isEqualTo(SyntaxLanguage.PYTHON)
        assertThat(normalizeCodeLanguage("html")).isNull()
        assertThat(normalizeCodeLanguage(null)).isNull()
    }

    @Test
    fun `message copy joins text parts with a blank line`() {
        val message = testMessage(text = "First").copy(
            role = MessageRole.ASSISTANT,
            status = MessageStatus.COMPLETE,
            parts = listOf(
                ContentPart.Text("First"),
                ContentPart.Reasoning("hidden"),
                ContentPart.Text("Second"),
            ),
        )

        assertThat(message.copyableText()).isEqualTo("First\n\nSecond")
    }
}
