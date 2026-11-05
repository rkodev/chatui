package dev.chatui

import android.content.Context
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.createGraph
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.datastore.settings.AndroidSettings
import dev.chatui.shell.ChatDestination
import dev.chatui.shell.ProviderDestination
import dev.chatui.shell.ShellDestination
import dev.chatui.shell.shellDestinations
import dev.chatui.shell.shellStartDestination
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
        assertThat(navController.graph.startDestinationRoute)
            .isEqualTo(ShellDestination.Conversations.route)
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
                AndroidSettings(
                    onboardingCompleted = true,
                    lastActiveConversationId = "conversation-1",
                ),
            ),
        ).isEqualTo(ChatDestination.createRoute("conversation-1"))
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
