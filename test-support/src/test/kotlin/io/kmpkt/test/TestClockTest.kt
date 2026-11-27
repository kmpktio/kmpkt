package io.kmpkt.test

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.ZoneId
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class TestClockTest :
    FunSpec({
        test("returns initial instant until advanced manually") {
            val clock = TestClock()

            clock.instant() shouldBe TestClock.DEFAULT_INSTANT
            clock.advance(5.seconds)
            clock.instant() shouldBe TestClock.DEFAULT_INSTANT.plusSeconds(5)
        }

        test("advances on every instant read when autoAdvance is configured") {
            val clock = TestClock(autoAdvance = 250.milliseconds)

            clock.instant() shouldBe TestClock.DEFAULT_INSTANT
            clock.instant() shouldBe TestClock.DEFAULT_INSTANT.plusMillis(250)
            clock.instant() shouldBe TestClock.DEFAULT_INSTANT.plusMillis(500)
        }

        test("preserves current instant and step when changing zone") {
            val clock = TestClock()
            clock.advance(10.seconds)
            val berlin = ZoneId.of("Europe/Berlin")

            val zoned = clock.withZone(berlin)

            zoned.zone shouldBe berlin
            zoned.instant() shouldBe TestClock.DEFAULT_INSTANT.plusSeconds(10)
        }
    })
