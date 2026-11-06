package dev.chatui.startup

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.database.recovery.InterruptedMessageRecovery
import dev.chatui.core.database.recovery.RecoveryResult
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AppStartupTest {
    private val startedAt = Instant.parse("2026-11-06T12:00:00Z")
    private val clock = Clock.fixed(startedAt, ZoneOffset.UTC)

    @Test
    fun `runs recovery once per process`() = runTest {
        val recovery = FakeRecovery()
        val startup = AppStartup(recovery, FakeCleanup(), clock)

        startup.run()
        startup.run()

        assertThat(recovery.calls).isEqualTo(1)
    }

    @Test
    fun `concurrent callers run recovery once`() = runTest {
        val recovery = FakeRecovery()
        val startup = AppStartup(recovery, FakeCleanup(), clock)

        coroutineScope {
            repeat(5) { launch { startup.run() } }
        }

        assertThat(recovery.calls).isEqualTo(1)
    }

    @Test
    fun `a failed recovery is not retried in the same process`() = runTest {
        val recovery = FakeRecovery(fail = true)
        val startup = AppStartup(recovery, FakeCleanup(), clock)

        runCatching { startup.run() }
        startup.run()

        assertThat(recovery.calls).isEqualTo(1)
    }

    @Test
    fun `runs cleanup after recovery with the startup timestamp`() = runTest {
        val events = mutableListOf<String>()
        val recovery = FakeRecovery(events = events)
        val cleanup = FakeCleanup(events = events)
        val startup = AppStartup(recovery, cleanup, clock)

        startup.run()

        assertThat(events).containsExactly("recovery", "cleanup").inOrder()
        assertThat(cleanup.before).containsExactly(startedAt)
    }

    @Test
    fun `cleanup runs once per process`() = runTest {
        val cleanup = FakeCleanup()
        val startup = AppStartup(FakeRecovery(), cleanup, clock)

        startup.run()
        startup.run()

        assertThat(cleanup.calls).isEqualTo(1)
    }

    @Test
    fun `a failed cleanup does not block the shell and is recorded`() = runTest {
        val cleanup = FakeCleanup(fail = true)
        val startup = AppStartup(FakeRecovery(), cleanup, clock)

        startup.run()
        startup.run()

        assertThat(cleanup.calls).isEqualTo(1)
        assertThat(startup.lastCleanupFailure).isEqualTo("IllegalStateException")
    }

    @Test
    fun `cleanup is still attempted when recovery fails`() = runTest {
        val cleanup = FakeCleanup()
        val startup = AppStartup(FakeRecovery(fail = true), cleanup, clock)

        runCatching { startup.run() }

        assertThat(cleanup.calls).isEqualTo(1)
    }

    private class FakeRecovery(
        private val fail: Boolean = false,
        private val events: MutableList<String>? = null,
    ) : InterruptedMessageRecovery {
        var calls: Int = 0
            private set

        override suspend fun recoverInterruptedMessages(): RecoveryResult {
            calls += 1
            events?.add("recovery")
            delay(10)
            if (fail) throw IllegalStateException("recovery failed")
            return RecoveryResult(recovered = emptyList(), failed = emptyList())
        }
    }

    private class FakeCleanup(
        private val fail: Boolean = false,
        private val events: MutableList<String>? = null,
    ) : AttachmentCleanup {
        var calls: Int = 0
            private set
        val before = mutableListOf<Instant>()

        override suspend fun cleanupOrphans(before: Instant): List<String> {
            calls += 1
            this.before += before
            events?.add("cleanup")
            if (fail) throw IllegalStateException("cleanup failed")
            return emptyList()
        }
    }
}
