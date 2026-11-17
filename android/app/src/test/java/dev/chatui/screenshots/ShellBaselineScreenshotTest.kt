package dev.chatui.screenshots

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.core.designsystem.ChatUiScaffold
import dev.chatui.core.designsystem.GroupedSection
import dev.chatui.core.designsystem.IconAction
import dev.chatui.core.designsystem.LoadingButton
import dev.chatui.core.designsystem.LocalReducedMotion
import dev.chatui.core.designsystem.SettingsRow
import dev.chatui.core.designsystem.Skeleton
import dev.chatui.core.designsystem.TitleBarStyle
import dev.chatui.core.model.FinishReason
import dev.chatui.core.model.StreamEvent
import dev.chatui.core.model.Theme
import dev.chatui.core.model.Density as UiDensity
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
 * Current-candidate regressions; shell cases reuse frozen UX-00 fixture data.
 * The shell group renders real routes; the picker group is explicitly standalone.
 *
 * Assertions always run. Candidate captures require CHATUI_REFINEMENT_DIR and
 * never overwrite the frozen pre-redesign UX-00 captures or build.
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
    private var originalAnimatorScale = 1f

    @Before
    fun setUp() {
        originalTimeZone = TimeZone.getDefault()
        originalLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        val context = ApplicationProvider.getApplicationContext<Context>()
        originalAnimatorScale = Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
        )
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
        val context = ApplicationProvider.getApplicationContext<Context>()
        Settings.Global.putFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, originalAnimatorScale,
        )
        TimeZone.setDefault(originalTimeZone)
        Locale.setDefault(originalLocale)
    }

    @Test
    fun compactRootShowsRealChatsWithoutAnOffscreenDrawer() = shell("chats_root_360") {
        composeRule.onNodeWithTag(ConversationListTestTags.Screen).assertIsDisplayed()
        composeRule.onNodeWithTag(ShellTestTags.ConversationsPlaceholder).assertDoesNotExist()
        composeRule.onNodeWithTag(ShellTestTags.MenuButton).assertDoesNotExist()
        composeRule.onAllNodesWithText("New chat").assertCountEquals(1)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-mdpi")
    fun emptyHistoryAtChatsRoot() = shell("history_empty_root_411", emptyHistory = true) {
        waitForTag(ConversationListTestTags.Empty)
        composeRule.onAllNodesWithText("New chat").assertCountEquals(1)
    }

    @Test
    @Config(qualifiers = "w839dp-h1280dp-mdpi")
    fun lastCompactWidthKeepsRealChats() = shell("chats_root_839") {
        composeRule.onNodeWithTag(ShellTestTags.CompactLayout).assertIsDisplayed()
        composeRule.onNodeWithTag(ShellTestTags.ExpandedLayout).assertDoesNotExist()
        composeRule.onNodeWithTag(ConversationListTestTags.Screen)
            .assertIsDisplayed().assertWidthIsEqualTo(760.dp)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-mdpi")
    fun populatedHistoryInRealDrawer() = shell(
        "history_populated_drawer_411",
        start = BaselineFixtures.EMPTY_CHAT,
    ) {
        openDrawer()
        waitForTag(ConversationListTestTags.item(BaselineFixtures.CONTENT_CHAT))
        composeRule.onNodeWithTag(ShellTestTags.NewChat).assertIsDisplayed()
        composeRule.onNodeWithTag(ConversationListTestTags.NewChat).assertDoesNotExist()
        composeRule.onAllNodesWithText("New chat").assertCountEquals(1)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-mdpi")
    fun archivedHistoryInRealDrawer() = shell(
        "history_archived_drawer_411",
        start = BaselineFixtures.EMPTY_CHAT,
    ) {
        openDrawer()
        waitForTag(ConversationListTestTags.ArchivedToggle)
        composeRule.onNodeWithTag(ConversationListTestTags.ArchivedToggle).performClick()
        waitForTag(ConversationListTestTags.item(BaselineFixtures.ARCHIVED_CHAT))
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-mdpi")
    fun searchResultsInSharedHistory() = shell(
        "history_search_411",
        start = BaselineFixtures.EMPTY_CHAT,
    ) {
        openDrawer()
        composeRule.onNodeWithTag(ConversationListTestTags.SearchField).performTextInput("Cedar")
        waitForTag(ConversationListTestTags.searchResult(BaselineFixtures.CONTENT_CHAT))
    }

    @Test
    @Config(qualifiers = "w840dp-h1280dp-mdpi")
    fun expandedProductionSidebar() = shell("expanded_sidebar_840") {
        waitForTag(ShellTestTags.ExpandedLayout)
        composeRule.onNodeWithTag(ShellTestTags.ExpandedLayout).assertIsDisplayed()
        composeRule.onNodeWithTag(ShellTestTags.Sidebar).assertIsDisplayed()
        waitForTag(ConversationListTestTags.item(BaselineFixtures.CONTENT_CHAT))
        composeRule.onNodeWithTag(ShellTestTags.ConversationsPlaceholder).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1280dp-h900dp-mdpi")
    fun wideProductionChat() = shell(
        "chat_content_wide_1280",
        start = BaselineFixtures.CONTENT_CHAT,
    ) {
        composeRule.onNodeWithTag(ShellTestTags.ExpandedLayout).assertIsDisplayed()
        waitForText("Local baseline")
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-mdpi")
    fun landscapeProductionChat() = shell(
        "chat_content_landscape_640",
        start = BaselineFixtures.CONTENT_CHAT,
    ) {
        waitForTag(ChatMessageListTestTags.List)
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
            image.performScrollTo()
            val bounds = image.fetchSemanticsNode().boundsInRoot
            if (bounds.width <= 0f || bounds.height <= 0f) {
                false
            } else {
                val pixels = image.captureToImage().toPixelMap()
                (0 until pixels.width step 16).any { x ->
                    (0 until pixels.height step 16).any { y -> pixels[x, y].toArgb() == 0xff235789.toInt() }
                }
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
    @Config(qualifiers = "w640dp-h360dp-mdpi")
    fun shortLandscapeChatAtTwoHundredPercentText() = shell(
        "chat_landscape_font200",
        start = BaselineFixtures.EMPTY_CHAT,
        fontScale = 2f,
    ) {
        composeRule.onNodeWithTag(ChatComposerTestTags.Input).assertIsDisplayed()
        composeRule.onNodeWithTag(ChatComposerTestTags.Attach).assertIsDisplayed()
        composeRule.onNodeWithTag(ChatComposerTestTags.Send).assertIsDisplayed()
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
    fun attachmentWarningHasOneComposerOwner() = shell(
        "chat_attachment_warning_411",
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
        composeRule.onNodeWithTag(ChatScreenTestTags.AttachmentWarning).assertDoesNotExist()
        composeRule.onAllNodesWithTag(ChatComposerTestTags.AttachmentWarning).assertCountEquals(1)
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
    fun controlledStreamInRealChat() = streamingShell("chat_controlled_stream_360")

    @Test
    @Config(qualifiers = "w640dp-h360dp-mdpi")
    fun landscapeStreamWithSimulatedKeyboard() = streamingShell(
        name = "chat_landscape_ime224_font200",
        fontScale = 2f,
        imeBottom = 224.dp,
    )

    @Test
    @Config(qualifiers = "w320dp-h568dp-mdpi")
    fun portraitInputWithSimulatedKeyboard() = shell(
        name = "chat_portrait_ime224_font200",
        start = BaselineFixtures.CONTENT_CHAT,
        fontScale = 2f,
        imeBottom = 224.dp,
        verifyLayout = { assertComposerInsideContent(ChatComposerTestTags.Send) },
    ) {
        composeRule.onNodeWithTag(ChatComposerTestTags.Input).performTextInput("Retained keyboard draft")
    }

    private fun streamingShell(name: String, fontScale: Float = 1f, imeBottom: Dp? = null) = shell(
        name = name,
        start = BaselineFixtures.EMPTY_CHAT,
        fontScale = fontScale,
        imeBottom = imeBottom,
        verifyLayout = { assertComposerInsideContent(ChatComposerTestTags.Stop) },
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

    private fun assertComposerInsideContent(action: String) {
        val content = composeRule.onNodeWithTag(ShellTestTags.ScreenContent).getUnclippedBoundsInRoot()
        listOf(ChatComposerTestTags.Input, ChatComposerTestTags.Attach, action).forEach { tag ->
            val bounds = composeRule.onNodeWithTag(tag)
                .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).getUnclippedBoundsInRoot()
            assertThat(bounds.top.value).isAtLeast(content.top.value)
            assertThat(bounds.bottom.value).isAtMost(content.bottom.value)
        }
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

    @Test
    @Config(qualifiers = "w320dp-h568dp-mdpi")
    fun sharedFoundationAtNormalText() = foundation("foundations_font100", 1f)

    @Test
    @Config(qualifiers = "w320dp-h568dp-mdpi")
    fun sharedFoundationAtOneHundredFiftyPercentText() = foundation("foundations_font150", 1.5f)

    @Test
    @Config(qualifiers = "w320dp-h568dp-mdpi")
    fun sharedFoundationAtTwoHundredPercentText() = foundation("foundations_font200", 2f)

    private fun foundation(name: String, fontScale: Float) {
        val reduced = fontScale >= 2f
        val context = ApplicationProvider.getApplicationContext<Context>()
        Settings.Global.putFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, if (reduced) 0f else 1f,
        )
        var observedMotion: Boolean? = null
        captureBoth("candidate-foundation", name, verify = {
            assertThat(observedMotion).isEqualTo(reduced)
        }, interaction = {
            composeRule.onNodeWithText("A setting whose label wraps").assertIsDisplayed()
            composeRule.onNodeWithText("Saving changes to this device").assertIsNotEnabled()
        }) { theme, density ->
            val display = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(display.density, fontScale)) {
                ChatUiTheme(themeMode = theme, density = density) {
                    observedMotion = LocalReducedMotion.current
                    ChatUiScaffold(
                        title = "Foundations",
                        subtitle = if (fontScale == 1.5f) "Applied app theme" else null,
                        titleBarStyle = TitleBarStyle.Compact,
                        onTitleClick = {},
                    ) { padding ->
                        Column(
                            modifier = Modifier.fillMaxSize()
                                .padding(padding)
                                .background(MaterialTheme.colorScheme.background)
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            GroupedSection(title = "Appearance") {
                                SettingsRow(
                                    label = "A setting whose label wraps",
                                    value = "Readable secondary value",
                                    trailing = { IconAction(Icons.Rounded.Add, "Row action", {}) },
                                )
                            }
                            LoadingButton(
                                text = "Saving changes to this device",
                                loading = true,
                                onClick = {},
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Skeleton(Modifier.fillMaxWidth().height(20.dp))
                        }
                    }
                }
            }
        }
    }

    private fun shell(
        name: String,
        start: String? = null,
        emptyHistory: Boolean = false,
        missingConfiguration: Boolean = false,
        longHistory: Boolean = false,
        fontScale: Float = 1f,
        imeBottom: Dp? = null,
        verifyLayout: () -> Unit = {},
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
        captureBoth("candidate-actual-shell", name, verify = verifyLayout, interaction = {
            waitForTag(ShellTestTags.Root)
            if (start != null) {
                waitForTag(ChatComposerTestTags.Input)
                composeRule.waitUntil(10_000) {
                    composeRule.onAllNodesWithTag(ChatScreenTestTags.Loading).fetchSemanticsNodes().isEmpty()
                }
                if (start == BaselineFixtures.CONTENT_CHAT) waitForText("Local baseline")
            }
            interaction()
        }) { theme, density ->
            BaselineShell(
                seeded, theme, fontScale, density,
                windowInsets = imeBottom?.let { WindowInsets(bottom = it) } ?: WindowInsets.safeDrawing,
            )
        }
        afterCapture()
    }

    private fun picker(name: String, includeHidden: Boolean, interaction: () -> Unit) {
        var state by mutableStateOf(BaselineFixtures.pickerState(includeHidden))
        captureBoth("candidate-standalone-picker", name, interaction) { theme, density ->
            ChatUiTheme(themeMode = theme, density = density, reducedMotion = true) {
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
        verify: () -> Unit = {},
        content: @Composable (Theme, UiDensity) -> Unit,
    ) {
        fun captureChecked(suffix: String) {
            verify()
            capture(group, "${name}_$suffix")
        }
        var theme by mutableStateOf(Theme.LIGHT)
        var density by mutableStateOf(UiDensity.COMFORTABLE)
        composeRule.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides storeOwner) {
                if (showContent) content(theme, density)
            }
        }
        composeRule.waitForIdle()
        interaction()
        composeRule.waitForIdle()
        captureChecked("comfortable_light")
        theme = Theme.DARK
        composeRule.waitForIdle()
        captureChecked("comfortable_dark")
        density = UiDensity.COMPACT
        composeRule.waitForIdle()
        captureChecked("compact_dark")
        theme = Theme.LIGHT
        composeRule.waitForIdle()
        captureChecked("compact_light")
    }

    private fun capture(group: String, name: String) {
        val output = System.getenv("CHATUI_REFINEMENT_DIR") ?: return
        val directory = File(output, group)
        require(directory.isAbsolute) { "CHATUI_REFINEMENT_DIR must be an absolute artifact path." }
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
