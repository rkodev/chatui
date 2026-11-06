package dev.chatui.intent

import android.content.Intent
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import dev.chatui.FakeConversationService
import dev.chatui.fakeConversation
import dev.chatui.feature.chat.InMemoryChatDrafts
import dev.chatui.shell.ChatDestination
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Edge cases for inbound share and deep-link delivery that are easy to miss
 * when only the happy path is exercised.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExternalIntentEdgeCaseTest {
    private val conversationId = "0190a5c4-1d2e-7abc-8def-0123456789ab"

    @Test
    fun `cold start share becomes a pending share action`() {
        val router = ExternalIntentRouter()
        val launchIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "  shared from cold start  ")
        }

        ExternalIntentIntake.actionFor(launchIntent)?.let(router::submit)

        assertThat(router.pending.value)
            .isEqualTo(ExternalIntentAction.ShareText("shared from cold start"))
    }

    @Test
    fun `warm start share is delivered once`() {
        val router = ExternalIntentRouter()
        val newIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "shared while warm")
        }

        repeat(3) {
            ExternalIntentIntake.actionFor(newIntent)?.let(router::submit)
        }

        assertThat(router.pending.value)
            .isEqualTo(ExternalIntentAction.ShareText("shared while warm"))
    }

    @Test
    fun `non-text share is ignored without mutating drafts`() {
        val drafts = InMemoryChatDrafts()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_TEXT, "not text")
        }

        val action = ExternalIntentIntake.actionFor(intent)

        assertThat(action).isNull()
        assertThat(drafts.draftFor(conversationId)).isEmpty()
    }

    @Test
    fun `empty and blank shares are ignored`() {
        val empty = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "")
        }
        val blank = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, " \n\t ")
        }

        assertThat(ExternalIntentIntake.actionFor(empty)).isNull()
        assertThat(ExternalIntentIntake.actionFor(blank)).isNull()
    }

    @Test
    fun `malformed deep links are ignored`() {
        val malformed = listOf(
            "https://chat/$conversationId",
            "chatui://conversation/$conversationId",
            "chatui://chat/",
            "chatui://chat/not-a-uuid",
            "chatui://chat/$conversationId/extra",
            "chatui://chat/$conversationId?token=secret",
        )

        malformed.forEach { link ->
            assertThat(ExternalIntentIntake.actionFor(Intent(Intent.ACTION_VIEW, Uri.parse(link))))
                .isNull()
        }
    }

    @Test
    fun `deep link stays pending through onboarding and is consumed afterwards`() = runTest {
        val router = ExternalIntentRouter()
        val drafts = InMemoryChatDrafts()
        val service = FakeConversationService()
        val action = ExternalIntentAction.OpenChat(conversationId)
        router.submit(action)
        val handler = ExternalIntentHandler(router, service, drafts)

        assertThat(handler.handle(action, onboardingCompleted = false))
            .isEqualTo(ExternalIntentOutcome.OpenOnboarding)
        assertThat(router.pending.value).isEqualTo(action)

        assertThat(handler.handle(action, onboardingCompleted = true))
            .isEqualTo(
                ExternalIntentOutcome.NavigateToChat(
                    ChatDestination.createRoute(conversationId),
                ),
            )
        assertThat(router.pending.value).isNull()
    }

    @Test
    fun `share during onboarding with a configured provider still creates a conversation`() =
        runTest {
            val router = ExternalIntentRouter()
            val drafts = InMemoryChatDrafts()
            val service = FakeConversationService(
                createdConversation = fakeConversation(id = "new-conversation"),
            )
            val action = ExternalIntentAction.ShareText("shared text")
            router.submit(action)

            val outcome = ExternalIntentHandler(router, service, drafts)
                .handle(action, onboardingCompleted = false)

            assertThat(outcome).isEqualTo(
                ExternalIntentOutcome.NavigateToChat(
                    ChatDestination.createRoute("new-conversation"),
                ),
            )
            assertThat(drafts.draftFor("new-conversation")).isEqualTo("shared text")
            assertThat(router.pending.value).isNull()
        }
}
