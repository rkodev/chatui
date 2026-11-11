package dev.chatui.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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
}
