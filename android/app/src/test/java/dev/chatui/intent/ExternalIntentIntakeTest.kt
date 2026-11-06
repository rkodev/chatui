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
class ExternalIntentIntakeTest {
    private val conversationId = "0190a5c4-1d2e-7abc-8def-0123456789ab"

    @Test
    fun `cold start launch intent becomes a pending deep link action`() {
        val router = ExternalIntentRouter()
        val launchIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("chatui://chat/$conversationId"),
        )

        ExternalIntentIntake.actionFor(launchIntent)?.let(router::submit)

        assertThat(router.pending.value)
            .isEqualTo(ExternalIntentAction.OpenChat(conversationId))
    }

    @Test
    fun `warm start new intent is delivered once`() {
        val router = ExternalIntentRouter()
        val newIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("chatui://chat/$conversationId"),
        )

        repeat(2) {
            ExternalIntentIntake.actionFor(newIntent)?.let(router::submit)
        }

        assertThat(router.pending.value)
            .isEqualTo(ExternalIntentAction.OpenChat(conversationId))
    }

    @Test
    fun `a redelivered link after consumption is ignored`() {
        val router = ExternalIntentRouter()
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("chatui://chat/$conversationId"),
        )
        val action = ExternalIntentIntake.actionFor(intent)!!
        router.submit(action)
        router.consume(action)

        ExternalIntentIntake.actionFor(intent)?.let(router::submit)

        assertThat(router.pending.value).isNull()
    }

    @Test
    fun `share intents still take precedence`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "shared text")
        }

        assertThat(ExternalIntentIntake.actionFor(intent))
            .isEqualTo(ExternalIntentAction.ShareText("shared text"))
    }

    @Test
    fun `unsupported intents are ignored`() {
        assertThat(ExternalIntentIntake.actionFor(null)).isNull()
        assertThat(
            ExternalIntentIntake.actionFor(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
            ),
        ).isNull()
    }
}
