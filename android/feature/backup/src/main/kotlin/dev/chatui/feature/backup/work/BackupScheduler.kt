package dev.chatui.feature.backup.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Scheduling surface used by the backup settings flow. */
interface BackupScheduling {
    fun schedulePeriodic()

    fun cancel()
}

/** No-op scheduling used by tests and non-Android callers. */
object NoOpBackupScheduling : BackupScheduling {
    override fun schedulePeriodic() = Unit

    override fun cancel() = Unit
}

/**
 * WorkManager-backed periodic backup scheduler.
 *
 * Work is unique, so repeated enables update the existing request instead of
 * creating duplicates. WorkManager persists periodic work across process death
 * and device reboots, so no boot receiver or foreground service is needed.
 * Timing is best-effort: Android may defer work to respect battery and network
 * constraints.
 */
class BackupScheduler private constructor(
    private val workManagerProvider: () -> WorkManager,
) : BackupScheduling {
    constructor(workManager: WorkManager) : this({ workManager })

    constructor(context: Context) : this({ WorkManager.getInstance(context) })

    override fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<BackupWorker>(
            REPEAT_INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                MIN_BACKOFF_SECONDS,
                TimeUnit.SECONDS,
            )
            .build()

        workManagerProvider().enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    override fun cancel() {
        workManagerProvider().cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    companion object {
        /** Stable unique name so repeated enables never duplicate work. */
        const val UNIQUE_WORK_NAME: String = "chatui-backup-periodic"

        /** Backup cadence. WorkManager's minimum periodic interval is 15 minutes. */
        const val REPEAT_INTERVAL_HOURS: Long = 6L

        /** Exponential backoff for transient failures. */
        const val MIN_BACKOFF_SECONDS: Long = 30L
    }
}
