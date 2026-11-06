package dev.chatui.testing

import dev.chatui.core.model.Conversation
import dev.chatui.feature.chat.ChatContextResult
import dev.chatui.feature.chat.ChatContextResolver
import dev.chatui.feature.chat.ChatConversationSource

/** Resolver that returns a fixed [ChatContextResult]. */
class FakeChatContextResolver(
    var result: ChatContextResult = ChatContextResult.Ready(testChatContext()),
) : ChatContextResolver {
    override suspend fun resolve(conversation: Conversation): ChatContextResult = result
}

/** Conversation lookup backed by a single mutable conversation. */
class FakeChatConversationSource(
    var conversation: Conversation? = testConversation(),
) : ChatConversationSource {
    override suspend fun getConversation(id: String): Conversation? = conversation
}
