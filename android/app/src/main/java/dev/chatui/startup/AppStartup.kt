package dev.chatui.startup

import dev.chatui.core.database.recovery.InterruptedMessageRecovery
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Application-scoped startup initializer.
 *
 * Runs interrupted-stream recovery exactly once per process before the shell
 * renders, so chat interaction cannot start against a stale `streaming` row.
 * The guard is both a fast path and a mutex-protected critical section, which
 * keeps concurrent callers from running recovery twice.
 */
@Singleton
class AppStartup @Inject constructor(
    private val recovery: InterruptedMessageRecovery,
) {
    private val mutex = Mutex()

    @Volatile
    private var completed = false

    suspend fun run() {
        if (completed) return

        mutex.withLock {
            if (completed) return

            try {
                recovery.recoverInterruptedMessages()
            } finally {
                completed = true
            }
        }
    }
}
