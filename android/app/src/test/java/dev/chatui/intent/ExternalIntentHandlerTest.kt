package dev.chatui.intent

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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExternalIntentHandlerTest {
    @Test
    fun `share creates a conversation and stores the text as its draft`() = runTest {
        val router = ExternalIntentRouter()
        val drafts = InMemoryChatDrafts()
        val service = FakeConversationService(
            createdConversation = fakeConversation(id = "new-conversation"),
        )
        val action = ExternalIntentAction.ShareText("shared text")
        router.submit(action)

        val outcome = ExternalIntentHandler(router, service, drafts)
            .handle(action, onboardingCompleted = true)

        assertThat(outcome).isEqualTo(
            ExternalIntentOutcome.NavigateToChat(
                ChatDestination.createRoute("new-conversation"),
            ),
        )
        assertThat(drafts.draftFor("new-conversation")).isEqualTo("shared text")
        assertThat(router.pending.value).isNull()
    }

    @Test
    fun `share preserves drafts in other conversations`() = runTest {
        val router = ExternalIntentRouter()
        val drafts = InMemoryChatDrafts().apply {
            setDraft("existing-conversation", "keep this draft")
        }
        val service = FakeConversationService(
            createdConversation = fakeConversation(id = "new-conversation"),
        )
        val action = ExternalIntentAction.ShareText("shared text")
        router.submit(action)

        ExternalIntentHandler(router, service, drafts)
            .handle(action, onboardingCompleted = true)

        assertThat(drafts.draftFor("existing-conversation")).isEqualTo("keep this draft")
        assertThat(drafts.draftFor("new-conversation")).isEqualTo("shared text")
    }

    @Test
    fun `share does not overwrite an active composer draft`() = runTest {
        val router = ExternalIntentRouter()
        val drafts = InMemoryChatDrafts().apply {
            setDraft("active-conversation", "typing in progress")
        }
        val service = FakeConversationService(
            createdConversation = fakeConversation(id = "new-conversation"),
        )
        val action = ExternalIntentAction.ShareText("shared text")
        router.submit(action)

        ExternalIntentHandler(router, service, drafts)
            .handle(action, onboardingCompleted = true)

        assertThat(drafts.draftFor("active-conversation")).isEqualTo("typing in progress")
    }

    @Test
    fun `not configured share opens providers and keeps the action pending`() = runTest {
        val router = ExternalIntentRouter()
        val drafts = InMemoryChatDrafts()
        val service = FakeConversationService(createdConversation = null)
        val action = ExternalIntentAction.ShareText("shared text")
        router.submit(action)

        val outcome = ExternalIntentHandler(router, service, drafts)
            .handle(action, onboardingCompleted = true)

        assertThat(outcome).isEqualTo(ExternalIntentOutcome.OpenProviders)
        assertThat(router.pending.value).isEqualTo(action)
        assertThat(drafts.draftFor("new-conversation")).isEmpty()
    }

    @Test
    fun `not configured share during onboarding opens onboarding`() = runTest {
        val router = ExternalIntentRouter()
        val drafts = InMemoryChatDrafts()
        val service = FakeConversationService(createdConversation = null)
        val action = ExternalIntentAction.ShareText("shared text")
        router.submit(action)

        val outcome = ExternalIntentHandler(router, service, drafts)
            .handle(action, onboardingCompleted = false)

        assertThat(outcome).isEqualTo(ExternalIntentOutcome.OpenOnboarding)
        assertThat(router.pending.value).isEqualTo(action)
    }

    @Test
    fun `deep link after onboarding navigates to the chat route`() = runTest {
        val router = ExternalIntentRouter()
        val drafts = InMemoryChatDrafts()
        val service = FakeConversationService()
        val action = ExternalIntentAction.OpenChat(DEEP_LINK_ID)
        router.submit(action)

        val outcome = ExternalIntentHandler(router, service, drafts)
            .handle(action, onboardingCompleted = true)

        assertThat(outcome).isEqualTo(
            ExternalIntentOutcome.NavigateToChat(
                ChatDestination.createRoute(DEEP_LINK_ID),
            ),
        )
        assertThat(router.pending.value).isNull()
        assertThat(drafts.draftFor(DEEP_LINK_ID)).isEmpty()
    }

    @Test
    fun `deep link during onboarding opens onboarding and keeps the link pending`() = runTest {
        val router = ExternalIntentRouter()
        val drafts = InMemoryChatDrafts()
        val service = FakeConversationService()
        val action = ExternalIntentAction.OpenChat(DEEP_LINK_ID)
        router.submit(action)

        val outcome = ExternalIntentHandler(router, service, drafts)
            .handle(action, onboardingCompleted = false)

        assertThat(outcome).isEqualTo(ExternalIntentOutcome.OpenOnboarding)
        assertThat(router.pending.value).isEqualTo(action)
    }

    @Test
    fun `deep link to a missing conversation still routes to the chat destination`() = runTest {
        val router = ExternalIntentRouter()
        val drafts = InMemoryChatDrafts()
        val service = FakeConversationService(conversations = emptyList())
        val action = ExternalIntentAction.OpenChat(DEEP_LINK_ID)
        router.submit(action)

        val outcome = ExternalIntentHandler(router, service, drafts)
            .handle(action, onboardingCompleted = true)

        assertThat(outcome).isEqualTo(
            ExternalIntentOutcome.NavigateToChat(
                ChatDestination.createRoute(DEEP_LINK_ID),
            ),
        )
    }

    private companion object {
        const val DEEP_LINK_ID = "0190a5c4-1d2e-7abc-8def-0123456789ab"
    }
}
