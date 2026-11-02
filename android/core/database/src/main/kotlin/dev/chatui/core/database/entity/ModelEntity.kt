package dev.chatui.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Model row from spec 9.5.
 *
 * The unique `(providerId, remoteId)` index is the database-level guarantee
 * that a provider cannot expose the same remote model twice.
 */
@Entity(
    tableName = "models",
    indices = [
        Index(value = ["providerId", "remoteId"], unique = true),
    ],
)
data class ModelEntity(
    @PrimaryKey val id: String,
    val providerId: String,
    val remoteId: String,
    val label: String,
    val contextWindow: Int?,
    val maxOutputTokens: Int?,
    val capabilitiesJson: String,
    val pricingJson: String?,
    val canonicalSlug: String?,
    val inputModalitiesJson: String,
    val outputModalitiesJson: String,
    val supportedParametersJson: String,
    val metadataJson: String?,
    val hidden: Boolean,
    val pinned: Boolean,
    val source: String,
    val createdAt: String,
    val updatedAt: String,
)
