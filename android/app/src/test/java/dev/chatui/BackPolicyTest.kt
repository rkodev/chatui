package dev.chatui

import com.google.common.truth.Truth.assertThat
import dev.chatui.shell.BackLayer
import dev.chatui.shell.BackState
import dev.chatui.shell.appHandlesBack
import dev.chatui.shell.topmostBackLayer
import org.junit.Test

class BackPolicyTest {
    @Test
    fun `dialog is the topmost layer`() {
        assertThat(
            topmostBackLayer(
                BackState(
                    dialogOpen = true,
                    sheetOpen = true,
                    drawerOpen = true,
                    keyboardOpen = true,
                ),
            ),
        ).isEqualTo(BackLayer.Dialog)
    }

    @Test
    fun `sheet closes before the drawer`() {
        assertThat(
            topmostBackLayer(
                BackState(sheetOpen = true, drawerOpen = true, keyboardOpen = true),
            ),
        ).isEqualTo(BackLayer.Sheet)
    }

    @Test
    fun `drawer closes before the keyboard and navigation`() {
        assertThat(
            topmostBackLayer(BackState(drawerOpen = true, keyboardOpen = true)),
        ).isEqualTo(BackLayer.Drawer)
    }

    @Test
    fun `keyboard is left to the system`() {
        assertThat(topmostBackLayer(BackState(keyboardOpen = true)))
            .isEqualTo(BackLayer.Keyboard)
        assertThat(appHandlesBack(BackState(keyboardOpen = true))).isFalse()
    }

    @Test
    fun `navigation is the final fallback`() {
        assertThat(topmostBackLayer(BackState())).isEqualTo(BackLayer.Navigation)
        assertThat(appHandlesBack(BackState())).isFalse()
    }

    @Test
    fun `app handles dialogs sheets and the drawer`() {
        assertThat(appHandlesBack(BackState(dialogOpen = true))).isTrue()
        assertThat(appHandlesBack(BackState(sheetOpen = true))).isTrue()
        assertThat(appHandlesBack(BackState(drawerOpen = true))).isTrue()
    }
}
