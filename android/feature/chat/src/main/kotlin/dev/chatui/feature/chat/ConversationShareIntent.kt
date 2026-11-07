package dev.chatui.feature.chat

import android.content.Intent

/** Builds the outbound `ACTION_SEND` intent for a conversation share. */
object ConversationShareIntent {
    const val MIME_TYPE = "text/plain"
    const val CHOOSER_TITLE = "Share conversation"

    fun createSendIntent(text: String): Intent = Intent(Intent.ACTION_SEND).apply {
        type = MIME_TYPE
        putExtra(Intent.EXTRA_TEXT, text)
    }

    fun createChooserIntent(
        text: String,
        chooserTitle: String = CHOOSER_TITLE,
    ): Intent = Intent.createChooser(createSendIntent(text), chooserTitle)
}
