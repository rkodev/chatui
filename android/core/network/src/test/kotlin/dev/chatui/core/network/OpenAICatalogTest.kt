package dev.chatui.core.network

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.model.InputModality
import dev.chatui.core.model.ModelCapability
import dev.chatui.core.model.OutputModality
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class OpenAICatalogTest {
    private val fixture: JsonObject = Json.parseToJsonElement(
        readResource("/contracts/fixtures/expected/capability-inference.json"),
    ).jsonObject

    @Test
    fun `capability inference cases match the shared fixture`() {
        val cases = fixture["cases"]!!.jsonArray.map { it.jsonObject }

        for (case in cases) {
            val preset = case["preset"]!!.jsonObject
            val defaults = OpenAICatalogDefaults(
                inputModalities = preset["inputModalities"]!!.jsonArray.map {
                    inputModality(it.jsonPrimitive.content)
                },
                outputModalities = preset["outputModalities"]!!.jsonArray.map {
                    outputModality(it.jsonPrimitive.content)
                },
                capabilities = preset["capabilities"]!!.jsonArray.map {
                    capability(it.jsonPrimitive.content)
                },
                supportedParameters = preset["supportedParameters"]!!.jsonArray.map {
                    it.jsonPrimitive.content
                },
            )
            val expected = case["expected"]!!.jsonObject

            val inferred = inferOpenAIModelMetadata(
                case["raw"]!!.jsonObject,
                defaults,
            )

            assertThat(inferred.inputModalities)
                .containsExactlyElementsIn(
                    expected["inputModalities"]!!.jsonArray.map {
                        inputModality(it.jsonPrimitive.content)
                    },
                )
                .inOrder()
            assertThat(inferred.outputModalities)
                .containsExactlyElementsIn(
                    expected["outputModalities"]!!.jsonArray.map {
                        outputModality(it.jsonPrimitive.content)
                    },
                )
                .inOrder()
            assertThat(inferred.capabilities)
                .containsExactlyElementsIn(
                    expected["capabilities"]!!.jsonArray.map {
                        capability(it.jsonPrimitive.content)
                    },
                )
                .inOrder()
            assertThat(inferred.supportedParameters)
                .containsExactlyElementsIn(
                    expected["supportedParameters"]!!.jsonArray.map {
                        it.jsonPrimitive.content
                    },
                )
                .inOrder()
            assertThat(inferred.contextWindow)
                .isEqualTo(expected["contextWindow"]?.jsonPrimitive?.intOrNull)
            assertThat(inferred.maxOutputTokens)
                .isEqualTo(expected["maxOutputTokens"]?.jsonPrimitive?.intOrNull)
        }
    }

    @Test
    fun `openai mapping marks embedding models hidden`() {
        val models = mapOpenAIModels(
            Json.parseToJsonElement(
                """{"data":[{"id":"gpt-4o-mini"},{"id":"text-embedding-3-small"}]}""",
            ),
            catalogDefaultsForPreset("openai"),
        )

        assertThat(models.map { it.remoteId })
            .containsExactly("gpt-4o-mini", "text-embedding-3-small")
            .inOrder()
        assertThat(models.map { it.hidden }).containsExactly(false, true).inOrder()
    }

    private fun inputModality(value: String): InputModality = when (value) {
        "text" -> InputModality.TEXT
        "image" -> InputModality.IMAGE
        "file" -> InputModality.FILE
        "audio" -> InputModality.AUDIO
        "video" -> InputModality.VIDEO
        else -> error("Unknown input modality $value")
    }

    private fun outputModality(value: String): OutputModality = when (value) {
        "text" -> OutputModality.TEXT
        "image" -> OutputModality.IMAGE
        "audio" -> OutputModality.AUDIO
        else -> error("Unknown output modality $value")
    }

    private fun capability(value: String): ModelCapability = when (value) {
        "text" -> ModelCapability.TEXT
        "vision" -> ModelCapability.VISION
        "files" -> ModelCapability.FILES
        "tools" -> ModelCapability.TOOLS
        "reasoning" -> ModelCapability.REASONING
        "streaming" -> ModelCapability.STREAMING
        else -> error("Unknown capability $value")
    }

    private fun readResource(path: String): String {
        val normalized = path.removePrefix("/")
        val stream = javaClass.classLoader.getResourceAsStream(normalized)
            ?: error("Missing test resource $normalized")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
