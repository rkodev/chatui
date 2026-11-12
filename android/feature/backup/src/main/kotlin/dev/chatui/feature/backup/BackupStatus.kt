package dev.chatui.feature.backup

import java.time.Instant

/**
 * Status shown on the backup settings screen.
 *
 * [storageUsageBytes] is an approximate local data size that a backup would
 * upload, not a remote bucket quota. [lastError] is the most recent manual
 * action failure held in memory; it is never persisted.
 */
data class BackupStatus(
    val enabled: Boolean = false,
    val lastBackupAt: Instant? = null,
    val pendingChanges: Int = 0,
    val lastError: String? = null,
    val storageUsageBytes: Long = 0,
)

/**
 * Read-only status source for the backup screen.
 *
 * Kept separate from the sync engine so the ViewModel and service can be tested
 * without a Room database.
 */
interface BackupStatusSource {
    /** Number of queued local changes waiting for the next sync. */
    suspend fun pendingChanges(): Int

    /** Approximate local data size that a backup would upload. */
    suspend fun storageUsageBytes(): Long
}
