package io.kmpkt.messenger.retry

import io.kmpkt.messenger.fixture.Greet
import io.kmpkt.messenger.handler.exception.HandlerFailedException
import io.kmpkt.messenger.message.Envelope
import io.kmpkt.messenger.stamp.RedeliveryStamp
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private class Lost :
    RuntimeException("lost the race"),
    RecoverableException

private class Invalid :
    RuntimeException("will never work"),
    UnrecoverableException

private val message = Greet("Milch")
private val fresh = Envelope(message)

private fun failed(vararg failures: Throwable) = HandlerFailedException(message, *failures)

class RetryDeciderTest :
    FunSpec({
        test("a failure says for itself whether it is worth retrying, or says nothing") {
            RetryDecider.decideFrom(Lost()) shouldBe true
            RetryDecider.decideFrom(Invalid()) shouldBe false
            RetryDecider.decideFrom(RuntimeException()).shouldBeNull()
        }

        test("a handler failure is judged by what the handlers threw") {
            RetryDecider.decideFrom(failed(RuntimeException(), Lost())) shouldBe true
            RetryDecider.decideFrom(failed(Invalid(), Lost())) shouldBe true
            RetryDecider.decideFrom(failed(Invalid(), Invalid())) shouldBe false
            RetryDecider.decideFrom(failed(Invalid(), RuntimeException())).shouldBeNull()
            RetryDecider.decideFrom(failed()).shouldBeNull()
        }

        test("for a queued message the failure's word comes first and the strategy's only when it has none") {
            // Arrange
            val never = MultiplierRetryStrategy(maxRetries = 0)
            val always = MultiplierRetryStrategy(maxRetries = 1)

            // Act + Assert
            RetryDecider.shouldRetry(Lost(), fresh, never) shouldBe true
            RetryDecider.shouldRetry(Invalid(), fresh, always) shouldBe false
            RetryDecider.shouldRetry(RuntimeException(), fresh, always) shouldBe true
            RetryDecider.shouldRetry(RuntimeException(), fresh, never) shouldBe false
        }
    })

/** Always the same number, so a jittered wait can be written down. */
private class FixedRandom(
    private val value: Double,
) : Random() {
    override fun nextBits(bitCount: Int): Int = 0

    override fun nextDouble(): Double = value
}

class MultiplierRetryStrategyTest :
    FunSpec({
        test("retries any failure until the envelope has been redelivered as often as allowed, three times by default") {
            // Arrange
            val strategy = MultiplierRetryStrategy(maxRetries = 2)

            // Act + Assert
            strategy.isRetryable(fresh, RuntimeException()) shouldBe true
            strategy.isRetryable(fresh.with(RedeliveryStamp(1)), RuntimeException()) shouldBe true
            strategy.isRetryable(fresh.with(RedeliveryStamp(1), RedeliveryStamp(2)), RuntimeException()) shouldBe false
            MultiplierRetryStrategy().isRetryable(fresh.with(RedeliveryStamp(2)), RuntimeException()) shouldBe true
            MultiplierRetryStrategy().isRetryable(fresh.with(RedeliveryStamp(3)), RuntimeException()) shouldBe false
        }

        test("waits the delay first, then the multiplier times as long each retry, up to the maximum") {
            // Arrange
            val strategy = MultiplierRetryStrategy(delay = 2.seconds, multiplier = 3.0, maxDelay = 10.seconds, jitter = 0.0)
            val unlimited = MultiplierRetryStrategy(delay = 2.seconds, multiplier = 3.0, maxDelay = Duration.ZERO, jitter = 0.0)

            // Act + Assert
            strategy.waitingTime(fresh, RuntimeException()) shouldBe 2.seconds
            strategy.waitingTime(fresh.with(RedeliveryStamp(1)), RuntimeException()) shouldBe 6.seconds
            strategy.waitingTime(fresh.with(RedeliveryStamp(2)), RuntimeException()) shouldBe 10.seconds
            unlimited.waitingTime(fresh.with(RedeliveryStamp(2)), RuntimeException()) shouldBe 18.seconds
        }

        test("by default doubles from one second and stops growing at ten") {
            // Arrange
            val strategy = MultiplierRetryStrategy(jitter = 0.0)

            // Act + Assert
            strategy.waitingTime(fresh, RuntimeException()) shouldBe 1.seconds
            strategy.waitingTime(fresh.with(RedeliveryStamp(1)), RuntimeException()) shouldBe 2.seconds
            strategy.waitingTime(fresh.with(RedeliveryStamp(3)), RuntimeException()) shouldBe 8.seconds
            strategy.waitingTime(fresh.with(RedeliveryStamp(4)), RuntimeException()) shouldBe 10.seconds
        }

        test("jitter spreads the wait by up to its fraction either way, and never past the maximum") {
            // Arrange
            fun strategy(random: Double) =
                MultiplierRetryStrategy(delay = 10.seconds, maxDelay = 10.5.seconds, jitter = 0.1, random = FixedRandom(random))

            // Act + Assert
            strategy(0.0).waitingTime(fresh, RuntimeException()) shouldBe 9.seconds
            strategy(0.5).waitingTime(fresh, RuntimeException()) shouldBe 10.seconds
            strategy(0.75).waitingTime(fresh, RuntimeException()) shouldBe 10.5.seconds
            strategy(1.0).waitingTime(fresh, RuntimeException()) shouldBe 10.5.seconds
        }

        test("a real random stays within the jitter") {
            // Arrange
            val strategy = MultiplierRetryStrategy(delay = 10.seconds, maxDelay = Duration.ZERO, jitter = 0.2)

            // Act
            val waits = List(200) { strategy.waitingTime(fresh, RuntimeException()) }

            // Assert
            waits.all { it >= 8.seconds && it <= 12.seconds } shouldBe true
            waits.distinct().size shouldBe 200
        }

        test("refuses what makes no sense as a schedule") {
            shouldThrow<IllegalArgumentException> { MultiplierRetryStrategy(maxRetries = -1) }
            shouldThrow<IllegalArgumentException> { MultiplierRetryStrategy(delay = (-1).seconds) }
            shouldThrow<IllegalArgumentException> { MultiplierRetryStrategy(multiplier = 0.5) }
            shouldThrow<IllegalArgumentException> { MultiplierRetryStrategy(maxDelay = (-1).seconds) }
            shouldThrow<IllegalArgumentException> { MultiplierRetryStrategy(jitter = -0.1) }
            shouldThrow<IllegalArgumentException> { MultiplierRetryStrategy(jitter = 1.1) }
            MultiplierRetryStrategy(maxRetries = 0, delay = Duration.ZERO, multiplier = 1.0, maxDelay = Duration.ZERO, jitter = 1.0)
        }
    })
