package dev.chatui

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.captureToImage
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.material3.dynamicDarkColorScheme
import com.google.common.truth.Truth.assertThat
import dev.chatui.feature.conversations.ConversationListTestTags
import dev.chatui.core.datastore.settings.AndroidSettings
import dev.chatui.core.designsystem.DarkColors
import dev.chatui.core.designsystem.LightColors
import dev.chatui.core.model.Conversation
import dev.chatui.core.model.Density
import dev.chatui.core.model.Theme
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
import org.robolectric.annotation.GraphicsMode

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
        composeRule.onNodeWithTag(ShellTestTags.CompactLayout).assertIsDisplayed()
        composeRule.onNodeWithTag(ConversationListTestTags.Screen)
            .assertIsDisplayed()
    }

    @Test
    fun `compact chats overflow has an accessible name`() {
        composeRule.setContent {
            ChatUiApp(
                settings = AndroidSettings(onboardingCompleted = true),
                conversationService = EmptyConversationService(),
            )
        }

        composeRule.onNodeWithTag(ShellTestTags.MoreButton)
            .assertContentDescriptionEquals("More")
    }

    @Test
    fun `stored haptic changes reach actual root history actions`() {
        var enabled by mutableStateOf(false)
        val effects = mutableListOf<HapticFeedbackType>()
        val feedback = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                effects += hapticFeedbackType
            }
        }
        val service = EmptyConversationService()
        composeRule.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides feedback) {
                ChatUiApp(
                    settings = AndroidSettings(onboardingCompleted = true, hapticFeedback = enabled),
                    conversationService = service,
                )
            }
        }

        composeRule.onNodeWithText("Archived").performClick()
        composeRule.runOnIdle {
            assertThat(effects).isEmpty()
            enabled = true
        }
        composeRule.onNode(
            hasText("Chats") and hasAnyAncestor(hasTestTag(ConversationListTestTags.ArchivedToggle)),
        ).performClick()
        composeRule.runOnIdle { assertThat(effects).containsExactly(HapticFeedbackType.SegmentTick) }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w411dp-h891dp-notnight")
    fun `stored dynamic color is applied by the production root with its app theme override`() {
        var expected = Color.Unspecified
        composeRule.setContent {
            expected = dynamicDarkColorScheme(LocalContext.current).primary
            ChatUiApp(
                settings = AndroidSettings(
                    onboardingCompleted = true,
                    theme = Theme.DARK,
                    dynamicColor = true,
                ),
                conversationService = EmptyConversationService(),
            )
        }

        val pixels = composeRule.onNodeWithTag(ConversationListTestTags.NewChat)
            .captureToImage().toPixelMap()
        assertThat(pixels[pixels.width / 2, 2]).isEqualTo(expected)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w840dp-h1280dp-notnight")
    fun `expanded history paints its safe viewport with the applied app theme`() {
        var theme by mutableStateOf(Theme.DARK)
        composeRule.setContent {
            ChatUiApp(
                settings = AndroidSettings(
                    onboardingCompleted = true,
                    theme = theme,
                    density = Density.COMPACT,
                ),
                conversationService = EmptyConversationService(),
            )
        }

        composeRule.onNodeWithTag(ShellTestTags.ExpandedLayout).assertIsDisplayed()
        val darkPixels = composeRule.onNodeWithTag(ShellTestTags.Sidebar).captureToImage().toPixelMap()
        assertThat(darkPixels[0, 0]).isEqualTo(DarkColors.bgCanvas)
        composeRule.runOnIdle { theme = Theme.LIGHT }
        val lightPixels = composeRule.onNodeWithTag(ShellTestTags.Sidebar).captureToImage().toPixelMap()
        assertThat(lightPixels[0, 0]).isEqualTo(LightColors.bgCanvas)
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
