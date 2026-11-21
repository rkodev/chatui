package dev.chatui.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the startup baseline profile for the release app.
 *
 * The profile covers the cold-start path (application, activity, Compose shell,
 * settings load, and startup recovery), seeded history, a local draft, and
 * More > Keyboard shortcuts. Seed the acknowledged disposable install with
 * BaselineSeedTest before installing the matching benchmark build; onboarding
 * or a missing control is a failed journey, never a successful partial profile.
 */
@RunWith(AndroidJUnit4::class)
class StartupBaselineProfile {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Before
    fun requireFixtureAcknowledgement() {
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("baselineDisposable") == "true") {
            "Profile only an explicitly acknowledged disposable fixture install."
        }
        check(arguments.getString("baselineFixture") == "android-minimalist-ux-00.2-v1") {
            "Seed and verify android-minimalist-ux-00.2-v1 outside profile collection."
        }
    }

    @Test
    fun startupAndShell() {
        baselineProfileRule.collect(
            packageName = "dev.chatui",
            includeInStartupProfile = true,
        ) {
            pressHome()
            startActivityAndWait()
            fun required(selector: BySelector, surface: String): UiObject2 =
                checkNotNull(device.wait(Until.findObject(selector), 10_000)) {
                    "Seeded profile journey did not reach $surface."
                }

            required(By.res("chat-composer-input"), "the seeded chat composer")
            required(By.res("chat-message-list"), "the local transcript")
            if (device.hasObject(By.res("shell-expanded-layout"))) {
                required(By.res("shell-sidebar"), "permanent history")
            } else {
                required(By.desc("Open navigation"), "the history button").click()
            }
            required(By.res("conversation-search-field"), "history search").text = "10,000 synthetic messages"
            required(
                By.res("conversation-search-result-019a5520-0000-7000-8000-000000000006"),
                "the seeded long-history conversation",
            ).click()

            val composer = required(By.res("chat-composer-input"), "the reopened composer")
            composer.click()
            composer.text = "Invented local profile draft"
            check(composer.text == "Invented local profile draft") { "Draft editing did not complete." }
            composer.text = ""
            device.pressBack()
            required(By.res("chat-composer-input"), "chat after keyboard dismissal")
            required(By.desc("More"), "the contextual More menu").click()
            required(By.text("Keyboard shortcuts"), "the keyboard-help menu item").click()
            required(By.text("Keyboard shortcuts"), "keyboard help")
            required(By.text("Close"), "the keyboard-help close action").click()
            required(By.res("chat-composer-input"), "chat after closing keyboard help")
        }
    }
}
