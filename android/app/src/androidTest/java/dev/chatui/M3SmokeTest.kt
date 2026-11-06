package dev.chatui

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.core.model.StreamEvent
import dev.chatui.core.network.NormalizedChatRequest
import dev.chatui.testing.FakeProviderAdapter
import dev.chatui.testing.testChatContext
import dev.chatui.testing.testChatParams
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the instrumentation harness, Compose test rule, and shared fakes work
 * on a device or emulator.
 */
@RunWith(AndroidJUnit4::class)
class M3SmokeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun composeSurfaceRenders() {
        composeRule.setContent {
            ChatUiTheme {
                Text(
                    text = "M3 smoke",
                    modifier = Modifier.testTag(SMOKE_TAG),
                )
            }
        }

        composeRule.onNodeWithTag(SMOKE_TAG).assertIsDisplayed()
    }

    @Test
    fun fakeProviderAdapterEmitsControlledStream() = runTest {
        val adapter = FakeProviderAdapter()
        adapter.emitAndFinish(
            StreamEvent.Start(responseId = "response-1"),
            StreamEvent.TextDelta("hello"),
            StreamEvent.Finish(dev.chatui.core.model.FinishReason.STOP),
        )

        val events = adapter.streamChat(
            context = testChatContext().providerContext,
            request = NormalizedChatRequest(
                model = "openrouter/auto",
                system = null,
                messages = emptyList(),
                params = testChatParams(),
            ),
        ).toList()

        assertEquals(3, events.size)
        assertEquals(StreamEvent.TextDelta("hello"), events[1])
    }

    private companion object {
        const val SMOKE_TAG = "m3-smoke"
    }
}
