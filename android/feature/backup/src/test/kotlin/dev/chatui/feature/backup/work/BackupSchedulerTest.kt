package dev.chatui.feature.backup.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupSchedulerTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val configuration = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
        workManager = WorkManager.getInstance(context)
    }

    @Test
    fun `schedule enqueues unique periodic work with network and battery constraints`() {
        BackupScheduler(workManager).schedulePeriodic()

        val infos = workInfos()

        assertThat(infos).hasSize(1)
        val info = infos.single()
        assertThat(info.state).isEqualTo(WorkInfo.State.ENQUEUED)
        assertThat(info.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
        assertThat(info.constraints.requiresBatteryNotLow()).isTrue()
        assertThat(info.periodicityInfo?.repeatIntervalMillis)
            .isEqualTo(TimeUnit.HOURS.toMillis(BackupScheduler.REPEAT_INTERVAL_HOURS))
    }

    @Test
    fun `repeated enable does not duplicate work`() {
        val scheduler = BackupScheduler(workManager)

        scheduler.schedulePeriodic()
        scheduler.schedulePeriodic()
        scheduler.schedulePeriodic()

        assertThat(workInfos()).hasSize(1)
    }

    @Test
    fun `cancel removes scheduled work`() {
        val scheduler = BackupScheduler(workManager)
        scheduler.schedulePeriodic()

        scheduler.cancel()

        assertThat(workInfos().none { it.state == WorkInfo.State.ENQUEUED }).isTrue()
    }

    private fun workInfos(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(BackupScheduler.UNIQUE_WORK_NAME).get()
}
