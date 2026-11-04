package dev.chatui.feature.chat

import dev.chatui.core.model.Conversation

class FakeChatContextResolver(
    var result: ChatContextResult = ChatContextResult.Ready(testChatContext()),
) : ChatContextResolver {
    override suspend fun resolve(conversation: Conversation): ChatContextResult = result
}

class FakeChatConversationSource(
    var conversation: Conversation? = testConversation(),
) : ChatConversationSource {
    override suspend fun getConversation(id: String): Conversation? = conversation
}
