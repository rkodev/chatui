package dev.chatui.feature.providers

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import dev.chatui.core.designsystem.ChatUiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderRoutesTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `list route renders the provider list`() {
        composeRule.setContent {
            ChatUiTheme {
                ProviderListRoute(
                    service = FakeProviderManagementService(),
                    onOpenProvider = {},
                )
            }
        }

        composeRule.onNodeWithTag(ProviderListTestTags.Screen).assertIsDisplayed()
        composeRule.onNodeWithTag(ProviderListTestTags.provider("provider-1"))
            .assertIsDisplayed()
    }

    @Test
    fun `detail route renders the selected provider`() {
        composeRule.setContent {
            ChatUiTheme {
                ProviderDetailRoute(
                    service = FakeProviderManagementService(),
                    providerId = "provider-1",
                )
            }
        }

        composeRule.onNodeWithTag(ProviderDetailTestTags.Screen).assertIsDisplayed()
        composeRule.onNodeWithText("OpenRouter").assertIsDisplayed()
    }
}
