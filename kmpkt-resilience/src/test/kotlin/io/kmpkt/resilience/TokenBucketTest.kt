package io.kmpkt.resilience

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class TokenBucketTest :
    FunSpec({
        test("tryAcquire consumes burst capacity and refills over time") {
            var now = Instant.parse("2026-01-01T00:00:00Z")
            val clock =
                object : Clock() {
                    override fun getZone(): ZoneId = ZoneOffset.UTC

                    override fun withZone(zone: ZoneId): Clock = this

                    override fun instant(): Instant = now
                }
            val bucket = TokenBucket(capacity = 2, refillTokens = 1, refillPeriod = 1.seconds, clock = clock)

            bucket.tryAcquire() shouldBe true
            bucket.tryAcquire() shouldBe true
            bucket.tryAcquire() shouldBe false

            now = now.plusMillis(1000)
            bucket.tryAcquire() shouldBe true
            bucket.tryAcquire() shouldBe false
        }

        test("acquire suspends until tokens refill") {
            val bucket = TokenBucket(capacity = 1, refillTokens = 1, refillPeriod = 20.milliseconds)
            bucket.tryAcquire() shouldBe true

            val acquired = async { bucket.acquire() }
            delay(5.milliseconds)
            acquired.isCompleted shouldBe false

            acquired.await()
            acquired.isCompleted shouldBe true
        }

        test("validates configuration and permit counts") {
            shouldThrow<IllegalArgumentException> { TokenBucket(capacity = 0, refillTokens = 1, refillPeriod = 1.seconds) }
            shouldThrow<IllegalArgumentException> { TokenBucket(capacity = 1, refillTokens = 0, refillPeriod = 1.seconds) }
            shouldThrow<IllegalArgumentException> { TokenBucket(capacity = 1, refillTokens = 1, refillPeriod = 0.seconds) }

            val bucket = TokenBucket(capacity = 2, refillTokens = 1, refillPeriod = 1.seconds)
            shouldThrow<IllegalArgumentException> { bucket.tryAcquire(0) }
            shouldThrow<IllegalArgumentException> { bucket.tryAcquire(3) }
            shouldThrow<IllegalArgumentException> { bucket.acquire(0) }
        }
    })
