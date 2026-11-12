package dev.chatui.feature.backup.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.android.EntryPointAccessors
import dev.chatui.core.backup.storage.StorageError
import dev.chatui.core.backup.storage.StorageErrorCode
import dev.chatui.feature.backup.BackupWorkerEntryPoint
import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * Periodic backup worker.
 *
 * The worker only calls [BackupSyncRunner.sync]. It never receives plaintext,
 * credentials, or a storage adapter, and it never logs content or secrets.
 *
 * Transient storage failures (network, server, conflict) and I/O failures map
 * to [Result.retry]. Authentication, permission, configuration, and other
 * permanent failures map to [Result.failure].
 */
class BackupWorker @JvmOverloads constructor(
    appContext: Context,
    params: WorkerParameters,
    private val runner: BackupSyncRunner? = null,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val syncRunner = runner ?: resolveRunner() ?: return Result.failure()

        return try {
            syncRunner.sync()
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (isTransient(error)) Result.retry() else Result.failure()
        }
    }

    private fun resolveRunner(): BackupSyncRunner? = runCatching {
        EntryPointAccessors.fromApplication(
            applicationContext,
            BackupWorkerEntryPoint::class.java,
        ).backupSyncRunner()
    }.getOrNull()

    private fun isTransient(error: Throwable): Boolean = when (error) {
        is StorageError -> error.code in TRANSIENT_STORAGE_CODES
        is IOException -> true
        else -> false
    }

    private companion object {
        val TRANSIENT_STORAGE_CODES = setOf(
            StorageErrorCode.NETWORK,
            StorageErrorCode.SERVER,
            StorageErrorCode.CONFLICT,
        )
    }
}
