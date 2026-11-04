package dev.chatui.feature.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.chatui.core.designsystem.LocalDimens
import dev.chatui.core.model.Message

/** Test tags exposed by the assistant-message model snapshot. */
object MessageModelSnapshotTestTags {
    const val Snapshot = "message-model-snapshot"
    const val Model = "message-model-snapshot-model"
    const val Provider = "message-model-snapshot-provider"
}

/**
 * Assistant-message provider/model snapshot.
 *
 * Snapshots are written when the message is appended and never rewritten when
 * the conversation switches models later.
 */
@Composable
fun MessageModelSnapshot(
    message: Message,
    modifier: Modifier = Modifier,
) {
    if (message.modelLabel == null && message.modelId == null) {
        return
    }

    val dimens = LocalDimens.current

    Row(
        modifier = modifier.testTag(MessageModelSnapshotTestTags.Snapshot),
        horizontalArrangement = Arrangement.spacedBy(dimens.space2),
    ) {
        Text(
            text = "Model: ${message.modelLabel ?: message.modelId}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(MessageModelSnapshotTestTags.Model),
        )
        message.providerId?.let { providerId ->
            Text(
                text = "Provider: $providerId",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(MessageModelSnapshotTestTags.Provider),
            )
        }
    }
}
