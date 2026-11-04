package dev.chatui.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.chatui.core.designsystem.LocalDimens
import dev.chatui.core.model.SendKey

/** Test tags exposed by the chat composer. */
object ChatComposerTestTags {
    const val Composer = "chat-composer"
    const val Input = "chat-composer-input"
    const val Send = "chat-composer-send"
    const val Stop = "chat-composer-stop"
    const val Offline = "chat-composer-offline"
}

/**
 * Auto-growing chat composer.
 *
 * The component is presentational: the draft, send-key mode, streaming flag,
 * and availability come from the ViewModel. Drafts are hoisted to
 * [ChatDrafts] so switching conversations restores what the user typed.
 */
@Composable
fun ChatComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    sendKey: SendKey,
    enabled: Boolean,
    streaming: Boolean,
    sending: Boolean,
    modifier: Modifier = Modifier,
    offline: Boolean = false,
) {
    val dimens = LocalDimens.current
    val canSend = enabled && !offline && !streaming && !sending && draft.isNotBlank()

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(ChatComposerTestTags.Composer),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(dimens.padding),
            verticalArrangement = Arrangement.spacedBy(dimens.space2),
        ) {
            if (offline) {
                Text(
                    text = "You are offline. Sending is disabled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(ChatComposerTestTags.Offline),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(dimens.space2),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(ChatComposerTestTags.Input)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            if (event.key != Key.Enter) return@onPreviewKeyEvent false
                            if (event.isShiftPressed) return@onPreviewKeyEvent false

                            val modifierPressed = event.isCtrlPressed || event.isMetaPressed
                            val shouldSend = sendKey == SendKey.ENTER || modifierPressed

                            if (!shouldSend || !canSend) {
                                false
                            } else {
                                onSend()
                                true
                            }
                        },
                    placeholder = { Text("Send a message…") },
                    enabled = enabled,
                    minLines = 1,
                    maxLines = 8,
                    keyboardOptions = KeyboardOptions(
                        imeAction = if (sendKey == SendKey.ENTER) {
                            ImeAction.Send
                        } else {
                            ImeAction.Default
                        },
                    ),
                    keyboardActions = if (sendKey == SendKey.ENTER) {
                        KeyboardActions(
                            onSend = {
                                if (canSend) onSend()
                            },
                        )
                    } else {
                        KeyboardActions()
                    },
                )

                if (streaming || sending) {
                    TextButton(
                        onClick = onStop,
                        modifier = Modifier
                            .heightIn(min = dimens.touchTargetMin)
                            .testTag(ChatComposerTestTags.Stop),
                    ) {
                        Text("Stop")
                    }
                } else {
                    Button(
                        onClick = onSend,
                        enabled = canSend,
                        modifier = Modifier
                            .heightIn(min = dimens.touchTargetMin)
                            .testTag(ChatComposerTestTags.Send),
                    ) {
                        Text("Send")
                    }
                }
            }
        }
    }
}
