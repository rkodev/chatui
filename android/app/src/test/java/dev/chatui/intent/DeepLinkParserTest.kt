package dev.chatui.intent

import android.content.Intent
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeepLinkParserTest {
    private val conversationId = "0190a5c4-1d2e-7abc-8def-0123456789ab"

    @Test
    fun `accepts a valid chat deep link`() {
        val parsed = DeepLinkParser.parse(Uri.parse("chatui://chat/$conversationId"))

        assertThat(parsed).isEqualTo(conversationId)
    }

    @Test
    fun `accepts a valid deep link from a view intent`() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("chatui://chat/$conversationId"))

        assertThat(DeepLinkParser.parse(intent)).isEqualTo(conversationId)
    }

    @Test
    fun `rejects a non-view intent`() {
        val intent = Intent(Intent.ACTION_SEND, Uri.parse("chatui://chat/$conversationId"))

        assertThat(DeepLinkParser.parse(intent)).isNull()
    }

    @Test
    fun `rejects a null intent`() {
        assertThat(DeepLinkParser.parse(null as Intent?)).isNull()
    }

    @Test
    fun `rejects a null uri`() {
        assertThat(DeepLinkParser.parse(null as Uri?)).isNull()
    }

    @Test
    fun `rejects the wrong scheme`() {
        val parsed = DeepLinkParser.parse(Uri.parse("https://chat/$conversationId"))

        assertThat(parsed).isNull()
    }

    @Test
    fun `rejects the wrong host`() {
        val parsed = DeepLinkParser.parse(Uri.parse("chatui://conversation/$conversationId"))

        assertThat(parsed).isNull()
    }

    @Test
    fun `rejects an empty id`() {
        assertThat(DeepLinkParser.parse(Uri.parse("chatui://chat/"))).isNull()
        assertThat(DeepLinkParser.parse(Uri.parse("chatui://chat"))).isNull()
    }

    @Test
    fun `rejects an extra path segment`() {
        val parsed = DeepLinkParser.parse(
            Uri.parse("chatui://chat/$conversationId/extra"),
        )

        assertThat(parsed).isNull()
    }

    @Test
    fun `accepts a trailing slash as the same single segment`() {
        val parsed = DeepLinkParser.parse(Uri.parse("chatui://chat/$conversationId/"))

        assertThat(parsed).isEqualTo(conversationId)
    }

    @Test
    fun `rejects invalid id characters`() {
        assertThat(DeepLinkParser.parse(Uri.parse("chatui://chat/not-a-uuid"))).isNull()
        assertThat(
            DeepLinkParser.parse(Uri.parse("chatui://chat/0190a5c4-1d2e-7abc-8def-0123456789ag")),
        ).isNull()
    }

    @Test
    fun `rejects a non-v7 uuid`() {
        val parsed = DeepLinkParser.parse(
            Uri.parse("chatui://chat/0190a5c4-1d2e-4abc-8def-0123456789ab"),
        )

        assertThat(parsed).isNull()
    }

    @Test
    fun `rejects query parameters`() {
        val parsed = DeepLinkParser.parse(
            Uri.parse("chatui://chat/$conversationId?token=secret"),
        )

        assertThat(parsed).isNull()
    }

    @Test
    fun `rejects a fragment`() {
        val parsed = DeepLinkParser.parse(
            Uri.parse("chatui://chat/$conversationId#secret"),
        )

        assertThat(parsed).isNull()
    }

    @Test
    fun `rejects user info`() {
        val parsed = DeepLinkParser.parse(
            Uri.parse("chatui://user@chat/$conversationId"),
        )

        assertThat(parsed).isNull()
    }

    @Test
    fun `rejects an explicit port`() {
        val parsed = DeepLinkParser.parse(
            Uri.parse("chatui://chat:8080/$conversationId"),
        )

        assertThat(parsed).isNull()
    }

    @Test
    fun `pure parser rejects a blank segment`() {
        assertThat(
            DeepLinkParser.parse(
                scheme = "chatui",
                host = "chat",
                pathSegments = listOf(" "),
            ),
        ).isNull()
    }
}
