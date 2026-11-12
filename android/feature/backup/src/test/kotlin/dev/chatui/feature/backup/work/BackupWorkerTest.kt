package dev.chatui.feature.backup.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.backup.storage.StorageError
import dev.chatui.core.backup.storage.StorageErrorCode
import dev.chatui.core.backup.sync.SyncResult
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `successful sync maps to success`() = runTest {
        val runner = FakeRunner { syncResult() }

        val result = worker(runner).doWork()

        assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
        assertThat(runner.calls).isEqualTo(1)
    }

    @Test
    fun `transient storage failures map to retry`() = runTest {
        listOf(
            StorageErrorCode.NETWORK,
            StorageErrorCode.SERVER,
            StorageErrorCode.CONFLICT,
        ).forEach { code ->
            val runner = FakeRunner { throw StorageError(code, "transient") }

            val result = worker(runner).doWork()

            assertThat(result).isInstanceOf(ListenableWorker.Result.Retry::class.java)
        }
    }

    @Test
    fun `io failures map to retry`() = runTest {
        val runner = FakeRunner { throw IOException("offline") }

        val result = worker(runner).doWork()

        assertThat(result).isInstanceOf(ListenableWorker.Result.Retry::class.java)
    }

    @Test
    fun `permanent storage failures map to failure`() = runTest {
        listOf(
            StorageErrorCode.AUTH,
            StorageErrorCode.PERMISSION,
            StorageErrorCode.NOT_FOUND,
        ).forEach { code ->
            val runner = FakeRunner { throw StorageError(code, "permanent") }

            val result = worker(runner).doWork()

            assertThat(result).isInstanceOf(ListenableWorker.Result.Failure::class.java)
        }
    }

    @Test
    fun `unexpected failures map to failure`() = runTest {
        val runner = FakeRunner { throw IllegalStateException("missing passphrase") }

        val result = worker(runner).doWork()

        assertThat(result).isInstanceOf(ListenableWorker.Result.Failure::class.java)
    }

    @Test
    fun `worker adds no input data and only calls the sync runner`() = runTest {
        val runner = FakeRunner { syncResult() }
        val worker = worker(runner)

        assertThat(worker.inputData.keyValueMap).isEmpty()
        worker.doWork()

        assertThat(runner.calls).isEqualTo(1)
    }

    private fun worker(runner: BackupSyncRunner): BackupWorker {
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker? =
                if (workerClassName == BackupWorker::class.java.name) {
                    BackupWorker(appContext, workerParameters, runner)
                } else {
                    null
                }
        }
        return TestListenableWorkerBuilder<BackupWorker>(context)
            .setWorkerFactory(factory)
            .build()
    }

    private fun syncResult() = SyncResult(
        uploadedConversations = emptyList(),
        uploadedAttachments = emptyList(),
        downloadedConversations = emptyList(),
        manifestEtag = null,
        drainedQueue = 0,
    )
}

private class FakeRunner(
    private val block: suspend () -> SyncResult,
) : BackupSyncRunner {
    var calls = 0
        private set

    override suspend fun sync(): SyncResult {
        calls += 1
        return block()
    }
}
