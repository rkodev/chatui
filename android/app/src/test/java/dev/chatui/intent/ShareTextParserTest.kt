package dev.chatui.intent

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareTextParserTest {
    @Test
    fun `accepts a plain text send and trims the text`() {
        val parsed = ShareTextParser.parse(
            action = Intent.ACTION_SEND,
            type = "text/plain",
            text = "  hello from another app  ",
        )

        assertThat(parsed).isEqualTo("hello from another app")
    }

    @Test
    fun `accepts a plain text type with a charset parameter`() {
        val parsed = ShareTextParser.parse(
            action = Intent.ACTION_SEND,
            type = "text/plain; charset=utf-8",
            text = "hello",
        )

        assertThat(parsed).isEqualTo("hello")
    }

    @Test
    fun `rejects a non-send action`() {
        val parsed = ShareTextParser.parse(
            action = Intent.ACTION_VIEW,
            type = "text/plain",
            text = "hello",
        )

        assertThat(parsed).isNull()
    }

    @Test
    fun `rejects a non-plain-text type`() {
        val parsed = ShareTextParser.parse(
            action = Intent.ACTION_SEND,
            type = "image/png",
            text = "hello",
        )

        assertThat(parsed).isNull()
    }

    @Test
    fun `rejects a missing type`() {
        val parsed = ShareTextParser.parse(
            action = Intent.ACTION_SEND,
            type = null,
            text = "hello",
        )

        assertThat(parsed).isNull()
    }

    @Test
    fun `rejects blank text`() {
        val parsed = ShareTextParser.parse(
            action = Intent.ACTION_SEND,
            type = "text/plain",
            text = "   \n\t ",
        )

        assertThat(parsed).isNull()
    }

    @Test
    fun `rejects missing text`() {
        val parsed = ShareTextParser.parse(
            action = Intent.ACTION_SEND,
            type = "text/plain",
            text = null,
        )

        assertThat(parsed).isNull()
    }

    @Test
    fun `caps accepted text at the documented limit`() {
        val parsed = ShareTextParser.parse(
            action = Intent.ACTION_SEND,
            type = "text/plain",
            text = "a".repeat(ShareTextParser.MAX_TEXT_LENGTH + 50),
        )

        assertThat(parsed).hasLength(ShareTextParser.MAX_TEXT_LENGTH)
    }

    @Test
    fun `reads extra text from an intent`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "  shared text  ")
        }

        assertThat(ShareTextParser.parse(intent)).isEqualTo("shared text")
    }

    @Test
    fun `ignores a null intent`() {
        assertThat(ShareTextParser.parse(null as Intent?)).isNull()
    }
}
