package dev.chatui.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the startup baseline profile for the release app.
 *
 * The profile covers the cold-start path (application, activity, Compose shell,
 * settings load, and startup recovery) plus the two shell surfaces a user can
 * reach immediately: the compact-layout drawer and the keyboard-shortcuts
 * dialog. Both interactions are optional so the rule still produces a profile
 * when onboarding is shown on a fresh install.
 */
@RunWith(AndroidJUnit4::class)
class StartupBaselineProfile {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startupAndShell() {
        baselineProfileRule.collect(
            packageName = "dev.chatui",
            includeInStartupProfile = true,
        ) {
            pressHome()
            startActivityAndWait()
            device.waitForIdle()

            // Compact layout: open the navigation drawer, then close it.
            device.findObject(By.text("☰"))?.let { menu ->
                menu.click()
                device.waitForIdle()
                device.pressBack()
                device.waitForIdle()
            }

            // Open and dismiss the keyboard-shortcuts dialog.
            device.findObject(By.desc("Keyboard shortcuts"))?.let { shortcuts ->
                shortcuts.click()
                device.waitForIdle()
                device.pressBack()
                device.waitForIdle()
            }
        }
    }
}
