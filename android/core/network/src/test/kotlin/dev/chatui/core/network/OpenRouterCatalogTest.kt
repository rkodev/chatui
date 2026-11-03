package dev.chatui.core.network

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.model.InputModality
import dev.chatui.core.model.ModelCapability
import dev.chatui.core.model.ModelPricing
import dev.chatui.core.model.OutputModality
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertThrows
import org.junit.Test

class OpenRouterCatalogTest {
    private val modelsFixture: JsonElement = parseResource(
        "/contracts/fixtures/models/openrouter-models.json",
    )
    private val keyFixture: JsonElement = parseResource(
        "/contracts/fixtures/models/openrouter-key.json",
    )

    private val models: List<ModelInfo> = mapOpenRouterModels(modelsFixture)

    @Test
    fun `maps every fixture entry`() {
        assertThat(models).hasSize(8)
    }

    @Test
    fun `maps a paid text and tool model`() {
        val model = modelByRemoteId("openai/gpt-4o-mini-2024-07-18")

        assertThat(model.remoteId).isEqualTo("openai/gpt-4o-mini-2024-07-18")
        assertThat(model.canonicalSlug).isEqualTo("openai/gpt-4o-mini")
        assertThat(model.label).isEqualTo("OpenAI: GPT-4o mini (2024-07-18)")
        assertThat(model.contextWindow).isEqualTo(128000)
        assertThat(model.maxOutputTokens).isEqualTo(16384)
        assertThat(model.inputModalities).containsExactly(InputModality.TEXT)
        assertThat(model.outputModalities).containsExactly(OutputModality.TEXT)
        assertThat(model.isFree).isFalse()
        assertThat(model.capabilities).containsExactly(
            ModelCapability.TEXT,
            ModelCapability.TOOLS,
            ModelCapability.STREAMING,
        ).inOrder()
        assertThat(model.pricing).isEqualTo(
            ModelPricing(
                inputPerMTok = 0.15,
                outputPerMTok = 0.6,
                perRequest = 0.0,
                currency = "USD",
            ),
        )

        val metadata = model.metadata
        assertThat(metadata?.get("architecture")?.jsonObject?.get("modality")?.jsonPrimitive?.content)
            .isEqualTo("text->text")
        assertThat(metadata?.get("architecture")?.jsonObject?.get("tokenizer")?.jsonPrimitive?.content)
            .isEqualTo("GPT")
        assertThat(metadata?.get("pricing")?.jsonObject?.get("prompt")?.jsonPrimitive?.content)
            .isEqualTo("0.00000015")
        assertThat(metadata?.get("pricing")?.jsonObject?.get("completion")?.jsonPrimitive?.content)
            .isEqualTo("0.0000006")
        assertThat(metadata?.get("topProvider")?.jsonObject?.get("isModerated")?.jsonPrimitive?.boolean)
            .isFalse()
    }

    @Test
    fun `marks a zero-priced free model and preserves per-request limits`() {
        val model = modelByRemoteId("meta-llama/llama-3.2-3b-instruct:free")

        assertThat(model.isFree).isTrue()
        assertThat(model.pricing).isEqualTo(
            ModelPricing(
                inputPerMTok = 0.0,
                outputPerMTok = 0.0,
                perRequest = 0.0,
                currency = "USD",
            ),
        )
        assertThat(model.capabilities).containsExactly(
            ModelCapability.TEXT,
            ModelCapability.STREAMING,
        ).inOrder()
        assertThat(
            model.metadata?.get("perRequestLimits")
                ?.jsonObject?.get("requests_per_minute")?.jsonPrimitive?.int,
        ).isEqualTo(20)
    }

    @Test
    fun `infers vision and file capabilities from modalities`() {
        val model = modelByRemoteId("google/gemini-2.5-flash")

        assertThat(model.inputModalities).containsExactly(
            InputModality.TEXT,
            InputModality.IMAGE,
            InputModality.FILE,
        ).inOrder()
        assertThat(model.outputModalities).containsExactly(OutputModality.TEXT)
        assertThat(model.capabilities).containsExactly(
            ModelCapability.TEXT,
            ModelCapability.VISION,
            ModelCapability.FILES,
            ModelCapability.TOOLS,
            ModelCapability.REASONING,
            ModelCapability.STREAMING,
        ).inOrder()
        assertThat(model.pricing).isEqualTo(
            ModelPricing(
                inputPerMTok = 0.3,
                outputPerMTok = 2.5,
                perRequest = 0.0,
                currency = "USD",
            ),
        )
        assertThat(
            model.metadata?.get("defaultParameters")
                ?.jsonObject?.get("temperature")?.jsonPrimitive?.double,
        ).isEqualTo(0.7)
        assertThat(
            model.metadata?.get("topProvider")
                ?.jsonObject?.get("isModerated")?.jsonPrimitive?.boolean,
        ).isTrue()
    }

    @Test
    fun `infers reasoning from supported parameters and keeps raw reasoning pricing`() {
        val model = modelByRemoteId("deepseek/deepseek-r1")

        assertThat(model.capabilities).containsExactly(
            ModelCapability.TEXT,
            ModelCapability.REASONING,
            ModelCapability.STREAMING,
        ).inOrder()
        assertThat(
            model.metadata?.get("pricing")
                ?.jsonObject?.get("internal_reasoning")?.jsonPrimitive?.content,
        ).isEqualTo("0.00000219")
    }

    @Test
    fun `maps a tool-capable model`() {
        val model = modelByRemoteId("anthropic/claude-sonnet-4.5")

        assertThat(model.capabilities).containsExactly(
            ModelCapability.TEXT,
            ModelCapability.VISION,
            ModelCapability.FILES,
            ModelCapability.TOOLS,
            ModelCapability.REASONING,
            ModelCapability.STREAMING,
        ).inOrder()
    }

    @Test
    fun `maps a long-context model`() {
        val model = modelByRemoteId("google/gemini-2.5-pro")

        assertThat(model.contextWindow).isEqualTo(1048576)
        assertThat(model.maxOutputTokens).isEqualTo(65536)
    }

    @Test
    fun `converts unusual decimal pricing without binary-float artifacts`() {
        val model = modelByRemoteId("example/unusual-pricing")

        assertThat(model.pricing).isEqualTo(
            ModelPricing(
                inputPerMTok = 0.000001,
                outputPerMTok = 0.000002,
                perRequest = 0.0005,
                currency = "USD",
            ),
        )
        assertThat(model.metadata?.get("pricing")?.jsonObject?.get("prompt")?.jsonPrimitive?.content)
            .isEqualTo("0.000000000001")
        assertThat(model.metadata?.get("pricing")?.jsonObject?.get("completion")?.jsonPrimitive?.content)
            .isEqualTo("0.000000000002")
        assertThat(model.metadata?.get("pricing")?.jsonObject?.get("request")?.jsonPrimitive?.content)
            .isEqualTo("0.0005")
        assertThat(model.metadata?.get("pricing")?.jsonObject?.get("image")?.jsonPrimitive?.content)
            .isEqualTo("0.0000001")
        assertThat(model.isFree).isFalse()
    }

    @Test
    fun `ignores unknown fields but keeps small non-secret metadata`() {
        val model = modelByRemoteId("example/unknown-fields")

        assertThat(model.metadata?.get("x_provider_note")?.jsonPrimitive?.content)
            .isEqualTo("ignore this unknown field")
        assertThat(
            model.metadata?.get("future_capability_flags")
                ?.jsonObject?.get("synthetic")?.jsonPrimitive?.boolean,
        ).isTrue()
    }

    @Test
    fun `skips malformed entries instead of failing the catalog`() {
        val mapped = mapOpenRouterModels(
            Json.parseToJsonElement(
                """
                {
                  "data": [
                    null,
                    {},
                    {"id": ""},
                    {"id": "example/minimal", "name": ""},
                    {
                      "id": "example/fallback",
                      "context_length": 0,
                      "top_provider": {"context_length": 100, "max_completion_tokens": 0}
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )

        assertThat(mapped).hasSize(2)
        assertThat(mapped[0].remoteId).isEqualTo("example/minimal")
        assertThat(mapped[0].label).isEqualTo("example/minimal")
        assertThat(mapped[0].contextWindow).isNull()
        assertThat(mapped[0].maxOutputTokens).isNull()
        assertThat(mapped[0].capabilities).isEmpty()
        assertThat(mapped[0].pricing).isNull()
        assertThat(mapped[0].canonicalSlug).isNull()
        assertThat(mapped[0].inputModalities).isEmpty()
        assertThat(mapped[0].outputModalities).isEmpty()
        assertThat(mapped[0].supportedParameters).isEmpty()
        assertThat(mapped[0].isFree).isFalse()
        assertThat(mapped[1].remoteId).isEqualTo("example/fallback")
        assertThat(mapped[1].contextWindow).isEqualTo(100)
        assertThat(mapped[1].maxOutputTokens).isEqualTo(100)
    }

    @Test
    fun `accepts bare arrays and models envelopes`() {
        assertThat(
            mapOpenRouterModels(Json.parseToJsonElement("""[{"id":"example/array"}]""")),
        ).hasSize(1)
        assertThat(
            mapOpenRouterModels(
                Json.parseToJsonElement("""{"models":[{"id":"example/models"}]}"""),
            ),
        ).hasSize(1)
    }

    @Test
    fun `rejects a malformed envelope`() {
        assertThrows(OpenRouterCatalogException::class.java) {
            mapOpenRouterModels(Json.parseToJsonElement("{}"))
        }
        assertThrows(OpenRouterCatalogException::class.java) {
            mapOpenRouterModels(Json.parseToJsonElement("""{"data":"nope"}"""))
        }
    }

    @Test
    fun `returns null for a non-object model entry`() {
        assertThat(mapOpenRouterModel(null)).isNull()
        assertThat(mapOpenRouterModel(JsonPrimitive("model"))).isNull()
    }

    @Test
    fun `infers modalities from the architecture modality string`() {
        val model = mapOpenRouterModel(
            buildJsonObject {
                put("id", "example/modality")
                put(
                    "architecture",
                    buildJsonObject { put("modality", "text+image->text") },
                )
            },
        )

        assertThat(model?.inputModalities).containsExactly(
            InputModality.TEXT,
            InputModality.IMAGE,
        ).inOrder()
        assertThat(model?.outputModalities).containsExactly(OutputModality.TEXT)
        assertThat(model?.capabilities).containsExactly(
            ModelCapability.TEXT,
            ModelCapability.VISION,
        ).inOrder()
    }

    @Test
    fun `deduplicates supported parameters`() {
        val model = mapOpenRouterModel(
            buildJsonObject {
                put("id", "example/params")
                put(
                    "supported_parameters",
                    buildJsonArray {
                        add(JsonPrimitive("stream"))
                        add(JsonPrimitive("stream"))
                        add(JsonPrimitive("tools"))
                        add(JsonPrimitive("tools"))
                    },
                )
            },
        )

        assertThat(model?.supportedParameters).containsExactly("stream", "tools").inOrder()
        assertThat(model?.capabilities).containsExactly(
            ModelCapability.TOOLS,
            ModelCapability.STREAMING,
        ).inOrder()
    }

    @Test
    fun `does not mark a model free when a non-token charge is non-zero`() {
        val model = mapOpenRouterModel(
            buildJsonObject {
                put("id", "example/paid-image")
                put(
                    "pricing",
                    buildJsonObject {
                        put("prompt", "0")
                        put("completion", "0")
                        put("request", "0")
                        put("image", "0.0001")
                    },
                )
            },
        )

        assertThat(model?.isFree).isFalse()
    }

    @Test
    fun `marks a free suffix model free even when pricing is non-zero`() {
        val model = mapOpenRouterModel(
            buildJsonObject {
                put("id", "example/suffix:free")
                put(
                    "pricing",
                    buildJsonObject {
                        put("prompt", "0.1")
                        put("completion", "0.2")
                    },
                )
            },
        )

        assertThat(model?.isFree).isTrue()
    }

    @Test
    fun `does not copy secret-like unknown fields into metadata`() {
        val model = mapOpenRouterModel(
            buildJsonObject {
                put("id", "example/secret-metadata")
                put("api_key", "must-not-be-copied")
                put("x_provider_note", "safe")
            },
        )

        assertThat(model?.metadata?.get("x_provider_note")?.jsonPrimitive?.content).isEqualTo("safe")
        assertThat(model?.metadata?.containsKey("api_key")).isFalse()
    }

    @Test
    fun `maps the shared key fixture`() {
        val info = mapOpenRouterKey(keyFixture)

        assertThat(info.label).isEqualTo("Example OpenRouter Key")
        assertThat(info.usageUsd).isEqualTo(12.34)
        assertThat(info.limitUsd).isEqualTo(100.0)
        assertThat(info.limitRemainingUsd).isEqualTo(87.66)
        assertThat(info.isFreeTier).isFalse()
        assertThat(info.rateLimit?.get("requests")?.jsonPrimitive?.int).isEqualTo(60)
        assertThat(info.rateLimit?.get("interval")?.jsonPrimitive?.content).isEqualTo("1m")
    }

    @Test
    fun `maps null and numeric-string key fields`() {
        val nulls = mapOpenRouterKey(
            Json.parseToJsonElement(
                """
                {
                  "data": {
                    "label": null,
                    "usage": null,
                    "limit": null,
                    "limit_remaining": null,
                    "is_free_tier": null,
                    "rate_limit": null
                  }
                }
                """.trimIndent(),
            ),
        )

        assertThat(nulls.label).isNull()
        assertThat(nulls.usageUsd).isNull()
        assertThat(nulls.limitUsd).isNull()
        assertThat(nulls.limitRemainingUsd).isNull()
        assertThat(nulls.isFreeTier).isNull()
        assertThat(nulls.rateLimit).isNull()

        val numericStrings = mapOpenRouterKey(
            Json.parseToJsonElement("""{"data":{"usage":"12.5","limit":"100"}}"""),
        )

        assertThat(numericStrings.usageUsd).isEqualTo(12.5)
        assertThat(numericStrings.limitUsd).isEqualTo(100.0)
    }

    @Test
    fun `rejects a malformed key envelope`() {
        assertThrows(OpenRouterCatalogException::class.java) {
            mapOpenRouterKey(Json.parseToJsonElement("{}"))
        }
        assertThrows(OpenRouterCatalogException::class.java) {
            mapOpenRouterKey(Json.parseToJsonElement("""{"data":null}"""))
        }
    }

    @Test
    fun `multiplies decimal strings by one million exactly`() {
        assertThat(multiplyDecimalByMillion("0.00000015")).isEqualTo("0.15")
        assertThat(multiplyDecimalByMillion("0.0000006")).isEqualTo("0.6")
        assertThat(multiplyDecimalByMillion("0.000000000001")).isEqualTo("0.000001")
        assertThat(multiplyDecimalByMillion("0.1")).isEqualTo("100000")
        assertThat(multiplyDecimalByMillion("1.5")).isEqualTo("1500000")
        assertThat(multiplyDecimalByMillion("0")).isEqualTo("0")
        assertThat(multiplyDecimalByMillion("-0.000001")).isEqualTo("-1")
    }

    @Test
    fun `rejects invalid decimal strings`() {
        assertThat(multiplyDecimalByMillion("abc")).isNull()
        assertThat(multiplyDecimalByMillion("1e-6")).isNull()
        assertThat(multiplyDecimalByMillion("")).isNull()
    }

    @Test
    fun `converts USD per token to USD per million`() {
        assertThat(usdPerMillion("0.00000015")).isEqualTo(0.15)
        assertThat(usdPerMillion("0.1")).isEqualTo(100000.0)
        assertThat(usdPerMillion(null)).isNull()
        assertThat(usdPerMillion("abc")).isNull()
    }

    @Test
    fun `parses USD amounts without multiplying`() {
        assertThat(usdAmount("0.0005")).isEqualTo(0.0005)
        assertThat(usdAmount("0")).isEqualTo(0.0)
        assertThat(usdAmount("abc")).isNull()
        assertThat(usdAmount(null)).isNull()
    }

    @Test
    fun `uses a 24-hour TTL and a stable cache key`() {
        assertThat(OPENROUTER_CATALOG_TTL_MS).isEqualTo(24L * 60L * 60L * 1000L)
        assertThat(openRouterCatalogCacheKey()).isEqualTo("chatui.catalog.openrouter.v1")
        assertThat(openRouterCatalogCacheKey("custom")).isEqualTo("chatui.catalog.custom.v1")
    }

    @Test
    fun `reports freshness at the TTL boundary`() {
        val snapshot = CatalogSnapshot(fetchedAt = 1000, models = emptyList())

        assertThat(isCatalogFresh(snapshot, 1000 + OPENROUTER_CATALOG_TTL_MS - 1)).isTrue()
        assertThat(isCatalogFresh(snapshot, 1000 + OPENROUTER_CATALOG_TTL_MS)).isFalse()
    }

    @Test
    fun `clones snapshots in the memory cache`() = runTest {
        val cache = MemoryCatalogCache()
        val source = mutableListOf(models.first())
        cache.write("key", CatalogSnapshot(fetchedAt = 1000, models = source))

        source.clear()

        val first = cache.read("key")
        val second = cache.read("key")
        assertThat(first?.models).hasSize(1)
        assertThat(second?.models).hasSize(1)
        assertThat(first?.models).isNotSameInstanceAs(second?.models)
    }

    private fun modelByRemoteId(remoteId: String): ModelInfo =
        models.firstOrNull { it.remoteId == remoteId }
            ?: error("Missing fixture model $remoteId")

    private fun parseResource(path: String): JsonElement {
        val normalized = path.removePrefix("/")
        val stream = javaClass.classLoader.getResourceAsStream(normalized)
            ?: error("Missing test resource $normalized")
        return Json.parseToJsonElement(
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() },
        )
    }
}
