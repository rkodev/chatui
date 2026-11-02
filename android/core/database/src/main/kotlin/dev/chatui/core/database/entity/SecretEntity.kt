package dev.chatui.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Ciphertext-only secret row from spec 9.5.
 *
 * There is intentionally no plaintext column. The ciphertext envelope is
 * produced by the Tink-backed secret store in `core:datastore`.
 */
@Entity(tableName = "secrets")
data class SecretEntity(
    @PrimaryKey val id: String,
    val ciphertext: String,
    val createdAt: String,
    val updatedAt: String,
)
