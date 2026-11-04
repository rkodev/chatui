package dev.chatui.core.database.repository

import dev.chatui.core.database.dao.ProviderDao
import dev.chatui.core.model.Provider
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Domain repository for providers.
 *
 * [upsertPreservingFlags] is the discovery/preset refresh path: it keeps the
 * user's enabled state and key reference while updating non-secret metadata.
 * Use [upsert] for explicit user edits.
 */
class ProviderRepository(
    private val dao: ProviderDao,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun observeAll(): Flow<List<Provider>> =
        dao.observeAll().map { providers -> providers.map { it.toDomain() } }

    suspend fun get(id: String): Provider? = dao.getById(id)?.toDomain()

    suspend fun upsert(provider: Provider): Provider {
        dao.upsert(provider.toEntity())
        return provider
    }

    /**
     * Upsert a provider while preserving user-owned state.
     *
     * Existing `enabled` and `secretId` values win. A non-null incoming
     * `secretId` is used only when the stored provider has no key. `createdAt`
     * is preserved for existing rows.
     */
    suspend fun upsertPreservingFlags(provider: Provider): Provider {
        val existing = dao.getById(provider.id)?.toDomain()
        val merged = if (existing == null) {
            provider
        } else {
            provider.copy(
                enabled = existing.enabled,
                secretId = existing.secretId ?: provider.secretId,
                createdAt = existing.createdAt,
            )
        }

        dao.upsert(merged.toEntity())
        return merged
    }

    suspend fun setEnabled(
        id: String,
        enabled: Boolean,
        updatedAt: Instant = clock.instant(),
    ): Boolean {
        val existing = dao.getById(id)?.toDomain() ?: return false
        dao.update(
            existing.copy(
                enabled = enabled,
                updatedAt = updatedAt,
            ).toEntity(),
        )
        return true
    }

    /** Delete a provider, its models, and its referenced secret. */
    suspend fun delete(id: String) = dao.deleteWithChildren(id)
}
