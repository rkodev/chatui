package dev.chatui.core.database.repository

import dev.chatui.core.database.dao.ModelDao
import dev.chatui.core.model.Model
import dev.chatui.core.model.ModelSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Domain repository for models.
 *
 * [upsertPreservingFlags] is the catalog refresh path: it keeps the user's
 * hidden/pinned flags, a manual model's source, and the original id/createdAt
 * while updating catalog fields.
 */
class ModelRepository(
    private val dao: ModelDao,
) {
    fun observeForProvider(providerId: String): Flow<List<Model>> =
        dao.observeForProvider(providerId).map { models -> models.map { it.toDomain() } }

    suspend fun forProvider(providerId: String): List<Model> =
        dao.forProvider(providerId).map { it.toDomain() }

    suspend fun get(id: String): Model? = dao.getById(id)?.toDomain()

    /** Every model, including hidden rows, for export. */
    suspend fun all(): List<Model> = dao.all().map { it.toDomain() }

    suspend fun findByRemoteId(providerId: String, remoteId: String): Model? =
        dao.findByRemoteId(providerId, remoteId)?.toDomain()

    /** Insert a model, surfacing the unique `(providerId, remoteId)` conflict. */
    suspend fun insert(model: Model): Model {
        dao.insert(model.toEntity())
        return model
    }

    suspend fun upsert(model: Model): Model {
        dao.upsert(model.toEntity())
        return model
    }

    /**
     * Upsert a model while preserving user-owned state.
     *
     * Existing `hidden`, `pinned`, `createdAt`, and id win. A model whose
     * stored source is [ModelSource.MANUAL] stays manual even when a catalog
     * refresh reports it as discovered.
     */
    suspend fun upsertPreservingFlags(model: Model): Model {
        val existing = dao.findByRemoteId(model.providerId, model.remoteId)
            ?: dao.getById(model.id)

        val merged = if (existing == null) {
            model
        } else {
            val stored = existing.toDomain()
            model.copy(
                id = stored.id,
                createdAt = stored.createdAt,
                hidden = stored.hidden,
                pinned = stored.pinned,
                source = if (stored.source == ModelSource.MANUAL) {
                    ModelSource.MANUAL
                } else {
                    model.source
                },
            )
        }

        dao.upsert(merged.toEntity())
        return merged
    }

    suspend fun update(model: Model): Model {
        dao.update(model.toEntity())
        return model
    }

    suspend fun delete(id: String) = dao.deleteById(id)

    suspend fun deleteForProvider(providerId: String) = dao.deleteForProvider(providerId)
}
