package dev.chatui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.chatui.core.datastore.settings.AndroidSettings
import dev.chatui.core.model.Conversation
import dev.chatui.core.model.Density
import dev.chatui.core.model.Theme
import dev.chatui.feature.conversations.ConversationListTestTags
import dev.chatui.feature.conversations.ConversationService
import dev.chatui.feature.conversations.CreateConversationResult
import dev.chatui.shell.ChatUiApp
import dev.chatui.shell.ShellTestTags
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShellThemeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `shell applies persisted dark theme and compact density`() {
        composeRule.setContent {
            ChatUiApp(
                settings = AndroidSettings(
                    theme = Theme.DARK,
                    density = Density.COMPACT,
                    onboardingCompleted = true,
                ),
                conversationService = EmptyConversationService(),
            )
        }

        composeRule.onNodeWithTag(
            ShellTestTags.appearance(Theme.DARK, Density.COMPACT),
        ).assertExists()
        composeRule.onNodeWithTag(ConversationListTestTags.Screen).assertIsDisplayed()
    }
}

private class EmptyConversationService : ConversationService {
    private val conversations = MutableStateFlow<List<Conversation>>(emptyList())
    private val active = MutableStateFlow<String?>(null)

    override fun observeConversations(): Flow<List<Conversation>> = conversations

    override fun observeLastActiveConversationId(): Flow<String?> = active

    override suspend fun createConversation(title: String?): CreateConversationResult =
        CreateConversationResult.NotConfigured("No provider is configured.")

    override suspend fun renameConversation(id: String, title: String): Conversation =
        error("No conversations exist")

    override suspend fun deleteConversation(id: String) = Unit

    override suspend fun setLastActiveConversation(id: String?) {
        active.value = id
    }

    override suspend fun getLastActiveConversation(): Conversation? = null

    override suspend fun getConversation(id: String): Conversation? = null
}
