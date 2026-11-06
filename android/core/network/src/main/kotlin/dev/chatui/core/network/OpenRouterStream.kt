package dev.chatui.core.network

import kotlin.math.pow
import kotlin.random.Random
import kotlinx.serialization.json.JsonObject

/** Default inactivity watchdog for an OpenAI-compatible stream. */
internal const val DEFAULT_INACTIVITY_TIMEOUT_MS: Long = 120_000L

/** Parameters that are safe to serialize when the catalog does not say otherwise. */
internal val DEFAULT_SUPPORTED_PARAMETERS: List<String> = listOf(
    "temperature",
    "top_p",
    "max_tokens",
    "stop",
    "stream",
    "tools",
    "tool_choice",
)

/**
 * Retry timing for provider requests from spec section 7.9.
 *
 * At most [maxAttempts] total attempts, exponential backoff with symmetric
 * jitter, base [baseDelayMillis], capped at [maxDelayMillis]. A provider
 * `Retry-After` value overrides the local backoff, matching the web client.
 */
data class OpenRouterRetryPolicy(
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    val baseDelayMillis: Long = DEFAULT_BASE_DELAY_MILLIS,
    val maxDelayMillis: Long = DEFAULT_MAX_DELAY_MILLIS,
    val jitterFraction: Double = DEFAULT_JITTER_FRACTION,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
        require(baseDelayMillis > 0) { "baseDelayMillis must be positive" }
        require(maxDelayMillis >= baseDelayMillis) { "maxDelayMillis must be >= baseDelayMillis" }
        require(jitterFraction in 0.0..1.0) { "jitterFraction must be in 0.0..1.0" }
    }

    /** True when another attempt is allowed after [attemptsMade] attempts. */
    fun canRetry(attemptsMade: Int): Boolean = attemptsMade in 1 until maxAttempts

    /** Capped exponential delay for [retryIndex] without jitter. */
    fun backoffMillis(retryIndex: Int): Long {
        require(retryIndex >= 0) { "retryIndex must be non-negative" }
        val exponential = baseDelayMillis.toDouble() * 2.0.pow(retryIndex)
        return minOf(exponential, maxDelayMillis.toDouble()).toLong()
    }

    /** Exponential delay for [retryIndex] with symmetric jitter. */
    fun jitteredBackoffMillis(retryIndex: Int, random: Random): Long {
        val base = backoffMillis(retryIndex)
        if (jitterFraction == 0.0) return base
        val jitter = base * jitterFraction
        val offset = (random.nextDouble() * 2.0 - 1.0) * jitter
        return (base + offset).toLong().coerceIn(0L, maxDelayMillis)
    }

    /**
     * Delay before the next attempt, or `null` when no retry is allowed.
     *
     * A provider `Retry-After` value wins over the local backoff policy.
     */
    fun nextDelayMillis(
        attemptsMade: Int,
        retryAfterSeconds: Long?,
        random: Random,
    ): Long? {
        if (!canRetry(attemptsMade)) return null
        if (retryAfterSeconds != null) {
            return retryAfterSeconds.coerceAtLeast(0) * 1_000L
        }
        return jitteredBackoffMillis(attemptsMade - 1, random)
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS: Int = 3
        const val DEFAULT_BASE_DELAY_MILLIS: Long = 800L
        const val DEFAULT_MAX_DELAY_MILLIS: Long = 8_000L
        const val DEFAULT_JITTER_FRACTION: Double = 0.2
    }
}

/**
 * Build the exact OpenRouter `POST /chat/completions` body.
 *
 * Kept as a thin wrapper over the preset-driven builder so the M1 vectors and
 * tests continue to exercise the OpenRouter preset.
 */
internal fun buildChatRequestBody(
    request: NormalizedChatRequest,
    supportedParameters: List<String> = DEFAULT_SUPPORTED_PARAMETERS,
): JsonObject = buildOpenAICompatibleChatRequestBody(
    request = request,
    preset = openRouterTransportPreset(),
    supportedParameters = supportedParameters,
)
