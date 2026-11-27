package io.kmpkt.test

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * Controllable [Clock] for deterministic unit tests.
 *
 * Advances manually via [advance] or automatically by [autoAdvance] on every [instant] lookup.
 */
public class TestClock(
    initial: Instant = DEFAULT_INSTANT,
    private val autoAdvance: Duration = Duration.ZERO,
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    private var current: Instant = initial

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = TestClock(current, autoAdvance, zone)

    override fun instant(): Instant =
        current.also {
            if (autoAdvance != Duration.ZERO) {
                current = current.plus(autoAdvance.toJavaDuration())
            }
        }

    public fun advance(by: Duration) {
        current = current.plus(by.toJavaDuration())
    }

    public companion object {
        public val DEFAULT_INSTANT: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }
}
