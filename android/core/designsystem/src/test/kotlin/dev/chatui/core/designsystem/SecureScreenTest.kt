package dev.chatui.core.designsystem

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecureScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `adds flag secure while composed and removes it on dispose`() {
        var secure by mutableStateOf(true)
        lateinit var window: Window

        composeRule.setContent {
            window = LocalView.current.context.findActivity().window
            if (secure) {
                SecureScreen { Text("secret") }
            }
        }
        composeRule.waitForIdle()

        assertThat(window.isSecure()).isTrue()

        secure = false
        composeRule.waitForIdle()

        assertThat(window.isSecure()).isFalse()
    }

    @Test
    fun `does not clear a flag that the host already set`() {
        var secure by mutableStateOf(true)
        lateinit var window: Window

        composeRule.setContent {
            window = LocalView.current.context.findActivity().window
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            if (secure) {
                SecureScreen { Text("secret") }
            }
        }
        composeRule.waitForIdle()

        secure = false
        composeRule.waitForIdle()

        assertThat(window.isSecure()).isTrue()
    }

    private fun Window.isSecure(): Boolean =
        attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    private tailrec fun Context.findActivity(): Activity = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> error("No activity in context chain")
    }
}
