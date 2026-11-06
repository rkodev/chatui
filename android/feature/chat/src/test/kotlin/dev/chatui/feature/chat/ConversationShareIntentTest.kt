package dev.chatui.feature.chat

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConversationShareIntentTest {
    @Test
    fun `send intent is text plain and carries only the text extra`() {
        val intent = ConversationShareIntent.createSendIntent("Hello")

        assertThat(intent.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(intent.type).isEqualTo("text/plain")
        assertThat(intent.getStringExtra(Intent.EXTRA_TEXT)).isEqualTo("Hello")
        assertThat(intent.hasExtra(Intent.EXTRA_STREAM)).isFalse()
        assertThat(intent.clipData).isNull()
    }

    @Test
    fun `chooser wraps the send intent`() {
        val chooser = ConversationShareIntent.createChooserIntent("Hello", "Share conversation")

        assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
        assertThat(chooser.getCharSequenceExtra(Intent.EXTRA_TITLE))
            .isEqualTo("Share conversation")

        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertThat(send?.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(send?.type).isEqualTo("text/plain")
        assertThat(send?.getStringExtra(Intent.EXTRA_TEXT)).isEqualTo("Hello")
    }
}
