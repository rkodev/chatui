package dev.chatui.core.common

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.common.testing.FixedRandom
import org.junit.Test

class RetryPolicyTest {
    private val policy = RetryPolicy.Default

    @Test
    fun `defaults match the specified policy`() {
        assertThat(policy.maxAttempts).isEqualTo(3)
        assertThat(policy.baseDelayMillis).isEqualTo(800L)
        assertThat(policy.maxDelayMillis).isEqualTo(8_000L)
    }

    @Test
    fun `allows at most three total attempts`() {
        assertThat(policy.canRetry(0)).isFalse()
        assertThat(policy.canRetry(1)).isTrue()
        assertThat(policy.canRetry(2)).isTrue()
        assertThat(policy.canRetry(3)).isFalse()
        assertThat(policy.canRetry(4)).isFalse()
    }

    @Test
    fun `backoff doubles from 800 ms and caps at 8 s`() {
        assertThat(policy.backoffMillis(0)).isEqualTo(800L)
        assertThat(policy.backoffMillis(1)).isEqualTo(1_600L)
        assertThat(policy.backoffMillis(2)).isEqualTo(3_200L)
        assertThat(policy.backoffMillis(3)).isEqualTo(6_400L)
        assertThat(policy.backoffMillis(4)).isEqualTo(8_000L)
        assertThat(policy.backoffMillis(10)).isEqualTo(8_000L)
    }

    @Test
    fun `jitter stays within the configured fraction`() {
        val low = policy.jitteredBackoffMillis(0, FixedRandom(fixedDouble = 0.0))
        val high = policy.jitteredBackoffMillis(0, FixedRandom(fixedDouble = 1.0))

        assertThat(low).isEqualTo(640L)
        assertThat(high).isEqualTo(960L)
    }

    @Test
    fun `jitter never exceeds the cap`() {
        val high = policy.jitteredBackoffMillis(10, FixedRandom(fixedDouble = 1.0))

        assertThat(high).isAtMost(8_000L)
    }

    @Test
    fun `next delay is null when attempts are exhausted`() {
        assertThat(policy.nextDelayMillis(3)).isNull()
        assertThat(policy.nextDelayMillis(4)).isNull()
    }

    @Test
    fun `next delay uses the first retry backoff after one attempt`() {
        val delay = policy.nextDelayMillis(1, random = FixedRandom(fixedDouble = 0.0))

        assertThat(delay).isEqualTo(640L)
    }

    @Test
    fun `retry after is honored when larger than backoff`() {
        val delay = policy.nextDelayMillis(1, retryAfterSeconds = 5, random = FixedRandom(fixedDouble = 0.0))

        assertThat(delay).isEqualTo(5_000L)
    }

    @Test
    fun `backoff wins when retry after is smaller`() {
        val delay = policy.nextDelayMillis(2, retryAfterSeconds = 1, random = FixedRandom(fixedDouble = 1.0))

        assertThat(delay).isEqualTo(1_920L)
    }

    @Test
    fun `negative retry after is treated as zero`() {
        val delay = policy.nextDelayMillis(1, retryAfterSeconds = -10, random = FixedRandom(fixedDouble = 0.0))

        assertThat(delay).isEqualTo(640L)
    }

    @Test
    fun `custom policy validates its bounds`() {
        assertThat(runCatching { RetryPolicy(maxAttempts = 0) }.isFailure).isTrue()
        assertThat(runCatching { RetryPolicy(baseDelayMillis = 0) }.isFailure).isTrue()
        assertThat(runCatching { RetryPolicy(baseDelayMillis = 1_000, maxDelayMillis = 500) }.isFailure).isTrue()
        assertThat(runCatching { RetryPolicy(jitterFraction = 1.5) }.isFailure).isTrue()
    }
}
