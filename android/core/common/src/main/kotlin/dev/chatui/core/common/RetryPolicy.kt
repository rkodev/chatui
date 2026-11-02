package dev.chatui.core.common

import kotlin.math.pow
import kotlin.random.Random

/**
 * Retry policy primitives from spec section 7.9.
 *
 * At most [maxAttempts] total attempts, exponential backoff with jitter, base
 * [baseDelayMillis], capped at [maxDelayMillis]. Retry only when no text has
 * been received; that decision belongs to the caller.
 *
 * [attemptsMade] is the number of attempts already performed. After the first
 * failure `attemptsMade == 1`, so the first retry uses retry index `0`.
 */
data class RetryPolicy(
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

    /**
     * Capped exponential delay for [retryIndex] without jitter.
     *
     * Index `0` is the first retry: 800 ms, then 1600, 3200, 6400, 8000, ...
     */
    fun backoffMillis(retryIndex: Int): Long {
        require(retryIndex >= 0) { "retryIndex must be non-negative" }
        val exponential = baseDelayMillis.toDouble() * 2.0.pow(retryIndex)
        return minOf(exponential, maxDelayMillis.toDouble()).toLong()
    }

    /**
     * Exponential delay for [retryIndex] with symmetric jitter of
     * [jitterFraction], clamped to `0..maxDelayMillis`.
     */
    fun jitteredBackoffMillis(
        retryIndex: Int,
        random: Random = Random.Default,
    ): Long {
        val base = backoffMillis(retryIndex)
        if (jitterFraction == 0.0) return base
        val jitter = base * jitterFraction
        val offset = (random.nextDouble() * 2.0 - 1.0) * jitter
        return (base + offset).toLong().coerceIn(0L, maxDelayMillis)
    }

    /**
     * Delay before the next attempt, or `null` when no retry is allowed.
     *
     * [retryAfterSeconds] is the `Retry-After` hook: when present it is
     * converted to milliseconds and the larger of the backoff and the header
     * value is used.
     */
    fun nextDelayMillis(
        attemptsMade: Int,
        retryAfterSeconds: Long? = null,
        random: Random = Random.Default,
    ): Long? {
        if (!canRetry(attemptsMade)) return null
        val backoff = jitteredBackoffMillis(attemptsMade - 1, random)
        val retryAfter = retryAfterSeconds?.coerceAtLeast(0)?.times(1_000L)
        return if (retryAfter != null) maxOf(backoff, retryAfter) else backoff
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS: Int = 3
        const val DEFAULT_BASE_DELAY_MILLIS: Long = 800L
        const val DEFAULT_MAX_DELAY_MILLIS: Long = 8_000L
        const val DEFAULT_JITTER_FRACTION: Double = 0.2

        val Default: RetryPolicy = RetryPolicy()
    }
}
