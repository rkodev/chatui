package dev.chatui.feature.providers

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.model.ChatParams
import dev.chatui.core.model.ReasoningEffort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun service(): FakeProviderManagementService {
        val service = FakeProviderManagementService()
        service.models = mutableListOf(
            testModel(
                id = "model-1",
                providerId = "provider-1",
                label = "Model One",
                supportedParameters = listOf("temperature", "top_p", "max_tokens", "stop"),
            ),
            testModel(
                id = "model-2",
                providerId = "provider-1",
                label = "Model Two",
                supportedParameters = listOf("temperature"),
            ),
        )
        service.conversations["conversation-1"] = testConversation(
            params = testChatParams(
                extra = JsonObject(mapOf("openrouter" to JsonPrimitive("keep"))),
            ),
        )
        return service
    }

    @Test
    fun `loads the conversation and explains unsupported values`() = runTest(dispatcher) {
        val service = service()
        service.conversations["conversation-1"] = testConversation(
            params = testChatParams().copy(reasoningEffort = ReasoningEffort.HIGH),
        )
        val viewModel = ConversationSettingsViewModel(service, "conversation-1")

        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.loading).isFalse()
        assertThat(state.model?.id).isEqualTo("model-1")
        assertThat(state.unsupportedNote)
            .isEqualTo("This model ignores these values, so they are not sent: Reasoning effort.")
    }

    @Test
    fun `system prompt persists immediately`() = runTest(dispatcher) {
        val service = service()
        val viewModel = ConversationSettingsViewModel(service, "conversation-1")
        advanceUntilIdle()

        viewModel.onSystemPromptChanged("Be concise")
        advanceUntilIdle()

        assertThat(service.conversations["conversation-1"]!!.systemPrompt)
            .isEqualTo("Be concise")
    }

    @Test
    fun `parameters persist immediately`() = runTest(dispatcher) {
        val service = service()
        val viewModel = ConversationSettingsViewModel(service, "conversation-1")
        advanceUntilIdle()

        viewModel.onTemperatureChanged("0.4")
        advanceUntilIdle()
        viewModel.onTopPChanged("0.9")
        advanceUntilIdle()
        viewModel.onMaxOutputTokensChanged("256")
        advanceUntilIdle()
        viewModel.onStopChanged("END\nSTOP")
        advanceUntilIdle()
        viewModel.onReasoningEffortChanged(ReasoningEffort.HIGH)
        advanceUntilIdle()
        viewModel.onStreamingChanged(false)
        advanceUntilIdle()

        val stored = service.conversations["conversation-1"]!!.params
        assertThat(stored.temperature).isEqualTo(0.4)
        assertThat(stored.topP).isEqualTo(0.9)
        assertThat(stored.maxOutputTokens).isEqualTo(256)
        assertThat(stored.stop).containsExactly("END", "STOP").inOrder()
        assertThat(stored.reasoningEffort).isEqualTo(ReasoningEffort.HIGH)
        assertThat(stored.streaming).isFalse()
    }

    @Test
    fun `protected extras are rejected and not persisted`() = runTest(dispatcher) {
        val service = service()
        val viewModel = ConversationSettingsViewModel(service, "conversation-1")
        advanceUntilIdle()

        viewModel.onExtrasChanged("""{"model":"override"}""")
        advanceUntilIdle()

        assertThat(viewModel.state.value.extrasError)
            .isEqualTo("Provider extras cannot override protected fields: model.")
        assertThat(service.conversations["conversation-1"]!!.params.extra["openrouter"])
            .isEqualTo(JsonPrimitive("keep"))
    }

    @Test
    fun `valid extras persist under the provider namespace`() = runTest(dispatcher) {
        val service = service()
        val viewModel = ConversationSettingsViewModel(service, "conversation-1")
        advanceUntilIdle()

        viewModel.onExtrasChanged("""{"route":"fallback"}""")
        advanceUntilIdle()

        val extra = service.conversations["conversation-1"]!!.params.extra
        assertThat(extra["openrouter"]!!.jsonObject["route"]!!.jsonPrimitive.content)
            .isEqualTo("fallback")
        assertThat(viewModel.state.value.extrasError).isNull()
    }

    @Test
    fun `model switch updates the note and keeps core parameters`() = runTest(dispatcher) {
        val service = service()
        val viewModel = ConversationSettingsViewModel(service, "conversation-1")
        advanceUntilIdle()

        viewModel.onTemperatureChanged("0.5")
        advanceUntilIdle()
        viewModel.onMaxOutputTokensChanged("128")
        advanceUntilIdle()

        viewModel.onModelSelected("model-2")
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.model?.id).isEqualTo("model-2")
        assertThat(state.params.temperature).isEqualTo(0.5)
        assertThat(state.params.maxOutputTokens).isEqualTo(128)
        assertThat(state.unsupportedNote)
            .isEqualTo("This model ignores these values, so they are not sent: Max output tokens.")
    }

    @Test
    fun `delete removes the conversation and marks the state`() = runTest(dispatcher) {
        val service = service()
        val viewModel = ConversationSettingsViewModel(service, "conversation-1")
        advanceUntilIdle()

        viewModel.onDelete()
        advanceUntilIdle()

        assertThat(viewModel.state.value.deleted).isTrue()
        assertThat(service.conversations).doesNotContainKey("conversation-1")
    }

    @Test
    fun `missing conversation reports an error`() = runTest(dispatcher) {
        val viewModel = ConversationSettingsViewModel(service(), "missing")

        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isEqualTo("Conversation not found.")
    }

    @Test
    fun `routing summary is shown when routing is active`() = runTest(dispatcher) {
        val service = service()
        service.routing["conversation-1"] = RoutingPreferences(
            enabled = true,
            allowFallbacks = true,
            sort = RoutingSort.PRICE,
            denyDataCollection = false,
        )
        service.conversations["conversation-1"] = testConversation(
            params = withRoutingPreferences(
                testChatParams(),
                service.routing["conversation-1"]!!,
            ),
        )
        val viewModel = ConversationSettingsViewModel(service, "conversation-1")

        advanceUntilIdle()

        assertThat(viewModel.state.value.routingSummary)
            .isEqualTo("Fallbacks on, cheapest provider")
    }

    @Test
    fun `empty numeric input clears the value`() = runTest(dispatcher) {
        val service = service()
        service.conversations["conversation-1"] = testConversation(
            params = testChatParams().copy(temperature = 0.7),
        )
        val viewModel = ConversationSettingsViewModel(service, "conversation-1")
        advanceUntilIdle()

        viewModel.onTemperatureChanged("")
        advanceUntilIdle()

        assertThat(service.conversations["conversation-1"]!!.params.temperature).isNull()
    }

    @Test
    fun `params default is empty`() {
        val state = ConversationSettingsUiState()

        assertThat(state.params).isEqualTo(
            ChatParams(
                temperature = null,
                topP = null,
                maxOutputTokens = null,
                stop = emptyList(),
                reasoningEffort = null,
                streaming = true,
                extra = JsonObject(emptyMap()),
            ),
        )
    }
}
