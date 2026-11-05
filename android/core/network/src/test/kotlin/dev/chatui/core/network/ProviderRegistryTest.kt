package dev.chatui.core.network

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.model.ProviderAuthType
import dev.chatui.core.model.ProviderKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertThrows
import org.junit.Test

class ProviderRegistryTest {
    private val registry = ProviderRegistry.load()

    @Test
    fun `loads the real contract without duplicating metadata`() {
        assertThat(registry.presets).hasSize(14)

        val openRouter = registry.getPreset("openrouter")
        assertThat(openRouter).isNotNull()
        assertThat(openRouter!!.label).isEqualTo("OpenRouter")
        assertThat(openRouter.kind).isEqualTo(ProviderKind.OPENAI_COMPATIBLE)
        assertThat(openRouter.baseUrl).isEqualTo("https://openrouter.ai/api/v1")
        assertThat(openRouter.auth.type).isEqualTo(ProviderAuthType.BEARER)
        assertThat(openRouter.modelDiscovery.format).isEqualTo(ModelDiscoveryFormat.OPENROUTER)
        assertThat(openRouter.defaultModelId).isEqualTo("openrouter/auto")
    }

    @Test
    fun `default reference and required lookup return openrouter`() {
        assertThat(registry.getDefaultPreset().id).isEqualTo("openrouter")
        assertThat(registry.getReferencePreset().id).isEqualTo("openrouter")
        assertThat(registry.getRequiredPreset().id).isEqualTo("openrouter")
    }

    @Test
    fun `M2 gating exposes OpenAI-compatible presets, Anthropic, and custom`() {
        val enabled = registry.getEnabledPresets().map { it.id }

        assertThat(enabled).containsExactly(
            "openrouter",
            "openai",
            "anthropic",
            "groq",
            "mistral",
            "deepseek",
            "xai",
            "together",
            "fireworks",
            "lmstudio",
            "llamacpp",
            "custom",
        )
        assertThat(registry.getEnabledPresets().first { it.id == "anthropic" }.kind)
            .isEqualTo(ProviderKind.ANTHROPIC)

        assertThat(isPresetEnabledInM2("openrouter")).isTrue()
        assertThat(isPresetEnabledInM2("openai")).isTrue()
        assertThat(isPresetEnabledInM2("custom")).isTrue()
        assertThat(isPresetEnabledInM2("anthropic")).isTrue()
        assertThat(isPresetEnabledInM2("google")).isFalse()
        assertThat(isPresetEnabledInM2("ollama")).isFalse()

        val availability = registry.getPresetAvailability()
        assertThat(availability.filter { it.enabled }.map { it.preset.id })
            .containsExactlyElementsIn(enabled)
        assertThat(availability.first { it.preset.id == "openai" }.status)
            .isEqualTo(PresetAvailabilityStatus.AVAILABLE)
        assertThat(availability.first { it.preset.id == "anthropic" }.status)
            .isEqualTo(PresetAvailabilityStatus.AVAILABLE)
        assertThat(availability.first { it.preset.id == "ollama" }.status)
            .isEqualTo(PresetAvailabilityStatus.COMING_LATER)
    }

    @Test
    fun `malformed fixture fails with a clear registry error`() {
        val error = assertThrows(ProviderRegistryException::class.java) {
            ProviderRegistry.fromJson(readResource("/contracts/malformed-providers.json"))
        }

        assertThat(error.message).contains("Provider presets are invalid")
        assertThat(error.issues).isNotEmpty()
    }

    @Test
    fun `duplicate ids are rejected`() {
        val duplicated = JsonArray(contractArray() + contractArray().first())

        val error = assertThrows(ProviderRegistryException::class.java) {
            ProviderRegistry.fromJson(encode(duplicated))
        }

        assertThat(error.message).contains("duplicate provider preset id(s): openrouter")
    }

    @Test
    fun `multiple defaults are rejected`() {
        val modified = contractArray().mapIndexed { index, element ->
            if (index == 1) element.jsonObject.with("default", JsonPrimitive(true)) else element
        }

        val error = assertThrows(ProviderRegistryException::class.java) {
            ProviderRegistry.fromJson(encode(modified))
        }

        assertThat(error.message).contains("multiple provider presets are marked as default")
    }

    @Test
    fun `missing default is rejected`() {
        val modified = contractArray().map { element ->
            element.jsonObject.with("default", JsonPrimitive(false))
        }

        val error = assertThrows(ProviderRegistryException::class.java) {
            ProviderRegistry.fromJson(encode(modified))
        }

        assertThat(error.message).contains("no provider preset is marked as default")
    }

    @Test
    fun `missing reference and required flags are rejected`() {
        val modified = contractArray().map { element ->
            element.jsonObject
                .with("reference", JsonPrimitive(false))
                .with("required", JsonPrimitive(false))
        }

        val error = assertThrows(ProviderRegistryException::class.java) {
            ProviderRegistry.fromJson(encode(modified))
        }

        assertThat(error.message).contains("no provider preset is marked as reference")
        assertThat(error.message).contains("no provider preset is marked as required")
    }

    @Test
    fun `missing required field is rejected`() {
        val modified = contractArray().mapIndexed { index, element ->
            if (index == 0) JsonObject(element.jsonObject - "baseUrl") else element
        }

        val error = assertThrows(ProviderRegistryException::class.java) {
            ProviderRegistry.fromJson(encode(modified))
        }

        assertThat(error.message).contains("baseUrl")
    }

    @Test
    fun `unknown fields are rejected`() {
        val modified = contractArray().mapIndexed { index, element ->
            if (index == 0) {
                element.jsonObject.with("unexpected", JsonPrimitive(true))
            } else {
                element
            }
        }

        val error = assertThrows(ProviderRegistryException::class.java) {
            ProviderRegistry.fromJson(encode(modified))
        }

        assertThat(error.message).contains("unexpected")
    }

    @Test
    fun `invalid preset values are rejected`() {
        val modified = contractArray().mapIndexed { index, element ->
            if (index == 0) element.jsonObject.with("id", JsonPrimitive("OpenRouter")) else element
        }

        val error = assertThrows(ProviderRegistryException::class.java) {
            ProviderRegistry.fromJson(encode(modified))
        }

        assertThat(error.message).contains("presets[0].id")
    }

    @Test
    fun `non http base url is rejected`() {
        val modified = contractArray().mapIndexed { index, element ->
            if (index == 0) {
                element.jsonObject.with("baseUrl", JsonPrimitive("ftp://example.com"))
            } else {
                element
            }
        }

        val error = assertThrows(ProviderRegistryException::class.java) {
            ProviderRegistry.fromJson(encode(modified))
        }

        assertThat(error.message).contains("presets[0].baseUrl")
    }

    private fun contractArray(): JsonArray =
        Json.parseToJsonElement(readResource("/contracts/providers.json")).jsonArray

    private fun encode(elements: List<JsonElement>): String =
        Json.encodeToString(JsonArray.serializer(), JsonArray(elements))

    private fun JsonObject.with(key: String, value: JsonElement): JsonObject =
        JsonObject(this + (key to value))

    private fun readResource(path: String): String =
        checkNotNull(javaClass.getResourceAsStream(path)) { "Missing test resource $path" }
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
}
