package dev.chatui.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Future backup configuration placeholder from spec 6.2 and 9.5.
 *
 * M0 only needs the durable shape. Backup behavior, credential handling, and
 * sync semantics are owned by M5.
 */
@Entity(tableName = "backup_config")
data class BackupConfigEntity(
    @PrimaryKey val id: String,
    val enabled: Boolean,
    val backend: String,
    val endpoint: String?,
    val region: String?,
    val bucket: String,
    val prefix: String,
    val credentialSecretId: String?,
    val encryption: String,
    val kdf: String,
    val kdfSalt: String?,
    val lastSyncAt: String?,
    val lastManifestEtag: String?,
    val deviceId: String,
    val updatedAt: String,
)
