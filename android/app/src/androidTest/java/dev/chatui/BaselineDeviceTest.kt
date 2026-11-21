package dev.chatui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.MessageStatus
import dev.chatui.feature.chat.ChatComposerTestTags
import dev.chatui.feature.chat.ChatMessageListTestTags
import dev.chatui.feature.chat.ChatScreenTestTags
import dev.chatui.shell.ShellTestTags
import dev.chatui.testing.BaselineFixtures
import dev.chatui.testing.BaselineHarness
import dev.chatui.testing.BaselineShell
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Reuses the host baseline's data and real shell on a reference device, never a provider. */
@RunWith(AndroidJUnit4::class)
class BaselineDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var harness: BaselineHarness
    private val streams = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val owner = object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }
    private var showContent by mutableStateOf(true)

    @After
    fun tearDown() {
        streams.cancel()
        composeRule.runOnIdle {
            owner.viewModelStore.clear()
            showContent = false
        }
        composeRule.waitForIdle()
        if (::harness.isInitialized) {
            harness.adapter.events.close()
            harness.close()
        }
    }

    @Test
    fun longHistoryRendersThroughProductionShellAndRealPaging() {
        seed(start = BaselineFixtures.LONG_CHAT, longHistory = true)
        assertEquals(10_000, runBlocking { harness.messages.count(BaselineFixtures.LONG_CHAT) })
        composeRule.onNodeWithTag(ShellTestTags.Root).assertIsDisplayed()
        composeRule.onNodeWithTag(ChatComposerTestTags.Input).assertIsDisplayed()
        captureIfRequested("actual-shell-long-history")
        composeRule.onNodeWithTag(ChatMessageListTestTags.List).performScrollToIndex(0)
        composeRule.onNodeWithTag(ChatMessageListTestTags.Older).assertIsDisplayed().performClick()
    }

    @Test
    fun deterministicLocalStreamCompletesInProductionShell() {
        seed(start = BaselineFixtures.EMPTY_CHAT)
        composeRule.onNodeWithTag(ChatComposerTestTags.Input).performTextInput("Stream an invented local answer.")
        composeRule.onNodeWithTag(ChatComposerTestTags.Send).performClick()
        runBlocking { withTimeout(5_000) { harness.adapter.started.await() } }
        streams.launch { BaselineFixtures.playStream(harness.adapter) }
        composeRule.waitUntil(15_000) {
            runBlocking {
                harness.messages.allForConversation(BaselineFixtures.EMPTY_CHAT)
                    .lastOrNull()?.status == MessageStatus.COMPLETE
            } && composeRule.onAllNodesWithTag(ChatMessageListTestTags.Streaming)
                .fetchSemanticsNodes().isEmpty()
        }
        val messages = runBlocking { harness.messages.allForConversation(BaselineFixtures.EMPTY_CHAT) }
        assertEquals(2, messages.size)
        assertEquals(
            BaselineFixtures.markdown,
            messages.last().parts.filterIsInstance<ContentPart.Text>().joinToString("") { it.text },
        )
        assertTrue(messages.last().parts.any { it is ContentPart.Reasoning })
        captureIfRequested("actual-shell-local-stream-complete")
    }

    private fun seed(start: String, longHistory: Boolean = false) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        harness = runBlocking {
            BaselineHarness.create(context, longHistory = longHistory, startConversationId = start, onDisk = true)
        }
        composeRule.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                if (showContent) BaselineShell(harness)
            }
        }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag(ChatComposerTestTags.Input).fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag(ChatScreenTestTags.Loading).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun captureIfRequested(name: String) {
        if (InstrumentationRegistry.getArguments().getString("baselineCapture") != "true") return
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.getExternalFilesDir(null), BaselineFixtures.ID).apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { output ->
            check(composeRule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }
}
