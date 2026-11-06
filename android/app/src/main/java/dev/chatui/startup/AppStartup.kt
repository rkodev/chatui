package dev.chatui.startup

import dev.chatui.core.database.recovery.InterruptedMessageRecovery
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Startup dependency that removes orphan attachment metadata and bytes.
 *
 * The production binding delegates to
 * [dev.chatui.core.database.attachment.AttachmentService.cleanupOrphans]. The
 * interface keeps [AppStartup] testable with a fake and avoids a startup
 * dependency on the concrete attachment service.
 */
interface AttachmentCleanup {
    suspend fun cleanupOrphans(before: Instant): List<String>
}

/**
 * Application-scoped startup initializer.
 *
 * Runs interrupted-stream recovery and orphan-attachment cleanup exactly once
 * per process before the shell renders, so chat interaction cannot start
 * against a stale `streaming` row or stale attachment storage. The guard is
 * both a fast path and a mutex-protected critical section, which keeps
 * concurrent callers from running startup work twice.
 *
 * Cleanup is best-effort: a failure is recorded as a non-sensitive class name
 * and never blocks the shell. Recovery failures keep their existing behavior
 * and still propagate to the caller after cleanup has been attempted.
 */
@Singleton
class AppStartup @Inject constructor(
    private val recovery: InterruptedMessageRecovery,
    private val attachmentCleanup: AttachmentCleanup,
    private val clock: Clock,
) {
    private val mutex = Mutex()

    @Volatile
    private var completed = false

    /**
     * Non-sensitive record of the last cleanup failure, or null when cleanup
     * succeeded. Only the exception class name is kept; messages and paths are
     * never stored.
     */
    @Volatile
    var lastCleanupFailure: String? = null
        private set

    suspend fun run() {
        if (completed) return

        mutex.withLock {
            if (completed) return

            // Capture the startup instant before recovery so an attachment
            // created at any point after startup began is never treated as an
            // orphan, even if recovery takes a moment.
            val startedAt = clock.instant()

            try {
                recovery.recoverInterruptedMessages()
            } finally {
                try {
                    attachmentCleanup.cleanupOrphans(startedAt)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    lastCleanupFailure = error::class.java.simpleName
                } finally {
                    completed = true
                }
            }
        }
    }
}
