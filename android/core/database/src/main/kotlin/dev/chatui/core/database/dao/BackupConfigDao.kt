package dev.chatui.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.chatui.core.database.entity.BackupConfigEntity
import kotlinx.coroutines.flow.Flow

/** Future backup configuration placeholder from spec 9.5. */
@Dao
abstract class BackupConfigDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(config: BackupConfigEntity)

    @Query("SELECT * FROM backup_config WHERE id = :id")
    abstract suspend fun getById(id: String): BackupConfigEntity?

    @Query("SELECT * FROM backup_config LIMIT 1")
    abstract fun observe(): Flow<BackupConfigEntity?>

    @Query("DELETE FROM backup_config WHERE id = :id")
    abstract suspend fun deleteById(id: String)

    companion object {
        const val DEFAULT_ID: String = "default"
    }
}
