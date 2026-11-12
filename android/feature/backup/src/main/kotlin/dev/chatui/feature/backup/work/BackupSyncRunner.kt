package dev.chatui.feature.backup.work

import dev.chatui.core.backup.sync.SyncResult

/**
 * Minimal sync surface used by [BackupWorker].
 *
 * The production binding delegates to the backup settings service, which builds
 * a [dev.chatui.core.backup.sync.SyncEngine] from the saved configuration,
 * encrypted credentials, and the in-memory passphrase. The worker never
 * receives plaintext, credentials, or a storage adapter.
 */
fun interface BackupSyncRunner {
    suspend fun sync(): SyncResult
}
