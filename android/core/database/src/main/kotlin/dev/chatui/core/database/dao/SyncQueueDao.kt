package dev.chatui.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.chatui.core.database.entity.SyncQueueEntity

/** Future sync queue placeholder from spec 9.5. */
@Dao
abstract class SyncQueueDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(item: SyncQueueEntity)

    @Query("SELECT * FROM sync_queue ORDER BY createdAt")
    abstract suspend fun all(): List<SyncQueueEntity>

    @Query("SELECT * FROM sync_queue WHERE conversationId = :conversationId ORDER BY createdAt")
    abstract suspend fun forConversation(conversationId: String): List<SyncQueueEntity>

    @Query("DELETE FROM sync_queue WHERE id = :id")
    abstract suspend fun deleteById(id: String)

    @Query("DELETE FROM sync_queue")
    abstract suspend fun clear()
}
