package dev.chatui.feature.providers

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.model.ChatParams
import dev.chatui.core.model.ReasoningEffort
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class ParameterSupportTest {
    private fun params(
        temperature: Double? = null,
        topP: Double? = null,
        maxOutputTokens: Int? = null,
        stop: List<String> = emptyList(),
        reasoningEffort: ReasoningEffort? = null,
        extra: JsonObject = JsonObject(emptyMap()),
    ): ChatParams = ChatParams(
        temperature = temperature,
        topP = topP,
        maxOutputTokens = maxOutputTokens,
        stop = stop,
        reasoningEffort = reasoningEffort,
        streaming = true,
        extra = extra,
    )

    @Test
    fun `namespace is the provider preset id`() {
        assertThat(extraNamespaceForPreset("openrouter")).isEqualTo("openrouter")
        assertThat(extraNamespaceForPreset("google")).isEqualTo("google")
        assertThat(extraNamespaceForPreset("custom")).isEqualTo("custom")
    }

    @Test
    fun `empty extras parse to an empty object`() {
        val result = parseProviderExtras("   ")

        assertThat(result.ok).isTrue()
        assertThat(result.value).isEqualTo(JsonObject(emptyMap()))
    }

    @Test
    fun `invalid JSON is rejected`() {
        val result = parseProviderExtras("{not json")

        assertThat(result.ok).isFalse()
        assertThat(result.message).isEqualTo("Provider extras must be valid JSON.")
    }

    @Test
    fun `non-object JSON is rejected`() {
        val result = parseProviderExtras("[1, 2]")

        assertThat(result.ok).isFalse()
        assertThat(result.message).isEqualTo("Provider extras must be a JSON object.")
    }

    @Test
    fun `protected fields are rejected with a sorted list`() {
        val result = parseProviderExtras("""{"stream":true,"model":"x","messages":[]}""")

        assertThat(result.ok).isFalse()
        assertThat(result.message)
            .isEqualTo("Provider extras cannot override protected fields: messages, model, stream.")
    }

    @Test
    fun `valid extras parse into an object`() {
        val result = parseProviderExtras("""{"reasoning":{"effort":"high"}}""")

        assertThat(result.ok).isTrue()
        assertThat(result.value!!["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
            .isEqualTo("high")
    }

    @Test
    fun `with provider extras replaces only one namespace`() {
        val original = params(
            extra = JsonObject(
                mapOf(
                    "openrouter" to JsonPrimitive("keep"),
                    "google" to JsonPrimitive("old"),
                ),
            ),
        )

        val updated = withProviderExtras(
            original,
            "google",
            JsonObject(mapOf("safety" to JsonPrimitive("high"))),
        )

        assertThat(updated.extra["openrouter"]).isEqualTo(JsonPrimitive("keep"))
        assertThat(updated.extra["google"]!!.jsonObject["safety"]!!.jsonPrimitive.content)
            .isEqualTo("high")
    }

    @Test
    fun `empty extras remove the namespace`() {
        val original = params(
            extra = JsonObject(mapOf("google" to JsonPrimitive("old"))),
        )

        val updated = withProviderExtras(original, "google", JsonObject(emptyMap()))

        assertThat(updated.extra).isEmpty()
    }

    @Test
    fun `pruning keeps only the current provider namespace`() {
        val extra = JsonObject(
            mapOf(
                "openrouter" to JsonPrimitive("keep"),
                "google" to JsonPrimitive("drop"),
            ),
        )

        val pruned = pruneExtrasForPreset(extra, "openrouter")

        assertThat(pruned.keys).containsExactly("openrouter")
    }

    @Test
    fun `unsupported values are explained but not removed`() {
        val value = params(temperature = 0.7, maxOutputTokens = 100)

        val note = unsupportedParameterNote(value, listOf("temperature"))

        assertThat(note)
            .isEqualTo("This model ignores these values, so they are not sent: Max output tokens.")
        assertThat(value.maxOutputTokens).isEqualTo(100)
    }

    @Test
    fun `no note when every value is supported`() {
        val value = params(temperature = 0.7, stop = listOf("END"))

        val note = unsupportedParameterNote(value, listOf("temperature", "stop"))

        assertThat(note).isNull()
    }

    @Test
    fun `reasoning effort maps to either reasoning parameter name`() {
        val value = params(reasoningEffort = ReasoningEffort.HIGH)

        assertThat(unsupportedParameterNote(value, listOf("reasoning"))).isNull()
        assertThat(unsupportedParameterNote(value, listOf("reasoning_effort"))).isNull()
        assertThat(unsupportedParameterNote(value, listOf("temperature")))
            .isEqualTo("This model ignores these values, so they are not sent: Reasoning effort.")
    }

    @Test
    fun `stringify and read round trip`() {
        val value = params(
            extra = JsonObject(mapOf("openrouter" to JsonObject(mapOf("route" to JsonPrimitive("x"))))),
        )

        val text = stringifyProviderExtras(value, "openrouter")
        val parsed = parseProviderExtras(text)

        assertThat(parsed.ok).isTrue()
        assertThat(readProviderExtras(value, "openrouter"))
            .isEqualTo(parsed.value)
    }
}
