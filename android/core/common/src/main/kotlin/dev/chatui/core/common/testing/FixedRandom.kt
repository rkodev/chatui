package dev.chatui.core.common.testing

import kotlin.random.Random

/**
 * Deterministic [Random] for retry/backoff tests.
 *
 * [bits] is returned for every `nextBits` call. Use `0` for the low end of a
 * jitter range and `-1` for the high end. When [fixedDouble] is set,
 * `nextDouble` returns it exactly, which makes jitter boundary tests precise.
 */
class FixedRandom(
    private val bits: Int = 0,
    private val fixedDouble: Double? = null,
) : Random() {
    override fun nextBits(bitCount: Int): Int =
        if (bitCount == 0) 0 else bits ushr (32 - bitCount)

    override fun nextDouble(): Double = fixedDouble ?: super.nextDouble()
}
