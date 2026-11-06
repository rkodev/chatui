package dev.chatui.intent

import android.content.Intent

/**
 * Converts an inbound activity intent into a single external action.
 *
 * Share intents take precedence because they carry user content; otherwise a
 * valid `chatui://chat/<id>` deep link is accepted. Unsupported intents return
 * `null` so the activity can ignore them without side effects.
 */
object ExternalIntentIntake {
    fun actionFor(intent: Intent?): ExternalIntentAction? {
        if (intent == null) return null

        ShareTextParser.parse(intent)?.let { text ->
            return ExternalIntentAction.ShareText(text)
        }
        DeepLinkParser.parse(intent)?.let { conversationId ->
            return ExternalIntentAction.OpenChat(conversationId)
        }

        return null
    }
}
