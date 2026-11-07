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
import dev.chatui.shell.ChatDestination
import dev.chatui.shell.ProviderDestination
import dev.chatui.shell.SettingsDestination
import dev.chatui.shell.ShellDestination
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
    fun `first run starts on onboarding`() {
        assertThat(shellStartDestination(AndroidSettings()))
            .isEqualTo(ShellDestination.Onboarding.route)
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

    private fun navController(): NavHostController {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val navController = NavHostController(context).apply {
            navigatorProvider.addNavigator(ComposeNavigator())
        }

        navController.graph = navController.createGraph(
            startDestination = ShellDestination.Conversations.route,
        ) {
            shellDestinations(navController)
        }
        return navController
    }
}
