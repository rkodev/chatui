package dev.chatui.core.database.repository

import dev.chatui.core.database.dao.ConversationDao
import dev.chatui.core.model.Conversation
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Domain repository for conversations.
 *
 * Reads expose [Conversation] flows; writes accept domain types so feature
 * modules never depend on Room entities.
 */
class ConversationRepository(
    private val dao: ConversationDao,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun observeAll(): Flow<List<Conversation>> =
        dao.observeAll().map { conversations -> conversations.map { it.toDomain() } }

    fun observeActive(): Flow<List<Conversation>> =
        dao.observeActive().map { conversations -> conversations.map { it.toDomain() } }

    fun observeArchived(): Flow<List<Conversation>> =
        dao.observeArchived().map { conversations -> conversations.map { it.toDomain() } }

    suspend fun get(id: String): Conversation? = dao.getById(id)?.toDomain()

    /** Every conversation, including archived rows, for export. */
    suspend fun all(): List<Conversation> = dao.all().map { it.toDomain() }

    /**
     * Search active conversation titles.
     *
     * The query is trimmed and `LIKE` wildcards are escaped so a user typing
     * `%` or `_` cannot turn the search into a full-table match.
     */
    suspend fun searchByTitle(
        query: String,
        limit: Int = 50,
    ): List<Conversation> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val escaped = trimmed
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")

        return dao.searchByTitle("%$escaped%", limit).map { it.toDomain() }
    }

    suspend fun create(conversation: Conversation): Conversation {
        dao.upsert(conversation.toEntity())
        return conversation
    }

    suspend fun upsert(conversation: Conversation): Conversation {
        dao.upsert(conversation.toEntity())
        return conversation
    }

    suspend fun update(conversation: Conversation): Conversation {
        dao.update(conversation.toEntity())
        return conversation
    }

    suspend fun rename(
        id: String,
        title: String,
        updatedAt: Instant = clock.instant(),
    ): Boolean {
        if (dao.getById(id) == null) return false
        dao.updateTitle(id, title, updatedAt.toString())
        return true
    }

    suspend fun setPinned(
        id: String,
        pinned: Boolean,
        updatedAt: Instant = clock.instant(),
    ): Boolean {
        if (dao.getById(id) == null) return false
        dao.setPinned(id, pinned, updatedAt.toString())
        return true
    }

    suspend fun setArchived(
        id: String,
        archived: Boolean,
        updatedAt: Instant = clock.instant(),
    ): Boolean {
        if (dao.getById(id) == null) return false
        dao.setArchived(id, archived, updatedAt.toString())
        return true
    }

    /** Hard-delete a conversation and its messages, attachments, and FTS rows. */
    suspend fun delete(id: String) = dao.deleteWithChildren(id)
}
