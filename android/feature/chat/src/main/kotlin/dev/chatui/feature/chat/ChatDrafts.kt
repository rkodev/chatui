package dev.chatui.feature.chat

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-conversation composer drafts.
 *
 * Drafts are kept outside the ViewModel so navigating between conversations
 * restores the text the user typed. The M1 store is in-memory; persistence
 * across process death is a later enhancement.
 */
interface ChatDrafts {
    fun draftFor(conversationId: String): String

    fun setDraft(conversationId: String, text: String)

    fun clearDraft(conversationId: String)
}

/** Thread-safe in-memory draft store used by the application and tests. */
class InMemoryChatDrafts : ChatDrafts {
    private val drafts = ConcurrentHashMap<String, String>()

    override fun draftFor(conversationId: String): String = drafts[conversationId].orEmpty()

    override fun setDraft(conversationId: String, text: String) {
        if (text.isEmpty()) {
            drafts.remove(conversationId)
        } else {
            drafts[conversationId] = text
        }
    }

    override fun clearDraft(conversationId: String) {
        drafts.remove(conversationId)
    }
}
