package dev.chatui.feature.providers

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConversationRoutingControlsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `shows a summary only when routing is active`() {
        composeRule.setContent {
            ChatUiTheme {
                ConversationRoutingControls(
                    state = ConversationRoutingUiState(
                        preferences = RoutingPreferences(
                            enabled = true,
                            allowFallbacks = true,
                            sort = RoutingSort.PRICE,
                            denyDataCollection = true,
                        ),
                        loading = false,
                    ),
                    onEnabledChanged = {},
                    onAllowFallbacksChanged = {},
                    onSortChanged = {},
                    onDenyDataCollectionChanged = {},
                )
            }
        }

        composeRule.onNodeWithTag(ConversationRoutingTestTags.Summary).assertIsDisplayed()
        composeRule.onNodeWithTag(ConversationRoutingTestTags.Warning).assertIsDisplayed()
    }

    @Test
    fun `hides the summary when routing is inactive`() {
        composeRule.setContent {
            ChatUiTheme {
                ConversationRoutingControls(
                    state = ConversationRoutingUiState(
                        preferences = DEFAULT_ROUTING_PREFERENCES,
                        loading = false,
                    ),
                    onEnabledChanged = {},
                    onAllowFallbacksChanged = {},
                    onSortChanged = {},
                    onDenyDataCollectionChanged = {},
                )
            }
        }

        composeRule.onNodeWithTag(ConversationRoutingTestTags.Summary).assertDoesNotExist()
        composeRule.onNodeWithTag(ConversationRoutingTestTags.AllowFallbacks).assertIsNotEnabled()
    }

    @Test
    fun `sort chips invoke the callback`() {
        var selected: RoutingSort? = null

        composeRule.setContent {
            ChatUiTheme {
                ConversationRoutingControls(
                    state = ConversationRoutingUiState(
                        preferences = RoutingPreferences(enabled = true),
                        loading = false,
                    ),
                    onEnabledChanged = {},
                    onAllowFallbacksChanged = {},
                    onSortChanged = { selected = it },
                    onDenyDataCollectionChanged = {},
                )
            }
        }

        composeRule.onNodeWithTag(ConversationRoutingTestTags.sort("latency")).performClick()

        assertThat(selected).isEqualTo(RoutingSort.LATENCY)
    }
}
