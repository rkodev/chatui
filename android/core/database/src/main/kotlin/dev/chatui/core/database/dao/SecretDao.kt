package dev.chatui.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.chatui.core.database.entity.SecretEntity

/**
 * Ciphertext-only secret CRUD from spec 9.5.
 *
 * The DAO never accepts or returns plaintext; encryption and decryption belong
 * to the Tink-backed secret store in `core:datastore`.
 */
@Dao
abstract class SecretDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(secret: SecretEntity)

    @Query("SELECT * FROM secrets WHERE id = :id")
    abstract suspend fun getById(id: String): SecretEntity?

    @Query("SELECT * FROM secrets ORDER BY id")
    abstract suspend fun getAll(): List<SecretEntity>

    @Query("DELETE FROM secrets WHERE id = :id")
    abstract suspend fun deleteById(id: String)

    @Query("DELETE FROM secrets")
    abstract suspend fun deleteAll()
}
