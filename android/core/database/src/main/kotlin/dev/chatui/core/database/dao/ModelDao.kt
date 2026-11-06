package dev.chatui.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.chatui.core.database.entity.ModelEntity
import kotlinx.coroutines.flow.Flow

/**
 * Model CRUD from spec 9.5.
 *
 * [insert] uses ABORT so the unique `(providerId, remoteId)` constraint is
 * observable to callers. [upsert] is the discovery-friendly variant.
 */
@Dao
abstract class ModelDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(model: ModelEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(model: ModelEntity)

    @Update
    abstract suspend fun update(model: ModelEntity)

    @Query("SELECT * FROM models WHERE providerId = :providerId ORDER BY label")
    abstract fun observeForProvider(providerId: String): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE providerId = :providerId ORDER BY label")
    abstract suspend fun forProvider(providerId: String): List<ModelEntity>

    @Query("SELECT * FROM models WHERE id = :id")
    abstract suspend fun getById(id: String): ModelEntity?

    /** Every model, including hidden rows, for export. */
    @Query("SELECT * FROM models")
    abstract suspend fun all(): List<ModelEntity>

    @Query("SELECT * FROM models WHERE providerId = :providerId AND remoteId = :remoteId")
    abstract suspend fun findByRemoteId(providerId: String, remoteId: String): ModelEntity?

    @Query("DELETE FROM models WHERE id = :id")
    abstract suspend fun deleteById(id: String)

    @Query("DELETE FROM models WHERE providerId = :providerId")
    abstract suspend fun deleteForProvider(providerId: String)
}
