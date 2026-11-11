package dev.chatui.feature.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.chatui.core.designsystem.BannerSeverity
import dev.chatui.core.designsystem.InlineBanner
import dev.chatui.core.designsystem.LocalDimens
import dev.chatui.core.model.Model

/** Test tags exposed by the global defaults screen. */
object DefaultsTestTags {
    const val Screen = "defaults-screen"
    const val Summary = "defaults-summary"
    const val Warning = "defaults-warning"
}

/**
 * Global default provider/model screen.
 *
 * Selecting a model persists the default immediately. An unavailable default
 * shows a warning and is never silently substituted.
 */
@Composable
fun DefaultsScreen(
    state: ModelPickerUiState,
    onQueryChanged: (String) -> Unit,
    onSelect: (Model) -> Unit,
    onPin: (Model, Boolean) -> Unit,
    onHide: (Model, Boolean) -> Unit,
    onManualRemoteIdChanged: (String) -> Unit,
    onManualLabelChanged: (String) -> Unit,
    onAddManual: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalDimens.current

    ModelPickerContent(
        state = state,
        title = "Default model",
        modifier = modifier.testTag(DefaultsTestTags.Screen),
        onQueryChanged = onQueryChanged,
        onSelect = onSelect,
        onPin = onPin,
        onHide = onHide,
        onManualRemoteIdChanged = onManualRemoteIdChanged,
        onManualLabelChanged = onManualLabelChanged,
        onAddManual = onAddManual,
        onRefresh = onRefresh,
        header = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(dimens.space1),
            ) {
                val provider = state.providers.firstOrNull { it.id == state.providerId }
                val model = state.selectedModel
                Text(
                    text = "Provider: ${provider?.label ?: "Not configured"}",
                    modifier = Modifier.testTag(DefaultsTestTags.Summary),
                )
                Text(
                    text = "Model: ${model?.label ?: "Not selected"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                state.warning?.let { warning ->
                    InlineBanner(
                        severity = BannerSeverity.Warning,
                        title = "Model unavailable",
                        body = warning,
                        modifier = Modifier.testTag(DefaultsTestTags.Warning),
                    )
                }
            }
        },
    )
}
