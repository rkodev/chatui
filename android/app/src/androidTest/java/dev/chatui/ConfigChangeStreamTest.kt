package dev.chatui

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.FinishReason
import dev.chatui.core.model.Message
import dev.chatui.core.model.MessageRole
import dev.chatui.core.model.StreamEvent
import dev.chatui.feature.chat.ChatComposerTestTags
import dev.chatui.feature.chat.ChatMessageListTestTags
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves that recreating the activity does not cancel an active stream.
 *
 * The test hosts the real [dev.chatui.feature.chat.ChatRoute] in
 * [ConfigChangeTestActivity] with a controlled [dev.chatui.testing.FakeProviderAdapter].
 * It starts a send, waits for the first partial delta, recreates the activity,
 * and then proves the same assistant message keeps receiving deltas.
 */
@RunWith(AndroidJUnit4::class)
class ConfigChangeStreamTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<ConfigChangeTestActivity>
    private lateinit var harness: ConfigChangeHarness

    @Before
    fun setUp() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        harness = createConfigChangeHarness(appContext)
        ConfigChangeTestDependencies.conversationId = harness.conversationId
        ConfigChangeTestDependencies.dependencies = harness.dependencies

        scenario = ActivityScenario.launch(ConfigChangeTestActivity::class.java)
        waitForComposer()
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) {
            runCatching {
                scenario.onActivity { activity ->
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
            scenario.close()
        }
        ConfigChangeTestDependencies.conversationId = null
        ConfigChangeTestDependencies.dependencies = null
    }

    @Test
    fun activeStreamSurvivesActivityRecreation() {
        startStreamAndWaitForPartial()
        val assistantBefore = assistantMessage()

        scenario.recreate()
        composeRule.waitForIdle()
        waitForComposer()

        assertFalse(
            "activity recreation must not cancel the provider stream",
            harness.adapter.cancelled,
        )
        waitForText("partial")

        finishStreamAndAssert(assistantBefore.id)
    }

    @Test
    fun realOrientationChangeKeepsStream() {
        startStreamAndWaitForPartial()
        val assistantBefore = assistantMessage()

        scenario.onActivity { activity ->
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        waitForOrientation(Configuration.ORIENTATION_LANDSCAPE)
        waitForComposer()

        assertFalse(
            "a real configuration change must not cancel the provider stream",
            harness.adapter.cancelled,
        )
        waitForText("partial")

        finishStreamAndAssert(assistantBefore.id)
    }

    private fun startStreamAndWaitForPartial() {
        composeRule.onNodeWithTag(ChatComposerTestTags.Input)
            .performTextInput("Hello")
        composeRule.onNodeWithTag(ChatComposerTestTags.Send)
            .performClick()

        runBlocking {
            withTimeout(5_000) { harness.adapter.started.await() }
        }

        harness.adapter.events.trySend(StreamEvent.Start(responseId = "response-1"))
        harness.adapter.events.trySend(StreamEvent.TextDelta("partial"))
        waitForText("partial")
    }

    private fun finishStreamAndAssert(assistantId: String) {
        harness.adapter.events.trySend(StreamEvent.TextDelta(" more"))
        waitForText("partial more")

        harness.adapter.events.trySend(StreamEvent.Finish(FinishReason.STOP))
        harness.adapter.events.close()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasTestTag(ChatMessageListTestTags.Streaming))
                .fetchSemanticsNodes().isEmpty()
        }

        val messages = runBlocking { harness.store.all(harness.conversationId) }
        val assistants = messages.filter { it.role == MessageRole.ASSISTANT }

        assertEquals("exactly one assistant message", 1, assistants.size)
        assertEquals("assistant message id is stable", assistantId, assistants.single().id)
        assertTrue(
            "the final message contains both deltas",
            assistants.single().parts.any { part ->
                part is ContentPart.Text && part.text == "partial more"
            },
        )
        assertFalse("the provider stream completed without cancellation", harness.adapter.cancelled)
    }

    private fun assistantMessage(): Message = runBlocking {
        harness.store.all(harness.conversationId)
            .single { it.role == MessageRole.ASSISTANT }
    }

    private fun waitForComposer() {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasTestTag(ChatComposerTestTags.Input))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText(text, substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForOrientation(orientation: Int) {
        composeRule.waitUntil(10_000) {
            runCatching {
                var current = Configuration.ORIENTATION_UNDEFINED
                scenario.onActivity { activity ->
                    current = activity.resources.configuration.orientation
                }
                current == orientation
            }.getOrDefault(false)
        }
    }
}
