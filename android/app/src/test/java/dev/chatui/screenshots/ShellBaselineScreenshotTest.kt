package dev.chatui.screenshots

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.core.model.FinishReason
import dev.chatui.core.model.StreamEvent
import dev.chatui.core.model.Theme
import dev.chatui.feature.chat.ChatComposerTestTags
import dev.chatui.feature.chat.ChatMessageListTestTags
import dev.chatui.feature.chat.ChatMessageTestTags
import dev.chatui.feature.chat.ChatScreenTestTags
import dev.chatui.feature.conversations.ConversationListTestTags
import dev.chatui.feature.providers.ModelPickerContent
import dev.chatui.feature.providers.ModelPickerTestTags
import dev.chatui.shell.ShellTestTags
import dev.chatui.testing.BaselineFixtures
import dev.chatui.testing.BaselineHarness
import dev.chatui.testing.BaselineImages
import dev.chatui.testing.BaselineShell
import java.io.File
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pre-redesign captures. The actual-shell group renders ChatUiApp, ShellNavHost,
 * ShellSidebar, and real routes; the picker group is explicitly standalone.
 *
 * Assertions always run. Recording/verifying external baseline images additionally
 * requires CHATUI_BASELINE_DIR, so ordinary verification never invents new goldens.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
class ShellBaselineScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var harness: BaselineHarness? = null
    private val storeOwner = object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }
    private var showContent by mutableStateOf(true)
    private lateinit var originalTimeZone: TimeZone
    private lateinit var originalLocale: Locale

    @Before
    fun setUp() {
        originalTimeZone = TimeZone.getDefault()
        originalLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        val context = ApplicationProvider.getApplicationContext<Context>()
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    @After
    fun tearDown() {
        composeRule.runOnIdle {
            storeOwner.viewModelStore.clear()
            showContent = false
        }
        composeRule.waitForIdle()
        harness?.adapter?.events?.close()
        harness?.close()
        TimeZone.setDefault(originalTimeZone)
        Locale.setDefault(originalLocale)
    }

    @Test
    fun compactRootKeepsPlaceholderAndDrawerClosed() = shell("root_closed_360") {
        composeRule.onNodeWithTag(ShellTestTags.ConversationsPlaceholder).assertIsDisplayed()
        composeRule.onNodeWithTag(ShellTestTags.NewChat).assertIsNotDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-mdpi")
    fun emptyHistoryInRealDrawer() = shell("history_empty_drawer_411", emptyHistory = true) {
        openDrawer()
        waitForTag(ConversationListTestTags.Empty)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-mdpi")
    fun populatedHistoryInRealDrawer() = shell("history_populated_drawer_411") {
        openDrawer()
        waitForTag(ConversationListTestTags.item(BaselineFixtures.CONTENT_CHAT))
        composeRule.onNodeWithTag(ShellTestTags.NewChat).assertIsDisplayed()
        composeRule.onNodeWithTag(ConversationListTestTags.NewChat).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-mdpi")
    fun archivedHistoryInRealDrawer() = shell("history_archived_drawer_411") {
        openDrawer()
        waitForTag(ConversationListTestTags.ArchivedToggle)
        composeRule.onNodeWithTag(ConversationListTestTags.ArchivedToggle).performClick()
        waitForTag(ConversationListTestTags.item(BaselineFixtures.ARCHIVED_CHAT))
    }

    @Test
    @Config(qualifiers = "w840dp-h1280dp-mdpi")
    fun expandedProductionSidebar() = shell("expanded_sidebar_840") {
        composeRule.onNodeWithTag(ShellTestTags.ExpandedLayout).assertIsDisplayed()
        composeRule.onNodeWithTag(ShellTestTags.Sidebar).assertIsDisplayed()
        waitForTag(ConversationListTestTags.item(BaselineFixtures.CONTENT_CHAT))
        composeRule.onNodeWithTag(ShellTestTags.ConversationsPlaceholder).assertIsDisplayed()
    }

    @Test
    fun emptyChatInRealNavigation() = shell("chat_empty_360", start = BaselineFixtures.EMPTY_CHAT) {
        waitForTag(ChatScreenTestTags.Empty)
        composeRule.onNodeWithTag(ChatComposerTestTags.Input).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-mdpi")
    fun codeTableAndReasoningInRealChat() = shell("chat_content_411", start = BaselineFixtures.CONTENT_CHAT) {
        waitForTag(ChatMessageTestTags.Reasoning)
        composeRule.onNodeWithTag(ChatMessageTestTags.Reasoning).performScrollTo().performClick()
        composeRule.onNodeWithTag(ChatScreenTestTags.Header).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-mdpi")
    fun localImageInRealChat() = shell("chat_local_image_411", start = BaselineFixtures.IMAGE_CHAT) {
        val tag = ChatMessageTestTags.attachment("baseline-image-0-visual")
        waitForTag(tag)
        val image = composeRule.onNodeWithTag(tag).performScrollTo()
        composeRule.waitUntil(5_000) {
            val pixels = image.captureToImage().toPixelMap()
            (0 until pixels.width step 16).any { x ->
                (0 until pixels.height step 16).any { y -> pixels[x, y].toArgb() == 0xff235789.toInt() }
            }
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp-mdpi")
    fun narrowChatAtTwoHundredPercentText() = shell(
        "chat_320_font200",
        start = BaselineFixtures.CONTENT_CHAT,
        fontScale = 2f,
    ) {
        composeRule.onNodeWithTag(ChatMessageListTestTags.List).performScrollToIndex(0)
    }

    @Test
    fun recoverableErrorInRealChat() = shell("chat_recoverable_error_360", start = BaselineFixtures.ERROR_CHAT) {
        waitForTag(ChatMessageTestTags.Retry)
    }

    @Test
    fun missingConfigurationInRealChat() = shell(
        "chat_missing_configuration_360",
        start = BaselineFixtures.EMPTY_CHAT,
        missingConfiguration = true,
    ) {
        waitForTag(ChatScreenTestTags.NoKey)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-mdpi")
    fun attachmentWarningsRemainDuplicatedInBaseline() = shell(
        "chat_attachment_warnings_411",
        start = BaselineFixtures.EMPTY_CHAT,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(harness!!.root, "fixture.png")
        file.writeBytes(BaselineImages.all.first().bytes)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newRawUri("Synthetic swatch", Uri.fromFile(file)))
        composeRule.onNodeWithTag(ChatComposerTestTags.Attach).performClick()
        composeRule.onNodeWithTag(ChatComposerTestTags.Paste).performClick()
        waitForTag(ChatComposerTestTags.AttachmentWarning)
        composeRule.onNodeWithTag(ChatScreenTestTags.AttachmentWarning).assertIsDisplayed()
        composeRule.onNodeWithTag(ChatComposerTestTags.AttachmentWarning).assertIsDisplayed()
    }

    @Test
    fun longHistoryUsesRealPaging() = shell(
        "chat_10000_messages_360",
        start = BaselineFixtures.LONG_CHAT,
        longHistory = true,
    ) {
        waitForTag(ChatMessageListTestTags.List)
    }

    @Test
    fun controlledStreamInRealChat() = shell(
        "chat_controlled_stream_360",
        start = BaselineFixtures.EMPTY_CHAT,
        afterCapture = {
            harness!!.adapter.events.trySend(StreamEvent.Finish(FinishReason.STOP))
            harness!!.adapter.events.close()
            waitForTag(ChatComposerTestTags.Send)
        },
    ) {
        composeRule.onNodeWithTag(ChatComposerTestTags.Input).performTextInput("Stream an invented local answer.")
        composeRule.onNodeWithTag(ChatComposerTestTags.Send).assertIsEnabled().performClick()
        try {
            composeRule.waitUntil(5_000) {
                // Room resumes on Robolectric's paused Android looper.
                composeRule.waitForIdle()
                harness!!.adapter.started.isCompleted
            }
        } catch (error: ComposeTimeoutException) {
            throw AssertionError(composeRule.onRoot().printToString(), error)
        }
        harness!!.adapter.events.trySend(StreamEvent.Start("fixture-response"))
        harness!!.adapter.events.trySend(StreamEvent.ReasoningDelta("Compare invented rows."))
        harness!!.adapter.events.trySend(StreamEvent.TextDelta("A synthetic partial answer.\n\n```kotlin\nval count ="))
        waitForTag(ChatMessageListTestTags.Streaming)
        waitForText("A synthetic partial answer.")
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-mdpi")
    fun standaloneFreeModelFilter() = picker("model_free_filter_411", includeHidden = false) {
        composeRule.onNodeWithTag(ModelPickerTestTags.filter("free")).performClick()
        composeRule.onNodeWithTag(ModelPickerTestTags.model("baseline-text")).assertIsDisplayed()
        composeRule.onNodeWithTag(ModelPickerTestTags.model("baseline-hidden")).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-mdpi")
    fun standaloneHiddenModelManagement() = picker("model_hidden_management_411", includeHidden = true) {
        composeRule.onNodeWithTag(ModelPickerTestTags.model("baseline-hidden")).performScrollTo()
    }

    private fun shell(
        name: String,
        start: String? = null,
        emptyHistory: Boolean = false,
        missingConfiguration: Boolean = false,
        longHistory: Boolean = false,
        fontScale: Float = 1f,
        afterCapture: () -> Unit = {},
        interaction: () -> Unit = {},
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val seeded = runBlocking {
            BaselineHarness.create(
                context = context,
                emptyHistory = emptyHistory,
                startConversationId = start,
                missingConfiguration = missingConfiguration,
                longHistory = longHistory,
            )
        }
        harness = seeded
        captureBoth("actual-shell", name, interaction = {
            waitForTag(ShellTestTags.Root)
            if (start != null) {
                waitForTag(ChatComposerTestTags.Input)
                composeRule.waitUntil(10_000) {
                    composeRule.onAllNodesWithTag(ChatScreenTestTags.Loading).fetchSemanticsNodes().isEmpty()
                }
                if (start == BaselineFixtures.CONTENT_CHAT) waitForText("Local baseline")
            }
            interaction()
        }) { theme ->
            BaselineShell(seeded, theme, fontScale)
        }
        afterCapture()
    }

    private fun picker(name: String, includeHidden: Boolean, interaction: () -> Unit) {
        var state by mutableStateOf(BaselineFixtures.pickerState(includeHidden))
        captureBoth("standalone-current", name, interaction) { theme ->
            ChatUiTheme(themeMode = theme, reducedMotion = true) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    ModelPickerContent(
                        state = state,
                        onQueryChanged = { state = state.copy(query = it) },
                        onSelect = {},
                        onPin = { _, _ -> },
                        onHide = { _, _ -> },
                        onManualRemoteIdChanged = {},
                        onManualLabelChanged = {},
                        onAddManual = {},
                        onRefresh = {},
                    )
                }
            }
        }
    }

    private fun captureBoth(
        group: String,
        name: String,
        interaction: () -> Unit,
        content: @Composable (Theme) -> Unit,
    ) {
        var theme by mutableStateOf(Theme.LIGHT)
        composeRule.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides storeOwner) {
                if (showContent) content(theme)
            }
        }
        composeRule.waitForIdle()
        interaction()
        composeRule.waitForIdle()
        capture(group, "${name}_light")
        theme = Theme.DARK
        composeRule.waitForIdle()
        capture(group, "${name}_dark")
    }

    private fun capture(group: String, name: String) {
        val output = System.getenv("CHATUI_BASELINE_DIR") ?: return
        val directory = File(output, group)
        require(directory.isAbsolute) { "CHATUI_BASELINE_DIR must be an absolute artifact path." }
        directory.mkdirs()
        composeRule.onRoot().captureRoboImage(file = File(directory, "$name.png"))
    }

    private fun openDrawer() {
        composeRule.onNodeWithTag(ShellTestTags.MenuButton).performClick()
        composeRule.onNodeWithTag(ShellTestTags.Sidebar).assertIsDisplayed()
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
