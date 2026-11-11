package dev.chatui.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataSettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `usage and stored keys render`() {
        composeRule.setContent {
            ChatUiTheme {
                DataSettingsScreen(
                    state = state(),
                    onConfirmationPhraseChanged = {},
                    onClearAll = {},
                    onRequestRemoveKey = {},
                    onCancelRemoveKey = {},
                    onConfirmRemoveKey = {},
                    onDismissError = {},
                    onOpenProviders = {},
                    onOpenBackup = {},
                    onExportAll = {},
                    onImportMerge = {},
                    onImportReplace = {},
                )
            }
        }

        composeRule.onNodeWithTag(DataSettingsTestTags.Usage)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(DataSettingsTestTags.usageRow("conversations"))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(DataSettingsTestTags.UsageTotal)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(DataSettingsTestTags.Keys)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(DataSettingsTestTags.key("provider-1"))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `clear all requires the exact confirmation phrase`() {
        var cleared = 0
        var phrase by mutableStateOf("")

        composeRule.setContent {
            ChatUiTheme {
                DataSettingsScreen(
                    state = state(confirmationPhrase = phrase),
                    onConfirmationPhraseChanged = { phrase = it },
                    onClearAll = { cleared += 1 },
                    onRequestRemoveKey = {},
                    onCancelRemoveKey = {},
                    onConfirmRemoveKey = {},
                    onDismissError = {},
                    onOpenProviders = {},
                    onOpenBackup = {},
                    onExportAll = {},
                    onImportMerge = {},
                    onImportReplace = {},
                )
            }
        }

        composeRule.onNodeWithTag(DataSettingsTestTags.Clear)
            .performScrollTo()
            .performClick()

        composeRule.onNodeWithTag(DataSettingsTestTags.ClearButton)
            .assertIsNotEnabled()

        composeRule.onNodeWithTag(DataSettingsTestTags.ClearConfirmation)
            .performTextInput(CLEAR_ALL_CONFIRMATION)

        composeRule.onNodeWithTag(DataSettingsTestTags.ClearButton)
            .assertIsEnabled()
            .performClick()

        assertThat(cleared).isEqualTo(1)
    }

    @Test
    fun `per-key deletion requires a second confirmation`() {
        var requested: String? = null
        var confirmed: String? = null
        var confirmKeyId by mutableStateOf<String?>(null)

        composeRule.setContent {
            ChatUiTheme {
                DataSettingsScreen(
                    state = state(confirmKeyId = confirmKeyId),
                    onConfirmationPhraseChanged = {},
                    onClearAll = {},
                    onRequestRemoveKey = {
                        requested = it
                        confirmKeyId = it
                    },
                    onCancelRemoveKey = { confirmKeyId = null },
                    onConfirmRemoveKey = {
                        confirmed = it
                        confirmKeyId = null
                    },
                    onDismissError = {},
                    onOpenProviders = {},
                    onOpenBackup = {},
                    onExportAll = {},
                    onImportMerge = {},
                    onImportReplace = {},
                )
            }
        }

        composeRule.onNodeWithTag(DataSettingsTestTags.removeKey("provider-1"))
            .performScrollTo()
            .performClick()

        assertThat(requested).isEqualTo("provider-1")
        assertThat(confirmed).isNull()

        composeRule.onNodeWithTag(DataSettingsTestTags.confirmRemoveKey("provider-1"))
            .performScrollTo()
            .performClick()

        assertThat(confirmed).isEqualTo("provider-1")
    }

    @Test
    fun `export import and backup entry points are reachable`() {
        var exported = 0
        var importedMerge = 0
        var importedReplace = 0
        var openedBackup = 0

        composeRule.setContent {
            ChatUiTheme {
                DataSettingsScreen(
                    state = state(),
                    onConfirmationPhraseChanged = {},
                    onClearAll = {},
                    onRequestRemoveKey = {},
                    onCancelRemoveKey = {},
                    onConfirmRemoveKey = {},
                    onDismissError = {},
                    onOpenProviders = {},
                    onOpenBackup = { openedBackup += 1 },
                    onExportAll = { exported += 1 },
                    onImportMerge = { importedMerge += 1 },
                    onImportReplace = { importedReplace += 1 },
                )
            }
        }

        composeRule.onNodeWithTag(DataSettingsTestTags.BackupButton)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(DataSettingsTestTags.ExportButton)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(DataSettingsTestTags.ImportMerge)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(DataSettingsTestTags.ImportReplace)
            .performScrollTo()
            .performClick()

        assertThat(exported).isEqualTo(1)
        assertThat(importedMerge).isEqualTo(1)
        assertThat(importedReplace).isEqualTo(1)
        assertThat(openedBackup).isEqualTo(1)
    }

    @Test
    fun `export reports progress through the snackbar`() {
        composeRule.setContent {
            ChatUiTheme {
                DataSettingsScreen(
                    state = state(),
                    onConfirmationPhraseChanged = {},
                    onClearAll = {},
                    onRequestRemoveKey = {},
                    onCancelRemoveKey = {},
                    onConfirmRemoveKey = {},
                    onDismissError = {},
                    onOpenProviders = {},
                    onOpenBackup = {},
                    onExportAll = {},
                    onImportMerge = {},
                    onImportReplace = {},
                )
            }
        }

        composeRule.onNodeWithTag(DataSettingsTestTags.ExportButton)
            .performScrollTo()
            .performClick()

        composeRule.onNodeWithText("Export started").assertIsDisplayed()
    }

    @Test
    fun `loading state shows skeletons instead of loading text`() {
        composeRule.setContent {
            ChatUiTheme {
                DataSettingsScreen(
                    state = state(loading = true),
                    onConfirmationPhraseChanged = {},
                    onClearAll = {},
                    onRequestRemoveKey = {},
                    onCancelRemoveKey = {},
                    onConfirmRemoveKey = {},
                    onDismissError = {},
                    onOpenProviders = {},
                    onOpenBackup = {},
                    onExportAll = {},
                    onImportMerge = {},
                    onImportReplace = {},
                )
            }
        }

        composeRule.onNodeWithTag(DataSettingsTestTags.Usage)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Calculating storage usage...").assertDoesNotExist()
        composeRule.onNodeWithText("Loading stored keys...").assertDoesNotExist()
    }

    @Test
    fun `error state uses an inline banner with dismiss`() {
        var dismissed = false

        composeRule.setContent {
            ChatUiTheme {
                DataSettingsScreen(
                    state = state(error = "Could not read storage"),
                    onConfirmationPhraseChanged = {},
                    onClearAll = {},
                    onRequestRemoveKey = {},
                    onCancelRemoveKey = {},
                    onConfirmRemoveKey = {},
                    onDismissError = { dismissed = true },
                    onOpenProviders = {},
                    onOpenBackup = {},
                    onExportAll = {},
                    onImportMerge = {},
                    onImportReplace = {},
                )
            }
        }

        composeRule.onNodeWithTag(DataSettingsTestTags.Error).assertIsDisplayed()
        composeRule.onNodeWithText("Dismiss").performClick()

        assertThat(dismissed).isTrue()
    }

    private fun state(
        confirmationPhrase: String = "",
        confirmKeyId: String? = null,
        loading: Boolean = false,
        error: String? = null,
    ): DataSettingsUiState = DataSettingsUiState(
        usage = StorageUsage(
            conversations = StorageUsageEntry(1, 100),
            messages = StorageUsageEntry(2, 200),
            attachments = StorageUsageEntry(3, 300),
            secrets = StorageUsageEntry(1, 400),
            providers = StorageUsageEntry(1, 500),
            models = StorageUsageEntry(1, 600),
        ),
        keys = listOf(
            ProviderKeySummary(
                id = "provider-1",
                label = "Work",
                presetId = "custom",
                hasKey = true,
                keyLabel = "Team key",
            ),
        ),
        loading = loading,
        error = error,
        confirmKeyId = confirmKeyId,
        confirmationPhrase = confirmationPhrase,
    )
}
