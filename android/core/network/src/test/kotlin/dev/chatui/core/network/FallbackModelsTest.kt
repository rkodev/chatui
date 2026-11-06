package dev.chatui.core.network

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.model.OutputModality
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class FallbackModelsTest {
    private val fixture: JsonObject = Json.parseToJsonElement(
        readResource("/contracts/fixtures/expected/fallback-models.json"),
    ).jsonObject

    @Test
    fun `explicit fallback lists match the shared fixture`() {
        val presets = fixture["presets"]!!.jsonArray.map { it.jsonObject }

        for (entry in presets) {
            val providerId = entry["providerId"]!!.jsonPrimitive.content
            val expected = entry["fallbackModelIds"]!!.jsonArray
                .map { it.jsonPrimitive.content }

            assertThat(fallbackModelIdsFor(providerId)).containsExactlyElementsIn(expected).inOrder()
        }
    }

    @Test
    fun `other presets fall back to the contract default model`() {
        val preset = ProviderRegistry.load().getPreset("deepseek")!!

        assertThat(fallbackModelIdsFor("deepseek", preset))
            .containsExactly("deepseek-chat")
    }

    @Test
    fun `openrouter keeps no fallback list`() {
        val preset = ProviderRegistry.load().getPreset("openrouter")!!

        assertThat(fallbackModelIdsFor("openrouter", preset)).isEmpty()
    }

    @Test
    fun `selection cases match the shared fixture`() {
        val cases = fixture["cases"]!!.jsonArray.map { it.jsonObject }

        for (case in cases) {
            val catalog = case["catalog"]!!.jsonArray.map { entry ->
                val obj = entry.jsonObject
                FallbackCatalogEntry(
                    remoteId = obj["remoteId"]!!.jsonPrimitive.content,
                    hidden = obj["hidden"]!!.jsonPrimitive.boolean,
                    outputModalities = obj["outputModalities"]!!.jsonArray.map {
                        when (it.jsonPrimitive.content) {
                            "text" -> OutputModality.TEXT
                            "image" -> OutputModality.IMAGE
                            "audio" -> OutputModality.AUDIO
                            else -> error("Unknown output modality")
                        }
                    },
                )
            }
            val fallbackIds = case["fallbackModelIds"]!!.jsonArray
                .map { it.jsonPrimitive.content }
            val expected = case["expected"]!!.jsonObject

            val selection = selectFallbackModel(
                catalog = catalog,
                presetDefaultModelId = case["presetDefaultModelId"]!!.jsonPrimitive.content,
                fallbackModelIds = fallbackIds,
            )

            assertThat(selection.selectedModelId)
                .isEqualTo(expected["selectedModelId"]?.jsonPrimitive?.contentOrNull)
            assertThat(selection.notice)
                .isEqualTo(expected["notice"]?.jsonPrimitive?.contentOrNull)
        }
    }

    private fun readResource(path: String): String {
        val normalized = path.removePrefix("/")
        val stream = javaClass.classLoader.getResourceAsStream(normalized)
            ?: error("Missing test resource $normalized")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
