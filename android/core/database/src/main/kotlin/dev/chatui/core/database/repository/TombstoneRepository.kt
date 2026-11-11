package dev.chatui.core.database.repository

import dev.chatui.core.database.dao.TombstoneDao
import dev.chatui.core.model.Tombstone

/**
 * Domain repository for deletion tombstones.
 *
 * Tombstones are retained until the remote manifest confirms the deletion.
 * This repository only persists and reads them; merge policy belongs to the
 * sync engine.
 */
class TombstoneRepository(
    private val dao: TombstoneDao,
) {
    suspend fun add(tombstone: Tombstone): Tombstone {
        dao.insert(tombstone.toEntity())
        return tombstone
    }

    suspend fun all(): List<Tombstone> = dao.all().map { it.toDomain() }

    suspend fun forConversation(conversationId: String): List<Tombstone> =
        dao.forConversation(conversationId).map { it.toDomain() }

    suspend fun delete(id: String) = dao.deleteById(id)

    suspend fun deleteForConversation(conversationId: String) =
        dao.deleteForConversation(conversationId)

    suspend fun clear() = dao.clear()
}
