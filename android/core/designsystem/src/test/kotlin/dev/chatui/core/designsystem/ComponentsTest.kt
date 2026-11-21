package dev.chatui.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.model.Density as UiDensity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComponentsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `settings row renders label value and handles clicks`() {
        var clicked = false
        composeRule.setContent {
            ChatUiTheme(reducedMotion = true) {
                GroupedSection(title = "Appearance") {
                    SettingsRow(
                        label = "Theme",
                        icon = Icons.Rounded.Settings,
                        value = "System",
                        showChevron = true,
                        onClick = { clicked = true },
                    )
                }
            }
        }

        composeRule.onNodeWithText("APPEARANCE").assertIsDisplayed()
        composeRule.onNodeWithText("Theme").assertIsDisplayed()
        composeRule.onNodeWithText("System").assertIsDisplayed()
        composeRule.onNodeWithText("Theme").performClick()

        composeRule.runOnIdle { assertThat(clicked).isTrue() }
    }

    @Test
    fun `grouped section renders footer`() {
        composeRule.setContent {
            ChatUiTheme(reducedMotion = true) {
                GroupedSection(title = "Data", footer = "Stored on this device") {
                    SettingsRow(label = "Export", showDivider = false)
                }
            }
        }

        composeRule.onNodeWithText("DATA").assertIsDisplayed()
        composeRule.onNodeWithText("Stored on this device").assertIsDisplayed()
    }

    @Test
    fun `empty state renders title body and action`() {
        var clicked = false
        composeRule.setContent {
            ChatUiTheme(reducedMotion = true) {
                EmptyState(
                    title = "No conversations",
                    body = "Start a new chat",
                    action = {
                        PrimaryButton(text = "New chat", onClick = { clicked = true })
                    },
                )
            }
        }

        composeRule.onNodeWithText("No conversations").assertIsDisplayed()
        composeRule.onNodeWithText("Start a new chat").assertIsDisplayed()
        composeRule.onNodeWithText("New chat").performClick()

        composeRule.runOnIdle { assertThat(clicked).isTrue() }
    }

    @Test
    fun `error state renders retry action`() {
        var retried = false
        composeRule.setContent {
            ChatUiTheme(reducedMotion = true) {
                ErrorState(
                    title = "Could not load",
                    body = "Check your connection",
                    onRetry = { retried = true },
                )
            }
        }

        composeRule.onNodeWithText("Could not load").assertIsDisplayed()
        composeRule.onNodeWithText("Check your connection").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()

        composeRule.runOnIdle { assertThat(retried).isTrue() }
    }

    @Test
    fun `inline banner renders title and body`() {
        composeRule.setContent {
            ChatUiTheme(reducedMotion = true) {
                InlineBanner(
                    severity = BannerSeverity.Warning,
                    title = "No provider configured",
                    body = "Add a provider to start chatting",
                )
            }
        }

        composeRule.onNodeWithText("No provider configured").assertIsDisplayed()
        composeRule.onNodeWithText("Add a provider to start chatting").assertIsDisplayed()
    }

    @Test
    fun `skeleton list renders placeholders`() {
        composeRule.setContent {
            ChatUiTheme(reducedMotion = true) {
                SkeletonList(
                    modifier = Modifier.testTag("skeleton-list"),
                    count = 2,
                )
            }
        }

        composeRule.onNodeWithTag("skeleton-list").assertIsDisplayed()
    }

    @Test
    fun `segmented control reports selection`() {
        var selected by mutableStateOf(0)
        composeRule.setContent {
            ChatUiTheme(reducedMotion = true) {
                SegmentedControl(
                    options = listOf("Chats", "Archived"),
                    selectedIndex = selected,
                    onSelected = { selected = it },
                )
            }
        }

        composeRule.onNodeWithText("Archived").performClick()

        composeRule.runOnIdle { assertThat(selected).isEqualTo(1) }
    }

    @Test
    fun `buttons render and report clicks`() {
        var primaryClicks = 0
        var secondaryClicks = 0
        var iconClicks = 0
        var circleClicks = 0
        composeRule.setContent {
            ChatUiTheme(reducedMotion = true) {
                Column {
                    PrimaryButton(text = "Save", onClick = { primaryClicks++ })
                    SecondaryButton(text = "Cancel", onClick = { secondaryClicks++ })
                    IconAction(
                        icon = Icons.Rounded.Add,
                        contentDescription = "Add",
                        onClick = { iconClicks++ },
                    )
                    CircleIconButton(
                        icon = Icons.Rounded.Add,
                        contentDescription = "Send",
                        onClick = { circleClicks++ },
                    )
                    LoadingButton(text = "Loading", loading = true, onClick = {})
                }
            }
        }

        composeRule.onNodeWithText("Save").performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithContentDescription("Add").performClick()
        composeRule.onNodeWithContentDescription("Send").performClick()

        composeRule.runOnIdle {
            assertThat(primaryClicks).isEqualTo(1)
            assertThat(secondaryClicks).isEqualTo(1)
            assertThat(iconClicks).isEqualTo(1)
            assertThat(circleClicks).isEqualTo(1)
        }
    }

    @Test
    fun `stepper reports clamped changes`() {
        var value by mutableStateOf(2)
        composeRule.setContent {
            ChatUiTheme(reducedMotion = true) {
                Stepper(
                    value = value,
                    onValueChange = { value = it },
                    min = 0,
                    max = 3,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Increase").performClick()
        composeRule.runOnIdle { assertThat(value).isEqualTo(3) }

        composeRule.onNodeWithContentDescription("Increase").performClick()
        composeRule.runOnIdle { assertThat(value).isEqualTo(3) }
    }

    @Test
    fun `labeled slider renders label and value`() {
        composeRule.setContent {
            ChatUiTheme(reducedMotion = true) {
                LabeledSlider(
                    label = "Temperature",
                    value = 0.5f,
                    onValueChange = {},
                    valueText = "50%",
                )
            }
        }

        composeRule.onNodeWithText("Temperature").assertIsDisplayed()
        composeRule.onNodeWithText("50%").assertIsDisplayed()
    }

    @Test
    fun `brand mark exposes a content description`() {
        composeRule.setContent {
            ChatUiTheme(reducedMotion = true) {
                BrandMark()
            }
        }

        composeRule.onNodeWithContentDescription("ChatUI").assertIsDisplayed()
    }

    @Test
    fun `chat ui scaffold renders title subtitle and content`() {
        composeRule.setContent {
            ChatUiTheme(reducedMotion = true) {
                ChatUiScaffold(
                    title = "Chats",
                    subtitle = "3 conversations",
                    scrollState = rememberScrollState(),
                ) { padding ->
                    Text(
                        text = "Body",
                        modifier = Modifier.padding(padding),
                    )
                }
            }
        }

        composeRule.onNodeWithText("Chats").assertIsDisplayed()
        composeRule.onNodeWithText("3 conversations").assertIsDisplayed()
        composeRule.onNodeWithText("Body").assertIsDisplayed()
    }

    @Test
    fun `chat ui scaffold reflows at 200 percent font scale`() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                ChatUiTheme(reducedMotion = true) {
                    ChatUiScaffold(
                        title = "A long screen title that wraps",
                        subtitle = "A subtitle that also needs room",
                        scrollState = rememberScrollState(),
                    ) { padding ->
                        Text(
                            text = "Body",
                            modifier = Modifier.padding(padding),
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("A long screen title that wraps").assertIsDisplayed()
        composeRule.onNodeWithText("A subtitle that also needs room").assertIsDisplayed()
        composeRule.onNodeWithText("Body").assertIsDisplayed()
    }

    @Test
    fun `soft shadow modifier renders`() {
        composeRule.setContent {
            ChatUiTheme(reducedMotion = true) {
                Box(
                    modifier = Modifier
                        .testTag("shadow")
                        .size(40.dp)
                        .softShadow(4.dp, RoundedCornerShape(8.dp)),
                )
            }
        }

        composeRule.onNodeWithTag("shadow").assertIsDisplayed()
    }

    @Test
    fun `rows and buttons grow at narrow width and double font scale in both densities`() {
        var density by mutableStateOf(UiDensity.COMFORTABLE)
        composeRule.setContent {
            val display = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(display.density, 2f)) {
                ChatUiTheme(density = density, reducedMotion = true) {
                    Column(Modifier.width(320.dp)) {
                        SettingsRow(
                            label = "A long accessible setting label",
                            value = "A secondary value that must not squeeze the label",
                            modifier = Modifier.testTag("growing-row"),
                            trailing = {
                                IconAction(Icons.Rounded.Add, "Row action", {})
                            },
                        )
                        PrimaryButton(
                            text = "An action label that wraps over several lines",
                            onClick = {},
                            modifier = Modifier.fillMaxWidth().testTag("growing-button"),
                        )
                    }
                }
            }
        }

        UiDensity.entries.forEach { selected ->
            composeRule.runOnIdle { density = selected }
            composeRule.onNodeWithText("A long accessible setting label").assertIsDisplayed()
            composeRule.onNodeWithText("A secondary value that must not squeeze the label")
                .assertIsDisplayed()
            composeRule.onNodeWithTag("growing-row").assertHeightIsAtLeast(96.dp)
            composeRule.onNodeWithTag("growing-button").assertHeightIsAtLeast(96.dp)
            composeRule.onNodeWithContentDescription("Row action")
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun `compact icon stepper and selectable targets remain at least 48 dp`() {
        composeRule.setContent {
            ChatUiTheme(density = UiDensity.COMPACT, reducedMotion = true) {
                Column {
                    IconAction(Icons.Rounded.Add, "Add item", {})
                    Stepper(value = 1, onValueChange = {}, max = 2)
                    SegmentedControl(listOf("Chats", "Archived"), 0, {})
                }
            }
        }

        listOf("Add item", "Increase", "Decrease").forEach {
            composeRule.onNodeWithContentDescription(it)
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
        composeRule.onNodeWithText("Archived").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `compact scaffold uses a stable height and preserves title action and slots`() {
        var clicked = false
        composeRule.setContent {
            ChatUiTheme(reducedMotion = true) {
                ChatUiScaffold(
                    title = "Chat",
                    subtitle = "Model",
                    titleBarStyle = TitleBarStyle.Compact,
                    contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                    onSubtitleClick = { clicked = true },
                    subtitleTestTag = "model",
                    actions = { IconAction(Icons.Rounded.Add, "More", {}) },
                    snackbarHost = { Text("Notice", modifier = Modifier.testTag("notice")) },
                ) { padding ->
                    Text("Body", modifier = Modifier.padding(padding).testTag("body"))
                }
            }
        }

        composeRule.onNodeWithTag("model").performClick().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("chat-ui-top-bar").assertHeightIsEqualTo(72.dp)
        composeRule.onNodeWithContentDescription("More").assertIsDisplayed()
        composeRule.onNodeWithTag("notice").assertIsDisplayed()
        val barBottom = composeRule.onNodeWithTag("chat-ui-top-bar")
            .fetchSemanticsNode().boundsInRoot.bottom
        val bodyTop = composeRule.onNodeWithTag("body").fetchSemanticsNode().boundsInRoot.top
        composeRule.runOnIdle {
            assertThat(clicked).isTrue()
            assertThat(bodyTop).isAtLeast(barBottom)
        }
    }

    @Test
    fun `compact scaffold without a subtitle grows to two large-font title lines with full accessible text`() {
        var large by mutableStateOf(false)
        composeRule.setContent {
            val display = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(display.density, if (large) 2f else 1f),
            ) {
                ChatUiTheme(reducedMotion = true) {
                    Box(Modifier.width(320.dp)) {
                        ChatUiScaffold(
                            title = if (large) "A long screen title that must wrap without clipping" else "Chats",
                            titleBarStyle = TitleBarStyle.Compact,
                            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                        ) { Text("Body", Modifier.padding(it)) }
                    }
                }
            }
        }

        composeRule.onNodeWithTag("chat-ui-top-bar").assertHeightIsEqualTo(56.dp)
        val normal = composeRule.onNodeWithTag("chat-ui-top-bar").getUnclippedBoundsInRoot()
        composeRule.runOnIdle { large = true }
        val grown = composeRule.onNodeWithTag("chat-ui-top-bar").getUnclippedBoundsInRoot()
        assertThat((grown.bottom - grown.top).value).isGreaterThan((normal.bottom - normal.top).value)
        composeRule.onNodeWithText("A long screen title that must wrap without clipping")
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { getLayout ->
                val layouts = mutableListOf<TextLayoutResult>()
                getLayout(layouts)
                assertThat(layouts.single().lineCount).isEqualTo(2)
                assertThat(layouts.single().isLineEllipsized(1)).isTrue()
            }
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-mdpi")
    fun `short keyboard viewport retains model title semantics and space for body actions`() {
        val title = "A deliberately long conversation title that remains available to accessibility"
        val model = "A model name that also needs bounded space at large fonts"
        var clicks = 0
        composeRule.setContent {
            val display = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(display.density, 2f)) {
                ChatUiTheme(reducedMotion = true) {
                    ChatUiScaffold(
                        title = title,
                        subtitle = model,
                        titleBarStyle = TitleBarStyle.Compact,
                        contentWindowInsets = WindowInsets(bottom = 224.dp),
                        onTitleClick = { clicks++ },
                        titleContentDescription = "Change model",
                        subtitleTestTag = "model",
                        navigationIcon = { IconAction(Icons.Rounded.Add, "History", {}) },
                        actions = { IconAction(Icons.Rounded.Settings, "More", {}) },
                    ) { padding ->
                        Column(Modifier.fillMaxSize().padding(padding)) {
                            Box(Modifier.weight(1f)) { Text("Body") }
                            IconAction(Icons.Rounded.Add, "Body action", {})
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithTag("model").assertHeightIsAtLeast(48.dp).performClick()
        assertThat(clicks).isEqualTo(1)
        assertThat(
            composeRule.onNodeWithTag("model").fetchSemanticsNode()
                .config[SemanticsProperties.StateDescription],
        ).isEqualTo(title)
        composeRule.onNodeWithText(model).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("History").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("More").assertHeightIsAtLeast(48.dp)
        val action = composeRule.onNodeWithContentDescription("Body action")
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).getUnclippedBoundsInRoot()
        assertThat(action.bottom.value).isAtMost(136f)
    }

    @Test
    fun `static skeleton does not schedule animation and pending button retains its label`() {
        var pending by mutableStateOf(true)
        var clicks = 0
        composeRule.setContent {
            ChatUiTheme(reducedMotion = false) {
                Column {
                    Skeleton(Modifier.size(48.dp).testTag("static-loading"))
                    LoadingButton("Add provider", pending, { clicks++ })
                }
            }
        }

        composeRule.onNodeWithTag("static-loading").assertIsDisplayed()
        composeRule.onNodeWithText("Add provider").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithText("Add provider").performClick()
        composeRule.runOnIdle {
            assertThat(clicks).isEqualTo(0)
            pending = false
        }
        composeRule.onNodeWithText("Add provider").performClick()
        composeRule.runOnIdle { assertThat(clicks).isEqualTo(1) }
    }
}
