package dev.chatui.feature.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.core.database.attachment.AttachmentInput
import dev.chatui.core.database.attachment.AttachmentValidationCode
import dev.chatui.core.database.attachment.AttachmentValidationResult
import dev.chatui.core.database.attachment.MAX_IMAGE_BYTES
import dev.chatui.core.database.attachment.validateAttachmentFile
import dev.chatui.core.model.ChatError
import dev.chatui.core.model.ChatErrorCode
import dev.chatui.core.model.MessageRole
import dev.chatui.core.model.MessageStatus
import dev.chatui.core.model.Density as UiDensity
import java.time.Instant
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val now = Instant.parse("2026-11-03T12:00:00Z")

    private fun state(
        messages: List<dev.chatui.core.model.Message> = emptyList(),
        streaming: StreamingState? = null,
        availability: ChatAvailability = ChatAvailability(available = true),
        draft: String = "",
        hasOlder: Boolean = false,
        announcement: String = "",
        error: ChatError? = null,
        attachmentWarning: String? = null,
        confirmSend: Boolean = false,
    ) = ChatUiState(
        conversationId = "conversation-1",
        conversation = testConversation(),
        messages = messages,
        streaming = streaming,
        draft = draft,
        loading = false,
        hasOlder = hasOlder,
        availability = availability,
        announcement = announcement,
        error = error,
        attachmentWarning = attachmentWarning,
        confirmSendWithUnsupportedAttachments = confirmSend,
    )

    private fun screen(
        state: ChatUiState,
        onDraftChange: (String) -> Unit = {},
        onSend: () -> Unit = {},
        onStop: () -> Unit = {},
        onLoadOlder: () -> Unit = {},
        onRetryMessage: (dev.chatui.core.model.Message) -> Unit = {},
        onRegenerateMessage: (dev.chatui.core.model.Message) -> Unit = {},
        onEditLastUser: (dev.chatui.core.model.Message, String) -> Unit = { _, _ -> },
        onOpenProviders: () -> Unit = {},
        onOpenConversationSettings: () -> Unit = {},
        onShareConversation: ((ConversationShareFormat) -> Unit)? = null,
        onConfirmSend: () -> Unit = {},
        onCancelSendConfirmation: () -> Unit = {},
        onOpenModelPicker: () -> Unit = {},
        imeInsets: WindowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        composeRule.setContent {
            ChatUiTheme {
                ChatScreen(
                    state = state,
                    onDraftChange = onDraftChange,
                    onSend = onSend,
                    onStop = onStop,
                    onLoadOlder = onLoadOlder,
                    onRetryMessage = onRetryMessage,
                    onDismissError = {},
                    onRegenerateMessage = onRegenerateMessage,
                    onEditLastUser = onEditLastUser,
                    onOpenProviders = onOpenProviders,
                    onOpenConversationSettings = onOpenConversationSettings,
                    onShareConversation = onShareConversation,
                    onConfirmSend = onConfirmSend,
                    onCancelSendConfirmation = onCancelSendConfirmation,
                    onOpenModelPicker = onOpenModelPicker,
                    zoneId = java.time.ZoneOffset.UTC,
                    imeInsets = imeInsets,
                )
            }
        }
    }

    @Test
    fun `loading and not-found states render`() {
        screen(ChatUiState(conversationId = "conversation-1", loading = true))
        composeRule.onNodeWithTag(ChatScreenTestTags.Loading).assertIsDisplayed()
        composeRule.onNodeWithText("Loading conversation…").assertDoesNotExist()
    }

    @Test
    fun `empty conversation shows the welcome state and composer`() {
        screen(state())

        composeRule.onNodeWithTag(ChatScreenTestTags.Empty).assertIsDisplayed()
        composeRule.onNodeWithText("How can I help?").assertIsDisplayed()
        composeRule.onNodeWithTag(ChatComposerTestTags.Composer).assertIsDisplayed()
    }

    @Test
    fun `empty state prompt chips fill the draft`() {
        var draft = ""
        var sends = 0
        screen(state(), onDraftChange = { draft = it }, onSend = { sends++ })

        composeRule.onNodeWithTag(ChatScreenTestTags.emptyPrompt(0)).performClick()

        assertThat(draft).isEqualTo("Summarize a document")
        assertThat(sends).isEqualTo(0)
        composeRule.onNodeWithTag(ChatScreenTestTags.EmptyModelSelector).assertDoesNotExist()
    }

    @Test
    fun `chat screen reflows at 200 percent font scale`() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                ChatUiTheme {
                    ChatScreen(
                        state = state(),
                        onDraftChange = {},
                        onSend = {},
                        onStop = {},
                        onLoadOlder = {},
                        onRetryMessage = {},
                        onDismissError = {},
                        onRegenerateMessage = {},
                        onEditLastUser = { _, _ -> },
                        onOpenProviders = {},
                        onOpenConversationSettings = {},
                        onShareConversation = null,
                        onConfirmSend = {},
                        onCancelSendConfirmation = {},
                        zoneId = java.time.ZoneOffset.UTC,
                    )
                }
            }
        }

        composeRule.onNodeWithTag(ChatScreenTestTags.Empty).assertIsDisplayed()
        composeRule.onNodeWithText("How can I help?").assertIsDisplayed()
        composeRule.onNodeWithTag(ChatComposerTestTags.Composer).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `empty state prompt chips meet the 48 dp touch target`() {
        screen(state())

        val bounds = composeRule.onNodeWithTag(ChatScreenTestTags.emptyPrompt(0))
            .getUnclippedBoundsInRoot()
        assertThat((bounds.bottom - bounds.top).value).isAtLeast(48f)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `composer stays above the ime inset`() {
        screen(
            state(),
            imeInsets = WindowInsets(0.dp, 0.dp, 0.dp, 300.dp),
        )

        val composer = composeRule
            .onNodeWithTag(ChatComposerTestTags.Composer)
            .getUnclippedBoundsInRoot()

        composeRule.onNodeWithTag(ChatComposerTestTags.Composer).assertIsDisplayed()
        // The screen is 891 dp tall; a 300 dp keyboard must leave the composer
        // above the remaining 591 dp.
        assertThat(composer.bottom.value).isAtMost(591f)
        assertThat(composer.bottom.value).isAtLeast(580f)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `composer sits at the bottom when the keyboard is closed`() {
        screen(state(), imeInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp))

        val composer = composeRule
            .onNodeWithTag(ChatComposerTestTags.Composer)
            .getUnclippedBoundsInRoot()

        assertThat(composer.bottom.value).isAtLeast(880f)
    }

    @Test
    fun `no provider disables the composer and opens providers`() {
        var opened = 0
        screen(
            state(
                availability = ChatAvailability(
                    available = false,
                    reason = ChatUnavailableReason.NO_PROVIDER,
                    message = "No provider is configured.",
                ),
            ),
            onOpenProviders = { opened += 1 },
        )

        composeRule.onNodeWithTag(ChatScreenTestTags.NoProvider).assertIsDisplayed()
        composeRule.onNodeWithTag(ChatScreenTestTags.OpenProviders).performClick()
        composeRule.onNodeWithTag(ChatComposerTestTags.Input).assertIsNotEnabled()

        assertThat(opened).isEqualTo(1)
    }

    @Test
    fun `model unavailable shows its state`() {
        screen(
            state(
                availability = ChatAvailability(
                    available = false,
                    reason = ChatUnavailableReason.MODEL_UNAVAILABLE,
                    message = "The selected model is unavailable. Choose another model.",
                ),
            ),
        )

        composeRule.onNodeWithTag(ChatScreenTestTags.ModelUnavailable).assertIsDisplayed()
    }

    @Test
    fun `cancelled and interrupted messages explain what happened`() {
        val cancelled = testMessage(
            id = "assistant-1",
            sequence = 2,
            role = MessageRole.ASSISTANT,
            status = MessageStatus.CANCELLED,
            text = "Partial",
        )
        val interrupted = testMessage(
            id = "assistant-2",
            sequence = 3,
            role = MessageRole.ASSISTANT,
            status = MessageStatus.CANCELLED,
            text = "Recovered",
        ).copy(
            error = ChatError(
                code = ChatErrorCode.CANCELLED,
                message = "The app closed before this response finished. " +
                    "Partial content is preserved.",
                providerId = null,
                status = null,
                retryable = false,
                retryAfterSeconds = null,
                requestId = null,
                details = null,
                at = now,
            ),
        )

        screen(state(messages = listOf(cancelled, interrupted)))

        composeRule.onNodeWithTag(ChatMessageListTestTags.List).performScrollToIndex(0)
        composeRule.onNodeWithText("Response cancelled. Partial content is preserved.")
            .assertExists()
        composeRule.onNodeWithTag(ChatMessageListTestTags.List).performScrollToIndex(1)
        composeRule.onNodeWithText(
            "The app closed before this response finished. Partial content is preserved.",
        ).assertExists()
    }

    @Test
    fun `error message renders an inline card and retry intent`() {
        var retried: dev.chatui.core.model.Message? = null
        val failed = testMessage(
            id = "assistant-1",
            sequence = 2,
            role = MessageRole.ASSISTANT,
            status = MessageStatus.ERROR,
            text = "Partial",
        ).copy(
            error = ChatError(
                code = ChatErrorCode.RATE_LIMIT,
                message = "Rate limited",
                providerId = "provider-1",
                status = 429,
                retryable = true,
                retryAfterSeconds = 30.0,
                requestId = null,
                details = null,
                at = now,
            ),
        )

        screen(
            state(messages = listOf(failed), error = failed.error),
            onRetryMessage = { retried = it },
        )

        composeRule.onNodeWithTag(ChatMessageTestTags.Error).assertIsDisplayed()
        composeRule.onNodeWithTag(ChatScreenTestTags.Error).assertDoesNotExist()
        composeRule.onNodeWithText("Rate limited").assertIsDisplayed()
        composeRule.onNodeWithTag(ChatMessageTestTags.Retry).performClick()

        assertThat(retried?.id).isEqualTo("assistant-1")
    }

    @Test
    fun `actions target the last assistant and last user messages`() {
        var regenerated: dev.chatui.core.model.Message? = null
        var edited: Pair<dev.chatui.core.model.Message, String>? = null
        val assistant = testMessage(
            id = "assistant-1",
            sequence = 1,
            role = MessageRole.ASSISTANT,
            text = "Answer",
        )
        val lastUser = testMessage(
            id = "user-2",
            sequence = 2,
            role = MessageRole.USER,
            text = "Second",
        )

        screen(
            state(messages = listOf(assistant, lastUser)),
            onRegenerateMessage = { regenerated = it },
            onEditLastUser = { target, text -> edited = target to text },
        )

        composeRule.onNode(
            hasTestTag(ChatMessageActionsTestTags.Overflow) and
                hasAnyAncestor(hasTestTag(ChatMessageTestTags.message("assistant-1"))),
        ).performClick()
        composeRule.onNodeWithTag(ChatMessageActionsTestTags.Regenerate).performClick()
        assertThat(regenerated?.id).isEqualTo("assistant-1")

        composeRule.onNode(
            hasTestTag(ChatMessageActionsTestTags.Overflow) and
                hasAnyAncestor(hasTestTag(ChatMessageTestTags.message("user-2"))),
        ).performClick()
        composeRule.onNodeWithTag(ChatMessageActionsTestTags.Edit).performClick()
        composeRule.onNodeWithTag(ChatMessageActionsTestTags.EditInput)
            .performTextReplacement("Updated")
        composeRule.onNodeWithTag(ChatMessageActionsTestTags.EditConfirm).performClick()

        assertThat(edited?.first?.id).isEqualTo("user-2")
        assertThat(edited?.second).isEqualTo("Updated")
    }

    @Test
    fun `streaming row replaces the persisted placeholder`() {
        val user = testMessage(
            id = "user-1",
            sequence = 1,
            role = MessageRole.USER,
            text = "Hello",
        )
        val placeholder = testMessage(
            id = "assistant-1",
            sequence = 2,
            role = MessageRole.ASSISTANT,
            status = MessageStatus.STREAMING,
            text = "",
        )
        val streaming = createStreamingState(
            conversationId = "conversation-1",
            messageId = "assistant-1",
            startedAt = now,
        ).copy(text = "Hi", status = MessageStatus.STREAMING)

        screen(state(messages = listOf(user, placeholder), streaming = streaming))

        composeRule.onNodeWithTag(ChatMessageListTestTags.Streaming).assertIsDisplayed()
        composeRule.onNodeWithText("Hi").assertIsDisplayed()
        composeRule.onAllNodesWithTag(ChatMessageTestTags.text("assistant-1"))
            .assertCountEquals(1)
    }

    @Test
    fun `older messages are loaded on demand`() {
        var loads = 0
        screen(
            state(
                messages = listOf(
                    testMessage(id = "user-1", sequence = 1, role = MessageRole.USER, text = "Hi"),
                ),
                hasOlder = true,
            ),
            onLoadOlder = { loads += 1 },
        )

        composeRule.onNodeWithTag(ChatMessageListTestTags.Older).performClick()

        assertThat(loads).isEqualTo(1)
    }

    @Test
    fun `announcement is exposed as a polite live region`() {
        screen(state(announcement = "Response complete"))

        composeRule.onNodeWithTag(ChatScreenTestTags.Announcement)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
            .assertContentDescriptionEquals("Response complete")
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `header and composer actions meet the 48 dp touch target`() {
        screen(state(), onShareConversation = {})

        listOf(
            ChatScreenTestTags.More,
            ChatScreenTestTags.ModelChip,
            ChatComposerTestTags.Send,
        ).forEach { tag ->
            val bounds = composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertThat((bounds.bottom - bounds.top).value).isAtLeast(48f)
        }
    }

    @Test
    fun `conversation settings entry point is available from the header`() {
        var opened = 0
        screen(state(), onOpenConversationSettings = { opened += 1 })

        composeRule.onNodeWithTag(ChatScreenTestTags.More).performClick()
        composeRule.onNodeWithTag(ChatScreenTestTags.ConversationSettings).performClick()

        assertThat(opened).isEqualTo(1)
    }

    @Test
    fun `share action offers text and markdown formats`() {
        var selected: ConversationShareFormat? = null
        screen(
            state(messages = listOf(testMessage(text = "Hello"))),
            onShareConversation = { selected = it },
        )

        composeRule.onNodeWithTag(ChatScreenTestTags.More).performClick()
        composeRule.onNodeWithTag(ChatScreenTestTags.ShareText).performClick()

        assertThat(selected).isEqualTo(ConversationShareFormat.TEXT)
    }

    @Test
    fun `share action can select markdown`() {
        var selected: ConversationShareFormat? = null
        screen(
            state(messages = listOf(testMessage(text = "Hello"))),
            onShareConversation = { selected = it },
        )

        composeRule.onNodeWithTag(ChatScreenTestTags.More).performClick()
        composeRule.onNodeWithTag(ChatScreenTestTags.ShareMarkdown).performClick()

        assertThat(selected).isEqualTo(ConversationShareFormat.MARKDOWN)
    }

    @Test
    fun `share action is hidden without a conversation`() {
        screen(
            ChatUiState(conversationId = null, conversation = null, loading = false),
            onShareConversation = {},
        )

        composeRule.onNodeWithTag(ChatScreenTestTags.More).performClick()
        composeRule.onNodeWithTag(ChatScreenTestTags.ShareText).assertDoesNotExist()
        composeRule.onNodeWithTag(ChatScreenTestTags.ShareMarkdown).assertDoesNotExist()
    }

    @Test
    fun `header model selection is distinct from provider repair`() {
        var pickers = 0
        var providers = 0
        screen(
            state(availability = ChatAvailability(false, ChatUnavailableReason.NO_KEY, "Add a key")),
            onOpenModelPicker = { pickers++ },
            onOpenProviders = { providers++ },
        )

        composeRule.onNodeWithTag(ChatScreenTestTags.ModelChip).performClick()
        composeRule.onNodeWithTag(ChatScreenTestTags.OpenProviders).performClick()
        assertThat(pickers).isEqualTo(1)
        assertThat(providers).isEqualTo(1)
    }

    @Test
    fun `unsupported attachment warning and confirmation are shown`() {
        var confirmed = 0
        var cancelled = 0
        screen(
            state(
                attachmentWarning = "This model cannot receive images.",
                confirmSend = true,
            ),
            onConfirmSend = { confirmed += 1 },
            onCancelSendConfirmation = { cancelled += 1 },
        )

        composeRule.onNodeWithTag(ChatScreenTestTags.AttachmentWarning).assertDoesNotExist()
        composeRule.onAllNodesWithTag(ChatComposerTestTags.AttachmentWarning).assertCountEquals(1)
        composeRule.onNodeWithTag(ChatScreenTestTags.SendConfirmation).assertIsDisplayed()
        composeRule.onNodeWithTag(ChatScreenTestTags.SendAnyway).performClick()
        composeRule.onNodeWithTag(ChatScreenTestTags.CancelSend).performClick()

        assertThat(confirmed).isEqualTo(1)
        assertThat(cancelled).isEqualTo(1)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `photo documents and clipboard use the same background ingestion path`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val registry = PickerRegistry()
        val inputs = CopyOnWriteArrayList<AttachmentInput>()
        val uris = listOf("photo.png", "notes.txt", "data.json", "pasted.png").map { name ->
            Uri.parse("content://chatui-fixture/$name").also {
                shadowOf(context.contentResolver).registerInputStream(it, ByteArrayInputStream(name.toByteArray()))
            }
        }
        attachmentScreen(registry, onAdded = { inputs += it })
        openAttachmentAction(ChatComposerTestTags.AttachPhoto)
        composeRule.runOnIdle { registry.dispatchResult(registry.lastRequest, uris[0]) }
        composeRule.waitUntil(5_000) { inputs.size == 1 }
        openAttachmentAction(ChatComposerTestTags.AttachFile)
        composeRule.runOnIdle { registry.dispatchResult(registry.lastRequest, uris.subList(1, 3)) }
        composeRule.waitUntil(5_000) { inputs.size == 3 }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newRawUri("Synthetic image", uris[3]))
        openAttachmentAction(ChatComposerTestTags.Paste)
        composeRule.waitUntil(5_000) { inputs.size == 4 }

        assertThat(inputs.map { it.name }).containsExactly("photo.png", "notes.txt", "data.json", "pasted.png")
        composeRule.onNodeWithTag(ChatComposerTestTags.Input).assertTextEquals("Retained draft")
        composeRule.onNodeWithTag(ChatComposerTestTags.AttachmentError).assertDoesNotExist()
    }

    @Test
    fun `picker cancellation is not a failure and a late result cannot enter another conversation`() {
        val registry = PickerRegistry()
        val inputs = CopyOnWriteArrayList<AttachmentInput>()
        var current by mutableStateOf(state(draft = "First draft"))
        attachmentScreen(registry, current = { current }, onAdded = { inputs += it })
        openAttachmentAction(ChatComposerTestTags.AttachPhoto)
        composeRule.runOnIdle { registry.dispatchResult<Uri?>(registry.lastRequest, null) }
        openAttachmentAction(ChatComposerTestTags.AttachFile)
        composeRule.runOnIdle { registry.dispatchResult(registry.lastRequest, emptyList<Uri>()) }
        composeRule.onNodeWithTag(ChatComposerTestTags.AttachmentError).assertDoesNotExist()

        openAttachmentAction(ChatComposerTestTags.AttachPhoto)
        current = current.copy(
            conversationId = "conversation-2",
            conversation = testConversation(id = "conversation-2"),
            draft = "Second draft",
        )
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            registry.dispatchResult(registry.lastRequest, Uri.parse("content://chatui-fixture/late.png"))
        }
        composeRule.onNodeWithTag(ChatComposerTestTags.Input).assertTextEquals("Second draft")
        composeRule.onNodeWithTag(ChatComposerTestTags.AttachmentError).assertDoesNotExist()
        assertThat(inputs).isEmpty()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `denied reads close their stream show one composer error and retain existing attachments`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val denied = Uri.parse("content://chatui-fixture/denied.png")
        val closed = AtomicBoolean()
        shadowOf(context.contentResolver).registerInputStream(denied, object : InputStream() {
            override fun read(): Int = throw SecurityException("Synthetic revoked grant")
            override fun close() { closed.set(true) }
        })
        val previous = runBlocking {
            FakeChatAttachmentStore().addFiles(
                listOf(AttachmentInput("kept.txt", "text/plain", 1, byteArrayOf(1))),
            ).attachments.single()
        }
        val inputs = CopyOnWriteArrayList<AttachmentInput>()
        attachmentScreen(
            PickerRegistry(),
            current = { state(draft = "Keep typing").copy(pendingAttachments = listOf(previous)) },
            onAdded = { inputs += it },
        )
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newRawUri("Synthetic denied image", denied))
        openAttachmentAction(ChatComposerTestTags.Paste)
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(ChatComposerTestTags.AttachmentError).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithTag(ChatComposerTestTags.AttachmentError).assertCountEquals(1)
        composeRule.onNodeWithTag(ChatComposerTestTags.Input).assertTextEquals("Keep typing")
        composeRule.onNodeWithTag(ChatComposerTestTags.attachmentChip(previous.id)).assertExists()
        assertThat(closed.get()).isTrue()
        assertThat(inputs).isEmpty()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `blocked URI work leaves editing and stop responsive and is cancelled on navigation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://chatui-fixture/slow.png")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = AtomicBoolean()
        val readOnMain = AtomicBoolean(true)
        shadowOf(context.contentResolver).registerInputStream(uri, object : InputStream() {
            override fun read(): Int = -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                readOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                started.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "Synthetic blocked read timed out" }
                return -1
            }
            override fun close() { closed.set(true) }
        })
        val added = CopyOnWriteArrayList<AttachmentInput>()
        var stops = 0
        var current by mutableStateOf(
            state(
                draft = "Before",
                streaming = createStreamingState("conversation-1", "assistant", now).copy(text = "Partial"),
            ),
        )
        attachmentScreen(
            PickerRegistry(),
            current = { current },
            onDraftChange = { current = current.copy(draft = it) },
            onAdded = { added += it },
            onStop = { stops++ },
        )
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newRawUri("Synthetic slow image", uri))
        try {
            openAttachmentAction(ChatComposerTestTags.Paste)
            composeRule.waitUntil(5_000) { started.count == 0L }
            composeRule.onNodeWithTag(ChatComposerTestTags.Processing).assertIsDisplayed()
            composeRule.onNodeWithTag(ChatComposerTestTags.Input).performTextReplacement("Still editing")
            composeRule.onNodeWithTag(ChatComposerTestTags.Stop).performClick()
            assertThat(stops).isEqualTo(1)
            assertThat(readOnMain.get()).isFalse()
            current = state(draft = "Other draft").copy(
                conversationId = "conversation-2",
                conversation = testConversation(id = "conversation-2"),
            )
            composeRule.waitUntil(5_000) { closed.get() }
            composeRule.onNodeWithTag(ChatComposerTestTags.Input).assertTextEquals("Other draft")
            composeRule.onNodeWithTag(ChatComposerTestTags.AttachmentError).assertDoesNotExist()
            assertThat(added).isEmpty()
        } finally {
            release.countDown()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `clip batches retain distinct drop URIs deduplicate active reads and release cancelled grants`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = Uri.parse("content://chatui-fixture/first.txt")
        val second = Uri.parse("content://chatui-fixture/second.txt")
        val clip = ClipData.newRawUri("Synthetic drop", first).apply {
            addItem(ClipData.Item("not a URI"))
            addItem(ClipData.Item(second))
            addItem(ClipData.Item(first))
        }
        val added = mutableListOf<String>()
        var released = 0
        val owner = ChatAttachmentIngestion(
            this,
            read = { AttachmentInput(it.lastPathSegment!!, "text/plain", 1, byteArrayOf(1)) },
            onInput = { added += it.name },
            dispatcher = dispatcher,
        )
        owner.submit(attachmentUris(clip)) { released++ }
        owner.submit(listOf(first)) { released++ }
        assertThat(owner.processing).isTrue()
        assertThat(added).isEmpty()
        runCurrent()
        assertThat(added).containsExactly("first.txt", "second.txt")
        assertThat(released).isEqualTo(2)
        assertThat(owner.processing).isFalse()

        owner.submit(listOf(first)) { released++ }
        owner.close()
        runCurrent()
        assertThat(added).hasSize(2)
        assertThat(released).isEqualTo(3)
        assertThat(owner.processing).isFalse()
        assertThat(owner.error).isNull()
    }

    @Test
    fun `URI ingestion remains capped above the existing image limit and closes the source`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://chatui-fixture/oversized.png")
        var consumed = 0L
        var closed = false
        shadowOf(context.contentResolver).registerInputStream(uri, object : InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                consumed += length
                return length
            }
            override fun close() { closed = true }
        })
        val input = withContext(Dispatchers.IO) { readAttachmentInput(context, uri) }
        assertThat(consumed).isEqualTo(MAX_IMAGE_BYTES + 1)
        assertThat(input.bytes.size.toLong()).isEqualTo(MAX_IMAGE_BYTES + 1)
        assertThat(closed).isTrue()
        val validation = validateAttachmentFile(input.name, input.mimeType, input.bytes.size.toLong())
            as AttachmentValidationResult.Invalid
        assertThat(validation.error.code).isEqualTo(AttachmentValidationCode.TOO_LARGE)
    }

    @Test
    fun `attachment warning has one owner before any send confirmation`() {
        screen(state(attachmentWarning = "This model cannot receive images."))
        composeRule.onAllNodesWithTag(ChatComposerTestTags.AttachmentWarning).assertCountEquals(1)
        composeRule.onNodeWithTag(ChatScreenTestTags.AttachmentWarning).assertDoesNotExist()
        composeRule.onNodeWithTag(ChatScreenTestTags.SendConfirmation).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `short landscape ime keeps the composer controls usable at two hundred percent in both densities`() {
        var uiDensity by mutableStateOf(UiDensity.COMFORTABLE)
        var draft by mutableStateOf("Retained multiline\ndraft")
        var stops = 0
        val header = ChatTopBarState()
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, 2f),
                LocalChatTopBarState provides header,
            ) {
                ChatUiTheme(density = uiDensity, reducedMotion = true) {
                    Box(Modifier.width(640.dp).height(264.dp)) {
                        ChatScreen(
                            state = state(
                                draft = draft,
                                streaming = createStreamingState("conversation-1", "assistant", now),
                            ).copy(processingAttachments = true),
                            onDraftChange = { draft = it },
                            onSend = {},
                            onStop = { stops++ },
                            onLoadOlder = {},
                            onRetryMessage = {},
                            onDismissError = {},
                            imeInsets = WindowInsets(0.dp, 0.dp, 0.dp, 180.dp),
                        )
                    }
                }
            }
        }
        UiDensity.entries.forEach { density ->
            uiDensity = density
            composeRule.onNodeWithTag(ChatComposerTestTags.Input).performTextReplacement("Still editing")
            composeRule.onNodeWithTag(ChatComposerTestTags.Input).assertTextEquals("Still editing")
            composeRule.onNodeWithTag(ChatComposerTestTags.Attach)
                .assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            composeRule.onNodeWithTag(ChatComposerTestTags.Stop)
                .assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
            val composer = composeRule.onNodeWithTag(ChatComposerTestTags.Composer).getUnclippedBoundsInRoot()
            assertThat(composer.bottom.value).isAtMost(84f)
        }
        assertThat(stops).isEqualTo(2)
    }

    private fun attachmentScreen(
        registry: PickerRegistry,
        current: () -> ChatUiState = { state(draft = "Retained draft") },
        onDraftChange: (String) -> Unit = {},
        onAdded: (List<AttachmentInput>) -> Unit = {},
        onStop: () -> Unit = {},
    ) {
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        composeRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                ChatUiTheme(reducedMotion = true) {
                    ChatScreen(
                        state = current(),
                        onDraftChange = onDraftChange,
                        onSend = {},
                        onStop = onStop,
                        onLoadOlder = {},
                        onRetryMessage = {},
                        onDismissError = {},
                        onAddAttachments = onAdded,
                        imeInsets = WindowInsets(0, 0, 0, 0),
                    )
                }
            }
        }
    }

    private fun openAttachmentAction(tag: String) {
        composeRule.onNodeWithTag(ChatComposerTestTags.Attach).performClick()
        composeRule.onNodeWithTag(tag).performClick()
    }

    private class PickerRegistry : ActivityResultRegistry() {
        var lastRequest = 0
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            lastRequest = requestCode
        }
    }
}
