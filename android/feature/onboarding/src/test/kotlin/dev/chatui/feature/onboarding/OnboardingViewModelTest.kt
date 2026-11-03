package dev.chatui.feature.onboarding

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.model.ChatErrorCode
import dev.chatui.core.network.ModelInfo
import dev.chatui.core.network.OpenRouterRequestException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `first run preselects OpenRouter and loads the public catalog`() = runTest(dispatcher) {
        val service = FakeOnboardingService()
        val viewModel = OnboardingViewModel(service)

        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.step).isEqualTo(OnboardingStep.WELCOME)
        assertThat(state.selectedProviderId).isEqualTo("openrouter")
        assertThat(state.providers.first { it.id == "openrouter" }.enabled).isTrue()
        assertThat(state.providers.first { it.id == "openrouter" }.recommended).isTrue()
        assertThat(state.providers.first { it.id == "openai" }.enabled).isFalse()
        assertThat(state.catalog).isEqualTo(
            CatalogUiState.Ready(
                listOf(
                    CatalogModel(
                        remoteId = "openrouter/auto",
                        label = "openrouter/auto",
                        contextWindow = 128_000,
                        isFree = false,
                    ),
                ),
            ),
        )
        assertThat(service.loadCatalogCalls).isEqualTo(1)
    }

    @Test
    fun `invalid key shows an actionable error and preserves the form`() = runTest(dispatcher) {
        val service = FakeOnboardingService().apply {
            connectError = OpenRouterRequestException(
                code = ChatErrorCode.AUTH,
                message = "Invalid key",
            )
        }
        val viewModel = OnboardingViewModel(service)
        advanceUntilIdle()

        viewModel.onContinue()
        viewModel.onApiKeyChanged("sk-bad")
        viewModel.onLabelChanged("Work")
        viewModel.onConnect()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.testing).isFalse()
        assertThat(state.connected).isFalse()
        assertThat(state.apiKey).isEqualTo("sk-bad")
        assertThat(state.label).isEqualTo("Work")
        assertThat(state.error?.title).isEqualTo("API key rejected")
        assertThat(state.error?.message)
            .isEqualTo("Your API key was rejected. Check the key and try again.")
        assertThat(service.lastApiKey).isEqualTo("sk-bad")
        assertThat(service.lastLabel).isEqualTo("Work")
    }

    @Test
    fun `valid key connects and emits a navigation event`() = runTest(dispatcher) {
        val service = FakeOnboardingService()
        val viewModel = OnboardingViewModel(service)
        advanceUntilIdle()

        viewModel.onContinue()
        viewModel.onApiKeyChanged("sk-good")

        val event = async { viewModel.events.first() }
        viewModel.onConnect()
        advanceUntilIdle()

        assertThat(event.await()).isEqualTo(OnboardingEvent.Connected)

        val state = viewModel.state.value
        assertThat(state.testing).isFalse()
        assertThat(state.connected).isTrue()
        assertThat(state.error).isNull()
        assertThat(service.lastApiKey).isEqualTo("sk-good")
    }

    @Test
    fun `catalog is visible before validation and sending stays disabled`() = runTest(dispatcher) {
        val service = FakeOnboardingService()
        val viewModel = OnboardingViewModel(service)
        advanceUntilIdle()

        viewModel.onContinue()

        val state = viewModel.state.value
        assertThat(state.catalog).isInstanceOf(CatalogUiState.Ready::class.java)
        assertThat(state.connected).isFalse()
        assertThat(state.canConnect).isFalse()
    }

    @Test
    fun `disabled providers cannot be selected`() = runTest(dispatcher) {
        val service = FakeOnboardingService()
        val viewModel = OnboardingViewModel(service)
        advanceUntilIdle()

        viewModel.onProviderSelected("openai")

        assertThat(viewModel.state.value.selectedProviderId).isEqualTo("openrouter")
    }

    @Test
    fun `catalog failure is non-blocking`() = runTest(dispatcher) {
        val service = FakeOnboardingService().apply {
            catalogError = OpenRouterRequestException(
                code = ChatErrorCode.NETWORK,
                message = "offline",
            )
        }
        val viewModel = OnboardingViewModel(service)

        advanceUntilIdle()

        val catalog = viewModel.state.value.catalog
        assertThat(catalog).isInstanceOf(CatalogUiState.Error::class.java)
        assertThat((catalog as CatalogUiState.Error).message)
            .contains("You can still connect")
    }
}

private class FakeOnboardingService : OnboardingService {
    var catalog: List<ModelInfo> = listOf(modelInfo("openrouter/auto"))
    var catalogError: Throwable? = null
    var connectResult: OnboardingResult = OnboardingResult(
        providerId = "provider-1",
        modelId = "model-1",
        notice = null,
    )
    var connectError: Throwable? = null
    var lastApiKey: String? = null
    var lastLabel: String? = null
    var loadCatalogCalls: Int = 0

    override fun providerOptions(): List<ProviderOption> = listOf(
        ProviderOption(
            id = "openrouter",
            label = "OpenRouter",
            enabled = true,
            recommended = true,
            statusLabel = null,
        ),
        ProviderOption(
            id = "openai",
            label = "OpenAI",
            enabled = false,
            recommended = false,
            statusLabel = "Coming later",
        ),
    )

    override suspend fun loadCatalog(): List<ModelInfo> {
        loadCatalogCalls += 1
        catalogError?.let { throw it }
        return catalog
    }

    override suspend fun connect(apiKey: String, label: String?): OnboardingResult {
        lastApiKey = apiKey
        lastLabel = label
        connectError?.let { throw it }
        return connectResult
    }
}

private fun modelInfo(
    remoteId: String,
    label: String = remoteId,
    isFree: Boolean = false,
): ModelInfo = ModelInfo(
    remoteId = remoteId,
    label = label,
    contextWindow = 128_000,
    isFree = isFree,
)
