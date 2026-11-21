package dev.chatui.feature.providers

import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.designsystem.ChatUiTheme
import org.junit.Rule
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderRoutesTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val originalFontScale = RuntimeEnvironment.getApplication().resources.configuration.fontScale

    @After
    fun restoreFontScale() {
        RuntimeEnvironment.setFontScale(originalFontScale)
    }

    @Test
    fun `list route renders the provider list`() {
        composeRule.setContent {
            ChatUiTheme {
                ProviderListRoute(
                    service = FakeProviderManagementService(),
                    onOpenProvider = {},
                )
            }
        }

        composeRule.onNodeWithTag(ProviderListTestTags.Screen).assertIsDisplayed()
        composeRule.onNodeWithTag(ProviderListTestTags.provider("provider-1"))
            .assertIsDisplayed()
    }

    @Test
    fun `detail route renders the selected provider`() {
        composeRule.setContent {
            ChatUiTheme {
                ProviderDetailRoute(
                    service = FakeProviderManagementService(),
                    providerId = "provider-1",
                )
            }
        }

        composeRule.onNodeWithTag(ProviderDetailTestTags.Screen).assertIsDisplayed()
        composeRule.onNodeWithText("OpenRouter").assertIsDisplayed()
    }

    @Test
    fun `provider Models entry recovers hidden models without changing selections`() {
        val service = FakeProviderManagementService()
        service.models += testModel(id = "hidden", label = "Hidden model", hidden = true)
        val defaults = service.defaults
        val conversation = service.conversations["conversation-1"]
        var managementProvider by mutableStateOf<String?>(null)
        var reopenPicker by mutableStateOf(false)

        composeRule.setContent {
            ChatUiTheme {
                when {
                    reopenPicker -> {
                        key("reopened-picker") {
                            val picker = androidx.lifecycle.viewmodel.compose.viewModel<ModelPickerViewModel>(
                                key = "reopened-picker",
                                factory = ModelPickerViewModel.factory(
                                    service,
                                    ModelPickerTarget.Conversation("conversation-1"),
                                ),
                            )
                            val state by picker.state.collectAsStateWithLifecycle()
                            ModelPickerContent(
                                state = state,
                                onQueryChanged = picker::onQueryChanged,
                                onSelect = picker::onSelect,
                                onPin = picker::onPin,
                                onHide = picker::onHide,
                                onManualRemoteIdChanged = picker::onManualRemoteIdChanged,
                                onManualLabelChanged = picker::onManualLabelChanged,
                                onAddManual = picker::onAddManual,
                                onRefresh = picker::onRefresh,
                            )
                        }
                    }
                    managementProvider != null -> ManageModelsRoute(
                        service = service,
                        providerId = managementProvider!!,
                    )
                    else -> ProviderDetailRoute(
                        service = service,
                        providerId = "provider-1",
                        onOpenModels = { managementProvider = it },
                    )
                }
            }
        }

        composeRule.onNodeWithTag(ProviderDetailTestTags.ManageModels).performScrollTo().performClick()
        composeRule.onNodeWithTag(ManageModelsTestTags.Screen).assertIsDisplayed()
        composeRule.onNodeWithTag(ModelPickerTestTags.Content)
            .performScrollToNode(hasTestTag(ModelPickerTestTags.menu("hidden")))
        composeRule.onNodeWithTag(ModelPickerTestTags.model("hidden")).performClick()
        composeRule.onNodeWithTag(ModelPickerTestTags.menu("hidden")).performClick()
        composeRule.onNodeWithTag(ModelPickerTestTags.hide("hidden")).performClick()
        composeRule.onNodeWithTag(ModelPickerTestTags.Content)
            .performScrollToNode(hasTestTag(ModelPickerTestTags.ManualToggle))
        composeRule.onNodeWithTag(ModelPickerTestTags.ManualToggle).performClick()
        composeRule.onNodeWithTag(ModelPickerTestTags.Content)
            .performScrollToNode(hasTestTag(ModelPickerTestTags.ManualId))
        composeRule.onNodeWithTag(ModelPickerTestTags.ManualId).performTextInput("future/manual")
        composeRule.onNodeWithTag(ModelPickerTestTags.Content)
            .performScrollToNode(hasTestTag(ModelPickerTestTags.AddManual))
        composeRule.onNodeWithTag(ModelPickerTestTags.AddManual).performClick()
        composeRule.runOnIdle { managementProvider = null }
        composeRule.onNodeWithTag(ProviderDetailTestTags.ModelCount)
            .assertTextContains("3 models are cached for this provider.")
        composeRule.onNodeWithTag(ProviderDetailTestTags.ManageModels).assertIsFocused()
        composeRule.runOnIdle { reopenPicker = true }
        composeRule.onNodeWithTag(ModelPickerTestTags.Content)
            .performScrollToNode(hasTestTag(ModelPickerTestTags.model("hidden")))
        composeRule.onNodeWithTag(ModelPickerTestTags.model("hidden")).assertIsDisplayed()
        composeRule.runOnIdle {
            assertThat(service.models.first { it.id == "hidden" }.hidden).isFalse()
            assertThat(service.defaults).isEqualTo(defaults)
            assertThat(service.conversations["conversation-1"]).isEqualTo(conversation)
            assertThat(service.lastSelectedModelId).isNull()
        }
    }

    @Test
    fun `successful provider creation closes the form and keeps its profile reachable`() {
        val service = FakeProviderManagementService()
        composeRule.setContent {
            ChatUiTheme {
                ProviderListRoute(service = service, onOpenProvider = {})
            }
        }
        composeRule.onNodeWithTag(ProviderListTestTags.AddProvider).performClick()
        composeRule.onNodeWithTag(ProviderListTestTags.providerOption("openai")).performClick()
        composeRule.onNodeWithTag(ProviderListTestTags.PresetApiKey).performTextInput("fake-key")
        composeRule.onNodeWithTag(ProviderListTestTags.PresetCreate).performScrollTo().performClick()

        composeRule.onNodeWithTag(ProviderListTestTags.AddSheet).assertDoesNotExist()
        composeRule.onNodeWithTag(ProviderListTestTags.provider("provider-2")).assertIsDisplayed()
        composeRule.onNodeWithText("OpenAI added.").assertIsDisplayed()
        composeRule.runOnIdle { assertThat(service.presetCreateCalls).isEqualTo(1) }
    }

    @Test
    fun `settings Model opens its conversation picker and refreshes persisted context on return`() {
        val service = FakeProviderManagementService()
        service.models += testModel(id = "model-2", label = "Second model")
        service.conversations["conversation-1"] = testConversation(
            params = testChatParams().copy(temperature = 0.4),
        ).copy(systemPrompt = "Retain prompt")
        var target: String? = null
        composeRule.setContent {
            ChatUiTheme {
                ConversationSettingsRoute(
                    service = service,
                    conversationId = "conversation-1",
                    onDismiss = {},
                    onDeleted = {},
                    onOpenModelPicker = { id, onReturned ->
                        target = id
                        service.conversations[id] = service.conversations.getValue(id)
                            .copy(modelId = "model-2", modelLabel = "Second model")
                        onReturned()
                    },
                )
            }
        }

        composeRule.onNodeWithTag(ConversationSettingsTestTags.Model).performClick()
        composeRule.onNodeWithText("Second model").assertIsDisplayed()
        composeRule.onNodeWithTag(ConversationSettingsTestTags.Model).assertIsFocused()
        composeRule.onNodeWithText("Retain prompt").assertExists()
        composeRule.onNodeWithText("Temperature 0.4").assertExists()
        assertThat(target).isEqualTo("conversation-1")
        assertThat(service.defaults.modelId).isEqualTo("model-1")
    }

    @Test
    fun `disclosed generation reset and raw extras share persisted routing controls`() {
        val service = FakeProviderManagementService()
        composeRule.setContent {
            ChatUiTheme {
                ConversationSettingsRoute(service, "conversation-1", onDismiss = {}, onDeleted = {})
            }
        }
        composeRule.onNodeWithTag(ConversationSettingsTestTags.Temperature).assertDoesNotExist()
        composeRule.onNodeWithTag(ConversationSettingsTestTags.Extras).assertDoesNotExist()
        composeRule.onNodeWithTag(ConversationSettingsTestTags.Generation).performScrollTo().performClick()
        composeRule.onNodeWithTag(ConversationSettingsTestTags.Temperature)
            .performScrollTo().performTextReplacement("0")
        composeRule.runOnIdle {
            assertThat(service.conversations.getValue("conversation-1").params.temperature).isEqualTo(0.0)
        }
        composeRule.onNodeWithTag(ConversationSettingsTestTags.reset("temperature"))
            .performScrollTo().performClick()
        composeRule.runOnIdle {
            assertThat(service.conversations.getValue("conversation-1").params.temperature).isNull()
        }
        composeRule.onNodeWithTag(ConversationSettingsTestTags.Advanced).performScrollTo().performClick()
        composeRule.onNodeWithTag(ConversationSettingsTestTags.Extras)
            .performScrollTo().performTextReplacement("""{"unknown":"retain"}""")
        composeRule.onNodeWithTag(ConversationRoutingTestTags.Enabled).performScrollTo().performClick()
        composeRule.onNodeWithTag(ConversationRoutingTestTags.DenyDataCollection)
            .performScrollTo().performClick()
        composeRule.runOnIdle {
            val params = service.conversations.getValue("conversation-1").params
            assertThat(params.extra["openrouter"].toString()).contains("retain")
            assertThat(readRoutingPreferences(params).denyDataCollection).isTrue()
        }
        composeRule.onNodeWithTag(ConversationSettingsTestTags.Advanced).performScrollTo().performClick()
        composeRule.onNodeWithTag(ConversationSettingsTestTags.Extras).assertDoesNotExist()
        composeRule.onNodeWithText("2 extra JSON field(s) · Routing: Fallbacks on, data collection denied")
            .assertExists()
    }

    @Test
    fun `non OpenRouter settings keep raw extras without curated routing`() {
        val service = FakeProviderManagementService()
        service.providersFlow.value = listOf(testProvider(presetId = "custom"))
        composeRule.setContent {
            ChatUiTheme {
                ConversationSettingsRoute(service, "conversation-1", onDismiss = {}, onDeleted = {})
            }
        }
        composeRule.onNodeWithTag(ConversationSettingsTestTags.Advanced).performScrollTo().performClick()
        composeRule.onNodeWithTag(ConversationSettingsTestTags.Extras).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(ConversationRoutingTestTags.Enabled).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp")
    fun `large font settings keep defaults advanced routing and delete reachable in a short viewport`() {
        RuntimeEnvironment.setFontScale(2f)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                ChatUiTheme {
                    ConversationSettingsRoute(
                        FakeProviderManagementService(), "conversation-1",
                        onDismiss = {}, onDeleted = {},
                        modifier = Modifier.heightIn(max = 344.dp),
                    )
                }
            }
        }
        composeRule.onNodeWithTag(ConversationSettingsTestTags.Generation).performScrollTo().performClick()
        composeRule.onNodeWithTag(ConversationSettingsTestTags.reset("max-output")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(ConversationSettingsTestTags.reasoning("high")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(ConversationSettingsTestTags.Advanced).performScrollTo().performClick()
        composeRule.onNodeWithTag(ConversationRoutingTestTags.Enabled).performScrollTo().performClick()
        composeRule.onNodeWithTag(ConversationRoutingTestTags.sort("throughput"))
            .performScrollTo().assertIsDisplayed()
        val bounds = composeRule.onNodeWithTag(ConversationRoutingTestTags.sort("throughput"))
            .fetchSemanticsNode().boundsInRoot
        assertThat(bounds.left).isAtLeast(0f)
        assertThat(bounds.right).isAtMost(320f)
        composeRule.onNodeWithTag(ConversationSettingsTestTags.Delete).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `delete cancellation preserves the conversation and confirmation alone removes it`() {
        val service = FakeProviderManagementService()
        var deleted = 0
        composeRule.setContent {
            ChatUiTheme {
                ConversationSettingsRoute(service, "conversation-1", onDismiss = {}, onDeleted = { deleted++ })
            }
        }
        composeRule.onNodeWithTag(ConversationSettingsTestTags.Delete).performScrollTo().performClick()
        composeRule.onNodeWithText("This permanently removes the conversation and its messages.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        assertThat(service.conversations).containsKey("conversation-1")
        composeRule.onNodeWithTag(ConversationSettingsTestTags.Delete).performScrollTo().performClick()
        composeRule.onNodeWithTag(ConversationSettingsTestTags.ConfirmDelete).performClick()
        composeRule.runOnIdle {
            assertThat(deleted).isEqualTo(1)
            assertThat(service.conversations).doesNotContainKey("conversation-1")
        }
    }
}
