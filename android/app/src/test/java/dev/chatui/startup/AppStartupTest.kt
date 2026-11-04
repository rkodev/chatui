package dev.chatui.startup

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.database.recovery.InterruptedMessageRecovery
import dev.chatui.core.database.recovery.RecoveryResult
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AppStartupTest {
    @Test
    fun `runs recovery once per process`() = runTest {
        val recovery = FakeRecovery()
        val startup = AppStartup(recovery)

        startup.run()
        startup.run()

        assertThat(recovery.calls).isEqualTo(1)
    }

    @Test
    fun `concurrent callers run recovery once`() = runTest {
        val recovery = FakeRecovery()
        val startup = AppStartup(recovery)

        coroutineScope {
            repeat(5) { launch { startup.run() } }
        }

        assertThat(recovery.calls).isEqualTo(1)
    }

    @Test
    fun `a failed recovery is not retried in the same process`() = runTest {
        val recovery = FakeRecovery(fail = true)
        val startup = AppStartup(recovery)

        runCatching { startup.run() }
        startup.run()

        assertThat(recovery.calls).isEqualTo(1)
    }

    private class FakeRecovery(
        private val fail: Boolean = false,
    ) : InterruptedMessageRecovery {
        var calls: Int = 0
            private set

        override suspend fun recoverInterruptedMessages(): RecoveryResult {
            calls += 1
            delay(10)
            if (fail) throw IllegalStateException("recovery failed")
            return RecoveryResult(recovered = emptyList(), failed = emptyList())
        }
    }
}
