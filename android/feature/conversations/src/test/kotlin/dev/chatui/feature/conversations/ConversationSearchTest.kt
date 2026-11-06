package dev.chatui.feature.conversations

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ConversationSearchTest {
    @Test
    fun `fts query keeps only safe tokens and adds prefixes`() {
        assertThat(buildFtsQuery("\"hello\" (world) OR -kotlin"))
            .isEqualTo("hello* world* OR* kotlin*")
    }

    @Test
    fun `fts query returns null when no safe token remains`() {
        assertThat(buildFtsQuery("   ")).isNull()
        assertThat(buildFtsQuery("\"()*")).isNull()
    }

    @Test
    fun `snippet centers on the first matching token`() {
        val message = testMessage(
            text = "The quick brown fox jumps over the lazy dog and keeps running",
        )

        val snippet = searchSnippet(message, "brown", maxLength = 20)

        assertThat(snippet).contains("brown")
        assertThat(snippet).endsWith("…")
    }

    @Test
    fun `snippet returns empty text for a message without searchable content`() {
        val message = testMessage(text = "   ")

        assertThat(searchSnippet(message, "brown")).isEmpty()
    }
}
