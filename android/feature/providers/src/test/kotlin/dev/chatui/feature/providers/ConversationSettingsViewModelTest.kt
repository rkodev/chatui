package dev.chatui.feature.providers

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.model.ChatParams
import dev.chatui.core.model.Conversation
import dev.chatui.core.model.ReasoningEffort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
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

    @Test
    fun `explicit zero values and every generation reset remain distinct from defaults`() = runTest(dispatcher) {
        val service = service()
        val viewModel = ConversationSettingsViewModel(service, "conversation-1")
        advanceUntilIdle()

        viewModel.onTemperatureChanged("0")
        viewModel.onTopPChanged("0")
        viewModel.onMaxOutputTokensChanged("1")
        viewModel.onStopChanged("END\nSTOP")
        viewModel.onReasoningEffortChanged(ReasoningEffort.LOW)
        viewModel.onStreamingChanged(false)
        advanceUntilIdle()

        val explicit = service.conversations.getValue("conversation-1").params
        assertThat(explicit.temperature).isEqualTo(0.0)
        assertThat(explicit.topP).isEqualTo(0.0)
        assertThat(explicit.maxOutputTokens).isEqualTo(1)
        assertThat(viewModel.state.value.generationSummary).contains("Temperature 0.0")
        assertThat(viewModel.state.value.generationSummary).contains("Streaming off")

        viewModel.onTemperatureChanged("")
        viewModel.onTopPChanged("")
        viewModel.onMaxOutputTokensChanged("")
        viewModel.onStopChanged("")
        viewModel.onReasoningEffortChanged(null)
        viewModel.onStreamingChanged(true)
        advanceUntilIdle()

        val reset = service.conversations.getValue("conversation-1").params
        assertThat(reset).isEqualTo(testChatParams(extra = explicit.extra))
        assertThat(viewModel.state.value.generationSummary).isEqualTo("Model defaults · Streaming on")
    }

    @Test
    fun `invalid numeric input is retained without silently clamping or clearing persistence`() = runTest(dispatcher) {
        val service = service()
        service.conversations["conversation-1"] = testConversation(
            params = testChatParams().copy(temperature = 0.7, maxOutputTokens = 256),
        )
        val viewModel = ConversationSettingsViewModel(service, "conversation-1")
        advanceUntilIdle()

        viewModel.onTemperatureChanged("4.2")
        viewModel.onMaxOutputTokensChanged("0")
        advanceUntilIdle()
        assertThat(service.conversations.getValue("conversation-1").params.temperature).isEqualTo(0.7)
        assertThat(service.conversations.getValue("conversation-1").params.maxOutputTokens).isEqualTo(256)
        assertThat(viewModel.state.value.temperatureText).isEqualTo("4.2")
        assertThat(viewModel.state.value.parameterErrors.keys)
            .containsAtLeast(CoreParameterKey.TEMPERATURE, CoreParameterKey.MAX_OUTPUT_TOKENS)

        viewModel.onTemperatureChanged("0.")
        viewModel.onMaxOutputTokensChanged("1025")
        viewModel.onTopPChanged("0.123456789")
        advanceUntilIdle()
        assertThat(viewModel.state.value.temperatureText).isEqualTo("0.")
        assertThat(service.conversations.getValue("conversation-1").params.temperature).isEqualTo(0.0)
        assertThat(service.conversations.getValue("conversation-1").params.topP).isEqualTo(0.123456789)
        assertThat(service.conversations.getValue("conversation-1").params.maxOutputTokens).isEqualTo(1025)
        assertThat(viewModel.state.value.parameterErrors).isEmpty()
    }

    @Test
    fun `interleaved suspended generation raw extras and routing writes preserve newer fields`() = runTest(dispatcher) {
        val backing = service()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var firstWrite = true
        val service = object : ProviderManagementService by backing {
            override suspend fun setConversationSettings(
                conversationId: String,
                systemPrompt: String?,
                params: ChatParams?,
            ): Conversation {
                if (firstWrite) {
                    firstWrite = false
                    entered.complete(Unit)
                    release.await()
                }
                return backing.setConversationSettings(conversationId, systemPrompt, params)
            }
        }
        val settings = ConversationSettingsViewModel(service, "conversation-1")
        advanceUntilIdle()
        val routing = ConversationRoutingViewModel(
            service, "conversation-1",
            initialConversation = settings.state.value.conversation,
            persistPreferences = settings::persistRouting,
        )

        settings.onTemperatureChanged("0.625")
        runCurrent()
        assertThat(entered.isCompleted).isTrue()
        settings.onExtrasChanged(
            """{"unknown":{"keep":true},"provider":{"order":["upstream"],"allow_fallbacks":false,"sort":"latency","data_collection":"deny"}}""",
        )
        routing.onEnabledChanged(true)
        settings.onStreamingChanged(false)
        settings.onSystemPromptChanged("Keep these instructions")
        release.complete(Unit)
        advanceUntilIdle()

        assertThat(readRoutingPreferences(backing.conversations.getValue("conversation-1").params)).isEqualTo(
            RoutingPreferences(
                enabled = true,
                allowFallbacks = false,
                sort = RoutingSort.LATENCY,
                denyDataCollection = true,
            ),
        )
        routing.onSortChanged(RoutingSort.PRICE)
        settings.onMaxOutputTokensChanged("513")
        advanceUntilIdle()

        val stored = backing.conversations.getValue("conversation-1")
        val extras = stored.params.extra.getValue("openrouter").jsonObject
        assertThat(extras.getValue("unknown").jsonObject.getValue("keep")).isEqualTo(JsonPrimitive(true))
        assertThat(extras.getValue("provider").jsonObject.getValue("order").toString())
            .isEqualTo("""["upstream"]""")
        assertThat(readRoutingPreferences(stored.params).sort).isEqualTo(RoutingSort.PRICE)
        assertThat(readRoutingPreferences(stored.params).allowFallbacks).isFalse()
        assertThat(readRoutingPreferences(stored.params).denyDataCollection).isTrue()
        assertThat(stored.params.temperature).isEqualTo(0.625)
        assertThat(stored.params.maxOutputTokens).isEqualTo(513)
        assertThat(stored.params.streaming).isFalse()
        assertThat(stored.systemPrompt).isEqualTo("Keep these instructions")
        assertThat(settings.state.value.params).isEqualTo(stored.params)

        val reopened = ConversationSettingsViewModel(service, "conversation-1")
        advanceUntilIdle()
        assertThat(reopened.state.value.extrasText).contains("unknown")
        assertThat(reopened.state.value.routingSummary).contains("cheapest provider")
    }

    @Test
    fun `invalid raw JSON survives routing and generation updates until corrected`() = runTest(dispatcher) {
        val service = service()
        val settings = ConversationSettingsViewModel(service, "conversation-1")
        advanceUntilIdle()
        val routing = ConversationRoutingViewModel(
            service, "conversation-1",
            initialConversation = settings.state.value.conversation,
            persistPreferences = settings::persistRouting,
        )

        settings.onExtrasChanged("""{"unfinished":""")
        routing.onEnabledChanged(true)
        advanceUntilIdle()
        settings.onTopPChanged("0.8")
        advanceUntilIdle()
        assertThat(settings.state.value.extrasText).isEqualTo("""{"unfinished":""")
        assertThat(settings.state.value.extrasError).isEqualTo("Provider extras must be valid JSON.")
        assertThat(readRoutingPreferences(service.conversations.getValue("conversation-1").params).enabled).isTrue()
        assertThat(settings.state.value.advancedSummary).contains("JSON needs correction")

        val corrected = readProviderExtras(settings.state.value.params, "openrouter")
        settings.onExtrasChanged(JsonObject(corrected + ("unknown" to JsonPrimitive("retained"))).toString())
        advanceUntilIdle()
        assertThat(settings.state.value.extrasError).isNull()
        assertThat(settings.state.value.params.topP).isEqualTo(0.8)
        assertThat(readRoutingPreferences(settings.state.value.params).enabled).isTrue()
        assertThat(settings.state.value.extrasText).contains("retained")
    }

    @Test
    fun `failed extras write retains pending edits and explicit retry persists them`() = runTest(dispatcher) {
        val service = service()
        val settings = ConversationSettingsViewModel(service, "conversation-1")
        advanceUntilIdle()
        service.error = IllegalStateException("Try again")
        settings.onExtrasChanged("""{"unknown":"retain"}""")
        settings.onTemperatureChanged("0.4")
        advanceUntilIdle()
        assertThat(settings.state.value.hasPendingChanges).isTrue()
        assertThat(settings.state.value.extrasText).contains("retain")
        assertThat(settings.state.value.error).isEqualTo("Try again")

        service.error = null
        settings.onRetry()
        advanceUntilIdle()
        assertThat(settings.state.value.hasPendingChanges).isFalse()
        assertThat(settings.state.value.error).isNull()
        assertThat(service.conversations.getValue("conversation-1").params.temperature).isEqualTo(0.4)
        assertThat(service.conversations.getValue("conversation-1").params.extra["openrouter"].toString())
            .contains("retain")
    }

    @Test
    fun `provider change refreshes extras context and prevents stale OpenRouter writes`() = runTest(dispatcher) {
        val service = service()
        service.providersFlow.value += testProvider(id = "google", presetId = "google")
        service.models += testModel(id = "google-model", providerId = "google")
        service.conversations["conversation-1"] = testConversation(
            params = withRoutingPreferences(
                testChatParams(
                    extra = JsonObject(mapOf("google" to JsonObject(mapOf("future" to JsonPrimitive(true))))),
                ).copy(temperature = 0.3),
                RoutingPreferences(enabled = true),
            ),
        ).copy(systemPrompt = "Retain prompt")
        val settings = ConversationSettingsViewModel(service, "conversation-1")
        advanceUntilIdle()
        settings.onExtrasChanged("{")

        settings.onModelSelected("google-model")
        advanceUntilIdle()
        assertThat(settings.state.value.presetId).isEqualTo("google")
        assertThat(settings.state.value.extrasText).contains("future")
        assertThat(settings.state.value.extrasError).isNull()
        assertThat(settings.state.value.routingSummary).isNull()
        assertThat(settings.state.value.params.temperature).isEqualTo(0.3)
        assertThat(settings.state.value.systemPrompt).isEqualTo("Retain prompt")
        assertThat(
            runCatching { settings.persistRouting { it.copy(enabled = true) } }.exceptionOrNull()?.message,
        ).contains("current provider")
        assertThat(service.conversations.getValue("conversation-1").params.extra["openrouter"]).isNull()

        settings.onModelSelected("model-1")
        advanceUntilIdle()
        assertThat(settings.state.value.extrasText).isEqualTo("{")
        assertThat(settings.state.value.extrasError).isNotNull()
    }
}
