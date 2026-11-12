package dev.chatui.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.chatui.core.database.entity.TombstoneEntity

/** Deletion tombstone operations from spec 13.5. */
@Dao
abstract class TombstoneDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(tombstone: TombstoneEntity)

    @Query("SELECT * FROM tombstones ORDER BY deletedAt, id")
    abstract suspend fun all(): List<TombstoneEntity>

    @Query(
        "SELECT * FROM tombstones WHERE conversationId = :conversationId " +
            "ORDER BY deletedAt, id",
    )
    abstract suspend fun forConversation(conversationId: String): List<TombstoneEntity>

    @Query("DELETE FROM tombstones WHERE id = :id")
    abstract suspend fun deleteById(id: String)

    @Query("DELETE FROM tombstones WHERE conversationId = :conversationId")
    abstract suspend fun deleteForConversation(conversationId: String)

    @Query("DELETE FROM tombstones")
    abstract suspend fun clear()
}
