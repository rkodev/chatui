package dev.chatui.feature.providers

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.model.BrowserCors
import dev.chatui.core.model.InputModality
import dev.chatui.core.model.ModelCapability
import dev.chatui.core.model.ModelPricing
import dev.chatui.core.model.OutputModality
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

class ModelPickerTest {
    @Test
    fun `converts per-token prices to per-million display values`() {
        assertThat(formatPricePerMillion(0.15)).isEqualTo("\$0.15 / M")
        assertThat(formatPricePerMillion(3.0)).isEqualTo("\$3 / M")
        assertThat(formatPricePerMillion(0.0005)).isEqualTo("\$0.0005 / M")
        assertThat(formatPricePerMillion(0.0)).isEqualTo("Free")
        assertThat(formatPricePerMillion(null)).isEqualTo("—")
    }

    @Test
    fun `marks zero-priced and free-suffixed models as free`() {
        assertThat(
            isFreeModel(
                testModel(pricing = ModelPricing(inputPerMTok = 0.0, outputPerMTok = 0.0)),
            ),
        ).isTrue()
        assertThat(isFreeModel(testModel(remoteId = "author/model:free"))).isTrue()
        assertThat(
            isFreeModel(
                testModel(pricing = ModelPricing(inputPerMTok = 0.15, outputPerMTok = 0.6)),
            ),
        ).isFalse()
        assertThat(isFreeModel(testModel(remoteId = "author/model"))).isFalse()
    }

    @Test
    fun `formats a model price line with input and output prices`() {
        assertThat(
            formatModelPrice(
                testModel(pricing = ModelPricing(inputPerMTok = 0.15, outputPerMTok = 0.6)),
            ),
        ).isEqualTo("\$0.15 / M input · \$0.6 / M output")
        assertThat(
            formatModelPrice(
                testModel(pricing = ModelPricing(inputPerMTok = 0.0, outputPerMTok = 0.0)),
            ),
        ).isEqualTo("Free")
        assertThat(formatModelPrice(testModel(pricing = null))).isEqualTo("Price unavailable")
    }

    @Test
    fun `formats context windows and modalities`() {
        assertThat(formatContextWindow(128_000)).isEqualTo("128K context")
        assertThat(formatContextWindow(1_000_000)).isEqualTo("1M context")
        assertThat(formatContextWindow(null)).isEqualTo("Context unknown")
        assertThat(
            formatModalities(
                testModel(
                    inputModalities = listOf(InputModality.TEXT, InputModality.IMAGE),
                    outputModalities = listOf(OutputModality.TEXT),
                ),
            ),
        ).isEqualTo("text, image in · text out")
    }

    @Test
    fun `builds capability chips`() {
        assertThat(
            modelCapabilityChips(
                testModel(
                    capabilities = listOf(
                        ModelCapability.TEXT,
                        ModelCapability.VISION,
                        ModelCapability.TOOLS,
                    ),
                ),
            ),
        ).containsExactly("Text", "Vision", "Tools").inOrder()
    }

    @Test
    fun `puts pinned models first and groups the rest by provider`() {
        val openrouter = testProvider(id = "provider-1", label = "OpenRouter")
        val other = testProvider(id = "provider-2", label = "Other")
        val pinned = testModel(id = "pinned", label = "Pinned model", pinned = true)
        val regular = testModel(id = "regular", label = "Regular model")
        val hidden = testModel(id = "hidden", label = "Hidden model", hidden = true)
        val otherModel = testModel(
            id = "other",
            providerId = "provider-2",
            label = "Other model",
            remoteId = "other/model",
        )

        val groups = buildModelPickerGroups(
            listOf(regular, hidden, otherModel, pinned),
            listOf(openrouter, other),
        )

        assertThat(groups.map { it.label }).containsExactly("Pinned", "OpenRouter", "Other")
            .inOrder()
        assertThat(groups[0].models.map { it.id }).containsExactly("pinned")
        assertThat(groups[1].models.map { it.id }).containsExactly("regular")
        assertThat(groups[2].models.map { it.id }).containsExactly("other")
    }

    @Test
    fun `searches by label, remote id, and canonical slug`() {
        val regular = testModel(
            id = "regular",
            label = "Regular model",
            remoteId = "author/regular",
            canonicalSlug = "author/regular-latest",
        )

        assertThat(modelMatchesQuery(regular, "regular")).isTrue()
        assertThat(modelMatchesQuery(regular, "author/reg")).isTrue()
        assertThat(modelMatchesQuery(regular, "latest")).isTrue()
        assertThat(modelMatchesQuery(regular, "missing")).isFalse()

        val groups = buildModelPickerGroups(
            listOf(regular, testModel(id = "other", remoteId = "other/model")),
            listOf(testProvider()),
            "other",
        )
        assertThat(groups.flatMap { it.models }.map { it.id }).containsExactly("other")
    }

    @Test
    fun `warns when the selected model is unavailable or not text`() {
        val text = testModel(id = "text")
        val imageOnly = testModel(
            id = "image",
            outputModalities = listOf(OutputModality.IMAGE),
        )

        assertThat(modelAvailability(null, listOf(text)).available).isFalse()
        assertThat(modelAvailability(testModel(id = "missing"), listOf(text)).available).isFalse()
        assertThat(modelAvailability(imageOnly, listOf(imageOnly)).available).isFalse()
        assertThat(modelAvailability(text, listOf(text)).available).isTrue()
    }

    @Test
    fun `formats provider metadata`() {
        assertThat(formatCors(BrowserCors.SUPPORTED)).isEqualTo("Supported")
        assertThat(formatCors(BrowserCors.UNSUPPORTED)).isEqualTo("Not supported")
        assertThat(formatCors(BrowserCors.UNKNOWN)).isEqualTo("Unknown")
        assertThat(formatFreeTier(true)).isEqualTo("Yes")
        assertThat(formatFreeTier(false)).isEqualTo("No")
        assertThat(formatFreeTier(null)).isEqualTo("Unknown")
        assertThat(
            formatRateLimit(
                JsonObject(
                    mapOf(
                        "requests" to JsonPrimitive(20),
                        "interval" to JsonPrimitive("1m"),
                    ),
                ),
            ),
        ).isEqualTo("20 requests per 1m")
        assertThat(formatRateLimit(null)).isNull()
    }
}
