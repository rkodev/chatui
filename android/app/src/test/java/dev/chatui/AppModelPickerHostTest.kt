package dev.chatui

import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.rememberNavController
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
import dev.chatui.core.model.Conversation
import dev.chatui.feature.chat.ChatScreenTestTags
import dev.chatui.feature.chat.ChatTopBarState
import dev.chatui.feature.providers.ModelDefaults
import dev.chatui.feature.providers.ConversationSettingsRoute
import dev.chatui.feature.providers.ConversationSettingsTestTags
import dev.chatui.feature.providers.ModelPickerTestTags
import dev.chatui.feature.providers.ProviderManagementService
import dev.chatui.shell.AppModelPickerState
import dev.chatui.shell.AppModelPickerHost
import dev.chatui.shell.ShellDestination
import dev.chatui.shell.ShellScaffold
import dev.chatui.testing.BaselineFixtures
import java.lang.reflect.Proxy
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class AppModelPickerHostTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val host = AppModelPickerState()
    private val records = mutableMapOf(
        "first" to fakeConversation("first", "First"),
        "second" to fakeConversation("second", "Second"),
    )
    private val models = listOf(
        BaselineFixtures.models.first().copy(
            id = "model-1", providerId = "provider-1", label = "Model One",
        ),
        BaselineFixtures.models.first().copy(
            id = "model-2", providerId = "provider-1", label = "Model Two", remoteId = "local/two",
        ),
    )
    private var defaults = ModelDefaults("provider-1", "model-1")
    private var failSelection = false
    private var refreshed = 0
    private val header = ChatTopBarState()

    private val service = Proxy.newProxyInstance(
        ProviderManagementService::class.java.classLoader,
        arrayOf(ProviderManagementService::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getConversation" -> records[args!![0]]
            "listModels" -> models
            "listProviders" -> listOf(BaselineFixtures.provider.copy(id = "provider-1"))
            "getDefaults" -> defaults
            "getDefaultModelWarning" -> null
            "setDefaults" -> {
                defaults = ModelDefaults(args!![0] as String?, args[1] as String?)
                defaults
            }
            "setConversationModel" -> {
                check(!failSelection) { "Selection failed. Try again." }
                val id = args!![0] as String
                val model = models.first { it.id == args[1] }
                records.getValue(id).copy(modelId = model.id, modelLabel = model.label)
                    .also { records[id] = it }
            }
            "toString" -> "Local picker fake"
            "hashCode" -> System.identityHashCode(this)
            "equals" -> false
            else -> error("Unexpected picker service call: ${method.name}")
        }
    } as ProviderManagementService

    @Test
    fun `header opens conversation picker and refreshes from the persisted result`() {
        showShell()
        composeRule.onNodeWithTag(ChatScreenTestTags.ModelChip).performClick()
        composeRule.onNodeWithTag(ModelPickerTestTags.Sheet).assertIsDisplayed()
        composeRule.onNodeWithText("Model for this conversation").assertIsDisplayed()
        composeRule.onNodeWithTag(ModelPickerTestTags.model("model-2")).performClick()

        composeRule.onNodeWithTag(ModelPickerTestTags.Sheet).assertDoesNotExist()
        composeRule.onNodeWithText("Model Two").assertIsDisplayed()
        assertThat(records.getValue("first").modelId).isEqualTo("model-2")
        assertThat(records.getValue("first").params).isEqualTo(fakeConversation().params)
        assertThat(defaults.modelId).isEqualTo("model-1")
        assertThat(refreshed).isEqualTo(1)
    }

    @Test
    fun `defaults and successive conversation requests cannot share a target`() {
        showShell()
        composeRule.runOnIdle { host.openDefaults() }
        composeRule.onNodeWithText("Default model for new chats").assertIsDisplayed()
        composeRule.onNodeWithTag(ModelPickerTestTags.model("model-2")).performClick()
        assertThat(defaults.modelId).isEqualTo("model-2")
        assertThat(records.values.map { it.modelId }).containsExactly("model-1", "model-1")

        composeRule.runOnIdle { host.openConversation("first") }
        composeRule.onNodeWithTag(ModelPickerTestTags.Sheet).assertIsDisplayed()
        composeRule.runOnIdle { host.openConversation("second") }
        composeRule.onNodeWithTag(ModelPickerTestTags.model("model-2")).performClick()
        assertThat(records.getValue("first").modelId).isEqualTo("model-1")
        assertThat(records.getValue("second").modelId).isEqualTo("model-2")
    }

    @Test
    fun `failed selection stays on its target and back dismisses only the sheet`() {
        failSelection = true
        showShell()
        composeRule.onNodeWithTag(ChatScreenTestTags.ModelChip).performClick()
        composeRule.onNodeWithTag(ModelPickerTestTags.model("model-2")).performClick()
        composeRule.onNodeWithText("Selection failed. Try again.").assertIsDisplayed()
        assertThat(records.getValue("first").modelId).isEqualTo("model-1")

        composeRule.runOnIdle {
            (ShadowDialog.getShownDialogs().last { it.isShowing } as ComponentDialog)
                .onBackPressedDispatcher.onBackPressed()
        }
        composeRule.onNodeWithTag(ModelPickerTestTags.Sheet).assertDoesNotExist()
        composeRule.onNodeWithTag(ChatScreenTestTags.ModelChip).assertIsDisplayed()
        assertThat(defaults.modelId).isEqualTo("model-1")
    }

    @Test
    fun `missing conversation is an explicit error and never changes defaults`() {
        showShell()
        composeRule.runOnIdle { host.openConversation("deleted") }
        composeRule.onNodeWithText("Conversation not found.").assertIsDisplayed()
        assertThat(defaults.modelId).isEqualTo("model-1")
    }

    @Test
    fun `a stale close cannot dismiss or refresh a replacement target`() {
        var firstClosed = 0
        var secondClosed = 0
        host.openConversation("first") { firstClosed++ }
        val first = host.request!!
        host.openConversation("second") { secondClosed++ }
        val second = host.request!!

        assertThat(host.close(first)).isFalse()
        assertThat(host.request).isSameInstanceAs(second)
        assertThat(firstClosed).isEqualTo(0)
        assertThat(secondClosed).isEqualTo(0)
        assertThat(host.close(second)).isTrue()
        assertThat(secondClosed).isEqualTo(1)
        assertThat(host.request).isNull()
    }

    @Test
    fun `settings picker returns persisted model and focus without dismissing its parent or restoring header focus`() {
        var showingSettings by mutableStateOf(true)
        records["first"] = records.getValue("first").copy(systemPrompt = "Retain prompt")
        composeRule.setContent {
            ChatUiTheme(reducedMotion = true) {
                Box {
                    if (showingSettings) {
                        ConversationSettingsRoute(
                            service, "first",
                            onDismiss = { showingSettings = false },
                            onDeleted = {},
                            onOpenModelPicker = host::openConversationSettings,
                        )
                    }
                    AppModelPickerHost(host, service = service, onClosed = { refreshed++ })
                }
            }
        }

        composeRule.onNodeWithTag(ConversationSettingsTestTags.Model).performClick()
        composeRule.onNodeWithTag(ModelPickerTestTags.Sheet).assertIsDisplayed()
        assertThat(host.request!!.restoreHeaderFocus).isFalse()
        composeRule.runOnIdle {
            (ShadowDialog.getShownDialogs().last { it.isShowing } as ComponentDialog)
                .onBackPressedDispatcher.onBackPressed()
        }
        composeRule.onNodeWithTag(ModelPickerTestTags.Sheet).assertDoesNotExist()
        composeRule.onNodeWithTag(ConversationSettingsTestTags.Sheet).assertIsDisplayed()
        composeRule.onNodeWithTag(ConversationSettingsTestTags.Model).assertIsFocused()
        assertThat(showingSettings).isTrue()

        composeRule.onNodeWithTag(ConversationSettingsTestTags.Model).performClick()
        composeRule.onNodeWithTag(ModelPickerTestTags.model("model-2")).performClick()
        composeRule.onNodeWithTag(ModelPickerTestTags.Sheet).assertDoesNotExist()
        composeRule.onNodeWithText("Model Two").assertIsDisplayed()
        composeRule.onNodeWithText("Retain prompt").assertExists()
        composeRule.onNodeWithTag(ConversationSettingsTestTags.Model).assertIsFocused()
        assertThat(records.getValue("first").modelId).isEqualTo("model-2")
        assertThat(defaults.modelId).isEqualTo("model-1")
        assertThat(refreshed).isEqualTo(0)
        composeRule.runOnIdle {
            (ShadowDialog.getShownDialogs().last { it.isShowing } as ComponentDialog)
                .onBackPressedDispatcher.onBackPressed()
        }
        composeRule.onNodeWithTag(ConversationSettingsTestTags.Sheet).assertDoesNotExist()
    }

    private fun showShell() {
        fun publish(conversation: Conversation) {
            header.update(
                conversation.title, conversation.modelLabel, null, null,
                {
                    host.openConversation(conversation.id) {
                        refreshed++
                        publish(records.getValue(conversation.id))
                    }
                },
            )
        }
        publish(records.getValue("first"))
        composeRule.setContent {
            ChatUiTheme(reducedMotion = true) {
                ShellScaffold(
                    navController = rememberNavController(),
                    startDestination = ShellDestination.Chat.route,
                    conversationService = FakeConversationService(),
                    chatTopBarState = header,
                    modelPickerState = host,
                    providerManagementService = service,
                )
            }
        }
    }
}
