package dev.chatui

import android.content.Context
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.createGraph
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.chatui.shell.ShellDestination
import dev.chatui.shell.shellDestinations
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShellNavigationTest {
    @Test
    fun `navigation graph resolves every shell destination`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val navController = NavHostController(context).apply {
            navigatorProvider.addNavigator(ComposeNavigator())
        }

        val graph = navController.createGraph(
            startDestination = ShellDestination.Conversations.route,
        ) {
            shellDestinations()
        }
        navController.graph = graph

        ShellDestination.entries.forEach { destination ->
            assertThat(navController.graph.findNode(destination.route)).isNotNull()
        }
        assertThat(navController.graph.startDestinationRoute)
            .isEqualTo(ShellDestination.Conversations.route)
    }
}
