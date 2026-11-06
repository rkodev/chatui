package dev.chatui.testing

import dev.chatui.core.model.Message
import dev.chatui.feature.chat.ChatMessageStore
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** In-memory message store that records writes and detects overlap. */
class FakeChatMessageStore(
    initial: List<Message> = emptyList(),
) : ChatMessageStore {
    private val messages = MutableStateFlow(initial)

    val writes: MutableList<Message> = mutableListOf()
    var writeDelayMs: Long = 0L

    private val concurrentWrites = AtomicInteger(0)
    var maxConcurrentWrites: Int = 0
        private set

    override fun observeConversation(conversationId: String): Flow<List<Message>> =
        messages.map { list ->
            list.filter { it.conversationId == conversationId }.sortedBy { it.sequence }
        }

    override suspend fun all(conversationId: String): List<Message> =
        messages.value
            .filter { it.conversationId == conversationId }
            .sortedBy { it.sequence }

    override suspend fun latest(conversationId: String, limit: Int): List<Message> =
        all(conversationId).takeLast(limit)

    override suspend fun older(
        conversationId: String,
        beforeSequence: Long,
        limit: Int,
    ): List<Message> = all(conversationId)
        .filter { it.sequence < beforeSequence }
        .takeLast(limit)

    override suspend fun appendMessage(message: Message): Message {
        recordWrite(message)
        messages.update { list -> list.filterNot { it.id == message.id } + message }
        return message
    }

    override suspend fun updateMessage(message: Message): Message {
        recordWrite(message)
        messages.update { list -> list.map { if (it.id == message.id) message else it } }
        return message
    }

    override suspend fun deleteMessage(id: String) {
        messages.update { list -> list.filterNot { it.id == id } }
    }

    private suspend fun recordWrite(message: Message) {
        val current = concurrentWrites.incrementAndGet()
        maxConcurrentWrites = maxOf(maxConcurrentWrites, current)

        try {
            if (writeDelayMs > 0) delay(writeDelayMs)
            writes += message
        } finally {
            concurrentWrites.decrementAndGet()
        }
    }
}
