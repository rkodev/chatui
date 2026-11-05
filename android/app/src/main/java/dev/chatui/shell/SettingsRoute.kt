package dev.chatui.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.chatui.feature.settings.SettingsPlaceholder

/** Test tags exposed by the shell settings destination. */
object SettingsTestTags {
    const val ManageProviders = "settings-manage-providers"
}

/**
 * Shell settings destination.
 *
 * Settings screens arrive in M2-06. Until then this keeps the placeholder and
 * exposes the provider-management entry point required by the shell.
 */
@Composable
fun SettingsRoute(
    onOpenProviders: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        SettingsPlaceholder(modifier = Modifier.weight(1f))
        Button(
            onClick = onOpenProviders,
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .testTag(SettingsTestTags.ManageProviders),
        ) {
            Text("Manage providers")
        }
    }
}
