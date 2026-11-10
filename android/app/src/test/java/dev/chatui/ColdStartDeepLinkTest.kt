package dev.chatui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import dev.chatui.core.datastore.settings.AndroidSettings
import dev.chatui.feature.chat.ChatScreenTestTags
import dev.chatui.feature.chat.InMemoryChatDrafts
import dev.chatui.intent.ExternalIntentAction
import dev.chatui.intent.ExternalIntentRouter
import dev.chatui.shell.ChatDestinationTestTags
import dev.chatui.shell.ChatUiApp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ColdStartDeepLinkTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `cold start deep link opens the chat after the navigation graph is ready`() {
        val conversation = fakeConversation(id = CONVERSATION_ID)
        val router = ExternalIntentRouter()
        router.submit(ExternalIntentAction.OpenChat(CONVERSATION_ID))

        composeRule.setContent {
            ChatUiApp(
                settings = AndroidSettings(onboardingCompleted = true),
                conversationService = FakeConversationService(
                    conversations = listOf(conversation),
                ),
                externalIntentRouter = router,
                chatDrafts = InMemoryChatDrafts(),
            )
        }

        composeRule.waitForIdle()

        composeRule.onNodeWithTag(ChatDestinationTestTags.conversation(CONVERSATION_ID))
            .assertIsDisplayed()
    }

    @Test
    fun `cold start deep link to a missing conversation shows not found`() {
        val router = ExternalIntentRouter()
        router.submit(ExternalIntentAction.OpenChat(MISSING_CONVERSATION_ID))

        composeRule.setContent {
            ChatUiApp(
                settings = AndroidSettings(onboardingCompleted = true),
                conversationService = FakeConversationService(conversations = emptyList()),
                externalIntentRouter = router,
                chatDrafts = InMemoryChatDrafts(),
            )
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(ChatScreenTestTags.NotFound)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeRule.onNodeWithTag(ChatDestinationTestTags.conversation(MISSING_CONVERSATION_ID))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(ChatScreenTestTags.NotFound).assertIsDisplayed()
    }

    private companion object {
        const val CONVERSATION_ID = "0190a5c4-1d2e-7abc-8def-0123456789ab"
        const val MISSING_CONVERSATION_ID = "0190a5c4-1d2e-7abc-8def-0123456789ac"
    }
}
