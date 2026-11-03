package dev.chatui.feature.chat

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.chatui.core.database.recovery.INTERRUPTION_NOTE
import dev.chatui.core.model.ChatErrorCode
import dev.chatui.core.model.Message
import dev.chatui.core.model.MessageStatus

/** Test tag for the interrupted-message explanation. */
const val INTERRUPTED_MESSAGE_TAG: String = "chat-message-interrupted"

/**
 * Explanation to show under a message that startup recovery marked cancelled.
 *
 * Returns `null` for every other message so the chat item can render the
 * notice conditionally. The note itself is attached by
 * `StreamingRecoveryService` through the normalized error field.
 */
fun Message.interruptionExplanation(): String? {
    val error = error

    return if (
        status == MessageStatus.CANCELLED &&
        error?.code == ChatErrorCode.CANCELLED &&
        error.message == INTERRUPTION_NOTE
    ) {
        error.message
    } else {
        null
    }
}

/**
 * Interrupted-state notice for a recovered message.
 *
 * M1-09 mounts this under the assistant message item. It renders nothing for
 * messages that were not interrupted by process death.
 */
@Composable
fun InterruptedMessageNotice(
    message: Message,
    modifier: Modifier = Modifier,
) {
    val explanation = message.interruptionExplanation() ?: return

    Text(
        text = explanation,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .padding(top = 4.dp)
            .testTag(INTERRUPTED_MESSAGE_TAG),
    )
}
