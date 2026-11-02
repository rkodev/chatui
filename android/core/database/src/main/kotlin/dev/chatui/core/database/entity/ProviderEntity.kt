package dev.chatui.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Provider row from spec 9.5. `secretId` references the encrypted secret
 * store; the key itself is never stored in this table.
 */
@Entity(
    tableName = "providers",
    indices = [
        Index("presetId"),
        Index("enabled"),
    ],
)
data class ProviderEntity(
    @PrimaryKey val id: String,
    val presetId: String,
    val label: String,
    val kind: String,
    val baseUrl: String,
    val authType: String,
    val secretId: String?,
    val headersJson: String,
    val enabled: Boolean,
    val browserCors: String,
    val metadataJson: String?,
    val createdAt: String,
    val updatedAt: String,
)
