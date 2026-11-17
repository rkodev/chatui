package dev.chatui.feature.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.core.model.Attachment
import dev.chatui.core.model.AttachmentKind
import dev.chatui.core.model.SendKey
import dev.chatui.core.model.Density as UiDensity
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatComposerTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `send is enabled for a non-blank draft and invokes the intent`() {
        var sends = 0
        var draft by mutableStateOf("")

        composeRule.setContent {
            ChatUiTheme {
                ChatComposer(
                    draft = draft,
                    onDraftChange = { draft = it },
                    onSend = { sends += 1 },
                    onStop = {},
                    sendKey = SendKey.ENTER,
                    enabled = true,
                    streaming = false,
                    sending = false,
                )
            }
        }

        composeRule.onNodeWithTag(ChatComposerTestTags.Input).performTextInput("Hello")
        composeRule.onNodeWithTag(ChatComposerTestTags.Send).assertIsEnabled().performClick()

        assertThat(sends).isEqualTo(1)
    }

    @Test
    fun `send is disabled for a blank draft`() {
        composeRule.setContent {
            ChatUiTheme {
                ChatComposer(
                    draft = "",
                    onDraftChange = {},
                    onSend = {},
                    onStop = {},
                    sendKey = SendKey.ENTER,
                    enabled = true,
                    streaming = false,
                    sending = false,
                )
            }
        }

        composeRule.onNodeWithTag(ChatComposerTestTags.Send).assertIsNotEnabled()
    }

    @Test
    fun `stop replaces send while streaming and invokes the stop intent`() {
        var stops = 0

        composeRule.setContent {
            ChatUiTheme {
                ChatComposer(
                    draft = "Hello",
                    onDraftChange = {},
                    onSend = {},
                    onStop = { stops += 1 },
                    sendKey = SendKey.ENTER,
                    enabled = true,
                    streaming = true,
                    sending = false,
                )
            }
        }

        composeRule.onNodeWithTag(ChatComposerTestTags.Send).assertDoesNotExist()
        composeRule.onNodeWithTag(ChatComposerTestTags.Stop)
            .assertIsDisplayed()
            .performClick()

        assertThat(stops).isEqualTo(1)
    }

    @Test
    fun `enter send-key mode sends from the IME action`() {
        var sends = 0

        composeRule.setContent {
            ChatUiTheme {
                ChatComposer(
                    draft = "Hello",
                    onDraftChange = {},
                    onSend = { sends += 1 },
                    onStop = {},
                    sendKey = SendKey.ENTER,
                    enabled = true,
                    streaming = false,
                    sending = false,
                )
            }
        }

        composeRule.onNodeWithTag(ChatComposerTestTags.Input).performImeAction()

        assertThat(sends).isEqualTo(1)
    }

    @Test
    fun `mod-enter mode does not send from a plain IME action`() {
        var sends = 0

        composeRule.setContent {
            ChatUiTheme {
                ChatComposer(
                    draft = "Hello",
                    onDraftChange = {},
                    onSend = { sends += 1 },
                    onStop = {},
                    sendKey = SendKey.MOD_ENTER,
                    enabled = true,
                    streaming = false,
                    sending = false,
                )
            }
        }

        composeRule.onNodeWithTag(ChatComposerTestTags.Input)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ImeAction,
                    ImeAction.Default,
                ),
            )

        assertThat(sends).isEqualTo(0)
    }

    @Test
    fun `unavailable and offline states disable and explain sending`() {
        composeRule.setContent {
            ChatUiTheme {
                ChatComposer(
                    draft = "Hello",
                    onDraftChange = {},
                    onSend = {},
                    onStop = {},
                    sendKey = SendKey.ENTER,
                    enabled = false,
                    streaming = false,
                    sending = false,
                    offline = true,
                )
            }
        }

        composeRule.onNodeWithTag(ChatComposerTestTags.Offline).assertIsDisplayed()
        composeRule.onNodeWithTag(ChatComposerTestTags.Input).assertIsNotEnabled()
        composeRule.onNodeWithTag(ChatComposerTestTags.Send).assertIsNotEnabled()
    }

    @Test
    fun `focus returns to the attach trigger after the sheet closes`() {
        var photos = 0

        composeRule.setContent {
            ChatUiTheme {
                ChatComposer(
                    draft = "",
                    onDraftChange = {},
                    onSend = {},
                    onStop = {},
                    sendKey = SendKey.ENTER,
                    enabled = true,
                    streaming = false,
                    sending = false,
                    onAddPhoto = { photos += 1 },
                )
            }
        }

        composeRule.onNodeWithTag(ChatComposerTestTags.Attach).requestFocus()
        composeRule.onNodeWithTag(ChatComposerTestTags.Attach).assertIsFocused()
        composeRule.onNodeWithTag(ChatComposerTestTags.Attach).performClick()
        composeRule.onNodeWithTag(ChatComposerTestTags.AttachPhoto).performClick()

        assertThat(photos).isEqualTo(1)
        composeRule.onNodeWithTag(ChatComposerTestTags.Attach).assertIsFocused()
    }

    @Test
    fun `composer icon actions expose content descriptions`() {
        composeRule.setContent {
            ChatUiTheme {
                ChatComposer(
                    draft = "Hello",
                    onDraftChange = {},
                    onSend = {},
                    onStop = {},
                    sendKey = SendKey.ENTER,
                    enabled = true,
                    streaming = false,
                    sending = false,
                )
            }
        }

        composeRule.onNodeWithTag(ChatComposerTestTags.Attach)
            .assertContentDescriptionEquals("Add attachment")
        composeRule.onNodeWithTag(ChatComposerTestTags.Send)
            .assertContentDescriptionEquals("Send message")
    }

    @Test
    fun `stop action exposes a content description while streaming`() {
        composeRule.setContent {
            ChatUiTheme {
                ChatComposer(
                    draft = "",
                    onDraftChange = {},
                    onSend = {},
                    onStop = {},
                    sendKey = SendKey.ENTER,
                    enabled = true,
                    streaming = true,
                    sending = false,
                )
            }
        }

        composeRule.onNodeWithTag(ChatComposerTestTags.Stop)
            .assertContentDescriptionEquals("Stop response")
    }

    @Test
    fun `attachments enable send and expose a remove action`() {
        var removed: String? = null
        val attachment = attachment()

        composeRule.setContent {
            ChatUiTheme {
                ChatComposer(
                    draft = "",
                    onDraftChange = {},
                    onSend = {},
                    onStop = {},
                    sendKey = SendKey.ENTER,
                    enabled = true,
                    streaming = false,
                    sending = false,
                    attachments = listOf(attachment),
                    onRemoveAttachment = { removed = it },
                )
            }
        }

        composeRule.onNodeWithTag(ChatComposerTestTags.attachmentChip(attachment.id))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(ChatComposerTestTags.Send).assertIsEnabled()
        val remove = composeRule.onNodeWithTag(
            ChatComposerTestTags.removeAttachment(attachment.id),
        )
        val bounds = remove.getUnclippedBoundsInRoot()
        assertThat((bounds.bottom - bounds.top).value).isAtLeast(48f)
        assertThat((bounds.right - bounds.left).value).isAtLeast(48f)
        remove.performClick()

        assertThat(removed).isEqualTo(attachment.id)
    }

    @Test
    fun `attach button opens a sheet with photo file and paste`() {
        var photos = 0
        var files = 0
        var pastes = 0

        composeRule.setContent {
            ChatUiTheme {
                ChatComposer(
                    draft = "",
                    onDraftChange = {},
                    onSend = {},
                    onStop = {},
                    sendKey = SendKey.ENTER,
                    enabled = true,
                    streaming = false,
                    sending = false,
                    onAddPhoto = { photos += 1 },
                    onAddFile = { files += 1 },
                    onPasteImage = { pastes += 1 },
                )
            }
        }

        composeRule.onNodeWithTag(ChatComposerTestTags.Attach).performClick()
        composeRule.onNodeWithTag(ChatComposerTestTags.AttachPhoto).assertIsDisplayed()
        composeRule.onNodeWithTag(ChatComposerTestTags.AttachFile).assertIsDisplayed()
        composeRule.onNodeWithTag(ChatComposerTestTags.Paste).assertIsDisplayed()
        composeRule.onNodeWithTag(ChatComposerTestTags.AttachPhoto).performClick()

        assertThat(photos).isEqualTo(1)
        assertThat(files).isEqualTo(0)
        assertThat(pastes).isEqualTo(0)
    }

    @Test
    fun `attachment warning and error are rendered`() {
        composeRule.setContent {
            ChatUiTheme {
                ChatComposer(
                    draft = "Hello",
                    onDraftChange = {},
                    onSend = {},
                    onStop = {},
                    sendKey = SendKey.ENTER,
                    enabled = true,
                    streaming = false,
                    sending = false,
                    attachmentWarning = "This model cannot receive images.",
                    attachmentError = "photo.png is too large.",
                )
            }
        }

        composeRule.onNodeWithTag(ChatComposerTestTags.AttachmentWarning).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(ChatComposerTestTags.AttachmentError).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `typing normal characters updates the draft without sending`() {
        var sends = 0
        var draft by mutableStateOf("")

        composeRule.setContent {
            ChatUiTheme {
                ChatComposer(
                    draft = draft,
                    onDraftChange = { draft = it },
                    onSend = { sends += 1 },
                    onStop = {},
                    sendKey = SendKey.ENTER,
                    enabled = true,
                    streaming = false,
                    sending = false,
                )
            }
        }

        composeRule.onNodeWithTag(ChatComposerTestTags.Input).performTextInput("Hello")

        assertThat(draft).isEqualTo("Hello")
        assertThat(sends).isEqualTo(0)
    }

    @Test
    fun `ctrl enter sends in mod-enter mode`() {
        var sends = 0

        composeRule.setContent {
            ChatUiTheme {
                ChatComposer(
                    draft = "Hello",
                    onDraftChange = {},
                    onSend = { sends += 1 },
                    onStop = {},
                    sendKey = SendKey.MOD_ENTER,
                    enabled = true,
                    streaming = false,
                    sending = false,
                )
            }
        }

        composeRule.onNodeWithTag(ChatComposerTestTags.Input).requestFocus()
        composeRule.onNodeWithTag(ChatComposerTestTags.Input).performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) }
        }

        assertThat(sends).isEqualTo(1)
    }

    @Test
    fun `plain enter does not send in mod-enter mode`() {
        var sends = 0

        composeRule.setContent {
            ChatUiTheme {
                ChatComposer(
                    draft = "Hello",
                    onDraftChange = {},
                    onSend = { sends += 1 },
                    onStop = {},
                    sendKey = SendKey.MOD_ENTER,
                    enabled = true,
                    streaming = false,
                    sending = false,
                )
            }
        }

        composeRule.onNodeWithTag(ChatComposerTestTags.Input).requestFocus()
        composeRule.onNodeWithTag(ChatComposerTestTags.Input).performKeyInput {
            pressKey(Key.Enter)
        }

        assertThat(sends).isEqualTo(0)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `ordinary input grows through eight lines then scrolls without truncating the draft`() {
        var draft by mutableStateOf("Line 1")
        composeRule.setContent {
            ChatUiTheme(reducedMotion = true) {
                Box(Modifier.width(411.dp).height(700.dp)) {
                    ChatComposer(
                        draft, { draft = it }, {}, {}, SendKey.MOD_ENTER,
                        enabled = true, streaming = false, sending = false,
                    )
                }
            }
        }
        draft = (1..8).joinToString("\n") { "Line $it" }
        composeRule.onNodeWithTag(ChatComposerTestTags.Input).assertHeightIsAtLeast(192.dp)
        val eight = composeRule.onNodeWithTag(ChatComposerTestTags.Input).getUnclippedBoundsInRoot()
        draft += "\nLine 9"
        val nine = composeRule.onNodeWithTag(ChatComposerTestTags.Input).getUnclippedBoundsInRoot()
        assertThat((nine.bottom - nine.top).value).isEqualTo((eight.bottom - eight.top).value)
        composeRule.onNodeWithTag(ChatComposerTestTags.Input).assertTextEquals(draft)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `short large-font viewport keeps editable text and immediate stop while adding attachments`() {
        var draft by mutableStateOf((1..30).joinToString("\n") { "Draft line $it" })
        var stops = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                ChatUiTheme(reducedMotion = true) {
                    Box(Modifier.width(320.dp).height(180.dp)) {
                        ChatComposer(
                            draft, { draft = it }, {}, { stops++ }, SendKey.MOD_ENTER,
                            enabled = true, streaming = true, sending = false,
                            processingAttachments = true,
                        )
                    }
                }
            }
        }
        val input = composeRule.onNodeWithTag(ChatComposerTestTags.Input).getUnclippedBoundsInRoot()
        assertThat((input.bottom - input.top).value).isAtMost(80f)
        composeRule.onNodeWithTag(ChatComposerTestTags.Input).performTextInput("\nKeep the rest")
        assertThat(draft).contains("Draft line 30")
        assertThat(draft).contains("Keep the rest")
        composeRule.onNodeWithTag(ChatComposerTestTags.Processing).assertIsDisplayed()
        composeRule.onNodeWithTag(ChatComposerTestTags.Stop)
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
        assertThat(stops).isEqualTo(1)
        composeRule.onNodeWithTag(ChatComposerTestTags.Attach).assertIsEnabled()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `attachment filename wraps without sharing its removal target`() {
        val file = attachment().copy(name = "meeting-notes-for-our-project.txt", size = 2048)
        var removed: String? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                ChatUiTheme(reducedMotion = true) {
                    ChatComposer(
                        "", {}, {}, {}, SendKey.ENTER,
                        enabled = true, streaming = false, sending = false,
                        attachments = listOf(file), onRemoveAttachment = { removed = it },
                    )
                }
            }
        }
        composeRule.onNodeWithText(file.name).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("2.0 KB").assertExists()
        composeRule.onNodeWithTag(ChatComposerTestTags.removeAttachment(file.id))
            .performScrollTo().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
        assertThat(removed).isEqualTo(file.id)
    }

    @Test
    fun `send changes directly to a stable stop target without waiting for motion`() {
        var streaming by mutableStateOf(false)
        var stops = 0
        composeRule.setContent {
            ChatUiTheme {
                ChatComposer(
                    "Draft", {}, {}, { stops++ }, SendKey.ENTER,
                    enabled = true, streaming = streaming, sending = false,
                )
            }
        }
        val send = composeRule.onNodeWithTag(ChatComposerTestTags.Send).getUnclippedBoundsInRoot()
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle { streaming = true }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.waitForIdle()
        val stop = composeRule.onNodeWithTag(ChatComposerTestTags.Stop).getUnclippedBoundsInRoot()
        assertThat(stop).isEqualTo(send)
        composeRule.onNodeWithTag(ChatComposerTestTags.Send).assertDoesNotExist()
        composeRule.onNodeWithTag(ChatComposerTestTags.Stop).performClick()
        assertThat(stops).isEqualTo(1)
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `short input discloses readable attachments and removal instead of clipping the controls`() {
        var density by mutableStateOf(UiDensity.COMFORTABLE)
        var files by mutableStateOf(listOf(attachment(), attachment().copy(id = "attachment-2")))
        var stops = 0
        val removed = mutableListOf<String>()
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                ChatUiTheme(density = density, reducedMotion = true) {
                    Box(Modifier.width(640.dp).height(56.dp)) {
                        ChatComposer(
                            "Retained draft", {}, {}, { stops++ }, SendKey.ENTER,
                            enabled = true, streaming = true, sending = false,
                            processingAttachments = true,
                            attachments = files,
                            attachmentWarning = "This model cannot receive images.",
                            onRemoveAttachment = { id ->
                                removed += id
                                files = files.filterNot { it.id == id }
                            },
                            onAddPhoto = {},
                        )
                    }
                }
            }
        }
        UiDensity.entries.forEach { selected ->
            density = selected
            val first = files.first()
            composeRule.onNodeWithTag(ChatComposerTestTags.Input)
                .assertTextEquals("Retained draft").assertHeightIsAtLeast(48.dp)
            composeRule.onNodeWithTag(ChatComposerTestTags.Attach)
                .assertContentDescriptionEquals("Attachments").performClick()
            composeRule.onNodeWithTag(ChatComposerTestTags.attachmentChip(first.id))
                .performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag(ChatComposerTestTags.removeAttachment(first.id))
                .performScrollTo().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
            composeRule.onNodeWithTag(ChatComposerTestTags.AttachmentWarning).performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag(ChatComposerTestTags.AttachPhoto).performScrollTo().performClick()
            composeRule.onNodeWithTag(ChatComposerTestTags.Stop)
                .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
            val stop = composeRule.onNodeWithTag(ChatComposerTestTags.Stop).getUnclippedBoundsInRoot()
            val composer = composeRule.onNodeWithTag(ChatComposerTestTags.Composer).getUnclippedBoundsInRoot()
            assertThat(stop.top.value).isAtLeast(composer.top.value)
            assertThat(stop.bottom.value).isAtMost(composer.bottom.value)
        }
        assertThat(stops).isEqualTo(2)
        assertThat(removed).containsExactly("attachment-1", "attachment-2").inOrder()
        assertThat(files).isEmpty()
    }

    private fun attachment(): Attachment = Attachment(
        id = "attachment-1",
        messageId = null,
        kind = AttachmentKind.FILE,
        mimeType = "text/plain",
        name = "notes.txt",
        size = 12,
        sha256 = "sha",
        storageKey = "attachments/sha",
        width = null,
        height = null,
        createdAt = Instant.parse("2026-11-05T12:00:00Z"),
    )
}
