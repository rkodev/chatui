package dev.chatui.core.network

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.model.ChatParams
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.FinishReason
import dev.chatui.core.model.Provider
import dev.chatui.core.model.ProviderAuthType
import dev.chatui.core.model.ProviderKind
import dev.chatui.core.model.BrowserCors
import dev.chatui.core.model.StreamEvent
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Test

class ProviderAdapterTest {
    private val at = Instant.parse("2026-11-02T12:34:56Z")
    private val provider = Provider(
        id = "provider-1",
        presetId = "openrouter",
        label = "OpenRouter",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        baseUrl = "https://openrouter.ai/api/v1",
        authType = ProviderAuthType.BEARER,
        secretId = "secret-1",
        headers = emptyMap(),
        enabled = true,
        browserCors = BrowserCors.SUPPORTED,
        metadata = null,
        createdAt = at,
        updatedAt = at,
    )
    private val context = ProviderContext(provider, "sk-test")
    private val request = NormalizedChatRequest(
        model = "openai/gpt-4o",
        system = "Be concise",
        messages = listOf(
            NormalizedMessage(NormalizedRole.USER, listOf(ContentPart.Text("hello"))),
        ),
        params = ChatParams(
            temperature = null,
            topP = null,
            maxOutputTokens = null,
            stop = emptyList(),
            reasoningEffort = null,
            streaming = true,
            extra = JsonObject(emptyMap()),
        ),
    )

    private class FakeAdapter : ProviderAdapter {
        override val kind: ProviderKind = ProviderKind.OPENAI_COMPATIBLE

        override suspend fun validateKey(context: ProviderContext): ProviderKeyInfo =
            ProviderKeyInfo(label = "test", isFreeTier = true)

        override suspend fun listModels(context: ProviderContext): List<ModelInfo> =
            listOf(ModelInfo(remoteId = "openai/gpt-4o", label = "GPT-4o"))

        override fun streamChat(
            context: ProviderContext,
            request: NormalizedChatRequest,
        ): Flow<StreamEvent> = kotlinx.coroutines.flow.flowOf(
            StreamEvent.Start("response-1"),
            StreamEvent.TextDelta("hi"),
            StreamEvent.Finish(FinishReason.STOP),
        )
    }

    @Test
    fun `adapter contract supports key validation and model listing`() = runTest {
        val adapter = FakeAdapter()

        assertThat(adapter.kind).isEqualTo(ProviderKind.OPENAI_COMPATIBLE)
        assertThat(adapter.validateKey(context).isFreeTier).isTrue()
        assertThat(adapter.listModels(context).single().remoteId).isEqualTo("openai/gpt-4o")
    }

    @Test
    fun `adapter contract streams normalized events`() = runTest {
        val events = FakeAdapter().streamChat(context, request).toList()

        assertThat(events).hasSize(3)
        assertThat(events[0]).isEqualTo(StreamEvent.Start("response-1"))
        assertThat(events[1]).isEqualTo(StreamEvent.TextDelta("hi"))
        assertThat(events[2]).isEqualTo(StreamEvent.Finish(FinishReason.STOP))
    }
}
