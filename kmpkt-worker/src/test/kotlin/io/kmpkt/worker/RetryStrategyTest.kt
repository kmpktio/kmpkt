package io.kmpkt.worker

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RetryStrategyTest :
    FunSpec({
        test("none gives up on the first failure") {
            // Act + Assert
            RetryStrategy.none().delayBefore(1).shouldBeNull()
        }

        test("fixed waits the same every time, then gives up") {
            // Arrange
            val retry = RetryStrategy.fixed(times = 2, delay = 50.milliseconds)

            // Act + Assert
            retry.delayBefore(1) shouldBe 50.milliseconds
            retry.delayBefore(2) shouldBe 50.milliseconds
            retry.delayBefore(3).shouldBeNull()
        }

        test("backOff doubles the wait from the initial one") {
            // Arrange
            val retry = RetryStrategy.backOff(times = 4, initial = 1.seconds, factor = 2.0, max = 1.seconds * 100)

            // Act + Assert - the first retry waits the initial delay, not twice it
            retry.delayBefore(1) shouldBe 1.seconds
            retry.delayBefore(2) shouldBe 2.seconds
            retry.delayBefore(3) shouldBe 4.seconds
            retry.delayBefore(4) shouldBe 8.seconds
        }

        test("backOff never waits longer than max") {
            // Arrange
            val retry = RetryStrategy.backOff(times = 10, initial = 1.seconds, factor = 10.0, max = 30.seconds)

            // Act + Assert
            retry.delayBefore(1) shouldBe 1.seconds
            retry.delayBefore(2) shouldBe 10.seconds
            retry.delayBefore(3) shouldBe 30.seconds
            retry.delayBefore(9) shouldBe 30.seconds
        }

        test("backOff gives up after its allowance") {
            // Arrange
            val retry = RetryStrategy.backOff(times = 3)

            // Act + Assert
            retry.delayBefore(3) shouldBe 4.seconds
            retry.delayBefore(4).shouldBeNull()
        }

        test("forever never gives up") {
            // Arrange
            val retry = RetryStrategy.forever(delay = 5.seconds)

            // Act + Assert
            retry.delayBefore(1) shouldBe 5.seconds
            retry.delayBefore(10_000) shouldBe 5.seconds
        }
    })
