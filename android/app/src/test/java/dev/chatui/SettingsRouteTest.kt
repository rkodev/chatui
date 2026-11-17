package dev.chatui

import android.app.Application
import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.core.app.ActivityOptionsCompat
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.core.model.Density
import dev.chatui.core.model.Theme
import dev.chatui.feature.backup.BackupSettingsTestTags
import dev.chatui.feature.conversations.CreateConversationResult
import dev.chatui.feature.conversations.export.ExportTestTags
import dev.chatui.feature.conversations.importer.ImportTestTags
import dev.chatui.feature.providers.ModelPickerTestTags
import dev.chatui.feature.settings.DataSettingsTestTags
import dev.chatui.feature.settings.SettingsRootScreen
import dev.chatui.feature.settings.SettingsTestTags
import dev.chatui.shell.ShellTestTags
import dev.chatui.testing.BaselineFixtures
import dev.chatui.testing.BaselineHarness
import dev.chatui.testing.BaselineShell
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
class SettingsRouteTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()
    private var harness: BaselineHarness? = null
    private var showContent by mutableStateOf(true)
    private val registry = DocumentRegistry()

    @After
    fun tearDown() {
        composeRule.runOnIdle {
            showContent = false
            composeRule.activity.viewModelStore.clear()
        }
        composeRule.waitForIdle()
        harness?.close()
    }

    @Test
    fun `settings root exposes every section and the provider entry point`() {
        var openedProviders = 0
        var openedAppearance = 0

        composeRule.setContent {
            ChatUiTheme {
                SettingsRootScreen(
                    onOpenAppearance = { openedAppearance += 1 },
                    onOpenChatDefaults = {},
                    onOpenProviders = { openedProviders += 1 },
                    onOpenData = {},
                    onOpenBackup = {},
                    onOpenAbout = {},
                )
            }
        }

        composeRule.onNodeWithTag(SettingsTestTags.Providers).performClick()
        composeRule.onNodeWithTag(SettingsTestTags.Appearance).performClick()

        assertThat(openedProviders).isEqualTo(1)
        assertThat(openedAppearance).isEqualTo(1)
    }

    @Test
    fun `production Defaults picker returns focus and changes only future conversations`() {
        val fixture = showSettings()
        val original = runBlocking { fixture.conversations.getConversation(BaselineFixtures.CONTENT_CHAT)!! }
        val history = runBlocking { fixture.messages.allForConversation(original.id) }
        composeRule.onNodeWithTag(SettingsTestTags.ChatDefaults).performClick()
        waitForTag(SettingsTestTags.DefaultModel)
        composeRule.onNodeWithTag(SettingsTestTags.DefaultModel).performClick()
        waitForTag(ModelPickerTestTags.model("baseline-vision"))
        composeRule.onNodeWithText("Default model for new chats").assertIsDisplayed()
        composeRule.onNodeWithTag(ModelPickerTestTags.model("baseline-hidden")).assertDoesNotExist()
        composeRule.onNodeWithTag(ModelPickerTestTags.model("baseline-vision")).performClick()
        waitForPickerClosed()
        waitForText(BaselineFixtures.models.first { it.id == "baseline-vision" }.label)
        assertModelEntryFocused()

        runBlocking {
            val settings = fixture.services.settings.current()
            assertThat(settings.defaultModelId).isEqualTo("baseline-vision")
            assertThat(settings.defaultProviderId).isEqualTo(BaselineFixtures.provider.id)
            val created = fixture.conversations.createConversation() as CreateConversationResult.Created
            assertThat(created.conversation.modelId).isEqualTo("baseline-vision")
            assertThat(created.conversation.params).isEqualTo(settings.defaultParams)
            assertThat(fixture.conversations.getConversation(original.id)).isEqualTo(original)
            assertThat(fixture.messages.allForConversation(original.id)).isEqualTo(history)
        }
    }

    @Test
    fun `cancelling and reopening Defaults preserves the persisted selection and parent`() {
        val fixture = showSettings()
        val defaults = runBlocking { fixture.services.settings.current() }
        composeRule.onNodeWithTag(SettingsTestTags.ChatDefaults).performClick()
        waitForTag(SettingsTestTags.DefaultModel)
        repeat(2) {
            composeRule.onNodeWithTag(SettingsTestTags.DefaultModel).performClick()
            waitForTag(ModelPickerTestTags.Sheet)
            composeRule.runOnIdle {
                (ShadowDialog.getShownDialogs().last { it.isShowing } as ComponentDialog)
                    .onBackPressedDispatcher.onBackPressed()
            }
            waitForPickerClosed()
            assertModelEntryFocused()
        }
        assertThat(runBlocking { fixture.services.settings.current() }).isEqualTo(defaults)
        composeRule.onNodeWithTag(SettingsTestTags.ChatDefaults).assertIsDisplayed()
    }

    @Test
    fun `appearance immediately updates the shell without a Save action`() {
        val fixture = showSettings()
        composeRule.onNodeWithTag(SettingsTestTags.Appearance).performClick()
        waitForTag(SettingsTestTags.theme("dark"))
        composeRule.onNodeWithTag(SettingsTestTags.theme("dark")).performClick()
        composeRule.onNodeWithTag(SettingsTestTags.density("compact")).performClick()
        waitForTag(ShellTestTags.appearance(Theme.DARK, Density.COMPACT))
        composeRule.onNodeWithTag(ShellTestTags.BackButton).performClick()
        waitForTag(SettingsTestTags.Root)
        runBlocking {
            assertThat(fixture.services.settings.current().theme).isEqualTo(Theme.DARK)
            assertThat(fixture.services.settings.current().density).isEqualTo(Density.COMPACT)
        }
    }

    @Test
    fun `toolbar Up returns from Configuration with unsaved edits and initiating focus intact`() {
        val fixture = showSettings()
        composeRule.onNodeWithTag(SettingsTestTags.Backup).performScrollTo().performClick()
        waitForTag(BackupSettingsTestTags.Configure, enabled = true)
        composeRule.onNodeWithTag(BackupSettingsTestTags.Configure).performScrollTo().performClick()
        composeRule.onNodeWithTag(BackupSettingsTestTags.Endpoint).performScrollTo()
            .performTextReplacement("https://edited.invalid")
        composeRule.onNodeWithTag(ShellTestTags.BackButton).performClick()
        composeRule.onNodeWithTag(BackupSettingsTestTags.Configure).assertIsFocused()
        composeRule.onNodeWithTag(BackupSettingsTestTags.Configuration).assertDoesNotExist()
        composeRule.onNodeWithTag(BackupSettingsTestTags.Configure).performClick()
        composeRule.onNodeWithTag(BackupSettingsTestTags.Endpoint).performScrollTo()
            .assertTextContains("https://edited.invalid")
        assertThat(runBlocking { fixture.services.backup.load().config }).isNull()
    }

    @Test
    fun `production document callbacks distinguish consent cancellation failure and completed IO`() {
        val fixture = showSettings()
        val original = runBlocking { fixture.messages.all() }
        composeRule.onNodeWithTag(SettingsTestTags.Data).performScrollTo().performClick()
        waitForTag(DataSettingsTestTags.ExportButton)

        composeRule.onNodeWithTag(DataSettingsTestTags.ExportButton).performScrollTo().performClick()
        composeRule.onNodeWithTag(ExportTestTags.PlaintextWarning).assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        assertThat(registry.launches).isEqualTo(0)
        composeRule.onNodeWithText("Export cancelled. No file was written.").assertIsDisplayed()

        startExport()
        composeRule.runOnIdle { registry.dispatchResult<Uri?>(registry.lastRequest, null) }
        composeRule.onNodeWithText("Export cancelled. No file was written.").assertIsDisplayed()
        startExport()
        composeRule.runOnIdle {
            registry.dispatchResult(
                registry.lastRequest,
                Uri.fromFile(fixture.root.resolve("missing").resolve("export.json")),
            )
        }
        waitForTag(DataSettingsTestTags.Error)
        composeRule.onNodeWithText("Export saved.").assertDoesNotExist()
        val archive = fixture.root.resolve("export-test.json")
        startExport()
        composeRule.runOnIdle { registry.dispatchResult(registry.lastRequest, Uri.fromFile(archive)) }
        waitForText("Export saved.")
        assertThat(archive.readText()).contains("chatui.export")

        composeRule.onNodeWithTag(DataSettingsTestTags.ImportMerge).performScrollTo().performClick()
        composeRule.runOnIdle { registry.dispatchResult<Uri?>(registry.lastRequest, null) }
        composeRule.onNodeWithText("Import cancelled. Local data was not changed.").assertIsDisplayed()
        composeRule.onNodeWithTag(DataSettingsTestTags.ImportMerge).performScrollTo().performClick()
        composeRule.runOnIdle {
            registry.dispatchResult(registry.lastRequest, Uri.fromFile(fixture.root.resolve("missing.json")))
        }
        waitForTag(DataSettingsTestTags.Error)
        assertThat(runBlocking { fixture.messages.all() }).isEqualTo(original)
        val invalid = fixture.root.resolve("invalid-test.json").apply { writeText("{}") }
        composeRule.onNodeWithTag(DataSettingsTestTags.ImportMerge).performScrollTo().performClick()
        composeRule.runOnIdle { registry.dispatchResult(registry.lastRequest, Uri.fromFile(invalid)) }
        waitForTag(DataSettingsTestTags.Error)
        composeRule.onNodeWithText("Import merged with local data.").assertDoesNotExist()
        assertThat(runBlocking { fixture.messages.all() }).isEqualTo(original)

        composeRule.onNodeWithTag(DataSettingsTestTags.ImportMerge).performScrollTo().performClick()
        composeRule.runOnIdle { registry.dispatchResult(registry.lastRequest, Uri.fromFile(archive)) }
        waitForText("Import merged with local data.")
        val launches = registry.launches
        composeRule.onNodeWithTag(DataSettingsTestTags.ImportReplace).performScrollTo().performClick()
        composeRule.onNodeWithTag(ImportTestTags.ReplaceConfirmation).assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        assertThat(registry.launches).isEqualTo(launches)
        assertThat(runBlocking { fixture.messages.all() }).isEqualTo(original)
        composeRule.onNodeWithTag(DataSettingsTestTags.BackupButton).performScrollTo().performClick()
        waitForTag(BackupSettingsTestTags.Configure)
    }

    @Test
    fun `a missing document picker produces one persistent error and can be retried`() {
        showSettings()
        composeRule.onNodeWithTag(SettingsTestTags.Data).performScrollTo().performClick()
        waitForTag(DataSettingsTestTags.ImportMerge)
        registry.failLaunch = true
        composeRule.onNodeWithTag(DataSettingsTestTags.ImportMerge).performScrollTo().performClick()
        waitForTag(DataSettingsTestTags.Error)
        composeRule.onNodeWithText("No document picker installed.").assertIsDisplayed()
        registry.failLaunch = false
        composeRule.onNodeWithTag(DataSettingsTestTags.ImportMerge).performScrollTo().performClick()
        composeRule.onNodeWithTag(DataSettingsTestTags.Error).assertDoesNotExist()
        composeRule.runOnIdle { registry.dispatchResult<Uri?>(registry.lastRequest, null) }
        composeRule.onNodeWithText("Import cancelled. Local data was not changed.").assertIsDisplayed()
    }

    private fun showSettings(): BaselineHarness {
        val fixture = runBlocking { BaselineHarness.create(composeRule.activity) }
        harness = fixture
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }
        composeRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                if (showContent) BaselineShell(fixture, theme = null, densityMode = null)
            }
        }
        waitForTag(ShellTestTags.MoreButton)
        composeRule.onNodeWithTag(ShellTestTags.MoreButton).performClick()
        composeRule.onNodeWithText("App settings").performClick()
        waitForTag(SettingsTestTags.Root)
        return fixture
    }

    private fun startExport() {
        val launches = registry.launches
        composeRule.onNodeWithTag(DataSettingsTestTags.ExportButton).performScrollTo().performClick()
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.waitForIdle()
            registry.launches > launches
        }
    }

    private fun waitForTag(tag: String, enabled: Boolean = false) {
        val matcher = if (enabled) hasTestTag(tag) and isEnabled() else hasTestTag(tag)
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForPickerClosed() {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag(ModelPickerTestTags.Sheet).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun assertModelEntryFocused() {
        composeRule.onNode(hasAnyAncestor(hasTestTag(SettingsTestTags.DefaultModel)) and isFocused())
            .assertExists()
    }

    private class DocumentRegistry : ActivityResultRegistry() {
        var launches = 0
        var lastRequest = 0
        var failLaunch = false
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            if (failLaunch) throw ActivityNotFoundException("No document picker installed.")
            launches++
            lastRequest = requestCode
        }
    }
}
