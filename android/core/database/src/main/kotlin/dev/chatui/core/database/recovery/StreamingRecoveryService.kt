package dev.chatui.core.database.recovery

import dev.chatui.core.database.repository.MessageRepository
import dev.chatui.core.model.ChatError
import dev.chatui.core.model.ChatErrorCode
import dev.chatui.core.model.FinishReason
import dev.chatui.core.model.Message
import dev.chatui.core.model.MessageStatus
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CancellationException

/**
 * Explanation stored on a message that was left streaming when the app closed.
 *
 * The partial content is never removed. The message status becomes `cancelled`
 * and this note is attached through the normalized error field so the chat
 * screen can explain why the response stopped.
 */
const val INTERRUPTION_NOTE: String =
    "The app closed before this response finished. Partial content is preserved."

/** A message that could not be recovered, with the failure that stopped it. */
data class RecoveryFailure(
    val message: Message,
    val error: Throwable,
)

/** Outcome of one startup recovery pass. */
data class RecoveryResult(
    val recovered: List<Message>,
    val failed: List<RecoveryFailure>,
)

/** Narrow surface used by the startup initializer and tests. */
interface InterruptedMessageRecovery {
    suspend fun recoverInterruptedMessages(): RecoveryResult
}

/**
 * Mark messages left in `streaming` as cancelled on startup.
 *
 * Each recovered message keeps its id, sequence, parts, usage, and timestamps;
 * only the terminal status, finish reason, error note, and `updatedAt` change.
 * Re-running recovery is safe because the second pass finds no streaming
 * messages. Per-message write failures are collected so one bad row does not
 * block the rest of the scan.
 */
class StreamingRecoveryService(
    private val messages: MessageRepository,
    private val clock: Clock = Clock.systemUTC(),
) : InterruptedMessageRecovery {
    override suspend fun recoverInterruptedMessages(): RecoveryResult {
        val streaming = messages.streamingMessages()
        val recovered = mutableListOf<Message>()
        val failed = mutableListOf<RecoveryFailure>()

        for (message in streaming) {
            val at = clock.instant()
            val updated = message.copy(
                status = MessageStatus.CANCELLED,
                finishReason = FinishReason.CANCELLED,
                error = interruptionError(message, at),
                updatedAt = at,
            )

            try {
                messages.updateMessage(updated)
                recovered += updated
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failed += RecoveryFailure(message, error)
            }
        }

        return RecoveryResult(recovered = recovered, failed = failed)
    }

    private fun interruptionError(message: Message, at: Instant): ChatError = ChatError(
        code = ChatErrorCode.CANCELLED,
        message = INTERRUPTION_NOTE,
        providerId = message.providerId,
        status = null,
        retryable = false,
        retryAfterSeconds = null,
        requestId = null,
        details = null,
        at = at,
    )
}
