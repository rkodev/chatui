package dev.chatui

import android.content.Context
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.createGraph
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.datastore.settings.AndroidSettings
import dev.chatui.intent.ExternalIntentAction
import dev.chatui.intent.ExternalIntentRouter
import dev.chatui.intent.ExternalIntentHandler
import dev.chatui.intent.ExternalIntentOutcome
import dev.chatui.feature.chat.InMemoryChatDrafts
import dev.chatui.feature.conversations.ConversationService
import dev.chatui.feature.conversations.CreateConversationResult
import dev.chatui.shell.ChatDestination
import dev.chatui.shell.ProviderDestination
import dev.chatui.shell.ProviderModelsDestination
import dev.chatui.shell.SettingsDestination
import dev.chatui.shell.ShellDestination
import dev.chatui.shell.isIsolatedShellRoute
import dev.chatui.shell.finishOnboarding
import dev.chatui.shell.resolveShellStartDestination
import dev.chatui.shell.shellDestinations
import dev.chatui.shell.shellStartDestination
import dev.chatui.feature.settings.SettingsSection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShellNavigationTest {
    @Test
    fun `navigation graph resolves every shell destination`() {
        val navController = navController()

        ShellDestination.entries.forEach { destination ->
            assertThat(navController.graph.findNode(destination.route)).isNotNull()
        }
        assertThat(navController.graph.findNode(ChatDestination.ROUTE)).isNotNull()
        assertThat(navController.graph.findNode(ProviderDestination.ROUTE)).isNotNull()
        assertThat(navController.graph.findNode(ProviderModelsDestination.ROUTE)).isNotNull()
        assertThat(navController.graph.findNode(SettingsDestination.APPEARANCE)).isNotNull()
        assertThat(navController.graph.findNode(SettingsDestination.CHAT_DEFAULTS)).isNotNull()
        assertThat(navController.graph.findNode(SettingsDestination.DATA)).isNotNull()
        assertThat(navController.graph.findNode(SettingsDestination.BACKUP)).isNotNull()
        assertThat(navController.graph.findNode(SettingsDestination.ABOUT)).isNotNull()
        assertThat(navController.graph.startDestinationRoute)
            .isEqualTo(ShellDestination.Conversations.route)
    }

    @Test
    fun `settings section routes keep the settings destination selected`() {
        assertThat(ShellDestination.Settings.matches("settings")).isTrue()
        assertThat(ShellDestination.Settings.matches("settings/appearance")).isTrue()
        assertThat(ShellDestination.Settings.matches("settings/chat")).isTrue()
        assertThat(ShellDestination.Settings.matches("providers")).isFalse()
    }

    @Test
    fun `data and backup settings routes are wired`() {
        assertThat(SettingsDestination.routeFor(SettingsSection.DATA))
            .isEqualTo(SettingsDestination.DATA)
        assertThat(SettingsDestination.routeFor(SettingsSection.BACKUP))
            .isEqualTo(SettingsDestination.BACKUP)
        assertThat(ShellDestination.Settings.matches(SettingsDestination.DATA)).isTrue()
        assertThat(ShellDestination.Settings.matches(SettingsDestination.BACKUP)).isTrue()
    }

    @Test
    fun `provider detail route keeps the providers destination selected`() {
        assertThat(ShellDestination.Providers.matches("providers")).isTrue()
        assertThat(ShellDestination.Providers.matches("providers/provider-1")).isTrue()
        assertThat(ShellDestination.Providers.matches("settings")).isFalse()
    }

    @Test
    fun `provider detail route accepts a provider id`() {
        val navController = navController()

        navController.navigate(ProviderDestination.createRoute("provider-1"))

        assertThat(navController.currentDestination?.route)
            .isEqualTo(ProviderDestination.ROUTE)
        assertThat(
            navController.currentBackStackEntry
                ?.arguments
                ?.getString(ProviderDestination.ARG_PROVIDER_ID),
        ).isEqualTo("provider-1")
    }

    @Test
    fun `model management preserves provider scope and back stack`() {
        val navController = navController()
        val providerId = "provider with spaces"
        navController.navigate(ProviderDestination.createRoute(providerId))
        navController.navigate(ProviderModelsDestination.createRoute(providerId))

        assertThat(navController.currentDestination?.route)
            .isEqualTo(ProviderModelsDestination.ROUTE)
        assertThat(
            navController.currentBackStackEntry?.arguments
                ?.getString(ProviderModelsDestination.ARG_PROVIDER_ID),
        ).isEqualTo(providerId)
        assertThat(ShellDestination.Providers.matches(ProviderModelsDestination.ROUTE)).isTrue()
        navController.popBackStack()
        assertThat(navController.currentDestination?.route).isEqualTo(ProviderDestination.ROUTE)
    }

    @Test
    fun `first run starts on onboarding`() {
        assertThat(shellStartDestination(AndroidSettings()))
            .isEqualTo(ShellDestination.Onboarding.route)
    }

    @Test
    fun `onboarding is isolated from the shell chrome`() {
        assertThat(isIsolatedShellRoute(ShellDestination.Onboarding.route)).isTrue()
        assertThat(isIsolatedShellRoute(ShellDestination.Conversations.route)).isFalse()
        assertThat(isIsolatedShellRoute("chat/conversation-1")).isFalse()
        assertThat(isIsolatedShellRoute(null)).isFalse()
    }

    @Test
    fun `completed onboarding starts on conversations`() {
        assertThat(shellStartDestination(AndroidSettings(onboardingCompleted = true)))
            .isEqualTo(ShellDestination.Conversations.route)
    }

    @Test
    fun `completed onboarding restores the last active conversation`() {
        assertThat(
            shellStartDestination(
                settings = AndroidSettings(onboardingCompleted = true),
                lastActiveConversation = fakeConversation("conversation-1"),
            ),
        ).isEqualTo(ChatDestination.createRoute("conversation-1"))
    }

    @Test
    fun `pending deep link takes precedence over last active restore`() {
        assertThat(
            shellStartDestination(
                settings = AndroidSettings(onboardingCompleted = true),
                lastActiveConversation = fakeConversation("conversation-1"),
                hasPendingExternalIntent = true,
            ),
        ).isEqualTo(ShellDestination.Conversations.route)
    }

    @Test
    fun `onboarding takes precedence over pending deep link and last active`() {
        assertThat(
            shellStartDestination(
                settings = AndroidSettings(onboardingCompleted = false),
                lastActiveConversation = fakeConversation("conversation-1"),
                hasPendingExternalIntent = true,
            ),
        ).isEqualTo(ShellDestination.Onboarding.route)
    }

    @Test
    fun `resolver restores an existing last active conversation`() = runTest {
        val service = FakeConversationService(
            conversations = listOf(fakeConversation("conversation-1")),
        )
        service.setLastActiveConversation("conversation-1")

        val start = resolveShellStartDestination(
            settings = AndroidSettings(onboardingCompleted = true),
            conversationService = service,
            hasPendingExternalIntent = false,
        )

        assertThat(start).isEqualTo(ChatDestination.createRoute("conversation-1"))
    }

    @Test
    fun `resolver falls back to the list and clears a deleted last active pointer`() = runTest {
        val service = FakeConversationService(conversations = emptyList())
        service.setLastActiveConversation("deleted-conversation")

        val start = resolveShellStartDestination(
            settings = AndroidSettings(onboardingCompleted = true),
            conversationService = service,
            hasPendingExternalIntent = false,
        )

        assertThat(start).isEqualTo(ShellDestination.Conversations.route)
        assertThat(service.observeLastActiveConversationId().first()).isNull()
    }

    @Test
    fun `resolver keeps onboarding ahead of a pending deep link`() = runTest {
        val service = FakeConversationService(
            conversations = listOf(fakeConversation("conversation-1")),
        )
        service.setLastActiveConversation("conversation-1")

        val start = resolveShellStartDestination(
            settings = AndroidSettings(onboardingCompleted = false),
            conversationService = service,
            hasPendingExternalIntent = true,
        )

        assertThat(start).isEqualTo(ShellDestination.Onboarding.route)
    }

    @Test
    fun `resolver prefers a pending deep link over last active restore`() = runTest {
        val service = FakeConversationService(
            conversations = listOf(fakeConversation("conversation-1")),
        )
        service.setLastActiveConversation("conversation-1")

        val start = resolveShellStartDestination(
            settings = AndroidSettings(onboardingCompleted = true),
            conversationService = service,
            hasPendingExternalIntent = true,
        )

        assertThat(start).isEqualTo(ShellDestination.Conversations.route)
    }

    @Test
    fun `a consumed deep link does not override last active restore on recreation`() = runTest {
        val router = ExternalIntentRouter()
        val action = ExternalIntentAction.OpenChat("deep-link-conversation")
        router.submit(action)
        router.consume(action)

        val service = FakeConversationService(
            conversations = listOf(fakeConversation("conversation-1")),
        )
        service.setLastActiveConversation("conversation-1")

        val start = resolveShellStartDestination(
            settings = AndroidSettings(onboardingCompleted = true),
            conversationService = service,
            hasPendingExternalIntent = router.pending.value != null,
        )

        assertThat(start).isEqualTo(ChatDestination.createRoute("conversation-1"))
    }

    @Test
    fun `chat destination accepts a conversation id`() {
        val navController = navController()

        navController.navigate(ChatDestination.createRoute("conversation-1"))

        assertThat(navController.currentDestination?.route).isEqualTo(ChatDestination.ROUTE)
        assertThat(
            navController.currentBackStackEntry
                ?.arguments
                ?.getString(ChatDestination.ARG_CONVERSATION_ID),
        ).isEqualTo("conversation-1")
    }

    @Test
    fun `skipping optional backup opens useful Chats and removes onboarding`() {
        val controller = navController(ShellDestination.Onboarding.route)
        controller.finishOnboarding()
        assertThat(controller.currentDestination?.route).isEqualTo(ShellDestination.Conversations.route)
        assertThat(runCatching { controller.getBackStackEntry(ShellDestination.Onboarding.route) }.isFailure)
            .isTrue()
    }

    @Test
    fun `backup completion cannot override a pending share routed before or after Skip`() = runTest {
        listOf(false, true).forEach { handledBeforeSkip ->
            val controller = navController(ShellDestination.Onboarding.route)
            val router = ExternalIntentRouter()
            val drafts = InMemoryChatDrafts()
            drafts.setDraft("existing", "Keep existing draft")
            var configured = false
            var createdChats = 0
            val service = object : ConversationService by FakeConversationService() {
                override suspend fun createConversation(title: String?): CreateConversationResult =
                    if (configured) {
                        createdChats++
                        CreateConversationResult.Created(fakeConversation("shared-chat"))
                    } else CreateConversationResult.NotConfigured("Connect a provider first")
            }
            val handler = ExternalIntentHandler(router, service, drafts)
            val action = ExternalIntentAction.ShareText("Pending shared text")
            router.submit(action)
            assertThat(handler.handle(action, onboardingCompleted = false))
                .isEqualTo(ExternalIntentOutcome.OpenOnboarding)
            configured = true
            if (handledBeforeSkip) {
                controller.navigate(
                    (handler.handle(action, true) as ExternalIntentOutcome.NavigateToChat).route,
                )
            }
            controller.finishOnboarding()
            if (!handledBeforeSkip) {
                assertThat(controller.currentDestination?.route).isEqualTo(ShellDestination.Conversations.route)
                assertThat(router.pending.value).isEqualTo(action)
                controller.navigate(
                    (handler.handle(action, true) as ExternalIntentOutcome.NavigateToChat).route,
                )
            }
            controller.finishOnboarding()
            assertThat(controller.currentBackStackEntry?.arguments?.getString(ChatDestination.ARG_CONVERSATION_ID))
                .isEqualTo("shared-chat")
            assertThat(createdChats).isEqualTo(1)
            assertThat(drafts.draftFor("shared-chat")).isEqualTo("Pending shared text")
            assertThat(drafts.draftFor("existing")).isEqualTo("Keep existing draft")
            assertThat(router.pending.value).isNull()
        }
    }

    @Test
    fun `late backup completion preserves valid and stale deep link destinations`() = runTest {
        listOf("existing", "missing").forEach { id ->
            val controller = navController(ShellDestination.Onboarding.route)
            val router = ExternalIntentRouter()
            val drafts = InMemoryChatDrafts()
            drafts.setDraft("existing", "Retain draft")
            val service = FakeConversationService(conversations = listOf(fakeConversation("existing")))
            service.setLastActiveConversation("existing")
            val handler = ExternalIntentHandler(router, service, drafts)
            val action = ExternalIntentAction.OpenChat(id)
            router.submit(action)
            assertThat(handler.handle(action, false)).isEqualTo(ExternalIntentOutcome.OpenOnboarding)
            val route = (handler.handle(action, true) as ExternalIntentOutcome.NavigateToChat).route
            controller.navigate(route)
            controller.finishOnboarding()
            assertThat(controller.currentBackStackEntry?.arguments?.getString(ChatDestination.ARG_CONVERSATION_ID))
                .isEqualTo(id)
            assertThat(drafts.draftFor("existing")).isEqualTo("Retain draft")
            assertThat(router.pending.value).isNull()
        }
    }

    private fun navController(
        startDestination: String = ShellDestination.Conversations.route,
    ): NavHostController {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val navController = NavHostController(context).apply {
            navigatorProvider.addNavigator(ComposeNavigator())
        }

        navController.graph = navController.createGraph(
            startDestination = startDestination,
        ) {
            shellDestinations(navController)
        }
        return navController
    }
}
