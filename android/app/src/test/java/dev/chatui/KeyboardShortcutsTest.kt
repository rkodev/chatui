package dev.chatui

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.withKeyDown
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.core.model.Conversation
import dev.chatui.feature.chat.ChatShortcutState
import dev.chatui.shell.AndroidShortcutHelp
import dev.chatui.shell.ShellDestination
import dev.chatui.shell.ShellScaffold
import dev.chatui.shell.ShellShortcutActions
import dev.chatui.shell.ShellTestTags
import dev.chatui.shell.ShortcutAction
import dev.chatui.shell.ShortcutHelpTestTags
import dev.chatui.shell.ShortcutModifiers
import dev.chatui.shell.shortcutFor
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class KeyboardShortcutsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController

    @Test
    fun `ctrl k invokes search`() {
        val recording = RecordingShortcutActions()
        setShell(actions = recording.actions)

        pressCtrl(Key.K)

        assertThat(recording.search).isEqualTo(1)
    }

    @Test
    fun `ctrl shift o invokes new chat`() {
        val recording = RecordingShortcutActions()
        setShell(actions = recording.actions)

        pressCtrlShift(Key.O)

        assertThat(recording.newChat).isEqualTo(1)
    }

    @Test
    fun `ctrl shift c invokes copy last response`() {
        val recording = RecordingShortcutActions()
        setShell(actions = recording.actions)

        pressCtrlShift(Key.C)

        assertThat(recording.copy).isEqualTo(1)
    }

    @Test
    fun `alt up and down invoke conversation navigation`() {
        val recording = RecordingShortcutActions()
        setShell(actions = recording.actions)

        pressAlt(Key.DirectionUp)
        pressAlt(Key.DirectionDown)

        assertThat(recording.previous).isEqualTo(1)
        assertThat(recording.next).isEqualTo(1)
    }

    @Test
    fun `escape invokes stop or close`() {
        val recording = RecordingShortcutActions()
        setShell(actions = recording.actions)

        composeRule.onNodeWithTag(ShellTestTags.Root).performKeyInput {
            pressKey(Key.Escape)
        }

        assertThat(recording.stopOrClose).isEqualTo(1)
    }

    @Test
    fun `shortcuts are ignored while an overlay is open`() {
        val recording = RecordingShortcutActions(overlay = true)
        setShell(actions = recording.actions)

        pressCtrl(Key.K)

        assertThat(recording.search).isEqualTo(0)
    }

    @Test
    fun `an open overlay blocks every shortcut`() {
        val recording = RecordingShortcutActions(overlay = true)
        setShell(actions = recording.actions)

        pressCtrl(Key.K)
        pressCtrlShift(Key.O)
        pressCtrlShift(Key.C)
        pressAlt(Key.DirectionUp)
        pressAlt(Key.DirectionDown)
        composeRule.onNodeWithTag(ShellTestTags.Root).performKeyInput {
            pressKey(Key.Escape)
        }

        assertThat(recording.search).isEqualTo(0)
        assertThat(recording.newChat).isEqualTo(0)
        assertThat(recording.copy).isEqualTo(0)
        assertThat(recording.previous).isEqualTo(0)
        assertThat(recording.next).isEqualTo(0)
        assertThat(recording.stopOrClose).isEqualTo(0)
    }

    @Test
    fun `text entry keys are never shell shortcuts`() {
        val plain = ShortcutModifiers()

        assertThat(shortcutFor(Key.Backspace, plain)).isNull()
        assertThat(shortcutFor(Key.Spacebar, plain)).isNull()
        assertThat(shortcutFor(Key.O, plain)).isNull()
        assertThat(shortcutFor(Key.C, plain)).isNull()
        assertThat(shortcutFor(Key.K, ShortcutModifiers(shift = true))).isNull()
        assertThat(shortcutFor(Key.Enter, ShortcutModifiers(ctrl = true))).isNull()
    }

    @Test
    fun `escape stops an active stream before closing the drawer`() {
        val bridge = ChatShortcutState()
        var stops = 0
        bridge.update(
            streaming = true,
            overlayOpen = false,
            onStop = { stops += 1 },
            onCopyLastResponse = null,
        )
        setShell(chatShortcutState = bridge)

        composeRule.onNodeWithTag(ShellTestTags.MenuButton).performClick()
        composeRule.onNodeWithTag(ShellTestTags.Sidebar).assertIsDisplayed()

        composeRule.onNodeWithTag(ShellTestTags.Root).performKeyInput {
            pressKey(Key.Escape)
        }

        assertThat(stops).isEqualTo(1)
        composeRule.onNodeWithTag(ShellTestTags.Sidebar).assertIsDisplayed()
    }

    @Test
    fun `escape closes the drawer when no stream is active`() {
        setShell()

        composeRule.onNodeWithTag(ShellTestTags.MenuButton).performClick()
        composeRule.onNodeWithTag(ShellTestTags.Sidebar).assertIsDisplayed()

        composeRule.onNodeWithTag(ShellTestTags.Root).performKeyInput {
            pressKey(Key.Escape)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(ShellTestTags.Sidebar).assertIsNotDisplayed()
    }

    @Test
    fun `help button opens the shortcut map`() {
        setShell()

        composeRule.onNodeWithTag(ShellTestTags.ShortcutHelpButton).performClick()

        composeRule.onNodeWithTag(ShortcutHelpTestTags.Dialog).assertIsDisplayed()
        composeRule.onNodeWithTag(ShortcutHelpTestTags.row("Ctrl + K")).assertIsDisplayed()
    }

    @Test
    fun `plain character keys are not shortcuts`() {
        assertThat(shortcutFor(Key.K, ShortcutModifiers())).isNull()
        assertThat(shortcutFor(Key.Enter, ShortcutModifiers())).isNull()
        assertThat(shortcutFor(Key.DirectionUp, ShortcutModifiers())).isNull()
        assertThat(shortcutFor(Key.Escape, ShortcutModifiers(ctrl = true))).isNull()
    }

    @Test
    fun `shortcut map covers the documented actions`() {
        assertThat(AndroidShortcutHelp.map { it.keys }).containsExactly(
            "Ctrl + K",
            "Ctrl + Shift + O",
            "Esc",
            "Ctrl + Shift + C",
            "Alt + Up / Down",
            "Ctrl + Enter",
        )
        assertThat(shortcutFor(Key.K, ShortcutModifiers(ctrl = true)))
            .isEqualTo(ShortcutAction.Search)
        assertThat(shortcutFor(Key.O, ShortcutModifiers(ctrl = true, shift = true)))
            .isEqualTo(ShortcutAction.NewChat)
        assertThat(shortcutFor(Key.C, ShortcutModifiers(ctrl = true, shift = true)))
            .isEqualTo(ShortcutAction.CopyLastResponse)
        assertThat(shortcutFor(Key.Escape, ShortcutModifiers()))
            .isEqualTo(ShortcutAction.StopOrClose)
        assertThat(shortcutFor(Key.DirectionUp, ShortcutModifiers(alt = true)))
            .isEqualTo(ShortcutAction.PreviousConversation)
        assertThat(shortcutFor(Key.DirectionDown, ShortcutModifiers(alt = true)))
            .isEqualTo(ShortcutAction.NextConversation)
    }

    private fun setShell(
        actions: ShellShortcutActions? = null,
        chatShortcutState: ChatShortcutState? = null,
        conversations: List<Conversation> = emptyList(),
    ) {
        composeRule.setContent {
            ChatUiTheme {
                navController = rememberNavController()
                ShellScaffold(
                    navController = navController,
                    startDestination = ShellDestination.Conversations.route,
                    conversationService = FakeConversationService(conversations),
                    chatShortcutState = chatShortcutState,
                    shortcutActions = actions,
                )
            }
        }
        composeRule.onNodeWithTag(ShellTestTags.Root).requestFocus()
        composeRule.onNodeWithTag(ShellTestTags.Root).assertIsFocused()
    }

    private fun pressCtrl(key: Key) {
        composeRule.onNodeWithTag(ShellTestTags.Root).performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(key) }
        }
    }

    private fun pressCtrlShift(key: Key) {
        composeRule.onNodeWithTag(ShellTestTags.Root).performKeyInput {
            withKeyDown(Key.CtrlLeft) {
                withKeyDown(Key.ShiftLeft) { pressKey(key) }
            }
        }
    }

    private fun pressAlt(key: Key) {
        composeRule.onNodeWithTag(ShellTestTags.Root).performKeyInput {
            withKeyDown(Key.AltLeft) { pressKey(key) }
        }
    }
}

private class RecordingShortcutActions(overlay: Boolean = false) {
    var search = 0
    var newChat = 0
    var stopOrClose = 0
    var copy = 0
    var previous = 0
    var next = 0

    val actions = ShellShortcutActions(
        overlayOpen = { overlay },
        onSearch = { search += 1 },
        onNewChat = { newChat += 1 },
        onStopOrClose = {
            stopOrClose += 1
            true
        },
        onCopyLastResponse = {
            copy += 1
            true
        },
        onPreviousConversation = {
            previous += 1
            true
        },
        onNextConversation = {
            next += 1
            true
        },
    )
}
