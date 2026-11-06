package dev.chatui

import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.feature.providers.ModelPickerSheet
import dev.chatui.feature.providers.ModelPickerTarget
import dev.chatui.feature.providers.ModelPickerUiState
import dev.chatui.shell.ShellDestination
import dev.chatui.shell.ShellScaffold
import dev.chatui.shell.ShellTestTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ShellBackNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController

    @Test
    fun `back closes the drawer before navigating`() {
        setShell()

        composeRule.onNodeWithTag(ShellTestTags.MenuButton).performClick()
        composeRule.onNodeWithTag(ShellTestTags.Sidebar).assertIsDisplayed()

        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        composeRule.onNodeWithTag(ShellTestTags.Sidebar).assertIsNotDisplayed()
        composeRule.runOnIdle {
            assertThat(navController.currentDestination?.route)
                .isEqualTo(ShellDestination.Conversations.route)
        }
    }

    @Test
    @Config(qualifiers = "w840dp-h1280dp")
    fun `back navigates after the drawer is closed`() {
        setShell()

        composeRule.onNodeWithTag(
            ShellTestTags.navigationItem(ShellDestination.Backup.route),
        ).performClick()
        composeRule.runOnIdle {
            assertThat(navController.currentDestination?.route)
                .isEqualTo(ShellDestination.Backup.route)
        }

        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        composeRule.runOnIdle {
            assertThat(navController.currentDestination?.route)
                .isEqualTo(ShellDestination.Conversations.route)
        }
    }

    @Test
    fun `back closes a sheet before the drawer`() {
        var showSheet by mutableStateOf(false)
        setShell(
            overlay = {
                if (showSheet) {
                    ModelPickerSheet(
                        state = ModelPickerUiState(
                            target = ModelPickerTarget.Manage("provider-1"),
                            loading = false,
                        ),
                        onDismiss = { showSheet = false },
                        onQueryChanged = {},
                        onSelect = {},
                        onPin = { _, _ -> },
                        onHide = { _, _ -> },
                        onManualRemoteIdChanged = {},
                        onManualLabelChanged = {},
                        onAddManual = {},
                        onRefresh = {},
                    )
                }
            },
        )

        composeRule.onNodeWithTag(ShellTestTags.MenuButton).performClick()
        composeRule.onNodeWithTag(ShellTestTags.Sidebar).assertIsDisplayed()

        composeRule.runOnIdle { showSheet = true }
        composeRule.waitForIdle()
        val sheet = ShadowDialog.getShownDialogs().last { it.isShowing } as ComponentDialog

        composeRule.runOnIdle { sheet.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()

        assertThat(showSheet).isFalse()
        composeRule.onNodeWithTag(ShellTestTags.Sidebar).assertIsDisplayed()

        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        composeRule.onNodeWithTag(ShellTestTags.Sidebar).assertIsNotDisplayed()
    }

    @Test
    fun `back closes a dialog before the drawer`() {
        var showDialog by mutableStateOf(false)
        setShell(
            overlay = {
                if (showDialog) {
                    AlertDialog(
                        onDismissRequest = { showDialog = false },
                        confirmButton = {},
                        text = { Text("Confirm") },
                    )
                }
            },
        )

        composeRule.onNodeWithTag(ShellTestTags.MenuButton).performClick()
        composeRule.onNodeWithTag(ShellTestTags.Sidebar).assertIsDisplayed()

        composeRule.runOnIdle { showDialog = true }
        composeRule.waitForIdle()
        val dialog = ShadowDialog.getShownDialogs().last { it.isShowing } as ComponentDialog

        composeRule.runOnIdle { dialog.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()

        assertThat(showDialog).isFalse()
        composeRule.onNodeWithTag(ShellTestTags.Sidebar).assertIsDisplayed()
    }

    @Test
    fun `back at the root is not intercepted by the app`() {
        setShell()

        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        composeRule.runOnIdle {
            assertThat(composeRule.activity.isFinishing).isTrue()
        }
    }

    private fun setShell(overlay: @Composable () -> Unit = {}) {
        composeRule.setContent {
            ChatUiTheme {
                navController = rememberNavController()
                ShellScaffold(
                    navController = navController,
                    startDestination = ShellDestination.Conversations.route,
                    conversationService = FakeConversationService(),
                )
                overlay()
            }
        }
    }
}
