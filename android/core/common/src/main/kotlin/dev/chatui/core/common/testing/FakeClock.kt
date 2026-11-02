package dev.chatui.core.common.testing

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Mutable [Clock] for tests in later modules.
 *
 * Defaults to a fixed instant so tests do not depend on wall-clock time.
 */
class FakeClock(
    private var current: Instant = Instant.parse("2026-01-01T00:00:00Z"),
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = current

    fun set(instant: Instant) {
        current = instant
    }

    fun advanceBy(duration: Duration) {
        current = current.plus(duration)
    }
}
