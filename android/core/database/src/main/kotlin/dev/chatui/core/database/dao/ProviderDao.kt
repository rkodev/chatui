package dev.chatui.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import dev.chatui.core.database.entity.ProviderEntity
import kotlinx.coroutines.flow.Flow

/** Provider CRUD from spec 9.5. */
@Dao
abstract class ProviderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(provider: ProviderEntity)

    @Update
    abstract suspend fun update(provider: ProviderEntity)

    @Query("SELECT * FROM providers ORDER BY label")
    abstract fun observeAll(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers WHERE id = :id")
    abstract suspend fun getById(id: String): ProviderEntity?

    @Query("DELETE FROM providers WHERE id = :id")
    abstract suspend fun deleteById(id: String)

    @Query("DELETE FROM models WHERE providerId = :providerId")
    abstract suspend fun deleteModelsForProvider(providerId: String)

    @Query(
        "DELETE FROM secrets WHERE id IN " +
            "(SELECT secretId FROM providers WHERE id = :providerId AND secretId IS NOT NULL)",
    )
    abstract suspend fun deleteSecretsForProvider(providerId: String)

    /**
     * Delete a provider, its models, and its referenced secrets. Conversations
     * keep their provider/model snapshots and remain readable.
     */
    @Transaction
    open suspend fun deleteWithChildren(providerId: String) {
        deleteSecretsForProvider(providerId)
        deleteModelsForProvider(providerId)
        deleteById(providerId)
    }
}
