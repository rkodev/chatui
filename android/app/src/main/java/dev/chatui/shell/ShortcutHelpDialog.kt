package dev.chatui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Test tags exposed by the shortcut help dialog. */
object ShortcutHelpTestTags {
    const val Dialog = "shortcut-help-dialog"
    const val Close = "shortcut-help-close"

    fun row(keys: String): String = "shortcut-help-row-$keys"
}

/**
 * Discoverable shortcut map.
 *
 * Every row exposes a single accessible label that combines the key chord and
 * the action, so TalkBack reads the shortcut as one item instead of two
 * unrelated text nodes.
 */
@Composable
fun ShortcutHelpDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier.testTag(ShortcutHelpTestTags.Dialog),
        title = { Text("Keyboard shortcuts") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AndroidShortcutHelp.forEach { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(ShortcutHelpTestTags.row(entry.keys))
                            .semantics {
                                contentDescription = "${entry.keys}: ${entry.action}"
                            },
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = entry.keys,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = entry.action,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1.4f),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(ShortcutHelpTestTags.Close),
            ) {
                Text("Close")
            }
        },
    )
}
