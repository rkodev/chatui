package dev.chatui.feature.providers

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class RoutingTest {
    @Test
    fun `inactive routing omits the provider object`() {
        assertThat(buildProviderRouting(DEFAULT_ROUTING_PREFERENCES)).isNull()
        assertThat(routingSummary(DEFAULT_ROUTING_PREFERENCES)).isNull()
    }

    @Test
    fun `active routing serializes curated fields`() {
        val provider = buildProviderRouting(
            RoutingPreferences(
                enabled = true,
                allowFallbacks = false,
                sort = RoutingSort.PRICE,
                denyDataCollection = true,
            ),
        )

        assertThat(provider).isNotNull()
        assertThat(provider!!["allow_fallbacks"]!!.jsonPrimitive.boolean).isFalse()
        assertThat(provider["sort"]!!.jsonPrimitive.content).isEqualTo("price")
        assertThat(provider["data_collection"]!!.jsonPrimitive.content).isEqualTo("deny")
    }

    @Test
    fun `routing summary describes active preferences`() {
        assertThat(
            routingSummary(
                RoutingPreferences(
                    enabled = true,
                    allowFallbacks = true,
                    sort = RoutingSort.PRICE,
                    denyDataCollection = true,
                ),
            ),
        ).isEqualTo("Fallbacks on, cheapest provider, data collection denied")

        assertThat(
            routingSummary(
                RoutingPreferences(
                    enabled = true,
                    allowFallbacks = false,
                    sort = RoutingSort.LATENCY,
                ),
            ),
        ).isEqualTo("Fallbacks off, lowest latency")
    }

    @Test
    fun `reads routing preferences from conversation params`() {
        val params = testChatParams(
            extra = JsonObject(
                mapOf(
                    "openrouter" to JsonObject(
                        mapOf(
                            "provider" to JsonObject(
                                mapOf(
                                    "allow_fallbacks" to JsonPrimitive(false),
                                    "sort" to JsonPrimitive("throughput"),
                                    "data_collection" to JsonPrimitive("deny"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val preferences = readRoutingPreferences(params)

        assertThat(preferences.enabled).isTrue()
        assertThat(preferences.allowFallbacks).isFalse()
        assertThat(preferences.sort).isEqualTo(RoutingSort.THROUGHPUT)
        assertThat(preferences.denyDataCollection).isTrue()
    }

    @Test
    fun `writes routing preferences and preserves other advanced fields`() {
        val params = testChatParams(
            extra = JsonObject(
                mapOf(
                    "openrouter" to JsonObject(
                        mapOf(
                            "route" to JsonPrimitive("fallback"),
                            "models" to JsonObject(emptyMap()),
                        ),
                    ),
                ),
            ),
        )

        val updated = withRoutingPreferences(
            params,
            RoutingPreferences(
                enabled = true,
                allowFallbacks = true,
                sort = RoutingSort.PRICE,
            ),
        )

        val openrouter = updated.extra["openrouter"]!!.jsonObject
        assertThat(openrouter["route"]!!.jsonPrimitive.content).isEqualTo("fallback")
        assertThat(openrouter["models"]).isNotNull()
        assertThat(openrouter["provider"]!!.jsonObject["sort"]!!.jsonPrimitive.content)
            .isEqualTo("price")
    }

    @Test
    fun `disabling routing removes only the curated provider object`() {
        val params = testChatParams(
            extra = JsonObject(
                mapOf(
                    "openrouter" to JsonObject(
                        mapOf(
                            "route" to JsonPrimitive("fallback"),
                            "provider" to JsonObject(
                                mapOf("allow_fallbacks" to JsonPrimitive(true)),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val updated = withRoutingPreferences(params, DEFAULT_ROUTING_PREFERENCES)
        val openrouter = updated.extra["openrouter"]!!.jsonObject

        assertThat(openrouter["provider"]).isNull()
        assertThat(openrouter["route"]!!.jsonPrimitive.content).isEqualTo("fallback")
    }

    @Test
    fun `round trips routing preferences`() {
        val preferences = RoutingPreferences(
            enabled = true,
            allowFallbacks = false,
            sort = RoutingSort.LATENCY,
            denyDataCollection = true,
        )

        val params = withRoutingPreferences(testChatParams(), preferences)

        assertThat(readRoutingPreferences(params)).isEqualTo(preferences)
    }
}
